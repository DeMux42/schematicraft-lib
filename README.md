<a id="readme-top"></a>

<!-- Structure adapted from othneildrew/Best-README-Template, credited in Acknowledgments. -->

<div align="center">

# Schematicraft Lib

Editor-agnostic Minecraft client library for [Schematicraft](https://schematicraft.com) integrations.

[![License][license-shield]][license-url]
[![Minecraft][mc-shield]][mc-url]
[![Java][java-shield]][java-url]

[Website](https://schematicraft.com) &middot; [Report a bug][issues-url]

</div>

<details>
  <summary>Contents</summary>

- [About The Project](#about-the-project)
  - [What It Provides](#what-it-provides)
  - [Built With](#built-with)
- [Design Rules](#design-rules)
- [Getting Started](#getting-started)
- [Writing An Integration](#writing-an-integration) (usage)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)
- [Acknowledgments](#acknowledgments)

</details>

## About The Project

This is the shared foundation behind every Schematicraft editor mod. It owns everything that is not specific to a particular building tool: talking to the In-Game API, holding library state, and drawing the browse, upload, and camera screens.

It is not a standalone mod. There is no mod metadata here, and loading it on its own does nothing. It is consumed by an editor mod, which supplies the parts that know about a specific tool.

Reference consumer: [schematicraft-mod](https://github.com/DeMux42/schematicraft-mod), which integrates Building Gadgets 2 and Create.

### What It Provides

| Area                 | Responsibility                                                                               |
| -------------------- | -------------------------------------------------------------------------------------------- |
| API client wrapper   | Async calls to the In-Game API for search, library, download, upload, palettes, and feedback |
| Library state        | Cached bundles and schematics shared across screens                                          |
| Screens              | Library grid, upload, new bundle, palette picker, API key entry                              |
| Entry button         | `SchematicraftButton`, the single authority for where the button sits on an editor screen    |
| Target catalog       | Registry of load targets and upload sources, so screens never name an editor                 |
| Thumbnail cache      | Byte-bounded image cache with redirect, address, size, and dimension limits                  |
| Schematic data cache | Byte-bounded cache of downloaded files so repeat loads are instant                           |
| Camera mode          | In-game screenshot capture with overlay, debounce, and an image cap                          |
| Config               | API key and endpoint storage, with endpoint validation                                       |
| Server detection     | Whether the server also has a Schematicraft mod installed                                    |

### Built With

[![Java][java-shield]][java-url]
[![NeoForge][neoforge-shield]][neoforge-url]
[![Gradle][gradle-shield]][gradle-url]

- Java 21, set by the Gradle toolchain.
- NeoForge 21.1.77 for Minecraft 1.21.1, through ModDevGradle 2.0.141, with Parchment mappings 2024.11.17.
- Gradle 8.10.2, through the wrapper.
- Gson 2.10.1, at compile time only, because Minecraft ships it.
- The Java client from [schematicraft-api](https://github.com/DeMux42/schematicraft-api), compiled in from source.

<p align="right"><a href="#readme-top">Back to top</a></p>

## Design Rules

These are not style preferences. Breaking them breaks consumers.

**Stay editor agnostic.** Nothing here may import an editor type. No `com.direwolf20`, no `com.simibubi`. An editor mod registers its capabilities through `TargetCatalog`, and the shared screens address them through opaque handles.

**Never name an editor in shared copy.** User-facing strings are derived from what is registered, not hardcoded. A message that says "hold a gadget" is wrong here, because there may be no gadget in the build.

**A target must actually receive.** Do not present something as a load destination unless it can accept a schematic. `UploadSource.displayName()` names the device, matching the download target exactly, so the two cannot drift.

**Credentials never leave config.** The API key is read through `ModConfig`, never logged, never pre-filled into a text field, and never included in an error message.

<p align="right"><a href="#readme-top">Back to top</a></p>

## Getting Started

To get something you can run, build the editor mod. It compiles these sources as part of its own source set. This repository's own `build.gradle` only compiles the library and runs `selfCheck`, and it reads the API client from the same relative path.

`SchematiCraftAPIWrapper` imports `com.schematicraft.api.SchematiCraftAPI`, so the [API client](https://github.com/DeMux42/schematicraft-api) has to be present as well. The editor mod resolves all three at fixed relative paths, so the layout matters:

```
workspace/
  api-clients/              clone of schematicraft-api, folder must be named api-clients
  mods/
    schematicraft-lib/      this repository
    schematicraft-mod/      the editor mod that builds everything
```

```sh
mkdir -p workspace/mods
cd workspace
git clone https://github.com/DeMux42/schematicraft-api.git api-clients
cd mods
git clone https://github.com/DeMux42/schematicraft-lib.git
git clone https://github.com/DeMux42/schematicraft-mod.git
cd schematicraft-mod
./gradlew build
```

None of the source directory links are version pinned, so keep all three repositories on matching branches.

Longer term this should ship as a versioned Jar-in-Jar artifact rather than a source directory. It is a source directory today because there is one consumer.

<p align="right"><a href="#readme-top">Back to top</a></p>

## Writing An Integration

1. Depend on this library and add its source directory to your source set.
2. Register your load targets and upload sources with `TargetCatalog` during client setup.
3. Place your entry point with `SchematicraftButton` so it lands where users already expect it.
4. Implement the load handler that takes downloaded bytes and hands them to your editor.
5. Declare your editor as an optional dependency, and confirm the mod still loads and browses when it is absent.

Conversion is server side, so an integration reuses an existing format and editor pair. Adding a genuinely new format also needs parser and enum work on the backend.

<p align="right"><a href="#readme-top">Back to top</a></p>

## Roadmap

- [x] Library grid with bundle tabs, search, and a 3D preview
- [x] Upload, new bundle, palette picker and API key screens
- [x] Camera mode
- [x] Discover, a paged search of public schematics kept apart from the personal library
- [x] Load limits each editor declares, for block count and download size
- [x] Byte-bounded thumbnail and schematic caches
- [x] The game's release sent with each download, and a prompt before a download loses content
- [x] Ports to Forge 1.20.1, NeoForge 26.1 and Fabric 1.21.1, each on its own branch
- [ ] The interactive 3D preview on the NeoForge 26.1 and Fabric 1.21.1 branches, which show the static thumbnail for now
- [ ] A versioned Jar-in-Jar artifact in place of the source directory

See [open issues][issues-url] for anything reported since.

<p align="right"><a href="#readme-top">Back to top</a></p>

## Contributing

Issues and pull requests are welcome.

1. Fork the repository
2. Create a branch (`git checkout -b feature/thing`)
3. Commit your changes
4. Push and open a pull request

The [Design Rules](#design-rules) are the project rules. A change that breaks one breaks consumers.

`master` is the NeoForge 1.21.1 build. The other ports live on the `forge/1.20.1`, `neoforge/26.1` and `fabric/1.21.1` branches, so open a pull request against the branch for the loader and version you changed.

`build.gradle` registers a `selfCheck` task, which `check` and `build` also run. It runs the checks that need no game, in `src/test`, with no test framework. Run it from the workspace layout in [Getting Started](#getting-started) before you open a pull request:

```sh
./gradlew selfCheck
```

<p align="right"><a href="#readme-top">Back to top</a></p>

## License

Distributed under the GNU Lesser General Public License v3.0. See [`LICENSE`](LICENSE) for the LGPL terms and [`COPYING`](COPYING) for the GPL terms it builds on.

<p align="right"><a href="#readme-top">Back to top</a></p>

## Contact

DeMux - [@DeMux42](https://github.com/DeMux42)

Project link: [https://github.com/DeMux42/schematicraft-lib](https://github.com/DeMux42/schematicraft-lib)

<p align="right"><a href="#readme-top">Back to top</a></p>

## Acknowledgments

- [NeoForge](https://neoforged.net/) and [Parchment](https://parchmentmc.org/)
- [Best-README-Template](https://github.com/othneildrew/Best-README-Template) by othneildrew, for this README's structure.
- [Shields.io](https://shields.io) for the badges.

<p align="right"><a href="#readme-top">Back to top</a></p>

[license-shield]: https://img.shields.io/badge/license-LGPL--3.0-blue.svg
[license-url]: LICENSE
[mc-shield]: https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg
[mc-url]: https://www.minecraft.net/
[java-shield]: https://img.shields.io/badge/Java-21-red.svg
[java-url]: https://adoptium.net/
[issues-url]: https://github.com/DeMux42/schematicraft-lib/issues
[neoforge-shield]: https://img.shields.io/badge/NeoForge-21.1-orange.svg
[neoforge-url]: https://neoforged.net/
[gradle-shield]: https://img.shields.io/badge/Gradle-8.10-02303A.svg
[gradle-url]: https://gradle.org/
