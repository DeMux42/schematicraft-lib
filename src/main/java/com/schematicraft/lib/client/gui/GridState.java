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

    /**
     * Which set of schematics the grid is showing.
     *
     * These behave differently on purpose. MY_LIBRARY is fully loaded, so the
     * search box filters it locally and instantly. DISCOVER is a server query, so
     * the search box submits instead of filtering, and results arrive by page.
     */
    public enum Scope { DISCOVER, MY_LIBRARY }

    /** An entry in the flat display list: either a bundle header or a schematic tile. */
    public sealed interface DisplayEntry permits BundleHeaderEntry, SchematicTileEntry {}

    public record BundleHeaderEntry(String bundleId, String name, int count, boolean pinned) implements DisplayEntry {}
    public record SchematicTileEntry(SchematicEntry schematic, String bundleId) implements DisplayEntry {}

    /**
     * Last scope the user chose, remembered for the session.
     *
     * A new GridState is created every time the screen opens, including the
     * returns after an upload or a bundle creation, so without this the view would
     * snap back to Discover and lose the user's place. Defaults to DISCOVER so a
     * first-time user with an empty library still sees downloadable content.
     */
    private static Scope lastScope = Scope.DISCOVER;

    private Scope scope = lastScope;

    // Bundle tab state. The active tab is remembered for the same reason as the
    // scope: reopening after an upload should not silently jump back to "All".
    private static int lastActiveBundleIndex = -1;
    private final List<BundleTabInfo> pinnedBundles = new ArrayList<>();
    private int activeBundleIndex = lastActiveBundleIndex; // -1 = "All"

    // Display list (flat, includes headers when on "All" tab)
    private List<DisplayEntry> displayList = Collections.emptyList();

    // Selection
    private int selectedIndex = -1; // index into displayList (only schematic entries are selectable)

    // Scroll
    private int scrollOffset = 0; // pixels scrolled

    // Search
    private String searchText = "";
    private boolean searchActive = false;

    // Status message shown in the status bar. Text, colour, and expiry live
    // together so there is one owner of "what is the current status".
    private String statusText = "";
    private int statusColor = GuiColors.SUCCESS;
    private long statusClearAt = 0;

    public GridState() {
        rebuildPinnedBundles();
    }

    // --- Scope ---

    public Scope getScope() { return scope; }

    public boolean isDiscover() { return scope == Scope.DISCOVER; }

    /**
     * Switches scope. Selection, scroll, and the search box are per-scope
     * concepts, so they reset rather than carrying a library filter into a server
     * query or vice versa.
     */
    public void setScope(Scope scope) {
        if (this.scope == scope) return;
        this.scope = scope;
        lastScope = scope;
        this.selectedIndex = -1;
        this.scrollOffset = 0;
        this.searchText = "";
        rebuildDisplayList();
    }

    // --- Bundle Tabs ---

    public record BundleTabInfo(String id, String name) {}

    public List<BundleTabInfo> getPinnedBundles() { return Collections.unmodifiableList(pinnedBundles); }

    public int getActiveBundleIndex() { return activeBundleIndex; }

    /** -1 = All, 0+ = pinned bundle index */
    public void setActiveBundleIndex(int index) {
        this.activeBundleIndex = index;
        lastActiveBundleIndex = index;
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

        // The remembered tab can point past the end if bundles were removed, or if
        // it was restored before the library finished loading.
        if (activeBundleIndex >= pinnedBundles.size()) {
            activeBundleIndex = -1;
            lastActiveBundleIndex = -1;
        }
    }

    // --- Display List ---

    public List<DisplayEntry> getDisplayList() { return displayList; }

    public void rebuildDisplayList() {
        List<DisplayEntry> result = new ArrayList<>();
        LibraryState state = LibraryState.get();
        String filter = searchText.toLowerCase().trim();

        if (scope == Scope.DISCOVER) {
            // Flat list, no headers and no local filtering: the server already
            // decided what matches and in what order.
            for (SchematicEntry s : com.schematicraft.lib.core.DiscoverState.get().getResults()) {
                result.add(new SchematicTileEntry(s, null));
            }
            this.displayList = result;
            if (selectedIndex >= getSchematicCount()) {
                selectedIndex = getSchematicCount() - 1;
            }
            return;
        }

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

    /** Move selection by delta in the grid (handles row wrapping and bundle headers). */
    public void moveSelection(int cols, int dx, int dy) {
        int count = getSchematicCount();
        if (count == 0) return;

        if (selectedIndex < 0) {
            selectedIndex = 0;
            return;
        }

        // For left/right within a row, flat offset works fine
        if (dy == 0) {
            int newIndex = selectedIndex + dx;
            if (newIndex >= 0 && newIndex < count) {
                selectedIndex = newIndex;
            }
            return;
        }

        // For up/down, we need visual position awareness.
        // Walk the display list to find the visual (row, col) of each schematic,
        // accounting for bundle headers resetting the column counter.

        int col = 0;
        int row = 0;
        int schematicIdx = 0;
        int[] rows = new int[count];
        int[] colsArr = new int[count];

        for (DisplayEntry entry : displayList) {
            if (entry instanceof BundleHeaderEntry) {
                if (col > 0) {
                    row++;
                    col = 0;
                }
                row++; // The header itself occupies a row
            } else if (entry instanceof SchematicTileEntry) {
                rows[schematicIdx] = row;
                colsArr[schematicIdx] = col;
                schematicIdx++;

                col++;
                if (col >= cols) {
                    col = 0;
                    row++;
                }
            }
        }

        int currentRow = rows[selectedIndex];
        int currentCol = colsArr[selectedIndex];
        int targetRow = currentRow + dy;
        int targetCol = currentCol;

        // Find exact column match on target row first
        int exactIdx = -1;
        for (int i = 0; i < count; i++) {
            if (rows[i] == targetRow && colsArr[i] == targetCol) {
                exactIdx = i;
                break;
            }
        }

        if (exactIdx >= 0) {
            // Exact column match found on the next row
            selectedIndex = exactIdx;
        } else {
            // No exact match (incomplete row). Skip to the next group's row
            // that has the target column, searching further in the direction.
            int searchRow = targetRow + dy;
            int maxRow = rows[count - 1];
            int minRow = rows[0];
            int bestIdx = -1;

            while (searchRow >= minRow && searchRow <= maxRow) {
                for (int i = 0; i < count; i++) {
                    if (rows[i] == searchRow && colsArr[i] == targetCol) {
                        bestIdx = i;
                        break;
                    }
                }
                if (bestIdx != -1) break;
                searchRow += dy;
            }

            if (bestIdx >= 0) {
                selectedIndex = bestIdx;
            } else {
                // Absolute last resort: go to the last item on the incomplete row
                // (only if nothing further exists in the target column)
                int fallbackIdx = -1;
                int fallbackRow = targetRow;
                // Search from targetRow outward
                while (fallbackRow >= minRow && fallbackRow <= maxRow) {
                    int lastOnRow = -1;
                    for (int i = 0; i < count; i++) {
                        if (rows[i] == fallbackRow) {
                            lastOnRow = i;
                        }
                    }
                    if (lastOnRow >= 0) {
                        fallbackIdx = lastOnRow;
                        break;
                    }
                    fallbackRow += dy;
                }
                if (fallbackIdx >= 0) {
                    selectedIndex = fallbackIdx;
                }
            }
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

    public int getStatusColor() { return statusColor; }

    /** Shows a status message. A duration of 0 means it stays until replaced. */
    public void setStatus(String text, int color, int durationMs) {
        this.statusText = text;
        this.statusColor = color;
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
