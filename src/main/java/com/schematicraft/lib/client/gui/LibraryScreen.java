package com.schematicraft.lib.client.gui;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.client.CameraMode;
import com.schematicraft.lib.client.ThumbnailCache;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.BundleEntry;
import com.schematicraft.lib.core.LibraryState;
import com.schematicraft.lib.core.SchematicEntry;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * Accepts a nullable TargetDevice.OpenContext so it can be opened from tables or keybind.
 */
public class LibraryScreen extends Screen {
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
    private static final int TARGET_INFO_MARGIN = 120;
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
    private final TargetDevice targetDevice;

    // Widgets
    private EditBox searchField;

    // Search debounce
    private long lastSearchKeystroke = 0;
    private String pendingSearchText = null;

    // Status display
    private int statusColor = GuiColors.SUCCESS;
    private long statusClearAt = 0;

    // Double-click detection
    private long lastClickTime = 0;
    private int lastClickIndex = -1;

    // Camera mode round-trip (static to survive screen close/reopen)
    private static boolean pendingCameraReopen = false;
    private static List<Path> pendingCameraImages = null;
    private static String pendingCameraSchematicId = null;
    private static String pendingCameraSchematicTitle = null;

    // Tooltip state (collected during render, drawn last)
    private List<Component> pendingTooltip = null;
    private int tooltipX, tooltipY;

    // Preview expand overlay
    private boolean previewExpanded = false;

    // Suppress the first charTyped after opening (keybind char leaks into search)
    private boolean suppressNextChar = true;

    public LibraryScreen(@Nullable TargetDevice.OpenContext openContext) {
        super(Component.literal("Schematicraft Library"));
        this.targetDevice = TargetDevice.resolve(openContext);
    }

    /** Convenience: open with no context (keybind). */
    public LibraryScreen() {
        this(null);
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

        // Search field (always visible)
        searchField = new EditBox(this.font, GRID_PADDING, TAB_BAR_H + 2,
                this.width - GRID_PADDING * 2, SEARCH_FIELD_H, Component.literal(""));
        searchField.setMaxLength(100);
        searchField.setHint(Component.literal("Type to filter..."));
        searchField.setValue(gridState.getSearchText());
        searchField.setResponder(text -> {
            pendingSearchText = text;
            lastSearchKeystroke = System.currentTimeMillis();
        });
        this.addRenderableWidget(searchField);
        this.setInitialFocus(searchField);

        // Load library on first open
        if (!LibraryState.get().isLibraryLoaded()
                && !LibraryState.get().isLibraryLoading()
                && ModConfig.hasApiKey()) {
            loadLibrary();
        } else if (LibraryState.get().isLibraryLoaded()) {
            gridState.onLibraryUpdated();
        }

        recomputeMaxScroll();

        // Camera mode round-trip restore
        if (pendingCameraReopen) {
            pendingCameraReopen = false;
            setStatus("Screenshots captured", GuiColors.SUCCESS, 3000);
        }
    }

