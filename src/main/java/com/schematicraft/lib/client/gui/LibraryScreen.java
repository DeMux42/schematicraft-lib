package com.schematicraft.lib.client.gui;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.client.CameraMode;
import com.schematicraft.lib.client.ThumbnailCache;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.BundleEntry;
import com.schematicraft.lib.core.DiscoverState;
import com.schematicraft.lib.core.LibraryState;
import com.schematicraft.lib.core.SchematicEntry;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone library screen for browsing and loading schematics.
 * Opens via H keybind or editor GUI buttons. 8-column tile grid with bundle tabs,
 * always-active search, and bottom bar with info/actions.
 *
 * Carries an {@link EditorJourney} supplied by a native editor entry point or
 * the shared keybind. The journey keeps destination, source, and return path
 * intact while child screens are opened.
 */
public class LibraryScreen extends Screen implements SchematicraftScreen {
    private static final Logger LOGGER = LogUtils.getLogger();

    // --- Layout constants ---
    private static final int COLS = 8;
    private static final int TAB_BAR_H = 20;
    private static final int TAB_Y_OFFSET = 3;
    private static final int TAB_H = 14;
    private static final int TAB_PAD_X = 4;
    private static final int TAB_PAD_INNER = 8;
    private static final int TAB_NAME_MAX_LEN = 12;
    private static final int SEARCH_BAR_H = 18;
    private static final int SEARCH_FIELD_H = 14;
    private static final int BOTTOM_BAR_H = 76;
    private static final int STATUS_BAR_H = 14;
    private static final int GRID_PADDING = 6;
    private static final int TILE_GAP = 3;
    private static final int TILE_NAME_H = 12;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_MARGIN = 2;
    private static final int SCROLLBAR_THUMB_MIN_H = 20;
    private static final int BUNDLE_HEADER_H = 16;
    private static final int PREVIEW_X = 4;
    private static final int PREVIEW_W = 100;
    private static final int INFO_X = 110;
    private static final int LOAD_BTN_W = 100;
    private static final int ACTION_BTN_W = 60;
    private static final int ACTION_BTN_H = 16;
    private static final int BTN_GAP = 4;
    private static final int BTN_Y_OFFSET = 40;
    private static final int TARGET_INFO_MARGIN = 140;
    private static final int TARGET_ICON_SIZE = 16;
    /** Space between a device icon and the slot it writes into. */
    private static final int TARGET_SLOT_GAP = 9;
    private static final String PLUS_LABEL = "+";
    private static final int SEARCH_DEBOUNCE_MS = 150;
    private static final int DOUBLE_CLICK_MS = 400;

    // --- Computed layout (set in init) ---
    private int gridTop;
    private int gridBottom;
    private int gridHeight;
    private int gridWidth;
    private int tileSize;    // tile width (also used for column spacing)
    private int tileThumbH;  // thumbnail height (16:9 aspect ratio)
    private int tileTotalH;  // thumbnail + name label
    private int maxScroll;
    private int btnY;

    // State (persists across init calls / resizes)
    private final GridState gridState = new GridState();
    private final EditorJourney journey;
    private final TargetDevice targetDevice;

    // Widgets
    private EditBox searchField;

    // Search debounce
    private long lastSearchKeystroke = 0;
    private String pendingSearchText = null;

    // Double-click detection
    private long lastClickTime = 0;
    private int lastClickIndex = -1;

    // Camera mode round-trip (static to survive screen close/reopen)
    private static boolean pendingCameraReopen = false;
    private static List<Path> pendingCameraImages = null;
    private static String pendingCameraSchematicId = null;
    private static String pendingCameraSchematicTitle = null;
    private static EditorJourney pendingCameraJourney = null;

    // Tooltip state (collected during render, drawn last)
    private List<Component> pendingTooltip = null;
    private int tooltipX, tooltipY;

    // Bounds of the "+" tab, computed during render so clicks match what is drawn.
    private int plusTabX = -1;
    private int plusTabW = 0;

    /**
     * Bundle tab bounds, recorded during render.
     *
     * The bundle tabs start after the scope tabs and a divider, and their widths
     * depend on bundle names, so recomputing the layout in the click handler drifts
     * from what is on screen. Index 0 is the "All" tab, index n+1 is pinned tab n.
     */
    private final List<int[]> bundleTabBounds = new ArrayList<>();

    /** True once the grid has been populated from the current loaded library. */
    private boolean gridSynced = false;

    // Scope tab bounds, recorded during render so clicks match what is drawn.
    private int discoverTabX = -1, discoverTabW = 0;
    private int libraryTabX = -1, libraryTabW = 0;

    // Discover search executor state.
    /** Idle time before an unsubmitted query fires, long enough not to spam. */
    private static final int DISCOVER_DEBOUNCE_MS = 650;
    private static final int DISCOVER_MIN_QUERY = 2;
    private String discoverPendingQuery = null;
    private long discoverLastKeystroke = 0;
    /** Query currently in flight, used to coalesce rather than queue requests. */
    private String discoverInFlight = null;
    /** Newest query typed while a request was in flight. */
    private String discoverQueued = null;
    /** True once the grid has been populated from the current discover results. */
    private boolean discoverSynced = false;

    // Preview expand overlay
    private boolean previewExpanded = false;

    // Tab-cycled focus on the bottom action buttons, for keyboard-only access.
    // Unlike the old search focus, this state is always drawn as a focus ring,
    // so it is never invisible to the user.
    private static final int ACTION_NONE = -1;
    private static final int ACTION_LOAD = 0;
    private static final int ACTION_UPLOAD = 1;
    private static final int ACTION_CAMERA = 2;
    private static final int ACTION_PALETTE = 3;
    private int actionFocus = ACTION_NONE;

    // Suppress the first charTyped after opening (keybind char leaks into search)
    private boolean suppressNextChar = true;

    public LibraryScreen(EditorJourney journey) {
        super(Component.literal("Schematicraft Library"));
        this.journey = journey;
        this.targetDevice = journey.target();
    }

    /** Convenience: resolve the current held-item journey. */
    public LibraryScreen() {
        this(EditorJourney.resolveHeld());
    }

    // --------------------------------------------------
    // Lifecycle
    // --------------------------------------------------

    @Override
    protected void init() {
        super.init();

        // Compute layout from screen dimensions
        gridTop = TAB_BAR_H + SEARCH_BAR_H;
        gridBottom = this.height - BOTTOM_BAR_H - STATUS_BAR_H;
        gridHeight = gridBottom - gridTop;
        gridWidth = this.width - (GRID_PADDING * 2) - SCROLLBAR_W;
        tileSize = (gridWidth - (TILE_GAP * (COLS - 1))) / COLS;
        tileThumbH = tileSize * 9 / 16;
        tileTotalH = tileThumbH + TILE_NAME_H;
        btnY = gridBottom + BTN_Y_OFFSET;

        // Search field. Permanently focused and cannot lose focus: typing always
        // filters, and arrows/Enter always drive the grid. There is deliberately
        // no focus mode to switch, so no keystroke ever changes meaning.
        searchField = new EditBox(this.font, GRID_PADDING, TAB_BAR_H + 2,
                this.width - GRID_PADDING * 2, SEARCH_FIELD_H, Component.literal(""));
        searchField.setMaxLength(100);
        searchField.setHint(Component.literal("Type to filter..."));
        searchField.setValue(gridState.getSearchText());
        searchField.setResponder(text -> {
            if (gridState.isDiscover()) {
                // Server query, so only note the keystroke; tickDiscover decides
                // when to actually send it.
                discoverPendingQuery = text;
                discoverLastKeystroke = System.currentTimeMillis();
            } else {
                pendingSearchText = text;
                lastSearchKeystroke = System.currentTimeMillis();
            }
        });
        searchField.setHint(Component.literal(gridState.isDiscover()
                ? "Search public schematics, Enter to search"
                : "Type to filter..."));
        searchField.setCanLoseFocus(false);
        searchField.setFocused(true);
        this.addRenderableWidget(searchField);
        this.setInitialFocus(searchField);

        // Load library on first open
        if (!LibraryState.get().isLibraryLoaded()
                && !LibraryState.get().isLibraryLoading()
                && ModConfig.hasApiKey()) {
            loadLibrary();
        } else if (LibraryState.get().isLibraryLoaded()) {
            gridState.onLibraryUpdated();
            gridSynced = true;
        }

        recomputeMaxScroll();

        // Camera mode round-trip restore
        if (pendingCameraReopen) {
            pendingCameraReopen = false;
            setStatus("Screenshots captured", GuiColors.SUCCESS, 3000);
        }

        // A message handed over by another screen, e.g. a finished upload.
        if (handoffStatus != null) {
            setStatus(handoffStatus, handoffStatusColor, handoffStatusDurationMs);
            handoffStatus = null;
        } else if (journey.openingNotice() != null) {
            setStatus(journey.openingNotice(), GuiColors.WARNING, 8000);
        }
    }

    // Status handed to this screen by another screen that is closing itself.
    private static String handoffStatus = null;
    private static int handoffStatusColor = GuiColors.SUCCESS;
    private static int handoffStatusDurationMs = 4000;

    /**
     * Queue a status message to show the next time the library screen opens.
     *
     * Lets a screen report its outcome and then close, instead of leaving the user
     * on a finished form to dismiss it themselves.
     */
    public static void queueStatus(String text, int color, int durationMs) {
        handoffStatus = text;
        handoffStatusColor = color;
        handoffStatusDurationMs = durationMs;
    }

    @Override
    public void tick() {
        super.tick();

        // The library is kept in step in both scopes: the bundle tabs and their
        // hotkeys are visible from Discover, so they must not be empty there.
        syncWithLibraryState();

        if (gridState.isDiscover()) {
            tickDiscover();
        } else {
            // Search debounce. Local filtering only, so this can be aggressive.
            if (pendingSearchText != null
                    && System.currentTimeMillis() - lastSearchKeystroke > SEARCH_DEBOUNCE_MS) {
                gridState.setSearchText(pendingSearchText);
                pendingSearchText = null;
                recomputeMaxScroll();

                // Auto-select the first result so Enter loads immediately after
                // typing, with no arrow press needed.
                if (gridState.getSchematicCount() > 0) {
                    gridState.setSelectedIndex(0);
                    gridState.setScrollOffset(0);
                    ensureSelectedVisible();
                }
                actionFocus = ACTION_NONE;
            }
        }

        gridState.tickStatus();
    }

