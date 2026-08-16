# Glossary — Terrain Generation (Minecraft / Voxy / Fabric / Voxygen)

> **Scope:** Only terms that appear when you generate, store, or render terrain in this monorepo.
> Read alongside `CONTEXT.md` (canonical project language), `python/docs/VOXY-FORMAT.md` (grounded Voxy audit), and the version-bound upstream references `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md` and `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md` for detailed version-specific behavior.
> Sources are noted per entry: `minecraft-src` = decompiled `net.minecraft.*`, `voxy` = `external/voxy/src/main/java/me/cortex/voxy/**`, `fabric` = `external/fabric-api`, `project` = `java/src/main/java/com/rhythmatician/lodiffusion/**`.

> **Authority hierarchy:**
> 1. `CONTEXT.md` — authoritative project language and architectural meanings.
> 2. `GLOSSARY.md` (this file) — concise cross-system definitions and disambiguation. Must conform to `CONTEXT.md`; if there is a conflict, `CONTEXT.md` wins.
> 3. Version-bound upstream references (`docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md`, `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md`) — authoritative for detailed version-specific external behavior.
> 4. Grounding docs / external source (`python/docs/VOXY-FORMAT.md`, `external/minecraft-src`, `external/voxy`) — source corpus.

> **Status legend:** `[External]` stable external system · `[Current]` current project canonical · `[Legacy]` historical / deprecated but still referenced · `[Planned]` design not yet implemented

---

## 0. Unit Conventions — Read This First

Dimension shorthand is a common source of coordinate/layout bugs. Enforce these rules everywhere in docs and code comments:

- **Never write `WorldSection is 32^3` without specifying the unit.** Always write `32^3 voxels` for the storage grid. Always write `blocks` for world-space extents.
- **Block vs voxel vs section are different lattices.** See the hierarchy below; do not mix them without naming the lattice.

```
Minecraft
    Chunk
        vertical column, 16 x worldHeight x 16 blocks
        └── Subchunks
              16^3 blocks each (code alias: Chunk Sections / LevelChunkSection)

Voxy
    WorldSection
        32^3 voxels at every LOD
        ├── L0 voxel = 1^3 blocks   -> region =  32^3 blocks
        ├── L1 voxel = 2^3 blocks   -> region =  64^3 blocks
        ├── L2 voxel = 4^3 blocks   -> region = 128^3 blocks
        ├── L3 voxel = 8^3 blocks   -> region = 256^3 blocks
        └── L4 voxel = 16^3 blocks  -> region = 512^3 blocks
```

| Term | Grid dimensions | World-space size | Meaning |
|------|-----------------|------------------|---------|
| Minecraft chunk | 16 x worldHeight x 16 blocks | 16 x worldHeight x 16 blocks | Vertical XZ column |
| Minecraft subchunk (chunk section) | 16^3 blocks | 16^3 blocks | One 16x16x16 portion of a chunk — called **subchunk** in this repo (code: Chunk Section) |
| Voxy WorldSection | 32^3 voxels | depends on LOD | Voxy storage/rendering unit |
| Voxy WorldSection @ L0 | 32^3 voxels | 32^3 blocks | 1 voxel = 1 block |
| Voxy WorldSection @ L1 | 32^3 voxels | 64^3 blocks | 1 voxel = 2^3 blocks |
| Voxy WorldSection @ L2 | 32^3 voxels | 128^3 blocks | 1 voxel = 4^3 blocks |
| Voxy WorldSection @ L3 | 32^3 voxels | 256^3 blocks | 1 voxel = 8^3 blocks |
| Voxy WorldSection @ L4 | 32^3 voxels | 512^3 blocks | 1 voxel = 16^3 blocks |

---

## 1. Minecraft Vanilla — Chunk Lattice

### Chunk [External]

```
chunk
    ALWAYS a vertical 16x16 XZ column.
    It is not 16x16x16.
```

The canonical vertical column `16 x worldHeight x 16` blocks (XZ footprint 16x16). In 1.18+ the overworld height is -64 inclusive to 320 exclusive (i.e. -64..319 inclusive) -> 384 block Y values -> 24 vertical sections. A chunk is identified by `ChunkPos(x, z)` where `x = floor(blockX / 16)`, `z = floor(blockZ / 16)`. Persisted as `LevelChunk` (fully generated) or `ProtoChunk` (in-progress). Source: `net.minecraft.world.level.chunk.LevelChunk`, `ProtoChunk`, `net.minecraft.world.level.ChunkPos`.

