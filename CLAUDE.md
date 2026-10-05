# Voxy NeoForge 26.2 development context

Target Minecraft 26.2, NeoForge 26.2.0.88, Java 25, ModDevGradle 2.0.148, and Gradle 9.2.1. Exact dependency pins are in `gradle.properties`. This is a native port of upstream Voxy 0.2.20-beta at commit `534d58ec8b4aa412ef314b884295552c69d480a6`.

## Reference-first development

Read the implementation and actual target source before changing API calls or mixin signatures. Use `build/moddev/artifacts/minecraft-patched-26.2.0.88-sources.jar`, downloaded Sodium/Iris implementation sources or bytecode, NeoForge/FML source JARs, and the pinned upstream reference in `.reference/upstream-dev/`. References and development worlds are ignored. Do not guess target signatures or mix upstream 26.3 code into this branch.

Minecraft 26.2 is unobfuscated. Avoid old Parchment/remapping assumptions. Translate Fabric lifecycle/events to native FML/NeoForge, and declare client entrypoints with `Dist.CLIENT`. Sodium's distribution contains a nested implementation JAR; its compile-only implementation artifact is separate from the runtime distribution.

## Data preservation

A malformed or unsupported persisted descriptor must never become an empty/default store automatically. Preserve stored mapping IDs and original compressed bytes when blocks/mods are missing. Persist and flush new IDs before publication. Preserve invalid section records and stop subsequent storage operations after a storage failure. Validate lengths before native memory reads or copies. Import Minecraft region files and external DH databases read-only. Existing disk formats must remain compatible unless an explicit, reviewed migration is implemented.

Use `AtomicFiles` for JSON writes. It requires a same-directory atomic replacement; do not add a truncating fallback. Configuration changes that choose a database/backend need strict validation; optional new visual/control fields require explicit compatibility handling.

## Verification

Run `gradlew build` with Java 25 and Python >=3.11. It includes NeoForge-loaded JUnit registry tests, standalone native/failure tests, access-transformer validation, and `scripts/validate_port.py` on the produced JAR. Optional-integration development runs use `-PwithIntegrations`. Use `--no-watch-fs -Dorg.gradle.native=false` on Windows when native Gradle watching stalls.

Runtime checks must load and reopen the same disposable world, exercise rendering/settings, and close normally. Never use a production world as the first migration fixture. Report enabled shader-pack, VR hardware, Redis durability, and power-loss testing separately from ordinary startup/build results. Keep [PORTING_26_2.md](docs/PORTING_26_2.md), [DATA_SAFETY.md](docs/DATA_SAFETY.md), and [TESTING.md](TESTING.md) current.

Important files: `build.gradle`, `gradle.properties`, `META-INF/neoforge.mods.toml`, `META-INF/accesstransformer.cfg`, both mixin JSON files, `Mapper`, `SaveLoadSystem3`, `SectionSerializationStorage`, and the importers. Upstream bugs affecting data safety are in scope.
