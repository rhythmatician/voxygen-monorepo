# Minecraft 1.21.11 Worldgen Seams — Version-Bound Upstream Reference

> **Status:** version-bound upstream reference — do not edit to describe a different upstream version
>
> doc-type: external-reference
> source-revision: 26.1-snapshot-11 (CFR 0.152 decompiled, external/minecraft-src/src; no git SHA)
>
> **Upstream project:** Minecraft (Mojang Studios)
>
> **Upstream version:** 1.21.11 (decompiled corpus identifies as 26.1-snapshot-11)
>
> **Decompiler:** CFR 0.152 (headers `Decompiled with CFR 0.152` throughout inspected corpus)
>
> **Exact source revision / commit:** not pinned — inspected corpus is the decompiled `external/minecraft-src/src` tree derived from `26.1-snapshot-11.jar` / `client.jar` present in `external/minecraft-src/` (`26.1-snapshot-11.json` + `cfr.jar` alongside `src/`). No git SHA is established for this upstream; re-verification must compare against the Mojang 1.21.11 / 26.1-snapshot-11 artifacts.
>
> **Artifact hash:** none established for this upstream beyond the local `26.1-snapshot-11.jar` present in `external/minecraft-src/`. The content below is grounded in the inspected `src/` files, not in a jar hash.
>
> **Source corpus inspected:** `external/minecraft-src/src/net/minecraft/world/level/levelgen/` (NoiseRouter, DensityFunction, DensityFunctions, NoiseChunk, NoiseSettings, NoiseGeneratorSettings, NoiseRouterData, RandomState, Noises, synth/NormalNoise, synth/PerlinNoise, synth/ImprovedNoise, synth/SimplexNoise, synth/PerlinSimplexNoise, synth/BlendedNoise, synth/NoiseUtils, blending/Blender, blending/BlendingData, Aquifer, Beardifier, OreVeinifier, SurfaceSystem, SurfaceRules, carver/WorldCarver et al., BiomeSource/Climate, Heightmap, ChunkStatus, etc.) plus `net/minecraft/world/level/chunk/LevelChunkSection`, `net/minecraft/world/level/chunk/status/ChunkStatus`, `net/minecraft/world/level/dimension/DimensionType`, `net/minecraft/core/QuartPos`, `net/minecraft/core/SectionPos`, `net/minecraft/world/level/levelgen/VerticalAnchor`, `WorldGenerationContext`, `GenerationStep`, `levelgen/placement/PlacedFeature`, `levelgen/feature/FeatureCountTracker`, `data/worldgen/NoiseData`, `data/worldgen/TerrainProvider`, `util/CubicSpline`
>
> **Research completion date:** 2026-08-16
>
> **Scope:** This document describes **only** the named Minecraft upstream version. It does not describe current Voxygen implementation, architecture, or decisions.
>
> **Invalidation rule:** A newer Minecraft upstream version requires re-verification against its own decompiled corpus. Do not silently edit this file to describe a different upstream version — create a separately versioned artifact.

---

## 1. ChunkStatus ordering and heightmap validity

**Source:** `net/minecraft/world/level/chunk/status/ChunkStatus.java:28` + `net/minecraft/world/level/levelgen/Heightmap.java:38,144`

```
EMPTY(0) → STRUCTURE_STARTS → STRUCTURE_REFERENCES → BIOMES → NOISE(4) → SURFACE(5) → CARVERS(6) → FEATURES(7) → INITIALIZE_LIGHT → LIGHT → SPAWN → FULL(11)
index = parent.index + 1; isOrAfter/isAfter/isOrBefore/isBefore compare index
```

* Heightmap validity flips at `NOISE` vs `CARVERS`:
  * `WORLDGEN_HEIGHTMAPS = {OCEAN_FLOOR_WG, WORLD_SURFACE_WG}` — valid at `NOISE`
  * `FINAL_HEIGHTMAPS   = {OCEAN_FLOOR, WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES}` — valid at `CARVERS` (mutated by `SURFACE` top-3 blocks and `CARVERS`/`FEATURES`)
* `Heightmap.Types` (6):

| id | name | Usage | predicate |
|---|---|---|---|
| 0 | `WORLD_SURFACE_WG` | WORLDGEN | `NOT_AIR` |
| 1 | `WORLD_SURFACE` | CLIENT | `NOT_AIR` |
| 2 | `OCEAN_FLOOR_WG` | WORLDGEN | `MATERIAL_MOTION_BLOCKING` |
| 3 | `OCEAN_FLOOR` | LIVE_WORLD | `MATERIAL_MOTION_BLOCKING` |
| 4 | `MOTION_BLOCKING` | CLIENT | `blocksMotion \|\| !fluidEmpty` |
| 5 | `MOTION_BLOCKING_NO_LEAVES` | CLIENT | `(blocksMotion \|\| !fluidEmpty) && !LeavesBlock` |

Invariant: generation that stops at `NOISE` has only `WORLDGEN_HEIGHTMAPS`. Claim `height == WORLD_SURFACE` at `NOISE` is false — correct name is `WORLD_SURFACE_WG`.

## 2. NoiseRouter — 15-field record

**Source:** `net/minecraft/world/level/levelgen/NoiseRouter.java:17` (record, `RecordCodecBuilder` of 15 `DensityFunction.HOLDER_HELPER_CODEC fieldOf(name)`) + `NoiseRouterData.java:24`

```java
public record NoiseRouter(
  DensityFunction barrierNoise, fluidLevelFloodednessNoise, fluidLevelSpreadNoise, lavaNoise,
  temperature, vegetation, continents, erosion, depth, ridges,
  preliminarySurfaceLevel, finalDensity,
  veinToggle, veinRidged, veinGap)
```

Grouped by `RouterField` order (ORDINALS 0..14 match codec field names):

| role | fields | cell behavior |
|---|---|---|
| Climate (6) | `temperature, vegetation, continents, erosion, depth, ridges` | mostly 2D quart-cached via `FlatCache(Cache2D(shiftedNoise2d(shiftX,shiftZ,0.25)))` |
| Density (2) | `preliminarySurfaceLevel, finalDensity` | 3D SDF — `finalDensity > 1.5625` decides solid |
| Aquifer (4) | `barrier, fluidLevelFloodedness, fluidLevelSpread, lava` | 3D at varied spacings |
| Veins (3) | `veinToggle, veinRidged, veinGap` | block-level |

