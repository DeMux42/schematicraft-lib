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
        return CompletableFuture.supplyAsync(() -> {
            try {
                long t0 = System.currentTimeMillis();
                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("schematicraft_dl_", ".json");
                var result = createClient().download(schematicId, tempFile, "json", "BuildingGadgets", null, null);
                long size = java.nio.file.Files.size(tempFile);
                LOGGER.info("[perf] download HTTP: {}ms, file: {} bytes", System.currentTimeMillis() - t0, size);
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
