package com.schematicraft.lib.client.gui;

import net.minecraft.network.chat.Component;

/**
 * Single source of truth for the Schematicraft entry button in editor screens.
 *
 * <p>Every editor integration places this button identically so the user learns
 * one target once. The rules:
 *
 * <ul>
 *   <li><b>Horizontally centered.</b> Center is the one position that is stable
 *       across screen widths and GUI scales. It also stays clear of JEI, whose
 *       item list and exclusion zones occupy the right edge.</li>
 *   <li><b>Vertically on the three-quarter line</b>, that is halfway between the
 *       screen middle and the bottom. The editor's own panel owns the middle, so
 *       sitting below it reads as an adjacent capability rather than a hijack of
 *       the editor's primary workflow.</li>
 *   <li><b>Never overlapping the editor's centered content.</b> The
 *       three-quarter line is a target, not a guarantee. At small window sizes or
 *       high GUI scales a tall panel reaches past it, so the button is pushed
 *       below the reserved region and then clamped back on screen.</li>
 *   <li><b>Vanilla button metrics.</b> Standard 20px height rather than the
 *       cramped 16px used before, which gives a larger click target and matches
 *       what players expect a button to look like.</li>
 * </ul>
 *
 * <p>Placement is deliberately separated from behavior. Each integration supplies
 * its own action and its own tooltip text, because the guidance is screen
 * specific, but none of them choose their own coordinates or size.
 */
public final class SchematicraftButton {

    /** Standard width. Fits the configured label without truncation. */
    public static final int WIDTH = 120;

    /** Standard height. Matches vanilla button height. */
    public static final int HEIGHT = 20;

    /** Clearance between the editor's centered content and the button. */
    private static final int PANEL_GAP = 8;

    /** Clearance between the button and the bottom edge of the screen. */
    private static final int BOTTOM_MARGIN = 8;

    private SchematicraftButton() {}

    /** Horizontally centered X for the standard button width. */
    public static int centeredX(int screenWidth) {
        return (screenWidth - WIDTH) / 2;
    }

    /**
     * Standard Y for a screen with no centered content to avoid.
     * Places the button on the three-quarter line.
     */
    public static int standardY(int screenHeight) {
        return standardY(screenHeight, 0);
    }

    /**
     * Standard Y for a screen whose centered content must not be covered.
     *
     * @param screenHeight         current screen height in GUI pixels
     * @param reservedCenterHeight height of the vertically centered region to
     *                             stay clear of, such as a container panel or a
     *                             radial menu diameter. Pass 0 when nothing is
     *                             centered.
     */
    public static int standardY(int screenHeight, int reservedCenterHeight) {
        // Target: centered on the line halfway between middle and bottom.
        int target = (screenHeight * 3) / 4 - HEIGHT / 2;

        if (reservedCenterHeight > 0) {
            int reservedBottom = (screenHeight + reservedCenterHeight) / 2;
            target = Math.max(target, reservedBottom + PANEL_GAP);
        }

        // Staying on screen wins over clearing the reserved region, because a
        // button drawn off the bottom edge cannot be clicked at all.
        int lowest = screenHeight - HEIGHT - BOTTOM_MARGIN;
        return Math.max(0, Math.min(target, lowest));
    }

    /**
     * Label for the button.
     *
     * <p>The two states are intentionally distinguishable at a glance. Configured
     * shows the cloud glyph in green, meaning the library is reachable. Not
     * configured drops the glyph and uses blue, meaning this opens setup rather
     * than the library.
     */
    public static Component label(boolean configured) {
        return Component.literal(configured
                ? "\u00a7a\u2601 Schematicraft"
                : "\u00a7bSchematicraft");
    }

    /** Tooltip text for the not-configured state, shared by every screen. */
    public static Component setupTooltip() {
        return Component.literal("Connect your Schematicraft account to browse your cloud library.");
    }
}
