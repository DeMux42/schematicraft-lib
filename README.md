# Schematicraft Lib

Shared Minecraft client library for [Schematicraft](https://schematicraft.com) editor integrations.

Provides the common foundation used by all Schematicraft editor mods (Building Gadgets 2, Litematica, Create, etc.):

- **API Client** - HTTP client for the Schematicraft In-Game API (search, download, upload, library, feedback)
- **Library State** - Cached library data (bundles, schematics) shared across screens
- **UI Widgets** - Schematic list widget, API key entry screen, thumbnail cache
- **Camera Mode** - In-game screenshot capture with overlay, debounce, and image cap
- **Server Detection** - Detects whether the server has the Schematicraft mod installed

## For Editor Mod Developers

This library is not intended to be used standalone. It is embedded (via Jar-in-Jar) into each editor-specific mod.

If you're building a Schematicraft integration for a new editor, depend on this library and implement the editor-specific load/export handlers.

## License

LGPL-3.0
