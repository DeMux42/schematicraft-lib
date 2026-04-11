package com.schematicraft.lib.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * In-game screenshot capture mode with overlay, debounce, and image cap.
 * Event handlers are registered manually via registerEvents() rather than
 * annotation-based, so the lib works regardless of which mod ID it's compiled into.
 */
public class CameraMode {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean eventsRegistered = false;
    private static final int ESC_KEY = 256;
    private static final int CAPTURE_COOLDOWN_TICKS = 20; // 1 second at 20 TPS
    private static final int FLASH_DURATION_TICKS = 15; // ~0.75 seconds
    private static final int MAX_IMAGES = 20;

    private static boolean active = false;
    private static List<Path> capturedImages;
    private static Runnable onFinished;
    private static boolean wasHudHidden;
    private static int flashTimer = 0;
    private static int captureCount = 0;
    private static int tickCounter = 0;
    private static boolean pendingCapture = false;
    private static long lastCaptureTimeMs = 0; // Debounce based on wall clock, not ticks
    private static final long CAPTURE_COOLDOWN_MS = 1000; // 1 second

    public static void registerEvents() {
        if (eventsRegistered) return;
        eventsRegistered = true;
        NeoForge.EVENT_BUS.addListener(CameraMode::onRenderGuiLayer);
        NeoForge.EVENT_BUS.addListener(CameraMode::onMouseClick);
        NeoForge.EVENT_BUS.addListener(CameraMode::onKeyInput);
        LOGGER.info("CameraMode events registered");
    }

    public static void start(List<Path> images, Runnable finished) {
        if (active) return;
        active = true;
        capturedImages = images;
        captureCount = images.size();
        onFinished = finished;
        lastCaptureTimeMs = 0;
        tickCounter = 0;

        Minecraft mc = Minecraft.getInstance();
        wasHudHidden = mc.options.hideGui;
        mc.options.hideGui = true;
    }

    public static void stop() {
        if (!active) return;
        active = false;

        Minecraft.getInstance().options.hideGui = wasHudHidden;
        LOGGER.info("Camera mode finished with {} screenshots", capturedImages.size());

        Runnable callback = onFinished;
        capturedImages = null;
        onFinished = null;
        flashTimer = 0;
        lastCaptureTimeMs = 0;
        captureCount = 0;

        if (callback != null) callback.run();
    }

    public static boolean isActive() { return active; }

    public static void onRenderGuiLayer(RenderGuiLayerEvent.Post event) {
        if (!active) return;

        // When a capture is pending, skip drawing the overlay so the framebuffer is clean,
        // then grab the screenshot from the clean frame.
        if (pendingCapture) {
            pendingCapture = false;
            captureScreenshot();
            lastCaptureTimeMs = System.currentTimeMillis();
            return;
        }

        tickCounter++;

        boolean inCooldown = (System.currentTimeMillis() - lastCaptureTimeMs) < CAPTURE_COOLDOWN_MS;

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        // Camera border
        int bc = 0x40FFFFFF;
        g.fill(0, 0, w, 2, bc);
        g.fill(0, h - 2, w, h, bc);
        g.fill(0, 0, 2, h, bc);
        g.fill(w - 2, 0, w, h, bc);

        // Rule of thirds
        int gc = 0x15FFFFFF;
        g.fill(w / 3, 0, w / 3 + 1, h, gc);
        g.fill(2 * w / 3, 0, 2 * w / 3 + 1, h, gc);
        g.fill(0, h / 3, w, h / 3 + 1, gc);
        g.fill(0, 2 * h / 3, w, 2 * h / 3 + 1, gc);

        // Crosshair
        int cx = w / 2, cy = h / 2;
        g.fill(cx - 8, cy, cx + 8, cy + 1, 0x40FFFFFF);
        g.fill(cx, cy - 8, cx + 1, cy + 8, 0x40FFFFFF);

        // Flash effect (fades out over FLASH_DURATION_TICKS)
        if (flashTimer > 0) {
            int alpha = Math.min(255, flashTimer * (200 / FLASH_DURATION_TICKS));
            g.fill(0, 0, w, h, (alpha << 24) | 0xFFFFFF);
            flashTimer--;

            // "Captured!" text during flash
            if (flashTimer > FLASH_DURATION_TICKS / 2) {
                g.drawCenteredString(font, "\u00a7aCaptured!", w / 2, h / 2 - 4, 0xFFFFFF);
            }
        }

        // Bottom instruction bar
        g.fill(0, h - 22, w, h - 2, 0x80000000);
        String msg;
        if (inCooldown) {
            msg = "\u00a7aSaved!  \u00a78|\u00a77  Move to a new angle...";
        } else if (captureCount >= MAX_IMAGES) {
            msg = "\u00a7e" + MAX_IMAGES + "/" + MAX_IMAGES + " max reached  \u00a78|\u00a77  Esc to finish";
        } else if (captureCount == 0) {
            msg = "\u00a7fClick \u00a77to capture  \u00a78|\u00a77  Esc \u00a77when done";
        } else {
            msg = "\u00a7a" + captureCount + "/" + MAX_IMAGES + "  \u00a78|\u00a77  Click for more  \u00a78|\u00a77  Esc to finish";
        }
        g.drawCenteredString(font, msg, w / 2, h - 18, 0xFFFFFF);

        // Count badge
        if (captureCount > 0) {
            String badge = "\u00a7a\u2713 " + captureCount;
            int bw = font.width(badge) + 8;
            g.fill(w - bw - 4, 4, w - 4, 18, 0x80000000);
            g.drawString(font, badge, w - bw, 6, 0xFFFFFF);
        }
    }

    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (!active) return;
        if (event.getButton() != 0 || event.getAction() != 1) return;

        event.setCanceled(true);

        if ((System.currentTimeMillis() - lastCaptureTimeMs) < CAPTURE_COOLDOWN_MS) return;
        if (pendingCapture) return;
        if (captureCount >= MAX_IMAGES) return;

        // Schedule capture on next render frame (overlay will be hidden for a clean screenshot)
        pendingCapture = true;
    }

    public static void onKeyInput(InputEvent.Key event) {
        if (!active) return;
        if (event.getKey() == ESC_KEY && event.getAction() == 1) {
            Minecraft.getInstance().execute(CameraMode::stop);
        }
    }

    private static void captureScreenshot() {
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget fb = mc.getMainRenderTarget();
            NativeImage image = new NativeImage(fb.width, fb.height, false);
            fb.bindRead();
            image.downloadTexture(0, false);
            fb.unbindRead();
            image.flipY();

            Path dir = Path.of("schematicraft_screenshots");
            Files.createDirectories(dir);
            Path out = dir.resolve("sc_" + System.currentTimeMillis() + ".png");
            image.writeToFile(out);
            image.close();

            capturedImages.add(out);
            captureCount++;
            flashTimer = FLASH_DURATION_TICKS;
            LOGGER.info("Captured: {} ({}x{})", out.getFileName(), fb.width, fb.height);
        } catch (Exception e) {
            LOGGER.error("Capture failed: {}", e.getMessage());
        }
    }
}
