package com.schematicraft.lib.core;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A palette from the Schematicraft API.
 * Contains block mappings that can be applied to schematics
 * to swap blocks (e.g., oak to dark oak).
 */
public record PaletteEntry(
        String id,
        String name,
        String createdBy,
        @Nullable String schematicId,
        List<BlockMapping> mappings,
        String visibility,
        String scope,
        int revision
) {
    /** Check if this palette has any mappings. */
    public boolean isEmpty() {
        return mappings == null || mappings.isEmpty();
    }

    /** Get the number of block replacements in this palette. */
    public int mappingCount() {
        return mappings != null ? mappings.size() : 0;
    }
}
