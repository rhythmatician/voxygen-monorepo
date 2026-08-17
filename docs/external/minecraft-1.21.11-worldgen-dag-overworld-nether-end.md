# Minecraft 1.21.11 Worldgen DAG — Overworld / Nether / End (dimension-aware) — Version-Bound Research

> **Status:** version-bound upstream research — do not edit to describe a different upstream version.
>
> doc-type: external-reference
> source-revision: 26.1-snapshot-11 (CFR 0.152 decompiled, jar SHA-256 `556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822`; no git SHA)
>
> **Research question (GitHub `rhythmatician/voxygen-monorepo#82`, part of #22):** source-grounded ordered DAG of Minecraft worldgen for **Overworld, Nether, and End**, from seed/configuration through climate/noise, biome placement, terrain shaping, aquifers, surface rules, carvers, and placed/configured features (ores, vegetation), recording per stage: I/O + dimensionality, ordering/dependencies, input-completeness + spatial halo, cost, determinism/entropy, and distant-visible impact across Voxy Levels L0–L4.
>
> **This is research, not the partition.** It records *what vanilla computes*; reuse/port/learn/omit decisions belong to `worldgen-partition` (#85, gated by #84). The L0–L4 columns are observations about vanilla output, not partition decisions.
>
> **Upstream project:** Minecraft (Mojang Studios).
>
> **Upstream version:** 1.21.11 (decompiled corpus self-identifies as `26.1-snapshot-11`).
>
> **Decompiler:** CFR 0.152 (`external/minecraft-src/cfr.jar`; `Decompiled with CFR 0.152` headers throughout the inspected corpus).
>
> **Artifact hash (reproduction anchor):** `26.1-snapshot-11.jar` / `client.jar` SHA-256 = `556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822` (both jars hash identically). No git SHA is established for this upstream; re-verification must compare against the Mojang 1.21.11 / `26.1-snapshot-11` artifacts. See §0.
>
> **Source corpus inspected:** `external/minecraft-src/src/net/minecraft/world/level/` (`chunk/status/*`, `levelgen/*`, `levelgen/synth/*`, `levelgen/carver/*`, `levelgen/blending/*`, `levelgen/placement/*`, `biome/*`), `net/minecraft/core/{QuartPos,SectionPos}`, `net/minecraft/world/level/dimension/DimensionType`, `data/worldgen/{NoiseData,TerrainProvider,SurfaceRuleData}`. Line numbers are against that CFR-decompiled tree.
>
> **Cross-reference:** [`docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md`](../reference/upstream/minecraft-1.21.11-worldgen-seams.md) is the sibling *seams* reference (per-class internals: NoiseRouter, DensityFunction, NoiseChunk, RandomState, noise stack, Aquifer, Beardifier, Blender, SurfaceSystem, carvers). This document is the *DAG/ordering* view and does not restate class internals already grounded there.
>
> **Research completion date:** 2026-08-16.
>
> **Scope:** Describes **only** the named Minecraft upstream version. It does not describe current Voxygen implementation, architecture, or decisions. L0–L4 refers to Voxy LOD Levels (external, `voxy-0.2.11-alpha`) used purely as a distant-visibility yardstick.
>
> **Invalidation rule:** A newer Minecraft upstream requires re-verification against its own decompiled corpus. Do not silently edit this file to describe a different upstream — create a separately versioned artifact.

---

## 0. Reproduction & artifact hashes

The audit is reproducible from the decompiled corpus alone:

| Artifact | Location | Hash / version |
|---|---|---|
| Server/obf jar | `26.1-snapshot-11.jar` | SHA-256 `556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822` |
| Client jar | `client.jar` | SHA-256 `556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822` (identical) |
| Decompiler | `cfr.jar` | CFR 0.152; SHA-256 `F686E8F3DED377D7BC87D216A90E9E9512DF4156E75B06C655A16648AE8765B2` |
| Version manifest | `26.1-snapshot-11.json` | version id `26.1-snapshot-11`; SHA-256 `D3DE3C825F3AA4A283A2754559CDE6D76FC5AD92C0E914069EECFDB4001B5F57` |
| Decompiled tree | `src/` | derived from the jars above via CFR 0.152 |

Re-verify the anchor (PowerShell):

```powershell
Get-FileHash external/minecraft-src/26.1-snapshot-11.jar -Algorithm SHA256
Get-FileHash external/minecraft-src/cfr.jar -Algorithm SHA256
Get-FileHash external/minecraft-src/26.1-snapshot-11.json -Algorithm SHA256
```

`external/minecraft-src` is an expected **read-only** local reference target (`external/README.md`); it is not committed and must be provisioned before running the commands above. The hashes in this record were verified against the locally provisioned `reference-code/26.1-snapshot-11` artifact set on 2026-08-16. Every `external/minecraft-src/src/...:line` citation below is against the CFR-0.152 decompilation of the hashed jar, so line numbers are reproducible for anyone who regenerates the tree with the same decompiler. The two authoritative ordering sources are `ChunkStatus.java` (linear chain) and `ChunkPyramid.java` (`GENERATION_PYRAMID`, the side-input DAG with halos).

---

## 1. Two graphs, not one: ChunkStatus **linearity** vs the **side-input DAG**

Vanilla worldgen is often described as a linear pipeline. That is only half true. There are **two** distinct graphs, and the deliverable must keep them separate.

### 1.1 The linear chain — `ChunkStatus` promotion order

**Source:** `external/minecraft-src/src/net/minecraft/world/level/chunk/status/ChunkStatus.java:30-41`.

The exact status indices, parent mechanics, and heightmap sets are recorded in the [worldgen seams reference §1](../reference/upstream/minecraft-1.21.11-worldgen-seams.md#1-chunkstatus-ordering-and-heightmap-validity). This DAG uses their total order:

```mermaid
flowchart LR
  E[EMPTY] --> SS[STRUCTURE_STARTS] --> SR[STRUCTURE_REFERENCES] --> B[BIOMES] --> N[NOISE]
  N --> SF[SURFACE] --> C[CARVERS] --> F[FEATURES] --> IL[INITIALIZE_LIGHT] --> L[LIGHT] --> SP[SPAWN] --> FU[FULL]
```

**Consequence for a silhouette/horizon consumer:** `NOISE` provides the first complete base-terrain height as `WORLD_SURFACE_WG` (not `WORLD_SURFACE`, which only exists at `CARVERS`). It is not the final visible geometry: `SURFACE` can add eroded-badlands and frozen-ocean extensions, `CARVERS` subtract, and structure/feature placement during `FEATURES` can add or replace blocks.

### 1.2 The side-input DAG — `ChunkPyramid.GENERATION_PYRAMID` halos

**Source:** `external/minecraft-src/src/net/minecraft/world/level/chunk/status/ChunkPyramid.java:18` (`GENERATION_PYRAMID`) + `ChunkStep.java:26` + `ChunkDependencies.java:16-50`.

Linearity says *what order a single chunk advances*. It does **not** say *which neighbor chunks must already be at some status* before a step can run. That is the real DAG: each `ChunkStep` declares `addRequirement(status, radius)` (neighbor chunks within `radius` must be at least `status`) and `blockStateWriteRadius(r)` (how far this step writes blocks outside its own chunk).

| Step | Direct requirements (`status` @ chunk radius) | `blockStateWriteRadius` |
|---|---|---|
| `EMPTY` | — | — |
| `STRUCTURE_STARTS` | — | — |
| `STRUCTURE_REFERENCES` | `STRUCTURE_STARTS` @ 8 | — |
| `BIOMES` | `STRUCTURE_STARTS` @ 8 | — |
| `NOISE` | `STRUCTURE_STARTS` @ 8, `BIOMES` @ 1 | 0 |
| `SURFACE` | `STRUCTURE_STARTS` @ 8, `BIOMES` @ 1 | 0 |
| `CARVERS` | `STRUCTURE_STARTS` @ 8 | 0 |
| `FEATURES` | `STRUCTURE_STARTS` @ 8, `CARVERS` @ 1 | 1 |
| `INITIALIZE_LIGHT` | — | — |
| `LIGHT` | `INITIALIZE_LIGHT` @ 1 | — |
| `SPAWN` | `BIOMES` @ 1 | — |
| `FULL` | — | — |

```mermaid
flowchart TD
  SS[STRUCTURE_STARTS] -->|r8| SR[STRUCTURE_REFERENCES]
  SS -->|r8| B[BIOMES]
  SS -->|r8| N[NOISE]
  B -->|r1| N
  SS -->|r8| SF[SURFACE]
  B -->|r1| SF
  SS -->|r8| C[CARVERS]
  SS -->|r8| F[FEATURES]
  C -->|r1| F
  B -->|r1| SP[SPAWN]
  IL[INITIALIZE_LIGHT] -->|r1| L[LIGHT]
  F -. writes r1 .-> F
```

Key readings that the linear view hides:

* **`STRUCTURE_STARTS` @ radius 8** is required by *everything* through `FEATURES` — the 17×17 chunk halo (`8` each side) supplies structure starts/references for terrain adaptation (`Beardifier`) and later placement. This is the widest declared read halo in the graph.
* **`NOISE`/`SURFACE` need `BIOMES` @ radius 1**: aquifer sampling and (upgrade-world) blending reach one chunk out, and biome-dependent surface needs neighbor biomes at the chunk edge.
* **`FEATURES` need `CARVERS` @ radius 1 and *write* radius 1** — the classic "feature bleed": a tree/ore placed near a chunk edge writes blocks into the neighbor, so neighbors must be carved first. This write-radius is why features cannot be trivially parallelized per chunk.
* **`NOISE`..`CARVERS` all have `blockStateWriteRadius(0)`** — terrain/surface/carving only mutate the owning chunk; only `FEATURES` writes outward.

**Distinction stated plainly:** the *promotion order* (§1.1) is a total order per chunk; the *side-input DAG* (§1.2) is a partial order over (chunk, status) pairs with explicit spatial radii. A correct distant-terrain reconstruction depends on the DAG's read halos (especially `BIOMES` @ r1 for `NOISE`), not merely on the linear order.

---

## 2. Shared side inputs: seed / dimension / config → `RandomState`

Before any per-chunk stage runs, a per-(dimension, seed) `RandomState` is built **once** and shared by every chunk. Its wiring and random-source internals are canonical in the [worldgen seams reference §§2, 6](../reference/upstream/minecraft-1.21.11-worldgen-seams.md#2-noiserouter--15-field-record); this table records only their DAG roles.

**Source:** `RandomState.java:47` (`create`), `NoiseGeneratorSettings.java:35`, `NoiseRouter.java:17`, `Climate.java:44`.

| Side input | Type / dimensionality | Role |
|---|---|---|
| world seed | `long` | seeds `PositionalRandomFactory` (Xoroshiro or Legacy per settings) |
| dimension key | `ResourceKey<NoiseGeneratorSettings>` | selects the whole config bundle (lattice, router, surface rules, flags) |
| `NoiseGeneratorSettings` | record (registry data) | `noiseSettings`, `defaultBlock/Fluid`, `noiseRouter`, `surfaceRule`, `seaLevel`, `aquifersEnabled`, `oreVeinsEnabled`, `useLegacyRandomSource` |
| `NoiseRouter` | 15 `DensityFunction` fields | downstream terrain/aquifer/vein interface |
| `Climate.Sampler` | six router climate functions | biome-source input; the End consumes only erosion after its radial test |
| noise registry | `HolderGetter<NoiseParameters>` | concrete octave tables instantiated lazily by key |

This global node is cheap and deterministic; per-chunk stages consume its already-wired functions rather than rebuilding noise graphs.

---

## 3. The per-stage master DAG (shared skeleton)

The following stages are shared across all three dimensions (which stages are *active* per dimension is in §5). "Halo" = spatial read radius the stage requires; "L0–L4" = whether the stage's contribution survives into distant-visible geometry at the coarsest Voxy Level(s). L0 voxel = 1 block; L4 voxel = 16 blocks (`voxy-0.2.11-alpha` §1).

**Generator method ordering:** structure starts/references are created first (`ChunkStatusTasks.java:49-67`), then `NoiseBasedChunkGenerator.createBiomes` (`:113`) → `fillFromNoise` (`:265`, terrain + aquifer substance + ore veins through the `NoiseChunk` block-state rule) → `buildSurface` (`:217/:226`) → `applyCarvers` (`:233`) → structure and placed-feature decoration interleaved by `GenerationStep` during `FEATURES` (`ChunkGenerator.java:257-324`). `getBaseColumn`/`iterateNoiseColumn` (`NoiseBasedChunkGenerator.java:149-213`) is the single-column base-terrain probe used by `getBaseHeight`.

| # | Stage (status) | Inputs | Outputs | Dimensionality | Depends on | Read halo | Cost | Determinism | Distant-visible L0–L4 |
|---|---|---|---|---|---|---|---|---|---|
| S0 | `RandomState` build (pre-chunk) | seed, dim key, `NoiseGeneratorSettings`, noise registry | wired `NoiseRouter`, `Climate.Sampler`, `SurfaceSystem` | global scalars → function graph | — | none | cheap, once | deterministic (seed,dim) | prerequisite for all Levels |
| S1 | Climate / base-noise fields | block/quart pos, wired router | 6 climate values + `preliminarySurfaceLevel` + `finalDensity` + aquifer/vein fields | climate 2D quart (`FlatCache(Cache2D)`); density 3D on cell lattice | S0 | own column; base3D on cell grid | climate cheap; `finalDensity` (BlendedNoise) dominates | deterministic | **L0–L4 (drives all silhouette)** |
| S2 | Biome placement (`BIOMES`) | quart position + dimension biome source; OW/Nether use six-field `Climate.Sampler`, End uses radial distance then erosion | `Holder<Biome>` per quart cell | 3D quart (x>>2,y>>2,z>>2); End classification is columnar because Y does not affect its erosion router | S1 climate subset, `STRUCTURE_STARTS`@8 | quart within chunk; `NOISE` reads `BIOMES`@1 | cheap: OW/Nether nearest-point lookup; End radial test + one erosion sample | deterministic | indirect: selects surface, carver, structure, and feature sets; visible at any Level through those consumers |
| S3 | Terrain noise fill (`NOISE`) | wired `finalDensity`, `NoiseChunk` cell grid, biomes@1 | solid/air blocks + `WORLD_SURFACE_WG`/`OCEAN_FLOOR_WG` | 3D cell lattice → trilinear to block | S1, S2, `STRUCTURE_STARTS`@8 | `BIOMES`@1 (aquifer/blend edge) | **dominant compute**: cell grid + trilinear (128× fewer samples than blocks) | deterministic | **L0–L4 — this IS the distant terrain** |
| S4 | Aquifers (inside `NOISE` fill) | `barrier/fluidLevelFloodedness/fluidLevelSpread/lava` fields, `finalDensity` | fluid vs air substance at `finalDensity≤0` | 3D grid 10×9×10 @ 16×12×16 spacing + Voronoi | S3 (same fill loop) | ±1 chunk grid | high-frequency, moderate; disabled → `createDisabled` flat picker | deterministic | perched water/lava pockets: **L0–L2 only; invisible at L3–L4** |
| S5 | Surface (`SURFACE`) | `WORLD_SURFACE_WG`, biome@column, `SurfaceRules`, surface noises | near-surface material replacement plus eroded-badlands and frozen-ocean extensions | per-column 2D walk with vertical block edits | S3 height, S2 biome, `BIOMES`@1 | own column (+ biome edge) | moderate: per-column rule tree and extension noises | deterministic | ordinary skinning: L0–L2; badlands pillars/icebergs can alter **L3–L4 geometry** |
| S6 | Carvers (`CARVERS`) | `WorldCarver` per source-biome, `CarvingMask`, aquifer | air/cave carve-outs, `FINAL_HEIGHTMAPS` | 3D ellipsoids over 17×17 chunk ring | S3, `STRUCTURE_STARTS`@8 | **17×17 chunks (range 8)** | moderate; large neighborhood, per-chunk seeded random | deterministic per seed, but per-chunk `WorldgenRandom` stream | subtractive caves/canyons: **mostly L0–L1; rare canyon lips L2; invisible L3–L4** |
| S7a | Structure placement (`FEATURES`) | structure starts/references, structure registry, `GenerationStep`, terrain-adaptation side input | villages, monuments, fortresses, cities, and other structure blocks | multi-chunk 3D bounding boxes clipped to the writable chunk | starts/references, S6@1; **writes @1** | starts declared @8; writable region @1 | expensive and sparse; template/jigsaw placement | deterministic per seed, high spatial entropy | size-dependent; major structures affect **L0–L4** |
| S7b | Placed/configured features (`FEATURES`) | biome `PlacedFeature` lists, `GenerationStep`, per-chunk random | trees, ore deposits, lakes, springs, vegetation, top-layer edits | mixed 2D placement + 3D features | S6@1, `STRUCTURE_STARTS`@8; **writes @1** | reads `CARVERS`@1, writes neighbor@1 | expensive: many placements, per-feature random streams | deterministic per seed, **high local entropy**; write-radius 1 blocks per-chunk parallelism | ores are internal; trees and large configured features can affect L0–L2, exceptionally coarser Levels by footprint |

Notes tying rows to source:

* **S1/S3 thresholds are distinct:** `SURFACE_DENSITY_THRESHOLD = 1.5625` gates the Overworld cave branch and preliminary-surface search; block filling asks `Aquifer.computeSubstance(context, finalDensity)`, which returns no replacement when density is **greater than zero**, leaving the default solid block. See `NoiseRouterData.java:241` and `Aquifer.java:37-41,135-143`.
* **Ore *veins* (S3, not S7):** `OreVeinifier` (`OreVeinifier.java:15`) is a `BlockStateFiller` consumed by the `NoiseChunk` block-state rule during `fillFromNoise`, gated by `veinToggle/veinRidged/veinGap` — it runs at `NOISE`, and only where `oreVeinsEnabled`. Ordinary ore *deposits* are configured features at `FEATURES` step `UNDERGROUND_ORES` (S7). These are two different mechanisms; both are horizon-invisible.
* **Aquifer (S4)** is not a separate status; it is decided inside the `NOISE` fill via `Aquifer.computeSubstance`. `createDisabled` (`Aquifer.java`) short-circuits to the flat `FluidPicker` when `aquifersEnabled==false` (Nether/End).
* **Carver ring (S6)** is verified from the generator loop: `int range = 8; for dx=-8..8 for dz=-8..8` over `region.getChunk(...)` (`NoiseBasedChunkGenerator.java:233`), each source chunk seeded by `random.setLargeFeatureSeed(seed+index, sourcePos.x, sourcePos.z)`.
* **Feature decoration order (S7):** 11 `GenerationStep.Decoration` steps `RAW_GENERATION, LAKES, LOCAL_MODIFICATIONS, UNDERGROUND_STRUCTURES, SURFACE_STRUCTURES, STRONGHOLDS, UNDERGROUND_ORES, UNDERGROUND_DECORATION, FLUID_SPRINGS, VEGETAL_DECORATION, TOP_LAYER_MODIFICATION` (`GenerationStep.java`), each a `PlacedFeature` list applied via `PlacedFeature.placeWithContext` with per-chunk `WorldgenRandom` unique seeds (`PlacedFeature.java`).
* **Structures share S7's loop:** for each decoration step, `ChunkGenerator.applyBiomeDecoration` places matching `StructureStart`s first, then that step's `PlacedFeature`s (`ChunkGenerator.java:275-324`). `STRUCTURE_STARTS` decides sparse multi-chunk layouts; `STRUCTURE_REFERENCES` makes starts discoverable from intersected chunks; actual blocks appear at `FEATURES`.

## 4. Input-completeness & spatial-halo contract (per stage)

For a consumer that wants to reconstruct a stage's output without running the whole pipeline, the "input completeness" is: *what must already exist, and over what spatial extent.*

| Stage | Complete inputs required | Spatial halo (chunks) | Vertical extent | Notes |
|---|---|---|---|---|
| S0 | seed + dimension config + noise registry | n/a (global) | n/a | reusable across the whole dimension |
| S1 | S0 router | own column (climate flat-cached per column; base3D per cell) | full dim height | no chunk data needed — pure function of position |
| S2 (`BIOMES`) | S1 climate + `STRUCTURE_STARTS`@8 | quart cells of own chunk | full height (3D biomes) | quart addressing: 4×4×4-block cells |
| S3 (`NOISE`) | S1 `finalDensity` + S2 biomes@1 | **+1 chunk** (aquifer/blend edge) | cell lattice over full dim height | write radius 0 |
| S4 (aquifer) | S3 in progress + aquifer fields | +1 chunk grid | full height | inside S3 loop |
| S5 (`SURFACE`) | `WORLD_SURFACE_WG` + biome@column + S2@1 + surface noises | own column (+biome edge) | near-surface rule depth; extensions may rise above base height | write radius 0; badlands/iceberg extensions are geometry, not only veneer |
| S6 (`CARVERS`) | S3 blocks + `STRUCTURE_STARTS`@8 | **17×17 (range 8)** | carve Y-range per carver | write radius 0; reads huge neighborhood, writes local |
| S7 (`FEATURES`) | S6@1 + structure starts/references@8 + biome feature lists | reads +1 plus starts@8, **writes +1** | full structure/feature bounding volumes | structures and feature bleed prevent naive per-chunk parallelism |

**Takeaway:** base natural-terrain reconstruction needs S0→S3 with a **+1 chunk** biome halo plus the declared structure-start side input used by `Beardifier`. Complete visible geometry also needs S5 extensions and S7 structures/features; the range-8 and write-1 dependencies cannot be discarded when reproducing those outputs.

---

## 5. Per-dimension audits

Each dimension is a different router, lattice, fluid policy, and feature registry. Exact router expressions, flags, lattice constants, and noise constructors are canonical in the [worldgen seams reference §§3, 7, 10–12](../reference/upstream/minecraft-1.21.11-worldgen-seams.md#3-densityfunction-algebra); this section records their consequences in the ordered DAG.

### 5.1 Overworld

* **S1–S4:** six-field climate drives 3D `MultiNoiseBiomeSource`; the 4×8 density lattice feeds active aquifers and `OreVeinifier`. Old-world blending and structure `Beardifier` are additional S3 inputs.
* **S5–S6:** biome surface rules can add badlands/iceberg extensions; cave/canyon carvers use the full range-8 source ring.
* **S7:** common decoration includes lava lakes, underground structures, stone/soil disks, ordinary ore deposits, water/lava springs, biome vegetation, freezing, and structures. Surface extensions and major structures can survive L3–L4; ores are internal; vegetation is mainly L0–L2.

### 5.2 Nether

* **S1–S4:** two legacy biome noises feed `MultiNoiseBiomeSource`; the 4×8 lattice forms a closed floor/ceiling volume. Aquifers and noise-time ore veins are disabled; the flat fluid picker supplies lava below its level.
* **S5–S6:** ceiling-aware surface rules and Nether cave carvers operate on the closed volume.
* **S7:** all five biomes place springs, glowstone, fire, mushrooms/fungi/vines, biome formations (basalt columns/deltas or soul-sand patches), and configured quartz/gold/ancient-debris ores. Formations and large fungi are the most likely coarse-visible features; ores and small vegetation remain L0–L1.

### 5.3 End

* **S1–S4:** the 8×4 density lattice uses the End-island field; aquifers and noise-time veins are disabled and the flat fluid picker supplies air.
* **S2 is a separate branch:** `TheEndBiomeSource.getNoiseBiome` returns `THE_END` inside chunk-radius 64. Outside it samples only router **erosion** at the chunk-center-derived block coordinate, then applies thresholds `>0.25` highlands, `≥-0.0625` midlands, `<-0.21875` small islands, otherwise barrens (`TheEndBiomeSource.java:58-79`). It does not use `MultiNoiseBiomeSource` or six-dimensional nearest-point lookup.
* **S5–S6:** the surface rule is end stone; the five End biome builders register no carvers.
* **S7:** `THE_END` places obsidian spikes and the platform; highlands place return gateways and chorus plants; small-island biomes place decorated End islands at `RAW_GENERATION`; midlands/barrens add none (`EndBiomes.java:24-46`). Spikes and decorated islands can survive L3–L4; chorus plants are local.

### 5.4 Feature-stage audit by dimension

All rows use biome `PlacedFeature` lists plus the per-(chunk, step, feature-index) random stream, read `CARVERS` at radius 1, and may write at radius 1. Cost scales with placement attempts and configured-feature volume; all are deterministic but spatially high-entropy.

| Dimension / decoration class | Inputs → outputs / dimensionality | Order and cost | L0–L4 impact |
|---|---|---|---|
| Overworld lakes, springs, freezing | height/air/fluid predicates → 3D fluid bodies or column-top edits | `LAKES`, `FLUID_SPRINGS`, `TOP_LAYER_MODIFICATION`; moderate | lakes/ice L0–L2; rare broad surfaces coarser |
| Overworld ores/disks/underground decoration | height providers + replaceable tags → 3D blobs | `UNDERGROUND_ORES` then `UNDERGROUND_DECORATION`; many attempts, expensive | normally internal, L0 only when exposed |
| Overworld vegetation/local formations | biome surface + placement modifiers → trees, plants, rocks, icebergs | `LOCAL_MODIFICATIONS`, `VEGETAL_DECORATION`; biome-dependent, high variance | plants L0–L2; large trees/icebergs can reach L3 |
| Nether formations/springs/glowstone | closed-volume predicates → columns, deltas, blobs, fluids, glowstone | local/surface/underground decoration; many cavity probes | formations L0–L3; small patches L0–L1 |
| Nether ores/vegetation | replaceable tags or floor/ceiling predicates → ore blobs, fungi, vines | underground then vegetal decoration; high attempt count | ores internal; large fungi L0–L2 |
| End islands/spikes/gateways/platform | radial/height predicates → multi-block 3D structures | raw generation, surface structures, top layer; sparse but large | **L0–L4** for islands/spikes |
| End chorus | End-stone surface predicate → branching 3D plants | vegetal decoration; sparse | L0–L2 |

Primary feature sources: `BiomeDefaultFeatures.java:19-105,391-423`, `biome/OverworldBiomes.java`, `biome/NetherBiomes.java:39-79`, and `biome/EndBiomes.java:24-46`.

### 5.5 Shared vs dimension-specific seams

| Seam | Shared across dims | Dimension-specific |
|---|---|---|
| `ChunkStatus` chain & `GENERATION_PYRAMID` halos | ✅ identical (§1) | — |
| `RandomState`/`NoiseRouter.mapAll` wiring mechanism | ✅ same code path | seed-fork + Legacy-vs-Xoroshiro choice differs |
| 15-field router **interface** | ✅ same record | **field *contents* differ per dim** (routers `overworld/nether/end`) |
| Cell lattice math (`QuartPos.toBlock`) | ✅ same formula | **values differ: 4×8 (OW/Nether) vs 8×4 (End)** |
| Biome lookup | quart-addressed output contract | OW/Nether use multi-noise nearest-point lookup; **End uses radial distance + erosion thresholds** |
| Base 3D density primitive | mechanism shared | **OW/Nether = BlendedNoise; End = EndIsland(SimplexNoise)+BlendedNoise** |
| Aquifers (S4) | mechanism shared | **only Overworld active** |
| Ore veins (S3 OreVeinifier) | mechanism shared | **only Overworld active** |
| Surface rule engine | ✅ same `SurfaceRules` | rule trees differ (`overworld/nether/end`) |
| Carver 17×17 ring | ✅ same loop | carver *sets* differ per biome; End ~none for horizon |
| Feature `GenerationStep` order | ✅ same 11 steps | feature *contents* differ per biome/dim |

**Do not generalize the Overworld heightmap abstraction to Nether (closed ceiling, no aquifers) or End (swapped lattice, simplex island field, AIR fluid).** These are separate audits with a shared *interface* (the 15-field router + ChunkStatus DAG) but dimension-specific *content and lattice*.

---

## 6. Distant-visible (L0–L4) impact synthesis

Voxy Levels: voxel edge = `1<<lvl` blocks → L0=1, L1=2, L2=4, L3=8, L4=16; `WorldSection` footprint = `32<<lvl` blocks (`voxy-0.2.11-alpha` §1). A stage "affects Level Ln" if its geometric contribution is larger than one Ln voxel *and* is opaque/height-setting.

| Stage | L0 (1 blk) | L1 (2) | L2 (4) | L3 (8) | L4 (16) |
|---|---|---|---|---|---|
| S1 climate/base-noise (drives height) | ✅ | ✅ | ✅ | ✅ | ✅ |
| S3 terrain fill (`finalDensity`) | ✅ | ✅ | ✅ | ✅ | ✅ |
| S2 biome (material/tint, not height) | ✅ | ✅ | ~ | ✗ | ✗ |
| S5 surface rules/extensions | ✅ | ✅ | ✅ | ~ | ~ |
| S4 aquifer fluid pockets | ✅ | ✅ | ✗ | ✗ | ✗ |
| S6 carvers (caves/canyons, subtractive) | ✅ | ✅ | ~ (canyon lips) | ✗ | ✗ |
| S7a structures | ✅ | ✅ | ✅ | ✅ | ✅ (major structures) |
| S7b features/ores/vegetation | ✅ | ✅ | ~ (large features) | rare | rare |

✅ = materially affects that Level; ~ = marginal; ✗ = normally averaged out by downsampling. **S0→S3 determines the base natural-terrain silhouette**, with dimension-specific router/lattice. It is not the whole L3–L4 scene: Overworld surface extensions and major structures can survive coarse aggregation. This is an observation about vanilla output for the partition ticket to act on, not itself a partition decision.

---

## 7. Determinism & entropy summary

| Stage | Determinism | Entropy source | Reproducible from |
|---|---|---|---|
| S0 RandomState | fully deterministic | seed + dimension | (seed, dim) |
| S1 climate/base-noise | fully deterministic | wired `NormalNoise`/`BlendedNoise` | (seed, dim, pos) |
| S2 biomes | fully deterministic | OW/Nether climate target → nearest `ParameterPoint`; End radius + erosion thresholds | (seed, dim, quart pos, biome-source config) |
| S3 terrain fill | fully deterministic | `finalDensity` | (seed, dim, pos) |
| S4 aquifers | deterministic | `aquiferRandom=fromHash("aquifer")` + fields | (seed, dim, cell) |
| S5 surface | deterministic | surface/extension noises + `noiseRandom.at(x,0,z)` | (seed, dim, biome, column) |
| S6 carvers | deterministic **per seed**, high local variety | per-source-chunk `WorldgenRandom.setLargeFeatureSeed(seed+idx, cx, cz)` | (seed, dim, source chunk) |
| S7a structures | deterministic **per seed**, sparse/high variety | structure-set placement + per-step feature seed | (seed, dim, structure registry, starts/references, chunk, step) |
| S7b features | deterministic **per seed**, **highest local entropy** | per-chunk per-step `WorldgenRandom` unique seeds; write-radius 1 | (seed, dim, biome feature lists, chunk, step) |

Nothing in vanilla worldgen is non-deterministic given (seed, dimension, position); "high entropy" here means *high spatial-frequency variety and per-chunk independent random streams*, which is what makes S6/S7 expensive and hard to parallelize (write bleed), not non-reproducible.

---

## 8. Evidence index (primary sources)

All paths are `external/minecraft-src/src/net/minecraft/...` unless noted; line numbers are against the CFR-0.152 tree of the hashed jar (§0).

| Topic | Source |
|---|---|
| ChunkStatus chain, heightmap validity | `world/level/chunk/status/ChunkStatus.java:28-41`; `world/level/levelgen/Heightmap.java` |
| Side-input DAG (halos, write radii) | `world/level/chunk/status/ChunkPyramid.java:18`; `ChunkStep.java:26`; `ChunkDependencies.java:16-50` |
| Generator stage ordering | `world/level/levelgen/NoiseBasedChunkGenerator.java:113,149,217,226,233,265` |
| Carver 17×17 ring (range 8) | `world/level/levelgen/NoiseBasedChunkGenerator.java:233` (`applyCarvers`); `world/level/levelgen/carver/WorldCarver.java` |
| 15-field router | `world/level/levelgen/NoiseRouter.java:17`; `NoiseRouterData.java` |
| DensityFunction algebra / constants | `world/level/levelgen/DensityFunction.java`; `DensityFunctions.java`; `NoiseRouterData.java` |
| NoiseChunk cell grid / interpolation | `world/level/levelgen/NoiseChunk.java` |
| Per-dimension settings & lattice | `world/level/levelgen/NoiseSettings.java`; `NoiseGeneratorSettings.java` (bootstrap) |
| RandomState wiring (Legacy vs Xoroshiro, once) | `world/level/levelgen/RandomState.java:47`; `PositionalRandomFactory.java` |
| Noise stack | `world/level/levelgen/synth/{NormalNoise,PerlinNoise,ImprovedNoise,SimplexNoise,BlendedNoise}.java` |
| Terrain splines | `data/worldgen/TerrainProvider.java`; `world/level/levelgen/DensityFunctions.java` (spline); `util/CubicSpline.java` |
| Climate / biome source | `world/level/biome/Climate.java:44`; `MultiNoiseBiomeSource.java`; `TheEndBiomeSource.java:58-79` |
| Aquifer grid & disabled path | `world/level/levelgen/Aquifer.java` |
| OreVeinifier (S3, block-state filler) | `world/level/levelgen/OreVeinifier.java:15` |
| Surface system & rules | `world/level/levelgen/SurfaceSystem.java`; `SurfaceRules.java`; `data/worldgen/SurfaceRuleData.java` |
| Beardifier / Blender (structure/upgrade density) | `world/level/levelgen/Beardifier.java`; `blending/Blender.java`; `blending/BlendingData.java` |
| Structure planning/references/placement | `world/level/chunk/ChunkGenerator.java:257-324,380-461`; `world/level/chunk/status/ChunkStatusTasks.java:49-67`; `world/level/levelgen/structure/StructureStart.java` |
| Feature decoration | `world/level/chunk/ChunkGenerator.java:257-324`; `world/level/levelgen/GenerationStep.java`; `levelgen/placement/PlacedFeature.java`; `data/worldgen/BiomeDefaultFeatures.java`; `data/worldgen/biome/{OverworldBiomes,NetherBiomes,EndBiomes}.java` |
| Coordinates / lattices | `core/QuartPos.java`; `core/SectionPos.java`; `world/level/dimension/DimensionType.java` |

Sibling reference (class internals, same upstream/hash): [`docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md`](../reference/upstream/minecraft-1.21.11-worldgen-seams.md). Voxy Level semantics: [`docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md`](../reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md).

---
*Version-pinned DAG record for Minecraft 1.21.11 (`26.1-snapshot-11`, jar SHA-256 `556C0FA7…436822`) worldgen, Overworld/Nether/End, as inspected 2026-08-16. Research only — not partition decisions. Newer Minecraft versions require a separately versioned document.*