`NoiseRouter.mapAll(Visitor)` rewrites the whole tree in one pass. `NoiseBasedChunkGenerator` consumes only this record — downstream (`NoiseChunk`, `SurfaceSystem`, `Aquifer`) reads these 15 fields.

## 3. DensityFunction algebra

**Source:** `net/minecraft/world/level/levelgen/DensityFunction.java:20,107` + `DensityFunctions.java` (all impls) + `NoiseRouterData.java` builder

Interface:

```java
interface DensityFunction {
  double compute(FunctionContext{blockX,Y,Z, Blender});
  void fillArray(double[], ContextProvider);
  DensityFunction mapAll(Visitor);
  double minValue(); double maxValue();
  KeyDispatchDataCodec<? extends DensityFunction> codec();
  // default combinators: clamp/min/max/abs/square/cube/halfNegative/quarterNegative/invert/squeeze
  interface Visitor { default NoiseHolder visitNoise(NoiseHolder h){return h;} default DensityFunction apply(DensityFunction f){return f;} }
  record NoiseHolder(Holder<NoiseParameters> noiseData, @Nullable NormalNoise noise)
  interface SimpleFunction extends DensityFunction { default fillArray loops compute }
}
```

| combinator | class | notes |
|---|---|---|
| `YClampedGradient(fromY,toY,fromValue,toValue)` | `DensityFunctions.YClampedGradient:1034` | Codec bounds `[MIN_Y*2 .. MAX_Y*2]`; `y<fromY→fromValue, y>toY→toValue, else lerp` |
| `Noise(Noises.*, xzScale,yScale)` | `DensityFunctions.Noise` | `NormalNoise` sample |
| `Shift/ShiftA/ShiftB` | wrapper | `flatCache(cache2d(shiftA/B(SHIFT)))` warps 2D climate |
| `FlatCache / Cache2D / CacheAllInCell / CacheOnce / Cache2D` etc. | `Marker` subtypes `356` | memoize per-cell / per-column / per-quartet |
| `Interpolated` | `Marker.Interpolated` | trilinear over cell grid |
| `BlendedNoise(xzScale,yScale,xzFactor,yFactor,smear)` | `synth/BlendedNoise.java:30` | octave-blended base (see §7) |
| `WeirdScaledSampler(input, NoiseHolder, RarityValueMapper)` | `492` | warps `input` coordinates via noise `* RARITY` |
| `RangeChoice / Clamp / Mul / Add / HalfNegative / Squeeze` | arithmetic | `SLOPED_CHEESE = noiseGradientDensity(factor, depth+jaggedness*halfNegative(jagged)) + base3D` |
| `BeardifierOrMarker` | `DensityFunctions.BeardifierOrMarker` | structure influence |
| `HolderHolder` | registry indirection | codec layer |

Default `SimpleFunction.fillArray` loops `cellCountY+1` calling `compute`. `ContextProvider{forIndex(cellYIndex)→FunctionContext, fillAllDirectly}` is the batch path.

Grounded constants (`NoiseRouterData.java:24`):

```
GLOBAL_OFFSET=-0.50375f, SURFACE_DENSITY_THRESHOLD=1.5625, CHEESE_NOISE_TARGET=-0.703125,
NOISE_ZERO=0.390625, ISLAND_CHUNK_DISTANCE=64/4096,
DENSITY_Y_ANCHOR_BOTTOM=-64/TOP=320, DENSITY_Y_BOTTOM=1.5/TOP=-1.5,
OVERWORLD_BOTTOM_SLIDE_HEIGHT=24, BASE_DENSITY_MULTIPLIER=4.0,
BLENDING_FACTOR=10.0, BLENDING_JAGGEDNESS=zero()
```

Tree fragments (same file):

```
BASE_3D_NOISE_OVERWORLD = BlendedNoise.createUnseeded(0.25,0.125,80,160,8)
BASE_3D_NOISE_NETHER    = BlendedNoise.createUnseeded(0.25,0.375,80,60,8)
BASE_3D_NOISE_END       = BlendedNoise.createUnseeded(0.25,0.25,80,160,4)
CONTINENTS/EROSION/RIDGES = flatCache(cache2d(shiftedNoise2d(shiftX,shiftZ,0.25,Noises.*)))
RIDGES_FOLDED = -(abs(abs(weirdness)-0.666)-0.333)*3  (peaksAndValleys)
JAGGED     = noise(JAGGED,1500,0)
DEPTH      = yClampedGradient(-64,320,1.5→-1.5) + offset(GLOBAL_OFFSET)
SLOPED_CHEESE = noiseGradientDensity(factor,depth+jaggedness*halfNegative(jagged)) + base3D
slopedCheeseEnd = endIslands(64/4096,endNoise)+base3D_END
```

## 4. NoiseChunk — cell grid, interpolation, caches

**Source:** `net/minecraft/world/level/levelgen/NoiseChunk.java:42` (`implements FunctionContext, ContextProvider`)

Fields: `cellCountXZ, cellCountY, cellNoiseMinY, cellWidth, cellHeight, firstCellX/Z, firstNoiseX/Z, List<NoiseInterpolator> interpolators, List<CacheAllInCell> cellCaches, Map<DensityFunction,DensityFunction> wrapped, Long2IntMap preliminarySurfaceLevelCache, Aquifer aquifer, DensityFunction preliminarySurfaceLevel, BlockStateFiller blockStateRule, Blender blender, FlatCache blendAlpha/blendOffset, BeardifierOrMarker beardifier`

Cursor walk:
```
forChunk(ChunkAccess, RandomState, Beardifier, NoiseGeneratorSettings, FluidPicker, Blender)
  → cellCountXZ=16/cellWidth, cellCountY=floorDiv(height,cellHeight), cellNoiseMinY=floorDiv(minY,cellHeight)
initializeForFirstCellX → advanceCellX → selectCellYZ → updateForY/X/Z → getInterpolatedState
```

