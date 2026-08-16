# Research: port-vanilla-batch — Subtree Sharing Table for Layer 2

> **Status:** wayfinder:research — AFK, version-bound
> **Wayfinder map:** #22 · ticket #92 · blocks #83 tiling decision, informs #85 partition
> **Question:** Which DensityFunction subtrees are shareable across neighboring sections vs per-column, and what cache key + halo does that imply?
> **Upstream project:** Minecraft (Mojang Studios)
> **Upstream version:** 1.21.11 (decompiled corpus 26.1-snapshot-11, CFR 0.152, `external/minecraft-src/src`)
> **Source revision:** decompiled `external/minecraft-src/src` tree + local `26.1-snapshot-11.jar` (no git SHA; same corpus as `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md`)
> **Research date:** 2026-08-16
> **Scope:** only the named upstream version + current `java/` port adapters. Does not describe future L4/L3 learned approximators.
> **Invalidation rule:** newer Minecraft version requires re-verification against its `NoiseRouterData`/`NoiseChunk`/`DensityFunctions` corpus; do not silently edit this file for a different version.

---

## TL;DR — the one table the lattice gate needs

**Cache key for the conditioning cache must be `(dimKey, RouterField mask, lattice, halo)`. No invented 8×8 or 16×16 heightplane survives — the only native lattices are `cellWidth=4` / `cellHeight=8` (Overworld) and their dimension-swapped variants.**