### ChunkPos [External]
Immutable `record ChunkPos(int x, int z)` — XZ chunk coordinate. Encodes no Y. Helpers: `ChunkPos.containing(BlockPos)`, `ChunkPos.pack(x,z)`, region helpers `REGION_SIZE = 32` chunks. Source: `net.minecraft.world.level.ChunkPos`.

### Subchunk (Chunk Section / LevelChunkSection) [External]

```
subchunk
    A 16x16x16 block cube within a chunk.
    Code aliases: Chunk Section (Yarn), LevelChunkSection (Mojang).
```

In this repo we call the 16^3 cube a **subchunk** — your preferred vanilla term. Code aliases are **`LevelChunkSection`** (Mojang) / **`ChunkSection`** (Yarn); Bedrock/community docs also use `subchunk` for the same object. A chunk holds `Sections = worldHeight/16` sections (24 in current overworld). Each section stores block states in a `PalettedContainer<BlockState>` (palette + bit-packing) and a `PalettedContainer<Holder<Biome>>` at 4x4x4 quart resolution -> 64 biome entries per section. Source: `net.minecraft.world.level.chunk.LevelChunkSection`, `net.minecraft.world.level.chunk.PalettedContainer`.

> Do not generalize chunk composition upward: a Voxy WorldSection is not a chunk.

### SectionPos (Minecraft) [External]

```
SectionPos
    XYZ coordinate of a Minecraft subchunk (chunk section).
    Each increment corresponds to 16 blocks on that axis.
```

Immutable `record SectionPos(int x, int y, int z)` where each coordinate = `block >> 4` (section index in all axes). This *does* include Y, unlike `ChunkPos`. Static helpers: `SectionPos.blockToSectionCoord(int block)`, `SectionPos.of(BlockPos)`, `SectionPos.asLong(x,y,z)`. Used as the key for per-section data: `LevelChunkSection` array (`index = y - minSectionY`), block light, biomes-at-quart. Heightmaps are **not** per-section — they are per-chunk XZ structures (`16x16` per `ChunkPos`) stored on the chunk, not on the section. Source: `net.minecraft.core.SectionPos`.

### SectionPos (Project — Canonical) [Current]
`java/src/main/java/com/rhythmatician/lodiffusion/voxy/SectionPos.java` — Voxygen's canonical position type. Semantics: **identical** to Minecraft's `SectionPos` at L0 (`block >> 4`), used as the single source of truth for generation and writing. `CONTEXT.md` forbids calling it "chunk pos" or "WorldSection coord" — it is always `SectionPos`. Wraps `(x,y,z)` for both `VoxelVolumeWriter.writeSection` (16^3) and `writeRegion` (32^3 origin).

### BlockPos / BlockState [External]
`BlockPos(x,y,z)` = integer world block coordinate. `BlockState` = block type + properties (e.g. `minecraft:grass_block[snowy=false]`). Block states are palette-indexed inside `LevelChunkSection`; there are ~30k distinct block-state IDs in vanilla registry but only a few hundred appear in natural terrain.
### Heightmap [External]
A `16x16` **per-chunk** (XZ) array storing the highest Y for a predicate. See `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §1` for version-bound validity. In 1.21.11, `WORLD_SURFACE_WG` / `OCEAN_FLOOR_WG` (Usage WORLDGEN) become valid at `ChunkStatus.NOISE`; `WORLD_SURFACE` / `OCEAN_FLOOR` / `MOTION_BLOCKING` / `MOTION_BLOCKING_NO_LEAVES` become valid at `CARVERS`. Stored on `ChunkAccess` keyed by `ChunkPos`, not `SectionPos`. Source: `net.minecraft.world.level.levelgen.Heightmap`.

### Biome / BiomeSource / MultiNoiseBiomeSource [External]
`Biome` is a registry entry (e.g. `minecraft:plains`). Biomes in chunk sections are stored at quart resolution (4-block cells). Vanilla overworld biome placement uses `MultiNoiseBiomeSource` which evaluates 6 climate `DensityFunction`s (see Noise Router) and looks up the nearest biome via `Climate.ParameterPoint`. In Voxygen/Python the canonical set is 54 overworld biomes alphabetically mapped to IDs `0..53` (255 = unknown); see `CONTEXT.md` / `BiomeMapping.java`. Source: `net.minecraft.world.level.biome.*`.

