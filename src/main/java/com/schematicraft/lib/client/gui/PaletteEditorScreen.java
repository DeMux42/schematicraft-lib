package com.schematicraft.lib.client.gui;

import com.mojang.logging.LogUtils;
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
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Palette editor screen for creating and editing block mappings.
 * Shows the schematic's unique blocks on the left with editable
 * replacement targets on the right. Block search uses the game's
 * built-in registry for instant autocomplete.
 */
public class PaletteEditorScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 44;
    private static final int ROW_H = 22;
    private static final int PADDING = 8;
    private static final int SEARCH_POPUP_W = 180;
    private static final int SEARCH_POPUP_H = 140;
    private static final int SEARCH_RESULT_H = 16;
    private static final int MAX_SEARCH_RESULTS = 8;

    private final SchematicEntry schematic;
    private final TargetDevice targetDevice;
    @Nullable private final PaletteEntry basePalette;

    // Mapping state
    private final List<MappingRow> mappingRows = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;

    // Palette name
    private EditBox nameField;

    // Block search popup
    private boolean searchOpen = false;
    private int searchTargetRow = -1;
    private boolean searchEditingOriginal = false;
    private EditBox searchField;
    private List<String> searchResults = new ArrayList<>();
    private int searchSelectedIdx = 0;
    private int searchPopupX, searchPopupY;

    // Buttons
    private Button saveButton;
    private Button cancelButton;

    // Status
    private String statusText = "";
    private int statusColor = GuiColors.TEXT_DIM;

    /** A single mapping row in the editor. */
    private static class MappingRow {
        String original;
        String replacement;
        String originalDisplay;
        String replacementDisplay;

        MappingRow(String original, String replacement) {
            this.original = original;
            this.replacement = replacement;
            this.originalDisplay = shortName(original);
            this.replacementDisplay = shortName(replacement);
        }

        private static String shortName(String name) {
            if (name == null || name.isEmpty()) return "(none)";
            if (name.startsWith("minecraft:")) return name.substring(10);
            return name;
        }
    }

    public PaletteEditorScreen(SchematicEntry schematic, TargetDevice targetDevice,
                                @Nullable PaletteEntry basePalette,
                                @Nullable List<String> schematicBlocks) {
        super(Component.literal("New Palette"));
        this.schematic = schematic;
        this.targetDevice = targetDevice;
        this.basePalette = basePalette;

        // Initialize mapping rows from base palette or schematic blocks
        if (basePalette != null && !basePalette.isEmpty()) {
            for (BlockMapping m : basePalette.mappings()) {
                mappingRows.add(new MappingRow(m.original(), m.replacement()));
            }
        } else if (schematicBlocks != null) {
            for (String block : schematicBlocks) {
                if (!block.equals("minecraft:air")) {
                    mappingRows.add(new MappingRow(block, block));
                }
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // Ensure global palette is loaded
        com.schematicraft.lib.core.GlobalPalette.get().ensureLoaded();

        // Name field
        nameField = new EditBox(this.font, centerX - 100, 8, 200, 16, Component.literal(""));
        nameField.setMaxLength(255);
        nameField.setHint(Component.literal("Palette name..."));
        if (basePalette != null) {
            nameField.setValue(basePalette.name() + " (copy)");
        }
        this.addRenderableWidget(nameField);

        // Footer buttons
        int btnY = this.height - FOOTER_H + 12;
        cancelButton = Button.builder(Component.literal("Cancel"),
                b -> Minecraft.getInstance().setScreen(
                        new PaletteScreen(schematic, targetDevice)))
                .bounds(centerX - 120, btnY, 70, 20).build();
        this.addRenderableWidget(cancelButton);

        saveButton = Button.builder(Component.literal("Save Palette"),
                b -> savePalette())
                .bounds(centerX - 40, btnY, 100, 20).build();
        this.addRenderableWidget(saveButton);

        // Add Mapping button (allows adding rows when starting from scratch)
        Button addMappingButton = Button.builder(Component.literal("+ Add Block"),
                b -> addEmptyMapping())
                .bounds(centerX + 70, btnY, 80, 20).build();
        this.addRenderableWidget(addMappingButton);

        // Search field (hidden until popup opens)
        searchField = new EditBox(this.font, 0, 0, SEARCH_POPUP_W - 4, 14, Component.literal(""));
        searchField.setMaxLength(100);
        searchField.setHint(Component.literal("Search blocks..."));
        searchField.setResponder(this::onSearchTextChanged);
        searchField.visible = false;
        this.addRenderableWidget(searchField);

        recomputeScroll();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);

        // Header
        graphics.fill(0, 0, this.width, HEADER_H, GuiColors.PANEL_BG);
        graphics.fill(0, HEADER_H - 1, this.width, HEADER_H, GuiColors.BORDER_SEPARATOR);
        graphics.drawString(this.font, "Palette Editor", PADDING, 12, GuiColors.TEXT_DIM, false);

        // Column headers
        int listTop = HEADER_H + 4;
        int colOrigX = PADDING + 4;
        int colArrowX = this.width / 2 - 10;
        int colReplX = this.width / 2 + 10;
        graphics.drawString(this.font, "Original Block", colOrigX, listTop, GuiColors.TEXT_SECONDARY, false);
        graphics.drawString(this.font, "Replacement", colReplX, listTop, GuiColors.TEXT_SECONDARY, false);

        // Mapping rows (scrollable)
        int rowsTop = listTop + 14;
        int rowsBottom = this.height - FOOTER_H;
        graphics.enableScissor(0, rowsTop, this.width, rowsBottom);

        for (int i = 0; i < mappingRows.size(); i++) {
            int rowY = rowsTop + i * ROW_H - scrollOffset;
            if (rowY + ROW_H < rowsTop || rowY > rowsBottom) continue;

            MappingRow row = mappingRows.get(i);
            boolean hovered = mouseY >= rowY && mouseY < rowY + ROW_H && mouseY >= rowsTop && mouseY < rowsBottom;

            if (hovered) graphics.fill(0, rowY, this.width, rowY + ROW_H, GuiColors.HOVER_BG);
            graphics.fill(0, rowY + ROW_H - 1, this.width, rowY + ROW_H, 0x10FFFFFF);

            // Original block name
            graphics.drawString(this.font, row.originalDisplay, colOrigX, rowY + 6, GuiColors.TEXT_PRIMARY, false);

            // Arrow
            graphics.drawString(this.font, ">", colArrowX, rowY + 6, GuiColors.TEXT_DIM, false);

            // Replacement (clickable)
            boolean isChanged = !row.original.equals(row.replacement);
            int replColor = isChanged ? GuiColors.SELECTED : GuiColors.TEXT_SECONDARY;
            graphics.drawString(this.font, row.replacementDisplay, colReplX, rowY + 6, replColor, false);

            // Click target indicator
            if (hovered && mouseX >= colReplX) {
                int underlineY = rowY + 15;
                graphics.fill(colReplX, underlineY, colReplX + this.font.width(row.replacementDisplay), underlineY + 1, GuiColors.SELECTED);
            }
        }

        graphics.disableScissor();

        // Footer
        graphics.fill(0, this.height - FOOTER_H, this.width, this.height - FOOTER_H + 1, GuiColors.BORDER_SEPARATOR);

        // Status
        if (!statusText.isEmpty()) {
            graphics.drawCenteredString(this.font, statusText, this.width / 2, this.height - FOOTER_H + 32, statusColor);
        }

        // Widgets (buttons, name field)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Search popup (drawn on top)
        if (searchOpen) {
            renderSearchPopup(graphics, mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    private void renderSearchPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = searchPopupX;
        int y = searchPopupY;
        int h = 20 + Math.min(searchResults.size(), MAX_SEARCH_RESULTS) * SEARCH_RESULT_H + 4;

        // Background
        graphics.fill(x - 2, y - 2, x + SEARCH_POPUP_W + 2, y + h + 2, GuiColors.BORDER_SEPARATOR);
        graphics.fill(x, y, x + SEARCH_POPUP_W, y + h, 0xFF1A1A1A);

        // Results
        int resultY = y + 20;
        int shown = Math.min(searchResults.size(), MAX_SEARCH_RESULTS);
        for (int i = 0; i < shown; i++) {
            String block = searchResults.get(i);
            int ry = resultY + i * SEARCH_RESULT_H;
            boolean resultHovered = mouseX >= x && mouseX < x + SEARCH_POPUP_W
                    && mouseY >= ry && mouseY < ry + SEARCH_RESULT_H;
            boolean selected = i == searchSelectedIdx;

            if (selected || resultHovered) {
                graphics.fill(x, ry, x + SEARCH_POPUP_W, ry + SEARCH_RESULT_H, GuiColors.TILE_HOVER_BG);
            }

            String display = block.startsWith("minecraft:") ? block.substring(10) : block;
            boolean isFav = com.schematicraft.lib.core.GlobalPalette.get().hasFavorite(block);
            String prefix = isFav ? "\u2605 " : "";
            graphics.drawString(this.font, prefix + display, x + 4, ry + 4,
                    selected ? GuiColors.SELECTED : GuiColors.TEXT_PRIMARY, false);
        }

        if (searchResults.isEmpty() && searchField.getValue().length() >= 2) {
            graphics.drawString(this.font, "No matches", x + 4, resultY + 4, GuiColors.TEXT_DIM, false);
        }
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (searchOpen) {
            // Check if clicking a search result
            int resultY = searchPopupY + 20;
            int shown = Math.min(searchResults.size(), MAX_SEARCH_RESULTS);
            for (int i = 0; i < shown; i++) {
                int ry = resultY + i * SEARCH_RESULT_H;
                if (mouseX >= searchPopupX && mouseX < searchPopupX + SEARCH_POPUP_W
                        && mouseY >= ry && mouseY < ry + SEARCH_RESULT_H) {
                    if (button == 0) {
                        // Left click: apply as replacement
                        applySearchResult(searchResults.get(i));
                    } else if (button == 1) {
                        // Right click: toggle favorite
                        String block = searchResults.get(i);
                        var gp = com.schematicraft.lib.core.GlobalPalette.get();
                        if (gp.hasFavorite(block)) gp.removeFavorite(block);
                        else gp.addFavorite(block);
                    }
                    return true;
                }
            }
            // Click outside popup closes it
            closeSearch();
            return true;
        }

        // Check if clicking a replacement cell OR original cell
        int rowsTop = HEADER_H + 4 + 14;
        int colOrigX = PADDING + 4;
        int colReplX = this.width / 2 + 10;
        if (mouseY >= rowsTop && mouseY < this.height - FOOTER_H) {
            int rowIdx = ((int) mouseY - rowsTop + scrollOffset) / ROW_H;
            if (rowIdx >= 0 && rowIdx < mappingRows.size()) {
                if (mouseX >= colReplX) {
                    searchEditingOriginal = false;
                    openSearchForRow(rowIdx, (int) mouseX, (int) mouseY);
                    return true;
                } else if (mouseX >= colOrigX && mouseX < colReplX) {
                    searchEditingOriginal = true;
                    openSearchForRow(rowIdx, (int) mouseX, (int) mouseY);
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (searchOpen) { closeSearch(); return true; }
            Minecraft.getInstance().setScreen(new PaletteScreen(schematic, targetDevice));
            return true;
        }

        if (searchOpen) {
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                searchSelectedIdx = Math.min(searchSelectedIdx + 1, Math.min(searchResults.size(), MAX_SEARCH_RESULTS) - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                searchSelectedIdx = Math.max(searchSelectedIdx - 1, 0);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER && !searchResults.isEmpty()) {
                int idx = Math.min(searchSelectedIdx, searchResults.size() - 1);
                if (idx >= 0) applySearchResult(searchResults.get(idx));
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (searchOpen) return true;
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(scrollY * ROW_H)));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // --- Search ---

    private void openSearchForRow(int rowIdx, int mouseX, int mouseY) {
        searchTargetRow = rowIdx;
        searchOpen = true;
        searchSelectedIdx = 0;
        searchResults.clear();

        searchPopupX = Math.min(mouseX, this.width - SEARCH_POPUP_W - 4);
        searchPopupY = Math.min(mouseY, this.height - 180);

        searchField.setX(searchPopupX + 2);
        searchField.setY(searchPopupY + 2);
        searchField.visible = true;
        searchField.setValue("");
        searchField.setFocused(true);
        this.setFocused(searchField);
    }

    private void closeSearch() {
        searchOpen = false;
        searchField.visible = false;
        searchField.setFocused(false);
        searchTargetRow = -1;
    }

    private void applySearchResult(String blockName) {
        if (searchTargetRow >= 0 && searchTargetRow < mappingRows.size()) {
            MappingRow row = mappingRows.get(searchTargetRow);
            if (searchEditingOriginal) {
                row.original = blockName;
                row.originalDisplay = MappingRow.shortName(blockName);
            } else {
                row.replacement = blockName;
                row.replacementDisplay = MappingRow.shortName(blockName);
            }
        }
        closeSearch();
    }

    private void onSearchTextChanged(String text) {
        searchSelectedIdx = 0;
        if (text.length() < 2) {
            searchResults.clear();
            // Show favorites when search is empty-ish
            List<String> favs = com.schematicraft.lib.core.GlobalPalette.get().getFavorites();
            if (!favs.isEmpty() && text.isEmpty()) {
                searchResults = new ArrayList<>(favs.subList(0, Math.min(favs.size(), MAX_SEARCH_RESULTS)));
            }
            return;
        }

        String lower = text.toLowerCase();
        List<String> favs = com.schematicraft.lib.core.GlobalPalette.get().getFavorites();

        searchResults = BuiltInRegistries.BLOCK.keySet().stream()
                .map(ResourceLocation::toString)
                .filter(name -> {
                    String shortName = name.startsWith("minecraft:") ? name.substring(10) : name;
                    return shortName.contains(lower) || name.contains(lower);
                })
                .sorted((a, b) -> {
                    // Favorites first
                    boolean aFav = favs.contains(a);
                    boolean bFav = favs.contains(b);
                    if (aFav && !bFav) return -1;
                    if (!aFav && bFav) return 1;
                    // Then starts-with matches
                    String sa = a.startsWith("minecraft:") ? a.substring(10) : a;
                    String sb = b.startsWith("minecraft:") ? b.substring(10) : b;
                    boolean aStarts = sa.startsWith(lower);
                    boolean bStarts = sb.startsWith(lower);
                    if (aStarts && !bStarts) return -1;
                    if (!aStarts && bStarts) return 1;
                    return sa.compareTo(sb);
                })
                .limit(MAX_SEARCH_RESULTS * 2)
                .collect(Collectors.toList());
    }

    // --- Actions ---

    private void addEmptyMapping() {
        // Open search popup to pick the "original" block, then add a row
        // For simplicity, add a placeholder row that the user can edit
        mappingRows.add(new MappingRow("minecraft:stone", "minecraft:stone"));
        recomputeScroll();
        // Auto-scroll to bottom to show the new row
        scrollOffset = maxScroll;
    }

    private void savePalette() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            statusText = "Name required";
            statusColor = GuiColors.ERROR;
            return;
        }

        // Build mappings (only include changed ones)
        List<BlockMapping> mappings = new ArrayList<>();
        for (MappingRow row : mappingRows) {
            if (!row.original.equals(row.replacement)) {
                mappings.add(new BlockMapping(row.original, row.replacement));
            }
        }

        if (mappings.isEmpty()) {
            statusText = "No changes to save";
            statusColor = GuiColors.WARNING;
            return;
        }

        statusText = "Saving...";
        statusColor = GuiColors.INFO;
        saveButton.active = false;

        SchematiCraftAPIWrapper.get().createPalette(name, mappings, schematic.id())
                .thenAccept(palette -> {
            Minecraft.getInstance().execute(() -> {
                LOGGER.info("Palette created: {} with {} mappings", palette.id(), mappings.size());
                Minecraft.getInstance().setScreen(new PaletteScreen(schematic, targetDevice));
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                statusText = "Save failed: " + SchematiCraftAPIWrapper.rootMessage(ex);
                statusColor = GuiColors.ERROR;
                saveButton.active = true;
            });
            return null;
        });
    }

    private void recomputeScroll() {
        int contentH = mappingRows.size() * ROW_H;
        int viewH = this.height - HEADER_H - 18 - FOOTER_H;
        maxScroll = Math.max(0, contentH - viewH);
    }
}