| # | Subtree (RouterField(s)) | Sharing scope | Native lattice | Cache key | Halo (blocks) | Why |
|---|--------------------------|---------------|----------------|-----------|---------------|-----|
| 1 | **Climate 2D quart-cached** — `CONTINENTS`, `EROSION`, `RIDGES` (incl. `*_LARGE`, `RIDGES_FOLDED` folded), `SHIFT_X/Z` warp | **Tile-shareable**: shareable across *all* neighboring sections that overlap the same quart-column plane. FlatCache is per `NoiseChunk` (one chunk = `noiseSizeXZ+1 = 5×5` quarts), quantized to `QuartPos`. Any column at `(blockX, blockZ)` rounded to quart matches. | 2D quart: `4×4` XZ, Y-independent (`QuartPos.toBlock(QuartPos.fromBlock(x))`) | `(dimKey, {CONTINENTS,EROSION,RIDGES,SHIFT}, quart2d)` | **0** if tiles are quart-aligned; **4** (1 quart) if not. Shift warp is `shiftA/B(Noises.SHIFT)` with amplitude baked into the noise, not an extra spatial halo — the warp offset is evaluated *inside* the quart cell, so the provider must sample at the warped coordinate, not expand the cache. | `NoiseRouterData.java:89-96` `flatCache(cache2d(shiftA/B(SHIFT)))` + `flatCache(shiftedNoise2d(shiftX,shiftZ,0.25,…))`; `NoiseChunk.java:383-421` `FlatCache` values sized `noiseSizeXZ+1`, keyed by `QuartPos.fromBlock(blockX/Z) - firstNoiseX/Z`; `VanillaNoiseRouterSampler.java:22-32` sampling at `qx*4+2` cell centres |
| 2 | **Climate 2D shifted (no explicit flatCache in Router)** — `TEMPERATURE`, `VEGETATION` | Same as (1) logically, but upstream `NoiseRouterData.overworld()` builds them as bare `shiftedNoise2d(shiftX, shiftZ, 0.25, Noises.TEMPERATURE/VEGETATION)` without wrapping in `flatCache` (`NoiseRouterData.java:233-234`). They are still pure `f(blockX,blockZ)` (xzScale=0.25, yScale ignored). In a tiled port they **must** be wrapped as `flatCache(cache2d(…))` to be shareable — vanilla's per-block `compute` pays the cost, the port's batch benefits from caching. | 2D quart `4×4` XZ | `(dimKey, {TEMPERATURE,VEGETATION}, quart2d)` — same bucket as (1) | **0 / 4** same as (1) | `NoiseRouterData.java:233-234`; `DensityFunctions.java:119-131` `flatCache/cache2d` factories exist precisely to avoid recomputation; issue notes `VanillaNoiseRouterSampler` naive 480-sample loop benefits from in-graph cache — port should preserve in GLSL/CPU batch |
| 3 | **Depth 3D split** — `DEPTH` | **Per-column + Y-gradient**: `DEPTH = yClampedGradient(-64,320,1.5→-1.5) + offset(GLOBAL_OFFSET + spline(overworldOffset(continents,erosion,ridges)))` (`NoiseRouterData.java:118-128`). The `offset` spline is a 2D quart field (same sharing as 1); the `YClampedGradient` is trivial `f(blockY)`. So depth is `f2D(quartX,Z) + fY(y)`, shareable per column, not per block. | Mixed: 2D quart XZ + 1D Y (8-block cells) | `(dimKey, {DEPTH}, quart2d × yCell)` — cache the 2D offset once per quart column, add Y gradient on lookup | **0** XZ, **0** Y beyond cell (gradient is analytic) | `NoiseRouterData.java:127-128` `offsetToDepth`; `DensityFunctions.java` `YClampedGradient`; final density composes depth via `noiseGradientDensity(factor, depth + jaggedness*halfNegative(jagged))` |
| 4 | **Preliminary surface + final density 3D cell-interpolated** — `PRELIMINARY_SURFACE_LEVEL`, `FINAL_DENSITY` | **Cell-shareable, not section-shareable without halo**: wrapped as `Interpolated` → `BlendedNoise` → `noiseGradientDensity` → `slide` → `interpolated(blendDensity)`. `NoiseChunk` trilinearly interpolates over `cellWidth=4, cellHeight=8` cells (`NoiseSettings.java:52,56` accessors; `NoiseChunk.java:111-122` `cellCountXZ=16/cellWidth`, `cellCountY=height/cellHeight`, `cellNoiseMinY=floorDiv(minY,cellHeight)`). A block at a cell boundary needs the 8 corner cell values. | 3D cell: `4×8×4` blocks per cell; chunk = `4×48×4` cells Overworld (`16/4=4, 384/8=48`) | `(dimKey, {PRELIM,FINAL}, cell3d)` — tile key aligned to `cellWidth/Height` grid | **4 XZ, 8 Y** (1 cell) halo beyond any tile/selection. Sampling at quart centres `x=qx*4+2, y=qy*8+4, z=qz*4+2` (`VanillaNoiseRouterSampler.java:67-71`) is still sampling *inside* a cell — the interpolator (`NoiseChunk.java:219-312` `fillSlice/selectCellYZ/updateForY/X/Z`) already covers it with slice double-buffering. | `NoiseRouterData.java:91-93` `BASE_3D_NOISE_*` `BlendedNoise(0.25,0.125,80,160,8)` Overworld vs `0.25,0.375,80,60,8` Nether vs `0.25,0.25,80,160,4` End; `NoiseChunk.java:219-289` slice/interpolator lifecycle; `DensityFunctions.java:115,380` `Interpolated` marker |
| 5 | **Aquifer** — `BARRIER`, `FLUID_LEVEL_FLOODEDNESS`, `FLUID_LEVEL_SPREAD`, `LAVA` + derived `erosion/depth` + `aquiferRandom`/`positionalRandomFactory` | **Grid-shareable at aquifer spacing, not section-shareable**: `Aquifer.java:57-65` constants `X_SPACING=16, Y_SPACING=12, Z_SPACING=16` (`X_SPACING_SHIFT=4`, `Y_SEPARATION=3`, `Z_SEPARATION=6`, `X_RANGE=10, Y_RANGE=9, Z_RANGE=10`, `X_SEPARATION=6, Y_SEPARATION=3, Z_SEPARATION=6`, `SAMPLE_OFFSET=-5,1,-5`). Voronoi jitter: `random.nextInt(10/9/10)` inside each grid cell, then 2×3×2 neighbourhood search for 4 nearest centres (`Aquifer.java:167-218`). Requires caching per aquifer grid cell. | Aquifer grid: `16×12×16` blocks (jittered) | `(dimKey, {BARRIER,FLOODEDNESS,SPREAD,LAVA}, aquiferGrid)` — grid key = `gridX(blockX-5), gridY(blockY+1), gridZ(blockZ-5)`; also needs `preliminarySurfaceLevelCache` per quart column to compute `skipSamplingAboveY` | **16 XZ, 12 Y** (1 aquifer cell) beyond any tile. Plus Voronoi search radius: distance metric `similarity` threshold `25.0` (`Aquifer.java:262-265`), `MAX_REASONABLE_DISTANCE=11` — the 16/12 halo already covers the jittered centre displacement (`±5 XZ, ±4 Y` inside 16×12 window). | `Aquifer.java:57-98` grid constants + constructor computing `minGridX/Z` from `ChunkPos` ±5, `minGridY` from `minY+1`, `gridSizeX/Z`, `preliminarySurfaceLevelCache`; `Aquifer.java:135-255` `computeSubstance` Voronoi + `calculatePressure` barrier noise; `NoiseChunk.java:148-157` aquifer creation vs `createDisabled`; `NoiseRouterData.java:227-230` aquifer noises `0.5, 0.67, 0.714…, (default)` |
| 6 | **Ore veins block-level** — `VEIN_TOGGLE`, `VEIN_RIDGED`, `VEIN_GAP` | **Per-block (with Y-bounded sharing)**: built via `yLimitedInterpolatable(y, noise(…freq…), veinMinY, veinMaxY, fallback)` (`NoiseRouterData.java:244-251`). Frequencies: `VEININESS 1.5`, `VEIN_A/B 4.0`, `GAP default`. `veinMinY/maxY` derived from `OreVeinifier.VeinType.values().minY/maxY` (stream). Outside that Y band the function returns constant (0) — so whole XY planes above/below are shareable as constant. Inside band, no cell interpolation — raw `NormalNoise` per block. | Block `1×1×1` (noise frequency 1.5/4.0 means correlation radius ~1-2 blocks, but no cell grid) | `(dimKey, {VEIN_TOGGLE,RIDGED,GAP}, block1)` — effectively no inter-section sharing; omit at L4/L3 is valid if veins not needed for silhouette | **0** halo (aside from Y-band clipping). Y-band itself is `veinMinY..veinMaxY`, roughly Overworld vein band near -60..320 band — check `OreVeinifier.java` for `VeinType` bounds (source of `minY/maxY` stream). | `NoiseRouterData.java:244-251`; `DensityFunctions.java` `noise`, `yLimitedInterpolatable` → `RangeChoice`; `OreVeinifier.java` |
| 7 | **Scaffolding / caves inside finalDensity** — `SPAGHETTI_ROUGHNESS_FUNCTION`, `SPAGHETTI_2D_THICKNESS_MODULATOR`, `SPAGHETTI_2D`, `ENTRANCES`, `NOODLE`, `PILLARS` (all `cacheOnce`) + `SPAGHETTI_*_RARITY`, `CAVE_LAYER`, `CAVE_CHEESE` | **Per-evaluation cacheOnce** (`DensityFunctions.cacheOnce`): `NoiseChunk.java:362` `CacheOnce` invalidates only when `FunctionContext` position changes. Shareable only within a single block evaluation's subtree, not across sections. Indicates these subtrees appear many times in `underground`/`spaghettiRoughnessFunction`/`entrances`/`pillars` but are memoized per sample point. For batching, compute each `cacheOnce` once per quart point, not per reference. | Point `1×1×1` (per sample) | N/A — internal memoization, not conditioning-cache. Do not key as tile; let batch compute deduplicate by evaluating DAG bottom-up. | **0** external halo | `NoiseRouterData.java:106,150,154,162,186` `cacheOnce(…)`; `NoiseChunk.java:362` `CacheOnce` wrapping; `DensityFunctions.java:127` |
| 8 | **Blending / beardifier** — `BLENDING_FACTOR=10.0`, `BLENDING_JAGGEDNESS=zero()`, `BlendAlpha/BlendOffset`, `BeardifierMarker` | **Per-chunk bi-lattice**: `Blender` `BlendingOutput{alpha,blendingOffset}` sampled on quart lattice `noiseSizeXZ+1` (`NoiseChunk.java:132-147` double loop `x<=noiseSizeXZ, z<=noiseSizeXZ`, `alpha/offset` arrays). FlatCache-like but owned by `NoiseChunk.blendAlpha/blendOffset` (`NoiseChunk.java:130-131`). Shareable within blended-chunk radius only (~old world blending border). | Quart `4×4` XZ (same as 1) | `(dimKey, {BLEND_ALPHA,BLEND_OFFSET}, quart2d)` — but only when `blender != empty` (`NoiseChunk.java:365-371`). For Layer 2 distant tiling, blending is negligible; omit or set alpha=1, offset=0. | **0** (or omit) | `NoiseRouterData.java:40-41` `BLENDING_FACTOR/JAGGEDNESS`; `NoiseChunk.java:130-147,365-371,383-410,439-` `FlatCache/BlendAlpha`; `Blender.java` |
| 9 | **End islands** — `endIslands(0L)` → `cache2d(endIslands)` | **2D chunk-quantized** (`DensityFunctions.EndIslandDensityFunction:436-489`): `getHeightValue` loops `xo/zo ±12` chunks, threshold `islandThreshold=-0.9`, quantized to `sectionX/2, sectionZ/2` (`blockX/8, blockZ/8` via `context.blockX()/8`). Shareable per 16-block section pair, needs neighbour lookup radius. | 2D cache `16×16` section-chunk? Actually 8-block quant (`block/8` = half-chunk) | `(dimKey=END, {}, cache2d_8block)` | **192 blocks** (`12 chunks ×16`) halo per the loop bounds `xo/zo ∈ [-12,12]` — but only relevant in End dimension where `NoiseRouterData.end()` (`NoiseRouterData.java:289-293`) uses `cache2d(endIslands)` | `DensityFunctions.java:436-489` loop `-12..12`, `chunk*` math; `NoiseRouterData.java:104,290-291` |

