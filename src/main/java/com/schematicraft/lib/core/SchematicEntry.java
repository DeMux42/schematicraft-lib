package com.schematicraft.lib.core;

import javax.annotation.Nullable;

/**
 * Portable schematic metadata. No MC dependencies.
 * Represents a schematic from the API (library or search result).
 */
public record SchematicEntry(
        String id,
        String title,
        @Nullable String description,
        @Nullable String ownerName,
        @Nullable String thumbnailUrl,
        int downloadCount,
        boolean isPublished
) {
    public SchematicEntry(String id, String title) {
        this(id, title, null, null, null, 0, false);
    }
}