    @Override
    public void tick() {
        super.tick();

        // Search debounce
        if (pendingSearchText != null
                && System.currentTimeMillis() - lastSearchKeystroke > SEARCH_DEBOUNCE_MS) {
            gridState.setSearchText(pendingSearchText);
            pendingSearchText = null;
            recomputeMaxScroll();
        }

        // Status auto-clear
        if (statusClearAt > 0 && System.currentTimeMillis() >= statusClearAt) {
            gridState.clearStatus();
            statusClearAt = 0;
        }

        gridState.tickStatus();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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

        // "All" tab
        boolean allActive = gridState.getActiveBundleIndex() == -1;
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
        x += allW + TAB_PAD_X;

        // Pinned bundle tabs
        List<GridState.BundleTabInfo> pinned = gridState.getPinnedBundles();
        for (int i = 0; i < pinned.size(); i++) {
            GridState.BundleTabInfo tab = pinned.get(i);
            boolean active = gridState.getActiveBundleIndex() == i;
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

            x += tabW + TAB_PAD_X;
        }
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

        graphics.disableScissor();

        renderScrollbar(graphics, mouseX, mouseY);
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

        // Name below thumbnail
        String name = schematic.title() != null ? schematic.title() : "Untitled";
        String truncated = truncateToWidth(name, tileSize - 4);
        int nameColor = selected ? GuiColors.SELECTED
                : (hovered ? GuiColors.HOVER_TEXT : GuiColors.TILE_NAME);
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

            String bundleId = gridState.getSelectedBundleId();
            String bundleName = bundleId != null ? findBundleName(bundleId) : "Unbundled";
            graphics.drawString(this.font,
                    "\u00a77" + (bundleName != null ? bundleName : ""),
                    INFO_X, barY + 18, GuiColors.TEXT_SECONDARY, false);

            if (selected.downloadCount() > 0) {
                graphics.drawString(this.font,
                        "\u00a78" + selected.downloadCount() + " downloads",
                        INFO_X, barY + 28, GuiColors.TEXT_DIM, false);
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

        // Upload button
        int uploadBtnX = INFO_X + LOAD_BTN_W + BTN_GAP;
        graphics.fill(uploadBtnX, btnY, uploadBtnX + ACTION_BTN_W,
                btnY + ACTION_BTN_H, GuiColors.BTN_UPLOAD_BG);
        drawBorder(graphics, uploadBtnX, btnY, ACTION_BTN_W,
                ACTION_BTN_H, GuiColors.BTN_UPLOAD_BORDER);
        graphics.drawCenteredString(this.font, "Upload",
                uploadBtnX + ACTION_BTN_W / 2, btnY + 4, GuiColors.BTN_UPLOAD_TEXT);

        if (isOver(mouseX, mouseY, uploadBtnX, btnY, ACTION_BTN_W, ACTION_BTN_H)) {
            scheduleTooltip(List.of(
                    Component.literal("Upload schematic"),
                    Component.literal("\u00a78Shortcut: U")
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
                    Component.literal("\u00a78Shortcut: C")
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
                    Component.literal("\u00a78Swap blocks before loading")
            ), mouseX, mouseY);
        }

        // Right: Target device info
        int targetX = this.width - TARGET_INFO_MARGIN;
        graphics.drawString(this.font, "Target:", targetX, barY + 6,
                GuiColors.TEXT_DIM, false);
        if (targetDevice.isAvailable()) {
            int tColor = targetDevice.getMode() == TargetDevice.Mode.SERVER
                    ? GuiColors.TARGET_SERVER : GuiColors.TARGET_CLIENT;
            graphics.drawString(this.font, targetDevice.getDisplayName(),
                    targetX, barY + 18, tColor, false);
            graphics.drawString(this.font, "\u00a78" + targetDevice.getModeLabel(),
                    targetX, barY + 30, GuiColors.TEXT_DIM, false);
        } else {
            graphics.drawString(this.font, "None", targetX, barY + 18,
                    GuiColors.TARGET_NONE, false);
            graphics.drawString(this.font, "\u00a78Hold gadget/open table",
                    targetX, barY + 30, GuiColors.TEXT_DIM, false);
        }
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
                    this.width / 2, y + 3, statusColor);
        }