Lattice (via `NoiseSettings.getCellWidth/Height = QuartPos.toBlock(noiseSizeHorizontal/Vertical)`):

| preset | minY | height | sizeH | sizeV | cellWidth | cellHeight | cells/chunk |
|---|---|---|---|---|---|---|---|
| OVERWORLD | -64 | 384 | 1 | 2 | 4 | 8 | 4×48×4=768 |
| NETHER | 0 | 128 | 1 | 2 | 4 | 8 | 4×16×4 |
| END | 0 | 128 | 2 | 1 | 8 | 4 | 2×32×2 |
| CAVES | -64 | 192 | 1 | 2 | 4 | 8 |  |
| FLOATING_ISLANDS | 0 | 256 | 2 | 1 | 8 | 4 |  |

`fillArray + ContextProvider.sliceFillingContextProvider{forIndex(cellYIndex)→ctx, fillAllDirectly}` batches Y. `FlatCache` memoizes per-column, `Cache2D` per-XZ, `CacheAllInCell` per-quartet.

Cost illustration (Overworld): `16×384×16=98,304` blocks vs `768` cells — 128× reduction before per-block trilinear.

## 5. NoiseSettings / NoiseGeneratorSettings — per-dimension presets

**Source:** `net/minecraft/world/level/levelgen/NoiseSettings.java:23` + `NoiseGeneratorSettings.java:35,70`

```java
record NoiseSettings(int minY, int height, int noiseSizeHorizontal, int noiseSizeVertical) {
  getCellWidth()=QuartPos.toBlock(noiseSizeHorizontal); getCellHeight()=QuartPos.toBlock(noiseSizeVertical);
  clampToHeightAccessor(LevelHeightAccessor) → intersect minY..maxY
}
record NoiseGeneratorSettings(NoiseSettings noiseSettings, BlockState defaultBlock, BlockState defaultFluid,
  NoiseRouter noiseRouter, SurfaceRules.RuleSource surfaceRule, List<Climate.ParameterPoint> spawnTarget,
  int seaLevel, boolean disableMobGeneration, boolean aquifersEnabled, boolean oreVeinsEnabled, boolean useLegacyRandomSource)
```

Invariants: `minY%16==0, height%16==0, minY+height≤MAX_Y+1` (`DimensionType` cage). Selected presets (`NoiseGeneratorSettings.java:70`):

| preset | noiseSettings | defaultBlock | defaultFluid | seaLevel | aquifers | oreVeins | legacy | surfaceRule |
|---|---|---|---|---|---|---|---|
| OVERWORLD | `OVERWORLD_NOISE_SETTINGS` | STONE | WATER | 63 | true | true | false | `SurfaceRuleData.overworld()` |
| NETHER | `NETHER_NOISE_SETTINGS` | NETHERRACK | LAVA | 32 | false | false | true | `nether()` |
| END | `END_NOISE_SETTINGS` | END_STONE | AIR | 0 | false | false | true | `end()` |

`isAquifersEnabled() = aquifersEnabled && !DEBUG_DISABLE_AQUIFERS`; `oreVeinsEnabled()` similarly. The Nether/End trees differ in `BlendedNoise` frequencies and surface rules, not just params.

## 6. RandomState — seeded wiring

**Source:** `net/minecraft/world/level/levelgen/RandomState.java:28,47,132` + `PositionalRandomFactory.java:5` + `DensityFunction.java:107`

```java
final class RandomState {
  PositionalRandomFactory random, aquiferRandom=fromHash("aquifer"), oreRandom=fromHash("ore");
  HolderGetter<NoiseParameters> noises; NoiseRouter router; Climate.Sampler sampler; SurfaceSystem surfaceSystem;
  Map<ResourceKey<NoiseParameters>,NormalNoise> noiseInstances = ConcurrentHashMap;
  Map<Identifier,PositionalRandomFactory> positionalRandoms = ConcurrentHashMap;
  static create(NoiseGeneratorSettings, HolderGetter<NoiseParameters>, seed) {
    random = settings.getRandomSource().newInstance(seed).forkPositional();
    aquiferRandom=fromHash("aquifer").forkPositional(); oreRandom=fromHash("ore").forkPositional();
    surfaceSystem=new SurfaceSystem(this, defaultBlock, seaLevel, random);
    router=settings.noiseRouter().mapAll(noiseWiringHelper); // Visitor
    sampler=new Climate.Sampler(router.{temperature,vegetation,continents,erosion,depth,ridges} stripped, spawnTarget)
  }
  NormalNoise getOrCreateNoise(key) → noiseInstances.computeIfAbsent(key, k→Noises.instantiate(noises, random, k))
}
```

`PositionalRandomFactory`:
```java
interface PositionalRandomFactory { fromHashOf(Identifier) → fromHashOf(string); fromHashOf(String); fromSeed(long); at(int x,int y,int z); at(BlockPos); }
```

Visitor `NoiseWiringHelper implements DensityFunction.Visitor`:

* `visitNoise(NoiseHolder)` → `getOrCreateNoise(key)` except `TEMPERATURE_NETHER/VEGETATION_NETHER` → `NormalNoise.createLegacyNetherBiome(LegacyRandomSource(seed+off), params)`
* `BlendedNoise → withNewRandom(terrainRandom)` where `terrainRandom = legacyInstance(0)` if `useLegacy else random.fromHashOf("terrain")`
* `EndIslandDensityFunction → new EndIslandDensityFunction(seed)` (SimplexNoise path)
* Then `router = settings.noiseRouter().mapAll(helper)` — exactly once, not per chunk.
* Second visitor strips `HolderHolder/Marker` unwrap for `Climate.Sampler`.

`DensityFunction.NoiseHolder.CODEC = NoiseParameters.CODEC.xmap(data→new NoiseHolder(data,null), NoiseHolder::noiseData)`.

## 7. Noise stack — NormalNoise / PerlinNoise / ImprovedNoise / SimplexNoise / BlendedNoise

