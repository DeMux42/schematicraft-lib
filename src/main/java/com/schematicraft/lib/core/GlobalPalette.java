package com.schematicraft.lib.core;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Persistent global palette for quick-access block favorites.
 * Stored as a template-scope palette on the API (synced across devices).
 * Cached locally in config for offline access.
 *
 * The global palette is a single palette named "__global__" that the user
 * can pin favorite blocks to. It appears as a quick-access row in the
 * palette editor.
 */
public class GlobalPalette {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GLOBAL_PALETTE_NAME = "__global_favorites__";
    private static final Path CACHE_FILE = Path.of("config/schematicraft_global_palette.json");

    private static final GlobalPalette INSTANCE = new GlobalPalette();
    public static GlobalPalette get() { return INSTANCE; }

    private List<String> favoriteBlocks = new ArrayList<>();
    private String remoteId = null;
    private boolean loaded = false;
    private boolean loading = false;

    private GlobalPalette() {}

    public List<String> getFavorites() {
        return Collections.unmodifiableList(favoriteBlocks);
    }

    public boolean hasFavorite(String blockName) {
        return favoriteBlocks.contains(blockName);
    }

    public void addFavorite(String blockName) {
        if (!favoriteBlocks.contains(blockName)) {
            favoriteBlocks.add(blockName);
            saveLocal();
            syncToRemote();
        }
    }

    public void removeFavorite(String blockName) {
        if (favoriteBlocks.remove(blockName)) {
            saveLocal();
            syncToRemote();
        }
    }

    public boolean isLoaded() { return loaded; }

    /**
     * Load the global palette. Tries local cache first, then fetches from API.
     */
    public void ensureLoaded() {
        if (loaded || loading) return;
        loading = true;

        // Try local cache first
        if (loadLocal()) {
            loaded = true;
            loading = false;
            LOGGER.info("Global palette loaded from cache: {} favorites", favoriteBlocks.size());
        }

        // Also fetch from API to sync
        if (ModConfig.hasApiKey()) {
            fetchFromRemote();
        } else {
            loading = false;
            loaded = true;
        }
    }

    private boolean loadLocal() {
        try {
            if (!Files.exists(CACHE_FILE)) return false;
            String json = Files.readString(CACHE_FILE);
            var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (root.has("remoteId")) remoteId = root.get("remoteId").getAsString();
            if (root.has("blocks")) {
                favoriteBlocks.clear();
                for (var el : root.getAsJsonArray("blocks")) {
                    favoriteBlocks.add(el.getAsString());
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.debug("Failed to load global palette cache: {}", e.getMessage());
            return false;
        }
    }

    private void saveLocal() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            StringBuilder json = new StringBuilder("{");
            if (remoteId != null) json.append("\"remoteId\":\"").append(remoteId).append("\",");
            json.append("\"blocks\":[");
            for (int i = 0; i < favoriteBlocks.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(favoriteBlocks.get(i).replace("\"", "\\\"")).append("\"");
            }
            json.append("]}");
            Files.writeString(CACHE_FILE, json.toString());
        } catch (IOException e) {
            LOGGER.debug("Failed to save global palette cache: {}", e.getMessage());
        }
    }

    private void fetchFromRemote() {
        SchematiCraftAPIWrapper.get().loadPalettes(null).thenAccept(palettes -> {
            // Find the global palette by name
            for (PaletteEntry p : palettes) {
                if (GLOBAL_PALETTE_NAME.equals(p.name())) {
                    remoteId = p.id();
                    // Merge remote favorites with local
                    for (BlockMapping m : p.mappings()) {
                        if (!favoriteBlocks.contains(m.original())) {
                            favoriteBlocks.add(m.original());
                        }
                    }
                    saveLocal();
                    break;
                }
            }
            loaded = true;
            loading = false;
        }).exceptionally(ex -> {
            LOGGER.debug("Failed to fetch global palette from API: {}",
                    SchematiCraftAPIWrapper.rootMessage(ex));
            loaded = true;
            loading = false;
            return null;
        });
    }

    private void syncToRemote() {
        if (!ModConfig.hasApiKey()) return;

        // Convert favorites to mappings (original = favorite block, replacement = same)
        List<BlockMapping> mappings = new ArrayList<>();
        for (String block : favoriteBlocks) {
            mappings.add(new BlockMapping(block, block));
        }

        if (remoteId != null) {
            // Update existing
            // For simplicity, delete and recreate (avoids revision tracking)
            SchematiCraftAPIWrapper.get().deletePalette(remoteId).thenRun(() -> {
                SchematiCraftAPIWrapper.get().createPalette(GLOBAL_PALETTE_NAME, mappings, null)
                        .thenAccept(p -> { remoteId = p.id(); saveLocal(); });
            });
        } else {
            // Create new
            SchematiCraftAPIWrapper.get().createPalette(GLOBAL_PALETTE_NAME, mappings, null)
                    .thenAccept(p -> { remoteId = p.id(); saveLocal(); });
        }
    }
}
