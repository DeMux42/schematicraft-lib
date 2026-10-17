package com.schematicraft.lib.core;

import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Portable bundle metadata. No MC dependencies.
 */
public record BundleEntry(
        String id,
        String name,
        @Nullable String description,
        List<SchematicEntry> schematics
) {}
