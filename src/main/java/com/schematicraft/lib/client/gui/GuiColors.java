package com.schematicraft.lib.client.gui;

/**
 * All color constants for the Schematicraft GUI.
 * ARGB format. No magic hex values in render code.
 */
public final class GuiColors {
    private GuiColors() {}

    // Backgrounds
    public static final int SCREEN_BG = 0xD0080808;
    public static final int PANEL_BG = 0xD00A0A0A;
    public static final int BOTTOM_BAR_BG = 0xFF0D0D0D;
    public static final int STATUS_BAR_BG = 0xFF080808;

    // Borders
    public static final int BORDER_DARK = 0xFF1A1A1A;
    public static final int BORDER_SUBTLE = 0x30FFFFFF;
    public static final int BORDER_SEPARATOR = 0xFF222222;

    // Text
    public static final int TEXT_PRIMARY = 0xFFE0E0E0;
    public static final int TEXT_SECONDARY = 0xFFAAAAAA;
    public static final int TEXT_DIM = 0xFF666666;
    public static final int TEXT_HINT = 0xFF444444;
    public static final int TEXT_DISABLED = 0xFF333333;

    // Semantic colors
    public static final int SELECTED = 0xFF55FF55;
    public static final int SELECTED_BG = 0xFF0A200A;
    public static final int HOVER_BG = 0x180AFF0A;
    public static final int HOVER_TEXT = 0xFFFFFFFF;

    public static final int BUNDLE_HEADER = 0xFFAA8800;
    public static final int BUNDLE_HEADER_DIM = 0xFF554400;
    public static final int BUNDLE_TAB_ACTIVE = 0xFFFFAA00;
    public static final int BUNDLE_TAB_ACTIVE_BG = 0xFF1A1500;

    public static final int CLIPBOARD_HEADER = 0xFF55AA55;
    public static final int UPLOADED_MARK = 0xFF55AA55;

    public static final int ERROR = 0xFFFF5555;
    public static final int WARNING = 0xFFFFAA00;
    public static final int INFO = 0xFF55AAFF;
    public static final int SUCCESS = 0xFF55FF55;

    // Buttons
    public static final int BTN_BG = 0xFF222222;
    public static final int BTN_BORDER = 0xFF444444;
    public static final int BTN_TEXT = 0xFFCCCCCC;
    public static final int BTN_HOVER_BG = 0xFF333333;
    public static final int BTN_HOVER_BORDER = 0xFF666666;

    public static final int BTN_PRIMARY_BG = 0xFF1A3A1A;
    public static final int BTN_PRIMARY_BORDER = 0xFF2A5A2A;
    public static final int BTN_PRIMARY_TEXT = 0xFF55FF55;

    public static final int BTN_UPLOAD_BG = 0xFF1A1A2A;
    public static final int BTN_UPLOAD_BORDER = 0xFF2A2A4A;
    public static final int BTN_UPLOAD_TEXT = 0xFF88AAFF;

    public static final int BTN_CAMERA_BG = 0xFF2A2A1A;
    public static final int BTN_CAMERA_BORDER = 0xFF4A4A2A;
    public static final int BTN_CAMERA_TEXT = 0xFFFFCC44;

    // Tile grid
    public static final int TILE_BORDER = 0xFF1A1A22;
    public static final int TILE_BG = 0xFF111118;
    public static final int TILE_HOVER_BORDER = 0xFF1A3A1A;
    public static final int TILE_HOVER_BG = 0xFF0A1A0A;
    public static final int TILE_SELECTED_BORDER = 0xFF55FF55;
    public static final int TILE_SELECTED_BG = 0xFF0A200A;
    public static final int TILE_NAME = 0xFF888888;

    // Target device
    public static final int TARGET_SERVER = 0xFF55AA55;
    public static final int TARGET_CLIENT = 0xFFAAAA55;
    public static final int TARGET_NONE = 0xFF555555;

    // Scrollbar
    public static final int SCROLLBAR_TRACK = 0xFF0A0A0A;
    public static final int SCROLLBAR_THUMB = 0xFF333333;
    public static final int SCROLLBAR_THUMB_HOVER = 0xFF555555;
}
