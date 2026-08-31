# Voxy On-Disk & In-Memory Format — Version-Bound Upstream Reference

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
> **Artifact inspected:** `tools/server-harness/runtime/mods/voxy-0.2.11-alpha.jar (pinned via .ci/voxy-artifact.json, reconstructed by tools/server-harness/scripts/install.sh)`
>
> **Artifact SHA-256:** `63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c`
>
> **Source corpus inspected:** Voxy source inside the jar and the mirrored `external/voxy` checkout (`WorldSection.java`, `WorldEngine.java`, `Mapper.java`, `Mipper.java`, `VoxelizedSection.java`, `WorldConversionFactory.java`, `WorldUpdater.java`) — all claims below are grounded in specific Java files (see per-section citations)
>
> **Symlink:** `external/voxy → /mnt/c/Users/JeffHall/git/MC/reference-code/voxy` — the `external/voxy` path resolves to the mirrored checkout at the commit above.
>
> **Research completion date:** 2026-08-16
>
> **Scope:** This document describes **only** the named Voxy upstream version. It does not describe current Voxygen implementation, architecture, or decisions.
>
> **Invalidation rule:** A newer Voxy upstream version (different jar SHA or commit) requires re-verification against its own jar/source. Do not silently edit this file to describe a different Voxy version — create a separately versioned artifact.

---

## 1. Section Geometry

| Concept | Value | Source |
|---|---|---|
| Voxels per section (in-memory) | `32 × 32 × 32 = 32,768` | `WorldSection.java` |
| In-memory index formula | `(y<<10)\|(z<<5)\|x` | `WorldSection.java:getIndex()` |
| Vanilla chunk section size | `16 × 16 × 16` | MC standard |
| LOD 0 WorldSection = | 32 world-blocks per axis @ 1:1 | derived |
| LOD 1 WorldSection = | 32 voxels covering 64 world-blocks per axis | derived |
| LOD _n_ WorldSection = | 32 voxels covering `32 × 2ⁿ` world-blocks per axis | derived |

Two vanilla chunk sections fit along each axis per LOD-0 WorldSection (2×2×2 = 8 sections total fill one WorldSection).

---

## 2. Section Key Encoding (64-bit)

From `WorldEngine.java`, method `getWorldSectionId(int lvl, int x, int y, int z)`:

```java
return ((long)lvl<<60)
     | ((long)(y&0xFF)<<52)
     | ((long)(z&((1<<24)-1))<<28)
     | ((long)(x&((1<<24)-1))<<4);
// NOTE: bits 3-0 are spare/unused
```

| Field | Bits | Width | Notes |
|---|---|---|---|
| `lvl` | 63–60 | 4 bits | LOD level 0–15 |
| `y` | 59–52 | 8 bits | Signed, -128 to +127 (section units) |
| `z` | 51–28 | 24 bits | Signed 24-bit (via arithmetic shift in getter) |
| `x` | 27–4 | 24 bits | Signed 24-bit (via arithmetic shift in getter) |
| spare | 3–0 | 4 bits | Unused |

Decoder helpers (also in `WorldEngine.java`):
```java
getLevel(id) = (id>>60)&0xF
getX(id) = (int)((id<<36)>>40)   // sign-extends 24-bit
getY(id) = (int)((id<<4)>>56)    // sign-extends 8-bit
getZ(id) = (int)((id<<12)>>40)   // sign-extends 24-bit
```

---

## 3. Per-Voxel `long` Encoding

From `Mapper.java` constants and `composeMappingId()`:

```
Bit 63–56  (8 bits) : light  — packed as (block<<4 | sky), each 4 bits
                      high nibble = block light, low nibble = sky light
                      (source: VoxelIngestService.java:71  sky|(block<<4),
                       Mipper.java blockLight & 0xF0 vs skyLight & 0x0F)
Bit 55–47  (9 bits) : biome ID  — Voxy's internal biome index (up to 512)
Bit 46–27  (20 bits): block state ID — Voxy's **internal** mapped ID (not MC registry ID)
Bit 26–0   (27 bits): unused / lower flags (zero in practice)
```

Special values:
- `AIR = 0` (the entire long is 0, i.e. block bits 27-46 == 0 means air)
- `Mapper.isAir(id)` checks whether block state bits are zero

**Critical:** The block state ID in bits 27–46 is **Voxy's own internal mapping**, NOT the
Minecraft registry block state ID. The mapping is session-unique and persisted per-world in
the storage backend's ID mapping table (string `BlockState.toString()` ↔ integer ID).

---

## 4. VoxelizedSection — Ingestion-Time Pyramid

`VoxelizedSection.java` is used *only during ingestion* (not the stored form). It holds the
full 5-level LOD pyramid derived from a single vanilla 16³ chunk section:

| Level | Size | Offset into flat array |
|---|---|---|
| 0 (full res) | 16³ = 4096 | 0 |
| 1 | 8³ = 512 | 4096 (`1<<12`) |
| 2 | 4³ = 64 | 4608 (`(1<<12)\|(1<<9)`) |
| 3 | 2³ = 8 | 4672 (`(1<<12)\|(1<<9)\|(1<<6)`) |
| 4 | 1³ = 1 | 4680 (`(1<<12)\|(1<<9)\|(1<<6)\|(1<<3)`) |
| **Total** | **4681 longs** | |

