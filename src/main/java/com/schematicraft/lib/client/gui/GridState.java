package com.schematicraft.lib.client.gui;

import com.schematicraft.lib.core.BundleEntry;
import com.schematicraft.lib.core.LibraryState;
import com.schematicraft.lib.core.SchematicEntry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data model for the tile grid. Holds the filtered/sorted view of schematics,
 * selection state, scroll position, and active bundle filter.
 *
 * Completely separate from rendering. The TileGridWidget reads from this.
 * Persists across init() calls (screen resize) so scroll position is preserved.
 */
public class GridState {

    /** An entry in the flat display list: either a bundle header or a schematic tile. */
    public sealed interface DisplayEntry permits BundleHeaderEntry, SchematicTileEntry {}

    public record BundleHeaderEntry(String bundleId, String name, int count, boolean pinned) implements DisplayEntry {}
    public record SchematicTileEntry(SchematicEntry schematic, String bundleId) implements DisplayEntry {}

    // Bundle tab state
    private final List<BundleTabInfo> pinnedBundles = new ArrayList<>();
    private int activeBundleIndex = -1; // -1 = "All"

    // Display list (flat, includes headers when on "All" tab)
    private List<DisplayEntry> displayList = Collections.emptyList();

    // Selection
    private int selectedIndex = -1; // index into displayList (only schematic entries are selectable)

    // Scroll
    private int scrollOffset = 0; // pixels scrolled

    // Search
    private String searchText = "";
    private boolean searchActive = false;

    // Status
    private String statusText = "";
    private long statusClearAt = 0;

    public GridState() {
        rebuildPinnedBundles();
    }

    // --- Bundle Tabs ---

    public record BundleTabInfo(String id, String name) {}

    public List<BundleTabInfo> getPinnedBundles() { return Collections.unmodifiableList(pinnedBundles); }

    public int getActiveBundleIndex() { return activeBundleIndex; }

    /** -1 = All, 0+ = pinned bundle index */
    public void setActiveBundleIndex(int index) {
        this.activeBundleIndex = index;
        this.scrollOffset = 0;
        this.selectedIndex = -1;
        rebuildDisplayList();
    }

    public void nextBundle() {
        int max = pinnedBundles.size() - 1;
        setActiveBundleIndex(Math.min(activeBundleIndex + 1, max));
    }

    public void prevBundle() {
        setActiveBundleIndex(Math.max(activeBundleIndex - 1, -1));
    }

    public void pinBundle(String bundleId, String name) {
        if (pinnedBundles.stream().noneMatch(b -> b.id().equals(bundleId))) {
            pinnedBundles.add(new BundleTabInfo(bundleId, name));
        }
    }

    public void unpinBundle(String bundleId) {
        pinnedBundles.removeIf(b -> b.id().equals(bundleId));
        if (activeBundleIndex >= pinnedBundles.size()) {
            activeBundleIndex = -1;
        }
    }

    public boolean isBundlePinned(String bundleId) {
        return pinnedBundles.stream().anyMatch(b -> b.id().equals(bundleId));
    }

    private void rebuildPinnedBundles() {
        pinnedBundles.clear();
        // Auto-pin the first 7 bundles from the library
        List<BundleEntry> bundles = LibraryState.get().getBundles();
        for (int i = 0; i < Math.min(7, bundles.size()); i++) {
            BundleEntry b = bundles.get(i);
            pinnedBundles.add(new BundleTabInfo(b.id(), b.name()));
        }
    }

    // --- Display List ---

    public List<DisplayEntry> getDisplayList() { return displayList; }

