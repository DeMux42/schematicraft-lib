package com.schematicraft.lib.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Caches whether the connected server supports an integration's optional
 * server-side capability.
 *
 * The shared layer owns the lifecycle only. A loader and editor integration
 * registers the actual capability probe, so this class carries no packet id,
 * editor name, or loader-specific channel API.
 */
public final class ServerMode {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static BooleanSupplier capabilityProbe = () -> false;
    private static boolean serverHasCapability = false;
    private static boolean checked = false;

    private ServerMode() {}

    /** Register the loader-specific server capability check. */
    public static void registerCapabilityProbe(BooleanSupplier probe) {
        capabilityProbe = Objects.requireNonNull(probe);
        reset();
    }

    /** True in singleplayer or when the registered server capability is present. */
    public static boolean isDirectModeAvailable() {
        if (!checked) {
            detect();
        }
        return serverHasCapability;
    }

    /** Clear cached detection. Called on logout so the next world re-detects. */
    public static void reset() {
        checked = false;
        serverHasCapability = false;
    }

    private static void detect() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.isLocalServer()) {
            checked = true;
            serverHasCapability = true;
            LOGGER.info("Singleplayer detected, server capability enabled");
            return;
        }

        if (mc.getConnection() == null) {
            // Not connected yet. Do not cache so a later call can detect again.
            serverHasCapability = false;
            return;
        }

        checked = true;
        try {
            serverHasCapability = capabilityProbe.getAsBoolean();
        } catch (RuntimeException e) {
            serverHasCapability = false;
            LOGGER.debug("Server capability check unavailable ({}), using client-only paths",
                    e.getClass().getSimpleName());
        }

        LOGGER.info(serverHasCapability
                ? "Server capability detected, direct mode enabled"
                : "Server capability unavailable, using client-only paths");
    }
}