Built by `WorldConversionFactory.convert()` (fills level 0) then `mipSection()` (fills
levels 1–4). This structure is then passed to `WorldUpdater.insertUpdate()` which assembles
it into the persistent `WorldSection` (32³).

### 4.1 Top-down generated section writes (Sparse Octree / LODiffusion)

For auto-generated terrain (e.g. LODiffusion sparse-octree inference), the preferred
workflow is *not* to fully build an L0 `VoxelizedSection` and then call `mipSection()`.
Instead, write the model output directly to the target LOD level(s) using the same
low-level format used by Voxy's runtime writer:

- Write each Voxy `WorldSection` at the LOD level corresponding to model resolution
  (e.g. L4 output to L4 section, L3 output to L3 section, L2 → L2/16×16×16 etc.).
- Set `nonEmptyChildren` bits in parent sections (via `propagateChildExistence`) to
  reflect which octants are populated.
- Let Voxy's renderer traverse down from the first non-empty child until leaf or missing,
  delivering coarse LOD at distance and finer details as tasks refine.
- Skip `mipSection()` for generated content; `mipSection()` is reserved for ingesting
  full-resolution vanilla chunk sections (L0-first path).

This enables a distance-gated, top-down LOD policy where far columns are seeded at
L4 and only refined to L3/L2/L1/L0 as needed.

---

## 5. LOD Downsampling Algorithm (`Mipper.java`)

Voxy's mip algorithm for each 2×2×2 block of voxels is **opacity-biased selection**, NOT
majority vote or averaging:

```
For the 8 children (I000 … I111):
  1. Filter out air voxels.
  2. Among non-air, score = (blockOpacity << 4) | cornerPriority
     where cornerPriority: I111=7 > I110=6 > I011=5 > I010=4
                                    > I101=3 > I100=2 > I001=1 > I000=0
  3. Return the voxel with the highest score (max opacity, tie-break by corner).
  4. If ALL children are air:
       skyLight  = ceil(mean of 8 sky-lights)
       blockLight = floor(mean of 8 block-lights)
       return I111 with averaged light (still air).
```

**Implications for training data construction:**
- Do **not** use majority vote or probability pooling to match Voxy's LOD.
- Use per-block opacity from MC's registry + the I111 corner tie-break rule.
- The result is that opaque blocks (stone, dirt) always win over transparent ones (water, glass).

---

## 6. On-Disk Serialization Format

From `SaveLoadSystem3.java` (`me.cortex.voxy.common.world.SaveLoadSystem3`). Each section
is serialized after ZSTD compression as (little-endian):

| Field | Size | Notes |
|---|---|---|
| `key` | 8 bytes | Section's 64-bit position key (§2) — LE long |
| `metadata` | 8 bytes | LE long; low 2 bytes = palette size `lutLen` (≤ 32768), next byte = `nonEmptyChildren` |
| `indices` | `32³ × 2 = 65,536 bytes` | 16-bit LE indices into LUT, in **YZX linear order** `y<<10\|z<<5\|x` |
| `palette` | `lutLen × 8 bytes` | LE int64 palette entries (full packed voxel longs, §3) |

In the decompressed buffer layout (`SaveLoadSystem3.serialize`):
```
offset 0:       key (8 bytes LE)
offset 8:       metadata (8 bytes LE)
offset 16:      32768 × uint16 LE palette indices (YZX order)
offset 65552:   palette table (lutLen × int64 LE)
```

**Spatial ordering note:** Indices on disk are **YZX linear** (`(y<<10)|(z<<5)|x`), not
raster XYZ. `SaveLoadSystem3` loops over `section.data` (YZX) and writes indices in that
order; `deserialize` reads them back linearly into `section.data`. The Morton helpers
`lin2z`/`z2lin` are defined in the same file but are *not* used for this serialization
path — they remain as correct utilities for Morton-encoded pipelines and are tested for
bijection. When a Morton-ordered pipeline is needed, convert via `lin2z`/`z2lin`.

---

## 7. Default Storage Backend

From `StorageConfigUtil.createDefaultSerializer()`:

```java
var baseDB = new RocksDBStorageBackend.Config();
var compressor = new ZSTDCompressor.Config();   // compressionLevel = 1
var compression = new CompressionStorageAdaptor.Config();
// compression wraps baseDB with ZSTD
// then SectionSerializationStorage wraps compression
```

**Default: RocksDB + ZSTD (level 1) compression.** Alternative backends available:
LMDB, Redis, in-memory — but RocksDB is what ships by default and is used unless the
user edits `config.json`.

The ID mapping (block name ↔ Voxy integer ID) is stored as a separate RocksDB key via
`storage.putIdMapping()` / `storage.getIdMappingsData()`.

---

## 8. Block ID Mapping