### Key sizing summary for #83 lattice gate

* **Dim must be part of every key** — Overworld vs Nether vs End are *different trees*, not param tweaks (`NoiseRouterData.java:91-93` `BASE_3D_NOISE_*` distinct `xzScale/yScale/xzFactor/yFactor/smear`, `NoiseRouterData.java:226-293` `overworld/nether/end` routers). `WorldNoiseAccess` / `RandomState` already threads `dimKey` — hardcoding Overworld `4×8` silently misrenders other dims (`docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §4`).
* **RouterField mask** = 15-bit mask over `RouterField` enum (`RouterField.java:17-61` `COUNT=15`, ordinals match `NoiseRouter` codec field names). Climate 6 share one lattice, density 2 share another, aquifer 4 share another, veins 3 share another — so a cache entry can be keyed by `mask` to avoid over-fetching (e.g. L4 silhouette needs only 6+2=8 fields, not 15).
* **Lattice** is one of `{quart2d(4), cell3d(4×8×4), aquiferGrid(16×12×16), block(1), cache2d_8block(End)}`. This proves no invented `8×8` or `16×16` heightplane — the only 2D plane vanilla ever produces is quart `4×4` (via `QuartPos`), the only 3D cell is `4×8×4` (`NoiseSettings`), aquifer is `16×12×16` (`Aquifer.java`). Heightmap `WORLD_SURFACE_WG` itself is `16×16` *per chunk* but it is a *derived* max over 2D fields, not a sampling lattice — do not cache it as a separate plane.
* **Halo** values above are the minimal correct for *lossless* tiling. For Layer 2 where approximate silhouette is acceptable, the design can *choose* to truncate: e.g. omit aquifer/veins halos (omit entire groups 5/6) with documented silhouette cost (flat water vs aquifers, no ore veins — identical to `ChunkStatus.NOISE` heightmaps which are valid without `SURFACE`/`CARVERS`).

