package com.schematicraft.lib.client.gui;

/**
 * Marker for screens owned by Schematicraft.
 *
 * Editor integrations use this to tell "our GUI is open" so they can suppress
 * their editor's global keybind polling. Some editors, Building Gadgets among
 * them, read keybinds from the client tick loop without checking whether a
 * screen is open, so a keystroke typed into one of our text fields would also
 * trigger an editor action. Swallowing the key in the screen does not help,
 * because the editor never consults the screen.
 */
public interface SchematicraftScreen {
}
