package com.schematicraft.lib.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * In-game screenshot capture mode with overlay, debounce, and image cap.
 *
 * The three handlers (overlay draw, mouse click, key press) are plain methods
 * the loader integration subscribes to its own events. On Fabric the mod wires
 * renderOverlay to HudRenderCallback and onMouseClick/onKeyInput to the client
 * input callbacks; on NeoForge the equivalents are the event bus. Keeping the
 * handlers loader-neutral is why this lives in the editor-agnostic library.
 */
public class CameraMode {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ESC_KEY = 256;
    private static final int FLASH_DURATION_TICKS = 15; // ~0.75 seconds
    /**
     * Must match the server upload contract. The In-Game upload endpoint accepts
     * at most 5 images and rejects the whole request with 413 beyond that, so
     * capturing more here would fail at upload time.
     */
    private static final int MAX_IMAGES = 5;

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

    public static void start(List<Path> images, Runnable finished) {
        if (active) return;
        active = true;
        capturedImages = images;
        captureCount = images.size();
        onFinished = finished;
        lastCaptureTimeMs = 0;
        tickCounter = 0;

        MinecraftClient mc = MinecraftClient.getInstance();
        wasHudHidden = mc.options.hudHidden;
        mc.options.hudHidden = true;
    }

    public static void stop() {
        if (!active) return;
        active = false;

        MinecraftClient.getInstance().options.hudHidden = wasHudHidden;
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

    /**
     * Draw the capture overlay. The loader subscribes this to its HUD render
     * callback (Fabric HudRenderCallback). Behaviour is unchanged from 1.21.1.
     */
    public static void renderOverlay(DrawContext g) {
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

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer font = mc.textRenderer;
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();

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
                g.drawCenteredTextWithShadow(font, "\u00a7aCaptured!", w / 2, h / 2 - 4, 0xFFFFFF);
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
        g.drawCenteredTextWithShadow(font, msg, w / 2, h - 18, 0xFFFFFF);

        // Count badge
        if (captureCount > 0) {
            String badge = "\u00a7a\u2713 " + captureCount;
            int bw = font.getWidth(badge) + 8;
            g.fill(w - bw - 4, 4, w - 4, 18, 0x80000000);
            g.drawTextWithShadow(font, badge, w - bw, 6, 0xFFFFFF);
        }
    }

    /**
     * Left-click handler. The loader subscribes this to its client mouse input.
     * Returns true when the click was consumed (while active), so the loader can
     * cancel the click. button 0 is left; action 1 is press.
     */
    public static boolean onMouseClick(int button, int action) {
        if (!active) return false;
        if (button != 0 || action != 1) return false;

        // Consume the click while active even if it does not schedule a capture.
        if ((System.currentTimeMillis() - lastCaptureTimeMs) < CAPTURE_COOLDOWN_MS) return true;
        if (pendingCapture) return true;
        if (captureCount >= MAX_IMAGES) return true;

        // Schedule capture on next render frame (overlay will be hidden for a clean screenshot)
        pendingCapture = true;
        return true;
    }

    /**
     * Key handler. The loader subscribes this to its client key input. key is the
     * GLFW key code; action 1 is press.
     */
    public static void onKeyInput(int key, int action) {
        if (!active) return;
        if (key == ESC_KEY && action == 1) {
            MinecraftClient.getInstance().execute(CameraMode::stop);
        }
    }

    private static void captureScreenshot() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Framebuffer fb = mc.getFramebuffer();
            // ScreenshotRecorder reads the framebuffer on the render thread and
            // returns an image already flipped to top-down, the same pixels the
            // old bindRead plus downloadTexture plus flipY produced.
            NativeImage image = ScreenshotRecorder.takeScreenshot(fb);

            Path dir = Path.of("schematicraft_screenshots");
            Files.createDirectories(dir);
            Path out = dir.resolve("sc_" + System.currentTimeMillis() + ".png");
            image.writeTo(out);
            image.close();

            capturedImages.add(out);
            captureCount++;
            flashTimer = FLASH_DURATION_TICKS;
            LOGGER.info("Captured: {} ({}x{})", out.getFileName(), fb.textureWidth, fb.textureHeight);
        } catch (Exception e) {
            LOGGER.error("Capture failed: {}", e.getMessage());
        }
    }
}