**Source:** `synth/NormalNoise.java:30`, `synth/PerlinNoise.java:37`, `synth/ImprovedNoise.java:15`, `synth/SimplexNoise.java:9`, `synth/BlendedNoise.java:30`, `synth/PerlinSimplexNoise.java:20`, `synth/NoiseUtils.java:7`

* `NormalNoise` (`INPUT_FACTOR=1.0181268882175227, TARGET_DEVIATION=1/3`):
  ```java
  class NormalNoise { PerlinNoise first, second; double valueFactor, maxValue;
    NormalNoise(RandomSource r, NoiseParameters p, boolean useNewInit){
      first = useNewInit? PerlinNoise.create(r, firstOctave, amplitudes) : createLegacyForLegacyNetherBiome(r,…);
      second = same second instance at 1.018×; valueFactor=(1/6)/expectedDeviation(maxOctave-minOctave); maxValue=(first.maxValue()+second.maxValue())*valueFactor; }
    double getValue(x,y,z) → (first.getValue(x,y,z)+second.getValue(x*INPUT_FACTOR,…))*valueFactor;
  }
  ```

* `PerlinNoise` (`ROUND_OFF=0x2000000, @Nullable ImprovedNoise[] noiseLevels, int firstOctave, DoubleList amplitudes, lowestFreqValueFactor/InputFactor, maxValue`):
  * Factories: `create(RandomSource, firstOctave, DoubleList)`, `create(List<Integer> octaveSet)→makeAmplitudes(octaveSet)` where `makeAmplitudes` builds `amplitudes[octave+lowFreq]=1.0`, `firstOctave=-lowFreq`.
  * `PerlinNoise(pair, useNewInit)` allocates `ImprovedNoise` per non-zero amplitude slot; zero slots remain null.

* `ImprovedNoise` (`byte[256] p, double xo/yo/zo`):
  ```java
  ImprovedNoise(RandomSource r){ xo=r.nextDouble()*256; yo=*256; zo=*256; for i 0..255 p[i]=i; for i 0..255 swap p[i]↔p[i+rand(256-i)]; }
  double noise(_x,_y,_z, yScale,yFudge){ x=_x+xo; xf=floor(x); xr=x-xf; if(yScale!=0) yrFudge=floor(min(yr,yFudge)/yScale+1e-7)*yScale; return sampleAndLerp(xf,yf,zf, xr, yr-yrFudge, zr, yr); } // 8-corner gradDot + Mth.lerp3
  static gradDot(hash,x,y,z)→ dot(GRADIENT[hash&0xF],x,y,z);
  ```
  `p` is `byte`, not `int` (contrast Simplex `int[512]`).

* `SimplexNoise` (`int[16][3] GRADIENT, SQRT_3, F2=0.5*(SQRT_3-1), G2=(3-SQRT_3)/6, int[512] p, xo/yo/zo`):
  * `dot(g,x,y,z)=g[0]*x+g[1]*y+g[2]*z`; `getValue(xin,yin)` 2-D 70*sum(n0+n1+n2) at skew `F2/G2`; `getValue(xin,yin,zin)` 3-D 4 corners with `F3=1/3,G3=1/6`.
  * Dedicated to `DensityFunctions.EndIslandDensityFunction` and `PerlinSimplexNoise`; not used for overworld `ImprovedNoise` paths.

* `BlendedNoise` (`DataCodec xzScale/yScale/xzFactor/yFactor/smearScaleMultiplier, KeyDispatchDataCodec`):
  ```java
  class BlendedNoise implements DensityFunction.SimpleFunction {
    PerlinNoise minLimitNoise,maxLimitNoise,mainNoise; double xzMultiplier=684.412*xzScale, yMultiplier=684.412*yScale;
    BlendedNoise(RandomSource r, xzScale,yScale,xzFactor,yFactor,smear) : this(createLegacyForBlendedNoise(r,-15..0), createLegacy(-15..0), createLegacy(-7..0), …);
    withNewRandom(terrainRandom)→new BlendedNoise(terrainRandom, xzScale,…);
    compute(ctx): limitX=blockX*xzMult, mainX=limitX/xzFactor, limitSmear=yMult*smear, mainNoiseValue=Σ0..7 ImprovedNoise?.noise(wrap(mainX*pow),…,mainSmear*pow,mainY*pow)/pow
      factor=(mainNoiseValue/10+1)/2, blendMin/Max for i 0..15 with wrap + yScalePow, return clampedLerp(factor, blendMin/512, blendMax/512)/128;
  }
  ```

* `PerlinSimplexNoise` — pre-1.18 simplex blend, now only for legacy datapacks: similar octave wiring over `SimplexNoise[]` with `consumeCount(262)` parity and `highestFreqValueFactor=1/(2^octaves-1)`.

* `NoiseUtils`: `biasTowardsExtreme(noise,factor)=noise+sin(π*noise)*factor/π`.

## 8. Noise registry and bootstrap table

**Source:** `net/minecraft/world/level/levelgen/Noises.java:15,82` + `data/worldgen/NoiseData.java:11`

`Noises` — 50+ `ResourceKey<NoiseParameters>` (representative):

```
TEMPERATURE/VEGETATION/CONTINENTALNESS/EROSION (+_LARGE), RIDGE, SHIFT(offset),
TEMPERATURE_NETHER/VEGETATION_NETHER, AQUIFER_BARRIER/FLUID_LEVEL_FLOODEDNESS/LAVA/SPREAD,
PILLAR/RARENESS/THICKNESS, SPAGHETTI_2D/_ELEVATION/_MODULATOR/_THICKNESS/3D_1/2/_RARITY/_THICKNESS/ROUGHNESS,
CAVE_ENTRANCE/LAYER/CHEESE, ORE_VEININESS/_A/_B/GAP, NOODLE/_THICKNESS/_RIDGE_A/_B,
JAGGED, SURFACE/SURFACE_SECONDARY/CLAY_BANDS_OFFSET, BADLANDS_PILLAR/ROOF/SURFACE,
ICEBERG_PILLAR/ROOF/SURFACE, SWAMP, CALCITE/GRAVEL/POWDER_SNOW/PACKED_ICE/ICE,
SOUL_SAND_LAYER/GRAVEL_LAYER/PATCH, NETHERRACK/NETHER_WART/NETHER_STATE_SELECTOR
instantiate(noises, factory, key)→NormalNoise.create(factory.fromHashOf(key.identifier()), holder.value())
```