        // Right: keyboard hints
        String hints = searchField != null && searchField.isFocused()
                ? "Esc: clear search | Arrows: navigate"
                : "/ : search | Arrows: navigate | Enter: load | Esc: close";
        int hintsW = this.font.width(hints);
        graphics.drawString(this.font, hints,
                this.width - hintsW - GRID_PADDING, y + 3, GuiColors.TEXT_DIM, false);
    }

    // --------------------------------------------------
    // Input Handling
    // --------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        // Layered Esc
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (previewExpanded) {
                previewExpanded = false;
                return true;
            }
            if (searchField != null && searchField.isFocused()
                    && !searchField.getValue().isEmpty()) {
                searchField.setValue("");
                gridState.setSearchText("");
                recomputeMaxScroll();
                return true;
            }
            this.onClose();
            return true;
        }

        // Ctrl+Tab / Ctrl+Shift+Tab: cycle bundle tabs
        if (ctrl && keyCode == GLFW.GLFW_KEY_TAB) {
            if (shift) gridState.prevBundle(); else gridState.nextBundle();
            recomputeMaxScroll();
            return true;
        }

        // Ctrl+1-7: jump to pinned bundle
        if (ctrl && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_7) {
            int idx = keyCode - GLFW.GLFW_KEY_1;
            if (idx < gridState.getPinnedBundles().size()) {
                gridState.setActiveBundleIndex(idx);
                recomputeMaxScroll();
            }
            return true;
        }

        // If search field is focused, swallow all keys except Tab (AE2 pattern)
        if (searchField != null && searchField.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                searchField.setFocused(false);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // / or Ctrl+F: focus search
        if (keyCode == GLFW.GLFW_KEY_SLASH || (ctrl && keyCode == GLFW.GLFW_KEY_F)) {
            if (searchField != null) {
                searchField.setFocused(true);
                this.setFocused(searchField);
            }
            return true;
        }

        // Arrow keys: 2D grid navigation
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            gridState.moveSelection(COLS, -1, 0);
            ensureSelectedVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            gridState.moveSelection(COLS, 1, 0);
            ensureSelectedVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            gridState.moveSelection(COLS, 0, -1);
            ensureSelectedVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            gridState.moveSelection(COLS, 0, 1);
            ensureSelectedVisible();
            return true;
        }

        // Enter: load selected
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            loadSelectedSchematic();
            return true;
        }

        // C: camera mode
        if (keyCode == GLFW.GLFW_KEY_C) {
            enterCameraMode();
            return true;
        }

        // U: upload
        if (keyCode == GLFW.GLFW_KEY_U) {
            setStatus("Upload not yet implemented in standalone screen",
                    GuiColors.INFO, 3000);
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // Suppress the keybind character that leaks through on the frame the screen opens
        if (suppressNextChar) {
            suppressNextChar = false;
            return true;
        }

        // If search field is not focused, redirect typing to it (AE2 pattern)
        if (searchField != null && !searchField.isFocused()) {
            // Don't redirect shortcut chars when search is empty
            if (gridState.getSearchText().isEmpty()) {
                if (codePoint == 'c' || codePoint == 'C'
                        || codePoint == 'u' || codePoint == 'U') {
                    return false;
                }
            }
            searchField.setFocused(true);
            this.setFocused(searchField);
            return searchField.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
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
                // Unfocus search so arrow keys and Enter work for grid navigation
                if (searchField != null) {
                    searchField.setFocused(false);
                }
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

    private void loadLibrary() {
        setStatus("Loading library...", GuiColors.INFO, 0);
        SchematiCraftAPIWrapper.get().loadLibrary().thenRun(() -> {
            Minecraft.getInstance().execute(() -> {
                gridState.onLibraryUpdated();
                recomputeMaxScroll();
                int count = gridState.getSchematicCount();
                setStatus(count + " schematics loaded", GuiColors.SUCCESS, 3000);
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

        setStatus("Downloading...", GuiColors.INFO, 0);
        SchematiCraftAPIWrapper.get().downloadSchematic(
                selected.id(), targetDevice.getDownloadFormat(), targetDevice.getDownloadEditor())
                .thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    byte[] data = Files.readAllBytes(result.file);
                    Files.deleteIfExists(result.file);

                    // Cache the downloaded data for palette editing
                    com.schematicraft.lib.core.SchematicDataCache.get()
                            .put(selected.id(), data);

                    // DEBUG: dump first 500 chars of downloaded data
                    String preview = new String(data, 0, Math.min(data.length, 500));
                    LOGGER.info("[DEBUG] Downloaded JSON preview: {}", preview);

                    LoadResult loadResult = dispatchLoad(data);
                    if (loadResult.success()) {
                        String title = selected.title() != null
                                ? selected.title() : "schematic";
                        if (loadResult.hasDroppedBlocks()) {
                            setStatus("Loaded: " + title + " (" + loadResult.droppedBlockTypes().size()
                                    + " unknown block types dropped)", GuiColors.WARNING, 5000);
                            LOGGER.warn("Dropped block types: {}", loadResult.droppedBlockTypes());
                        } else {
                            setStatus("Loaded: " + title, GuiColors.SUCCESS, 3000);
                        }
                        SchematiCraftAPIWrapper.get()
                                .submitSuccessFeedback(result.downloadId);
                        this.onClose();
                    } else {
                        setStatus("Failed to load into target",
                                GuiColors.ERROR, 4000);
                        SchematiCraftAPIWrapper.get().submitFailureFeedback(
                                result.downloadId,
                                "import_error_or_crash",
                                "LibraryScreen dispatch returned false. Target: "
                                        + targetDevice.getType());
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
     * Editor mods register their handler via setLoadHandler().
     */
    private LoadResult dispatchLoad(byte[] data) {
        if (loadHandler != null) {
            return loadHandler.load(targetDevice, data);
        }
        LOGGER.warn("No load handler registered for LibraryScreen");
        return LoadResult.failure();
    }

    /** Result from a load operation. */
    public record LoadResult(boolean success, int blocksLoaded, List<String> droppedBlockTypes) {
        public static LoadResult success(int blocksLoaded) {
            return new LoadResult(true, blocksLoaded, List.of());
        }
        public static LoadResult partial(int blocksLoaded, List<String> droppedBlockTypes) {
            return new LoadResult(true, blocksLoaded, droppedBlockTypes);
        }
        public static LoadResult failure() {
            return new LoadResult(false, 0, List.of());
        }
        public boolean hasDroppedBlocks() {
            return !droppedBlockTypes.isEmpty();
        }
    }

    /** Functional interface for editor-specific load dispatch. */
    @FunctionalInterface
    public interface LoadHandler {
        LoadResult load(TargetDevice target, byte[] schematicData);
    }

    private static LoadHandler loadHandler = null;

    /** Register the editor-specific load handler. Called once during mod init. */
    public static void setLoadHandler(LoadHandler handler) {
        loadHandler = handler;
    }

    /** Get the registered load handler (used by PaletteScreen). */
    @Nullable
    public static LoadHandler getLoadHandler() {
        return loadHandler;
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

        this.minecraft.setScreen(null);
        CameraMode.start(pendingCameraImages, () -> {
            Minecraft.getInstance().execute(() -> {
                Minecraft.getInstance().setScreen(new LibraryScreen());
            });
        });
    }

    private void openPaletteScreen() {
        SchematicEntry selected = gridState.getSelectedSchematic();
        if (selected == null) {
            setStatus("Select a schematic first", GuiColors.WARNING, 2000);
            return;
        }
        Minecraft.getInstance().setScreen(new PaletteScreen(selected, targetDevice));
    }

    // --------------------------------------------------
    // Hit Testing & Helpers
    // --------------------------------------------------

    private void handleTabBarClick(double mouseX, double mouseY, int button) {
        int x = GRID_PADDING;

        String allLabel = "All";
        int allW = this.font.width(allLabel) + TAB_PAD_INNER;
        if (isOver((int) mouseX, (int) mouseY, x, TAB_Y_OFFSET, allW, TAB_H)) {
            gridState.setActiveBundleIndex(-1);
            recomputeMaxScroll();
            return;
        }
        x += allW + TAB_PAD_X;

        List<GridState.BundleTabInfo> pinned = gridState.getPinnedBundles();
        for (int i = 0; i < pinned.size(); i++) {
            String label = "[" + (i + 1) + "] " + truncate(pinned.get(i).name(), TAB_NAME_MAX_LEN);
            int tabW = this.font.width(label) + TAB_PAD_INNER;
            if (isOver((int) mouseX, (int) mouseY, x, TAB_Y_OFFSET, tabW, TAB_H)) {
                gridState.setActiveBundleIndex(i);
                recomputeMaxScroll();
                return;
            }
            x += tabW + TAB_PAD_X;
        }
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
            setStatus("Upload not yet implemented in standalone screen",
                    GuiColors.INFO, 3000);
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
        gridState.setStatus(text, durationMs);
        this.statusColor = color;
        if (durationMs > 0) {
            this.statusClearAt = System.currentTimeMillis() + durationMs;
        } else {
            this.statusClearAt = 0;
        }
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
