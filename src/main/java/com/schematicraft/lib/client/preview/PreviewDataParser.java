package com.schematicraft.lib.client.preview;

import com.mojang.logging.LogUtils;

import net.minecraft.util.math.BlockPos;

import net.minecraft.registry.Registries;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import org.slf4j.Logger;

import org.jetbrains.annotations.Nullable;
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
            NbtCompound nbt = StringNbtReader.parse(snbt);

            // Parse blockstatemap (palette)
            NbtList paletteList = nbt.getList("blockstatemap", NbtElement.COMPOUND_TYPE);
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < paletteList.size(); i++) {
                NbtCompound entry = paletteList.getCompound(i);
                BlockState state = parseBlockState(entry);
                palette.add(state);
            }

            // Parse statelist (positions + palette indices)
            NbtList stateList = nbt.getList("statelist", NbtElement.COMPOUND_TYPE);
            List<PreviewBlockData.BlockEntry> blocks = new ArrayList<>();

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (int i = 0; i < stateList.size(); i++) {
                NbtCompound entry = stateList.getCompound(i);

                // Position
                int[] pos;
                if (entry.contains("pos")) {
                    var posTag = entry.get("pos");
                    if (posTag instanceof NbtList posList) {
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
            String blockName = Registries.BLOCK.getId(state.getBlock()).toString();

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
    private static BlockState parseBlockState(NbtCompound entry) {
        String name = entry.getString("Name");
        if (name.isEmpty()) return Blocks.AIR.getDefaultState();

        Block block = Registries.BLOCK.get(
                net.minecraft.util.Identifier.tryParse(name));
        if (block == null || block == Blocks.AIR) {
            // Try without namespace
            if (!name.contains(":")) {
                block = Registries.BLOCK.get(
                        net.minecraft.util.Identifier.tryParse("minecraft:" + name));
            }
            if (block == null) return Blocks.AIR.getDefaultState();
        }

        BlockState state = block.getDefaultState();

        // Apply properties if present
        if (entry.contains("Properties", NbtElement.COMPOUND_TYPE)) {
            NbtCompound props = entry.getCompound("Properties");
            for (String key : props.getKeys()) {
                String value = props.getString(key);
                var property = block.getStateManager().getProperty(key);
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
        Block block = Registries.BLOCK.get(
                net.minecraft.util.Identifier.tryParse(blockName));
        if (block == null || block == Blocks.AIR) return original;

        BlockState state = block.getDefaultState();

        // Try to carry over compatible properties from the original
        for (var prop : original.getProperties()) {
            var targetProp = block.getStateManager().getProperty(prop.getName());
            if (targetProp != null) {
                state = copyProperty(state, original, prop, targetProp);
            }
        }

        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state,
                                             net.minecraft.state.property.Property property,
                                             String value) {
        var optional = property.parse(value);
        if (optional.isPresent()) {
            return state.with(property, (Comparable) optional.get());
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState copyProperty(BlockState target, BlockState source,
                                            net.minecraft.state.property.Property sourceProp,
                                            net.minecraft.state.property.Property targetProp) {
        try {
            Comparable value = source.get(sourceProp);
            if (targetProp.getValues().contains(value)) {
                return target.with(targetProp, value);
            }
        } catch (Exception ignored) {}
        return target;
    }
}