`NoiseData.bootstrap` — concrete `NoiseParameters(firstOctave, amplitudes)` (zeros preserved):

```
biomeNoises(offset): TEMPERATURE -10+off 1.5,0,1,0,0,0; VEGETATION -8+off 1,1,0,0,0,0;
  CONTINENTALNESS -9+off 1,1,2,2,2,1,1,1,1; EROSION -9+off 1,1,0,1,1 ; RIDGE -7 1,2,1,0,0,0; SHIFT -3 1,1,1,0
AQUIFER_BARRIER -3 1; FLUID_LEVEL_FLOODEDNESS -7 1; LAVA -1 1; FLUID_LEVEL_SPREAD -5 1
JAGGED -16 1×16; SURFACE -6 1,1,1; CAVE_CHEESE -8 0.5,1,2,1,2,1,0,2,0; ORE_VEININESS -8 1; PATCH -5 1,0×4,0.0133
NETHERRACK -3 1,0,0,0.35; NETHER_WART -3 1,0,0,0.9
```

Full table is in `NoiseData.java:22` — zero-amplitude slots are significant (allocation skipped per §7 but list index preserved).

## 9. TerrainProvider / CubicSpline

**Source:** `data/worldgen/TerrainProvider.java:11,21,30,35` + `util/CubicSpline.java:41`

`TerrainProvider` builds nested `continents→erosion→ridge→weirdness` splines:

```java
TerrainProvider { overworldOffset(continents,erosion,ridges,amplified), overworldFactor(…,weirdness,…), overworldJaggedness(…,weirdness,…)
  // each returns CubicSpline.Coordinate + Multipoint builder(coordinate, AMPLIFIED_*transform) linearExtend
  // NoiseRouterData registers as: splineWithBlending(constant(GLOBAL_OFFSET)+spline(overworldOffset), blendOffset) etc.
}
```

`CubicSpline.java:41` — pure math Hermite `lerp(t)+t(1-t)lerp(t,a,b)` with `Constant/Multipoint` builder. Generation that ports splines exactly (zero error) and learns residual `BlendedNoise+caves` separates concerns.

## 10. Coordinates and lattices

**Source:** `net/minecraft/core/QuartPos.java:6` + `net/minecraft/core/SectionPos.java:28` + `net/minecraft/world/level/levelgen/blending/BlendingData.java:51`

* `QuartPos`:
  ```java
  final class QuartPos { BITS=2, SIZE=4, MASK=3, SECTION_TO_QUARTS_BITS=2;
    fromBlock(b)=b>>2; toBlock(q)=q<<2; fromSection(s)=s<<2; toSection(q)=q>>2; }
  ```
  Noise lattice and `Climate.ParameterPoint` are quart-addressed.

* `SectionPos` (extends `Vec3i`):
  ```
  SECTION_BITS=4, SECTION_SIZE=16, MASK=15; PACKED 22+20+22: X_MASK 0x3FFFFF, Y 0xFFFFF, Z 0x3FFFFF;
  asLong(x,y,z)=((x&0x3FFFFF)<<42)|((z&0x3FFFFF)<<20)|(y&0xFFFFF);
  blockToSectionCoord(b)=b>>4, sectionToBlockCoord(s)=s<<4; range ±2M sections (±33M blocks)
  ```

* `VerticalAnchor` (`VerticalAnchor.java:21`) + `WorldGenerationContext.java:9`:
  ```java
  interface VerticalAnchor { absolute(int y)→Absolute(y){resolveY→y}
    aboveBottom(int off)→AboveBottom(off){resolveY→minGenY+off}
    belowTop(int off)→BelowTop(off){resolveY→minGenY+genDepth-1-off}; BOTTOM=aboveBottom(0), TOP=belowTop(0); }
  class WorldGenerationContext { int minY,height; WorldGenerationContext(ChunkGenerator gen, LevelHeightAccessor acc){ minY=max(acc.minY, gen.minY); height=min(acc.height, gen.genDepth); } }
  ```
  All `YClampedGradient`, `SurfaceRules YConditionSource/WaterConditionSource`, bedrock `verticalGradient` resolve via `VerticalAnchor.resolveY(ctx)`, not literal `-64`.

* `LevelChunkSection.java:19`: `SECTION_WIDTH=16, SECTION_HEIGHT=16, SECTION_SIZE=4096, BIOME_CONTAINER_BITS=2, PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes`; chunk = `ceil(height/16)` sections indexed by `SectionPos.sectionToBlockCoord(sectionY)/16 + minSection`.

* `DimensionType.java:41`: `record DimensionType(..., int minY, int height, int logicalHeight, ...)` `BITS_FOR_Y=26, Y_SIZE=(1<<26)-32, MAX_Y=(Y_SIZE>>1)-1, MIN_Y=MAX_Y-Y_SIZE+1, invariants height≥16, height%16==0, minY%16==0, minY+height≤MAX_Y+1`.

## 11. Climate / BiomeSource

**Source:** `net/minecraft/world/level/biome/Climate.java:44` + `net/minecraft/world/level/biome/MultiNoiseBiomeSource.java:33` + `net/minecraft/world/level/levelgen/Blender.java:48`

