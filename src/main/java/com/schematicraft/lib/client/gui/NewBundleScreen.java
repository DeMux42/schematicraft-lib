package com.schematicraft.lib.client.gui;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Creates a bundle. One field, two buttons.
 *
 * On success the library is refreshed so the new bundle appears immediately, and
 * an optional callback receives the new bundle id so a caller mid-upload can
 * select it without losing their place.
 */
public class NewBundleScreen extends Screen implements SchematicraftScreen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FIELD_W = 220;

    @Nullable private final Screen parent;
    @Nullable private final Consumer<String> onCreated;

    private EditBox nameField;
    private Button createButton;
    private String statusText = "";
    private int statusColor = GuiColors.TEXT_DIM;
    private boolean submitting = false;

    public NewBundleScreen(@Nullable Screen parent, @Nullable Consumer<String> onCreated) {
        super(Component.literal("New Bundle"));
        this.parent = parent;
        this.onCreated = onCreated;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int y = this.height / 2 - 20;

        nameField = new EditBox(this.font, centerX - FIELD_W / 2, y, FIELD_W, 18,
                Component.literal(""));
        nameField.setMaxLength(100);
        nameField.setHint(Component.literal("Bundle name"));
        nameField.setCanLoseFocus(false);
        nameField.setFocused(true);
        this.addRenderableWidget(nameField);
        this.setInitialFocus(nameField);

        createButton = Button.builder(Component.literal("Create"), b -> submit())
                .bounds(centerX - FIELD_W / 2, y + 26, FIELD_W / 2 - 2, 20).build();
        this.addRenderableWidget(createButton);

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"),
                b -> close())
                .bounds(centerX + 2, y + 26, FIELD_W / 2 - 2, 20).build());
    }

    private void submit() {
        if (submitting) return;
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            statusText = "Name required";
            statusColor = GuiColors.ERROR;
            return;
        }

        submitting = true;
        createButton.active = false;
        statusText = "Creating...";
        statusColor = GuiColors.INFO;

        SchematiCraftAPIWrapper.get().createBundle(name, null)
                .thenAccept(json -> Minecraft.getInstance().execute(() -> {
                    String id = com.schematicraft.lib.core.ApiJsonParser.parseBundleId(json);
                    LOGGER.info("Created bundle {} ({})", name, id);
                    // Refresh so the new bundle is present before we go back.
                    SchematiCraftAPIWrapper.get().refreshLibrary()
                            .thenRun(() -> Minecraft.getInstance().execute(() -> {
                                if (onCreated != null && id != null) onCreated.accept(id);
                                close();
                            }));
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        submitting = false;
                        createButton.active = true;
                        statusText = "Failed: " + SchematiCraftAPIWrapper.rootMessage(ex);
                        statusColor = GuiColors.ERROR;
                    });
                    return null;
                });
    }

    private void close() {
        Minecraft.getInstance().setScreen(parent != null ? parent : new LibraryScreen());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);

        graphics.drawCenteredString(this.font, "New bundle",
                this.width / 2, this.height / 2 - 44, GuiColors.SELECTED);
        graphics.drawCenteredString(this.font, "Bundles group schematics in your library",
                this.width / 2, this.height / 2 - 32, GuiColors.TEXT_DIM);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (!statusText.isEmpty()) {
            graphics.drawCenteredString(this.font, statusText,
                    this.width / 2, this.height / 2 + 54, statusColor);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Skip default
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        // Everything else goes to the field, which also stops game keybinds firing.
        if (nameField != null) {
            return nameField.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (nameField != null) return nameField.charTyped(codePoint, modifiers);
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
