package com.schematicraft.lib.client.preview;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses BG2 JSON schematic data into PreviewBlockData using only vanilla MC classes.
 * No BG2 dependency. Reads the SNBT structure: {blockstatemap:[...], statelist:[...]}
 *
 * Also supports applying palette mappings (block name swaps) to produce
 * modified PreviewBlockData for palette preview rendering.
 */
public class PreviewDataParser {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Parse BG2 JSON bytes into PreviewBlockData.
     * Format: {"statePosArrayList": "<SNBT>"}
     * SNBT: {blockstatemap:[{Name:"...",Properties:{...}},...], statelist:[{pos:[x,y,z],state:N},...]}
     */
    @Nullable
    public static PreviewBlockData parse(byte[] bg2JsonData) {
        try {
            String json = new String(bg2JsonData);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("statePosArrayList")) {
                LOGGER.debug("No statePosArrayList in data");
                return null;
            }

            String snbt = root.get("statePosArrayList").getAsString();
            CompoundTag nbt = TagParser.parseTag(snbt);

            // Parse blockstatemap (palette)
            ListTag paletteList = nbt.getList("blockstatemap", Tag.TAG_COMPOUND);
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < paletteList.size(); i++) {
                CompoundTag entry = paletteList.getCompound(i);
                BlockState state = parseBlockState(entry);
                palette.add(state);
            }

            // Parse statelist (positions + palette indices)
            ListTag stateList = nbt.getList("statelist", Tag.TAG_COMPOUND);
            List<PreviewBlockData.BlockEntry> blocks = new ArrayList<>();

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (int i = 0; i < stateList.size(); i++) {
                CompoundTag entry = stateList.getCompound(i);

                // Position
                int[] pos;
                if (entry.contains("pos")) {
                    var posTag = entry.get("pos");
                    if (posTag instanceof ListTag posList) {
                        pos = new int[]{posList.getInt(0), posList.getInt(1), posList.getInt(2)};
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }

                // State index
                int stateIdx = entry.getInt("state");
                if (stateIdx < 0 || stateIdx >= palette.size()) continue;

                BlockState state = palette.get(stateIdx);
                if (state.isAir()) continue;

                BlockPos blockPos = new BlockPos(pos[0], pos[1], pos[2]);
                blocks.add(new PreviewBlockData.BlockEntry(blockPos, state));

                // Track bounds
                if (pos[0] < minX) minX = pos[0];
                if (pos[1] < minY) minY = pos[1];
                if (pos[2] < minZ) minZ = pos[2];
                if (pos[0] > maxX) maxX = pos[0];
                if (pos[1] > maxY) maxY = pos[1];
                if (pos[2] > maxZ) maxZ = pos[2];
            }

            if (blocks.isEmpty()) return null;

            float midX = (minX + maxX) / 2f;
            float midY = (minY + maxY) / 2f;
            float midZ = (minZ + maxZ) / 2f;
            float maxExtent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ) + 1;

            LOGGER.debug("Parsed preview data: {} blocks, extent={}", blocks.size(), maxExtent);
            return new PreviewBlockData(blocks, midX, midY, midZ, maxExtent);

        } catch (Exception e) {
            LOGGER.warn("Failed to parse preview data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Apply palette mappings to PreviewBlockData, producing a new instance
     * with block states swapped according to the mappings.
     */
    public static PreviewBlockData applyPalette(PreviewBlockData original,
                                                 Map<String, String> mappings) {
        if (original == null || original.isEmpty() || mappings.isEmpty()) return original;

        List<PreviewBlockData.BlockEntry> newBlocks = new ArrayList<>(original.blocks().size());
        for (PreviewBlockData.BlockEntry entry : original.blocks()) {
            BlockState state = entry.state();
            String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

            String replacement = mappings.get(blockName);
            if (replacement != null) {
                BlockState newState = resolveBlockState(replacement, state);
                newBlocks.add(new PreviewBlockData.BlockEntry(entry.pos(), newState));
            } else {
                newBlocks.add(entry);
            }
        }

        return new PreviewBlockData(newBlocks, original.midX(), original.midY(),
                original.midZ(), original.maxExtent());
    }

    /**
     * Parse a BlockState from a BG2 blockstatemap NBT entry.
     * Format: {Name:"minecraft:stone", Properties:{variant:"smooth"}}
     */
    private static BlockState parseBlockState(CompoundTag entry) {
        String name = entry.getString("Name");
        if (name.isEmpty()) return Blocks.AIR.defaultBlockState();

        Block block = BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.tryParse(name));
        if (block == null || block == Blocks.AIR) {
            // Try without namespace
            if (!name.contains(":")) {
                block = BuiltInRegistries.BLOCK.get(
                        net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + name));
            }
            if (block == null) return Blocks.AIR.defaultBlockState();
        }

        BlockState state = block.defaultBlockState();

        // Apply properties if present
        if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
            CompoundTag props = entry.getCompound("Properties");
            for (String key : props.getAllKeys()) {
                String value = props.getString(key);
                var property = block.getStateDefinition().getProperty(key);
                if (property != null) {
                    state = applyProperty(state, property, value);
                }
            }
        }

        return state;
    }

    /**
     * Resolve a block name to a BlockState, carrying over properties from the original
     * where applicable.
     */
    private static BlockState resolveBlockState(String blockName, BlockState original) {
        Block block = BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.tryParse(blockName));
        if (block == null || block == Blocks.AIR) return original;

        BlockState state = block.defaultBlockState();

        // Try to carry over compatible properties from the original
        for (var prop : original.getProperties()) {
            var targetProp = block.getStateDefinition().getProperty(prop.getName());
            if (targetProp != null) {
                state = copyProperty(state, original, prop, targetProp);
            }
        }

        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state,
                                             net.minecraft.world.level.block.state.properties.Property property,
                                             String value) {
        var optional = property.getValue(value);
        if (optional.isPresent()) {
            return state.setValue(property, (Comparable) optional.get());
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState copyProperty(BlockState target, BlockState source,
                                            net.minecraft.world.level.block.state.properties.Property sourceProp,
                                            net.minecraft.world.level.block.state.properties.Property targetProp) {
        try {
            Comparable value = source.getValue(sourceProp);
            if (targetProp.getPossibleValues().contains(value)) {
                return target.setValue(targetProp, value);
            }
        } catch (Exception ignored) {}
        return target;
    }
}