### NoiseRouter / Noise Router [External]
`NoiseRouter` is a 15-field record on `NoiseGeneratorSettings` — see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §2` for the full field list and grouping: Climate 6 (`temperature, vegetation, continents, erosion, depth, ridges`), Density 2 (`preliminarySurfaceLevel, finalDensity` where `finalDensity > 1.5625` decides solid), Aquifer 4 (`barrier, fluidLevelFloodedness, fluidLevelSpread, lava`), Veins 3 (`veinToggle, veinRidged, veinGap`). `NoiseRouter.mapAll(Visitor)` rewrites the whole `DensityFunction` tree. Source: `net.minecraft.world.level.levelgen.NoiseRouter:17`.

### DensityFunction [External]
A functional interface `double compute(FunctionContext)` with an AST-like type hierarchy. Can be sampled directly (`DensityFunction.sample()`) or baked. In Voxygen, `WorldNoiseAccess` / legacy `NoiseTap` samples these per-chunk to obtain heightmap/biome/router channels. The cubiomes equivalent is `sampleBiomeNoise()` returning fixed-point `NP_TEMPERATURE..NP_WEIRDNESS` (divide by 10000). Source: `net.minecraft.world.level.levelgen.DensityFunction`.

### NoiseConfig / NoiseGeneratorSettings / RandomState [External]
`NoiseGeneratorSettings` is the datapack record that configures a dimension — see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §5` for the full shape (`noiseSettings, noiseRouter, surfaceRule, defaultBlock, seaLevel, aquifersEnabled, oreVeinsEnabled, useLegacyRandomSource`). `NoiseSettings` is per-dimension (Overworld `-64,384,1,2` → cell `4×8`; Nether `0,128,1,2` same; End `0,128,2,1` → `8×4` swapped) and `clampToHeightAccessor` defines the valid lattice. `RandomState` owns the seeded wiring — `PositionalRandomFactory` fork, `ConcurrentHashMap<ResourceKey<NoiseParameters>,NormalNoise>` cache, and `Visitor mapAll` that injects `NormalNoise` into the tree exactly once. Source: `net.minecraft.world.level.levelgen.NoiseGeneratorSettings:35`, `NoiseSettings.java:23`, `RandomState.java:28`.

### ChunkGenerator / NoiseBasedChunkGenerator [External]
Abstract class responsible for turning a `ChunkPos` -> `ChunkAccess`. Vanilla overworld uses `NoiseBasedChunkGenerator` which runs: aquifer -> noise routing -> surface rules -> carvers -> features/structures. Custom generators extend this; Mixins typically `@Inject` into its methods. Source: `net.minecraft.world.level.levelgen.chunk.ChunkGenerator`.

### Surface Rule / MaterialRule [External]
Datapack-driven `MaterialRule` tree (formerly surface builder) that assigns final block states to the noise-defined terrain surface (grass, dirt, sand, deepslate ...) based on depth/steepness/biome/water. Formerly `SurfaceRules`. Source: `net.minecraft.world.level.levelgen.SurfaceRules`.

### Aquifer / FluidStatus [External]
Sub-system that floods terrain below sea level and carves lava lakes. The router's `barrier` and `fluidLevel` density functions feed the aquifer sampler. In abandoned `NoiseTap` tiers this would have been `aquifer3` (`surface/flooded/lava`). Source: `net.minecraft.world.level.levelgen.Aquifer`.

### Carver (Cave / Ravine / Canyon) [External]
World-gen pass that etches caves after noise terrain. Two families: `CaveCarver` (noodle/cave) and `CanyonCarver`. Expensive to sample; Voxygen defers it (Phase-2 `cavePrior` was `[1,4,4,4]` coarse likelihood). Source: `net.minecraft.world.level.levelgen.carver.*`.

