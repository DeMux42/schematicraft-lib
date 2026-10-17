package com.schematicraft.lib.client.screen;

import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.OrderedText;

import org.jetbrains.annotations.Nullable;

public class ApiKeyScreen extends Screen
        implements com.schematicraft.lib.client.gui.SchematicraftScreen {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    @Nullable
    private final Screen parent;
    private TextFieldWidget apiKeyField;
    private ButtonWidget validateButton;
    private ButtonWidget cancelButton;
    private String statusMessage = "";
    private boolean validating = false;

    public ApiKeyScreen(@Nullable Screen parent) {
        super(Text.literal("Schematicraft API Key"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        apiKeyField = new TextFieldWidget(this.textRenderer, centerX - 120, centerY - 20, 240, 20,
                Text.literal("API Key"));
        apiKeyField.setMaxLength(128);
        // Never render the stored key. It stays write-only from the UI so it
        // cannot be read off the screen, a screenshot, or a stream.
        apiKeyField.setText("");
        apiKeyField.setRenderTextProvider((text, offset) ->
                OrderedText.styledForwardsVisitedString("*".repeat(text.length()), Style.EMPTY));
        apiKeyField.setPlaceholder(Text.literal(
                ModConfig.hasApiKey() ? "Key saved. Enter a new key to replace it." : "sk_live_..."));
        this.addDrawableChild(apiKeyField);

        validateButton = ButtonWidget.builder(
                Text.literal("Validate"),
                btn -> validateAndSave()
        ).dimensions(centerX - 120, centerY + 10, 115, 20).build();
        this.addDrawableChild(validateButton);

        cancelButton = ButtonWidget.builder(
                Text.literal("Cancel"),
                btn -> close()
        ).dimensions(centerX + 5, centerY + 10, 115, 20).build();
        this.addDrawableChild(cancelButton);
    }

    private void validateAndSave() {
        String key = apiKeyField.getText().trim();
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
            MinecraftClient.getInstance().execute(() -> {
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

                MinecraftClient.getInstance().execute(() -> {
                    if (parent != null) {
                        MinecraftClient.getInstance().setScreen(parent);
                    }
                });
            });
        }).exceptionally(ex -> {
            MinecraftClient.getInstance().execute(() -> {
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
    public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, centerY - 50, 0xFFFFFF);

        graphics.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Enter your API key from schematicraft.com"),
                centerX, centerY - 38, 0xAAAAAA);

        // Show where the key will be sent, so a redirected endpoint is visible.
        graphics.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Sends to " + ModConfig.getServerHost()),
                centerX, centerY - 28, 0x888888);

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredTextWithShadow(this.textRenderer, statusMessage, centerX, centerY + 36, 0xFFFFFF);
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
