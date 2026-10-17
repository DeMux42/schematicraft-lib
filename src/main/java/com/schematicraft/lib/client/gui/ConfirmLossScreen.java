package com.schematicraft.lib.client.gui;

import com.schematicraft.lib.core.IngameDownload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Asked before a download that loses content a player cares about: what does
 * not carry over, in the website's words, and the server's notes on it.
 * "Load anyway" goes back to the library and repeats the download without
 * asking, which then loads as any other.
 */
public class ConfirmLossScreen extends Screen implements SchematicraftScreen {
    private static final int TEXT_W = 300;
    private static final int LINE_H = 11;

    private final Screen parent;
    private final IngameDownload.Loss loss;
    private final Runnable loadAnyway;

    private final List<OrderedText> notes = new ArrayList<>();
    private int top;

    public ConfirmLossScreen(Screen parent, IngameDownload.Loss loss, Runnable loadAnyway) {
        super(Text.literal("Some of this build does not carry over"));
        this.parent = parent;
        this.loss = loss;
        this.loadAnyway = loadAnyway;
    }

    @Override
    protected void init() {
        super.init();
        notes.clear();
        // The messages are plain text already: no formatting codes survive.
        for (String m : loss.messages()) {
            notes.addAll(this.textRenderer.wrapLines(Text.literal(m), TEXT_W));
        }
        int lines = loss.lost().size() + notes.size();
        int height = 20 + lines * LINE_H + 8 + 20;
        top = Math.max(10, (this.height - height) / 2);

        int buttonY = Math.min(this.height - 28, top + height - 20);
        int centerX = this.width / 2;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> cancel())
                .dimensions(centerX - 102, buttonY, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Load anyway"), b -> confirm())
                .dimensions(centerX + 2, buttonY, 100, 20).build());
    }

    private void cancel() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    private void confirm() {
        MinecraftClient.getInstance().setScreen(parent);
        loadAnyway.run();
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);
        int centerX = this.width / 2;
        int y = top;
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, y, GuiColors.TEXT_PRIMARY);
        y += 20;
        for (String line : loss.lostLines()) {
            graphics.drawCenteredTextWithShadow(this.textRenderer, line, centerX, y, GuiColors.WARNING);
            y += LINE_H;
        }
        for (OrderedText note : notes) {
            graphics.drawCenteredTextWithShadow(this.textRenderer, note, centerX, y, GuiColors.TEXT_SECONDARY);
            y += LINE_H;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
        // Skip default
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() { return false; }
}
