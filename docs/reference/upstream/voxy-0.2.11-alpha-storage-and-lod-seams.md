# Voxy 0.2.11-alpha Storage and LOD Seams — Version-Bound Upstream Reference

> **Status:** version-bound upstream reference — do not edit to describe a different upstream version
>
> doc-type: external-reference
> source-revision: 337b919 (voxy dev branch, jar sha256 63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c)
>
> **Upstream project:** Voxy (MCRcortex/voxy)
>
> **Upstream version:** 0.2.11-alpha
>
> **Exact source revision / commit:** `337b919` (`dev` branch, mirrored at `MCRcortex/voxy`)
>
> **Artifact inspected:** `python/tools/fabric-server/runtime/mods/voxy-0.2.11-alpha.jar`
>
> **Artifact SHA-256:** `63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c`
>
> **Source corpus inspected:** Voxy source inside the jar and the mirrored `external/voxy` checkout (`WorldSection.java`, `WorldEngine.java`, `Mapper.java`, `Mipper.java`, `VoxelizedSection.java`, `WorldConversionFactory.java`, `WorldUpdater.java`, `SectionStorage.java`, `SaveLoadSystem.java`, `SaveLoadSystem3.java`, `SectionSerializationStorage.java`, `StorageBackend.java` (`RocksDBStorageBackend`, `CompressionStorageAdaptor`, `ZSTDCompressor`), `ActiveSectionTracker.java`, `SectionSavingService.java`, `LoadedPositionTracker.java`, `StorageConfigUtil.java`) — audited via `docs/reference/upstream/VOXY-FORMAT.md` (283 lines) and `python/voxel_tree/voxy_format/decoder.py` exercised by `tests/test_voxy_format.py`
>
> **Symlink:** `external/voxy → /mnt/c/Users/JeffHall/git/MC/reference-code/voxy` — the `external/voxy` path resolves to the mirrored checkout at the commit above.
>
> **Research completion date:** 2026-08-16
>
> **Scope:** This document describes **only** the named Voxy upstream version. It does not describe current Voxygen implementation, architecture, or decisions.
>
> **Invalidation rule:** A newer Voxy upstream version (different jar SHA or commit) requires re-verification against its own jar/source. Do not silently edit this file to describe a different Voxy version — create a separately versioned artifact.

---

## 1. Section geometry and Levels

| concept | value | source |
|---|---|---|
| voxels per `WorldSection` in-memory | `32 × 32 × 32 = 32,768` (`long[32768]`) | `WorldSection.java` |
| in-memory index | `(y << 10) \| (z << 5) \| x` | `WorldSection.java:getIndex(int x,int y,int z)` (`M=31`) |
| `VoxelizedSection` full pyramid | `long[4681] = 4096 + 512 + 64 + 8 + 1` (offsets 0, 4096, 4608, 4672, 4680) | `VoxelizedSection.java` / `VOXY-FORMAT.md §4` |
| `VoxelizedSection` L0 index | `(y << 8) \| (z << 4) \| x` | `VoxyWorldBinding.java` (L0 `VoxelizedSection` indexing) |
| LOD `lvl` range | `0..4` supported, `MAX_LOD_LAYER=4`, `PosFormatVersion=1` | `WorldEngine.java` |
| voxel size at `lvl` | `1 << lvl` blocks per voxel | `WorldEngine` / `VOXY-FORMAT.md §1` |
| world footprint at `lvl` | `32 << lvl` blocks per axis (`32 / 64 / 128 / 256 / 512` for L0..L4) | derived from `32 × 2^lvl` |
| pooling | `ARRAY_REUSE_CACHE_SIZE=400` pooled `long[32768]` (~50 MiB reused) | `WorldSection.java` |
| vanilla chunk section vs Voxy | `16³` vs `32³`; two chunk sections per axis fit in one L0 `WorldSection` (2×2×2 = 8) | `VOXY-FORMAT.md §1` |

## 2. Section coordinate / key encoding (64-bit)

From `WorldEngine.java:getWorldSectionId(int lvl, int x, int y, int z)`:

```java
return ((long)lvl << 60)
     | ((long)(y & 0xFF)         << 52)
     | ((long)(z & ((1<<24)-1))  << 28)
     | ((long)(x & ((1<<24)-1))  << 4);
// bits 3..0 spare / unused
```

| field | bits | width | signed range | decoder |
|---|---|---|---|---|
| `lvl` | 63..60 | 4 | 0..15 | `(id>>60)&0xF` |
| `y` | 59..52 | 8 | -128..127 | `(int)((id<<4)>>56)` (sign-extend 8-bit) |
| `z` | 51..28 | 24 | -8M..8M | `(int)((id<<12)>>40)` (sign-extend 24-bit) |
| `x` | 27..4 | 24 | -8M..8M | `(int)((id<<36)>>40)` (sign-extend 24-bit) |
| spare | 3..0 | 4 | — | unused |

