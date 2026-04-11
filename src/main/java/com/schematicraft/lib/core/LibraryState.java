package com.schematicraft.lib.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared library state for Schematicraft. No MC dependencies.
 * Manages library cache (bundles + unbundled schematics).
 * Editor-specific state (clipboard, etc.) lives in the editor mod.
 */
public class LibraryState {
    private static final LibraryState INSTANCE = new LibraryState();

    private List<BundleEntry> bundles = new ArrayList<>();
    private List<SchematicEntry> unbundled = new ArrayList<>();
    private boolean libraryLoaded = false;
    private boolean libraryLoading = false;
    private String libraryError = null;

    private LibraryState() {}

    public static LibraryState get() { return INSTANCE; }

    public List<BundleEntry> getBundles() { return bundles; }
    public List<SchematicEntry> getUnbundled() { return unbundled; }
    public boolean isLibraryLoaded() { return libraryLoaded; }
    public boolean isLibraryLoading() { return libraryLoading; }
    public String getLibraryError() { return libraryError; }

    public void setLibraryLoading() {
        libraryLoading = true;
        libraryError = null;
    }

    public void setLibraryData(List<BundleEntry> bundles, List<SchematicEntry> unbundled) {
        this.bundles = bundles;
        this.unbundled = unbundled;
        this.libraryLoaded = true;
        this.libraryLoading = false;
        this.libraryError = null;
    }

    public void setLibraryError(String error) {
        this.libraryLoading = false;
        this.libraryError = error;
    }

    public void invalidateLibrary() {
        this.libraryLoaded = false;
    }

    public List<BundleOption> getBundleOptions() {
        List<BundleOption> options = new ArrayList<>();
        options.add(new BundleOption(null, "Unbundled"));
        for (BundleEntry b : bundles) {
            options.add(new BundleOption(b.id(), b.name()));
        }
        return options;
    }

    public record BundleOption(String id, String name) {
        public boolean isUnbundled() { return id == null; }
    }
}