#### Concrete tile example (Layer 2 L4)

Region `T=32` sections/axis = `512³` blocks (GLOSSARY.md: L4 voxel 16³ → region 512³). Native chunk = 16 blocks → 32 chunks per axis, but the *sampling* lattice per chunk is:

* 2D climate: `noiseSizeXZ+1 = 5×5` quart points per chunk → `32×5 -1` ≈ 159 points per 512-block edge if merged (share boundaries, no double-count).
* 3D density: `4×48×4 = 768` cells per chunk column; per 512-block cube that is `128×64×128 = 1,048,576` cell corners.

A `QuartNoiseCompute` batch (`QuartNoiseCompute.java:43-44,72`) with `local_size=4,4,4` (64 threads/section) and `MAX_BATCH_SIZE=256` covers one dispatch per ~8 chunks (256 sections / 24 sections per chunk column ≈ 10 chunk columns). The batch should group by `(dimKey, lattice)` and expand each tile by its halo *before* dispatch, then trim halo after evaluation. Tile count is therefore determined *in cell/aquifer units*, not in fabricated heightplane pixels.

---

## 1. What the code actually caches (and why tiling must respect it)

### 1.1 FlatCache / Cache2D / CacheOnce / CacheAllInCell / Interpolated are Marker nodes

In `DensityFunctions.java:119-131` the only way to ask for caching is wrapping the target function in a `Marker`:

```java
return new Marker(Marker.Type.FlatCache, function);   // 119
return new Marker(Marker.Type.Cache2D, function);       // 123
return new Marker(Marker.Type.CacheOnce, function);     // 127
return new Marker(Marker.Type.CacheAllInCell, function);// 131
return new Marker(Marker.Type.Interpolated, function);  // 115
```

`Marker` itself is a no-op (`DensityFunctions.java:356-365` delegates `compute/fillArray` to `wrapped`). The *real* caching is injected by `NoiseChunk.wrapNew` (`NoiseChunk.java:349-381`):

```java
case FlatCache      -> new FlatCache(this, marker.wrapped(), true);
case Cache2D        -> new Cache2D(marker.wrapped());      // per-position 2D LRU
case CacheOnce      -> new CacheOnce(this, marker.wrapped());
case CacheAllInCell -> new CacheAllInCell(this, marker.wrapped());
case Interpolated   -> new NoiseInterpolator(this, marker.wrapped());
```

So a `DensityFunction` tree built in `NoiseRouterData.bootstrap` is *declarative* — only when a `NoiseChunk` is instantiated (`NoiseChunk.java:115-165`) does each `Marker` become a stateful cache scoped to that chunk.

