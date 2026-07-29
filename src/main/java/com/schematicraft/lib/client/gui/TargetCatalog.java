package com.schematicraft.lib.client.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Editor-registered metadata for download destinations.
 *
 * The shared screen renders and dispatches by opaque target id. Adding an editor
 * only requires a catalog entry, load handler, limits, and optional resolver.
 */
public final class TargetCatalog {
    public record Receiver(Supplier<ItemStack> stack, String label,
                           String emptyHint) {}

    public record Entry(
            TargetDevice.Type type,
            String label,
            String itemId,
            String howToUse,
            String loadButtonText,
            String destinationHint,
            String loadedLabel,
            String downloadFormat,
            String downloadEditor,
            @Nullable Receiver receiver) {

        public Entry {
            Objects.requireNonNull(type, "type");
            requireText(label, "label");
            requireText(itemId, "itemId");
            requireText(howToUse, "howToUse");
            requireText(loadButtonText, "loadButtonText");
            requireText(destinationHint, "destinationHint");
            requireText(loadedLabel, "loadedLabel");
            requireText(downloadFormat, "downloadFormat");
            requireText(downloadEditor, "downloadEditor");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }

        public Entry(TargetDevice.Type type, String label, String itemId,
                     String howToUse, String loadButtonText,
                     String destinationHint, String loadedLabel,
                     String downloadFormat, String downloadEditor) {
            this(type, label, itemId, howToUse, loadButtonText,
                    destinationHint, loadedLabel, downloadFormat,
                    downloadEditor, null);
        }

        public ItemStack icon() {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            if (id == null) return ItemStack.EMPTY;
            Item item = BuiltInRegistries.ITEM.get(id);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }

        public boolean isInstalled() { return !icon().isEmpty(); }
        public boolean hasReceiver() { return receiver != null; }

        public ItemStack receiverStack() {
            if (receiver == null) return ItemStack.EMPTY;
            try {
                ItemStack stack = receiver.stack().get();
                return stack == null ? ItemStack.EMPTY : stack;
            } catch (RuntimeException ignored) {
                return ItemStack.EMPTY;
            }
        }
    }

    private static final Map<TargetDevice.Type, Entry> ENTRIES =
            new LinkedHashMap<>();

    private TargetCatalog() {}

    public static void register(Entry entry) {
        ENTRIES.put(entry.type(), entry);
    }

    @Nullable
    public static Entry get(TargetDevice.Type type) {
        return ENTRIES.get(type);
    }

    public static List<Entry> all() {
        return new ArrayList<>(ENTRIES.values());
    }

    public static boolean isEmpty() { return ENTRIES.isEmpty(); }
}
