package com.schematicraft.lib.core;

import javax.annotation.Nullable;

/**
 * Portable schematic metadata. No MC dependencies.
 * Represents a schematic from the API (library or search result).
 *
 * @param blockCount total blocks from server-side analysis, or 0 when unknown.
 *                   Editors cap how many blocks they can accept, so this is used
 *                   to warn or refuse before spending a download. Zero means
 *                   "not analyzed", never "empty", so callers must treat it as
 *                   unknown rather than as a small schematic.
 */
public record SchematicEntry(
        String id,
        String title,
        @Nullable String description,
        @Nullable String ownerName,
        @Nullable String thumbnailUrl,
        int downloadCount,
        boolean isPublished,
        int blockCount,
        int width,
        int height,
        int length
) {
    public SchematicEntry(String id, String title) {
        this(id, title, null, null, null, 0, false, 0, 0, 0, 0);
    }

    /** True when the server has reported a block count for this schematic. */
    public boolean hasBlockCount() {
        return blockCount > 0;
    }

    /** True when the server has reported a bounding box. */
    public boolean hasDimensions() {
        return width > 0 && height > 0 && length > 0;
    }

    /** Bounding box as "102x32x18", or null when unknown. */
    public String dimensionsLabel() {
        return hasDimensions() ? width + "x" + height + "x" + length : null;
    }
}