### ChunkStatus / ChunkPyramid [External]
The generation stage enum — see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §1` for ordering and validity: `EMPTY(0) → STRUCTURE_STARTS → STRUCTURE_REFERENCES → BIOMES → NOISE(4) → SURFACE(5) → CARVERS(6) → FEATURES(7) → INITIALIZE_LIGHT → LIGHT → SPAWN → FULL(11)` with `WORLDGEN_HEIGHTMAPS` valid at `NOISE` and `FINAL_HEIGHTMAPS` at `CARVERS`. `ChunkStatus` ordering governs neighbor requirements. Source: `net.minecraft.world.level.chunk.status.ChunkStatus:28`.

### LevelHeightAccessor [External]
Reports `getMinY()`, `getHeight()`, `getSectionsCount()`, `getMinSection()`. Overworld in 1.21.11: `minY=-64`, `height=384`, `sections=24`, `minSection=-4` (see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §5/§10` for per-dimension `NoiseSettings` that define the underlying cell lattice). Source: `net.minecraft.world.level.LevelHeightAccessor`.

---

## 2. Voxy — Sparse Voxel LOD Store [External]

Version audited: `reference-code/voxy` v0.2.11-alpha. See `python/docs/VOXY-FORMAT.md` for full byte-level spec.

### Voxel [External]

```
voxel
    One cell in a Voxy WorldSection.

    A voxel is not inherently one Minecraft block.

    At L0: 1 voxel represents 1x1x1 blocks.
    At L1: 1 voxel represents 2x2x2 blocks.
    At L2: 1 voxel represents 4x4x4 blocks.
    At L3: 1 voxel represents 8x8x8 blocks.
    At L4: 1 voxel represents 16x16x16 blocks.
```

Without this distinction phrases like `32^3 section` are ambiguous — always qualify `32^3 voxels` vs `32^3 blocks`.

### WorldSection [External]

```
WorldSection
    Voxy concept only.
    ALWAYS 32x32x32 voxels.
    Never call it a chunk, subchunk, or chunk section.
```

The sole persistent storage unit in Voxy — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §1` for geometry (32³ voxels, `long[32768]` YZX `(y<<10)|(z<<5)|x`, coordinates `(lvl,x,y,z)` lvl 0..4) and lifecycle. Source: `voxy/common/world/WorldSection.java`.

At L0 only, a 32^3-voxel WorldSection spans 32^3 Minecraft blocks, which spatially corresponds to 2x2x2 subchunks. At higher LODs the WorldSection still contains exactly 32^3 voxels, but represents a progressively larger block-space volume — it does not "contain" those chunk sections as stored data.

BAD: `WorldSection = 32^3`

GOOD: `WorldSection = 32^3 voxels`

GOOD: `An L2 WorldSection is 32^3 voxels representing a 128^3-block world-space region.`

### LOD / Level (Voxy) vs. Project Level [External vs Current]
- **Voxy `lvl` [External]**: `0` finest (1 voxel = 1 block, section covers 32 blocks per axis), `4` coarsest (1 voxel = 16 blocks, section covers 512 blocks per axis). In general a WorldSection at `lvl=n` covers `32 x 2^n` world blocks per axis.
- **Project `Level` [Current]** (`java/.../voxy/Level.java`): LOD refinement/scale `L0..L4`, `L0` finest. For `writeRegion`, `Level` determines the world-space scale represented by each semantic voxel (`voxelSize = 1 << level`, `regionBlocks = 32 << level`). It is never inferred from `VoxelVolume` extent. The operation determines the required extent (`writeSection` requires 16^3, `writeRegion` requires 32^3). Validated; never inferred. Level is not storage — Voxy `WorldSection` (32^3) remains a private consolidation detail, never `Level`. Source: `CONTEXT.md` + `voxy/Level.java`.

Equivalent world-space footprints (dimensional equivalence, not storage composition):

| Voxy `lvl` | Voxel size | World footprint per WorldSection | Equivalent world-space footprint |
|---|---|---|---|
| 0 | 1 block | 32^3 blocks | 32^3 blocks = 2x2x2 subchunks (spatial correspondence) |
| 1 | 2 blocks | 64^3 blocks | 64^3 blocks = 4x4x4 subchunks extent |
| 2 | 4 blocks | 128^3 blocks | 128^3 blocks = 8x8x8 subchunks extent |
| 3 | 8 blocks | 256^3 blocks | 256^3 blocks = 16x16x16 subchunks extent |
| 4 | 16 blocks | 512^3 blocks | 512^3 blocks = 32x32x32 subchunks extent |

Do not say a higher-level WorldSection "contains" thousands of chunk sections — those Minecraft sections are not stored inside the Voxy WorldSection.

### WorldSection Key (64-bit packed ID) [External]
`WorldEngine.getWorldSectionId(lvl,x,y,z)` packing — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §2` ( `((lvl&0xF)<<60)|(y&0xFF)<<52|(z&0xFFFFFF)<<28|(x&0xFFFFFF)<<4`, 4 spare bits, decoders sign-extend). Used as RocksDB key. Source: `voxy/common/world/WorldEngine.java`.

