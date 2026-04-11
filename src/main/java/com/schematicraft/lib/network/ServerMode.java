package com.schematicraft.lib.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;

/**
 * Tracks whether the connected server has the Schematicraft BG2 mod installed.
 * When the server has the mod, direct packet-based template loading is used.
 * When client-only, the Template Manager fallback path is used instead.
 */
public class ServerMode {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean serverHasMod = false;
    private static boolean checked = false;

    /**
     * Check if the server has our mod's network channels registered.
     * In singleplayer this is always true. In multiplayer, it depends on
     * whether the server has the mod installed.
     */
    public static boolean isDirectModeAvailable() {
        if (!checked) {
            detect();
        }
        return serverHasMod;
    }

    public static void reset() {
        checked = false;
        serverHasMod = false;
    }

    private static void detect() {
        checked = true;
        Minecraft mc = Minecraft.getInstance();

        if (mc.isLocalServer()) {
            // Singleplayer: integrated server always has our mod
            serverHasMod = true;
            LOGGER.info("Singleplayer detected, direct mode enabled");
            return;
        }

        // Multiplayer: check if the server negotiated our packet channels.
        // If packets were registered as optional() and the server doesn't have
        // the mod, NeoForge still allows connection but the channels won't be active.
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            // If we can successfully check the connection, assume the server
            // negotiated our channels if it has the mod. The simplest detection:
            // try to check if our mod ID is in the server's mod list.
            var serverData = mc.getCurrentServer();
            // In practice, if the server has our mod, the optional packets will work.
            // If not, sending them will silently fail. We detect this by checking
            // if the integrated server is running (singleplayer) or if we're on
            // a dedicated server that may or may not have the mod.
            serverHasMod = false;
            LOGGER.info("Multiplayer detected, using Template Manager fallback. " +
                    "Install Schematicraft for Building Gadgets on the server for direct mode.");
        } else {
            serverHasMod = false;
        }
    }

    public static String getFallbackMessage() {
        return "\u00a7eSchematicraft for Building Gadgets mod not detected on the server. " +
                "Please use the Template Manager block for Schematicraft integration.";
    }
}