    /**
     * Keeps the grid in step with the shared library state.
     *
     * The grid used to be populated only in {@link #init()}, which broke whenever
     * this screen opened while a load was already in flight: the load had been
     * started elsewhere, so init saw "not loaded, already loading", did nothing,
     * and no one told the grid when the data arrived. Opening the library right
     * after an upload refresh landed in exactly that gap and showed an empty grid.
     *
     * Polling here means the grid recovers no matter who started the load or when
     * it finishes, and it also picks up a stale library by kicking a fresh load.
     */
    private void syncWithLibraryState() {
        LibraryState state = LibraryState.get();

        if (state.isLibraryLoaded()) {
            if (!gridSynced) {
                gridSynced = true;
                gridState.onLibraryUpdated();
                recomputeMaxScroll();
            }
            return;
        }

        // Not loaded: make sure something is actually fetching it.
        gridSynced = false;
        if (!state.isLibraryLoading() && ModConfig.hasApiKey()) {
            loadLibrary();
        }
    }

    /**
     * Drives the discover feed: first load, debounced querying, and syncing
     * results into the grid.
     *
     * Typing stays instant in the text box; only the request is throttled. A query
     * fires on Enter, or once typing has been idle for a moment. While a request
     * is in flight, newer queries replace each other instead of queueing, so a
     * burst of typing costs one extra request rather than one per keystroke.
     */
    private void tickDiscover() {
        DiscoverState discover = DiscoverState.get();

        // First open: fill the feed with popular public schematics.
        if (!discover.isLoaded() && !discover.isLoading()
                && discover.getError() == null && ModConfig.hasApiKey()) {
            fetchDiscover("", 1);
            return;
        }

        // Debounced query submission.
        if (discoverPendingQuery != null
                && System.currentTimeMillis() - discoverLastKeystroke > DISCOVER_DEBOUNCE_MS) {
            String q = discoverPendingQuery.trim();
            discoverPendingQuery = null;
            // Treat a too-short query as a request to browse again, so clearing
            // the box returns to the feed instead of stranding the user.
            if (q.length() >= DISCOVER_MIN_QUERY || q.isEmpty()) {
                submitDiscoverQuery(q);
            }
        }

        // Sync results into the grid when a fetch lands.
        if (!discover.isLoading()) {
            if (!discoverSynced) {
                discoverSynced = true;
                gridState.rebuildDisplayList();
                recomputeMaxScroll();
                if (gridState.getSchematicCount() > 0 && gridState.getSelectedIndex() < 0) {
                    gridState.setSelectedIndex(0);
                }
            }
            // A query typed while the last request was running.
            if (discoverQueued != null) {
                String q = discoverQueued;
                discoverQueued = null;
                submitDiscoverQuery(q);
            }
        }
    }

    /** Runs a query now, or defers it if a request is already in flight. */
    private void submitDiscoverQuery(String query) {
        if (query.equals(DiscoverState.get().getQuery())
                && DiscoverState.get().isLoaded()
                && DiscoverState.get().getError() == null) {
            return; // Already showing these results.
        }
        if (discoverInFlight != null) {
            discoverQueued = query;
            return;
        }
        gridState.setSelectedIndex(-1);
        gridState.setScrollOffset(0);
        fetchDiscover(query, 1);
    }

