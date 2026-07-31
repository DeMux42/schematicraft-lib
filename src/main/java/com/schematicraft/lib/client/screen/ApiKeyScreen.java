package com.schematicraft.lib.client.screen;

import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;

public class ApiKeyScreen extends Screen
        implements com.schematicraft.lib.client.gui.SchematicraftScreen {
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
        // Never render the stored key. It stays write-only from the UI so it
        // cannot be read off the screen, a screenshot, or a stream.
        apiKeyField.setValue("");
        apiKeyField.setFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        apiKeyField.setHint(Component.literal(
                ModConfig.hasApiKey() ? "Key saved. Enter a new key to replace it." : "sk_live_..."));
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

        // Remember the previous key so a failed attempt does not discard a
        // working credential.
        final String previousKey = ModConfig.getApiKey();
        ModConfig.setApiKey(key);
        if (!key.equals(previousKey)) {
            // Different account or credential, so cached images no longer apply.
            com.schematicraft.lib.client.ThumbnailCache.get().clear();
        }

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
                statusMessage = "\u00a7cValidation failed. Check the key and try again.";
                LOGGER.warn("API key validation failed: {}", ex.getClass().getSimpleName());
                ModConfig.setApiKey(previousKey);
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

        // Show where the key will be sent, so a redirected endpoint is visible.
        graphics.drawCenteredString(this.font,
                Component.literal("Sends to " + ModConfig.getServerHost()),
                centerX, centerY - 28, 0x888888);

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
