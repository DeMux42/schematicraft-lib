package com.schematicraft.lib.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One editor handoff into Schematicraft.
 *
 * A journey keeps download destination, upload source, and native return screen
 * independent. Editor integrations construct it without adding editor branches
 * to the shared library. Held-item integrations register a small resolver for
 * the shared keybind.
 */
public final class EditorJourney {
    @FunctionalInterface
    public interface HeldResolver {
        @Nullable EditorJourney resolve(Player player);
    }

    private static final List<HeldResolver> HELD_RESOLVERS =
            new CopyOnWriteArrayList<>();

    private final TargetDevice target;
    @Nullable private final UploadSource uploadSource;
    @Nullable private final Screen returnScreen;
    @Nullable private final String openingNotice;

    public EditorJourney(TargetDevice target, @Nullable UploadSource uploadSource,
                         @Nullable Screen returnScreen,
                         @Nullable String openingNotice) {
        this.target = target;
        this.uploadSource = uploadSource;
        this.returnScreen = returnScreen;
        this.openingNotice = openingNotice;
    }

    public static EditorJourney browse() {
        return new EditorJourney(TargetDevice.none(), null, null, null);
    }
    public TargetDevice target() { return target; }
    @Nullable public UploadSource uploadSource() { return uploadSource; }
    @Nullable public String openingNotice() { return openingNotice; }

    public void returnToOrigin() {
        Minecraft.getInstance().setScreen(returnScreen);
    }

    public static void registerHeldResolver(HeldResolver resolver) {
        HELD_RESOLVERS.add(resolver);
    }

    /** Resolve the first editor that recognizes the held item. */
    public static EditorJourney resolveHeld() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return browse();

        for (HeldResolver resolver : HELD_RESOLVERS) {
            try {
                EditorJourney journey = resolver.resolve(player);
                if (journey != null) return journey;
            } catch (RuntimeException ignored) {
                // A missing or changed optional editor must not break the keybind.
            }
        }
        return browse();
    }
}
