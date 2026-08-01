package com.schematicraft.lib.core;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Portable JSON parser for Schematicraft API responses. No MC dependencies.
 * Extracts typed data from raw JSON strings returned by the API client.
 */
public class ApiJsonParser {

    public static LibraryData parseLibrary(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<BundleEntry> bundles = new ArrayList<>();
        List<SchematicEntry> unbundled = new ArrayList<>();

        JsonArray bundlesArr = root.getAsJsonArray("bundles");
        if (bundlesArr != null) {
            for (JsonElement be : bundlesArr) {
                JsonObject b = be.getAsJsonObject();
                List<SchematicEntry> schems = new ArrayList<>();
                JsonArray schemsArr = b.getAsJsonArray("schematics");
                if (schemsArr != null) {
                    for (JsonElement se : schemsArr) {
                        schems.add(parseSchematic(se.getAsJsonObject()));
                    }
                }
                bundles.add(new BundleEntry(
                        str(b, "id"), str(b, "name"),
                        str(b, "description"), schems));
            }
        }

        JsonArray unbundledArr = root.getAsJsonArray("unbundled");
        if (unbundledArr != null) {
            for (JsonElement se : unbundledArr) {
                unbundled.add(parseSchematic(se.getAsJsonObject()));
            }
        }

        return new LibraryData(bundles, unbundled);
    }

    /** One page of search results, with the paging info needed to fetch more. */
    public record SearchPage(List<SchematicEntry> results, int page,
                            boolean hasMore, int total) {}

    /**
     * Parses the paged search response used by the Discover feed.
     *
     * Paging info stays on the page, not on each result, so callers can decide
     * whether to fetch more without inspecting the list.
     */
    public static SearchPage parseSearchPage(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<SchematicEntry> out = new ArrayList<>();

        JsonArray arr = root.getAsJsonArray("results");
        if (arr != null) {
            for (JsonElement re : arr) {
                if (!re.isJsonObject()) continue;
                JsonObject r = re.getAsJsonObject();
                out.add(new SchematicEntry(
                        str(r, "id"),
                        str(r, "title"),
                        str(r, "description"),
                        ownerName(r),
                        str(r, "thumbnailUrl"),
                        intVal(r, "downloads"),
                        true, // search only returns published schematics
                        intVal(r, "blockCount"),
                        dim(r, "width"), dim(r, "height"), dim(r, "length")));
            }
        }

        return new SearchPage(out,
                root.has("page") ? intVal(root, "page") : 1,
                boolVal(root, "hasMore"),
                intVal(root, "total"));
    }

    /** Owner username from the nested owner object, when present. */
    private static String ownerName(JsonObject obj) {
        if (obj.has("owner") && obj.get("owner").isJsonObject()) {
            return str(obj.getAsJsonObject("owner"), "username");
        }
        return str(obj, "ownerName");
    }

    public static String parseStatusTier(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("user")) {
            JsonObject user = root.getAsJsonObject("user");
            return str(user, "tier");
        }
        return "unknown";
    }

    public static String parseBundleId(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return str(root, "id");
    }

    private static SchematicEntry parseSchematic(JsonObject obj) {
        return new SchematicEntry(
                str(obj, "id"), str(obj, "title"),
                str(obj, "description"), null,
                str(obj, "thumbnailUrl"),
                intVal(obj, "downloadCount"),
                boolVal(obj, "isPublished"),
                intVal(obj, "blockCount"),
                dim(obj, "width"), dim(obj, "height"), dim(obj, "length"));
    }

    /** Reads a dimension out of the nested "dimensions" object. */
    private static int dim(JsonObject obj, String key) {
        if (obj.has("dimensions") && obj.get("dimensions").isJsonObject()) {
            return intVal(obj.getAsJsonObject("dimensions"), key);
        }
        return 0;
    }

    private static String str(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key).getAsString();
        return null;
    }

    private static int intVal(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key).getAsInt();
        return 0;
    }

    private static boolean boolVal(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key).getAsBoolean();
        return false;
    }

    public record LibraryData(List<BundleEntry> bundles, List<SchematicEntry> unbundled) {}

    // --- Palette parsing ---

    public static List<PaletteEntry> parsePalettes(String json) {
        List<PaletteEntry> palettes = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);

        // Handle both array response and object-with-array response
        JsonArray arr;
        if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("palettes")) {
            arr = root.getAsJsonObject().getAsJsonArray("palettes");
        } else {
            arr = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        }

        for (JsonElement pe : arr) {
            palettes.add(parsePalette(pe.getAsJsonObject()));
        }
        return palettes;
    }

    public static PaletteEntry parseSinglePalette(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return parsePalette(obj);
    }

    private static PaletteEntry parsePalette(JsonObject obj) {
        List<BlockMapping> mappings = new ArrayList<>();
        JsonArray mappingsArr = obj.getAsJsonArray("mappings");
        if (mappingsArr != null) {
            for (JsonElement me : mappingsArr) {
                JsonObject m = me.getAsJsonObject();
                mappings.add(new BlockMapping(
                        str(m, "id") != null ? str(m, "id") : "m-" + mappings.size(),
                        str(m, "original"),
                        str(m, "replacement"),
                        null, null,
                        !m.has("isValid") || boolVal(m, "isValid")
                ));
            }
        }

        return new PaletteEntry(
                str(obj, "id"),
                str(obj, "name"),
                str(obj, "createdBy"),
                str(obj, "schematicId"),
                mappings,
                str(obj, "visibility") != null ? str(obj, "visibility") : "private",
                str(obj, "scope") != null ? str(obj, "scope") : "template",
                intVal(obj, "revision")
        );
    }
}