**Batch consequence:** recreating a `NoiseChunk` per section (`VanillaNoiseRouterSampler` currently does `ChunkNoiseSampler(4)` per 16³) is the performance bottleneck (the issue's `500 ms timeout + 5 s warn` in `GpuNoiseRouterSampler`). The correct batch primitive is the existing `LodGenerationQueue.drainStage(maxBatch)` (`LodGenerationQueue.java:136-153` — blocking poll for first task then greedy drain without blocking) feeding `QuartNoiseCompute.compute(origins, N)` (`QuartNoiseCompute.java:126-163` — upload `N×4 ints`, `glDispatchCompute(N,1,1)`, barrier).

### 1.2 Where the halos really come from

| Cache impl | File | Halo source |
|------------|------|-------------|
| `FlatCache` | `NoiseChunk.java:383-437` | Array `values[sizeXZ*sizeXZ]` where `sizeXZ=noiseSizeXZ+1`, `noiseSizeXZ=QuartPos.fromBlock(cellCountXZ*cellWidth)`. Index `x=QuartPos.fromBlock(blockX)-firstNoiseX`. Out-of-bounds falls back to `noiseFiller.compute(context)` — so reading 1 quart beyond chunk is correct (no halo) but re-evaluates without cache. Halo 0 if aligned, else 1 quart to avoid uncached fallback. |
| `Cache2D` | `NoiseChunk.java:360` delegates to `Cache2D` (inner class not shown here but same quart lattice) | Same quant as FlatCache; used for `CONTINENTS_LARGE`/`EROSION_LARGE`/`END islands` where the wrapped value itself is 2D. |
| `NoiseInterpolator` | `NoiseChunk.java:219-312` | Double-buffered slices `slice0/slice1` per `cellZIndex`, filled via `fillArray(slice, sliceFillingContextProvider)` which loops `cellCountY+1` Y positions (`sliceFillingContextProvider` at `NoiseChunk.java:79-106` `fillAllDirectly`). To evaluate at `blockX` at the tile's max edge, the interpolator needs the *next* cell's corner — hence 1 cell halo. |
| `Aquifer` | `Aquifer.java:57-125,167-218` | Grid derived from `gridX(blockX-5)`, `gridY(blockY+1)`, `gridZ(blockZ-5)` (`Aquifer.java:108,112,115,156-158`). Sampling examines `2×3×2=12` surrounding grid cells (`x1∈[0,1], y1∈[-1,1], z1∈[0,1]`). To answer a block at the tile edge you need the centre from the adjacent aquifer cell — halo 1 cell (16/12/16). The inner jitter `nextInt(10/9/10)` does not enlarge the halo beyond the cell because centres stay inside their cell. |
| `CacheAllInCell` | `NoiseChunk.java:278-289` `selectCellYZ` loops `cellCaches.forEach(c -> c.fillArray(...))` | Fills `cellWidth*cellWidth*cellHeight = 4*4*8=64` values per cell. Halo is the cell itself — sharing is per-cell, so tiling must keep cell boundaries. |
| `CacheOnce` | `NoiseChunk.java:362` | `lastPos` single-entry; invalidated when `blockX/Y/Z` changes. No halo — it's point-memo. |

### 1.3 No invented 8×8 / 16×16 heightplane

Proof:

* **Quantization is only via `QuartPos`** (`net.minecraft.core.QuartPos:6-10` `SECTION_TO_QUARTS_BITS=2`, helpers `fromBlock(block) = block>>2`, `toBlock(quart)=quart<<2`). Every 2D sampling (`VanillaNoiseRouterSampler.java:67,71` `qx*4+2`, `preliminarySurfaceLevelCache` `QuartPos.toBlock(QuartPos.fromBlock(sampleX))` `NoiseChunk.java:203-204`, `FlatCache` `QuartPos.fromBlock(context.blockX())` `NoiseChunk.java:413-414`) rounds to a 4-block grid. No `>>3` (8) or `>>4` (16) appears in any `DensityFunction` lattice.
* **Vertical cell is 8** (`NoiseSettings` Overworld `height=384, cellHeight=8 → cellCountY=48` `NoiseChunk.java:119`). Not 12 or 16 — those are *aquifer* spacings, not heightmap lattices. Nether/End swap to `8×4` and `BlendedNoise` factors (`NoiseRouterData.java:91-93`) but never introduce an 8×8 or 16×16 *heightplane* that could be confused with quart.
* **`SectionNoiseData.FLAT_LENGTH` is 480 or 960 depending on Y sampling** — `VanillaNoiseRouterSampler.java:57-76` uses `4×2×4=32` points ×15 = 480 floats per 16³ section (2 Y quarts per section because `cellHeight=8` → `16/8=2` cells). `QuartNoiseCompute.java:69` documents `FLOATS_PER_SECTION=SectionNoiseData.FLAT_LENGTH // 960` with `4×4×4=64` points (full Y). Neither is `8×8` or `16×16`.
* **Heightmap is `16×16` but derived**, not a sampling lattice: `Heightmap.java:38` `WORLD_SURFACE_WG` is `16×16` per `ChunkPos` but it stores the *max Y where density>threshold`, computed by scanning blocks — not by evaluating a 16-spaced noise. Caching it would be caching a *derived* scalar field, not reducing noise cost.

Therefore conditioning-cache keys of the form `(dimKey, mask, lattice, halo)` where `lattice ∈ {4, 4×8×4, 16×12×16, 1}` are sufficient and sound; any key that mentions `8×8`/`16×16` is an invention this research disproves.

---

## 2. Per-RouterField notes (the 15)

Derived from `RouterField.java:17-61` and `NoiseRouter` record `NoiseRouter.java:17`:

```
Climate 6: TEMPERATURE, VEGETATION, CONTINENTS, EROSION, DEPTH, RIDGES
Density 2: PRELIMINARY_SURFACE_LEVEL, FINAL_DENSITY
Aquifer 4: BARRIER, FLUID_LEVEL_FLOODEDNESS, FLUID_LEVEL_SPREAD, LAVA
Veins 3:   VEIN_TOGGLE, VEIN_RIDGED, VEIN_GAP       (COUNT=15)
```

* `TEMPERATURE`/`VEGETATION` — see §T1/T2: shareable as quart2d after port adds flatCache. In upstream they are evaluated per sample without cache; batch should add it.
* `CONTINENTS`/`EROSION`/`RIDGES` — already `flatCache(cache2d(shiftedNoise2d))` in `NoiseRouterData.java:94-96` (and `*_LARGE` variants `100-101`). The `RIDGES_FOLDED` (`peaksAndValleys`) is `-(abs(abs(weirdness)-0.666)-0.333)*3` (`NoiseRouterData.java:139-145`) — pure arithmetic on `RIDGES`, so inherits its 2D quart sharing.
* `DEPTH` — `yClampedGradient + offset` (`NoiseRouterData.java:118-128`); the offset spline (`TerrainProvider.overworldOffset/overworldFactor/overworldJaggedness` via `CubicSpline` `lerp + t(1-t)lerp` Hermite) is 2D quart; depth itself is accessed inside `noiseGradientDensity` per Y, so key must include Y.
* `PRELIMINARY_SURFACE_LEVEL` — `NoiseChunk.preliminarySurfaceLevel(int,int)` (`NoiseChunk.java:202-212`) with `Long2IntMap` per quart column (`QuartPos.toBlock(fromBlock)`). Shareable as quart2d result (int Y), but it is *inside* the density tree for caves/slides — for silhouette it can be cached as `int[16×16]` per chunk (?) but the native sharing is still quart (4-step lookup loops `blockZ+=4, blockX+=4` in `maxPreliminarySurfaceLevel` `NoiseChunk.java:190-199`).
* `FINAL_DENSITY` — the only field that gates `SURFACE_DENSITY_THRESHOLD=1.5625` (`NoiseRouterData.java:29` `1.5625`) → solid/air; includes `BlendedNoise` (`BlendedNoise.java:75-116` `xzMultiplier=684.412*xzScale`, per-octave `ImprovedNoise.noise(wrap(x*pow), wrap(y*pow), wrap(z*pow), yScalePow, y*pow)`), `BlendedNoise` interpolator, aquifer-affected `computeSubstance` barrier logic.
* `BARRIER`/`FLOODEDNESS`/`SPREAD`/`LAVA` — raw `Perlin/NormalNoise` at frequencies `0.5,0.67,0.714…, default` (`NoiseRouterData.java:227-230`); their *use* is via aquifer Voronoi, not direct per-block threshold, hence the grid halo.
* `VEIN_TOGGLE`/`RIDGED`/`GAP` — see §6.

All 15 are wired in `NoiseRouterData.overworld` (`NoiseRouterData.java:226-253` `new NoiseRouter(barrier…, temperature…, continents…, …, preliminarySurfaceLevel, fullNoise, veinToggle, veinRidged, veinGap)`). `nether()` (`267-273`) and `end()` (`289-293`) return zeroed climate/aquifer/vein fields, proving `dimKey` is mandatory.

---

## 3. What the port already does, and the gap

Evidence from `#92` comment (already gathered, re-grounded here):

* **Extractor** (`worldgen/ShadowRouterExtractor.java`): mirrors live `NoiseRouter` via `DensityFunction.Visitor` (reflection, no `net.minecraft` compile dep) → 8 SSBOs via indices for `NormalNoise/PerlinNoise/ImprovedNoise` + named `nnContinents/nnErosion/nnRidges/nnTemperature/nnVegetation/nnJagged(xzScale≈1500)/shift` + aquifer `nnBarrier/nnFloodedness/nnSpread/nnLava` + veins `nnVeinToggle/Ridged/Gap` (−1 fallback). Bindings 0 origins,1 perms(stride256),2 perlin ints,3 floats(MAX_OCTAVES16),4 normal ints,5 floats,6 spline,7 density.
* **Dispatch** (`ShaderSSBOManager`+`TerrainComputeDispatcher`+`QuartNoiseCompute`): `ShaderSSBOManager` owns 12 buffers (0–7+11), `RouterConfig` UBO 144 bytes binding 8 (`chunk_origin_x/z` at 0/4, grad, spline offsets, `nn_barrier…nn_vein_gap`). Per-chunk dispatch was `8-byte glBufferSubData at offset 0 + glDispatchCompute(1,1,1)+barrier` (`QuartNoiseCompute.java:126-163,152-153`). Quart batch upgrade: SSBO 14 origins (`N×4 ints x,y,z,pad`) + 15 output, `local_size=4,4,4` (64 threads/sec), `MAX_BATCH_SIZE=256` → one dispatch covers L4 (`T=32` sections/axis) fully (`QuartNoiseCompute.java:43-44,69-72,142-144,161`).
* **Biome** (`BiomePaletteSSBO.java`): bindings 12 palette (16 floats/entry `[temp/humid…offset/biomeId bits]`, `DEQUANT 1/10000`) +13 output (`4*4*96=1536 ints, qx*(4*96)+qz*96+qy`). `BiomePaletteSerializer.java` shows `16 floats/entry` with `DEQUANT 1/10000`.
* **MLP** (`TerrainShaperMLP` 4→128→128→3 + `TerrainShaperMlpSsbo`): faithful `CubicSpline` Hermite `lerp+t(1-t)lerp` → distill pattern (upstream `util/CubicSpline.java`).
* **Gap**: `GpuNoiseRouterSampler` today does per-section `CompletableFuture` 500 ms timeout + 5 s warn — **not** using `QuartNoiseCompute` batch. Fix is `LodGenerationQueue.drainStage(maxBatch)` (already in `LodGenerationQueue.java:136-153`) → `quartCompute.compute(origins,N)` — batch at queue drain, not dispatch. `RandomState` seed→`DensityFunction` wiring (`NoiseWiringHelper` + `aquiferRandom`/`oreRandom`) is the root the extractor mirrors, not to duplicate per-chunk.

Preserving in-graph caches in the port: the GLSL must implement `flatCache/cache2d/cacheOnce` memoization (e.g. shared memory per workgroup for quart points, private register for cacheOnce). The CPU batch (`port-vanilla-batch`) preserves them via `NoiseChunk`-style arrays or explicit `Map<QuartPos, double>` for quart2d and `double[cellCount]` for `fillArray`.

---

## 4. Recommendations for #83 and #85

### #83 tiling

* Tile on **cell/aquifer boundaries**, not on fabricated 8×8 patches. Keys as above.
* For a 512³ L4 region, expand each tile by halo = `max(group halo)` among requested fields. If requesting only climate+density (mask without aquifer/veins), halo is 4/8, not 16/12 — significant saving.
* Align tiles to quart (`block & ~3`) and cell (`block & ~3` XZ, `block & ~7` Y). The `LodGenerationQueue` `SectionTask` already keys by `SectionPos` (`block>>4`), so tile → origin list conversion must re-quantize origins to `blockX = sectionX*16` (section origin) then sample at `+2/+4` offsets — do not invent intermediate origins.

### #85 partition (omit vs learn aquifer/veins at L4/L3)

* At L4 viewed at 1 voxel = 16 blocks (region 512), aquifer Voronoi at 16×12 spacing and ore veins at block frequency are **sub-voxel** — omitting them changes sub-voxel occupancy but not silhouette at horizon distance. This matches `ChunkStatus.NOISE` validity: `WORLD_SURFACE_WG`/`OCEAN_FLOOR_WG` are valid at `NOISE` without `SURFACE`/`CARVERS`/`aquifer` final substance.
* Decision: **omit aquifer+veins for L4**, optionally also L3; recompute only for L2/L1/L0 where fluid/vein matters for player-adjacent correctness. If learning, learn only `finalDensity>threshold` (1.5625) as scalar SDF, not per-field vein/aquifer — the MLP already distills `CubicSpline` → 4→128→128→3, not aquifer.

---

## 5. Sources

**Primary upstream** (all `external/minecraft-src/src`, CFR 0.152, 26.1-snapshot-11):

* `net.minecraft.world.level.levelgen.NoiseRouter.java:17` — 15-field record
* `net.minecraft.world.level.levelgen.NoiseRouterData.java:24-253` — constants `GLOBAL_OFFSET=-0.50375, SURFACE_DENSITY_THRESHOLD=1.5625, NOISE_ZERO=0.390625 …`, `flatCache/cache2d/shiftA/B/BlendedNoise` wiring, `overworld/nether/end` routers
* `net.minecraft.world.level.levelgen.DensityFunction.java:20-107` — interface `compute/fillArray/mapAll`, `Visitor`, `NoiseHolder`, `SimpleFunction`
* `net.minecraft.world.level.levelgen.DensityFunctions.java:49-398` — `Marker.Type {Interpolated,FlatCache,Cache2D,CacheOnce,CacheAllInCell}`, `Noise`, `EndIslandDensityFunction`, `WeirdScaledSampler`, arithmetic combinators
* `net.minecraft.world.level.levelgen.NoiseChunk.java:42-437` — `cellCountXZ/Y`, `cellWidth/Height`, `firstCellX/Z`, `noiseSizeXZ`, `interpolators/cellCaches/wrapped`, `sliceFillingContextProvider`, `fillSlice/selectCellYZ/updateFor*`, `FlatCache/Cache2D/CacheOnce/Interpolated` wrapping, `preliminarySurfaceLevelCache`, `Aquifer.create`, `Beardifier`, `Blender`
* `net.minecraft.world.level.levelgen.NoiseSettings.java:52,56` — `getCellWidth/Height`
* `net.minecraft.world.level.levelgen.NoiseGeneratorSettings.java` + `RandomState.java` — `getOrCreateNoise(Noises.*)`, `router()`, `aquiferRandom()/oreRandom()`
* `net.minecraft.world.level.levelgen.Noises.java` — `SHIFT, CONTINENTALNESS, EROSION, RIDGE, JAGGED, AQUIFER_*, ORE_*`
* `net.minecraft.world.level.levelgen.synth.NormalNoise.java` / `PerlinNoise.java` / `ImprovedNoise.java` / `BlendedNoise.java:30-116` — octave stack, `xzMultiplier=684.412*xzScale`, `BlendedNoise.createUnseeded(0.25,0.125,80,160,8)` etc
* `net.minecraft.world.level.levelgen.Aquifer.java:57-265` — spacing `16×12×16`, Voronoi `2×3×2`, `similarity`, `calculatePressure(barrierNoise)`
* `net.minecraft.world.level.levelgen.OreVeinifier.java` — `VeinType minY/maxY` (stream source for `veinMinY/maxY`)
* `net.minecraft.world.level.levelgen.blending.Blender.java` — `BlendingOutput{alpha, offset}`, `blendOffsetAndFactor`
* `net.minecraft.world.level.levelgen.SurfaceSystem.java:77` — `buildSurface` (veneer, top 3 blocks)
* `net.minecraft.core.QuartPos.java:6-10` — `SECTION_TO_QUARTS_BITS=2`
* `net.minecraft.core.SectionPos.java` — `blockToSectionCoord`
* `net.minecraft.world.level.levelgen.Heightmap.java:38,144` + `net.minecraft.world.level.chunk.status.ChunkStatus.java:28` — `WORLDGEN_HEIGHTMAPS` at `NOISE`

**Project current** (`java/src`, git HEAD at research date):

* `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/RouterField.java:17-61` — `COUNT=15`
* `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/VanillaNoiseRouterSampler.java:22-76` — `4×2×4` quart centres `qx*4+2, qy*8+4`
* `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/SectionNoiseData.java` — `FLAT_LENGTH` (480 vs 960)
* `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/GpuNoiseRouterSampler.java` — per-section `CompletableFuture` 500 ms / 5 s (gap noted in issue comment)
* `java/src/main/java/io/github/lodiffusion/worldgen/QuartNoiseCompute.java:43-72,126-163,161-163` — `local_size 4,4,4`, `MAX_BATCH_SIZE=256`, SSBO 14/15, `glDispatchCompute(N,1,1)`
* `java/src/main/java/com/rhythmatician/lodiffusion/voxy/LodGenerationQueue.java:136-153` — `drainStage(maxBatch, timeout, unit)`
* `java/src/main/java/com/rhythmatician/lodiffusion/voxy/WorldNoiseAccess.java` — `dimKey` threading
* `java/src/main/java/com/rhythmatician/lodiffusion/gpu/BiomePaletteSerializer.java` + `BiomePaletteSSBO.java` — `16 floats/entry, DEQUANT 1/10000`, bindings 12/13, `4*4*96`
* `java/src/main/java/com/rhythmatician/lodiffusion/gpu/TerrainShaperMlpSsbo.java` — `4→128→128→3`
* `graphify-out/graph.json` + `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md` — 7-module map, seams

**Not inspected here but referenced for completeness:** `TerrainProvider` (`overworldOffset/Factor/Jaggedness` splines), `CubicSpline` Hermite, `util/CubicSpline.java`.

---

*End — capture for #92. Next step is #83 lattice gate; this table is the input to sizing `(dimKey, mask, lattice, halo)` and to the invariant proof for `cellWidth=4/cellHeight=8`.*
