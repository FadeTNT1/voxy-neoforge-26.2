# Voxy NeoForge 26.2

Unofficial native NeoForge port of [MCRcortex's Voxy](https://github.com/MCRcortex/voxy), based on upstream `dev` commit `534d58ec8b4aa412ef314b884295552c69d480a6` (Voxy 0.2.20-beta). All original Voxy credit belongs to MCRcortex. See [LICENSE.md](LICENSE.md); upstream remains All Rights Reserved.

Voxy renders distant terrain using levels of detail. This port uses NeoForge entrypoints, events, configuration screens, commands, and access transformers.

## Requirements

| Component | Pinned version |
| --- | --- |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.88 |
| Java | 25 |
| Sodium for NeoForge | `mc26.2-0.9.2-neoforge` (mod version `0.9.2+mc26.2`) |
| Optional Iris | `1.11.4+26.2-neoforge` |
| Optional Lithium | `mc26.2-0.25.3-neoforge` |
| Optional Vivecraft | `26.2-1.3.15-neoforge` |

Download loader-matching dependencies from their official [Sodium](https://modrinth.com/mod/sodium), [Iris](https://modrinth.com/mod/iris), [Lithium](https://modrinth.com/mod/lithium), and [Vivecraft](https://modrinth.com/mod/vivecraft) releases. Forgified Fabric API and Sodium Options API are no longer required.

OpenGL is required for Voxy's renderer. The Vulkan backend leaves Voxy rendering disabled. Flashback, Nvidium, Mod Menu, and the Fabric-specific Chunky integration are excluded from this build. World, ZIP, and DH import commands remain available.

## Build

Use Java 25 and Python 3.11 or newer:

```powershell
.\gradlew.bat build
```

On systems where Gradle's native file watcher hangs:

```powershell
.\gradlew.bat build --no-watch-fs -Dorg.gradle.native=false --console=plain
```

Output: `build/libs/voxy-0.2.20-neoforge.1.jar`. Build checks validate packaged dependencies, native libraries, metadata, and mixin registration, and run storage/compression/config/import/mapping failure regressions.

## Development and validation

```powershell
.\gradlew.bat runClient
.\gradlew.bat runClient -PwithIntegrations
```

The integration run adds Iris and Lithium. Access Voxy settings through NeoForge's Mods configuration screen. Legacy JSON settings remain readable, including environmental fog, LOD boundary buffer, and earth curvature.

See [migration decisions](docs/PORTING_26_2.md), [data safety audit](docs/DATA_SAFETY.md), [test procedure](TESTING.md), and [development rules](CLAUDE.md). The port has passed development-world startup, rendering, persistence, and clean shutdown checks; production-world and enabled shader-pack validation remain necessary before release.