```
Climate { PARAMETER_COUNT=7, QUANTIZATION_FACTOR=10000f;
  TargetPoint target(t,h,c,e,d,w)→TargetPoint(quantize(t),…); quantizeCoord(f)=round(f*10000);
  Parameter {min,max quantized ints; point(float) / point(min,max)}; ParameterPoint CODEC intRange floats;
  Sampler { DensityFunction t,h,c,e,d,w; spawnTarget List<ParameterPoint>;
    sample(quartX,quartY,quartZ)→target(t.compute(blockContext),…); findSpawnPosition(radialSearch 2048/512 then 512/32) }
  ParameterList<T> { List<Pair<ParameterPoint,T>> values; getValue(TargetPoint)→nearest ParameterPoint by distance (O(n)) } }
MultiNoiseBiomeSource extends BiomeSource {
  DIRECT_CODEC=ParameterList.codec(ENTRY_CODEC).fieldOf("biomes"); PRESET_CODEC=MultiNoiseBiomeSourceParameterList.CODEC.fieldOf("preset");
  CODEC=mapEither(DIRECT_CODEC,PRESET_CODEC); Either<ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters;
  getNoiseBiome(quartX,quartY,quartZ, sampler)→getNoiseBiome(sampler.sample(quartX,quartY,quartZ)) → parameters().findValue(target);
}
```

Biome lookup is quart-addressed (`getNoiseBiome(x>>2,y>>2,z>>2)` via `Climate.Sampler.sample`) and reuses the 6 climate `DensityFunction`s — no extra noise.

## 12. Aquifer

**Source:** `net/minecraft/world/level/levelgen/Aquifer.java:33,55`

```java
interface Aquifer { BlockState computeSubstance(FunctionContext,float density); boolean shouldScheduleFluidUpdate();
  static createDisabled(FluidPicker f)→ density>0?null: f.computeFluid(x,y,z).at(y); shouldScheduleFluidUpdate→false;
  static create(NoiseChunk,ChunkPos,NoiseRouter,PositionalRandomFactory,int minBlockY,int yBlockSize, FluidPicker)→NoiseBasedAquifer;
  interface FluidPicker { FluidStatus computeFluid(x,y,z); } record FluidStatus(int fluidLevel, BlockState fluidType){ BlockState at(y)→y<=fluidLevel?fluidType:AIR }
  class NoiseBasedAquifer implements Aquifer {
    X_RANGE=10,Y_RANGE=9,Z_RANGE=10, X_SEPARATION=6,Y_SEPARATION=3,Z_SEPARATION=6,
    X_SPACING=16,Y_SPACING=12,Z_SPACING=16, X_SHIFT=4,Z_SHIFT=4,
    MAX_REASONABLE_DISTANCE=11, SAMPLE_OFFSET -5,1,-5, MIN_CELL_SAMPLE 0,-1,0, MAX 1,1,1 (12 samples),
    FLOWING_UPDATE_SIMILARITY=similarity(100,144),
    NoiseChunk noiseChunk; DensityFunction barrier,fluidLevelFloodedness,fluidLevelSpread,lava,erosion,depth;
    FluidStatus[] aquiferCache[900]; long[] aquiferLocationCache; FluidPicker globalFluidPicker; boolean shouldScheduleFluidUpdate;
  }}
```

`NoiseBasedAquifer` samples a `10×9×10` grid at `16×12×16` spacing, 12-neighbor distance-weighted `similarity`, with `aquiferCache` per cell. `createDisabled` (flat `FluidPicker`) is used when `aquifersEnabled==false` (Nether/End) or `DEBUG_DISABLE_AQUIFERS`.

## 13. Beardifier

**Source:** `net/minecraft/world/level/levelgen/Beardifier.java:30`

```java
class Beardifier implements DensityFunctions.BeardifierOrMarker {
  BEARD_KERNEL_RADIUS=12, SIZE=24, KERNEL=float[13824] = exp(-(dx²+dy²+dz²)/16) for dx,dy,dz -12..11;
  EMPTY=Beardifier(List.of(),List.of(),null);
  static forStructuresInChunk(StructureManager, ChunkPos)→ scan startsForStructure(chunk, s→terrainAdaptation!=NONE) → Rigid{box,adjustment,groundLevelDelta}+JigsawJunction else EMPTY;
  // TerrainAdjustment: NONE→0, BURY→clampedMap(length(dx,dy/2,dz),0,6,1,0),
  //  BEARD_THIN/BOX→beard(dx,dy,dz,yToGround)*0.8, ENCAPSULATE→bury(dx/2,dy/2,dz/2)*0.8; junction *0.4
  // beard: if |dx|,|dy|,|dz|≤12 → dy0=yToGround+0.5, value=-dy0*invSqrt(distSqr/2)/2 * KERNEL[zi*576+xi*24+yi]
}
```

Modifies `finalDensity` near structures; `EMPTY` is zero contribution. Depends on `StructureManager` chunk neighborhood scan.

## 14. Blender / BlendingData

**Source:** `net/minecraft/world/level/levelgen/blending/Blender.java:48` + `blending/BlendingData.java:51`

```java
class Blender {
  EMPTY=new Blender(empty,empty){ blendOffsetAndFactor→(1.0,0.0); blendDensity→identity; getBiomeResolver→identity }
  SHIFT_NOISE=NormalNoise.create(Xoroshiro(42), DEFAULT_SHIFT)
  HEIGHT_BLENDING_RANGE_CELLS=QuartPos.fromSection(7)-1, HEIGHT_BLENDING_RANGE_CHUNKS=toSection(HEIGHT+3) // 7 sections
  DENSITY_BLENDING_RANGE_CELLS=2, DENSITY_BLENDING_RANGE_CHUNKS=toSection(5)
  Long2ObjectOpenHashMap<BlendingData> heightAndBiomeBlendingData, densityBlendingData;
  static of(WorldGenRegion region): if DEBUG_DISABLE_BLENDING||region==null →EMPTY; if !isOldChunkAround(center,HEIGHT_RANGE_CHUNKS)→EMPTY; scan dx,dz within square→ getOrUpdateBlendingData; if both empty→EMPTY;
  blendOffsetAndFactor(blockX,blockZ): cellX=fromBlock(blockX), fixedHeight=getBlendingDataValue(cellX,0,cellZ, getHeight); if fixedHeight!=MAX_VALUE→(0.0,heightToOffset(fixedHeight)); else weighted 1/dist⁴ avg over iterateHeights; blendDensity similar over densityBlendingData ±1Y
}
class BlendingData {
  BLENDING_DENSITY_FACTOR=0.1, CELL_WIDTH=4, CELL_HEIGHT=8, CELL_RATIO=2, SOL/ AIR 1.0/-1.0, CELLS_PER_SECTION_Y=2, QUARTS_PER_SECTION=4;
  CELL_HORIZONTAL_MAX_INDEX_INSIDE=3, OUTSIDE=4, CELL_COLUMN_INSIDE_COUNT=7, OUTSIDE_COUNT=9, CELL_COLUMN_COUNT=16;
  SURFACE_BLOCKS=[PODZOL,GRAVEL,GRASS_BLOCK,STONE,COARSE_DIRT,SAND,RED_SAND,MYCELIUM,SNOW_BLOCK,TERRACOTTA,DIRT], NO_VALUE=MAX_VALUE;
  double[] heights[16]; List<List<Holder<Biome>>> biomes; double[][] densities[16][];
  getHeightAtXZ: hasPrimedHeightmap(WORLD_SURFACE_WG)? min(getHeight, maxY):maxY; getDensityColumn per CELL_HEIGHT;
}
```

