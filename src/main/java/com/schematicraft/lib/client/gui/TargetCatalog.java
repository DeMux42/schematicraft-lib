package com.schematicraft.lib.client.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What Schematicraft can load into, and how to show it.
 *
 * Editor integrations register an entry per target they support. The library
 * screen renders the current target and the full compatibility list from this,
 * so it never needs to know which editors exist. Adding Litematica or Axiom
 * later is one registration each.
 *
 * Items are looked up by registry id rather than by class so the shared library
 * keeps no compile-time dependency on any editor, and a missing mod simply
 * yields an empty icon instead of failing.
 */
public final class TargetCatalog {

    /**
     * A loadable target.
     *
     * @param type      which target this describes
     * @param label     short human name, e.g. "Copy/Paste Gadget"
     * @param itemId    registry id of the item to render, e.g.
     *                  "buildinggadgets2:gadget_copy_paste"
     * @param howToUse  one line telling the user how to reach this target
     */
    public record Entry(TargetDevice.Type type, String label, String itemId, String howToUse) {

        /** The icon for this target, or an empty stack when the mod is absent. */
        public ItemStack icon() {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            if (id == null) return ItemStack.EMPTY;
            Item item = BuiltInRegistries.ITEM.get(id);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }

        /**
         * True when the item exists in this game instance, which means the
         * providing mod is installed.
         */
        public boolean isInstalled() {
            return !icon().isEmpty();
        }
    }

    private static final Map<TargetDevice.Type, Entry> entries =
            new EnumMap<>(TargetDevice.Type.class);

    private TargetCatalog() {}

    /** Register a target. Called once per target during client setup. */
    public static void register(Entry entry) {
        entries.put(entry.type(), entry);
    }

    /** The entry for a target type, or null when nothing registered it. */
    public static Entry get(TargetDevice.Type type) {
        return entries.get(type);
    }

    /** All registered targets, in registration order of the enum. */
    public static List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public static boolean isEmpty() {
        return entries.isEmpty();
    }
}
