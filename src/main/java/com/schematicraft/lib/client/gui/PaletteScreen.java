package com.schematicraft.lib.client.gui;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.BlockMapping;
import com.schematicraft.lib.core.PaletteEntry;
import com.schematicraft.lib.core.SchematicEntry;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Palette picker. Apply only.
 *
 * Shows the palettes the user already created on the website and applies the
 * selected one to this schematic at download time. There is no palette
 * authoring in game: palettes are created and edited on the website.
 */
public class PaletteScreen extends Screen implements SchematicraftScreen {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Layout
    private static final int COLS = 4;
    private static final int CARD_GAP = 6;
    private static final int CARD_PADDING = 8;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 40;
    private static final int MAPPING_ROW_H = 14;
    private static final int MAX_VISIBLE_MAPPINGS = 6;

    // State
    private final SchematicEntry schematic;
    private final EditorJourney journey;
    private final TargetDevice targetDevice;
    private List<PaletteEntry> palettes = new ArrayList<>();
    private int selectedIndex = -1;
    private boolean loading = true;
    private String errorMessage = null;
    /** Transient message for a failed download or load. Keeps the grid visible. */
    private String statusText = "";
    private int scrollOffset = 0;
    private int maxScroll = 0;

    // Computed layout
    private int gridTop;
    private int gridBottom;
    private int cardW;
    private int cardH;

    // Buttons
    private Button applyButton;
    private Button backButton;

    public PaletteScreen(SchematicEntry schematic, EditorJourney journey) {
        super(Component.literal("Palettes"));
        this.schematic = schematic;
        this.journey = journey;
        this.targetDevice = journey.target();
    }

    @Override
    protected void init() {
        super.init();

        gridTop = HEADER_H;
        gridBottom = this.height - FOOTER_H;
        int gridWidth = this.width - CARD_PADDING * 2;
        cardW = (gridWidth - CARD_GAP * (COLS - 1)) / COLS;
        cardH = 120;

        int btnY = this.height - FOOTER_H + 10;
        int centerX = this.width / 2;

        backButton = Button.builder(Component.literal("Back"),
                b -> Minecraft.getInstance().setScreen(new LibraryScreen(journey)))
                .bounds(centerX - 130, btnY, 60, 20).build();
        this.addRenderableWidget(backButton);

        applyButton = Button.builder(Component.literal("Download with Palette"),
                b -> downloadWithPalette())
                .bounds(centerX - 60, btnY, 190, 20).build();
        applyButton.active = false;
        this.addRenderableWidget(applyButton);

        if (loading) {
            loadPalettes();
        }
    }

