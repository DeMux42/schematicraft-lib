package com.schematicraft.lib.client.gui;

/**
 * Limits for a load target, declared by the editor integration.
 *
 * The shared library has no idea what any editor can accept, so each editor
 * registers its own numbers and the library applies them uniformly. This keeps
 * editor-specific constants out of the shared layer.
 *
 * Two independent guards, because they protect against different failures:
 *
 * Block count guards the EDITOR. It is advisory, since it depends on the server
 * having analyzed the schematic. When the count is missing, no block check runs.
 *
 * Byte size guards THIS GAME CLIENT. It is deterministic: the downloaded bytes
 * are always measurable, no metadata required. Parsing a very large template
 * means holding the raw bytes, a String copy, a JSON tree, and an SNBT tag tree
 * in memory at once, which is what actually freezes or crashes the client. The
 * byte check therefore runs before any parsing, on every load, always.
 *
 * @param soft     above this block count, warn but allow. Use the editor's own
 *                 documented limit so the user learns where support ends.
 * @param hard     above this block count, refuse before downloading. 0 disables.
 * @param maxBytes above this many downloaded bytes, refuse before parsing.
 *                 0 disables. This is the deterministic backstop.
 * @param reason   short explanation of where the limits come from
 */
public record LoadLimits(int soft, int hard, int maxBytes, String reason) {

    /**
     * No editor limits, but still a client self-protection byte cap.
     * Even file-based editors should not try to parse an unbounded payload.
     */
    public static final LoadLimits UNLIMITED =
            new LoadLimits(0, 0, 64 * 1024 * 1024, "");

    public boolean exceedsSoft(int blockCount) {
        return soft > 0 && blockCount > soft;
    }

    public boolean exceedsHard(int blockCount) {
        return hard > 0 && blockCount > hard;
    }

    public boolean exceedsBytes(int byteLength) {
        return maxBytes > 0 && byteLength > maxBytes;
    }
}
