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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Palette selection and preview screen.
 * Shows available palettes for a schematic in a 4-wide grid.
 * Each palette card shows name, mapping count, and a visual summary
 * of block replacements. User can select a palette and confirm to
 * download the schematic with that palette applied.
 */
public class PaletteScreen extends Screen {
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
    private final TargetDevice targetDevice;
    private List<PaletteEntry> palettes = new ArrayList<>();
    private int selectedIndex = -1;
    private boolean loading = true;
    private String errorMessage = null;
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
    private Button newPaletteButton;

    public PaletteScreen(SchematicEntry schematic, TargetDevice targetDevice) {
        super(Component.literal("Palette Manager"));
        this.schematic = schematic;
        this.targetDevice = targetDevice;
    }

    @Override
    protected void init() {
        super.init();

        gridTop = HEADER_H;
        gridBottom = this.height - FOOTER_H;
        int gridWidth = this.width - CARD_PADDING * 2;
        cardW = (gridWidth - CARD_GAP * (COLS - 1)) / COLS;
        cardH = 120; // Fixed card height

        // Footer buttons
        int btnY = this.height - FOOTER_H + 10;
        int centerX = this.width / 2;

        backButton = Button.builder(Component.literal("Back"),
                b -> Minecraft.getInstance().setScreen(new LibraryScreen()))
                .bounds(centerX - 150, btnY, 60, 20).build();
        this.addRenderableWidget(backButton);

        applyButton = Button.builder(Component.literal("Download with Palette"),
                b -> downloadWithPalette())
                .bounds(centerX - 80, btnY, 130, 20).build();
        applyButton.active = false;
        this.addRenderableWidget(applyButton);

        newPaletteButton = Button.builder(Component.literal("+ New Palette"),
                b -> openPaletteEditor())
                .bounds(centerX + 60, btnY, 90, 20).build();
        this.addRenderableWidget(newPaletteButton);

        Button editButton = Button.builder(Component.literal("Edit Copy"),
                b -> editSelectedPalette())
                .bounds(centerX + 160, btnY, 70, 20).build();
        this.addRenderableWidget(editButton);

        // Load palettes from API
        if (loading) {
            loadPalettes();
        }
    }

    private void loadPalettes() {
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
                // Treat 404 as "no palettes" (endpoint may not be deployed yet)
                if (msg.contains("404")) {
                    palettes = new ArrayList<>();
                    LOGGER.debug("Palette endpoint not available (404), showing empty list");
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
        // Background
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);

        // Header
        renderHeader(graphics);

        // Palette grid
        if (loading) {
            graphics.drawCenteredString(this.font, "Loading palettes...",
                    this.width / 2, this.height / 2, GuiColors.TEXT_SECONDARY);
        } else if (errorMessage != null) {
            graphics.drawCenteredString(this.font, errorMessage,
                    this.width / 2, this.height / 2, GuiColors.ERROR);
        } else if (palettes.isEmpty()) {
            graphics.drawCenteredString(this.font, "No palettes available. Create one!",
                    this.width / 2, this.height / 2, GuiColors.TEXT_SECONDARY);
        } else {
            renderPaletteGrid(graphics, mouseX, mouseY);
        }

        // Footer separator
        graphics.fill(0, this.height - FOOTER_H, this.width, this.height - FOOTER_H + 1,
                GuiColors.BORDER_SEPARATOR);

        // Widgets (buttons)
        super.render(graphics, mouseX, mouseY, partialTick);
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
        graphics.drawString(this.font, palettes.size() + " palette" + (palettes.size() != 1 ? "s" : "") + " available",
                CARD_PADDING, 18, GuiColors.TEXT_DIM, false);
    }

    private void renderPaletteGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.enableScissor(0, gridTop, this.width, gridBottom);

        int x = CARD_PADDING;
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

        // Card background
        int bg = selected ? GuiColors.TILE_SELECTED_BG : (hovered ? GuiColors.TILE_HOVER_BG : GuiColors.TILE_BG);
        int border = selected ? GuiColors.TILE_SELECTED_BORDER : (hovered ? GuiColors.TILE_HOVER_BORDER : GuiColors.TILE_BORDER);
        graphics.fill(x, y, x + cardW, y + cardH, bg);
        drawBorder(graphics, x, y, cardW, cardH, border);

        // Palette name
        String name = palette.name() != null ? palette.name() : "Unnamed";
        graphics.drawString(this.font, truncate(name, cardW - 8),
                x + 4, y + 4, selected ? GuiColors.SELECTED : GuiColors.TEXT_PRIMARY, false);