Helpers are `WorldEngine.getLevel/getX/getY/getZ(long)`. Python reference `voxel_tree.voxy_format.make_key/decode_key` mirrors this with explicit mask + `_sign_extend`, tested for signed 24-bit X/Z edges `-8388608..8388607` and 8-bit Y `-128..127`.

## 3. Per-voxel packed `long` representation

From `Mapper.java` constants and `composeMappingId()`:

```
bit 63..56 (8 bits): light — packed as (block << 4 | sky), each 4 bits
                      high nibble = block light, low nibble = sky light
                      (encoders: VoxelIngestService.java:71 sky|(block<<4); Mipper.java blockLight & 0xF0 vs skyLight & 0x0F)
bit 55..47 (9 bits): biome ID — Voxy internal biome index (up to 512)
bit 46..27 (20 bits): block state ID — Voxy internal mapped ID (not MC registry ID); 20 bits ⇒ 1M ids
bit 26..0  (27 bits): unused (zero in practice)
```

* `AIR = 0` (entire long is 0 ⇔ block bits 27..46 are 0)
* `Mapper.isAir(id)` checks whether block-state bits are zero
* Light nibble ownership is asymmetric: `skyLight` is low nibble `0x0F`, `blockLight` is high nibble `0xF0` (tested `sky=2, block=13 → light=0xD2` in `test_voxy_format.py`)

## 4. Mapper — per-world unstable block/biome identity

| property | fact | source |
|---|---|---|
| block ID assignment | lazy sequential `1..` via `Mapper.registerNewBlockState(BlockState)` on first encounter | `Mapper.java` |
| biome ID assignment | lazy sequential `1..` via `Mapper.registerNewBiome(Biome)` | `Mapper.java` |
| persistence | `storage.putIdMapping()` / `storage.getIdMappingsData()` — separate RocksDB keys with prefix | `Mapper.java` / `SectionStorage` |
| key encoding for persisted mapping | `(type << 30) \| id` where `type 1=block, 2=biome` | `Mapper.java` |
| string form | `BlockState.toString()` full property string (e.g. `minecraft:grass_block[snowy=false]`) | `Mapper.java` |
| maps | `ConcurrentHashMap<BlockState,Integer> block2stateEntry` + `ObjectArrayList<BlockState> blockId2stateEntry` + mirrored biome maps + `ReentrantLock` per table | `Mapper.java` |
| stability | stable **intra-world**, **different between worlds** — no canonical cross-world mapping | audited fact |

## 5. VoxelizedSection — ingestion-only pyramid

* `VoxelizedSection` holds the 5-level mip pyramid derived from a single vanilla `16³` chunk section:

| level | size | offset into flat `long[4681]` | formula |
|---|---|---|---|
| 0 | 16³=4096 | 0 | `0` |
| 1 | 8³=512 | 4096 | `1<<12` |
| 2 | 4³=64 | 4608 | `(1<<12)\|(1<<9)` |
| 3 | 2³=8 | 4672 | `(1<<12)\|(1<<9)\|(1<<6)` |
| 4 | 1³=1 | 4680 | `(1<<12)\|(1<<9)\|(1<<6)\|(1<<3)` |

* Built by `WorldConversionFactory.convert()` (fills L0) then `mipSection()` (fills 1..4). Passed to `WorldUpdater.insertUpdate()` which scatters into persistent `WorldSection` (32³) array — never persisted itself.
* Upstream also supports direct higher-Level `WorldSection` creation without `mipSection()`: a `WorldSection` can be constructed at any `lvl` using the same per-voxel `long` packing (§3), `nonEmptyChildren` bits in ancestors set via `propagateChildExistence` to reflect populated octants, and renderer traversal from the first non-empty child until leaf or missing. In this path `mipSection()` is only for ingesting full-resolution vanilla chunk sections (see `VOXY-FORMAT.md §4.1`).

## 6. Mip / downsampling (`Mipper.java`)

* Algorithm for each `2×2×2` block of 8 children `I000 … I111`:

  1. Filter out air voxels.
  2. Among non-air, `score = (blockOpacity << 4) | cornerPriority` where `cornerPriority: I111=7 > I110=6 > I011=5 > I010=4 > I101=3 > I100=2 > I001=1 > I000=0`
  3. Return highest-score voxel (max opacity, tie-break by corner).
  4. If all 8 are air: `skyLight = ceil(mean of 8 skyLights)`, `blockLight = floor(mean of 8 blockLights)`, return `I111` with averaged light (still air).

* Consequence: opaque blocks win over transparent blocks under this rule.

## 7. `nonEmptyChildren` and octant geometry

* `nonEmptyChildren` is an 8-bit mask, one bit per octant:

```java
int octantIndex(int lx,int ly,int lz) { return (lx & 1) | ((lz & 1) << 1) | ((ly & 1) << 2); }
// I000=0 … I111=7 as above Mipper cornerPriority
int childX = (parentX << 1) + (octant & 1);
int childZ = (parentZ << 1) + ((octant >> 1) & 1);
int childY = (parentY << 1) + ((octant >> 2) & 1);
```

* `isRegionFullyPopulated` checks `nonEmptyChildren == 0xFF`; save-queue backpressure checks `saveQueueDepth()` on `SectionSavingService`.
* `WorldSection` atomic state (`atomicState`, `VarHandle ATOMIC_STATE_HANDLE`): bit0 = loaded, bits1.. = refCount×2; operations `tryAcquire/acquire/release/trySetFreed/primeForReuse`.

## 8. WorldEngine and section lifecycle

```
WorldEngine {
  MAX_LOD_LAYER=4; PosFormatVersion=1;
  SectionStorage storage; Mapper mapper; ActiveSectionTracker sectionTracker;
  acquire(int lvl,int x,int y,int z) / acquire(long pos)
  getWorldSectionId(lvl,x,y,z) // §2
}
```

* Sections are reference-counted, demand-loaded via `ActiveSectionTracker` → `SectionSavingService` save queue on eviction.
* `LoadedPositionTracker` tracks client render cull positions separately from storage lifecycle.

## 9. ActiveSectionTracker and save queue

| setting | value | source |
|---|---|---|
| default MRU cache size | 1024 entries | `ActiveSectionTracker` (ctor arg `6, loadSection, cacheSize`) |
| ≥4 GiB heap threshold | 2048 entries (enabled if `Runtime.maxMemory() ≥ (4 << 30) - (200 << 20)`) | `WorldEngine.java` constructor |
| eviction target | `SectionSavingService` save queue (`saveQueueDepth()`) | `WorldEngine` / `ActiveSectionTracker` |
| tracker type | MRU (most-recently-used) eviction | `ActiveSectionTracker.java` |

 

## 10. Default storage backend — RocksDB + ZSTD

From `StorageConfigUtil.createDefaultSerializer()`:

```java
var baseDB       = new RocksDBStorageBackend.Config();
var compressor   = new ZSTDCompressor.Config(); // compressionLevel = 1
var compression  = new CompressionStorageAdaptor.Config(); // wraps baseDB with ZSTD
// then SectionSerializationStorage wraps compression
```

* Default shipped backend is **RocksDB + ZSTD level 1 compression**. Alternatives exist in `StorageBackend` community (`LMDBStorageBackend`, `RedisStorageBackend`, in-memory) but RocksDB is default unless `config.json` is edited.
* ID mapping table shares the same RocksDB store under a separate key prefix via `putIdMapping/getIdMappingsData`.

## 11. Serialization layout and ordering (`SaveLoadSystem3`)

* From `me.cortex.voxy.common.world.SaveLoadSystem3`. Each section is serialized **after ZSTD compression** as **little-endian**:

| offset | size | field | notes |
|---|---|---|---|
| 0 | 8 | `key` | section's 64-bit position key (§2) — LE `long` |
| 8 | 8 | `metadata` | LE `long`; **low 2 bytes = palette size `lutLen` (≤32768)**; next byte (bits 16..23) = `nonEmptyChildren` (1 byte) |
| 16 | `32768 × 2 = 65,536` | `indices` | 16-bit LE palette indices into LUT, in **YZX linear order** `y<<10|z<<5|x` (§1) |
| 65,552 | `lutLen × 8` | `palette` | LE int64 palette entries — full packed voxel `long`s (§3) |

* **Spatial ordering on disk is YZX linear** `(y<<10)|(z<<5)|x` — `SaveLoadSystem3` loops over `section.data` (YZX) and writes indices linearly; `deserialize` reads linearly back into `section.data`. The Morton helpers `lin2z`/`z2lin` (`Integer.expand` masks `0b100100…` / `compress`) are defined in the same class but **not used** for this serialization path — they remain as valid bijection utilities and are tested for full-domain `32³` inverse (`z2lin(lin2z(idx))==idx`).
* `SaveLoadSystem` (older) is **big-endian** — distinct from `SaveLoadSystem3` LE. Confusing them swaps bytes.
* Python reference decoder: `voxel_tree.voxy_format.{make_key,decode_key,encode_voxel,decode_voxel,yzx_index,lin2z,z2lin}` — single source of truth, exercised by `voxel_tree/tests/test_voxy_format.py` (signed 24/8-bit edge cases, asymmetric nibbles `sky=2:block=13 → light 0xD2`, Morton full-domain bijection, sentinel `x+100y+10000z` XYZ↔YZX↔Morton round-trip, `reshape(32,32,32)` YZX semantics).

---
*This document is the version-pinned record for Voxy 0.2.11-alpha storage and LOD seams as audited from jar `63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c` / commit `337b919`. Newer Voxy versions require a separately versioned document.*
