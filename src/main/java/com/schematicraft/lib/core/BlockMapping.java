package com.schematicraft.lib.core;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * A single block replacement mapping within a palette.
 * Maps an original block name to a replacement block name,
 * optionally with property overrides.
 */
public record BlockMapping(
        String id,
        String original,
        String replacement,
        @Nullable Map<String, String> originalProperties,
        @Nullable Map<String, String> replacementProperties,
        boolean isValid
) {
    /** Convenience constructor for simple name-to-name mappings. */
    public BlockMapping(String original, String replacement) {
        this("mapping-" + original.hashCode(), original, replacement, null, null, true);
    }
}