        // Mapping count
        graphics.drawString(this.font, palette.mappingCount() + " mappings",
                x + 4, y + 16, GuiColors.TEXT_DIM, false);

        // Visibility badge
        String vis = palette.visibility();
        int visColor = "public".equals(vis) ? GuiColors.SUCCESS : GuiColors.TEXT_DIM;
        graphics.drawString(this.font, vis, x + cardW - this.font.width(vis) - 4, y + 4, visColor, false);

        // Block mapping preview (show first few mappings)
        int mappingY = y + 30;
        List<BlockMapping> mappings = palette.mappings();
        int shown = Math.min(mappings.size(), MAX_VISIBLE_MAPPINGS);
        for (int i = 0; i < shown; i++) {
            BlockMapping m = mappings.get(i);
            String from = shortBlockName(m.original());
            String to = shortBlockName(m.replacement());
            String line = from + " > " + to;
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
            Minecraft.getInstance().setScreen(new LibraryScreen());
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

        // Download with paletteId parameter (server applies the palette)
        applyButton.active = false;
        applyButton.setMessage(Component.literal("Downloading..."));

        SchematiCraftAPIWrapper.get().downloadSchematicWithPalette(schematic.id(), palette.id())
                .thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    byte[] data = java.nio.file.Files.readAllBytes(result.file);
                    java.nio.file.Files.deleteIfExists(result.file);

                    if (LibraryScreen.getLoadHandler() != null
                            && LibraryScreen.getLoadHandler().load(targetDevice, data)) {
                        SchematiCraftAPIWrapper.get().submitSuccessFeedback(result.downloadId);
                        Minecraft.getInstance().setScreen(null);
                    } else {
                        applyButton.setMessage(Component.literal("Load failed"));
                        applyButton.active = true;
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

    private void openPaletteEditor() {
        // Download schematic (or use cache) to extract block list, then open editor
        openEditorWithBlocks(null);
    }

    private void editSelectedPalette() {
        if (selectedIndex < 0 || selectedIndex >= palettes.size()) return;
        openEditorWithBlocks(palettes.get(selectedIndex));
    }

    private void openEditorWithBlocks(@Nullable PaletteEntry base) {
        var cache = com.schematicraft.lib.core.SchematicDataCache.get();

        if (cache.has(schematic.id())) {
            // Already cached, extract blocks and open immediately
            List<String> blocks = cache.extractBlockList(schematic.id());
            doOpenEditor(base, blocks);
        } else {
            // Need to download first
            loading = true;
            SchematiCraftAPIWrapper.get().downloadSchematic(schematic.id()).thenAccept(result -> {
                Minecraft.getInstance().execute(() -> {
                    try {
                        byte[] data = java.nio.file.Files.readAllBytes(result.file);
                        java.nio.file.Files.deleteIfExists(result.file);
                        cache.put(schematic.id(), data);
                        List<String> blocks = com.schematicraft.lib.core.SchematicDataCache
                                .extractBlockListFromData(data);
                        doOpenEditor(base, blocks);
                    } catch (Exception e) {
                        loading = false;
                        errorMessage = "Failed to load schematic: " + e.getMessage();
                    }
                });
            }).exceptionally(ex -> {
                Minecraft.getInstance().execute(() -> {
                    loading = false;
                    errorMessage = "Download failed: " + SchematiCraftAPIWrapper.rootMessage(ex);
                });
                return null;
            });
        }
    }

    private void doOpenEditor(@Nullable PaletteEntry base, List<String> schematicBlocks) {
        if (editorOpener != null) {
            editorOpener.open(schematic, targetDevice, base, schematicBlocks);
        } else {
            LOGGER.warn("No palette editor opener registered");
        }
    }

    /** Editor opener callback. Set by the editor mod at startup. */
    private static PaletteEditorOpener editorOpener = null;

    @FunctionalInterface
    public interface PaletteEditorOpener {
        void open(SchematicEntry schematic, TargetDevice target,
                  @Nullable PaletteEntry base, @Nullable List<String> schematicBlocks);
    }

    public static void setEditorOpener(PaletteEditorOpener opener) {
        editorOpener = opener;
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
        // Remove minecraft: prefix for display
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

    // --- Public accessor for LoadHandler ---

    @Nullable
    private static LibraryScreen.LoadHandler getLoadHandler() {
        return LibraryScreen.getLoadHandler();
    }
}
