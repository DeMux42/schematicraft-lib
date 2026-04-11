package com.schematicraft.lib.client.screen;

import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

public class ApiKeyScreen extends Screen {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    @Nullable
    private final Screen parent;
    private EditBox apiKeyField;
    private Button validateButton;
    private Button cancelButton;
    private String statusMessage = "";
    private boolean validating = false;

    public ApiKeyScreen(@Nullable Screen parent) {
        super(Component.literal("Schematicraft API Key"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        apiKeyField = new EditBox(this.font, centerX - 120, centerY - 20, 240, 20,
                Component.literal("API Key"));
        apiKeyField.setMaxLength(128);
        apiKeyField.setValue(ModConfig.getApiKey());
        apiKeyField.setHint(Component.literal("sk_live_..."));
        this.addRenderableWidget(apiKeyField);

        validateButton = Button.builder(
                Component.literal("Validate"),
                btn -> validateAndSave()
        ).bounds(centerX - 120, centerY + 10, 115, 20).build();
        this.addRenderableWidget(validateButton);

        cancelButton = Button.builder(
                Component.literal("Cancel"),
                btn -> onClose()
        ).bounds(centerX + 5, centerY + 10, 115, 20).build();
        this.addRenderableWidget(cancelButton);
    }

    private void validateAndSave() {
        String key = apiKeyField.getValue().trim();
        if (key.isEmpty()) {
            statusMessage = "\u00a7cPlease enter an API key";
            return;
        }
        if (!key.startsWith("sk_")) {
            statusMessage = "\u00a7cAPI key must start with sk_";
            return;
        }

        validating = true;
        statusMessage = "\u00a7eValidating...";
        validateButton.active = false;

        ModConfig.setApiKey(key);

        SchematiCraftAPIWrapper.get().getStatus().thenAccept(statusJson -> {
            Minecraft.getInstance().execute(() -> {
                validating = false;
                validateButton.active = true;
                String tier = "unknown";
                int tierIdx = statusJson.indexOf("\"tier\"");
                if (tierIdx != -1) {
                    int start = statusJson.indexOf("\"", tierIdx + 6) + 1;
                    int end = statusJson.indexOf("\"", start);
                    if (start > 0 && end > start) tier = statusJson.substring(start, end);
                }
                statusMessage = "\u00a7aConnected as " + tier + " user";
                LOGGER.info("API key validated for user tier: {}", tier);

                Minecraft.getInstance().execute(() -> {
                    if (parent != null) {
                        Minecraft.getInstance().setScreen(parent);
                    }
                });
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                validating = false;
                validateButton.active = true;
                statusMessage = "\u00a7cValidation failed: " + ex.getMessage();
                ModConfig.setApiKey("");
            });
            return null;
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, centerY - 50, 0xFFFFFF);

        graphics.drawCenteredString(this.font,
                Component.literal("Enter your API key from schematicraft.com"),
                centerX, centerY - 38, 0xAAAAAA);

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, statusMessage, centerX, centerY + 36, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
