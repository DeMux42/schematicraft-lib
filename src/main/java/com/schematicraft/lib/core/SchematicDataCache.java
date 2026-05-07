package com.schematicraft.lib.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LRU cache for downloaded schematic data (raw BG2 JSON bytes).
 * Avoids re-downloading the same schematic for palette editing or repeated loads.
 * Keyed by schematic ID. Evicts oldest entries when capacity is exceeded.
 *
 * Also provides block list extraction from cached BG2 JSON data.
 */
public class SchematicDataCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ENTRIES = 10;
    private static final SchematicDataCache INSTANCE = new SchematicDataCache();

    public static SchematicDataCache get() { return INSTANCE; }

    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private SchematicDataCache() {}

    /** Store downloaded schematic data in the cache. */
    public void put(String schematicId, byte[] data) {
        cache.put(schematicId, data);
        LOGGER.debug("Cached schematic {}: {} bytes", schematicId, data.length);
    }

    /** Get cached data for a schematic, or null if not cached. */
    @Nullable
    public byte[] get(String schematicId) {
        return cache.get(schematicId);
    }

    /** Check if a schematic is cached. */
    public boolean has(String schematicId) {
        return cache.containsKey(schematicId);
    }

    /** Remove a specific entry (e.g., after palette download replaces it). */
    public void invalidate(String schematicId) {
        cache.remove(schematicId);
    }

    /** Clear the entire cache. */
    public void clear() {
        cache.clear();
    }

    /**
     * Extract the unique block names from cached BG2 JSON data.
     * Parses the blockstatemap from the statePosArrayList NBT string.
     *
     * BG2 JSON format: {"statePosArrayList": "<SNBT>"}
     * The SNBT contains: {blockstatemap: [{Name:"minecraft:stone",...}, ...], statelist: [...]}
     *
     * Returns the block names from blockstatemap, excluding air.
     */
    public List<String> extractBlockList(String schematicId) {
        byte[] data = get(schematicId);
        if (data == null) return List.of();
        return extractBlockListFromData(data);
    }

    /**
     * Extract block names from raw BG2 JSON bytes.
     */
    public static List<String> extractBlockListFromData(byte[] data) {
        List<String> blocks = new ArrayList<>();
        try {
            String json = new String(data);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("statePosArrayList")) {
                LOGGER.debug("No statePosArrayList in cached data");
                return blocks;
            }

            // The statePosArrayList is an SNBT string. We need to parse the block names
            // from it. The format is: {blockstatemap:[{Name:"minecraft:stone",Properties:{...}},...],...}
            // Since parsing full SNBT in Java without MC's TagParser is complex,
            // we'll use a simple regex approach to extract Name values.
            String snbt = root.get("statePosArrayList").getAsString();

            // Extract all Name:"..." values from the SNBT
            int searchFrom = 0;
            while (true) {
                int nameIdx = snbt.indexOf("Name:\"", searchFrom);
                if (nameIdx == -1) break;
                int start = nameIdx + 6; // after Name:"
                int end = snbt.indexOf("\"", start);
                if (end == -1) break;
                String blockName = snbt.substring(start, end);
                if (!blockName.equals("minecraft:air") && !blocks.contains(blockName)) {
                    blocks.add(blockName);
                }
                searchFrom = end + 1;
            }

            LOGGER.debug("Extracted {} unique blocks from schematic", blocks.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to extract block list: {}", e.getMessage());
        }
        return blocks;
    }
}
