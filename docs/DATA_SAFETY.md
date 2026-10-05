# Voxy 26.2 data safety audit

## Scope and evidence

The audit covers persistent mapping allocation/loading, section serialization/compression, RocksDB/LMDB/Redis/memory storage boundaries, storage adaptors, JSON descriptors, region/ZIP/DH imports, failure propagation, and shutdown/reference handling. The user's Discord screenshot is a reported concern, not proof of a specific corruption mechanism or proof that Minecraft region files were damaged.

The directly relevant upstream [PR 691 report](https://github.com/MCRcortex/voxy/pull/691) describes unresolved stored block states being replaced with random blocks and resaved. Its 26.3 codec field changes must not be copied blindly into 26.2. Actual 26.2 block-state NBT still uses `Name` and `Properties`. The pinned 26.2 code contained the destructive random fallback too, so this port fixes preservation independently.

Voxy's persistent writes target its own LOD databases and settings. The audited Minecraft region importer opens files with `StandardOpenOption.READ`; the DH source database now opens read-only. Minecraft's own world-save/version conversion is outside Voxy's cache format.

## Confirmed defects and fixes

| Risk | Resulting protection | Regression coverage |
| --- | --- | --- |
| Missing/undecodable blocks became random blocks; decoded mappings automatically resaved | Deterministic display fallback; retain original compressed bytes and IDs, including duplicates; no automatic load-time mapping writes | Missing mod, invalid property, duplicate block, biome fallback, save/reopen byte equality |
| New ID published before a successful persistent write | Write and flush before publication; reject overflow and gaps; publish immutable block lookup snapshot | Injected put/flush errors and blocked concurrent writes; ID exhaustion |
| Corrupt section decode could access native memory out of bounds | Validate minimum/exact lengths, palette size, every index, and section key before mutating target data | Truncated headers/data, invalid palette/index, mismatched key; target remains unchanged |
| Failed section load deleted the stored record | Retain record and poison subsequent session storage loads/saves/mapping operations | Invalid record still exists; later writes fail |
| ZSTD errors/checksums ignored; LZ4 returned compressed byte count as decoded length | Check native status, emit/check ZSTD checksum, preserve legacy frame compatibility; safe bounded LZ4 decoder returns output size | Corrupted checksum, legacy frame, truncation, bad sizes, round trips |
| RocksDB/LMDB values could exceed native scratch capacity | Validate returned size before copy/use; reject malformed mapping key sizes; check RocksDB iterator status | Native oversized-value canaries; persistence/reopen |
| Invalid/unreadable/unsupported storage config reset to defaults | Abort and preserve descriptor; refuse guessing backend if config is missing from a nonempty directory; reject duplicate/nonnumeric/missing backend fields | Original files remain byte-identical across invalid cases |
| JSON write could truncate original before failure | Force complete same-directory temporary contents; atomic replacement; reject nonregular/symlink targets and unsupported atomic moves | Atomic helper replacement/preservation fixtures |
| Cache mapping merge lost newly allocated IDs; flush closed databases | Merge both mapping stores with conflict checks; flush both without closing | Cached IDs survive; flush remains usable; conflicts rejected |
| Fragmented storage guessed/rewrote mismatched mappings | Require all fragments to agree; preserve databases and fail on mismatch | Conflicting/missing fragment mappings rejected |
| Loader failure left waiters spinning and active entries leaked | Publish failure to waiters, remove failed holder, release array, permit bounded retry | Concurrent loader/waiter failure and count cleanup |
| Failed parent load retained child reference and could block shutdown | Release retained child in `finally`; release instance locks when storage creation/cleanup throws | Fault-injected parent load allows world free |
| Region reads assumed complete reads and trusted corrupt lengths/coordinates | Complete-read loop; bounded sector/record/source sizes and chunk Y; skip mismatched coordinates; bounded input stream | MCA/ZIP coordinate alias fixtures, source byte preservation, malformed palette rejection, and valid MCA import with loaded Minecraft registries |
| DH ZSTD arguments reversed; large input truncated; partial columns/stale scratch reused | Correct source/destination; bounded complete input/frame sizes; `readFully`; column/mapping/height checks; clear worker scratch; advance SQL result cursor | Large incompressible frame >8196 bytes, truncated frames/columns, oversized column |
| DH source database opened writable | SQLite read-only connection | UPDATE rejected; database bytes unchanged |
| Imported coordinates could alias a different packed cache key | Require canonical integer chunk coordinates inside Minecraft bounds; validate DH SQL coordinates as long before narrowing/multiplying | MCA/ZIP alias fixtures and DH overflow guards |
| Partial palettes could replace cached terrain with fallback data | Skip undecodable imported block/biome palettes; bound chunk NBT reads | Unknown block palette imports no terrain |
| Malformed imports could wait forever or leak sources/world references | Complete reads for ZIP entries; terminate by service completion; close ZIP/SQLite sources and release references on failure/cancellation | Missing DH column, malformed MCA/ZIP, successful follow-up import; no leaked refs |
| Worker setup failure leaked running counters and map locks | Context creation inside try/finally; weak-map factory locks released on failure | Injected SQL setup failure and bounded shutdown |
| XZ dictionary header accepted unlimited allocation | 256 MiB XZ decoder memory limit | Supported XZ round trip |
| Mapping NBT accepted unbounded expansion | 1 MiB NBT allocation limit; malformed data aborts without writes | Compressed oversized NBT fixture |

## Compatibility and remaining limits

The section layout and packed ID widths are unchanged. New ZSTD frames add checksums without changing the payload; existing checksum-less frames remain readable. Structurally valid corruption in a legacy checksum-less frame or raw/LZ4 payload can remain undetectable. A database/backend checksum catches only corruption within its coverage.

These protections prevent the confirmed destructive fallback/rewrite/delete paths. They do not prove zero corruption risk. Calls already executing when another thread detects a failure are not atomically cancelled. Multi-fragment replication is not a distributed transaction; a partial write causes a mismatch on reopen and an explicit stop. Unflushed queued LOD updates can be lost on disk failure, process termination, or power loss. Do not interpret a logged save failure as a successful save.

Atomic JSON replacement depends on filesystem support. Forced file contents do not provide a portable directory-fsync/power-loss guarantee. Default RocksDB/LMDB behavior was tested through orderly close/reopen; forced power-loss behavior was not. Redis `flush()` does not configure Redis server disk persistence; memory storage is intentionally volatile. Remote Redis was not exercised.

Mapping preservation does not reconstruct data that an older build already overwrote. Missing mods may display air/plains until the registry entry returns; original IDs/bytes remain available. DataFixer fallback is used only for runtime interpretation and does not rewrite original mappings; historical source data versions are still not stored per mapping.

DH import supports its existing `FullData` format version 1 and compression modes 3/4, with a 256 MiB ZSTD blob ceiling and 256 MiB XZ decoder-memory limit. It rejects invalid data, but an import may have inserted earlier valid tile batches before a later batch fails. Synthetic MCA/ZIP rejection and malformed-DH cleanup now pass end-to-end against loaded Minecraft registries; full real DH imports and more malformed archive variants still need staging fixtures. Shader packs, VR hardware, multiplayer/modpack interactions, and real legacy production caches also need staging validation.

## Deployment decision

Use [the staging procedure](../TESTING.md) on a closed copy of the intended world and Voxy data before release. Keep the original Minecraft save and entire Voxy directory for rollback. No production deployment was performed during this audit. A passing build and disposable-world smoke test establish the checked paths, not universal deployment safety.