Upgrade worlds only (1.12→1.18 old octaves → new octaves). New worlds: `Blender.EMPTY` identity, zero cost.

## 15. SurfaceSystem / SurfaceRules

**Source:** `net/minecraft/world/level/levelgen/SurfaceSystem.java:37,77` + `SurfaceRules.java:56` + `data/worldgen/SurfaceRuleData.java:18`

`SurfaceSystem`:

```java
class SurfaceSystem { BlockState defaultBlock; int seaLevel; BlockState[] clayBands[192]; NormalNoise clayBandsOffsetNoise, surfaceNoise, surfaceSecondaryNoise, badlandsPillarNoise, badlandsPillarRoofNoise, badlandsSurfaceNoise, icebergPillarNoise, icebergPillarRoofNoise, icebergSurfaceNoise; PositionalRandomFactory noiseRandom;
  SurfaceSystem(RandomState r, defaultBlock, seaLevel, noiseRandom){ … getOrCreateNoise(CLAY_BANDS_OFFSET…); clayBands=generateBands(fromHash("clay_bands")); }
  void buildSurface(RandomState,BiomeManager,Registry<Biome>,boolean useLegacyRandom,WorldGenerationContext,ChunkAccess protoChunk,NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource){
    for x 0..15, z 0..15: blockX=minBlockX+x, startingHeight=getHeight(WORLD_SURFACE_WG,x,z)+1; surfaceBiome=getBiome(x, useLegacyRandom?0:startingHeight, z);
      context=new SurfaceRules.Context(this,randomState,protoChunk,noiseChunk,biomeGetter,biomes,generationContext); rule=ruleSource.apply(context);
      column=BlockColumn(protoChunk) getBlock(y)/setBlock(y)→insideBuildHeight + markPosForPostprocessing if fluid;
      for y=height downTo minY: stoneAboveDepth/waterHeight/nextCeilingStoneY bookkeeping; context.updateXZ(blockX,blockZ); context.updateY(++stoneAboveDepth,stoneBelowDepth,waterHeight,blockX,y,blockZ);
        if old==defaultBlock && (state=rule.tryApply(x,y,z))!=null column.setBlock(y,state);
  }
  getSurfaceDepth(x,z)→ (int)(surfaceNoise.getValue(x,0,z)*2.75+3.0+ noiseRandom.at(x,0,z).nextDouble()*0.25)
  static generateBands(random): clayBands[192] TERRACOTTA, ORANGE every 5+1, makeBands YELLOW/BROWN/RED baseWidth 1/2/1, white 9..random.nextInt(16)+4 with LIGHT_GRAY halo
}
```

`SurfaceRules`:

```java
class SurfaceRules {
  ON_FLOOR=stoneDepthCheck(0,false,FLOOR); UNDER_FLOOR=stoneDepthCheck(0,true,FLOOR);
  DEEP_UNDER_FLOOR=stoneDepthCheck(0,true,6,FLOOR); VERY_DEEP_UNDER_FLOOR=stoneDepthCheck(0,true,30,FLOOR);
  ON_CEILING/UNDER_CEILING similarly CEILING;
  ConditionSource: StoneDepthCheck(offset,addSurfaceDepth,secondaryDepthRange,surfaceType), YConditionSource(VerticalAnchor,…), WaterConditionSource(offset,multiplier), BiomeConditionSource(List<ResourceKey<Biome>>), NoiseThresholdConditionSource(ResourceKey<NoiseParameters>,min,max), VerticalGradientConditionSource(Identifier,trueAtAndBelow,falseAtAndAbove), NotConditionSource
  RuleSource: SequenceRuleSource(List<RuleSource>), ConditionRuleSource(ConditionSource,RuleSource), BlockRuleSource(BlockState)
}
```

`SurfaceRuleData`: 34 `makeStateRule` constants (`AIR,BEDROCK,WHITE_TERRACOTTA,ORANGE_TERRACOTTA,TERRACOTTA,RED_SAND,RED_SANDSTONE,STONE,DEEPSLATE,DIRT,PODZOL,COARSE_DIRT,MYCELIUM,GRASS_BLOCK,CALCITE,GRAVEL,SAND,SANDSTONE,PACKED_ICE,SNOW_BLOCK,MUD,POWDER_SNOW,ICE,WATER,LAVA,NETHERRACK,SOUL_SAND/SOIL,BASALT,BLACKSTONE,WARPED_*,CRIMSON_*,ENDSTONE`) and `overworld()=overworldLike(true,false,true)` / `nether()` / `end()` trees. Veneer only — top 1–3 blocks per column; `WORLD_SURFACE_WG` already valid before `buildSurface`.

## 16. OreVeinifier / Carvers

**Source:** `net/minecraft/world/level/levelgen/OreVeinifier.java:15` + `net/minecraft/world/level/levelgen/carver/WorldCarver.java:50`

