package com.schematicraft.lib.client.gui;

import com.schematicraft.lib.core.IngameDownload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
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

    private final List<FormattedCharSequence> notes = new ArrayList<>();
    private int top;

    public ConfirmLossScreen(Screen parent, IngameDownload.Loss loss, Runnable loadAnyway) {
        super(Component.literal("Some of this build does not carry over"));
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
            notes.addAll(this.font.split(Component.literal(m), TEXT_W));
        }
        int lines = loss.lost().size() + notes.size();
        int height = 20 + lines * LINE_H + 8 + 20;
        top = Math.max(10, (this.height - height) / 2);

        int buttonY = Math.min(this.height - 28, top + height - 20);
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
                .bounds(centerX - 102, buttonY, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Load anyway"), b -> confirm())
                .bounds(centerX + 2, buttonY, 100, 20).build());
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void confirm() {
        Minecraft.getInstance().setScreen(parent);
        loadAnyway.run();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);
        int centerX = this.width / 2;
        int y = top;
        graphics.centeredText(this.font, this.title, centerX, y, GuiColors.TEXT_PRIMARY);
        y += 20;
        for (String line : loss.lostLines()) {
            graphics.centeredText(this.font, line, centerX, y, GuiColors.WARNING);
            y += LINE_H;
        }
        for (FormattedCharSequence note : notes) {
            graphics.centeredText(this.font, note, centerX, y, GuiColors.TEXT_SECONDARY);
            y += LINE_H;
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Skip default
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