    /** Fetches one page of public schematics. */
    private void fetchDiscover(String query, int page) {
        if (!ModConfig.hasApiKey()) return;

        DiscoverState discover = DiscoverState.get();
        discover.beginFetch(query, page);
        discoverInFlight = query;
        discoverSynced = false;
        if (page <= 1) {
            setStatus(query.isEmpty() ? "Loading popular schematics..." : "Searching...",
                    GuiColors.INFO, 0);
        }

        // Empty query browses by popularity, which is what makes the first open
        // useful. A real query is ordered by relevance instead.
        String sort = query.isEmpty() ? "popular" : null;

        SchematiCraftAPIWrapper.get().searchPublic(query, page, sort)
                .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    discoverInFlight = null;
                    discover.addPage(result.results(), result.page(),
                            result.hasMore(), result.total());
                    discoverSynced = false;
                    if (page <= 1) {
                        setStatus(result.total() + " public schematics",
                                GuiColors.SUCCESS, 2500);
                    } else {
                        gridState.clearStatus();
                    }
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        discoverInFlight = null;
                        String msg = SchematiCraftAPIWrapper.rootMessage(ex);
                        // Rate limiting is expected if someone hammers search.
                        if (msg.contains("429")) {
                            msg = "Too many searches, wait a moment";
                        }
                        discover.setError(msg);
                        discoverSynced = false;
                        setStatus(msg, GuiColors.ERROR, 5000);
                    });
                    return null;
                });
    }

    /** Loads the next page when the user scrolls to the end of the feed. */
    private void maybeLoadMoreDiscover() {
        DiscoverState discover = DiscoverState.get();
        if (!gridState.isDiscover() || discover.isLoading() || !discover.hasMore()) return;
        if (discoverInFlight != null) return;

        // Within one row of the bottom.
        if (gridState.getScrollOffset() >= maxScroll - tileTotalH) {
            fetchDiscover(discover.getQuery(), discover.getPage() + 1);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        journey.returnToOrigin();
    }

    // --------------------------------------------------
    // Rendering
    // --------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        pendingTooltip = null;

        // Background
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);

        // Bundle tab bar
        renderTabBar(graphics, mouseX, mouseY);

        // Search field rendered by super (it's a widget)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Tile grid (clipped)
        renderGrid(graphics, mouseX, mouseY);

        // Bottom bar
        renderBottomBar(graphics, mouseX, mouseY);

        // Status bar
        renderStatusBar(graphics);

        // Tooltips drawn last (on top of everything)
        if (pendingTooltip != null && !previewExpanded) {
            graphics.renderComponentTooltip(this.font, pendingTooltip, tooltipX, tooltipY);
        }

        // Preview expand overlay (on top of everything)
        renderPreviewOverlay(graphics, mouseX, mouseY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Skip default dirt background
    }

    private void renderTabBar(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, this.width, TAB_BAR_H, GuiColors.PANEL_BG);
        graphics.fill(0, TAB_BAR_H - 1, this.width, TAB_BAR_H, GuiColors.BORDER_SEPARATOR);

        int x = GRID_PADDING;

        // Scope tabs first. These are the primary split: other people's
        // schematics versus your own, never mixed in one list.
        x = renderScopeTab(graphics, x, GridState.Scope.DISCOVER, "Discover", mouseX, mouseY);
        x = renderScopeTab(graphics, x, GridState.Scope.MY_LIBRARY, "My Library", mouseX, mouseY);

        // Divider between scope tabs and the library's bundle tabs.
        graphics.fill(x, TAB_Y_OFFSET + 2, x + 1, TAB_Y_OFFSET + TAB_H - 2,
                GuiColors.BORDER_SEPARATOR);
        x += TAB_PAD_X + 1;

        // Bundle tabs stay visible in both scopes so Ctrl+1-7 always means the same
        // thing. In Discover they read as inactive, and using one switches back to
        // the library rather than doing nothing.
        boolean inLibrary = !gridState.isDiscover();
        bundleTabBounds.clear();

        // "All" tab
        boolean allActive = inLibrary && gridState.getActiveBundleIndex() == -1;
        int allText = allActive ? GuiColors.BUNDLE_TAB_ACTIVE : GuiColors.TEXT_SECONDARY;
        String allLabel = "All";
        int allW = this.font.width(allLabel) + TAB_PAD_INNER;
        if (allActive) graphics.fill(x, TAB_Y_OFFSET, x + allW, TAB_Y_OFFSET + TAB_H, GuiColors.BUNDLE_TAB_ACTIVE_BG);
        graphics.drawString(this.font, allLabel, x + TAB_PAD_X, TAB_Y_OFFSET + 3, allText, false);
        if (isOver(mouseX, mouseY, x, TAB_Y_OFFSET, allW, TAB_H)) {
            scheduleTooltip(List.of(
                    Component.literal("All schematics"),
                    Component.literal("\u00a78Ctrl+Tab / Ctrl+Shift+Tab to cycle")
            ), mouseX, mouseY);
        }
        bundleTabBounds.add(new int[] { x, allW });
        x += allW + TAB_PAD_X;

        // Pinned bundle tabs
        List<GridState.BundleTabInfo> pinned = gridState.getPinnedBundles();
        for (int i = 0; i < pinned.size(); i++) {
            GridState.BundleTabInfo tab = pinned.get(i);
            boolean active = inLibrary && gridState.getActiveBundleIndex() == i;
            int textColor = active ? GuiColors.BUNDLE_TAB_ACTIVE : GuiColors.TEXT_SECONDARY;

            String label = "[" + (i + 1) + "] " + truncate(tab.name(), TAB_NAME_MAX_LEN);
            int tabW = this.font.width(label) + TAB_PAD_INNER;

            if (x + tabW > this.width - GRID_PADDING) break;

            if (active) graphics.fill(x, TAB_Y_OFFSET, x + tabW, TAB_Y_OFFSET + TAB_H, GuiColors.BUNDLE_TAB_ACTIVE_BG);
            graphics.drawString(this.font, label, x + TAB_PAD_X, TAB_Y_OFFSET + 3, textColor, false);

            if (isOver(mouseX, mouseY, x, TAB_Y_OFFSET, tabW, TAB_H)) {
                scheduleTooltip(List.of(
                        Component.literal(tab.name()),
                        Component.literal("\u00a78Ctrl+" + (i + 1) + " to jump")
                ), mouseX, mouseY);
            }

            bundleTabBounds.add(new int[] { x, tabW });
            x += tabW + TAB_PAD_X;
        }

        // "+" tab, always last, for creating a bundle.
        int plusW = this.font.width(PLUS_LABEL) + TAB_PAD_INNER;
        if (x + plusW <= this.width - GRID_PADDING) {
            boolean plusHover = isOver(mouseX, mouseY, x, TAB_Y_OFFSET, plusW, TAB_H);
            if (plusHover) {
                graphics.fill(x, TAB_Y_OFFSET, x + plusW, TAB_Y_OFFSET + TAB_H,
                        GuiColors.TILE_HOVER_BG);
                scheduleTooltip(List.of(Component.literal("New bundle")), mouseX, mouseY);
            }
            graphics.drawString(this.font, PLUS_LABEL, x + TAB_PAD_X, TAB_Y_OFFSET + 3,
                    plusHover ? GuiColors.SELECTED : GuiColors.TEXT_SECONDARY, false);
        }
        plusTabX = x;
        plusTabW = plusW;
    }

    /** Switches scope and resets the per-scope input state. */
    private void switchScope(GridState.Scope scope) {
        if (gridState.getScope() == scope) return;

        gridState.setScope(scope);
        actionFocus = ACTION_NONE;
        pendingSearchText = null;
        discoverPendingQuery = null;
        discoverQueued = null;
        discoverSynced = false;
        gridState.clearStatus();

        if (searchField != null) {
            searchField.setValue("");
            searchField.setHint(Component.literal(scope == GridState.Scope.DISCOVER
                    ? "Search public schematics, Enter to search"
                    : "Type to filter..."));
        }
        recomputeMaxScroll();
    }

    /** Draws one scope tab and records its bounds for hit-testing. */
    private int renderScopeTab(GuiGraphics graphics, int x, GridState.Scope scope,
                               String label, int mouseX, int mouseY) {
        boolean active = gridState.getScope() == scope;
        int w = this.font.width(label) + TAB_PAD_INNER;

        if (active) {
            graphics.fill(x, TAB_Y_OFFSET, x + w, TAB_Y_OFFSET + TAB_H,
                    GuiColors.BUNDLE_TAB_ACTIVE_BG);
        } else if (isOver(mouseX, mouseY, x, TAB_Y_OFFSET, w, TAB_H)) {
            graphics.fill(x, TAB_Y_OFFSET, x + w, TAB_Y_OFFSET + TAB_H,
                    GuiColors.TILE_HOVER_BG);
        }
        graphics.drawString(this.font, label, x + TAB_PAD_X, TAB_Y_OFFSET + 3,
                active ? GuiColors.BUNDLE_TAB_ACTIVE : GuiColors.TEXT_SECONDARY, false);

        if (scope == GridState.Scope.DISCOVER) {
            discoverTabX = x;
            discoverTabW = w;
        } else {
            libraryTabX = x;
            libraryTabW = w;
        }
        return x + w + TAB_PAD_X;
    }

    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.enableScissor(0, gridTop, this.width - SCROLLBAR_W, gridBottom);

        List<GridState.DisplayEntry> displayList = gridState.getDisplayList();
        int scrollOffset = gridState.getScrollOffset();

        int y = gridTop - scrollOffset;
        int col = 0;
        int schematicIdx = 0;

        for (GridState.DisplayEntry entry : displayList) {
            if (entry instanceof GridState.BundleHeaderEntry header) {
                if (col > 0) {
                    y += tileTotalH + TILE_GAP;
                    col = 0;
                }
                if (y + BUNDLE_HEADER_H > gridTop && y < gridBottom) {
                    graphics.fill(GRID_PADDING, y + 2,
                            this.width - GRID_PADDING - SCROLLBAR_W, y + 2 + TILE_NAME_H,
                            GuiColors.BUNDLE_HEADER_DIM);
                    String headerText = header.name() + " (" + header.count() + ")";
                    graphics.drawString(this.font, headerText,
                            GRID_PADDING + TAB_PAD_X, y + 4, GuiColors.BUNDLE_HEADER, false);
                }
                y += BUNDLE_HEADER_H + TILE_GAP;
            } else if (entry instanceof GridState.SchematicTileEntry tile) {
                int tileX = GRID_PADDING + col * (tileSize + TILE_GAP);
                int tileY = y;

                if (tileY + tileTotalH > gridTop && tileY < gridBottom) {
                    renderTile(graphics, tile, tileX, tileY, schematicIdx, mouseX, mouseY);
                }

                col++;
                if (col >= COLS) {
                    col = 0;
                    y += tileTotalH + TILE_GAP;
                }
                schematicIdx++;
            }
        }

        int contentHeight = y + (col > 0 ? tileTotalH + TILE_GAP : 0) - gridTop + scrollOffset;
        maxScroll = Math.max(0, contentHeight - gridHeight);
        gridState.clampScroll(maxScroll);

        // Empty and loading states, so the grid never sits blank without saying why.
        if (displayList.isEmpty()) {
            renderGridPlaceholder(graphics);
        } else if (gridState.isDiscover() && DiscoverState.get().isLoading()) {
            graphics.drawCenteredString(this.font, "Loading more...",
                    this.width / 2, gridBottom - 14, GuiColors.TEXT_DIM);
        }

        graphics.disableScissor();

        renderScrollbar(graphics, mouseX, mouseY);
    }

    /** Explains an empty grid, which differs by scope and cause. */
    private void renderGridPlaceholder(GuiGraphics graphics) {
        int cx = this.width / 2;
        int cy = gridTop + (gridBottom - gridTop) / 2;

        if (!ModConfig.hasApiKey()) {
            graphics.drawCenteredString(this.font, "Set your API key to get started",
                    cx, cy, GuiColors.TEXT_SECONDARY);
            return;
        }

        if (gridState.isDiscover()) {
            DiscoverState d = DiscoverState.get();
            if (d.isLoading()) {
                graphics.drawCenteredString(this.font, "Loading public schematics...",
                        cx, cy, GuiColors.TEXT_SECONDARY);
            } else if (d.getError() != null) {
                graphics.drawCenteredString(this.font, d.getError(), cx, cy - 6,
                        GuiColors.ERROR);
                graphics.drawCenteredString(this.font, "Press Enter to try again",
                        cx, cy + 6, GuiColors.TEXT_DIM);
            } else if (!d.getQuery().isEmpty()) {
                graphics.drawCenteredString(this.font,
                        "No public schematics match \"" + d.getQuery() + "\"",
                        cx, cy - 6, GuiColors.TEXT_SECONDARY);
                graphics.drawCenteredString(this.font, "Clear the box to browse popular builds",
                        cx, cy + 6, GuiColors.TEXT_DIM);
            } else {
                graphics.drawCenteredString(this.font, "No public schematics found",
                        cx, cy, GuiColors.TEXT_SECONDARY);
            }
            return;
        }

        // My Library
        if (!gridState.getSearchText().isEmpty()) {
            graphics.drawCenteredString(this.font, "Nothing in your library matches",
                    cx, cy, GuiColors.TEXT_SECONDARY);
        } else if (LibraryState.get().isLibraryLoading()) {
            graphics.drawCenteredString(this.font, "Loading your library...",
                    cx, cy, GuiColors.TEXT_SECONDARY);
        } else {
            graphics.drawCenteredString(this.font, "Your library is empty",
                    cx, cy - 6, GuiColors.TEXT_SECONDARY);
            graphics.drawCenteredString(this.font, "Upload a build, or grab one from Discover",
                    cx, cy + 6, GuiColors.TEXT_DIM);
        }
    }

    private void renderTile(GuiGraphics graphics, GridState.SchematicTileEntry tile,
                            int x, int y, int schematicIdx, int mouseX, int mouseY) {
        SchematicEntry schematic = tile.schematic();
        boolean selected = schematicIdx == gridState.getSelectedIndex();
        boolean hovered = isOver(mouseX, mouseY, x, y, tileSize, tileTotalH);

        int bg = selected ? GuiColors.TILE_SELECTED_BG
                : (hovered ? GuiColors.TILE_HOVER_BG : GuiColors.TILE_BG);
        int border = selected ? GuiColors.TILE_SELECTED_BORDER
                : (hovered ? GuiColors.TILE_HOVER_BORDER : GuiColors.TILE_BORDER);

        graphics.fill(x, y, x + tileSize, y + tileThumbH, bg);
        drawBorder(graphics, x, y, tileSize, tileThumbH, border);

        // Thumbnail
        ResourceLocation tex = ThumbnailCache.get().getTexture(
                schematic.id(), schematic.thumbnailUrl());
        if (tex != null) {
            graphics.blit(tex, x + 1, y + 1, 0, 0,
                    tileSize - 2, tileThumbH - 2, tileSize - 2, tileThumbH - 2);
        } else {
            graphics.fill(x + 1, y + 1, x + tileSize - 1, y + tileThumbH - 1, 0xFF0A0A0A);
            if (schematic.thumbnailUrl() != null && !schematic.thumbnailUrl().isEmpty()) {
                // Has a thumbnail URL but still loading
                int dotCount = (int) ((System.currentTimeMillis() / 500) % 4);
                String dots = ".".repeat(dotCount);
                graphics.drawCenteredString(this.font, dots,
                        x + tileSize / 2, y + tileThumbH / 2 - 4, GuiColors.TEXT_DIM);
            } else {
                // No thumbnail available
                graphics.drawCenteredString(this.font, "No image",
                        x + tileSize / 2, y + tileThumbH / 2 - 4, GuiColors.TEXT_DIM);
            }
        }

        // Name below thumbnail, with the block count appended so size is visible
        // before selecting. Oversized entries are colored so they stand out.
        String name = schematic.title() != null ? schematic.title() : "Untitled";
        LoadLimits tileLimits = getLoadLimits(targetDevice);
        boolean tileTooBig = schematic.hasBlockCount()
                && tileLimits.exceedsHard(schematic.blockCount());
        boolean tileOverSoft = schematic.hasBlockCount()
                && tileLimits.exceedsSoft(schematic.blockCount());
        if (schematic.hasBlockCount()) {
            name += " (" + formatCountShort(schematic.blockCount()) + ")";
        }
        String truncated = truncateToWidth(name, tileSize - 4);
        int nameColor = tileTooBig ? GuiColors.ERROR
                : (selected ? GuiColors.SELECTED
                : (hovered ? GuiColors.HOVER_TEXT
                : (tileOverSoft ? GuiColors.WARNING : GuiColors.TILE_NAME)));
        graphics.drawString(this.font, truncated, x + 2, y + tileThumbH + 2, nameColor, false);

        // Tooltip on hover
        if (hovered) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal(
                    schematic.title() != null ? schematic.title() : "Untitled"));
            if (schematic.description() != null && !schematic.description().isEmpty()) {
                tooltip.add(Component.literal(
                        "\u00a77" + truncate(schematic.description(), 50)));
            }
            if (tile.bundleId() != null) {
                String bundleName = findBundleName(tile.bundleId());
                if (bundleName != null) {
                    tooltip.add(Component.literal("\u00a7eBundle: " + bundleName));
                }
            }
            if (schematic.downloadCount() > 0) {
                tooltip.add(Component.literal(
                        "\u00a78" + schematic.downloadCount() + " downloads"));
            }
            if (schematic.hasDimensions()) {
                tooltip.add(Component.literal("\u00a78" + schematic.dimensionsLabel()));
            }
            if (schematic.hasBlockCount()) {
                LoadLimits limits = getLoadLimits(targetDevice);
                int count = schematic.blockCount();
                if (limits.exceedsHard(count)) {
                    tooltip.add(Component.literal("\u00a7c" + formatCount(count)
                            + " blocks, too big for " + targetDevice.getDisplayName()));
                } else if (limits.exceedsSoft(count)) {
                    tooltip.add(Component.literal("\u00a7e" + formatCount(count)
                            + " blocks, above " + targetDevice.getDisplayName() + " limit"));
                } else {
                    tooltip.add(Component.literal("\u00a78" + formatCount(count) + " blocks"));
                }
            }
            scheduleTooltip(tooltip, mouseX, mouseY);
        }
    }

    private void renderScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        int sbX = this.width - SCROLLBAR_W - SCROLLBAR_MARGIN;
        int sbHeight = gridBottom - gridTop;

        graphics.fill(sbX, gridTop, sbX + SCROLLBAR_W, gridBottom, GuiColors.SCROLLBAR_TRACK);

        if (maxScroll <= 0) return;

        int thumbHeight = Math.max(SCROLLBAR_THUMB_MIN_H,
                (int) ((float) gridHeight / (gridHeight + maxScroll) * sbHeight));
        float scrollRatio = (float) gridState.getScrollOffset() / maxScroll;
        int thumbY = gridTop + (int) (scrollRatio * (sbHeight - thumbHeight));

        boolean thumbHovered = isOver(mouseX, mouseY, sbX, thumbY, SCROLLBAR_W, thumbHeight);
        int thumbColor = thumbHovered
                ? GuiColors.SCROLLBAR_THUMB_HOVER : GuiColors.SCROLLBAR_THUMB;
        graphics.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbHeight, thumbColor);
    }

    private void renderBottomBar(GuiGraphics graphics, int mouseX, int mouseY) {
        int barY = this.height - BOTTOM_BAR_H - STATUS_BAR_H;

        graphics.fill(0, barY, this.width, this.height - STATUS_BAR_H, GuiColors.BOTTOM_BAR_BG);
        graphics.fill(0, barY, this.width, barY + 1, GuiColors.BORDER_SEPARATOR);

        SchematicEntry selected = gridState.getSelectedSchematic();

        // Left: Preview area (progressive: thumbnail -> 3D render when cached)
        int previewRight = PREVIEW_X + PREVIEW_W;
        int previewTop = barY + 4;
        int previewH = BOTTOM_BAR_H - 8;
        graphics.fill(PREVIEW_X, previewTop, previewRight, previewTop + previewH, 0xFF0A0A0A);

        if (selected != null) {
            var cache = com.schematicraft.lib.core.SchematicDataCache.get();
            var renderer = com.schematicraft.lib.client.preview.SchematicPreviewRenderer.get();

            if (cache.has(selected.id())) {
                // 3D render: parse and prepare if needed
                ensurePreviewPrepared(selected.id());
                if (renderer.isReady()) {
                    graphics.flush();
                    renderer.renderAutoRotate(graphics, PREVIEW_X + 1, previewTop + 1,
                            PREVIEW_W - 2, previewH - 2);
                } else {
                    renderThumbnailPreview(graphics, selected, previewTop, previewH);
                }
            } else {
                renderThumbnailPreview(graphics, selected, previewTop, previewH);
            }

            // Click hint on hover
            if (isOver(mouseX, mouseY, PREVIEW_X, previewTop, PREVIEW_W, previewH)) {
                drawBorder(graphics, PREVIEW_X, previewTop, PREVIEW_W, previewH, GuiColors.SELECTED);
                scheduleTooltip(List.of(Component.literal("Click to expand")), mouseX, mouseY);
            }
        } else {
            graphics.drawCenteredString(this.font, "No selection",
                    PREVIEW_X + PREVIEW_W / 2, barY + 34, GuiColors.TEXT_DIM);
        }

        // Center: Info + Actions
        if (selected != null) {
            String title = selected.title() != null ? selected.title() : "Untitled";
            graphics.drawString(this.font, title, INFO_X, barY + 6,
                    GuiColors.SELECTED, false);

            // In Discover the useful second line is who made it, not a bundle.
            String subtitle;
            if (gridState.isDiscover()) {
                subtitle = selected.ownerName() != null
                        ? "by " + selected.ownerName() : "public";
            } else {
                String bundleId = gridState.getSelectedBundleId();
                String bundleName = bundleId != null ? findBundleName(bundleId) : "Unbundled";
                subtitle = bundleName != null ? bundleName : "";
            }
            graphics.drawString(this.font, "\u00a77" + subtitle,
                    INFO_X, barY + 18, GuiColors.TEXT_SECONDARY, false);

            String meta = selected.downloadCount() > 0
                    ? selected.downloadCount() + " downloads"
                    : "";
            if (selected.hasBlockCount()) {
                if (!meta.isEmpty()) meta += "  ";
                meta += formatCount(selected.blockCount()) + " blocks";
            }
            if (selected.hasDimensions()) {
                if (!meta.isEmpty()) meta += "  ";
                meta += selected.dimensionsLabel();
            }
            if (!meta.isEmpty()) {
                LoadLimits limits = getLoadLimits(targetDevice);
                int metaColor = GuiColors.TEXT_DIM;
                if (selected.hasBlockCount()) {
                    if (limits.exceedsHard(selected.blockCount())) {
                        metaColor = GuiColors.ERROR;
                    } else if (limits.exceedsSoft(selected.blockCount())) {
                        metaColor = GuiColors.WARNING;
                    }
                }
                graphics.drawString(this.font, meta,
                        INFO_X, barY + 28, metaColor, false);
            }
        }

        // Load button
        boolean loadEnabled = selected != null && targetDevice.isAvailable();
        int loadBg = loadEnabled ? GuiColors.BTN_PRIMARY_BG : GuiColors.BTN_BG;
        int loadBorder = loadEnabled ? GuiColors.BTN_PRIMARY_BORDER : GuiColors.BORDER_DARK;
        int loadTextColor = loadEnabled ? GuiColors.BTN_PRIMARY_TEXT : GuiColors.TEXT_DISABLED;
        String loadLabel = targetDevice.getLoadButtonText();

        graphics.fill(INFO_X, btnY, INFO_X + LOAD_BTN_W, btnY + ACTION_BTN_H, loadBg);
        drawBorder(graphics, INFO_X, btnY, LOAD_BTN_W, ACTION_BTN_H, loadBorder);
        graphics.drawCenteredString(this.font, loadLabel,
                INFO_X + LOAD_BTN_W / 2, btnY + 4, loadTextColor);

        if (isOver(mouseX, mouseY, INFO_X, btnY, LOAD_BTN_W, ACTION_BTN_H)) {
            if (loadEnabled) {
                scheduleTooltip(List.of(
                        Component.literal(targetDevice.getLoadButtonText()),
                        Component.literal("\u00a77" + targetDevice.getDestinationHint()),
                        Component.literal("\u00a78Enter to load"),
                        Component.literal("\u00a78Mode: " + targetDevice.getModeLabel())
                ), mouseX, mouseY);
            } else {
                scheduleTooltip(List.of(
                        Component.literal("No target device"),
                        Component.literal("\u00a78Hold a Copy/Paste gadget or open a table")
                ), mouseX, mouseY);
            }
        }

        // Upload button. Availability belongs to this journey, not to whichever
        // editor happened to initialize last.
        int uploadBtnX = INFO_X + LOAD_BTN_W + BTN_GAP;
        UploadSource uploadSource = journey.uploadSource();
        boolean uploadEnabled = uploadSource != null && uploadSource.isReady();
        int uploadBg = uploadEnabled ? GuiColors.BTN_UPLOAD_BG : GuiColors.BTN_BG;
        int uploadBorder = uploadEnabled ? GuiColors.BTN_UPLOAD_BORDER : GuiColors.BORDER_DARK;
        int uploadText = uploadEnabled ? GuiColors.BTN_UPLOAD_TEXT : GuiColors.TEXT_DISABLED;
        graphics.fill(uploadBtnX, btnY, uploadBtnX + ACTION_BTN_W,
                btnY + ACTION_BTN_H, uploadBg);
        drawBorder(graphics, uploadBtnX, btnY, ACTION_BTN_W,
                ACTION_BTN_H, uploadBorder);
        graphics.drawCenteredString(this.font, "Upload",
                uploadBtnX + ACTION_BTN_W / 2, btnY + 4, uploadText);

        if (isOver(mouseX, mouseY, uploadBtnX, btnY, ACTION_BTN_W, ACTION_BTN_H)) {
            String sourceLine = uploadSource == null
                    ? "No upload source here"
                    : (uploadEnabled ? "From " + uploadSource.displayName()
                    : uploadSource.emptyHint());
            scheduleTooltip(List.of(
                    Component.literal(uploadEnabled ? "Upload build" : "Upload unavailable"),
                    Component.literal("\u00a78" + sourceLine),
                    Component.literal("\u00a78Shortcut: Ctrl+U")
            ), mouseX, mouseY);
        }

        // Camera button
        int camBtnX = uploadBtnX + ACTION_BTN_W + BTN_GAP;
        graphics.fill(camBtnX, btnY, camBtnX + ACTION_BTN_W,
                btnY + ACTION_BTN_H, GuiColors.BTN_CAMERA_BG);
        drawBorder(graphics, camBtnX, btnY, ACTION_BTN_W,
                ACTION_BTN_H, GuiColors.BTN_CAMERA_BORDER);
        graphics.drawCenteredString(this.font, "[C]amera",
                camBtnX + ACTION_BTN_W / 2, btnY + 4, GuiColors.BTN_CAMERA_TEXT);

        if (isOver(mouseX, mouseY, camBtnX, btnY, ACTION_BTN_W, ACTION_BTN_H)) {
            scheduleTooltip(List.of(
                    Component.literal("Enter camera mode"),
                    Component.literal("\u00a78Take screenshots for upload"),
                    Component.literal("\u00a78Shortcut: Ctrl+K")
            ), mouseX, mouseY);
        }

        // Palette button
        int palBtnX = camBtnX + ACTION_BTN_W + BTN_GAP;
        boolean palEnabled = selected != null;
        int palBg = palEnabled ? 0xFF1A1A2A : GuiColors.BTN_BG;
        int palBorder = palEnabled ? 0xFF3A3A5A : GuiColors.BORDER_DARK;
        int palText = palEnabled ? 0xFFAAAAFF : GuiColors.TEXT_DISABLED;
        graphics.fill(palBtnX, btnY, palBtnX + ACTION_BTN_W,
                btnY + ACTION_BTN_H, palBg);
        drawBorder(graphics, palBtnX, btnY, ACTION_BTN_W,
                ACTION_BTN_H, palBorder);
        graphics.drawCenteredString(this.font, "Palette",
                palBtnX + ACTION_BTN_W / 2, btnY + 4, palText);

        if (isOver(mouseX, mouseY, palBtnX, btnY, ACTION_BTN_W, ACTION_BTN_H)) {
            scheduleTooltip(List.of(
                    Component.literal("Change block palette"),
                    Component.literal("\u00a78Swap blocks before loading"),
                    Component.literal("\u00a78Shortcut: Ctrl+P")
            ), mouseX, mouseY);
        }

        // Focus ring for the Tab-cycled action, drawn last so it sits on top.
        if (actionFocus != ACTION_NONE) {
            int ringX = switch (actionFocus) {
                case ACTION_LOAD -> INFO_X;
                case ACTION_UPLOAD -> uploadBtnX;
                case ACTION_CAMERA -> camBtnX;
                default -> palBtnX;
            };
            int ringW = actionFocus == ACTION_LOAD ? LOAD_BTN_W : ACTION_BTN_W;
            drawBorder(graphics, ringX - 1, btnY - 1, ringW + 2, ACTION_BTN_H + 2,
                    GuiColors.SELECTED);
        }

        // Right: Target device info
        renderTargetPanel(graphics, barY, mouseX, mouseY);
    }

    /** Render the independent download destination and upload source. */
    private void renderTargetPanel(GuiGraphics graphics, int barY, int mouseX, int mouseY) {
        int panelX = this.width - TARGET_INFO_MARGIN;
        boolean available = targetDevice.isAvailable();
        UploadSource source = journey.uploadSource();
        boolean sourceReady = source != null && source.isReady();

        TargetCatalog.Entry entry = available
                ? TargetCatalog.get(targetDevice.getType()) : null;
        boolean hasReceiver = entry != null && entry.hasReceiver();
        ItemStack receiverStack = hasReceiver ? entry.receiverStack() : ItemStack.EMPTY;
        boolean receiverReady = !receiverStack.isEmpty();

        int iconsWidth = hasReceiver
                ? TARGET_ICON_SIZE * 2 + TARGET_SLOT_GAP
                : TARGET_ICON_SIZE;
        int textWidth = TARGET_INFO_MARGIN - GRID_PADDING - iconsWidth - 4;
        int receiverX = this.width - TARGET_ICON_SIZE - GRID_PADDING;
        int deviceX = hasReceiver
                ? receiverX - TARGET_ICON_SIZE - TARGET_SLOT_GAP
                : receiverX;
        float phase = (System.currentTimeMillis() % 2000L) / 2000f;

        graphics.drawString(this.font, "Download to:", panelX, barY + 4,
                GuiColors.TEXT_DIM, false);
        if (available) {
            int color = targetDevice.getMode() == TargetDevice.Mode.SERVER
                    ? GuiColors.TARGET_SERVER : GuiColors.TARGET_CLIENT;
            graphics.drawString(this.font,
                    truncateToWidth(targetDevice.getDisplayName(), textWidth),
                    panelX, barY + 16, color, false);
            String detail = hasReceiver
                    ? (receiverReady ? "into " + receiverStack.getHoverName().getString()
                    : entry.receiver().emptyHint())
                    : targetDevice.getModeLabel();
            graphics.drawString(this.font, "\u00a78" + truncateToWidth(detail, textWidth),
                    panelX, barY + 28,
                    hasReceiver && !receiverReady ? GuiColors.WARNING : GuiColors.TEXT_DIM,
                    false);
        } else {
            graphics.drawString(this.font, "None", panelX, barY + 16,
                    GuiColors.TARGET_NONE, false);
            graphics.drawString(this.font, "\u00a78Browse only", panelX, barY + 28,
                    GuiColors.TEXT_DIM, false);
        }

        int targetY = barY + 18;
        ItemStack targetIcon = entry != null ? entry.icon() : ItemStack.EMPTY;
        if (!targetIcon.isEmpty()) {
            drawTargetIcon(graphics, targetIcon, deviceX, targetY, phase, 0x2055FF55);
        } else {
            drawEmptyTargetSlot(graphics, deviceX, targetY, "?");
        }
        if (hasReceiver) {
            int lineY = targetY + TARGET_ICON_SIZE / 2;
            graphics.fill(deviceX + TARGET_ICON_SIZE + 1, lineY,
                    receiverX - 1, lineY + 1,
                    receiverReady ? GuiColors.SUCCESS : GuiColors.BORDER_DARK);
            if (receiverReady) {
                drawTargetIcon(graphics, receiverStack, receiverX, targetY,
                        phase - 0.12f, 0x2055FF55);
            } else {
                drawEmptyTargetSlot(graphics, receiverX, targetY, "+");
            }
        }

        graphics.drawString(this.font, "Upload from:", panelX, barY + 44,
                GuiColors.TEXT_DIM, false);
        String sourceName = source != null ? source.displayName() : "None";
        int sourceColor = sourceReady ? GuiColors.SUCCESS
                : (source != null ? GuiColors.WARNING : GuiColors.TARGET_NONE);
        graphics.drawString(this.font, truncateToWidth(sourceName, textWidth),
                panelX, barY + 56, sourceColor, false);

        ItemStack sourceIcon = source != null ? source.icon() : ItemStack.EMPTY;
        int sourceY = barY + 50;
        if (!sourceIcon.isEmpty()) {
            drawTargetIcon(graphics, sourceIcon, receiverX, sourceY,
                    phase - 0.2f, sourceReady ? 0x2055FF55 : 0x20FFAA33);
        } else {
            drawEmptyTargetSlot(graphics, receiverX, sourceY,
                    source != null ? "!" : "-");
        }

        if (isOver(mouseX, mouseY, panelX, barY + 2,
                TARGET_INFO_MARGIN, BOTTOM_BAR_H - 4)) {
            scheduleTooltip(buildCompatibilityTooltip(), mouseX, mouseY);
        }
    }

    /** One live target icon, floating gently, with a soft halo behind it. */
    private void drawTargetIcon(GuiGraphics graphics, ItemStack stack,
                                int x, int y, float phase, int haloColor) {
        int bob = (int) Math.round(Math.sin(phase * Math.PI * 2) * 2.0);
        graphics.fill(x - 2, y - 2 + bob,
                x + TARGET_ICON_SIZE + 2, y + TARGET_ICON_SIZE + 2 + bob,
                haloColor);
        graphics.renderItem(stack, x, y + bob);
    }

    /** An outlined slot, so a missing piece looks deliberate rather than broken. */
    private void drawEmptyTargetSlot(GuiGraphics graphics, int x, int y, String glyph) {
        graphics.fill(x, y, x + TARGET_ICON_SIZE, y + TARGET_ICON_SIZE, 0x30FFFFFF);
        drawBorder(graphics, x, y, TARGET_ICON_SIZE, TARGET_ICON_SIZE,
                GuiColors.BORDER_DARK);
        graphics.drawCenteredString(this.font, glyph,
                x + TARGET_ICON_SIZE / 2, y + 4, GuiColors.TEXT_DIM);
    }

    /**
     * The compatibility list: every target Schematicraft supports, marked with
     * whether it is in use, ready, or unavailable because the mod is absent.
     */
    private List<Component> buildCompatibilityTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Loads into"));

        if (TargetCatalog.isEmpty()) {
            lines.add(Component.literal("\u00a77No editor integrations active"));
            return lines;
        }

        for (TargetCatalog.Entry entry : TargetCatalog.all()) {
            boolean isCurrent = targetDevice.isAvailable()
                    && targetDevice.getType().equals(entry.type());
            if (isCurrent) {
                lines.add(Component.literal("\u00a7a\u25b6 " + entry.label()
                        + " \u00a78(active)"));
                if (entry.hasReceiver()) {
                    ItemStack stack = entry.receiverStack();
                    lines.add(stack.isEmpty()
                            ? Component.literal("\u00a7e    " + entry.receiver().label()
                                    + " slot: " + entry.receiver().emptyHint())
                            : Component.literal("\u00a77    " + entry.receiver().label()
                                    + " slot: " + stack.getHoverName().getString()));
                }
            } else if (entry.isInstalled()) {
                lines.add(Component.literal("\u00a7f\u2022 " + entry.label()));
                lines.add(Component.literal("\u00a78    " + entry.howToUse()));
            } else {
                lines.add(Component.literal("\u00a78\u2022 " + entry.label()
                        + " (not installed)"));
            }
        }

        UploadSource source = journey.uploadSource();
        lines.add(Component.literal(""));
        lines.add(Component.literal("Uploads from"));
        if (source == null) {
            lines.add(Component.literal("\u00a78No source in this context"));
        } else if (source.isReady()) {
            lines.add(Component.literal("\u00a7a\u25b6 " + source.displayName()));
        } else {
            lines.add(Component.literal("\u00a7e" + source.displayName()));
            lines.add(Component.literal("\u00a78    " + source.emptyHint()));
        }
        return lines;
    }

    private void renderStatusBar(GuiGraphics graphics) {
        int y = this.height - STATUS_BAR_H;
        graphics.fill(0, y, this.width, this.height, GuiColors.STATUS_BAR_BG);
        graphics.fill(0, y, this.width, y + 1, GuiColors.BORDER_SEPARATOR);

        // Left: schematic count
        int count = gridState.getSchematicCount();
        String countText = count + " schematic" + (count != 1 ? "s" : "");
        graphics.drawString(this.font, countText, GRID_PADDING, y + 3,
                GuiColors.TEXT_DIM, false);

        // Center: status message
        String status = gridState.getStatusText();
        if (!status.isEmpty()) {
            graphics.drawCenteredString(this.font, status,
                    this.width / 2, y + 3, gridState.getStatusColor());
        }

        // Right: keyboard hints. Fixed, because the keys never change meaning.
        String hints = "Type: filter | Arrows: navigate | Enter: load | Esc: back";
        int hintsW = this.font.width(hints);
        graphics.drawString(this.font, hints,
                this.width - hintsW - GRID_PADDING, y + 3, GuiColors.TEXT_DIM, false);
    }

    // --------------------------------------------------
    // Input Handling
    // --------------------------------------------------

    /**
     * Keyboard model: printable characters always filter, everything else always
     * drives the grid. There is no focus mode, so a given keystroke always means
     * the same thing. Commands use Ctrl so they never collide with typing.
     *
     * Routing order:
     * 1. Esc, layered by scope (overlay, then filter, then screen)
     * 2. Ctrl combos (bundles and commands)
     * 3. Tab, cycles the action buttons for keyboard-only access
     * 4. Navigation and activation (arrows, Home/End, Page keys, Enter)
     * 5. Everything else falls through to the search field (backspace, Ctrl+A/C/V)
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        // --- 1. Layered Esc, ordered by scope, not by focus ---
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (previewExpanded) {
                previewExpanded = false;
                return true;
            }
            if (actionFocus != ACTION_NONE) {
                actionFocus = ACTION_NONE;
                return true;
            }
            if (searchField != null && !searchField.getValue().isEmpty()) {
                clearSearch();
                return true;
            }
            this.onClose();
            return true;
        }

        // --- 2. Ctrl combos ---
        // Bundle hotkeys work from either scope, switching to the library if needed.
        if (ctrl && keyCode == GLFW.GLFW_KEY_TAB) {
            switchScope(GridState.Scope.MY_LIBRARY);
            if (shift) gridState.prevBundle(); else gridState.nextBundle();
            afterBundleChange();
            return true;
        }
        if (ctrl && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_7) {
            int idx = keyCode - GLFW.GLFW_KEY_1;
            if (idx < gridState.getPinnedBundles().size()) {
                selectBundle(idx);
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_U) {
            openUploadScreen();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_K) {
            enterCameraMode();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_P) {
            openPaletteScreen();
            return true;
        }

        // --- 3. Tab cycles the action buttons (visible focus ring) ---
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            cycleActionFocus(shift ? -1 : 1);
            return true;
        }

        // --- 4. Navigation and activation, always the grid ---
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            moveGrid(-1, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            moveGrid(1, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            moveGrid(0, -1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            moveGrid(0, 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            gridState.scroll(-gridHeight);
            gridState.clampScroll(maxScroll);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            gridState.scroll(gridHeight);
            gridState.clampScroll(maxScroll);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            if (gridState.getSchematicCount() > 0) {
                gridState.setSelectedIndex(0);
                ensureSelectedVisible();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            int count = gridState.getSchematicCount();
            if (count > 0) {
                gridState.setSelectedIndex(count - 1);
                ensureSelectedVisible();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // In Discover, an unsent query means Enter submits the search rather
            // than loading, which is what the box invites you to do.
            if (gridState.isDiscover() && discoverPendingQuery != null) {
                String q = discoverPendingQuery.trim();
                discoverPendingQuery = null;
                submitDiscoverQuery(q);
                return true;
            }
            // Enter activates a Tab-focused action, otherwise loads the selection.
            if (actionFocus != ACTION_NONE) {
                activateFocusedAction();
            } else {
                loadSelectedSchematic();
            }
            return true;
        }

        // --- 5. Everything else goes to the search field ---
        // Backspace, Delete, Ctrl+A/C/V and friends. The field is permanently
        // focused, so this also swallows the inventory key and stops it closing
        // the screen while typing.
        if (searchField != null) {
            return searchField.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // Swallow the character from the keybind that opened this screen, which
        // arrives on the first frame and would otherwise seed the filter.
        if (suppressNextChar) {
            suppressNextChar = false;
            return true;
        }

        // Typing always filters. No exceptions, no letter commands.
        if (searchField != null) {
            return searchField.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    // --------------------------------------------------
    // Input helpers
    // --------------------------------------------------

    private void clearSearch() {
        searchField.setValue("");
        gridState.setSearchText("");
        pendingSearchText = null;
        recomputeMaxScroll();
        if (gridState.getSchematicCount() > 0) {
            gridState.setSelectedIndex(0);
            ensureSelectedVisible();
        }
    }

    private void moveGrid(int dx, int dy) {
        // Any navigation leaves action-button focus, so Enter means "load" again.
        actionFocus = ACTION_NONE;
        gridState.moveSelection(COLS, dx, dy);
        ensureSelectedVisible();
    }

    private void afterBundleChange() {
        recomputeMaxScroll();
        actionFocus = ACTION_NONE;
        if (gridState.getSchematicCount() > 0) {
            gridState.setSelectedIndex(0);
            gridState.setScrollOffset(0);
            ensureSelectedVisible();
        }
    }

    private void cycleActionFocus(int direction) {
        // Cycles NONE -> Load -> Upload -> Camera -> Palette -> NONE
        actionFocus += direction;
        if (actionFocus < ACTION_NONE) actionFocus = ACTION_PALETTE;
        if (actionFocus > ACTION_PALETTE) actionFocus = ACTION_NONE;
    }

    private void activateFocusedAction() {
        switch (actionFocus) {
            case ACTION_LOAD -> loadSelectedSchematic();
            case ACTION_UPLOAD -> openUploadScreen();
            case ACTION_CAMERA -> enterCameraMode();
            case ACTION_PALETTE -> openPaletteScreen();
            default -> { }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Expanded preview: click anywhere to close
        if (previewExpanded) {
            previewExpanded = false;
            return true;
        }

        // Tab bar clicks
        if (mouseY < TAB_BAR_H) {
            handleTabBarClick(mouseX, mouseY, button);
            return true;
        }

        // Grid area clicks
        if (mouseY >= gridTop && mouseY < gridBottom
                && mouseX < this.width - SCROLLBAR_W) {
            int tileIdx = getTileIndexAt(mouseX, mouseY);
            if (tileIdx >= 0 && button == 0) {
                // Search keeps focus. Clicking a tile only changes the selection,
                // so Enter still loads and typing still filters.
                actionFocus = ACTION_NONE;
                long now = System.currentTimeMillis();
                if (tileIdx == lastClickIndex && now - lastClickTime < DOUBLE_CLICK_MS) {
                    gridState.setSelectedIndex(tileIdx);
                    loadSelectedSchematic();
                    lastClickTime = 0;
                } else {
                    gridState.setSelectedIndex(tileIdx);
                    lastClickTime = now;
                    lastClickIndex = tileIdx;
                }
                return true;
            }
        }

        // Bottom bar button clicks
        if (mouseY >= this.height - BOTTOM_BAR_H - STATUS_BAR_H) {
            handleBottomBarClick(mouseX, mouseY, button);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double scrollX, double scrollY) {
        if (previewExpanded) {
            com.schematicraft.lib.client.preview.SchematicPreviewRenderer.get().zoom((float) scrollY);
            return true;
        }
        if (mouseY >= gridTop && mouseY < gridBottom) {
            int scrollAmount = (int) (-scrollY * (tileTotalH + TILE_GAP));
            gridState.scroll(scrollAmount);
            gridState.clampScroll(maxScroll);
            maybeLoadMoreDiscover();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                 double dragX, double dragY) {
        if (previewExpanded && button == 0) {
            com.schematicraft.lib.client.preview.SchematicPreviewRenderer.get()
                    .drag((float) dragX, (float) dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    // --------------------------------------------------
    // Actions
    // --------------------------------------------------

    /**
     * Loads the user's library.
     *
     * This runs in both scopes, because the bundle tabs and their Ctrl+1-7 hotkeys
     * are visible from Discover too. Progress is only reported when the library is
     * the visible list, so it does not fight the discover feed for the status bar.
     */
    private void loadLibrary() {
        boolean visible = !gridState.isDiscover();
        if (visible) setStatus("Loading library...", GuiColors.INFO, 0);
        SchematiCraftAPIWrapper.get().loadLibrary().thenRun(() -> {
            Minecraft.getInstance().execute(() -> {
                gridState.onLibraryUpdated();
                recomputeMaxScroll();
                if (!gridState.isDiscover()) {
                    setStatus(gridState.getSchematicCount() + " schematics loaded",
                            GuiColors.SUCCESS, 3000);
                }
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                String msg = SchematiCraftAPIWrapper.rootMessage(ex);
                setStatus("Load failed: " + msg, GuiColors.ERROR, 5000);
            });
            return null;
        });
    }

    private void loadSelectedSchematic() {
        SchematicEntry selected = gridState.getSelectedSchematic();
        if (selected == null) {
            setStatus("No schematic selected", GuiColors.WARNING, 2000);
            return;
        }
        if (!targetDevice.isAvailable()) {
            setStatus("No target device. Hold a gadget or open a table.",
                    GuiColors.WARNING, 3000);
            return;
        }

        // Fail fast before spending a download. The authoritative size check
        // still happens after conversion, but when the server told us the block
        // count we can refuse immediately and explain why.
        LoadLimits limits = getLoadLimits(targetDevice);
        if (selected.hasBlockCount() && limits.exceedsHard(selected.blockCount())) {
            setStatus("Too big for " + targetDevice.getDisplayName() + ": "
                            + formatCount(selected.blockCount()) + " blocks, limit "
                            + formatCount(limits.hard()),
                    GuiColors.ERROR, 8000);
            return;
        }

        setStatus("Downloading...", GuiColors.INFO, 0);
        SchematiCraftAPIWrapper.get().downloadSchematic(
                selected.id(), targetDevice.getDownloadFormat(), targetDevice.getDownloadEditor())
                .thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    long fileBytes = Files.size(result.file);

                    // Deterministic self-protection, before anything expensive.
                    // Parsing holds the bytes, a String copy, a JSON tree, and an
                    // SNBT tree in memory at once, so an oversized payload freezes
                    // or crashes the client. Refuse here, and never cache it.
                    if (limits.maxBytes() > 0 && fileBytes > limits.maxBytes()) {
                        Files.deleteIfExists(result.file);
                        setStatus("Too large to load safely: "
                                        + formatBytes(fileBytes) + " (limit "
                                        + formatBytes(limits.maxBytes()) + ")",
                                GuiColors.ERROR, 8000);
                        LOGGER.warn("Refused oversized payload for {}: {} bytes, limit {}",
                                selected.id(), fileBytes, limits.maxBytes());
                        SchematiCraftAPIWrapper.get().submitFailureFeedback(
                                result.downloadId, "other",
                                "Refused before parsing: " + fileBytes
                                        + " bytes exceeds client limit " + limits.maxBytes()
                                        + " for target " + targetDevice.getType());
                        return;
                    }

                    byte[] data = Files.readAllBytes(result.file);
                    Files.deleteIfExists(result.file);

                    // Cache the downloaded data for previews
                    com.schematicraft.lib.core.SchematicDataCache.get()
                            .put(selected.id(), data);

                    String title = selected.title() != null
                            ? selected.title() : "schematic";
                    LoadResult loadResult = dispatchLoad(data, title);
                    if (loadResult.success()) {
                        // Name the destination. Targets differ in where a load
                        // lands, and "Loaded" alone left users checking the wrong
                        // slot for the result.
                        String into = loadResult.confirmed()
                                ? "Loaded into " + targetDevice.getLoadedLabel() + ": " + title
                                : "Sent to " + targetDevice.getLoadedLabel() + ": " + title;
                        if (loadResult.hasDroppedBlocks()) {
                            setStatus(into + " (" + loadResult.droppedBlockTypes().size()
                                    + " unknown block types dropped)", GuiColors.WARNING, 5000);
                            LOGGER.warn("Dropped block types: {}", loadResult.droppedBlockTypes());
                        } else if (selected.hasBlockCount()
                                && limits.exceedsSoft(selected.blockCount())) {
                            // Loaded, but past what this editor was built for.
                            // Say so rather than implying everything is routine.
                            setStatus(into + " ("
                                            + formatCount(selected.blockCount())
                                            + " blocks, may be slow to paste)",
                                    GuiColors.WARNING, 5000);
                        } else {
                            setStatus(into, GuiColors.SUCCESS, 3000);
                        }
                        if (loadResult.confirmed()) {
                            SchematiCraftAPIWrapper.get()
                                    .submitSuccessFeedback(result.downloadId);
                        }
                        this.onClose();
                    } else {
                        // Stay on the screen so the user can read why and pick
                        // something else, rather than closing onto an unchanged tool.
                        String reason = loadResult.reason() != null
                                ? loadResult.reason()
                                : "Could not load into " + targetDevice.getDisplayName();
                        setStatus(reason, GuiColors.ERROR, 8000);
                        LOGGER.warn("Load failed for {}: {}", selected.id(), reason);
                        SchematiCraftAPIWrapper.get().submitFailureFeedback(
                                result.downloadId,
                                "import_error_or_crash",
                                "Load failed. Target: " + targetDevice.getType()
                                        + ". Reason: " + reason);
                    }
                } catch (Exception e) {
                    setStatus("Error: " + e.getMessage(), GuiColors.ERROR, 4000);
                    LOGGER.error("Load failed", e);
                }
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                String msg = SchematiCraftAPIWrapper.rootMessage(ex);
                setStatus(msg, GuiColors.ERROR, 4000);
            });
            return null;
        });
    }

    /**
     * Dispatch downloaded schematic data to the resolved target device.
     * Editor mods register a handler per target via setLoadHandler().
     */
    private LoadResult dispatchLoad(byte[] data, String schematicName) {
        LoadHandler handler = getLoadHandler(targetDevice);
        if (handler != null) {
            return handler.load(targetDevice, data, schematicName);
        }
        LOGGER.warn("No load handler registered for target {}", targetDevice.getType());
        return LoadResult.failure();
    }

    /**
     * Result from a load operation.
     *
     * {@code confirmed} is true only when the editor handoff completed locally.
     * Packet-based integrations without an acknowledgment use dispatched results,
     * which may close back to the native editor but do not submit positive
     * feedback. {@code reason} is a short user-facing failure explanation.
     */
    public record LoadResult(boolean success, boolean confirmed, int blocksLoaded,
                             List<String> droppedBlockTypes, @Nullable String reason) {
        public static LoadResult success(int blocksLoaded) {
            return new LoadResult(true, true, blocksLoaded, List.of(), null);
        }
        public static LoadResult partial(int blocksLoaded, List<String> droppedBlockTypes) {
            return new LoadResult(true, true, blocksLoaded, droppedBlockTypes, null);
        }
        public static LoadResult dispatched(int blocksLoaded) {
            return new LoadResult(true, false, blocksLoaded, List.of(), null);
        }
        public static LoadResult dispatchedPartial(
                int blocksLoaded, List<String> droppedBlockTypes) {
            return new LoadResult(true, false, blocksLoaded, droppedBlockTypes, null);
        }
        public static LoadResult failure(String reason) {
            return new LoadResult(false, false, 0, List.of(), reason);
        }
        public static LoadResult failure() {
            return new LoadResult(false, false, 0, List.of(), null);
        }
        public boolean hasDroppedBlocks() {
            return !droppedBlockTypes.isEmpty();
        }
    }

    /**
     * Functional interface for editor-specific load dispatch.
     *
     * @param schematicName the schematic's title, for editors that name what they
     *                      store. File-based editors like Create need this: without
     *                      it every download lands under the same generic filename
     *                      and the user cannot tell their schematics apart.
     */
    @FunctionalInterface
    public interface LoadHandler {
        LoadResult load(TargetDevice target, byte[] schematicData, String schematicName);
    }

    /**
     * Load handlers keyed by target type.
     *
     * Keyed rather than a single handler so multiple editors can coexist in one
     * mod. With both Building Gadgets and Create installed, a single slot would
     * mean the last registration silently wins.
     */
    private static final java.util.Map<TargetDevice.Type, LoadHandler> loadHandlers =
            new java.util.HashMap<>();

    /** Register the load handler for a target. Called once during client setup. */
    public static void setLoadHandler(TargetDevice.Type type, LoadHandler handler) {
        loadHandlers.put(type, handler);
    }

    /** Block-count limits per target, declared by each editor integration. */
    private static final java.util.Map<TargetDevice.Type, LoadLimits> loadLimits =
            new java.util.HashMap<>();

    /** Register block-count limits for a target. Called once during client setup. */
    public static void setLoadLimits(TargetDevice.Type type, LoadLimits limits) {
        loadLimits.put(type, limits);
    }

    /** Limits for a target, or UNLIMITED when the editor declared none. */
    public static LoadLimits getLoadLimits(TargetDevice target) {
        return loadLimits.getOrDefault(target.getType(), LoadLimits.UNLIMITED);
    }

    /** Thousands-separated count for user-facing messages. */
    private static String formatCount(int count) {
        return String.format("%,d", count);
    }

    /** Compact byte size for user-facing messages. */
    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return Math.max(1L, bytes / 1024L) + " KB";
    }

    /**
     * Compact block count for tile labels, e.g. 44.7M or 250k.
     * Tiles are narrow, so the full thousands-separated form does not fit.
     */
    private static String formatCountShort(int count) {
        if (count >= 1_000_000) {
            return String.format("%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return (count / 1000) + "k";
        }
        return String.valueOf(count);
    }

    /** Get the handler for a resolved target, or null when none is registered. */
    @Nullable
    public static LoadHandler getLoadHandler(TargetDevice target) {
        return loadHandlers.get(target.getType());
    }

    private void enterCameraMode() {
        SchematicEntry selected = gridState.getSelectedSchematic();
        if (selected == null) {
            setStatus("Select a schematic first", GuiColors.WARNING, 2000);
            return;
        }

        pendingCameraReopen = true;
        pendingCameraImages = new ArrayList<>();
        pendingCameraSchematicId = selected.id();
        pendingCameraSchematicTitle = selected.title();
        pendingCameraJourney = journey;

        this.minecraft.setScreen(null);
        CameraMode.start(pendingCameraImages, () -> {
            Minecraft.getInstance().execute(() -> {
                EditorJourney reopen = pendingCameraJourney != null
                        ? pendingCameraJourney : EditorJourney.browse();
                pendingCameraJourney = null;
                Minecraft.getInstance().setScreen(new LibraryScreen(reopen));
            });
        });
    }

    private void openUploadScreen() {
        UploadSource source = journey.uploadSource();
        if (source == null) {
            setStatus("No upload source in this editor context",
                    GuiColors.WARNING, 3000);
            return;
        }
        if (!source.isReady()) {
            setStatus(source.emptyHint(), GuiColors.WARNING, 4000);
            return;
        }
        Minecraft.getInstance().setScreen(new UploadScreen(source, journey));
    }

    private void openPaletteScreen() {
        SchematicEntry selected = gridState.getSelectedSchematic();
        if (selected == null) {
            setStatus("Select a schematic first", GuiColors.WARNING, 2000);
            return;
        }
        Minecraft.getInstance().setScreen(new PaletteScreen(selected, journey));
    }

    // --------------------------------------------------
    // Hit Testing & Helpers
    // --------------------------------------------------

    private void handleTabBarClick(double mouseX, double mouseY, int button) {
        // Scope tabs take priority; they are drawn leftmost.
        if (discoverTabX >= 0 && isOver((int) mouseX, (int) mouseY,
                discoverTabX, TAB_Y_OFFSET, discoverTabW, TAB_H)) {
            switchScope(GridState.Scope.DISCOVER);
            return;
        }
        if (libraryTabX >= 0 && isOver((int) mouseX, (int) mouseY,
                libraryTabX, TAB_Y_OFFSET, libraryTabW, TAB_H)) {
            switchScope(GridState.Scope.MY_LIBRARY);
            return;
        }

        // "+" first: its bounds come from the last render, so it always matches.
        if (plusTabX >= 0 && isOver((int) mouseX, (int) mouseY,
                plusTabX, TAB_Y_OFFSET, plusTabW, TAB_H)) {
            Minecraft.getInstance().setScreen(
                    new NewBundleScreen(new LibraryScreen(journey), null));
            return;
        }

        // Bundle tabs, using the bounds recorded during render. Index 0 is "All".
        for (int i = 0; i < bundleTabBounds.size(); i++) {
            int[] b = bundleTabBounds.get(i);
            if (isOver((int) mouseX, (int) mouseY, b[0], TAB_Y_OFFSET, b[1], TAB_H)) {
                selectBundle(i - 1);
                return;
            }
        }
    }

    /**
     * Selects a bundle, switching to the library first if needed.
     *
     * Bundle tabs and their hotkeys are visible from Discover too, so using one is
     * an implicit "take me to my library, at this bundle" rather than a no-op.
     */
    private void selectBundle(int index) {
        switchScope(GridState.Scope.MY_LIBRARY);
        gridState.setActiveBundleIndex(index);
        actionFocus = ACTION_NONE;
        recomputeMaxScroll();
    }

    private void handleBottomBarClick(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int barY2 = this.height - BOTTOM_BAR_H - STATUS_BAR_H;

        // Preview area click (expand)
        int previewTop = barY2 + 4;
        int previewH = BOTTOM_BAR_H - 8;
        if (isOver(mx, my, PREVIEW_X, previewTop, PREVIEW_W, previewH)) {
            togglePreviewExpand();
            return;
        }

        // Load button
        if (isOver(mx, my, INFO_X, btnY, LOAD_BTN_W, ACTION_BTN_H)) {
            loadSelectedSchematic();
            return;
        }

        // Upload button
        int uploadBtnX = INFO_X + LOAD_BTN_W + BTN_GAP;
        if (isOver(mx, my, uploadBtnX, btnY, ACTION_BTN_W, ACTION_BTN_H)) {
            openUploadScreen();
            return;
        }

        // Camera button
        int camBtnX = uploadBtnX + ACTION_BTN_W + BTN_GAP;
        if (isOver(mx, my, camBtnX, btnY, ACTION_BTN_W, ACTION_BTN_H)) {
            enterCameraMode();
            return;
        }

        // Palette button
        int palBtnX = camBtnX + ACTION_BTN_W + BTN_GAP;
        if (isOver(mx, my, palBtnX, btnY, ACTION_BTN_W, ACTION_BTN_H)) {
            openPaletteScreen();
        }
    }

    private int getTileIndexAt(double mouseX, double mouseY) {
        int scrollOffset = gridState.getScrollOffset();
        List<GridState.DisplayEntry> displayList = gridState.getDisplayList();

        int y = gridTop - scrollOffset;
        int col = 0;
        int schematicIdx = 0;

        for (GridState.DisplayEntry entry : displayList) {
            if (entry instanceof GridState.BundleHeaderEntry) {
                if (col > 0) {
                    y += tileTotalH + TILE_GAP;
                    col = 0;
                }
                y += BUNDLE_HEADER_H + TILE_GAP;
            } else if (entry instanceof GridState.SchematicTileEntry) {
                int tileX = GRID_PADDING + col * (tileSize + TILE_GAP);
                int tileY = y;

                if (mouseX >= tileX && mouseX < tileX + tileSize
                        && mouseY >= tileY && mouseY < tileY + tileTotalH) {
                    return schematicIdx;
                }

                col++;
                if (col >= COLS) {
                    col = 0;
                    y += tileTotalH + TILE_GAP;
                }
                schematicIdx++;
            }
        }
        return -1;
    }

    private void ensureSelectedVisible() {
        int idx = gridState.getSelectedIndex();
        if (idx < 0) return;

        // Walk the display list to find the actual Y position of the selected tile,
        // accounting for bundle headers that add vertical space.
        List<GridState.DisplayEntry> displayList = gridState.getDisplayList();
        int y = 0;
        int col = 0;
        int schematicIdx = 0;
        int tileY = 0;

        for (GridState.DisplayEntry entry : displayList) {
            if (entry instanceof GridState.BundleHeaderEntry) {
                if (col > 0) {
                    y += tileTotalH + TILE_GAP;
                    col = 0;
                }
                y += BUNDLE_HEADER_H + TILE_GAP;
            } else if (entry instanceof GridState.SchematicTileEntry) {
                if (schematicIdx == idx) {
                    tileY = y;
                    break;
                }
                col++;
                if (col >= COLS) {
                    col = 0;
                    y += tileTotalH + TILE_GAP;
                }
                schematicIdx++;
            }
        }

        int scrollOffset = gridState.getScrollOffset();

        if (tileY < scrollOffset) {
            gridState.setScrollOffset(tileY);
        } else if (tileY + tileTotalH > scrollOffset + gridHeight) {
            gridState.setScrollOffset(tileY + tileTotalH - gridHeight);
        }
        gridState.clampScroll(maxScroll);
    }

    private void recomputeMaxScroll() {
        List<GridState.DisplayEntry> displayList = gridState.getDisplayList();
        int y = 0;
        int col = 0;
        for (GridState.DisplayEntry entry : displayList) {
            if (entry instanceof GridState.BundleHeaderEntry) {
                if (col > 0) {
                    y += tileTotalH + TILE_GAP;
                    col = 0;
                }
                y += BUNDLE_HEADER_H + TILE_GAP;
            } else {
                col++;
                if (col >= COLS) {
                    col = 0;
                    y += tileTotalH + TILE_GAP;
                }
            }
        }
        if (col > 0) y += tileTotalH + TILE_GAP;
        maxScroll = Math.max(0, y - gridHeight);
        gridState.clampScroll(maxScroll);
    }

    // --------------------------------------------------
    // Utility
    // --------------------------------------------------
    // Preview Helpers
    // --------------------------------------------------

    private String lastPreparedSchematicId = null;

    private void togglePreviewExpand() {
        previewExpanded = !previewExpanded;
        if (previewExpanded) {
            com.schematicraft.lib.client.preview.SchematicPreviewRenderer.get().resetView();
        }
    }

    private void renderPreviewOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!previewExpanded) return;

        var renderer = com.schematicraft.lib.client.preview.SchematicPreviewRenderer.get();
        if (!renderer.isReady()) { previewExpanded = false; return; }

        // Dim background
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        // Large preview area (60% of screen, centered)
        int pw = (int) (this.width * 0.6);
        int ph = (int) (this.height * 0.6);
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;

        graphics.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, GuiColors.BORDER_SEPARATOR);
        graphics.fill(px, py, px + pw, py + ph, 0xFF0A0A0A);

        graphics.flush();
        renderer.renderInteractive(graphics, px, py, pw, ph);

        // Instructions
        graphics.drawCenteredString(this.font, "Drag to rotate, scroll to zoom, click/Esc to close",
                this.width / 2, py + ph + 8, GuiColors.TEXT_DIM);
    }

    private void ensurePreviewPrepared(String schematicId) {
        if (schematicId.equals(lastPreparedSchematicId)) return;

        var cache = com.schematicraft.lib.core.SchematicDataCache.get();
        byte[] data = cache.get(schematicId);
        if (data == null) return;

        var previewData = com.schematicraft.lib.client.preview.PreviewDataParser.parse(data);
        if (previewData == null) return;

        com.schematicraft.lib.client.preview.SchematicPreviewRenderer.get()
                .prepare(previewData, schematicId);
        lastPreparedSchematicId = schematicId;
    }

    private void renderThumbnailPreview(GuiGraphics graphics, SchematicEntry selected,
                                         int previewTop, int previewH) {
        ResourceLocation tex = ThumbnailCache.get().getTexture(
                selected.id(), selected.thumbnailUrl());
        if (tex != null) {
            graphics.blit(tex, PREVIEW_X + 1, previewTop + 1, 0, 0,
                    PREVIEW_W - 2, previewH - 2, PREVIEW_W - 2, previewH - 2);
        } else {
            graphics.drawCenteredString(this.font, "Preview",
                    PREVIEW_X + PREVIEW_W / 2, previewTop + previewH / 2 - 4, GuiColors.TEXT_DIM);
        }
    }

    // --------------------------------------------------
    // Utility
    // --------------------------------------------------

    private void setStatus(String text, int color, int durationMs) {
        gridState.setStatus(text, color, durationMs);
    }

    private void scheduleTooltip(List<Component> lines, int x, int y) {
        this.pendingTooltip = lines;
        this.tooltipX = x;
        this.tooltipY = y;
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static void drawBorder(GuiGraphics graphics,
                                    int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "\u2026";
    }

    private String truncateToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "\u2026";
        int ellipsisW = this.font.width(ellipsis);
        int available = maxWidth - ellipsisW;
        if (available <= 0) return ellipsis;

        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (this.font.width(text.substring(0, mid)) <= available) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    @Nullable
    private String findBundleName(String bundleId) {
        if (bundleId == null) return null;
        for (BundleEntry b : LibraryState.get().getBundles()) {
            if (b.id().equals(bundleId)) return b.name();
        }
        return null;
    }

    // --------------------------------------------------
    // Static accessors for camera round-trip state
    // --------------------------------------------------

    @Nullable
    public static List<Path> getPendingCameraImages() {
        return pendingCameraImages;
    }

    @Nullable
    public static String getPendingCameraSchematicId() {
        return pendingCameraSchematicId;
    }

    @Nullable
    public static String getPendingCameraSchematicTitle() {
        return pendingCameraSchematicTitle;
    }

    public static void clearPendingCameraState() {
        pendingCameraReopen = false;
        pendingCameraImages = null;
        pendingCameraSchematicId = null;
        pendingCameraSchematicTitle = null;
    }
}
