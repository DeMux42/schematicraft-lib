package com.schematicraft.lib.client.gui;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Editor-agnostic upload seam.
 *
 * The library screen knows how to collect a title, description, bundle, and
 * screenshots. It does not know how any editor stores a build. Each editor
 * integration registers an UploadSource that exposes the builds it can upload
 * (for Building Gadgets these are gadget copies) and performs the upload.
 *
 * Registered once at client setup via
 * {@link LibraryScreen#setUploadSource(UploadSource)}.
 */
public interface UploadSource {

    /**
     * A build that can be uploaded, in editor-specific storage.
     *
     * @param id    opaque editor-specific identifier
     * @param label short human-readable label, e.g. "Copy (482 blocks)"
     * @param detail optional secondary line, e.g. "2m ago"
     */
    record Candidate(String id, String label, @Nullable String detail) {}

    /**
     * Builds currently available to upload, newest first.
     * Empty when the editor has nothing captured yet.
     */
    List<Candidate> listCandidates();

    /**
     * Why no candidates are available, shown to the user as guidance.
     * For example "Copy a build with your Copy/Paste gadget first".
     */
    String emptyHint();

    /**
     * Upload the given candidate.
     *
     * @return future completing with true when the server reported a duplicate
     *         that was skipped, false for a new upload
     */
    CompletableFuture<Boolean> upload(String candidateId, String title,
                                      String description, @Nullable String bundleId,
                                      List<Path> images);
}