### VoxelizedSection [External]
**Ingestion-only** container (`voxy/common/voxelization/VoxelizedSection.java`) holding the 5-level mip pyramid for a single `16^3` subchunk — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §5` for layout (`long[4681]`, offsets `0/4096/4608/4672/4680`, `WorldConversionFactory.convert()` → `mipSection()` → `WorldUpdater.insertUpdate()`). Never stored on disk.

### Mapper — Block/Biome Mapping and 64-bit Voxel Encoding [External]
`voxy/common/world/other/Mapper.java` — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §3-§4` for bit layout (`63..56` light `block<<4|sky`, `55..47` biome 9b, `46..27` blockId 20b, `AIR=0` via block bits) and per-world sequential identity (persisted via `storage.putIdMapping()`, not vanilla registry ID).

### Mipper / Mip (Mipper.java) [External]
Voxy's LOD downsampler — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §6` for algorithm (opacity-biased selection, score `(blockOpacity << 4) | cornerPriority` `I111=7..I000=0`, highest wins; all-air averages light). Opaque blocks win over transparent under this rule. Source: `voxy/common/world/other/Mipper.java`.

### nonEmptyChildren / Octant Mask [External]
`byte nonEmptyChildren` on each `WorldSection` — 8-bit octant mask — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §7` for bit index `Ixyz` and geometry. `0b00000000`=empty (skipped). Source: `voxy/common/world/WorldSection.java`.

### Octant [External]
One of the 8 children of a WorldSection. For parent `(px,py,pz)` at level `L`, child `octant in 0..7` is at `childX=(px<<1)|(octant&1)`, `childY=(py<<1)|((octant>>2)&1)`, `childZ=(pz<<1)|((octant>>1)&1)` at level `L-1`. Octants are extracted as `16^3` sub-cubes of a parent `32^3`-voxel grid (then 2x upsampled for refinement model input).

### WorldEngine / ActiveSectionTracker / WorldUpdater [External]
See `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §8-§9` for lifecycle: `WorldEngine` owns `SectionStorage`/`Mapper`/`ActiveSectionTracker` (`acquire`, `markDirty`, `MAX_LOD_LAYER=4`), `ActiveSectionTracker` MRU cache (1024 default, 2048 if ≥4 GiB) → save queue, `WorldUpdater`/`SectionSavingService` flush. Source: `voxy/common/world/WorldEngine.java`.

### SectionStorage / SectionSerializationStorage / RocksDB + ZSTD [External]
`SectionStorage` pluggable backend — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §10` for default composition (`RocksDBStorageBackend` + `CompressionStorageAdaptor(ZSTD level 1)` + `SectionSerializationStorage`). Alternatives: LMDB, Redis, in-memory.

