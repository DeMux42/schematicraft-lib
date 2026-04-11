package com.schematicraft.lib.config;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Simple file-based config for API key and server URL.
 * Stored in .minecraft/config/schematicraft.properties
 */
public class ModConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_FILE = "config/schematicraft.properties";
    private static String apiKey = "";
    private static String serverUrl = "https://schematicraft.com";

    public static void init() {
        load();
    }

    public static String getApiKey() {
        return apiKey;
    }

    public static void setApiKey(String key) {
        apiKey = key != null ? key.trim() : "";
        save();
    }

    public static String getServerUrl() {
        return serverUrl;
    }

    public static boolean hasApiKey() {
        return !apiKey.isEmpty() && apiKey.startsWith("sk_");
    }

    private static void load() {
        Path path = Path.of(CONFIG_FILE);
        if (!Files.exists(path)) return;

        try {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(path));
            apiKey = props.getProperty("api_key", "");
            serverUrl = props.getProperty("server_url", "https://schematicraft.com");
        } catch (IOException e) {
            LOGGER.warn("Failed to load config: {}", e.getMessage());
        }
    }

    private static void save() {
        Path path = Path.of(CONFIG_FILE);
        try {
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            props.setProperty("api_key", apiKey);
            props.setProperty("server_url", serverUrl);
            props.store(Files.newBufferedWriter(path), "Schematicraft Config");
        } catch (IOException e) {
            LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }
}
