# Minecraft 26.2 / NeoForge migration

## Reference baseline

This port replaces the old 1.21.1 core/render assets with the complete official 26.2 `dev` implementation, pinned to [534d58ec8b4aa412ef314b884295552c69d480a6](https://github.com/MCRcortex/voxy/tree/534d58ec8b4aa412ef314b884295552c69d480a6) (0.2.20-beta). The separately inspected `2622` branch also targets Minecraft 26.2; it is not a Minecraft 26.2.2 release. Upstream 26.3 changes were excluded.

Migration references: [NeoForge 26.1 release/migration guidance](https://neoforged.net/news/26.1release/), [26.1 migration primer](https://github.com/neoforged/.github/blob/main/primers/26.1/index.md), [21.6 rendering/ValueInput guidance](https://neoforged.net/news/21.6release/), [ModDevGradle configuration and NeoForge JUnit support](https://github.com/neoforged/ModDevGradle). Final signatures were checked against generated NeoForge 26.2 sources and current integration implementation classes, rather than relying only on general primers.

## Adapted components

| Area | Migration |
| --- | --- |
| Toolchain | Java 21 -> 25; MDG 2.0.148; Gradle 9.2.1; NeoForge 26.2.0.88; remove old Parchment/remapping setup |
| Loader | Native common/client `@Mod` entrypoints; FML dist handling and loader metadata; no Fabric API bridge |
| Lifecycle | NeoForge client command events, debug registration, native configuration screen, session mixins |
| Rendering | Upstream 26.2 extraction/render pipeline, software model bakery, revised Sodium hooks, Iris hooks, visibility/SSAO/bounds/shader assets |
| NeoForge models | Contextual `BlockStateModel.collectParts` receives block view, position, state, random, and model data; fluid view retained |
| Access | Translate required access wideners into validated access transformers, including the `PalettedContainer.Data` record/constructor |
| Dependency metadata | Exact Minecraft 26.2 and complete Sodium `0.9.2+mc26.2` constraint fixes the loader error shown by the user |
| Sodium packaging | Runtime NeoForge distribution plus compile-only `net.caffeinemc:sodium-neoforge-mod:0.9.2+mc26.2` implementation |
| Native libraries | Match Minecraft LWJGL 3.4.1; jar-in-jar LMDB/ZSTD and database/compression dependencies; preserve Windows x64/Linux x64/Linux arm64 native paths without extra JPMS descriptors |
| Config discovery | Scan FML mod JAR contents; verified 13 storage/compressor types register in the game |
| Imports | Adapt commands and MC26.2 NBT/chunk APIs; keep source region/DH access read-only; add malformed-input checks |

## Retained behavior

The upstream algorithms, voxel ID packing (20-bit block / 9-bit biome), `SaveLoadSystem3` format/layout, database backends, hierarchy, and renderer architecture remain the upstream 26.2 implementation except for documented safety fixes. The pre-port active serializer already used `SaveLoadSystem3` with the same section layout and packed IDs; unused old serializer classes were removed with the upstream refresh.

The native port's environmental fog compatibility, LOD boundary buffer, earth curvature, and configuration UX were adapted to the new renderer instead of discarded. Native TOML and legacy JSON synchronize after renderer availability. F3 integration is restored.

Storage descriptor version 1 remains in use. The new root `disabled` control defaults to false when absent from an original 1.21.1 descriptor; existing valid descriptors are not rewritten. Backend-selection fields remain mandatory. Original mapping bytes and numeric IDs are retained when registry entries cannot resolve.

Flashback, Nvidium, Mod Menu, and Fabric Chunky integration have no implemented native integration in this port and are excluded. Iris/Lithium hooks compile and load; Vivecraft hooks compile against the matching release. Vulkan cannot run Voxy's OpenGL renderer and is explicitly disabled.

## Verification scope

Automated build, access transforms, packaged-JAR checks, native database persistence/compression, and corruption regressions are specified in [TESTING.md](../TESTING.md). A development 26.2 world was created and loaded with Sodium, Iris and Lithium; Voxy shaders compiled, terrain was processed, and the world and Voxy instance shut down normally. Enabled shader-pack and VR rendering, real 1.21.1 production caches, alternate platforms, and the full intended production modpack remain separate staging checks.

The corruption audit found additional inherited defects. Those fixes and their limits are documented in [DATA_SAFETY.md](DATA_SAFETY.md).
