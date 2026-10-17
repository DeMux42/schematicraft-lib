package com.schematicraft.lib.network;

import com.schematicraft.api.SchematiCraftAPI;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.ApiJsonParser;
import com.schematicraft.lib.core.IngameDownload;
import com.schematicraft.lib.core.LibraryState;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async wrapper around the official SchematiCraftAPI client.
 * All calls run off the render thread via a dedicated executor.
 * Shared across all editor mods. Editor-specific operations (upload with
 * format conversion) should be implemented in the editor mod.
 */
public class SchematiCraftAPIWrapper {
    private static final SchematiCraftAPIWrapper INSTANCE = new SchematiCraftAPIWrapper();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_FEEDBACK_LENGTH = 1000;

    private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(10);
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(30);
    private static final java.time.Duration DOWNLOAD_TIMEOUT = java.time.Duration.ofMinutes(2);

    /**
     * Single client for the hand-written calls in this class.
     *
     * <p>Without a connect timeout, a server that accepts a connection and then
     * stalls holds an API worker thread indefinitely and the UI waits forever.
     * Per-request deadlines are set at each call site.
     */
    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "Schematicraft-API");
        t.setDaemon(true);
        return t;
    });

    /**
     * Sent as the X-Schematicraft-Client header.
     *
     * This library is editor agnostic and has no version of its own to report, so
     * the consuming mod must call {@link #setClientIdentifier(String)} during setup.
     * The default deliberately says "unknown" rather than naming a version: a stale
     * hardcoded number here would silently misreport which build made the request.
     * The header must stay non-null, so this is not left empty.
     */
    private String clientIdentifier = "schematicraft/unknown";

    private SchematiCraftAPIWrapper() {}
    public static SchematiCraftAPIWrapper get() { return INSTANCE; }

    /** Called by the consuming mod. Expected format: name/version (platform). */
    public void setClientIdentifier(String id) { this.clientIdentifier = id; }

    public SchematiCraftAPI createClient() {
        return new SchematiCraftAPI(ModConfig.getApiKey(), ModConfig.getServerUrl(), clientIdentifier);
    }

    public ExecutorService getExecutor() { return executor; }

    /** Upload an editor-native file without teaching the shared UI its format. */
    public CompletableFuture<Boolean> uploadFile(
            Path file, String title, String description, String minecraftVersion,
            String loader, String bundleId, List<Path> images) {
        return runAsync(() -> {
            if (file == null || !Files.isRegularFile(file)) {
                throw new IllegalStateException("Upload file is no longer available");
            }
            String response = createClient().upload(
                    file, title, description, minecraftVersion, loader, null,
                    false, bundleId, images);
            LibraryState.get().invalidateLibrary();
            return response != null && response.contains("\"isDuplicate\":true");
        });
    }

    public CompletableFuture<String> getStatus() {
        return runAsync(() -> createClient().getStatus());
    }

    public CompletableFuture<Void> loadLibrary() {
        LibraryState state = LibraryState.get();
        state.setLibraryLoading();
        long start = System.currentTimeMillis();

        return runAsync(() -> {
            long t0 = System.currentTimeMillis();
            String json = createClient().getLibrary();
            LOGGER.info("[perf] library HTTP: {}ms, response: {} chars", System.currentTimeMillis() - t0, json.length());
            return json;
        }).thenAccept(json -> {
            long t0 = System.currentTimeMillis();
            var data = ApiJsonParser.parseLibrary(json);
            LOGGER.info("[perf] library parse: {}ms, bundles: {}, unbundled: {}", System.currentTimeMillis() - t0, data.bundles().size(), data.unbundled().size());
            state.setLibraryData(data.bundles(), data.unbundled());
            LOGGER.info("[perf] library total: {}ms", System.currentTimeMillis() - start);
        }).exceptionally(ex -> {
            LOGGER.info("[perf] library FAILED after {}ms: {}", System.currentTimeMillis() - start, rootMessage(ex));

            // If the key is rejected (401/403), clear it so the UI shows the setup state
            Throwable cause = ex;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof SchematiCraftAPI.APIException apiEx
                    && (apiEx.statusCode == 401 || apiEx.statusCode == 403)) {
                LOGGER.warn("API key rejected (HTTP {}), clearing key", apiEx.statusCode);
                ModConfig.setApiKey("");
                // Drop user-derived thumbnails so they do not outlive the credential.
                com.schematicraft.lib.client.ThumbnailCache.get().clear();
            }

            state.setLibraryError(rootMessage(ex));
            return null;
        });
    }

    public CompletableFuture<Void> refreshLibrary() {
        LibraryState.get().invalidateLibrary();
        return loadLibrary();
    }

    /** Page size for the discover feed. The server caps limit at 50. */
    public static final int DISCOVER_PAGE_SIZE = 24;

    /**
     * Search public schematics, paged and sorted.
     *
     * Used for the discover feed, where an empty query means "browse" and the
     * sort decides what a first-time user sees. Hand-rolled rather than routed
     * through the API client because the client does not expose sort.
     */
    public CompletableFuture<ApiJsonParser.SearchPage> searchPublic(
            String query, int page, String sort) {
        return runAsync(() -> {
            StringBuilder url = new StringBuilder(ModConfig.getServerUrl())
                    .append("/api/ingame/v1/search?page=").append(Math.max(1, page))
                    .append("&limit=").append(DISCOVER_PAGE_SIZE);
            if (query != null && !query.isBlank()) {
                url.append("&query=").append(urlEncode(query));
            }
            if (sort != null && !sort.isBlank()) {
                url.append("&sort=").append(urlEncode(sort));
            }

            long t0 = System.currentTimeMillis();
            String json = httpGet(url.toString());
            var parsed = ApiJsonParser.parseSearchPage(json);
            LOGGER.info("[discover] page {} sort {} query '{}': {} results, hasMore {}, {}ms",
                    page, sort, query, parsed.results().size(), parsed.hasMore(),
                    System.currentTimeMillis() - t0);
            return parsed;
        });
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void requireDownloadTarget(String format, String targetEditor) {
        if (format == null || format.isBlank()
                || targetEditor == null || targetEditor.isBlank()) {
            throw new IllegalArgumentException(
                    "Download target must declare a format and editor");
        }
    }

    /**
     * Download for the target device, converted for the game this client runs.
     *
     * <p>With {@code confirm}, a conversion that would lose content a player
     * cares about fails with {@link IngameDownload.ConfirmLoss}, saying what;
     * call again without it once the player agrees.
     */
    public CompletableFuture<SchematiCraftAPI.DownloadResult> downloadSchematic(
            String schematicId, String format, String targetEditor, boolean confirm) {
        return download(schematicId, format, targetEditor, null, confirm);
    }

    /** The game's own release, or null for a snapshot or a renamed build. */
    @org.jetbrains.annotations.Nullable
    private static String gameRelease() {
        return IngameDownload.release(
                net.minecraft.SharedConstants.getGameVersion().getName());
    }

    /**
     * One hand-written request for both downloads, so an error answer's body
     * can be read: the API client turns every 409 into "analysis pending".
     */
    private CompletableFuture<SchematiCraftAPI.DownloadResult> download(
            String schematicId, String format, String targetEditor,
            @org.jetbrains.annotations.Nullable String paletteId, boolean confirm) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireDownloadTarget(format, targetEditor);
                long t0 = System.currentTimeMillis();
                String url = ModConfig.getServerUrl() + "/api/ingame/v1/schematics/"
                        + urlEncode(schematicId) + "/download?"
                        + IngameDownload.query(format, targetEditor, gameRelease(),
                                paletteId, confirm);

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(DOWNLOAD_TIMEOUT)
                        .header("Authorization", "Bearer " + ModConfig.getApiKey())
                        .header("X-Schematicraft-Client", clientIdentifier)
                        .GET().build();

                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(
                        paletteId != null ? "schematicraft_pal_" : "schematicraft_dl_",
                        "." + format);
                final java.net.http.HttpResponse<java.nio.file.Path> response;
                try {
                    response = HTTP_CLIENT.send(
                            request, java.net.http.HttpResponse.BodyHandlers.ofFile(tempFile));
                } catch (Exception sendFailure) {
                    // Do not leave a partial download behind on timeout or error.
                    java.nio.file.Files.deleteIfExists(tempFile);
                    throw sendFailure;
                }

                int status = response.statusCode();
                if (status >= 400) {
                    String body = readError(tempFile);
                    java.nio.file.Files.deleteIfExists(tempFile);
                    IngameDownload.Loss loss = IngameDownload.parseConfirmLoss(status, body);
                    if (loss != null) throw new IngameDownload.ConfirmLoss(loss);
                    throw new RuntimeException(IngameDownload.failure(status, body));
                }

                String downloadId = response.headers().firstValue("X-Download-Id").orElse(null);
                long size = java.nio.file.Files.size(tempFile);
                LOGGER.info("[perf] download HTTP: {}ms, file: {} bytes, format: {}, palette: {}",
                        System.currentTimeMillis() - t0, size, format, paletteId);
                return new SchematiCraftAPI.DownloadResult(downloadId, tempFile, null);
            } catch (IngameDownload.ConfirmLoss e) {
                throw e;
            } catch (Exception e) {
                LOGGER.error("Download failed for {}: {}", schematicId, e.getMessage());
                throw e instanceof RuntimeException r ? r : new RuntimeException(e);
            }
        }, executor);
    }

    /** An error answer streamed to a file, read whole up to a bound. */
    private static String readError(java.nio.file.Path file) {
        try (var in = java.nio.file.Files.newInputStream(file)) {
            return new String(in.readNBytes(IngameDownload.MAX_ERROR_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public CompletableFuture<String> createBundle(String name, String description) {
        return runAsync(() -> createClient().createBundle(name, description));
    }

    public void submitSuccessFeedback(String downloadId) {
        if (downloadId == null) return;
        executor.submit(() -> {
            try {
                createClient().submitFeedback(downloadId, "worked", null, null,
                        null, null, null);
                LOGGER.info("Feedback submitted: worked for download {}", downloadId);
            } catch (Exception e) {
                LOGGER.debug("Feedback submission failed: {}", e.getMessage());
            }
        });
    }

    public void submitFailureFeedback(String downloadId, String issueCategory, String errorDetails) {
        if (downloadId == null) return;
        executor.submit(() -> {
            try {
                String notes = errorDetails;
                if (notes != null && notes.length() > MAX_FEEDBACK_LENGTH) notes = notes.substring(0, MAX_FEEDBACK_LENGTH);
                createClient().submitFeedback(downloadId, "didnt_work", issueCategory, notes,
                        null, null, null);
                LOGGER.info("Feedback submitted: didnt_work ({}) for download {}", issueCategory, downloadId);
            } catch (Exception e) {
                LOGGER.debug("Feedback submission failed: {}", e.getMessage());
            }
        });
    }

    public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    // --- Palette API methods ---

    public CompletableFuture<java.util.List<com.schematicraft.lib.core.PaletteEntry>> loadPalettes(String schematicId) {
        return runAsync(() -> {
            String url = ModConfig.getServerUrl() + "/api/ingame/v1/palettes"
                    + (schematicId != null ? "?schematicId=" + schematicId : "");
            LOGGER.info("[palette] GET {}", url);
            try {
                String json = httpGet(url);
                LOGGER.info("[palette] response: {} chars", json.length());
                var result = ApiJsonParser.parsePalettes(json);
                LOGGER.info("[palette] parsed {} palettes", result.size());
                return result;
            } catch (Exception e) {
                LOGGER.error("[palette] request failed: {}", e.getMessage(), e);
                throw e;
            }
        });
    }

    // Palette creation, update, and delete are website concerns and are
    // intentionally not exposed here. The mod only lists and applies palettes.

    /**
     * Download a schematic with a palette applied by the cloud.
     * Format and target editor come from the resolved target device so palette
     * apply works for every editor, not just Building Gadgets.
     */
    public CompletableFuture<SchematiCraftAPI.DownloadResult> downloadSchematicWithPalette(
            String schematicId, String paletteId, String format, String targetEditor) {
        // The server converts a palette download itself, without the engine's
        // report, so there is nothing to confirm.
        return download(schematicId, format, targetEditor, paletteId, false);
    }

    private String httpGet(String url) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + ModConfig.getApiKey())
                .header("X-Schematicraft-Client", clientIdentifier)
                .GET().build();
        var response = HTTP_CLIENT.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return response.body();
    }

    private String httpPost(String url, String jsonBody) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + ModConfig.getApiKey())
                .header("Content-Type", "application/json")
                .header("X-Schematicraft-Client", clientIdentifier)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        var response = HTTP_CLIENT.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return response.body();
    }

    /** Maximum remote error text carried into an exception message. */
    private static final int MAX_ERROR_CHARS = 500;

    /** Keep a remote error body from flooding logs or exception messages. */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String single = body.replaceAll("\\s+", " ").trim();
        return single.length() <= MAX_ERROR_CHARS
                ? single
                : single.substring(0, MAX_ERROR_CHARS) + "\u2026";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public static String rootMessage(Throwable ex) {
        Throwable c = ex;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
