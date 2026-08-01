package com.schematicraft.lib.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.schematicraft.lib.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads and caches schematic thumbnail images as Minecraft textures.
 *
 * <p>Thumbnail URLs arrive in API responses, so they are treated as untrusted
 * input. Every fetch enforces:
 * <ul>
 *   <li>An allowed scheme and host ({@link ModConfig#isMediaUrlAllowed}).</li>
 *   <li>Rejection of private, loopback, link-local, and multicast addresses,
 *       revalidated on every redirect hop.</li>
 *   <li>Connect and request deadlines.</li>
 *   <li>A response byte ceiling, enforced while streaming.</li>
 *   <li>A supported image content type and signature.</li>
 *   <li>A decoded pixel ceiling, checked from image headers before decoding.</li>
 * </ul>
 *
 * <p>The texture cache is bounded by approximate GPU bytes rather than entry
 * count, and evicted entries release their texture.
 *
 * <p>Known limitation: the address check happens at resolution time, so a
 * DNS rebinding race is not fully excluded. Requiring HTTPS to a public host
 * keeps the residual risk low without a custom socket factory.
 */
public class ThumbnailCache {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Maximum compressed bytes accepted for one thumbnail. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** Maximum decoded pixels accepted for one thumbnail. */
    private static final int MAX_IMAGE_PIXELS = 8_000_000;

    /** Approximate GPU byte budget for cached thumbnail textures. */
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;

    /** Maximum redirects followed, each revalidated. */
    private static final int MAX_REDIRECTS = 3;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final ThumbnailCache INSTANCE = new ThumbnailCache();
    public static ThumbnailCache get() { return INSTANCE; }

    /** Access-ordered cache guarded by {@link #cacheLock}. */
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Object cacheLock = new Object();
    private long cachedBytes = 0L;

    private final Map<String, Boolean> pendingMap = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Schematicraft-Thumbnail");
        t.setDaemon(true);
        return t;
    });

    // Redirects are handled explicitly so each hop can be revalidated.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private ThumbnailCache() {}

    private record CacheEntry(ResourceLocation location, int width, int height, long bytes) {}

    public ResourceLocation getTexture(String schematicId, String url) {
        if (url == null || url.isEmpty()) return null;

        ResourceLocation existing = lookup(schematicId);
        if (existing != null) return existing;

        if (pendingMap.putIfAbsent(schematicId, true) == null) {
            executor.submit(() -> {
                try {
                    downloadAndRegister(schematicId, url);
                } finally {
                    pendingMap.remove(schematicId);
                }
            });
        }
        return null;
    }

    public int[] getDimensions(String schematicId) {
        synchronized (cacheLock) {
            CacheEntry entry = cache.get(schematicId);
            return entry == null ? null : new int[]{entry.width(), entry.height()};
        }
    }

    public void registerLocalFile(String key, Path filePath) {
        if (lookup(key) != null) return;
        if (pendingMap.putIfAbsent(key, true) != null) return;

        executor.submit(() -> {
            try {
                long size = Files.size(filePath);
                if (size > MAX_RESPONSE_BYTES) {
                    LOGGER.debug("Local thumbnail {} is too large to cache", key);
                    return;
                }
                registerImageBytes(key, Files.readAllBytes(filePath));
            } catch (Exception e) {
                LOGGER.debug("Failed to register local file {}: {}", key, e.getMessage());
            } finally {
                pendingMap.remove(key);
            }
        });
    }

    public ResourceLocation getLocalTexture(String key) {
        return lookup(key);
    }

    /**
     * Release every cached texture. Call on logout, disconnect, API key change,
     * and shutdown so user-derived images do not persist across sessions.
     */
    public void clear() {
        List<ResourceLocation> toRelease;
        synchronized (cacheLock) {
            toRelease = new ArrayList<>(cache.size());
            for (CacheEntry entry : cache.values()) {
                toRelease.add(entry.location());
            }
            cache.clear();
            cachedBytes = 0L;
        }
        pendingMap.clear();
        releaseAll(toRelease);
    }

    private ResourceLocation lookup(String key) {
        synchronized (cacheLock) {
            CacheEntry entry = cache.get(key);
            return entry == null ? null : entry.location();
        }
    }

    private void downloadAndRegister(String schematicId, String url) {
        try {
            byte[] body = fetchBounded(url);
            if (body != null) {
                registerImageBytes(schematicId, body);
            }
        } catch (Exception e) {
            LOGGER.debug("Thumbnail download error for {}: {}", schematicId, e.getMessage());
        }
    }

    /**
     * Fetch a validated URL, following a bounded number of revalidated
     * redirects, and stop reading past the byte ceiling.
     */
    private byte[] fetchBounded(String url) throws IOException, InterruptedException {
        String current = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isFetchAllowed(current)) {
                return null;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(current))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            if (status >= 300 && status < 400) {
                Optional<String> location = response.headers().firstValue("location");
                response.body().close();
                if (location.isEmpty()) {
                    return null;
                }
                // Resolve relative redirects against the current URL, then
                // revalidate on the next iteration.
                current = URI.create(current).resolve(location.get()).toString();
                continue;
            }

            if (status != 200) {
                response.body().close();
                LOGGER.debug("Thumbnail fetch returned HTTP {}", status);
                return null;
            }

            if (!isSupportedContentType(response.headers().firstValue("content-type").orElse(""))) {
                response.body().close();
                LOGGER.debug("Thumbnail fetch returned an unsupported content type");
                return null;
            }

            return readBounded(response.body());
        }

        LOGGER.debug("Thumbnail fetch exceeded the redirect limit");
        return null;
    }

    private static boolean isSupportedContentType(String contentType) {
        String value = contentType.toLowerCase(Locale.ROOT);
        return value.startsWith("image/png") || value.startsWith("image/jpeg");
    }

    /** Read at most {@link #MAX_RESPONSE_BYTES}, aborting an oversized body. */
    private static byte[] readBounded(InputStream stream) throws IOException {
        try (stream) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    LOGGER.debug("Thumbnail exceeded the {} byte limit", MAX_RESPONSE_BYTES);
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * Scheme and host policy plus resolved-address checks. Blocks fetches aimed
     * at loopback, private, link-local, and multicast destinations.
     */
    private static boolean isFetchAllowed(String url) {
        if (!ModConfig.isMediaUrlAllowed(url)) {
            LOGGER.debug("Blocked thumbnail URL that is not an allowed media destination");
            return false;
        }

        final String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }

        // A loopback endpoint is only reachable when explicitly enabled.
        boolean loopbackPermitted = ModConfig.isInsecureLocalhostAllowed();

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address, loopbackPermitted)) {
                    LOGGER.debug("Blocked thumbnail URL resolving to a non-public address");
                    return false;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }

        return true;
    }

    private static boolean isBlockedAddress(InetAddress address, boolean loopbackPermitted) {
        if (address.isLoopbackAddress()) {
            return !loopbackPermitted;
        }
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            // Carrier-grade NAT 100.64.0.0/10
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // Reserved 0.0.0.0/8 and 240.0.0.0/4
            if (first == 0 || first >= 240) {
                return true;
            }
        } else if (address instanceof Inet6Address && raw.length == 16) {
            // Unique local addresses fc00::/7
            if ((raw[0] & 0xFE) == 0xFC) {
                return true;
            }
        }

        return false;
    }

    private void registerImageBytes(String key, byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            return;
        }

        try {
            BufferedImage buffered = decodeBounded(imageData);
            if (buffered == null) {
                return;
            }

            int w = buffered.getWidth();
            int h = buffered.getHeight();

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
                    put(key, loc, w, h);
                } catch (Exception e) {
                    LOGGER.debug("Failed to register texture for {}: {}", key, e.getMessage());
                    nativeImage.close();
                }
            });

        } catch (Exception e) {
            LOGGER.debug("Failed to process image for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Decode only after confirming the header-reported dimensions are within
     * the pixel ceiling, so a small compressed file cannot expand into a very
     * large allocation.
     */
    private static BufferedImage decodeBounded(byte[] imageData) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageData))) {
            if (input == null) {
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                LOGGER.debug("No image reader for thumbnail data");
                return null;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_IMAGE_PIXELS) {
                    LOGGER.debug("Thumbnail exceeded the {} pixel limit", MAX_IMAGE_PIXELS);
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Insert an entry and evict least recently used entries over budget. */
    private void put(String key, ResourceLocation location, int width, int height) {
        long bytes = (long) width * height * 4L;
        List<ResourceLocation> evicted = new ArrayList<>();

        synchronized (cacheLock) {
            CacheEntry previous = cache.put(key, new CacheEntry(location, width, height, bytes));
            if (previous != null) {
                cachedBytes -= previous.bytes();
                evicted.add(previous.location());
            }
            cachedBytes += bytes;

            Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
            while (cachedBytes > MAX_CACHE_BYTES && it.hasNext()) {
                Map.Entry<String, CacheEntry> oldest = it.next();
                if (oldest.getKey().equals(key)) {
                    continue;
                }
                cachedBytes -= oldest.getValue().bytes();
                evicted.add(oldest.getValue().location());
                it.remove();
            }
        }

        releaseAll(evicted);
    }

    private static void releaseAll(List<ResourceLocation> locations) {
        if (locations.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            for (ResourceLocation location : locations) {
                try {
                    client.getTextureManager().release(location);
                } catch (Exception e) {
                    LOGGER.debug("Failed to release texture: {}", e.getMessage());
                }
            }
        });
    }
}
