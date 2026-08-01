package com.schematicraft.lib.client.gui;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Editor-owned builds that can be uploaded through the shared form. */
public interface UploadSource {
    record Candidate(String id, String label, @Nullable String detail) {}

    /**
     * Name of the device or place this source reads from.
     *
     * <p>Must be the <em>place</em>, not the content, and must match the label of
     * the matching {@link TargetCatalog.Entry}. The screen shows "Download to"
     * and "Upload from" side by side, so when both refer to the same device they
     * have to read identically or the user cannot tell it is the same thing.
     *
     * <p>The specific build, file, or copy being uploaded belongs on a
     * {@link Candidate}, which already carries a label and a detail line.
     * Returning a filename or an item name here is a bug.
     */
    String displayName();

    /** Live source icon. Empty is valid for file-only or unavailable sources. */
    default ItemStack icon() { return ItemStack.EMPTY; }

    /** Builds available now, ordered with the contextual build first. */
    List<Candidate> listCandidates();

    /** Guidance shown when the source currently has no uploadable build. */
    String emptyHint();

    default boolean isReady() { return !listCandidates().isEmpty(); }

    CompletableFuture<Boolean> upload(String candidateId, String title,
                                      String description, @Nullable String bundleId,
                                      List<Path> images);
}
