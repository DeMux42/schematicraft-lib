package com.schematicraft.lib.client.gui;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Editor-owned builds that can be uploaded through the shared form. */
public interface UploadSource {
    record Candidate(String id, String label, @Nullable String detail) {}

    /** Short source name shown before the upload form opens. */
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
