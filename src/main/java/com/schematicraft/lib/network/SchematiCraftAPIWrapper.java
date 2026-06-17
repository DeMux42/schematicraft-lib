package com.schematicraft.lib.network;

import com.schematicraft.api.SchematiCraftAPI;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.ApiJsonParser;
import com.schematicraft.lib.core.LibraryState;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

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

    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "Schematicraft-API");
        t.setDaemon(true);
        return t;
    });

    private String clientIdentifier = "schematicraft/0.1.0";

    private SchematiCraftAPIWrapper() {}
    public static SchematiCraftAPIWrapper get() { return INSTANCE; }

    public void setClientIdentifier(String id) { this.clientIdentifier = id; }

    public SchematiCraftAPI createClient() {
        return new SchematiCraftAPI(ModConfig.getApiKey(), ModConfig.getServerUrl(), clientIdentifier);
    }

    public ExecutorService getExecutor() { return executor; }

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
            }

            state.setLibraryError(rootMessage(ex));
            return null;
        });
    }

    public CompletableFuture<Void> refreshLibrary() {
        LibraryState.get().invalidateLibrary();
        return loadLibrary();
    }

    public CompletableFuture<String> search(String query) {
        return runAsync(() -> createClient().search(query, 1, 20));
    }

    public CompletableFuture<SchematiCraftAPI.DownloadResult> downloadSchematic(String schematicId) {
        return downloadSchematic(schematicId, "json", "BuildingGadgets");
    }

    public CompletableFuture<SchematiCraftAPI.DownloadResult> downloadSchematic(
            String schematicId, String format, String targetEditor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long t0 = System.currentTimeMillis();
                String ext = format != null ? format : "json";
                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("schematicraft_dl_", "." + ext);
                var result = createClient().download(schematicId, tempFile, format, targetEditor, null, null);
                long size = java.nio.file.Files.size(tempFile);
                LOGGER.info("[perf] download HTTP: {}ms, file: {} bytes, format: {}",
                        System.currentTimeMillis() - t0, size, format);
                return result;
            } catch (SchematiCraftAPI.AnalysisPendingException e) {
                throw new RuntimeException("Schematic is still being analyzed. Try again in a moment.");
            } catch (SchematiCraftAPI.QuotaExceededException e) {
                throw new RuntimeException("Download quota exceeded.");
            } catch (SchematiCraftAPI.RateLimitException e) {
                throw new RuntimeException("Too many requests. Wait a moment.");
            } catch (Exception e) {
                LOGGER.error("Download failed for {}: {}", schematicId, e.getMessage());
                throw new RuntimeException(e);
            }
        }, executor);
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

    public CompletableFuture<com.schematicraft.lib.core.PaletteEntry> createPalette(
            String name, java.util.List<com.schematicraft.lib.core.BlockMapping> mappings,
            String schematicId) {
        return runAsync(() -> {
            String url = ModConfig.getServerUrl() + "/api/ingame/v1/palettes";
            StringBuilder body = new StringBuilder();
            body.append("{\"name\":\"").append(escapeJson(name)).append("\",");
            body.append("\"visibility\":\"private\",");
            if (schematicId != null) {
                body.append("\"schematicId\":\"").append(schematicId).append("\",");
            }
            body.append("\"mappings\":[");
            for (int i = 0; i < mappings.size(); i++) {
                var m = mappings.get(i);
                if (i > 0) body.append(",");
                body.append("{\"original\":\"").append(escapeJson(m.original()))
                        .append("\",\"replacement\":\"").append(escapeJson(m.replacement())).append("\"}");
            }
            body.append("]}");
            String json = httpPost(url, body.toString());
            return ApiJsonParser.parseSinglePalette(json);
        });
    }

    public CompletableFuture<Void> deletePalette(String paletteId) {
        return runAsync(() -> {
            String url = ModConfig.getServerUrl() + "/api/ingame/v1/palettes/" + paletteId;
            httpDelete(url);
            return null;
        });
    }

    public CompletableFuture<SchematiCraftAPI.DownloadResult> downloadSchematicWithPalette(
            String schematicId, String paletteId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long t0 = System.currentTimeMillis();
                String url = ModConfig.getServerUrl() + "/api/ingame/v1/schematics/" + schematicId
                        + "/download?format=json&targetEditor=BuildingGadgets&paletteId=" + paletteId + "&force=true";

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("Authorization", "Bearer " + ModConfig.getApiKey())
                        .header("X-Schematicraft-Client", clientIdentifier)
                        .GET().build();

                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("schematicraft_pal_", ".json");
                java.net.http.HttpResponse<java.nio.file.Path> response = java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1).build()
                        .send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(tempFile));

                if (response.statusCode() >= 400) {
                    String body = java.nio.file.Files.readString(tempFile);
                    java.nio.file.Files.deleteIfExists(tempFile);
                    throw new RuntimeException("HTTP " + response.statusCode() + ": " + body);
                }

                String downloadId = response.headers().firstValue("X-Download-Id").orElse(null);
                long size = java.nio.file.Files.size(tempFile);
                LOGGER.info("[perf] download+palette HTTP: {}ms, file: {} bytes, palette: {}",
                        System.currentTimeMillis() - t0, size, paletteId);

                return new SchematiCraftAPI.DownloadResult(downloadId, tempFile, null);
            } catch (Exception e) {
                LOGGER.error("Download with palette failed for {}: {}", schematicId, e.getMessage());
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private String httpGet(String url) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Bearer " + ModConfig.getApiKey())
                .header("X-Schematicraft-Client", clientIdentifier)
                .GET().build();
        var response = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).build()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private String httpPost(String url, String jsonBody) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Bearer " + ModConfig.getApiKey())
                .header("Content-Type", "application/json")
                .header("X-Schematicraft-Client", clientIdentifier)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        var response = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).build()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private void httpDelete(String url) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Bearer " + ModConfig.getApiKey())
                .header("X-Schematicraft-Client", clientIdentifier)
                .DELETE().build();
        var response = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).build()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400 && response.statusCode() != 404) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
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
