# Minecraft 1.21.11 Terrain Signal Lattices

> **Status:** version-bound upstream reference; executable mirror:
> `python/voxel_tree/contracts/terrain_signals.py`
>
> **Upstream revision:** Minecraft 1.21.11 / `26.1-snapshot-11`, decompiled with
> CFR 0.152. The inspected corpus is `external/minecraft-src/src`; no upstream
> Git SHA is available. Re-verify against the Mojang artifact before applying
> this contract to another version.
>
> **Research completion date:** 2026-08-16
>
> **Scope:** upstream signal geometry and the sampling consequences for Voxygen.
> Current implementation facts are linked to code/tests rather than repeated.

## Coordinate and phase contract

All integer division is floor division. This matters at negative coordinates:
the quart containing block `-1` is `-1`, not `0`. A Voxy WorldSection at Level
`L` is always 32 voxels wide and covers `W(L) = 32 * 2^L` blocks per axis.

| lattice | spacing in blocks | phase / ownership | interpolation and cache boundary |
|---|---:|---|---|
| block | 1×1×1 | integer block positions | none |
| quart / biome | 4×4×4 | quart `q` owns `[4q,4q+3]` | categorical nearest-cell; 4³ palette per chunk section |
| Overworld/Nether density | 4×8×4 | cell corners at multiples of `(4,8,4)` | trilinear in `NoiseChunk`; interpolator/cache lifetime is one `NoiseChunk` |
| End density | 8×4×8 | cell corners at multiples of `(8,4,8)` | same algorithm, different lattice |
| heightmap | 1×—×1 | one integer result per XZ block column | threshold crossing; chunk-local 16² cache |
| aquifer anchors | 16×12×16 | `floor((x-5)/16)`, `floor((y+1)/12)`, `floor((z-5)/16)`; seeded jitter inside cells | four-nearest seeded Voronoi plus pressure; per-`NoiseChunk` arrays |
| ore vein decision | 1×1×1 | integer block positions | thresholded noise; no safe continuous interpolation |

Sources: `NoiseSettings.java`, `QuartPos.java`, `NoiseChunk.java`,
`LevelChunkSection.java`, `Aquifer.java`, and `OreVeinifier.java` in the named
corpus. The broader source inventory and DensityFunction algebra are in
[`minecraft-1.21.11-worldgen-seams.md`](minecraft-1.21.11-worldgen-seams.md).

## Dimension profiles

| dimension/preset | generated Y | density cell | density cells across Y | aquifer / veins |
|---|---:|---:|---:|---|
| Overworld | `[-64,320)` (384) | 4×8×4 | 48 | enabled / enabled |
| Nether | `[0,128)` (128) | 4×8×4 | 16 | disabled / disabled |
| End | `[0,128)` (128) | 8×4×8 | 32 | disabled / disabled |

The End is therefore not an Overworld tensor with a shorter Y axis. For a
32-block L0 WorldSection its density shape is 4×8×4 cells, while Overworld and
Nether are 8×4×8. End island density also uses a dedicated seeded simplex
function; Nether has its own blended-noise scales and a ceiling-oriented
surface rule.

## Quantity contract

| quantity | dimensionality and vertical dependence | native frequency statement | exact cache context / safe resampling |
|---|---|---|---|
| temperature, vegetation, continents, erosion, ridges | effectively 2D; Y ignored by their shifted-noise inputs | continuous octave noise, not proven band-limited | cache by dimension, seed/router identity and quart-aligned XZ; evaluate at destination coordinates or low-pass before decimation |
| depth | 3D: terrain offset plus an explicit Y-clamped gradient | dimension density lattice; Y phase is dimension-dependent | one density-cell corner halo; trilinear is exact only for the already sampled piecewise-trilinear representation |
| preliminary surface level | 2D result of a vertical density search | quart-aligned XZ queries; discontinuous height result | `NoiseChunk` quart-keyed cache; recompute or declare a height reducer, never assume linear exactness |
| final density | 3D | density lattice above; upstream represents each cell trilinearly | one upper corner per axis is required to reconstruct a closed tile |
| biome | 3D quart categorical value; some callers intentionally sample a fixed Y | categorical, so Fourier interpolation is inapplicable | nearest or a declared majority/priority reducer; never trilinear |
| worldgen heightmaps | 2D integer column result; vertical dependence is collapsed by the topmost predicate match | block XZ output but derived from density threshold crossings | max/min/mean have different meanings; the reducer is part of the contract |
| aquifer | 3D seeded topology; Overworld only in these presets | 16×12×16 jittered anchor grid plus lower-frequency fluid cells (16×40×16) and lava cells (64×40×64) | recompute, do not interpolate. Exact tiling needs neighbor anchors plus preliminary-surface samples; use a conservative 4×2×4 anchor-cell halo |
| ore veins | 3D; Overworld Y ranges and block thresholds | block-level ridged/noise classification | classify first, then aggregate material; absent in Nether/End presets |
| surface rules | per block column and biome, with Y tests | block-level, discontinuous materials | evaluate after density/biome; aggregation is a material policy |
| carvers | 3D seeded masks | block-level; source chunks in a radius-8 (17×17) neighborhood | not a conditioning-cache resample; exact generation needs the whole source-chunk neighborhood |
| placed features | 3D seeded placements | block-level and sparse | not resample-safe; omit or generate by the upstream placement contract |

