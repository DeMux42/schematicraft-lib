package com.schematicraft.lib.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;

/**
 * Scrollable list widget for displaying schematics.
 * Supports three entry types: schematic items, section headers, and messages.
 */
public class SchematicListWidget extends ObjectSelectionList<SchematicListWidget.BaseEntry> {

    private final BiConsumer<String, String> onSelect;
    private final int listLeft;

    public SchematicListWidget(Minecraft mc, int width, int height, int top, int left,
                               BiConsumer<String, String> onSelect) {
        super(mc, width, height, top, 24); // 24px per entry
        this.onSelect = onSelect;
        this.listLeft = left;
        this.setX(left);
    }

    public void clearEntries() {
        this.children().clear();
    }

    @Override
    public int addEntry(BaseEntry entry) {
        return super.addEntry(entry);
    }

    @Override
    public int getRowWidth() {
        return this.width - 12;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.listLeft + this.width - 6;
    }

    public static abstract class BaseEntry extends ObjectSelectionList.Entry<BaseEntry> {
        protected final SchematicListWidget parent;

        protected BaseEntry(SchematicListWidget parent) {
            this.parent = parent;
        }
    }

    public static class SchematicEntry extends BaseEntry {
        private final String schematicId;
        private final String title;
        private final String subtitle;
        private final String thumbnailUrl;

        public String getSchematicId() { return schematicId; }

        public SchematicEntry(SchematicListWidget parent, String id, String title, String subtitle) {
            this(parent, id, title, subtitle, null);
        }

        public SchematicEntry(SchematicListWidget parent, String id, String title, String subtitle, String thumbnailUrl) {
            super(parent);
            this.schematicId = id;
            this.title = title != null ? title : "Untitled";
            this.subtitle = subtitle != null ? subtitle : "";
            this.thumbnailUrl = thumbnailUrl;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left,
                           int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int textX = left + 4;

            if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
                var cache = com.schematicraft.lib.client.ThumbnailCache.get();
                net.minecraft.resources.ResourceLocation tex = cache.getTexture(schematicId, thumbnailUrl);
                if (tex != null) {
                    int thumbH = height - 2;
                    int thumbW = (int)(thumbH * 16.0 / 9.0); // 16:9
                    int[] dims = cache.getDimensions(schematicId);
                    if (dims != null) {
                        thumbW = (int)(thumbH * (double)dims[0] / dims[1]);
                    }
                    if (thumbW > width / 2) thumbW = width / 2;
                    com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, tex);
                    graphics.blit(tex, left + 1, top + 1, 0, 0, thumbW, thumbH, thumbW, thumbH);
                    textX = left + thumbW + 6;
                }
            }

            String displayTitle = title;
            int maxTitleWidth = left + width - textX - 4;
            if (mc.font.width(displayTitle) > maxTitleWidth) {
                while (mc.font.width(displayTitle + "...") > maxTitleWidth && displayTitle.length() > 3)
                    displayTitle = displayTitle.substring(0, displayTitle.length() - 1);
                displayTitle += "...";
            }

            // Vertically center text within the entry
            int textHeight = subtitle.isEmpty() ? 9 : 20; // single line vs title+subtitle
            int textY = top + (height - textHeight) / 2;
            graphics.drawString(mc.font, displayTitle, textX, textY, hovered ? 0xFFFF55 : 0xE0E0E0);

            if (!subtitle.isEmpty()) {
                String displaySub = subtitle;
                if (mc.font.width(displaySub) > maxTitleWidth) {
                    while (mc.font.width(displaySub + "...") > maxTitleWidth && displaySub.length() > 3)
                        displaySub = displaySub.substring(0, displaySub.length() - 1);
                    displaySub += "...";
                }
                graphics.drawString(mc.font, displaySub, textX, textY + 11, 0x707070);
            }

            if (hovered) {
                graphics.fill(left, top, left + width, top + height, 0x1800FF00);
                graphics.fill(left, top, left + 1, top + height, 0x4000FF00); // Left accent
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                parent.onSelect.accept(schematicId, title);
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(title);
        }
    }

    public static class HeaderEntry extends BaseEntry {
        private final String text;

        public HeaderEntry(SchematicListWidget parent, String text) {
            super(parent);
            this.text = text;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left,
                           int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            // Bundle header
            graphics.drawString(mc.font, text, left + 3, top + 7, 0xFFAA00);
            graphics.fill(left + 3, top + height - 1, left + width - 3, top + height, 0x20FFAA00);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(text);
        }
    }

    public static class MessageEntry extends BaseEntry {
        private final String message;

        public MessageEntry(SchematicListWidget parent, String message) {
            super(parent);
            this.message = message;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left,
                           int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int textWidth = mc.font.width(message);
            graphics.drawString(mc.font, message, left + (width - textWidth) / 2, top + 6, 0xAAAAAA);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(message);
        }
    }
}
