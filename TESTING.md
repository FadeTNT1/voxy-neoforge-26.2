# Voxy 26.2 validation

## Automated checks

Use Java 25, Python >=3.11, and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat build --no-watch-fs -Dorg.gradle.native=false --console=plain
```

`build` runs these checks:

| Check | Coverage |
| --- | --- |
| NeoForge JUnit `test` | Real Minecraft registries; original/missing/duplicate mappings preserved; failed persistence does not publish IDs; concurrent publication; mapping ID exhaustion; oversized NBT; failed parent load releases child references; malformed MCA/ZIP/DH lifecycle with real registries, source preservation, and valid import afterward |
| `configSafety` | Invalid/duplicate/unsupported/missing descriptors preserved; strict backend fields; atomic JSON replacement |
| `dataSafety` | Malformed palettes/indexes/keys/truncation; unchanged target after rejection; invalid records retained; future writes disabled; ZSTD checksum errors; legacy frames; bounded LZ4 and RocksDB/LMDB reads |
| `adaptorSafety` | Cached mappings retained; flush keeps storage open; conflicting fragment/cache mappings rejected |
| `executorSafety` | SQL/context setup failures release counters and map locks; shutdown terminates |
| `sectionFailure` | Loader failure reaches waiting threads; no leaked cache entry; retry terminates |
| `importSafety` | DH ZSTD frame above 8196 bytes; bounded XZ round trip; truncation/column/coordinate bounds; read-only source SQLite database |
| `nativeSmoke` | RocksDB/LMDB write-flush-close-reopen; ZSTD/LZ4 round trips; SQLite JNI |
| `validatePort` | MC/Sodium constraints; Java 25 bytecode; registered mixins present; no packaged orphan mixins; nested dependencies/natives; no Fabric API dependency |

Some malformed-input fixtures intentionally log errors. A successful task exit and zero JUnit failures determine the result. Native runtime checks on other operating systems still need to run there; packaging their libraries is not execution coverage.

## Development client

```powershell
.\gradlew.bat runClient
.\gradlew.bat runClient -PwithIntegrations
```

The second command includes NeoForge Iris and Lithium. Use `-PsmokeWorld=WorldName` only for an existing disposable development save; it enables Minecraft's offline quick-play mode. Runs use ignored `run/`; server runs use `run-server/`.

Create/load a disposable world, confirm a Voxy renderer is created and shaders compile, travel to ingest terrain, adjust NeoForge settings, and quit normally. Reopen that same save and confirm its Voxy cache remains usable. Check `run/logs/latest.log` for Voxy exceptions and shutdown completion. Test an enabled shader pack separately; merely having Iris installed exercises its mixins but does not validate the shader pipeline. VR rendering requires compatible hardware.

## Production staging

Use an isolated copy of the intended 26.2 modpack, Minecraft save, and entire Voxy data directory. Keep the original copy closed and unchanged. Match Sodium's complete mod version `0.9.2+mc26.2`; the bare `0.9.2` equality is incompatible with NeoForge's version comparison.

Load/reopen every relevant dimension, travel through previously cached areas, change representative modded blocks, save/quit/reopen repeatedly, and compare block/biome mapping IDs and bytes. Test the chosen import source and storage backend. An invalid descriptor, missing mapping IDs, or inconsistent fragments must stop Voxy storage without regenerating descriptors or deleting records.

Minecraft owns conversion of the Minecraft world between game versions. This port retains the existing Voxy `SaveLoadSystem3` layout but does not claim validation against a real 1.21.1 production cache or every older Voxy format. Preserve originals for rollback. Power-loss, forced-process termination, enabled shader packs, multiplayer/modpack interactions, and Redis server persistence are separate validation requirements. See [the audit](docs/DATA_SAFETY.md) for remaining limits.
