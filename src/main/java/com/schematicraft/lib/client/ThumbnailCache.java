package com.schematicraft.lib.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads and caches schematic thumbnail images as Minecraft textures.
 * Handles JPEG/PNG via ImageIO, converts to NativeImage with ABGR pixel format,
 * and registers as DynamicTexture for GUI rendering.
 */
public class ThumbnailCache {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final ThumbnailCache INSTANCE = new ThumbnailCache();
    public static ThumbnailCache get() { return INSTANCE; }

    private final Map<String, ResourceLocation> textureMap = new ConcurrentHashMap<>();
    private final Map<String, int[]> dimensionMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingMap = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Schematicraft-Thumbnail");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ThumbnailCache() {}

    public ResourceLocation getTexture(String schematicId, String url) {
        if (url == null || url.isEmpty()) return null;

        ResourceLocation existing = textureMap.get(schematicId);
        if (existing != null) return existing;

        // Start async download if not already pending
        if (pendingMap.putIfAbsent(schematicId, true) == null) {
            executor.submit(() -> downloadAndRegister(schematicId, url));
        }
        return null;
    }

    public int[] getDimensions(String schematicId) {
        return dimensionMap.get(schematicId);
    }

    public void registerLocalFile(String key, Path filePath) {
        if (textureMap.containsKey(key)) return;
        executor.submit(() -> {
            try {
                byte[] data = Files.readAllBytes(filePath);
                registerImageBytes(key, data);
            } catch (Exception e) {
                LOGGER.debug("Failed to register local file {}: {}", key, e.getMessage());
            }
        });
    }

    public ResourceLocation getLocalTexture(String key) {
        return textureMap.get(key);
    }

    private void downloadAndRegister(String schematicId, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                LOGGER.debug("Thumbnail download failed for {}: HTTP {}", schematicId, response.statusCode());
                pendingMap.remove(schematicId);
                return;
            }

            registerImageBytes(schematicId, response.body());

        } catch (Exception e) {
            LOGGER.debug("Thumbnail download error for {}: {}", schematicId, e.getMessage());
            pendingMap.remove(schematicId);
        }
    }

    private void registerImageBytes(String key, byte[] imageData) {
        try {
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(imageData));
            if (buffered == null) {
                LOGGER.debug("ImageIO returned null for {}", key);
                pendingMap.remove(key);
                return;
            }

            int w = buffered.getWidth();
            int h = buffered.getHeight();
            dimensionMap.put(key, new int[]{w, h});

            NativeImage nativeImage = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = buffered.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // NativeImage expects ABGR pixel order
                    nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }

            Minecraft.getInstance().execute(() -> {
                try {
                    DynamicTexture texture = new DynamicTexture(nativeImage);
                    ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                            .register("schematicraft_thumb_" + key.replaceAll("[^a-zA-Z0-9_]", "_"), texture);
                    textureMap.put(key, loc);
                } catch (Exception e) {
                    LOGGER.debug("Failed to register texture for {}: {}", key, e.getMessage());
                    nativeImage.close();
                }
            });

        } catch (Exception e) {
            LOGGER.debug("Failed to process image for {}: {}", key, e.getMessage());
            pendingMap.remove(key);
        }
    }
}
