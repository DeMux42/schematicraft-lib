package com.schematicraft.lib;

import com.schematicraft.lib.config.ModConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Shared Schematicraft library mod.
 * Provides API client, library state, thumbnail cache, camera mode,
 * and UI widgets for all Schematicraft editor integrations.
 */
@Mod(SchematiCraftLib.MODID)
public class SchematiCraftLib {
    public static final String MODID = "schematicraft_lib";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SchematiCraftLib(IEventBus modEventBus) {
        LOGGER.info("Schematicraft Lib initializing");
        ModConfig.init();
    }
}
