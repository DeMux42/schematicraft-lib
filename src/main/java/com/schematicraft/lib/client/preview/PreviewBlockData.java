package com.schematicraft.lib.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Universal block data for 3D preview rendering.
 * Format-agnostic: just positions and block states.
 * Parsed from BG2 JSON (the universal preview format from the server).
 */
public record PreviewBlockData(
        List<BlockEntry> blocks,
        float midX, float midY, float midZ,
        float maxExtent
) {
    public record BlockEntry(BlockPos pos, BlockState state) {}

    public boolean isEmpty() { return blocks == null || blocks.isEmpty(); }
    public int blockCount() { return blocks != null ? blocks.size() : 0; }
}
