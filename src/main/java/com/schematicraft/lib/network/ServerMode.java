package com.schematicraft.lib.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * Tracks whether the connected server can handle our optional packets.
 *
 * When true, "direct mode" is available: schematics can be loaded straight into
 * a Building Gadgets gadget, and clipboard copies can be read for upload.
 * When false, the client-only paths are used instead (BG2 Template Manager
 * slot, Create's schematics folder), which work on any server.
 *
 * Detection is based on NeoForge channel negotiation rather than assuming.
 * Singleplayer is always direct mode. On a dedicated server we ask the
 * connection whether our load-template channel was negotiated, which is only
 * true when the server also has this mod installed.
 */
public class ServerMode {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Payload id of our load-template packet. Must stay in sync with the
     * editor module that registers it (Building Gadgets integration).
     */
    private static final ResourceLocation PROBE_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("schematicraft_bg2", "load_template");

    private static boolean serverHasMod = false;
    private static boolean checked = false;

    /**
     * True when the server can handle our packets (singleplayer, or a server
     * with this mod installed).
     */
    public static boolean isDirectModeAvailable() {
        if (!checked) {
            detect();
        }
        return serverHasMod;
    }

    /** Clear cached detection. Called on logout so the next world re-detects. */
    public static void reset() {
        checked = false;
        serverHasMod = false;
    }

    private static void detect() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.isLocalServer()) {
            // Singleplayer: the integrated server runs our code.
            checked = true;
            serverHasMod = true;
            LOGGER.info("Singleplayer detected, direct mode enabled");
            return;
        }

        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            // Not connected yet. Do not cache: re-detect once a connection exists.
            serverHasMod = false;
            return;
        }

        checked = true;
        serverHasMod = hasChannel(connection, PROBE_CHANNEL);

        if (serverHasMod) {
            LOGGER.info("Server has Schematicraft, direct mode enabled");
        } else {
            LOGGER.info("Server does not have Schematicraft. Using client-only paths "
                    + "(Template Manager for Building Gadgets, schematics folder for Create).");
        }
    }

    /**
     * Ask NeoForge whether a custom payload channel was negotiated with the server.
     * Uses reflection so the lib keeps compiling if the connection API shifts
     * between NeoForge versions. A failure is treated as "no mod on server",
     * which degrades to the client-only paths rather than breaking.
     */
    private static boolean hasChannel(ClientPacketListener connection, ResourceLocation channel) {
        try {
            return connection.hasChannel(channel);
        } catch (Throwable t) {
            LOGGER.debug("Channel negotiation check unavailable ({}), assuming client-only mode",
                    t.getClass().getSimpleName());
            return false;
        }
    }

    public static String getFallbackMessage() {
        return "\u00a7eSchematicraft is not installed on this server. "
                + "Use the Template Manager to load schematics into your gadget.";
    }
}