### Serialization Format and Morton (Z-curve) Order [External]
Serialized section — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §11` for layout (`SaveLoadSystem3` little-endian, YZX-linear `(y<<10)|(z<<5)|x`, metadata low 2 bytes lutLen + next byte nonEmptyChildren). `SaveLoadSystem` (older) is big-endian. Morton helpers exist but are not the storage path. Tested specs: `python/docs/VOXY-FORMAT.md`, `python/voxel_tree/voxy_format`.

---

## 3. Fabric — Mod Loader and Interop (terrain-relevant subset) [External]

### Fabric Loader [External]
The bootstrap that loads mods on vanilla. Reads `fabric.mod.json` from each jar, resolves dependencies, and invokes entrypoints. Not terrain-specific but required for every terrain mod (Voxy, Sodium compat, LODiffusion). Version pinned via `fabric-loader` in `gradle.properties`.

### fabric.mod.json [External]
Mod metadata + entrypoint manifest. For LODiffusion / Voxy:
```json
{ "schemaVersion":1, "id":"lodiffusion", "entrypoints": { "client":["com.rhythmatician.lodiffusion.LodiffusionClient"], "main":["com.rhythmatician.lodiffusion.HelloTerrainMod"] } }
```
`client` runs on `MinecraftClient`, `main`/`server` on dedicated server. `depends: { "fabricloader": ">=0.18.4", "minecraft":"1.21.11", "java":">=21" } (verified from `java/src/main/resources/fabric.mod.json` 2026-08-10)`.

### Fabric API [External] (`external/fabric-api`)
A collection of hooks/events that mods use instead of raw Mixins where possible. Terrain-adjacent modules include `fabric-api: fabric-events-lifecycle` (server tick, chunk load/unload), `fabric-rendering-v1` (render hooks). Voxygen uses Fabric API for `ClientChunkEvents`, `ServerLifecycleEvents`, and Sodium/Flashback compat shims. Fabric API itself adds no terrain generation logic — it is plumbing.

### Mixin (SpongePowered Mixin) [External]
Bytecode injection framework bundled by Fabric Loader. Terrain mods inject into `ChunkGenerator`, `ClientChunkCache`, `LevelRenderer`, `LayerLightSectionStorage`, `SodiumChunkRenderer`, etc. Voxy's mixins in `voxy/client/mixin/**` intercept chunk load/unload and light updates to maintain `WorldSection` cache coherency. Annotations: `@Mixin(TargetClass.class)`, `@Inject(method="...", at=@At("HEAD"))`, `@Overwrite`, `@Shadow`. Mixins are the reason `external/minecraft-src` exists — decompiled mapped source needed to locate injection points.

### Fabric Loom (gradle plugin loom) [External]
Gradle plugin that deobfuscates/maps Minecraft jars, remaps mod code per Yarn/Mojang mappings, and runs `genSources` to produce `external/minecraft-src`. Powers `gradlew build` and `gradlew genSources`.

---

## 4. Voxygen Project Canonicals (bridge between Minecraft and Voxy) [Current unless noted]

| Project term | What it is | Maps to | Avoid saying |
|---|---|---|---|
| **SectionPos** | `record SectionPos(int x,int y,int z)` where block>>4. Single position type for generation/writing at L0. | Minecraft `SectionPos` at L0 | chunk pos, WorldSection coord, wsX (prefer `subchunk` over `chunk section` in prose) |
| **Level** | LOD refinement/scale `L0..L4`, `L0` finest. For `writeRegion`, `Level` determines the world-space scale represented by each semantic voxel (`voxelSize = 1 << level`). It is never inferred from `VoxelVolume` extent; the operation determines the required extent. Validated; never inferred. Level is not storage — Voxy `WorldSection` (32^3 voxels) remains a private consolidation detail, never `Level`. | Voxy `lvl` numerically, but kept distinct in code | FULL32, storage level, Voxy level |
| **VoxelVolume** | Semantic dense XYZ cube of canonical `(blockId, biomeId)`, accessed through an opaque coordinate API `blockId(x,y,z)` / `biomeId(x,y,z)`. Valid operation-specific extents are currently 16 and 32. Backing storage and linearization are implementation details (primitive arrays or otherwise) and are not part of the contract. | semantic terrain volume; adapted from/to backend-specific representations (see `RealVoxyVolumeWriter` for Voxy `WorldSection.long[32768]` details) | `long[] yzx`, packed voxel, Voxy voxel, `int[]` with fixed linearization, 32768, `x+y*E+z*E*E` |
| **Canonical Block Registry** | Versioned stable mapping block identities -> stable canonical IDs (0 = air). In this repo the canonical ID **is** the stable `BlockVocabulary` canonical index (built from `block_mapping` / `config/voxy_vocab.json`); "Canonical Block Registry" is the preferred project name for that same number. There is a single ID space; do not invent a parallel "vocab index" registry. Must be proved identical Python <-> Java via explicit version/hash in contract metadata, not per-volume (will be verified; not yet enforced per-volume). | Voxy `Mapper` block table *per world* (not reused — do not conflate) | Voxy block ID, packed ID, unqualified "vocab index" (use "canonical ID" or "BlockVocabulary canonical index" if you must qualify) |
| **Canonical Biome Registry** | 54-entry alphabetically-ordered overworld biome map `0..53`, 255=unknown, shared Python+Java. Must be proved identical via version/hash in contract metadata (will be verified; not yet enforced). | Voxy `Mapper` biome table / vanilla `Biome` registry (not reused) | Voxy biome ID |
| **VoxelVolumeWriter** | Deep module seam between generation and storage with two explicit operations: `writeSection(SectionPos, VoxelVolume[16])` and `writeRegion(SectionPos origin, Level, VoxelVolume[32])`. No extent is inferred; contract violations throw `IllegalArgumentException`, binding unavailability throws unchecked `VolumeUnavailableException`. Hides storage details; `WorldSection` mapping stays private. | Storage backends (see below) | VoxySectionWriter, VoxyCompat, VoxyEngine direct |
| **HeightPlanes / HeightmapFallbackGenerator** | `[5,32,32]` tensor `(surface, ocean_floor, slope_x, slope_z, curvature)` tiled per WorldSection. Fallback synthesizes when chunk not loaded. | Minecraft `Heightmap` WORLD_SURFACE_WG etc. | heightmap 16x16 only |
| **WorldNoiseAccess / AnchorSampler** | Runtime sampler that produces `(HeightPlanes, biome[32x32], y_index, level)` per section from loaded chunks or `NoiseConfig` sampling. Successor to abandoned `NoiseTap`. | `NoiseTap` (legacy), `NoiseConfig` sampling | router6, RouterField |
| **Router6 / Noise Router (legacy) [Legacy]** | Former 6-channel conditioning `temperature, vegetation, continentalness, erosion, depth, ridges` sampled from `DensityFunction`s via `NoiseTap`. **Dropped March 2026** — redundant with biome+heightmap (see `python/docs/NOISE-DESIGN.md`). Remains in interfaces for reference. | Minecraft `NoiseRouter` 6 climate DensityFunctions | still required |

---

## 5. Voxygen Write Path — Operations and Adapters [Current]

### writeSection [Current]
`WriteOutcome writeSection(SectionPos pos, VoxelVolume volume)` on `VoxelVolumeWriter`. Writes one L0 `16^3` section. Requires `volume.extent() == 16`. Origin `pos` is the section's own coordinate. Contract violations throw `IllegalArgumentException`. Returns `WriteOutcome` (`WRITTEN` / `SKIPPED_AIR` / `SKIPPED_EXISTS`). Source: `java/.../voxy/VoxelVolumeWriter.java`.

### writeRegion [Current]
`WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume)` on `VoxelVolumeWriter`. Writes one `32^3`-voxel octree region at the given `Level`. Requires `volume.extent() == 32` and that `origin` is aligned to the region grid (`origin % level.regionSections() == 0` per axis). `Level` controls voxel scale (`1 << level` blocks per voxel), not volume size. Never inferred from the volume. Source: `java/.../voxy/VoxelVolumeWriter.java`, `Level.java#isAligned`.

### VoxelVolumeWriter (interface) [Current]
Deep seam with exactly the two operations above and no other write entry points. Implementations must not add overloads that infer `Level` from extent. Binding unavailability throws unchecked `VolumeUnavailableException` (extends `IllegalStateException`). See also `WriteOutcome` and `VolumeUnavailableException`.

### RealVoxyVolumeWriter [Current]
The production `VoxelVolumeWriter` (`java/src/main/java/com/rhythmatician/lodiffusion/voxy/RealVoxyVolumeWriter.java`) that encodes semantic `(blockId, biomeId)` via `VoxyBlockMapper` / `BlockVocabulary` and writes into Voxy's `WorldSection` store. **This is the only place where** YZX linearization `(y<<10)|(z<<5)|x`, `long[]` packing, `VarHandle`/CAS, reflection, light defaults, and `WorldSection` / `WorldEngine` mapping live. Callers behind the `VoxelVolumeWriter` interface never see these details. Obtained via `VoxyWorldBinding` when Voxy is present; otherwise the binding is unavailable and writes throw `VolumeUnavailableException`. Sources: `java/.../voxy/RealVoxyVolumeWriter.java`, `VoxyCompat.java`, `VoxyBlockMapper.java`.

### InMemoryVolumeWriter [Current]
Test/contract adapter for `VoxelVolumeWriter` (`java/.../voxy/InMemoryVolumeWriter.java`). Records semantic `WriteRecord` entries — `SectionRecord(SectionPos, VoxelVolume)` and `RegionRecord(SectionPos origin, Level, VoxelVolume)` — as opaque `VoxelVolume` snapshots keyed by position/level. Implements the intended semantic writer guards for contract testing (all-air -> `SKIPPED_AIR`, second write to same position -> `SKIPPED_EXISTS`) but **never stores or emulates Voxy packed `long[]` or `WorldSection` internals**. Deterministic and free of Minecraft/Voxy classes; can be marked unavailable to test error paths.

### VoxelPredictionDecoder [Current]
Inference-boundary module (`java/src/main/java/com/rhythmatician/lodiffusion/voxy/VoxelPredictionDecoder.java`) that decodes model outputs (logits/argmax) into a semantic `VoxelVolume`. The only place that understands model output layout. The writer never does argmax. Avoid: "writer argmax", "logits in writer".

### WriteOutcome / VolumeUnavailableException [Current]
`WriteOutcome` is `WRITTEN | SKIPPED_AIR | SKIPPED_EXISTS`. `SKIPPED_AIR` and `SKIPPED_EXISTS` are normal runtime decisions, not errors. Contract violations throw `IllegalArgumentException`; binding unavailability throws unchecked `VolumeUnavailableException` (extends `IllegalStateException`, not declared). Avoid: `SKIPPED_BOUNDS`, `SKIPPED_INVALID`, checked exception.

---

## 6. Quick Disambiguation

- **"Chunk" vs "Subchunk" vs "WorldSection"** — Chunk = XZ column (16 x worldHeight x 16 blocks); Subchunk (= Chunk Section / LevelChunkSection) = 16^3 block cube inside a chunk; WorldSection = Voxy 32^3-voxel storage tile. Say "chunk" only for the XZ column, "subchunk" for 16^3 blocks, "WorldSection" for Voxy's 32^3 voxels. At L0 only, one WorldSection spatially corresponds to 2x2x2 subchunks; at higher LODs the correspondence is dimensional equivalence, not containment.
- **"Subchunk"** — In this repo, **subchunk** is the preferred term in all prose (docs, comments, agent output) for the 16³ block cube. Code/API names remain `LevelChunkSection` (Mojang) / `ChunkSection` (Yarn).
- **"Noise Router"** — vanilla's `NoiseRouter` DensityFunction graph; not a Voxygen runtime input since router6 was removed. When docs say "noise router" they usually mean the 6 climate fields.
- **"Level" vs "LOD" vs "lvl"** — `L0` finest in Voxygen; higher number = coarser. Some renderers invert this. Voxy file uses field name `lvl`; Voxygen uses type `Level`. Always state the scale. `Level` never inferred from `VoxelVolume` extent; the operation (`writeSection` vs `writeRegion`) determines the required extent and whether a `Level` is needed.
- **"Palette" vs "Mapper" vs "Registry"** — Palette = per-section `PalettedContainer` local compression; Mapper = Voxy per-world global `long` packing; Registry = vanilla `Registries.BLOCK / BIOME` authoritative IDs; Canonical Registry = Voxygen cross-language stable IDs (same number as `BlockVocabulary` canonical index — one ID space).
- **"VoxelVolume" backing** — Do not freeze to `int[]` or to `x+y*E+z*E*E` or to YZX. The contract is an opaque XYZ coordinate API; backing and linearization are implementation details (currently primitive arrays, but not frozen).
- **YZX vs XYZ vs Morton** — Voxy in-memory order is YZX `(y<<10)|(z<<5)|x` inside `RealVoxyVolumeWriter` only; `VoxelVolume` API is XYZ; serialized order for `SaveLoadSystem3` is YZX-linear (little-endian; Morton `lin2z`/`z2lin` helpers exist but are not the storage path — see `python/docs/VOXY-FORMAT.md`). Convert only inside the writer.

---

## 7. Where to Learn More

- Version-bound upstream facts: `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md` (§1-§18) and `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md` (§1-§11)
- Chunk lattice and generation order: `external/minecraft-src/src/net/minecraft/world/level/chunk/**`, `net.minecraft.core.SectionPos`, `net.minecraft.world.level.ChunkPos`
- Noise: `net.minecraft.world.level.levelgen.NoiseRouter`, `DensityFunction`, `NoiseGeneratorSettings` + `python/docs/NOISE-DESIGN.md` (current)
- Voxy store internals: `external/voxy/src/main/java/me/cortex/voxy/common/world/WorldSection.java`, `WorldEngine.java`, `common/world/other/Mapper.java`, `Mipper.java`, `common/voxelization/VoxelizedSection.java`, `python/docs/VOXY-FORMAT.md` + `python/voxel_tree/voxy_format`
- Canonical language: `CONTEXT.md`