The block state ID in bits 27–46 is **NOT** the Minecraft block registry ID. It is:
- Assigned lazily at first encounter (`registerNewBlockState()` on the Mapper)
- Persisted in the same RocksDB store as the section data (separate key prefix)
- Sequential starting from 1 (0 = air is implicit)
- Keyed by `BlockState.toString()` (the full property string, e.g.
  `minecraft:grass_block[snowy=false]`)
- Stable intra-world but **different between worlds** (no canonical mapping)

**For our training pipeline:** when extracting from a Voxy world, we must read the
ID mapping table first, then decode voxels. We cannot assume any mapping from our
`block_vocab.json`.

---

## 9. Python Reference Decoder (tested)

Use the tested helpers in `voxel_tree.voxy_format` — they are the single source of
truth for this document and are exercised by `voxel_tree/tests/test_voxy_format.py`.

```python
from voxel_tree.voxy_format import (
    make_key, decode_key,        # WorldEngine.getWorldSectionId family (§2)
    encode_voxel, decode_voxel,  # Mapper bit layout + sky|(block<<4) (§3)
    yzx_index, lin2z, z2lin,     # WorldSection.getIndex + SaveLoadSystem3 Morton (§6)
)

# --- Section key round-trip (correct sign-extension) ---
key = make_key(lvl=1, x=-1, y=-128, z=8388607)
lvl, x, y, z = decode_key(key)     # -> (1, -1, -128, 8388607)
# Implementation: explicit mask + _sign_extend, not np.int32((key<<... )>>...)

# --- Voxel long decoding (asymmetric nibbles) ---
v = encode_voxel(block_id=42, biome_id=7, sky_light=2, block_light=13)  # light = 0xD2
block_id, biome_id, sky, block = decode_voxel(v)
assert (sky, block) == (2, 13)   # low nibble = sky (0x2), high nibble = block (0xD)

# --- Morton helpers (YZX ↔ Morton, proved bijection over 32^3) ---
idx = yzx_index(x=1, y=2, z=3)    # (2<<10)|(3<<5)|1
assert z2lin(lin2z(idx)) == idx

# --- Parse a decompressed SaveLoadSystem3 section (little-endian, YZX) ---
import struct, numpy as np

def parse_section_save3(decompressed: bytes):
    # layout: key(LE q), metadata(LE Q), 32768*2 indices(LE u2 YZX), palette(LE i8)
    key = struct.unpack_from('<q', decompressed, 0)[0]
    metadata = struct.unpack_from('<Q', decompressed, 8)[0]
    lut_len = int(metadata & 0xFFFF)
    non_empty_children = int((metadata >> 16) & 0xFF)

    indices = np.frombuffer(decompressed, dtype='<u2', count=32768, offset=16)  # YZX
    lut = np.frombuffer(decompressed, dtype='<i8', count=lut_len, offset=16 + 32768*2)

    # YZX linear voxels (axis 0=y, 1=z, 2=x): reshape as (32,32,32) YZX
    voxels_flat = lut[indices]                     # (32768,) packed longs
    voxels_yzx = voxels_flat.reshape(32, 32, 32)    # (y,z,x) — not [x,z,y]
    # Optional Morton-ordered view for Morton pipelines:
    # morton_view[lin2z(idx)] = voxels_flat[idx]
    return decode_key(key), voxels_yzx, non_empty_children
```

All helpers above are imported from `voxel_tree.voxy_format` and are tested:

* signed 24-bit X/Z edges (`-8388608, -1, 0, 1, 8388607`), signed 8-bit Y edges
  (`-128, -1, 0, 1, 127`), and L0/L4 round-trips;
* asymmetric `sky=2, block=13` proving high/low nibble ownership;
* `lin2z`/`z2lin` full-domain inverse and uniqueness over `32^3`;
* sentinel `val = x + 100*y + 10000*z` volume proving XYZ ↔ YZX ↔ Morton round-trips
  (including `reshape(32,32,32)` YZX semantics and `lin2z`/`z2lin` bijection).

---

## 10. Corrections to Prior PROJECT-OUTLINE.md Assumptions

| Prior claim | Reality |
|---|---|
| "palette + 16-bit indices" | ✅ Correct structurally, but palette entries are **full 64-bit longs** containing block+biome+light |
| "32³ section format" | ✅ Correct |
| "RocksDB with world_sections column family" | ✅ RocksDB is correct default, but no "column family" — all in one RocksDB store with key prefixes |
| Linear voxel ordering assumed | ✅ YZX linear `(y<<10)|(z<<5)|x` on disk (SaveLoadSystem3); Morton `lin2z`/`z2lin` are valid transforms but not used for this store |
| No mention of biome per-voxel | ❌ Each voxel carries 9-bit biome ID (bits 47–55) |
| No mention of light per-voxel | ❌ Each voxel carries 8-bit light (bits 56–63: `block<<4|sky`, high/low nibbles) |
| "Block ID = Minecraft registry ID" (implied) | ❌ Voxy uses its own **internal mapped IDs**, world-specific |
| LOD downsampling = majority vote (implied) | ❌ Opacity-biased corner selection (§5) |

---

*Keep this file updated as integration work proceeds. Cross-reference with `docs/AC.md`.*