    public void rebuildDisplayList() {
        List<DisplayEntry> result = new ArrayList<>();
        LibraryState state = LibraryState.get();
        String filter = searchText.toLowerCase().trim();

        if (activeBundleIndex == -1) {
            // "All" tab: show all bundles with headers
            for (BundleEntry bundle : state.getBundles()) {
                List<SchematicEntry> filtered = filterSchematics(bundle.schematics(), filter);
                if (!filtered.isEmpty()) {
                    result.add(new BundleHeaderEntry(bundle.id(), bundle.name(), filtered.size(),
                            isBundlePinned(bundle.id())));
                    for (SchematicEntry s : filtered) {
                        result.add(new SchematicTileEntry(s, bundle.id()));
                    }
                }
            }
            // Unbundled
            List<SchematicEntry> unbundledFiltered = filterSchematics(state.getUnbundled(), filter);
            if (!unbundledFiltered.isEmpty()) {
                result.add(new BundleHeaderEntry(null, "Unbundled", unbundledFiltered.size(), false));
                for (SchematicEntry s : unbundledFiltered) {
                    result.add(new SchematicTileEntry(s, null));
                }
            }
        } else {
            // Specific bundle tab: no headers, just tiles
            if (activeBundleIndex < pinnedBundles.size()) {
                String targetId = pinnedBundles.get(activeBundleIndex).id();
                for (BundleEntry bundle : state.getBundles()) {
                    if (bundle.id().equals(targetId)) {
                        List<SchematicEntry> filtered = filterSchematics(bundle.schematics(), filter);
                        for (SchematicEntry s : filtered) {
                            result.add(new SchematicTileEntry(s, bundle.id()));
                        }
                        break;
                    }
                }
            }
        }

        this.displayList = result;

        // Clamp selection
        if (selectedIndex >= getSchematicCount()) {
            selectedIndex = getSchematicCount() - 1;
        }
    }

    private List<SchematicEntry> filterSchematics(List<SchematicEntry> schematics, String filter) {
        if (filter.isEmpty()) return schematics;
        List<SchematicEntry> result = new ArrayList<>();
        for (SchematicEntry s : schematics) {
            String title = s.title() != null ? s.title().toLowerCase() : "";
            if (title.contains(filter)) {
                result.add(s);
            }
        }
        return result;
    }

    // --- Selection ---

    public int getSelectedIndex() { return selectedIndex; }

    public void setSelectedIndex(int index) {
        this.selectedIndex = Math.max(-1, Math.min(index, getSchematicCount() - 1));
    }

    @Nullable
    public SchematicEntry getSelectedSchematic() {
        if (selectedIndex < 0) return null;
        int schematicIdx = 0;
        for (DisplayEntry entry : displayList) {
            if (entry instanceof SchematicTileEntry tile) {
                if (schematicIdx == selectedIndex) return tile.schematic();
                schematicIdx++;
            }
        }
        return null;
    }

    @Nullable
    public String getSelectedBundleId() {
        if (selectedIndex < 0) return null;
        int schematicIdx = 0;
        for (DisplayEntry entry : displayList) {
            if (entry instanceof SchematicTileEntry tile) {
                if (schematicIdx == selectedIndex) return tile.bundleId();
                schematicIdx++;
            }
        }
        return null;
    }

    public int getSchematicCount() {
        int count = 0;
        for (DisplayEntry entry : displayList) {
            if (entry instanceof SchematicTileEntry) count++;
        }
        return count;
    }

    /** Move selection by delta in the grid (handles row wrapping). */
    public void moveSelection(int cols, int dx, int dy) {
        int count = getSchematicCount();
        if (count == 0) return;

        if (selectedIndex < 0) {
            selectedIndex = 0;
            return;
        }

        int newIndex = selectedIndex + dx + (dy * cols);
        if (newIndex >= 0 && newIndex < count) {
            selectedIndex = newIndex;
        }
    }

    // --- Scroll ---

    public int getScrollOffset() { return scrollOffset; }
    public void setScrollOffset(int offset) { this.scrollOffset = Math.max(0, offset); }

    public void scroll(int delta) {
        this.scrollOffset = Math.max(0, scrollOffset + delta);
    }

    public void clampScroll(int maxScroll) {
        this.scrollOffset = Math.min(scrollOffset, Math.max(0, maxScroll));
    }

    // --- Search ---

    public String getSearchText() { return searchText; }

    public void setSearchText(String text) {
        if (!text.equals(this.searchText)) {
            this.searchText = text;
            this.scrollOffset = 0;
            rebuildDisplayList();
        }
    }

    public boolean isSearchActive() { return searchActive; }
    public void setSearchActive(boolean active) { this.searchActive = active; }

    // --- Status ---

    public String getStatusText() { return statusText; }

    public void setStatus(String text, int durationMs) {
        this.statusText = text;
        this.statusClearAt = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0;
    }

    public void clearStatus() { this.statusText = ""; this.statusClearAt = 0; }

    public void tickStatus() {
        if (statusClearAt > 0 && System.currentTimeMillis() >= statusClearAt) {
            clearStatus();
        }
    }

    // --- Lifecycle ---

    /** Called when library data changes (loaded, refreshed). */
    public void onLibraryUpdated() {
        rebuildPinnedBundles();
        rebuildDisplayList();
    }
}