### Aquifer halo derivation

For a block query, `Aquifer.NoiseBasedAquifer` searches two X anchor cells,
three Y cells, and two Z cells. Computing an anchor's fluid status then samples
preliminary surface at chunk offsets extending to `(-3,0)` and `(+1,+1)`.
Four anchor cells in X/Z and two in Y are a conservative symmetric cache halo
that includes both the nearest-anchor search and the farthest surface context.
The executable contract deliberately stores this halo in **anchor cells**, not
blocks, so a cache key cannot silently reuse density-cell geometry.

## Per-Level tile derivation

For native spacing `(sx,sy,sz)`, a full WorldSection contains
`(W/sx, W/sy, W/sz)` cells. A closed trilinear tile additionally needs the
upper corner halo. Applying this formula gives:

| Level | footprint `W` | Overworld density cells | End density cells | biome quart cells |
|---:|---:|---:|---:|---:|
| L0 | 32 | 8×4×8 | 4×8×4 | 8×8×8 |
| L1 | 64 | 16×8×16 | 8×16×8 | 16×16×16 |
| L2 | 128 | 32×16×32 | 16×32×16 | 32×32×32 |
| L3 | 256 | 64×32×64 | 32×64×32 | 64×64×64 |
| L4 | 512 | 128×64×128 | 64×128×64 | 128×128×128 |

The full Overworld generated height is only 48 density cells; Nether is 16 and
End is 32. A cache covering a nominal cubic WorldSection must clip or mark
out-of-dimension Y rather than pretending all 32 output voxels contain terrain.

```text
block lattice:       |.|.|.|.|.|.|.|.|
quart/density XZ:    |-------|-------|     spacing 4 (Overworld/Nether)
End density XZ:      |---------------|     spacing 8
L0 WorldSection:     |-------------------------------| 32 blocks
                      8 OW cells / 4 End cells
```

## Resampling proof obligations

Uniform sampling at spacing `s` has Nyquist wavelength `2s`. Thus an 8×8
conditioning grid over one WorldSection has the following hard limits:

| Level | footprint | 8-sample spacing | shortest alias-free wavelength |
|---:|---:|---:|---:|
| L2 | 128 | 16 | 32 |
| L3 | 256 | 32 | 64 |
| L4 | 512 | 64 | 128 |

The upstream octave fields are not band-limited at those cutoffs. Therefore
plain point subsampling from the native grid cannot prove reconstruction of all
frequencies visible at the target voxel spacing (4, 8, and 16 blocks). The
current L2-L4 8×8 feature reduction may be an empirically good predictor, but
it is not an alias-free reconstruction contract. A production decimator must
name its low-pass filter and boundary halo, or record that it is a measured
lossy feature-selection decision.

L0 and L1 model grids preserve the Overworld native XZ density samples
(8 samples over 32 blocks and 16 over 64); they still cannot reconstruct
block-level veins, aquifer topology, thresholded surfaces, carvers, or features
from density samples alone. Those quantities require direct generation,
post-classification aggregation, or an explicitly measured learned residual.

| Level | current conditioning geometry | target voxel | strict sufficiency result |
|---:|---|---:|---|
| L0 | noise 15×8×4×8; biome 8×4×8 | 1 block | preserves Overworld density cells, but halves native biome Y and cannot encode block-frequency/topological stages |
| L1 | noise 15×16×8×16; biome 16×8×16 | 2 blocks | preserves Overworld density cells, but halves native biome Y and cannot prove aquifer/vein reconstruction |
| L2 | 7 continuous channels + biome on 8×8 XZ | 4 blocks | 16-block sample spacing aliases wavelengths below 32 blocks; measured-lossy only |
| L3 | 6 climate channels + biome on 8×8 XZ | 8 blocks | 32-block sample spacing aliases wavelengths below 64 blocks; measured-lossy only |
| L4 | 6 climate channels + biome on 8×8 XZ | 16 blocks | 64-block sample spacing aliases wavelengths below 128 blocks; measured-lossy only |

No Level therefore has a proof of reconstructing every upstream frequency
visible at its voxel scale. L0/L1 preserve the density representation; L2-L4
trade exactness for an empirical feature budget. That distinction is the gate
for any future conditioning-cache tiling decision.

## Conditioning-cache key and invalidation

The minimum safe key is:

```text
(contractRevision, dimension, seed/routerIdentity, signalMask,
 nativeSpacing, interpolationPolicy, haloCells, Level, WorldSectionXYZ)
```

`terrain_signals.cache_key()` encodes every geometry field except
`seed/routerIdentity` and `signalMask`, which belong to the future cache owner.
That owner must add them rather than weakening this key. Tile origin is always
derived by floor-aligned WorldSection coordinates, so negative coordinates do
not require a special case.

## Preserve Futures disposition

This audit permits dimension-aware cache/tile work to proceed, with three hard
guards:

1. End density tensors use 8×4×8 cells, not Overworld's 4×8×4.
2. Aquifer, biome, and density caches retain distinct lattice and halo policy.
3. L2-L4 8×8 conditioning is labelled measured-lossy until a filter plus
   per-Level error bound proves otherwise.