    private void loadPalettes() {
        if (!ModConfig.hasApiKey()) {
            loading = false;
            palettes = new ArrayList<>();
            return;
        }
        loading = true;
        errorMessage = null;
        SchematiCraftAPIWrapper.get().loadPalettes(schematic.id()).thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                palettes = result;
                loading = false;
                recomputeScroll();
                LOGGER.info("Loaded {} palettes for schematic {}", palettes.size(), schematic.id());
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                loading = false;
                String msg = SchematiCraftAPIWrapper.rootMessage(ex);
                // Treat 404 or connection errors as "no palettes"
                if (msg.contains("404") || msg.contains("EOF") || msg.contains("Connection")) {
                    palettes = new ArrayList<>();
                    LOGGER.debug("Palette load unavailable ({}), showing empty list", msg);
                } else {
                    errorMessage = "Failed to load palettes: " + msg;
                    LOGGER.warn("Palette load failed: {}", errorMessage);
                }
            });
            return null;
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);

        renderHeader(graphics);

        if (loading) {
            graphics.drawCenteredString(this.font, "Loading palettes...",
                    this.width / 2, this.height / 2, GuiColors.TEXT_SECONDARY);
        } else if (errorMessage != null) {
            graphics.drawCenteredString(this.font, errorMessage,
                    this.width / 2, this.height / 2, GuiColors.ERROR);
        } else if (palettes.isEmpty()) {
            graphics.drawCenteredString(this.font, "No palettes for this schematic",
                    this.width / 2, this.height / 2 - 6, GuiColors.TEXT_SECONDARY);
            graphics.drawCenteredString(this.font, "Create palettes on schematicraft.com",
                    this.width / 2, this.height / 2 + 6, GuiColors.TEXT_DIM);
        } else {
            renderPaletteGrid(graphics, mouseX, mouseY);
        }

        graphics.fill(0, this.height - FOOTER_H, this.width, this.height - FOOTER_H + 1,
                GuiColors.BORDER_SEPARATOR);

        // Failure message sits above the footer so the palette grid stays visible.
        if (!statusText.isEmpty()) {
            graphics.drawCenteredString(this.font, statusText,
                    this.width / 2, this.height - FOOTER_H - 12, GuiColors.ERROR);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void failLoad(String reason) {
        statusText = reason;
        LOGGER.warn("Palette load failed: {}", reason);
        applyButton.setMessage(Component.literal("Download with Palette"));
        applyButton.active = true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Skip default
    }

    private void renderHeader(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, HEADER_H, GuiColors.PANEL_BG);
        graphics.fill(0, HEADER_H - 1, this.width, HEADER_H, GuiColors.BORDER_SEPARATOR);

        String title = "Palettes for: " + (schematic.title() != null ? schematic.title() : "Untitled");
        graphics.drawString(this.font, title, CARD_PADDING, 6, GuiColors.SELECTED, false);
        graphics.drawString(this.font, "Select a palette, then download", CARD_PADDING, 18,
                GuiColors.TEXT_DIM, false);
    }

    private void renderPaletteGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.enableScissor(0, gridTop, this.width, gridBottom);

        int y = gridTop + CARD_PADDING - scrollOffset;

        for (int i = 0; i < palettes.size(); i++) {
            int col = i % COLS;
            int cardX = CARD_PADDING + col * (cardW + CARD_GAP);
            int cardY = y;

            if (cardY + cardH > gridTop && cardY < gridBottom) {
                renderPaletteCard(graphics, palettes.get(i), cardX, cardY, i, mouseX, mouseY);
            }

            if (col == COLS - 1) {
                y += cardH + CARD_GAP;
            }
        }

        graphics.disableScissor();
    }

    private void renderPaletteCard(GuiGraphics graphics, PaletteEntry palette,
                                    int x, int y, int index, int mouseX, int mouseY) {
        boolean selected = index == selectedIndex;
        boolean hovered = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;

        int bg = selected ? GuiColors.TILE_SELECTED_BG : (hovered ? GuiColors.TILE_HOVER_BG : GuiColors.TILE_BG);
        int border = selected ? GuiColors.TILE_SELECTED_BORDER : (hovered ? GuiColors.TILE_HOVER_BORDER : GuiColors.TILE_BORDER);
        graphics.fill(x, y, x + cardW, y + cardH, bg);
        drawBorder(graphics, x, y, cardW, cardH, border);

        String name = palette.name() != null ? palette.name() : "Unnamed";
        graphics.drawString(this.font, truncate(name, cardW - 8),
                x + 4, y + 4, selected ? GuiColors.SELECTED : GuiColors.TEXT_PRIMARY, false);

        graphics.drawString(this.font, palette.mappingCount() + " mappings",
                x + 4, y + 16, GuiColors.TEXT_DIM, false);

        String vis = palette.visibility();
        int visColor = "public".equals(vis) ? GuiColors.SUCCESS : GuiColors.TEXT_DIM;
        graphics.drawString(this.font, vis, x + cardW - this.font.width(vis) - 4, y + 4, visColor, false);

        int mappingY = y + 30;
        List<BlockMapping> mappings = palette.mappings();
        int shown = Math.min(mappings.size(), MAX_VISIBLE_MAPPINGS);
        for (int i = 0; i < shown; i++) {
            BlockMapping m = mappings.get(i);
            String line = shortBlockName(m.original()) + " to " + shortBlockName(m.replacement());
            graphics.drawString(this.font, truncate(line, cardW - 8),
                    x + 4, mappingY + i * MAPPING_ROW_H, GuiColors.TEXT_SECONDARY, false);
        }
        if (mappings.size() > shown) {
            graphics.drawString(this.font, "+" + (mappings.size() - shown) + " more",
                    x + 4, mappingY + shown * MAPPING_ROW_H, GuiColors.TEXT_DIM, false);
        }
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseY >= gridTop && mouseY < gridBottom && button == 0) {
            int clickedIndex = getCardIndexAt(mouseX, mouseY);
            if (clickedIndex >= 0 && clickedIndex < palettes.size()) {
                selectedIndex = clickedIndex;
                applyButton.active = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= gridTop && mouseY < gridBottom) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(scrollY * 30)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(new LibraryScreen(journey));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && selectedIndex >= 0) {
            downloadWithPalette();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // --- Actions ---

    private void downloadWithPalette() {
        if (selectedIndex < 0 || selectedIndex >= palettes.size()) return;
        if (!targetDevice.isAvailable()) return;

        PaletteEntry palette = palettes.get(selectedIndex);
        LOGGER.info("Downloading schematic {} with palette {}", schematic.id(), palette.id());

        statusText = "";
        applyButton.active = false;
        applyButton.setMessage(Component.literal("Downloading..."));

        // Request the format and editor for the resolved target device so palette
        // apply works for every editor, not just Building Gadgets.
        SchematiCraftAPIWrapper.get().downloadSchematicWithPalette(
                        schematic.id(), palette.id(),
                        targetDevice.getDownloadFormat(), targetDevice.getDownloadEditor())
                .thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    byte[] data = java.nio.file.Files.readAllBytes(result.file);
                    java.nio.file.Files.deleteIfExists(result.file);

                    var handler = LibraryScreen.getLoadHandler(targetDevice);
                    if (handler == null) {
                        failLoad("Loading is not available for "
                                + targetDevice.getDisplayName());
                        return;
                    }
                    String name = schematic.title() != null
                            ? schematic.title() : "schematic";
                    var loadResult = handler.load(targetDevice, data, name);
                    if (loadResult.success()) {
                        if (loadResult.confirmed()) {
                            SchematiCraftAPIWrapper.get()
                                    .submitSuccessFeedback(result.downloadId);
                        }
                        journey.returnToOrigin();
                    } else {
                        failLoad(loadResult.reason() != null
                                ? loadResult.reason()
                                : "Could not load into " + targetDevice.getDisplayName());
                    }
                } catch (Exception e) {
                    LOGGER.error("Palette download load failed", e);
                    applyButton.setMessage(Component.literal("Error"));
                    applyButton.active = true;
                }
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                LOGGER.warn("Palette download failed: {}", SchematiCraftAPIWrapper.rootMessage(ex));
                applyButton.setMessage(Component.literal("Download failed"));
                applyButton.active = true;
            });
            return null;
        });
    }

    // --- Helpers ---

    private int getCardIndexAt(double mouseX, double mouseY) {
        int relY = (int) mouseY - gridTop - CARD_PADDING + scrollOffset;
        int relX = (int) mouseX - CARD_PADDING;
        if (relX < 0 || relY < 0) return -1;

        int col = relX / (cardW + CARD_GAP);
        int row = relY / (cardH + CARD_GAP);
        if (col >= COLS) return -1;

        return row * COLS + col;
    }

    private void recomputeScroll() {
        int rows = (palettes.size() + COLS - 1) / COLS;
        int contentH = rows * (cardH + CARD_GAP) + CARD_PADDING * 2;
        int viewH = gridBottom - gridTop;
        maxScroll = Math.max(0, contentH - viewH);
        scrollOffset = Math.min(scrollOffset, maxScroll);
    }

    private String shortBlockName(String fullName) {
        if (fullName == null) return "?";
        if (fullName.startsWith("minecraft:")) return fullName.substring(10);
        return fullName;
    }

    private String truncate(String text, int maxPixelWidth) {
        if (text == null) return "";
        if (this.font.width(text) <= maxPixelWidth) return text;
        String ellipsis = "\u2026";
        int available = maxPixelWidth - this.font.width(ellipsis);
        if (available <= 0) return ellipsis;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (this.font.width(text.substring(0, mid)) <= available) lo = mid; else hi = mid - 1;
        }
        return text.substring(0, lo) + ellipsis;
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }
}