* OreVeinifier:
  ```
  VEININESS_THRESHOLD=0.4f, EDGE_ROUNDOFF_BEGIN=20, MAX_EDGE_ROUNDOFF=0.2, VEIN_SOLIDNESS=0.7f,
  MIN_RICHNESS=0.1f, MAX_RICHNESS=0.3f, MAX_RICHNESS_THRESHOLD=0.6f, CHANCE_OF_RAW_ORE_BLOCK=0.02f, SKIP_ORE_IF_GAP_NOISE_IS_BELOW=-0.3f
  VeinType COPPER(COPPER_ORE, RAW_COPPER_BLOCK, GRANITE, 0..50), IRON(DEEPSLATE_IRON_ORE, RAW_IRON_BLOCK, TUFF, -60..-8)
  create(veinToggle, veinRidged, veinGap, oreRandomFactory) → BlockStateFiller:
    ridged=abs(veinToggle.compute); edgeRoundoff=clampedMap(min(distTop,distBottom),0,20,-0.2,0);
    if ridged+edgeRoundoff<0.4 → default; if rand.nextFloat()>0.7 → default; if veinRidged.compute≥0 → default;
    richness=clampedMap(ridged,0.4,0.6,0.1,0.3); if rand<richness && veinGap> -0.3 → 2% rawOre else ore else filler
  ```

* WorldCarver:
  ```
  abstract WorldCarver<C extends CarverConfiguration> { CAVE, NETHER_CAVE, CANYON (registry BuiltInRegistries.CARVER);
    AIR, CAVE_AIR, WATER, LAVA; getRange()=4 (9×9 chunk neighborhood); CarvingMask + CarvingContext;
    abstract carve(CarvingContext, C, ChunkAccess, BiomeGetter, RandomSource, Aquifer, ChunkPos, CarvingMask);
    carveEllipsoid → for each block in ellipsoid carveBlock → writes CAVE_AIR, respects CarvingMask }
  ```

## 17. NoiseBasedChunkGenerator / ChunkGenerator placement

**Source:** `net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java:83,113,149,217,265` + `net/minecraft/world/level/chunk/ChunkGenerator.java:17`

```
NoiseBasedChunkGenerator extends ChunkGenerator { Holder<NoiseGeneratorSettings> settings; Supplier<FluidPicker> globalFluidPicker=memoize(createFluidPicker(settings))
  createFluidPicker: FluidStatus lava(-54,LAVA), sea(seaLevel, defaultFluid), empty(MIN_Y*2,AIR); y<min(-54,seaLevel)?lava:sea
  createBiomes(randomState,blender,structureManager,protoChunk)→supplyAsync(doCreateBiomes, "init_biomes"): noiseChunk=forChunk(…Beardifier.forStructuresInChunk…), biomeResolver=BelowZeroRetrogen.getBiomeResolver(blender.getBiomeResolver(biomeSource)), fillBiomesFromNoise(cachedClimateSampler)
  getBaseHeight(x,z,type,heightAccessor,randomState)→iterateNoiseColumn(…,type.isOpaque()).orElse(minY)
  iterateNoiseColumn: clamp NoiseSettings, noiseChunk=new NoiseChunk(1,…,BeardifierMarker.INSTANCE,…,Blender.empty()) single-cell scan initializeForFirstCellX/advanceCellX(0)/selectCellYZ/updateForY/X/Z/getInterpolatedState top-down
  buildSurface(WorldGenRegion)→WorldGenerationContext(this,region)+Blender.of(region)+surfaceSystem.buildSurface
  fillFromNoise(blender,randomState,structureManager,centerChunk)→supplyAsync(doFill, "wgen_fill_noise"): acquire LevelChunkSections, doFill: noiseChunk.forChunk, Heightmap OCEAN_FLOOR_WG/WORLD_SURFACE_WG, initializeForFirstCellX, for cellX,cellZ,cellY top-down updateForY/X/Z → state=getInterpolatedState()??defaultBlock; setBlockState; oceanFloor/worldSurface update; if aquifer.shouldScheduleFluidUpdate && !fluidEmpty markPosForPostprocessing
  applyCarvers: 17×17 neighborhood (range 8), CarvingContext+CarvingMask, per sourceChunk BiomeGenerationSettings.getCarvers()
}
ChunkGenerator { BiomeSource biomeSource; abstract MapCodec<? extends ChunkGenerator> codec(); abstract int getGenDepth()/getMinY(); }
```

## 18. GenerationStep / PlacedFeature / FeatureCountTracker

**Source:** `net/minecraft/world/level/levelgen/GenerationStep.java:12` + `levelgen/placement/PlacedFeature.java:36` + `levelgen/feature/FeatureCountTracker.java:37`

```
GenerationStep { enum Decoration { RAW_GENERATION, LAKES, LOCAL_MODIFICATIONS, UNDERGROUND_STRUCTURES, SURFACE_STRUCTURES, STRONGHOLDS, UNDERGROUND_ORES, UNDERGROUND_DECORATION, FLUID_SPRINGS, VEGETAL_DECORATION, TOP_LAYER_MODIFICATION } }
record PlacedFeature(Holder<ConfiguredFeature<?,?>> feature, List<PlacementModifier> placement){
  DIRECT_CODEC=feature+placement list; place → placeWithContext(new PlacementContext(level,generator,Optional.empty()));
  placeWithContext: Stream<BlockPos> placements=Stream.of(origin); for each modifier placements=flatMap(modifier.getPositions(context,random,p)); for each pos feature.value().place(level,generator,random,pos); DEBUG_FEATURE_COUNT→FeatureCountTracker.featurePlaced
}
class FeatureCountTracker { LoadingCache<ServerLevel,LevelData> data = CacheBuilder.weakKeys().expireAfterAccess(5,MINUTES).build(... synchronized Object2IntMap<FeatureData> + MutableInt chunksWithFeatures);
  chunkDecorated(level)→increment; featurePlaced(level,feature,Optional<PlacedFeature> topFeature)→computeInt(FeatureData(feature,topFeature), old→old==null?1:old+1); }
```

`PlacedFeature.place` runs during `ChunkStatus.FEATURES`; `FeatureCountTracker` is `DEBUG_FEATURE_COUNT` gated, debug-only.

---
*This document is the version-pinned record for Minecraft 1.21.11 worldgen seams as inspected 2026-08-16. Newer Minecraft versions require a separately versioned document.*
