package com.schematicraft.lib.client.gui;

import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Resolves and holds the load target for the library screen.
 * Determines WHERE a schematic will be loaded when the user presses Enter.
 *
 * Resolution priority:
 * 1. Opened from a table GUI (passed in as context)
 * 2. Holding a BG2 gadget + server has mod
 * 3. Holding a Create schematic item (file-based, always works)
 * 4. Nothing relevant: disabled
 */
public class TargetDevice {

    public enum Type {
        /** BG2 Copy/Paste gadget in hand, server has mod */
        BG2_GADGET,
        /** BG2 Template Manager block (client-only, always works) */
        BG2_TEMPLATE_MANAGER,
        /** Create Schematic Table (file-based, always works) */
        CREATE_SCHEMATIC_TABLE,
        /** No valid target resolved */
        NONE
    }

    public enum Mode {
        /** Server has Schematicraft mod, direct packet loading */
        SERVER,
        /** Client-only, uses editor's native mechanisms */
        CLIENT_ONLY
    }

    private final Type type;
    private final Mode mode;
    private final String displayName;

    private TargetDevice(Type type, Mode mode, String displayName) {
        this.type = type;
        this.mode = mode;
        this.displayName = displayName;
    }

    public Type getType() { return type; }
    public Mode getMode() { return mode; }
    public String getDisplayName() { return displayName; }
    public boolean isAvailable() { return type != Type.NONE; }

    public String getLoadButtonText() {
        return switch (type) {
            case BG2_GADGET -> "Load into Gadget";
            case BG2_TEMPLATE_MANAGER -> "Load into Template";
            case CREATE_SCHEMATIC_TABLE -> "Load into Create";
            case NONE -> "No target";
        };
    }

    public String getModeLabel() {
        return switch (mode) {
            case SERVER -> "Server";
            case CLIENT_ONLY -> "Client-only";
        };
    }

    /**
     * Resolve the target device based on current context.
     *
     * @param openedFrom The context from which the screen was opened, or null if via keybind.
     */
    public static TargetDevice resolve(@Nullable OpenContext openedFrom) {
        // Priority 1: Opened from a specific table
        if (openedFrom != null) {
            return switch (openedFrom) {
                case BG2_TEMPLATE_MANAGER -> new TargetDevice(
                        Type.BG2_TEMPLATE_MANAGER, Mode.CLIENT_ONLY, "Template Manager");
                case CREATE_SCHEMATIC_TABLE -> new TargetDevice(
                        Type.CREATE_SCHEMATIC_TABLE, Mode.CLIENT_ONLY, "Schematic Table");
                case BG2_GADGET -> new TargetDevice(
                        Type.BG2_GADGET, Mode.SERVER, "Copy/Paste Gadget");
            };
        }

        // Priority 2: Check what the player is holding
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return new TargetDevice(Type.NONE, Mode.CLIENT_ONLY, "");
        }

        ItemStack mainHand = player.getMainHandItem();

        // Check for BG2 gadget in hand
        if (isBG2CopyPasteGadget(mainHand)) {
            if (ServerMode.isDirectModeAvailable()) {
                return new TargetDevice(Type.BG2_GADGET, Mode.SERVER, "Copy/Paste Gadget");
            }
            // Server doesn't have mod, can't load into gadget directly
            // Fall through to NONE
        }

        // Check for Create schematic in hand (file-based, always works)
        if (isCreateSchematicItem(mainHand)) {
            return new TargetDevice(Type.CREATE_SCHEMATIC_TABLE, Mode.CLIENT_ONLY, "Create Schematic");
        }

        return new TargetDevice(Type.NONE, Mode.CLIENT_ONLY, "");
    }

    private static boolean isBG2CopyPasteGadget(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // Check by class name to avoid hard dependency on BG2 classes in the lib
        String className = stack.getItem().getClass().getName();
        return className.contains("GadgetCopyPaste") || className.contains("GadgetCutPaste");
    }

    private static boolean isCreateSchematicItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String className = stack.getItem().getClass().getName();
        return className.contains("SchematicItem") || className.contains("SchematicAndQuillItem");
    }

    /**
     * Context passed when opening the library screen from a specific GUI.
     */
    public enum OpenContext {
        BG2_TEMPLATE_MANAGER,
        BG2_GADGET,
        CREATE_SCHEMATIC_TABLE
    }
}
