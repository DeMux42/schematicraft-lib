package com.schematicraft.lib;

import com.schematicraft.lib.config.ModConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Shared Schematicraft library. Not a mod itself.
 * Editor mods call init() from their own mod constructor.
 */
public class SchematiCraftLib {
    public static final String MODID = "schematicraft_lib";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("Schematicraft Lib initializing");
        ModConfig.init();
    }
}
