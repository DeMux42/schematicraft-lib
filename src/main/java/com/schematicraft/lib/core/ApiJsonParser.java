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

    public static List<SearchResultEntry> parseSearch(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<SearchResultEntry> results = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("results");
        if (arr != null) {
            for (JsonElement re : arr) {
                JsonObject r = re.getAsJsonObject();
                results.add(new SearchResultEntry(
                        new SchematicEntry(
                                str(r, "id"), str(r, "title"),
                                str(r, "description"), str(r, "ownerName"),
                                str(r, "thumbnailUrl"),
                                intVal(r, "downloadCount"),
                                false),
                        boolVal(root, "hasMore")));
            }
        }
        return results;
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
                boolVal(obj, "isPublished"));
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
    public record SearchResultEntry(SchematicEntry schematic, boolean hasMore) {}
}
