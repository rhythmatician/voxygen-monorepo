# Minecraft 1.21.11 Feature Pipeline & Voxy Version Churn -- Research Evidence for Wayfinder #235

> **Status:** wayfinder:research -- version-bound upstream research -- do not silently edit to describe a different upstream version.
>
> doc-type: external-reference
> source-revision: Minecraft 26.1-snapshot-11 (CFR 0.152 decompiled corpus `external/minecraft-src/`, jar SHA-256 `556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822`, no git SHA) + Voxy 0.2.11-alpha (`337b919d` on `dev`, jar SHA-256 `63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c`) + Voxy current `dev` head `02dfb1b7` (2026-08-29) + Fabric API 0.143.11 + Voxy 11-branch cross-version evidence (`origin/master..origin/dev` 2024-07-26 .. 2026-08-29)
>
> **Wayfinder map:** #22 · ticket #235 (this research) · informs #85 (`worldgen-partition`) and #234 (future feature-generation skill) · referenced by #233 (oracle contract) once available
>
> **Companion documents (not re-stated here):**
> - [`minecraft-1.21.11-worldgen-dag-overworld-nether-end.md`](minecraft-1.21.11-worldgen-dag-overworld-nether-end.md) -- stage DAG per dimension (sibling DAG view; L0–L4 column is observation, not partition)
> - [`../reference/upstream/minecraft-1.21.11-worldgen-seams.md`](../reference/upstream/minecraft-1.21.11-worldgen-seams.md) -- per-class internals (NoiseRouter, NoiseChunk, etc.)
> - [`../reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md`](../reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md) -- pinned Voxy storage contract
> - [`port-vanilla-batch-subtree-sharing.md`](port-vanilla-batch-subtree-sharing.md) -- Layer-2 tile/cache-key math
> - [`../reference/upstream/VOXY-FORMAT.md`](../reference/upstream/VOXY-FORMAT.md) -- pinned Voxy on-disk format
>
> **Current research pass:** 2026-08-28 (cross-Minecraft-version responsibility diff remains open — see §1.2)
>
> **Scope:** Documents the **feature-pipeline stability** audit (Minecraft 1.21.11 single-version corpus) and the **Voxy version-compatibility** audit (verified over the `337b919d` → `02dfb1b7` window; broader Voxy history surveyed by file presence only, not semantically diffed -- see §5.3/§5.6). The **cross-Minecraft-version responsibility diff is a required #235 deliverable that is not yet complete**: the project currently vendors a single Minecraft decompiled corpus, and the comparison corpora #235 requires have not been procured (§1.2, §9.1).
>
> **Invalidation rule:** A newer Minecraft upstream version requires re-verification against its own decompiled corpus. A newer Voxy upstream version requires re-running §3 (Voxy compatibility matrix) and bumping `source-revision`. Do not silently edit this file to describe a different upstream version -- create a separately versioned artifact.

---

## 0. TL;DR -- the three takeaways for #85

1. **The feature-pipeline machinery is small, stable-shaped, and a strong port candidate.** The placement/configured/placed/predicate stack is 16 `PlacementModifier` types, 38 `FeatureConfiguration` record types, ~50 `Feature` implementations, and 10 + 11 datapack registries (~200 KB of vanilla data). None of those core abstractions (`PlacedFeature.placeWithContext` stream-flatMap, `PlacementModifier.getPositions(PlacementContext, RandomSource, BlockPos) → Stream<BlockPos>`, `ConfiguredFeature.place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)`) have changed semantically in the 1.21.11 corpus. Treat this surface as **small, generic, and a plausible port boundary within 1.21.11**. Cross-Minecraft-version stability is the open question this research has **not** answered -- no second MC corpus was diffed (§1.2) -- so "port candidate" here means "candidate for the pinned 1.21.11 corpus", not "port once across MC versions". Whether to port is #85's decision.
2. **The feature content is overwhelmingly data/configuration and belongs in a normalized profile.** The datapack registry bodies (`OreFeatures` 11.1 KB, `VegetationFeatures` 35.9 KB, `TreeFeatures` 31.9 KB, `CaveFeatures` 18.5 KB, `VegetationPlacements` 37.8 KB, `OrePlacements` 17.6 KB, `TreePlacements` 16.6 KB, `OverworldBiomes` 49.6 KB) describe what to place, where, and how often. A new MC version is *expected* to change these bodies (new biomes, new features, new rarity curves) rather than the engine -- an expectation from published Mojang release behavior, **not verified against a second local corpus** (§1.2). The most defensible cost-reduction is to express the bodies as **versioned data fixtures** behind a single execution engine, not to reimplement per-version.
3. **Voxy `VoxelVolumeWriter` is correct as the adapter seam.** Across the audit window 2026-08-10 (audited `337b919d`) → 2026-08-29 (current `origin/dev` `02dfb1b7`), the only changes to `me.cortex.voxy.common.world.*` are a 6-line refactor of `SaveLoadSystem3`, a 19-line refactor of `WorldUpdater` (rename `airCount`→`nonAirCount`, add JMH-visible helper), and a 6-line `Mapper.isNotAirInt` micro-opt. **Zero changes** to `WorldSection`, `WorldEngine`, `Mipper`, `VoxelizedSection`, `WorldConversionFactory`, `SectionStorage`, `ActiveSectionTracker` -- i.e. the data-model and target-semantic surface are stable. VoxelVolumeWriter (which sits above `VoxyCompat`/`WorldEngine`) absorbs the small backend drift; no Voxygen code or model retraining is required for the audit-window delta.

The same `0.2.11-alpha` → `0.2.x` window does not introduce a new `nonEmptyChildren` rule, Mipper algorithm change, `WorldSection` size change, or Mapper layout change. ADR 0015's "post-ingest Voxy mip parity" oracle target therefore remains the same shape across the audit window -- and the Voxygen `VoxelVolumeWriter` (interface, not the implementation behind it) needs no new method for it.

---

## 1. Source-grounded framing

### 1.1 What is actually in the upstream corpus

| Source | Identity | Provenance | Status |
|---|---|---|---|
| Minecraft 1.21.11 / 26.1-snapshot-11 | CFR 0.152 decompiled corpus under `external/minecraft-src/src/` | jar SHA-256 `556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822`; version manifest SHA-256 `D3DE3C825F3AA4A283A2754559CDE6D76FC5AD92C0E914069EECFDB4001B5F57`; **no git SHA established** | Single-version corpus, non-git. Re-verification requires obtaining the same Mojang jar + CFR 0.152. |
| Voxy 0.2.11-alpha | jar at `python/tools/fabric-server/runtime/mods/voxy-0.2.11-alpha.jar`; source `external/voxy` | jar SHA-256 `63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c`; source commit `337b919d` on `dev` branch (2026-08-10); jar metadata is `python/voxel_tree/voxy_format.py` mirror | Audited, version-pinned in `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md`. |
| Voxy current `dev` | `origin/dev` = `02dfb1b7` (2026-08-29) | reachable via `git fetch --all --tags` in `external/voxy`; pre-fetch the local checkout was stale (`HEAD = eaf107e4` = 2026-03-05, **5 months behind**) | Used only for §3 compatibility matrix; not a new pin. |
| Voxy version branches | `origin/master` (2024-07-26), `inverted_nether` (2025-05-29), `mc_1215` (2025-06-18), `mc_1217_mesh3` (2025-09-15), `mc_1217` (2025-10-04), `mc12110` (2026-02-04), `revz` (2026-04-15), `12111` (2026-05-25), `2622` (2026-07-04), `2612` (2026-07-21), `dev` (2026-08-29) | all reachable from `origin`; verified via `git for-each-ref` | Used for §3.3 cross-version table. |
| Fabric API | `external/fabric-api` | `gradle.properties` `version=0.143.11 minecraft_version=26.1-snapshot-11`; mainline `dev/yarn` | Lifecycle-only per `docs/external/l1-availability-contract.md`. |

### 1.2 Honest scope note: cross-Minecraft-version diff is not in this research

The ticket #235 research-program §1 ("Build a cross-version responsibility diff") and §6 ("Bound the ML role") require a Minecraft version span. The project currently vendors **only Minecraft 1.21.11** -- no 1.20.x, 1.21.5, 1.21.7, or 1.21.10 corpus is present. The ticket itself anticipates this: *"If local source for a needed version is absent, obtain/reproduce it through the normal version-pinned source process and record exact version/mapping/source identity."* We have **not** yet performed that source procurement. It is a **remaining open deliverable of #235 itself** -- not a follow-up ticket and not out of scope. The §2 responsibility matrix is therefore a **1.21.11 single-version audit with explicit cross-version-bounded candidate sets**; the cross-version semantic diff is the next #235 work item.

What we can do with the available evidence:

- **Within-MC-version**: 1.21.11 corpus internals (every class, every line, every field).
- **Within-Voxy-version**: full git history 2024-07-26 .. 2026-08-29 with 11 branches.
- **Cross-MC vs cross-Voxy**: relative stability claims need at least one prior MC corpus, which is missing.

The §2 responsibility matrix marks each axis with a `cross-MC-version evidence: none-local | one-local | multi-local` flag, and §4 marks each boundary's cross-version expectation. The ticket's winner rule is evaluated in §10 **against completed evidence only**; the cross-MC diff and the residual measurement remain open.

### 1.3 What this research does and does not claim

- **Does**: give a source-grounded audit of the 1.21.11 feature pipeline's stability-shape, identify what the Voxy adapter seam absorbs vs what would force model retraining, and bound the ML role in concrete candidate slices.
- **Does not**: pick the final partition (that's #85's HITL decision), train any model, generalize across MC versions (missing evidence -- see §1.2), or replace any ADR.
- **Cited concrete examples in §6** are all drawn from the 1.21.11 corpus and the Voxy audit window. They are reproducible by an agent with `rtk read` access to `external/minecraft-src/src/...` and `git -C external/voxy show <ref>:...`.

---

## 2. Cross-version responsibility matrix (1.21.11 only; with explicit missing-evidence flags)

Each row is a Partition Responsibility (CONTEXT.md) classified against the seven evidence axes the ticket requests. `MC-1.21.11` cells describe the 1.21.11 corpus; `MC-1.21.11 ↔ ?` cells are bounded candidate sets because we lack older/newer MC corpora.

The companion DAG document already exhaustively tables per-stage inputs/outputs/dimensionality/cost/determinism. This table only adds the **stability evidence** the ticket asks for, per responsibility.

### 2.1 Climate / base-noise (S1)

| Evidence axis | Verdict (1.21.11) | Evidence | cross-MC-version evidence |
|---|---|---|---|
| Semantic stability | High -- `NoiseRouter` 15-field record is a stable interface; the *contents* per dimension may swap (Overworld `BASE_3D_NOISE_OVERWORLD` vs End `BASE_3D_NOISE_END`) but the record shape does not | `NoiseRouter.java:17` record; `NoiseRouterData.java:91-93` `BlendedNoise.createUnseeded(0.25,0.125,80,160,8)` Overworld / `(0.25,0.375,80,60,8)` Nether / `(0.25,0.25,80,160,4)` End | none-local -- record shape across 1.20.x and 1.21.5/7/10 is **not** verified here |
| Code churn | Low within 1.21.11; `DensityFunctions` and `NoiseRouterData` are stable across 1.21 minor versions in published Mojang history (Mojang reposts rarely touch the noise stack) | `DensityFunctions.java:49-398`, `NoiseRouterData.java:24-293` | none-local |
| Data/config churn | None in 1.21.11 -- the router is fully baked; datapacks can override the 15 fields by registry key | `NoiseData.java:11,22` lists 50+ `NoiseParameters`; datapack override path | none-local |
| Runtime cost | Cheap once warmed (cached `NormalNoise`); dominated by `BlendedNoise` octaves for `finalDensity` | `NormalNoise.java:30` valueFactor; `BlendedNoise.java:30-116` per-octave cost | none-local |
| Residual complexity | After 1.21.11 port, the L4/L3/L2 climate inputs are *pure functions* of `(seed, dim, pos)` -- no residual | `RandomState.java:47` wiring; `Climate.Sampler` is a lookup | n/a |
| Level visibility | Climate drives height + biome selection -- visible at **all** L0..L4 | `NoiseRouterData.java:127-128` `DEPTH = yClampedGradient + offset`; `Climate.Sampler` nearest-point | n/a |
| Retraining burden | Zero if ported; full retraining if learned at this stage (current `voxy_models.py` doesn't) | `python/voxy_models.py` `VoxyL{0..4}Model` do not take climate directly -- they take vanilla **noise inputs** | n/a |
| Compatibility blast radius | New MC = new `NoiseData` table; record shape and field names appear stable in published 1.21.x (unverified locally) | `Noises.java:15,82` 50+ keys; same key names likely stable in 1.21.5/7/10 (unverified) | none-local |

### 2.2 Biome placement (S2)

| Evidence axis | Verdict (1.21.11) | Evidence | cross-MC-version evidence |
|---|---|---|---|
| Semantic stability | High interface -- `BiomeSource.getBiomes(QuartPos) → HolderSet<Biome>`; Overworld/Nether use 6-climate nearest-point, End uses radial + erosion threshold | `BiomeSource.java`, `MultiNoiseBiomeSource.java`, `TheEndBiomeSource.java:58-79` | none-local |
| Code churn | Interface stable; `TheEndBiomeSource` is the dimension-specific branch | n/a | none-local |
| Data/config churn | **Largest churn surface in MC** -- every biome update, mod, datapack changes this. `OverworldBiomes.java` is 49.6 KB; `NetherBiomes.java` 14.9 KB; `EndBiomes.java` 3.0 KB; `BiomeData.java` 7.6 KB | `external/minecraft-src/src/net/minecraft/data/worldgen/biome/` | **strong expectation**, unverified locally: biomes change every MC minor; biome IDs do not persist across versions. This is the canonical reason to **canonicalize at the seam**, not in the writer. |
| Runtime cost | Cheap OW/Nether (lookup); End is a single radial test + one erosion sample | `TheEndBiomeSource.java:58-79` `getNoiseBiome` | n/a |
| Residual complexity | After canonicalization (`BiomeMapping` 54-entry alpha for OW), zero residual for OW; Nether/End follow the same `BiomeMapping` shape per dimension | `AnchorSampler.sampleFromNoise` already does canonical lookup | n/a |
| Level visibility | Material/tint (not height) -- visible at L0..L2, marginal at L3, dropped at L4 by mip opacity rule | companion DAG doc §6; ADR 0015 mip opacity-15-wins | n/a |
| Retraining burden | Zero if canonicalized; full if learned per-biome (not done) | `python/voxy_models.py` consumes canonical IDs not raw biome keys | n/a |
| Compatibility blast radius | New biome = new canonical ID (or 255 unknown); id stability across versions is intentionally NOT promised | `BiomeMapping.toCanonicalId` (`AnchorSampler.java:68-78`) | none-local |

### 2.3 Terrain fill (S3 + S4 + S5 + S6) -- the dominant cost responsibility

| Evidence axis | Verdict (1.21.11) | Evidence | cross-MC-version evidence |
|---|---|---|---|
| Semantic stability | High -- `finalDensity > SURFACE_DENSITY_THRESHOLD(1.5625)` is the single 3D SDF that decides solid; aquifer Voronoi + ore vein + surface + carvers are layered on top | `NoiseRouterData.java:29`; `Aquifer.java:28-265`; `OreVeinifier.java:15`; `SurfaceSystem.java:77`; `carver/WorldCarver.java` | none-local |
| Code churn | Within 1.21.11 stable. The 5-line `NetherForestVegetationConfig` / `RootSystemConfiguration` etc. record shapes change occasionally across minor versions, but the responsibilities (height, fluid, ore, surface, carver) are stable | `external/minecraft-src/src/net/minecraft/world/level/levelgen/` 54 files total | none-local |
| Data/config churn | **Second-largest** -- surface rules per biome, ore target lists, carver configs, etc. all live as datapack data | `SurfaceRuleData.java`, `OreFeatures.java` 11.1 KB, `Carvers.java` 3.7 KB | **strong expectation**, unverified locally |
| Runtime cost | **Dominant** -- full chunk fill is the cost; the 128× cell win from `NoiseChunk` is the reason vanilla runs on ordinary hardware | companion DAG doc §3; `NoiseChunk.java:42-437` | n/a |
| Residual complexity | After `NoiseRouter` port, zero residual; if learned, residual = `finalDensity` per block (already done by the Voxygen `finalDensity>threshold` MLP pattern) | `python/voxy_models.py` | n/a |
| Level visibility | **L0..L4** (this IS the distant terrain) | companion DAG doc §6 | n/a |
| Retraining burden | Zero if ported. If learned, retraining per MC version is the cost | ADR 0015 oracle target; `python/voxy_models.py` `VoxyL{0..4}Model` already retrain per version implicitly via data | n/a |
| Compatibility blast radius | New MC = new `NoiseData` + new `SurfaceRuleData` + new `OreFeatures`; the **router interface** is stable, the **router contents** are not | `NoiseRouterData.java:226-293` per-dimension builders | none-local |

### 2.4 Feature pipeline (S7a + S7b) -- see §3 of this doc for the dedicated audit

The feature pipeline is large enough to deserve its own section, not a single row. §3 audits the placement / configured / placed / modifier / feature-family machinery.

### 2.5 What we can NOT responsibly claim without more MC corpora

- "X is stable across MC versions" -- would require at least one additional decompiled corpus (1.20.4 or 1.21.5 say). The published Mojang history strongly suggests the climate/router interface has been stable since 1.18 (the Caves & Cliffs rewrite), and that biomes/datapacks are the volatile surface; but **this is not** verified in the local evidence.
- "New MC version requires N months of porting" -- depends on what changed; we cannot estimate without the diff.

The cheapest next experiment is recorded in §7.

---

## 3. Feature-pipeline stability audit (generic machinery vs data vs family-specific)

### 3.1 The pipeline shape (1.21.11)

```
PlacedFeature (record)
  ├── Holder<ConfiguredFeature<?,?>> feature
  ├── List<PlacementModifier> placement
  └── placeWithContext(PlacementContext, RandomSource, BlockPos):
        Stream<BlockPos> placements = Stream.of(origin)
        for (PlacementModifier m : placement)
            placements = placements.flatMap(p -> m.getPositions(ctx, random, p))
        ConfiguredFeature<?,?> feature = this.feature.value()
        placements.forEach(pos -> feature.place(ctx.getLevel(), ctx.generator(), random, pos))
```

Source: `external/minecraft-src/src/net/minecraft/world/level/levelgen/placement/PlacedFeature.java:30-55`.

The shape is **a stream-flatMap over a generic `getPositions(PlacementContext, RandomSource, BlockPos) → Stream<BlockPos>`** and a terminal `ConfiguredFeature.place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)`. Every dimension's feature pipeline, every biome's placed-feature list, every structure's `GenerationStep.Decoration` runs through this exact shape. There is no dimension- or family-specific override of the *machinery*.

### 3.2 Generic placement machinery -- small, stable, shared-code candidate

The 16 `PlacementModifier` types in `external/minecraft-src/src/net/minecraft/world/level/levelgen/placement/`:

```
BiomeFilter.java                  1.5K
BlockPredicateFilter.java         1.7K
CaveSurface.java                  1.0K
CountOnEveryLayerPlacement.java   3.5K
CountPlacement.java               1.3K
EnvironmentScanPlacement.java     3.7K
FixedPlacement.java               2.4K
HeightRangePlacement.java         2.3K
HeightmapPlacement.java           1.9K
InSquarePlacement.java            1.2K
NoiseBasedCountPlacement.java     2.3K
NoiseThresholdCountPlacement.java 2.2K
PlacementContext.java             1.8K
PlacementFilter.java              794B
PlacementModifier.java            907B
PlacementModifierType.java        4.2K
RarityFilter.java                 1.2K
RandomOffsetPlacement.java        2.5K
RepeatingPlacement.java           730B
SurfaceRelativeThresholdFilter.java 2.5K
SurfaceWaterDepthFilter.java      2.0K
```

≈ 50 KB of code total. Every one of these is a `Stream<BlockPos> getPositions(...)` with a single specialization. The `PlacementContext` (1.8K) is the per-chunk state carrier (level, generator, top-feature, biome lookup). This surface is what Voxygen would port if it were to own placed-feature execution at L0/L1 (player-adjacent). The cost is one-time, well-bounded, and the abstractions are stable in the 1.21.11 corpus.

**38 `FeatureConfiguration` records** in `external/minecraft-src/src/net/minecraft/world/level/levelgen/feature/configurations/`: BlockBlobConfiguration 1.1K, BlockColumnConfiguration 2.3K, BlockPileConfiguration 0.8K, BlockStateConfiguration 0.7K, ColumnFeatureConfiguration 1.4K, CountConfiguration 0.9K, DeltaFeatureConfiguration 1.8K, DiskConfiguration 1.4K, DripstoneClusterConfiguration 3.9K, EndGatewayConfiguration 1.6K, EndSpikeConfiguration 2.3K, FallenTreeConfiguration 3.0K, FeatureConfiguration 0.5K, GeodeConfiguration 4.3K, HugeMushroomFeatureConfiguration 1.4K, LargeDripstoneConfiguration 3.1K, LayerConfiguration 1.2K, MultifaceGrowthConfiguration 4.5K, NetherForestVegetationConfig 1.5K, NoneFeatureConfiguration 0.7K, OreConfiguration 3.0K, PointedDripstoneConfiguration 2.1K, ProbabilityFeatureConfiguration 1.1K, RandomBooleanFeatureConfiguration 1.7K, RandomFeatureConfiguration 1.7K, RandomPatchConfiguration 1.4K, ReplaceBlockConfiguration 1.7K, ReplaceSphereConfiguration 1.5K, RootSystemConfiguration 4.4K, SculkPatchConfiguration 1.8K, SimpleBlockConfiguration 1.2K, SimpleRandomFeatureConfiguration 1.2K, SpikeConfiguration 1.2K, SpringConfiguration 2.0K, TreeConfiguration 6.0K, TwistingVinesConfig 1.2K, UnderwaterMagmaConfiguration 1.7K, VegetationPatchConfiguration 3.4K. **These are pure data records** (no behavior beyond Codec); they describe *what* to place. The set is small, the record shapes are tight, and new features typically add a new `*Configuration` record rather than mutating existing ones.

**~50 `Feature` impls** in `external/minecraft-src/src/net/minecraft/world/level/levelgen/feature/`: AbstractHugeMushroomFeature 3.9K, BambooFeature 3.9K, BasaltColumnsFeature 5.9K, BasaltPillarFeature 4.0K, BlockBlobFeature 1.8K, BlockColumnFeature 2.9K, BlockPileFeature 2.6K, BlueIceFeature 2.7K, BonusChestFeature 2.8K, ChorusPlantFeature 1.3K, ConfiguredFeature 1.9K, CoralClawFeature 2.8K, CoralFeature 3.4K, CoralMushroomFeature 1.8K, CoralTreeFeature 2.0K, DeltaFeature 3.4K, DesertWellFeature 4.9K, DiskFeature 2.5K, DripstoneClusterFeature 9.4K, DripstoneUtils 5.2K, EndGatewayFeature 2.7K, EndIslandFeature 1.6K, EndPlatformFeature 1.8K, EndPodiumFeature 3.7K, EndSpikeFeature 8.9K, FallenTreeFeature 5.8K, Feature 21.6K, FeatureCountTracker 4.3K, FeaturePlaceContext 1.6K, FillLayerFeature 1.4K, FossilFeature 4.3K, FossilFeatureConfiguration 4.3K, GeodeFeature 9.3K, plus 16+ more. This is the *implementation* layer where bespoke procedural logic lives. Sizes range 1.3K to 21.6K (`Feature.java` itself is the registry base class).

**10 datapack feature registries** in `external/minecraft-src/src/net/minecraft/data/worldgen/features/`: AquaticFeatures 2.9K, CaveFeatures 18.5K, EndFeatures 2.3K, FeatureUtils 4.1K, MiscOverworldFeatures 7.4K, NetherFeatures 8.7K, OreFeatures 11.1K, PileFeatures 2.3K, TreeFeatures 31.9K, VegetationFeatures 35.9K (≈ 124 KB of placement *content*).

**11 datapack placement registries** in `external/minecraft-src/src/net/minecraft/data/worldgen/placement/`: AquaticPlacements 5.3K, CavePlacements 11.9K, EndPlacements 3.5K, MiscOverworldPlacements 10.1K, NetherPlacements 8.9K, OrePlacements 17.6K, PlacementUtils 6.5K, TreePlacements 16.6K, VegetationPlacements 37.8K, VillagePlacements 5.3K (≈ 117 KB of placement *content*).

**4 biome registries**: BiomeData 7.6K, EndBiomes 3.0K, NetherBiomes 14.9K, OverworldBiomes 49.6K (≈ 75 KB of biome *content*).

`BiomeDefaultFeatures.java` (33.1 KB) wires biome→feature lists per dimension.

### 3.3 Stability table -- generic machinery vs data/configuration vs feature-family logic vs biome-specific content

The ticket's deliverable #2 names four categories. They are separated below:

| Category | Layer | # items | Total size | Stays in code or data? | Cross-MC-version expectation |
|---|---|---|---|---|---|
| **Generic placement machinery** (code) | `PlacementModifier` impls | 16 | ~50 KB | **Shared-code candidate** | The set is stable; new modifiers (e.g. `EnvironmentScanPlacement`) appear occasionally; rare mutation of existing modifiers. Strong expectation, unverified locally. |
| **Generic placement machinery** (code) | `PlacedFeature.placeWithContext` loop + `PlacementContext` + `PlacedFeature.placeWithBiomeCheck` | 3 | ~3 KB | **Shared-code candidate** | The shape is dimension- and family-agnostic; survives version churn. |
| **Generic data shape** (code) | `FeatureConfiguration` records | 38 | ~85 KB | **Shared-code candidate** | New MC = new `*Configuration` records for new features; existing ones almost never change shape. Strong expectation, unverified. |
| **Generic data shape** (code) | `ConfiguredFeature` + `Feature` base classes (registry + dispatch) | 2 | ~24 KB | **Shared-code candidate** | Stable interface; per-version delta is in the registry contents, not the class shape. |
| **Feature-family logic** (code, bespoke) | `Feature` impls | ~50 | ~250 KB | **Code, port per family, each bespoke** | New MC = a few new `Feature` impls; existing ones occasionally gain new fields. Strong expectation, unverified. |
| **Data/configuration** (data, dimension-agnostic) | Feature registries (`features/*.java`) | 10 | ~124 KB | **Data adapter** (extract to JSON/datapack adapter) | New MC = register more entries, mostly additive. |
| **Data/configuration** (data, dimension-agnostic) | Placement registries (`placement/*.java`) | 11 | ~117 KB | **Data adapter** (extract to JSON/datapack adapter) | New MC = register more entries, mostly additive. |
| **Biome-specific content** (data, dimension-specific) | Biome registries (`biome/*.java`) | 4 | ~75 KB | **Data adapter** (extract to JSON/datapack adapter) | New MC = new biomes; existing biome IDs are **not** stable across versions (canonical mapping required). |
| **Biome-specific content** (data, biome-keyed) | `BiomeDefaultFeatures` biome→feature wiring | 1 | 33 KB | **Data adapter** (keyed by biome ID) | New MC = new biome→feature lists; this is the most volatile single file. |

### 3.4 Answering the §1 questions concretely

> What placement machinery is sufficiently generic and stable that Voxygen could reuse or port it once?

The 16 `PlacementModifier` impls + `PlacementContext` + the `PlacedFeature.placeWithContext` loop + the 38 `FeatureConfiguration` record codecs. Total ~135 KB. **No vanilla source assumes anything dimension- or family-specific about this machinery.** ADR 0013 (sampler-direct End base terrain) already shows the shape of a one-time port: the project ports vanilla's `DensityFunction` evaluation into a `VanillaNoiseRouterSampler` adapter; the same pattern applies here.

> Which behavior is predominantly data/configuration and could be represented through a version adapter or normalized profile rather than code forks?

The feature/placement/biome registries (≈ 316 KB total) and `BiomeDefaultFeatures` (33 KB). These describe *what to place, where, how often, with which biomes*. The right shape is a **canonical profile**: `(profile_id, profile_version, dim_key) → { biome_lookup, feature_lookup, placement_lookup, biome_feature_wiring }` where each lookup is a stable schema. The current `BiomeMapping` (54-entry alpha for OW, `AnchorSampler.java:68-78`) is the per-dimension precursor; the same shape can be extended to features and placements.

> Which feature families actually contain irreducibly bespoke procedural logic?

- `TreeFeature.java` (decompiled) -- coordinate-based trunk/branch placement with `BlockPos`, `Sets`, `ObjectArrayList`, `Vec3i`, `BlockTags`, `LeavesBlock`, etc. The `TreeConfiguration` (6 KB) is the data, but the placement algorithm is bespoke.
- `DripstoneClusterFeature.java` (9.4K) + `DripstoneUtils.java` (5.2K) -- bespoke stalactite/stalagmite growth.
- `GeodeFeature.java` (9.3K) -- bespoke geode shell.
- `EndSpikeFeature.java` (8.9K) -- End obsidian spike column.
- `CoralClawFeature`/`CoralFeature`/`CoralMushroomFeature`/`CoralTreeFeature` -- bespoke coral shapes.
- `BasaltColumnsFeature` (5.9K) + `BasaltPillarFeature` (4.0K) -- bespoke Nether basalt.
- `FossilFeature` (4.3K) + `FossilFeatureConfiguration` (4.3K) -- bespoke fossil structure.

Each is **a different bespoke algorithm**; there is no universal "feature executor" abstraction in vanilla. The ticket's caveat holds: *"do not invent a universal placement engine if vanilla source does not support that abstraction."*

> At coarse Levels, which of those bespoke outputs survive Voxy mip strongly enough that we need anything more than a proxy/residual?

Per ADR 0015, the oracle target is post-ingest Voxy mip. The mip rule is opacity-15-wins with a deterministic corner-priority tie-break. Any bespoke output whose **coarse silhouette** survives the mip rule (canopy, spikes, basalt columns, large geodes) is a candidate for honest port or even learned residual; outputs that **mip to nothing visible** (dripstone tips inside a solid, fossil bones that mostly lose to surrounding stone) are omit-defer candidates.

This is a partition decision (`#85`), not a research conclusion. The research observation is: the mip rule is what filters "bespoke but invisible" from "bespoke and silhouette-bearing"; without measuring against the oracle, no Voxygen decision here is grounded.

---

## 4. Normalized version-boundary candidates (per ticket §4)

The ticket asks whether a useful normalized boundary is **cheaper to maintain than direct version-specific code**. The candidates below are framed as experiments, not decisions. For each, the test is the same: does expressing the responsibility at this boundary reduce the per-version port cost relative to direct per-version code?

| ID | Boundary | Shape | Status | Cross-MC-version expectation | Notes |
|---|---|---|---|---|---|
| 4.1 | Climate / scaffold | `Seed + Climate.Sampler → {block_pos → climate 6-tuple}` | **Implemented** (ADR 0013 seals the End sampler-direct approach; OW/Nether extension is not sealed) | Minimal port per version (interface expected stable since 1.18; `none-local` -- unverified against a second corpus) | Already ported as `VanillaNoiseRouterSampler` (480-float quart); cost is upstream `BlendedNoise` octave table + `NoiseGeneratorSettings` per-dimension change, addressed by `RandomState` wiring. |
| 4.2 | Biome / profile identity | `(dim, climate, pos) → canonical biome id` | **Implemented** (project contract: `Canonical Biome Registry` in CONTEXT.md; not an ADR-sealed boundary) | Medium cost (biome IDs are intentionally not stable across MC versions) | Already implemented as `BiomeMapping.toCanonicalId` (54-entry alpha for OW; equivalent for Nether/End if extended); contract metadata hash is the enforcement point. |
| 4.3 | Surface/material family | `(biome, column, noise) → material family id` | **Open** | Small fixed mapping per dimension per MC version (data) | Vanilla's `SurfaceSystem` is a biome-tagged `MaterialRule` tree; ADR 0015 §"Hierarchical Material Taxonomy" names this; mapping is data, not code. |
| 4.4 | Generic placement intent | `(biome, feature_intent, surface) → {position, count, modifier-chain}` | **Open** | Cost bounded by which `Feature` family is ported; not universal | The audit shows `PlacedFeature.placeWithContext` is generic, but vanilla does not provide a "feature-instance descriptor" abstraction (each `Feature` is bespoke); bounded port of a single family (e.g. vanilla trees) is the cheapest test. |
| 4.5 | Deterministic scaffold + sparse learned residual | `(seed, dim, pos) → {coarse_geometry, fine_residual}` | **Open** | Medium OW / low End / low-medium Nether | Scaffold Preference / Residual Default (CONTEXT.md) applied to the partition; End vertical slice already does this for chorus (ADR 0013, 0014); extending to OW/Nether requires the partition to commit to a stable scaffold shape. |
| 4.6 | Per-Level post-ingest semantic target | `(level, post_voxy_mip(scaffold))` | **Sealed** | Zero cost if Mipper rule and Mapper layout are stable in Voxy (see §5) | ADR 0015 fixes this as the default correctness target; the oracle will produce the post-ingest Voxy representation for any `(seed, dim, pos)` per #233. |
| 4.7 | Verdict (order to test) | -- | **Test order** (not a boundary; no seal implied) | n/a | §4.6 (committed by ADR 0015) → §4.1 (already ported) → §4.2 (already canonicalized) → §4.4 (bounded port of a single feature family) → §4.3 (next, coarser) → §4.5 (last, as a full OW port). Each step either confirms or refutes the boundary with measured evidence. |

---

## 5. Voxy version compatibility matrix

### 5.1 Method

Voxy's `external/voxy` clone has 11 reachable branches (after `git fetch --all --tags --prune`). For the **audit window** (audited `337b919d` → current `origin/dev` `02dfb1b7`), we ran `git diff --stat 337b919d..02dfb1b7` and grouped changes by file role.

For the **broader cross-version table** (§5.3), we sampled branches by date: `master` (2024-07), `mc_1217` (2025-10), `mc12110` (2026-02), `12111` (2026-05), `2612` (2026-07), `dev` (2026-08). For each, we checked the existence of the 12 core files the pinned reference audit tracks (per `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md`).

### 5.2 The audit window (2026-08-10 `337b919d` → 2026-08-29 `02dfb1b7`)

```
build.gradle                                       |  8 +++
gradle.properties                                  |  2 +-
src/jmh/java/me/cortex/voxy/jmh/SaveLoadJMH.java   | 78 ++++++++++++++++++++++
src/jmh/java/me/cortex/voxy/jmh/WorldUpdaterJMH.java | 64 ++++++++++++++++++
src/main/java/me/cortex/voxy/common/world/SaveLoadSystem3.java |  6 +++---
src/main/java/me/cortex/voxy/common/world/WorldUpdater.java    | 19 ++++++++++---------
src/main/java/me/cortex/voxy/common/world/other/Mapper.java    |  6 ++++++
7 files changed, 170 insertions(+), 13 deletions(-)
```

`git log 337b919d..02dfb1b7` yields 4 commits:

```
02dfb1b7 cleanup
30e268cc FUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU...
7383fd95 JMH + optimizations
f53bb469 ver up
```

Classifying each change:

| File | Lines | Type | Adapter-only? | Target-semantic? | Required Voxygen action |
|---|---|---|---|---|---|
| `build.gradle` | 8 | Version + dep bumps | Yes | No | Re-pin jar if Voxy version bump matters to Voxygen (jar SHA-256 will change) |
| `gradle.properties` | 2 | Version bump | Yes | No | Re-pin |
| `src/jmh/.../SaveLoadJMH.java` (new) | 78 | Benchmark scaffolding | Yes | No | None -- not on runtime path |
| `src/jmh/.../WorldUpdaterJMH.java` (new) | 64 | Benchmark scaffolding | Yes | No | None |
| `SaveLoadSystem3.java` | 6 (3+/3-) | Micro-opt: rename `emptyBlockCount`→`notEmpty`, use new `Mapper.isNotAirInt` | Yes | No | None -- file format is unchanged |
| `WorldUpdater.java` | 19 (10+/9-) | Same micro-opt: rename `airCount`→`nonAirCount`, add `public` modifier on `insertSectionLvlIntoWorld` for JMH | Yes | No | None -- if Voxygen calls `insertSectionLvlIntoWorld` it now compiles; the return type (`long`) and behavior are unchanged |
| `Mapper.java` | 6 (6+/0-) | Add `public static int isNotAirInt(long id)` helper that returns `Math.min(getBlockId(id), 1)` | Yes | No | None -- additive, old `isAir` still present |

**Critical invariants preserved** (verified by checking the file tree at `02dfb1b7` matches the audit):

- `WorldSection.java` -- same file path, same `SECTION_VOLUME=32768` (32³ voxels), same `nonEmptyChildren` byte layout, same `VarHandle` atomic state, same pool/cache invariants. No edits.
- `WorldEngine.java` -- same 64-bit position key encoding `(lvl<<60)|(y<<52)|(z<<28)|(x<<4)`. No edits.
- `Mipper.java` -- same opacity-15-wins + corner-priority tie-break. No edits. **This is the ADR 0015 oracle target -- unchanged.**
- `VoxelizedSection.java` -- same 5-level pyramid (4096+512+64+8+1 = 4681 longs), same L0 indexing `(y<<8)|(z<<4)|x`. No edits.
- `WorldConversionFactory.java`, `SectionStorage.java`, `ActiveSectionTracker.java`, `LoadedPositionTracker.java`, `SectionSavingService.java` -- no edits. No edits to the storage backend (`LMDBStorageBackend`, `MemoryStorageBackend`, `CompressionStorageAdaptor`, `ZSTDCompressor`).

**Conclusion: the audit window is adapter-only churn.** The Voxygen `VoxelVolumeWriter` interface (writeSection / writeRegion) needs no new method. The `RealVoxyVolumeWriter` implementation may need a one-line touch if it directly called `insertSectionLvlIntoWorld` (now public -- no change) or relied on `emptyBlockCount` (it doesn't; it uses `isDirty` and `nonEmptyChildren`).

### 5.3 Cross-version compatibility table (broader, file-presence based)

> **Evidentiary strength: presence-only.** This table records file/package presence per branch, not semantic diffs. It supports "the package layout moved" claims only. It does **not** verify that the Mipper rule, packed-`long` layout, 32³ geometry, or LOD semantics are stable across the full 2024-07 → 2026-08 history; those invariants are verified only over the §5.2 audit window (see §5.6 and §9.1 item 2).

| Voxy branch | HEAD commit | Date | Core 12 files all present? | Storage config location | Notes |
|---|---|---|---|---|---|
| `origin/master` | `581d48e2` | 2024-07-26 | not in this clone's reachable history for core types (pre-`dev` divergence) | n/a | Earliest reachable, before major package moves |
| `origin/inverted_nether` | `e8dd71de` | 2025-05-29 | n/a (research only) | n/a | Branch-only feature; inverted Nether biome |
| `origin/mc_1215` | `2327c6ba` | 2025-06-18 | reachable; package layout closer to audit-era | `world/storage/` likely | 1.21.5 baseline |
| `origin/mc_1217_mesh3` | `5458d9f9` | 2025-09-15 | reachable | `world/storage/` | Mesh renderer exploration |
| `origin/mc_1217` | `a06fc754` | 2025-10-04 | reachable | `world/storage/` | 1.21.7+1.21.8 baseline |
| `origin/mc12110` | `cad8d593` | 2026-02-04 | reachable | `world/storage/` | 1.21.10 backport |
| `origin/revz` | `7e924a45` | 2026-04-15 | reachable | `world/storage/` or `common/` | Renderer revision work |
| `origin/12111` | `59b62bee` | 2026-05-25 | reachable; this is when the package moves to `common/config/...` start | `common/config/storage/` | 1.21.11 baseline |
| `origin/2622` | `91c96528` | 2026-07-04 | reachable | `common/config/storage/` | 26.1.2 / 26.2.2 work |
| `origin/2612` | `4643445e` | 2026-07-21 | reachable | `common/config/storage/` | 26.1.2 (requires Java 25) -- Java-25-only target |
| `origin/dev` (= audit root's grandparent) | `02dfb1b7` | 2026-08-29 | reachable | `common/config/storage/` | Current dev |
| Audit pin | `337b919d` | 2026-08-10 | reachable; same `common/world/...` layout as `12111` and later | `common/world/` | The version Voxygen is pinned to |

`git ls-tree` was confirmed for the audit pin (`337b919d`) and current dev (`02dfb1b7`) and shows:

- The 9 files under `src/main/java/me/cortex/voxy/common/world/` (`ActiveSectionTracker`, `SaveLoadSystem3`, `WorldEngine`, `WorldSection`, `WorldUpdater`, `other/Mapper`, `other/Mipper`, `service/SectionSavingService`, `service/VoxelIngestService`) are present at both SHAs.
- `SectionStorage` and `SectionSerializationStorage` and `StorageConfigUtil` moved from `common/world/` to `common/config/section/` and `common/config/storage/other/` respectively. This is the **storage-backend interface refactor** -- exactly the part Voxygen's `VoxelVolumeWriter` adapter wraps.

### 5.4 Adapter vs target-semantic classification (Voxy)

| Change class | Files | Voxygen impact | Risk if changes |
|---|---|---|---|
| **Adapter-only** (storage backend, refactor, micro-opt, build) | `build.gradle`, `gradle.properties`, `SaveLoadSystem3`, `WorldUpdater`, `Mapper`, `SectionStorage` (move) | `VoxelVolumeWriter` interface unchanged; `RealVoxyVolumeWriter` needs an `import` line fix and possibly a config-path update | Low: zero Voxygen code change needed in audit window; future moves are mechanical |
| **Packed-`long` layout** (Mapper bit layout) | `Mapper.java` (32-bit block ID 20b, 9b biome, 8b light, 27b unused) | If layout changes, `RealVoxyVolumeWriter`'s `composeMappingId` re-implementation must change | High if changed: breaks any on-disk store from old jars |
| **`WorldSection` size** (32³ voxels, `SECTION_VOLUME=32768`) | `WorldSection.java` | If size changes, `writeSection`/`writeRegion` semantics change, and any pipeline that hardcodes 32³ breaks | Very high: would require an adapter version bump |
| **LOD geometry** (`lvl` 0..4, voxel size `1<<lvl`) | `WorldEngine.java`, `VoxelizedSection.java` | If geometry changes, `Level` ↔ Voxy `lvl` mapping needs to change | High: visible to all Voxygen Levels |
| **Mip rule** (opacity-15-wins + corner-priority) | `Mipper.java` | If mip rule changes, ADR 0015's oracle target shape changes; learned models must retrain | **Highest**: every L4/L3/L2/L1 trained model assumes this mip |
| **`nonEmptyChildren` semantics** (8-bit octant mask, propagation) | `WorldSection.java`, `WorldUpdater.java` (insertUpdate) | If semantics change, `nonEmptyChildren` propagation/clearing changes; would invalidate the rendered `NodeManager` state in ways visible to VoxyRenderHook | High: could re-break post-#221 work |
| **Biome/block identity** (per-world sequential IDs, persisted in RocksDB) | `Mapper.java` | If shape changes (e.g. block ID becomes 24b), Voxy Mapper mapping table format breaks | High for on-disk migration; low for new worlds |

**Audit window classification**: 100% adapter-only + micro-opt. The 5/5 (layout, geometry, mip, nonEmptyChildren, identity) high-risk invariants are **all preserved unchanged** between `337b919d` and `02dfb1b7`.

### 5.5 What Voxygen must do when a new Voxy version is pinned

1. **Re-pin the jar SHA-256** in `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md` and re-run the storage audit (§§1-11 of that doc).
2. **Re-run `git diff --stat <old>..<new>`** in `external/voxy`; classify each file change as adapter-only vs target-semantic using the table in §5.4.
3. **If any high-risk invariant changed** (Mapper layout, WorldSection size, LOD geometry, Mipper rule, nonEmptyChildren semantics): re-derive the post-ingest oracle target (per ADR 0015); existing fixtures and models become a different evidence population and must be regenerated or versioned.
4. **If only adapter-only changes**: update the `RealVoxyVolumeWriter` import paths and the `VoxyCompat` config loading; no model retraining, no fixture regeneration. This is the cheap path.

### 5.6 What this rules out

- **Conflating "new Voxy version" with "new Minecraft version"**: Voxy is a Java mod; its 11-branch history shows ~6 months between adjacent version branches and the `dev` branch moves in micro-opt cadence. Minecraft has 1.21.x micro-versions and 1.21.0/1.21.2/1.21.4/1.21.5/1.21.7/1.21.11 minor versions -- different cadence, different blast radius. The "compatibility matrix" is two matrices, not one.
- **Treating Voxygen's `VoxelVolumeWriter` as version-fragile**: the audit evidence is that the 32³ voxel model, packed-`long` layout, LOD geometry, and Mipper rule are **verified stable over the 2026-08-10 → 2026-08-29 audited interval**. Broader historical stability (2024-07 → 2026-08) remains **unverified** -- §5.3 is a file-presence/package-layout survey, not a semantic diff, and the full-history Mipper check (§9.1 item 2) has not been run. The safe finding: target semantics are verified stable over the audited interval; broader historical stability is an open question. The writer contract is still the right depth.
- **Replacing `Mipper`-driven post-ingest Voxy oracle with a hand-authored visual policy at any Level**: ADR 0015 already commits to the post-ingest target; this evidence reinforces that the mip rule is the **stable surface** the oracle should be built against.

---

## 6. Three source-backed concrete examples

Each example below cites the file(s) and line(s) in the 1.21.11 corpus or the Voxy audit window, and matches one of the three categories the ticket requires: stable/shared, data-driven/version-volatile, bespoke.

### 6.1 Example A (mostly stable/shared machinery) -- the placement stream-flatMap loop

**File:** `external/minecraft-src/src/net/minecraft/world/level/levelgen/placement/PlacedFeature.java:30-55`

```java
private boolean placeWithContext(PlacementContext context, RandomSource random, BlockPos origin) {
    Stream<BlockPos> placements = Stream.of(origin);
    for (PlacementModifier placementModifier : this.placement) {
        placements = placements.flatMap(p -> placementModifier.getPositions(context, random, (BlockPos)p));
    }
    ConfiguredFeature<?, ?> feature = this.feature.value();
    MutableBoolean placedAny = new MutableBoolean();
    placements.forEach(pos -> {
        if (feature.place(context.getLevel(), context.generator(), random, (BlockPos)pos)) {
            placedAny.setTrue();
            ...
        }
    });
    return placedAny.isTrue();
}
```

**Why this is "stable/shared":** the loop is a **single, generic, dimension-agnostic** operation: a chain of `PlacementModifier.getPositions` and one terminal `ConfiguredFeature.place`. It is used by **every dimension, every biome, every GenerationStep.Decoration** -- the only specialization is the `List<PlacementModifier>` chain contents and the `ConfiguredFeature` impl. If Voxygen were to port this loop, the cost is ~25 lines of code; the rest is data.

**Implication for #85:** the placement machinery is a strong shared-code candidate — evidence favors testing an exact port. The cost is bounded, the surface is well-defined, and the per-version delta is purely the modifier list contents (data). A Voxygen port of the loop could run unmodified against any MC version that preserves the `PlacementContext` / `PlacementModifier` / `ConfiguredFeature` interface (the published Mojang history suggests this is stable since 1.18; unverified locally). Whether to port is #85's decision.

### 6.2 Example B (mostly data-driven/version-volatile content) -- biome→feature wiring

**File:** `external/minecraft-src/src/net/minecraft/data/worldgen/BiomeDefaultFeatures.java` (33.1 KB, ~50+ biome feature-list registrations)

This file wires each `Biome` to its `GenerationStep.Decoration` feature list. The wiring logic per biome is **mechanical** (e.g. "add common ores to every stone biome"), but the *contents* (which features, in which step, with which placement modifier) change every MC minor. Examples visible at the file head:

```java
public static void addDefaultOres(BiomeGenerationSettings.Builder builder) { ... }
public static void addExtraGoldOre(BiomeGenerationSettings.Builder builder) { ... }
public static void addDefaultMushrooms(BiomeGenerationSettings.Builder builder) { ... }
public static void addDefaultVegetation(BiomeGenerationSettings.Builder builder) { ... }
```

**Why this is "data-driven":** the wiring is composed from small static helpers that, together, define *which features get added to which biomes*. The composition is the kind of thing that can be expressed as a **canonical profile** (`biome_id → (feature_id, step, modifier_chain_id)[]`) and stored as JSON or as a frozen Java registry built at startup. New MC versions = new profile, not new code.

**Implication for #85:** the right Voxygen boundary is a **biome feature-list profile**, not a per-MC-version code path. The current `BiomeMapping` (canonical biome ID for OW) is the per-dimension precursor; the same shape extends to `(biome_id, feature_id, step, modifier_chain_id)` tuples. The 33.1 KB file becomes a 33.1 KB profile fixture that can be regenerated by a one-time script and versioned.

### 6.3 Example C (genuinely bespoke feature logic) -- `EndSpikeFeature`

**File:** `external/minecraft-src/src/net/minecraft/world/level/levelgen/feature/EndSpikeFeature.java` (8.9 KB)

EndSpikeFeature is a custom feature that grows the obsidian End-spike column from the End podium. The placement algorithm is bespoke: it locates the spike origin, sets a `BlockState` for each obsidian block by iterating the column, and writes a `BoundingBox` of placed blocks. The `EndSpikeConfiguration` (2.3 KB) carries the spike params (`crystalInvulnerable`, `crystalBeamTarget`, `spikeRadius`, `guardians`, etc.).

```java
public class EndSpikeFeature extends Feature<EndSpikeConfiguration> {
    public boolean place(FeaturePlaceContext<EndSpikeConfiguration> context) {
        ...
        // bespoke per-spike algorithm:
        //   locate podium origin
        //   set spike base blocks
        //   place crystal at top (per config.crystalInvulnerable)
        //   optionally place end-crystal entity (per config.crystalBeamTarget)
        //   ... (8.9 KB of obsidian-column geometry)
    }
}
```

**Why this is "bespoke":** there is no generic abstraction in vanilla that captures spike placement. The algorithm uses `EndGatewayFeature` for return gateways (2.7 KB) and `EndPodiumFeature` (3.7 KB) for the platform, but each is its own bespoke impl. There is no "tree of column-block features" or "vertical-pillar feature base class" in vanilla.

**Implication for #85:** End spikes are **dimension-defining** and **L3–L4 visible** (per companion DAG doc §6). They are a candidate for **port (L0/L1)** + **omit (L4) or learned residual (L3)** depending on ADR 0015 oracle measurement. The cost of a port is ~10 KB; the cost of a learned surrogate is a model that predicts the spike's column-block mask per `(EndSpikeConfiguration, pos)`. The right choice is an empirical #85 disposition backed by the oracle, not a research conclusion here.

### 6.4 Bonus: Voxy-version example -- `SaveLoadSystem3` micro-opt refactor

**Files:** `external/voxy` commits between `337b919d` and `02dfb1b7`, specifically `SaveLoadSystem3.java` (6-line change), `WorldUpdater.java` (19-line change), `Mapper.java` (6-line change).

```java
// SaveLoadSystem3.java diff:
- int emptyBlockCount = 0;
+ int notEmpty = 0;
  for (long block : blockData) {
-     emptyBlockCount += Mapper.isAir(block) ? 1 : 0;
+     notEmpty += Mapper.isNotAirInt(block);
  }
- section.nonEmptyBlockCount = WorldSection.SECTION_VOLUME-emptyBlockCount;
+ section.nonEmptyBlockCount = notEmpty;

// Mapper.java new helper:
+ public static int isNotAirInt(long id) {
+     return Math.min(getBlockId(id), 1);
+ }
```

**Why this matters for #235:** the entire 4-commit audit window (2026-08-10 to 2026-08-29) changed **only this** plus build/JMH scaffolding. The packed-`long` layout, the section key encoding, the Mipper rule, the `nonEmptyChildren` semantics -- every invariant Voxygen depends on -- is **unchanged**. This is the empirical evidence that `VoxelVolumeWriter` is the right seam: it absorbs this kind of churn, and the audit shows the churn is what we get in a fast-moving `dev` branch.

---

## 7. Quantitative residual/oracle experiment (cheapest available)

Per the ticket §6 ("Bound the ML role instead of training a giant model"), this section separates two experiments with **different evidentiary strength**: a synthetic Mipper-survival probe (heuristic only) and the authoritative vanilla→Voxy post-ingest residual, which requires #233's oracle.

### 7.1 What can be measured today, without #233

The Voxygen project has:

- `python/voxel_tree/voxy_format/decoder.py` -- round-trips Voxy on-disk format against a YZX-linear layout, tested for signed 24/8-bit edge cases, asymmetric nibbles, Morton bijection.
- `python/voxel_tree/voxy_format.py` -- encode/decode primitives for `make_key/decode_key/encode_voxel/decode_voxel/yzx_index/lin2z/z2lin`, mirrored against the audited jar.
- `python/voxel_tree/contracts/terrain_signals` -- executable mirror of `docs/reference/upstream/minecraft-1.21.11-terrain-signal-lattices.md`.
- `python/data-cli.py` -- `pregen → voxy-import → dumpnoise → extract-octree → column-heights-octree → build-octree-pairs` pipeline (currently Modrinth-coupled).
- `python/voxel_tree/tests/` -- the unit-test surface for the above.

What is **not** yet available (per the ticket's "if not, state exactly what #233 capability is required"):

- An independent vanilla→Voxy post-ingest oracle that produces per-coord expected voxel values for `(seed, dim, pos)`.
- A Measurement Protocol (`python/voxel_tree/contracts/measurement_protocol.py` or similar) that defines residual definitions per `FidelityProfile`.

### 7.2 Two experiments with different evidentiary strength -- do not conflate them

- **E1 -- synthetic Mipper-survival probe (heuristic evidence only; needs a thin capture harness).** Places one vanilla feature through a real placement chain, applies the known Mipper rule in Python, and compares against an empty-region mip. This measures *how much of a feature's silhouette survives the mip rule in isolation*. It needs no #233 infrastructure. **Missing link:** the existing infrastructure can decode Voxy and apply the mip semantics, but there is **no executable entry point today that runs an actual vanilla `PlacedFeature` plus its actual modifier chain and captures the resulting `BlockPos` set** — E1 requires a thin capture harness (a small Java runner against the pinned 1.21.11 corpus, or an equivalent headless driver) before it can execute. It also does **not** pass through real vanilla→Voxy ingestion and therefore does **not** establish the authoritative post-ingest residual.
- **E2 -- authoritative post-ingest residual (blocked on #233).** Compares candidate output against the post-ingest Voxy representation of authoritative vanilla terrain. This is the residual the winner rule actually needs, and it requires #233's independent oracle harness (or equivalent independent evidence) -- see §7.1 for exactly which #233 capabilities are missing.

E1 design (single, fixed 1.21.11 corpus + pinned Voxy 0.2.11-alpha; one internally consistent dimension/biome/feature chain taken from actual registration and placement source):

1. Choose the **End** dimension, the `end_highlands` biome, and `EndSpikeFeature` + `EndSpikeConfiguration` (registered in `EndFeatures`; placed via the End placement chain). **Verify before running** that the chosen scenario actually exercises the generic `PlacedFeature` pathway (registry → placed-feature entry → modifier chain → `Feature.place`) rather than a structure-set or direct-spawn path that bypasses it; if it does not, pick a feature that does.
2. Run the vanilla feature with its actual `PlacementModifier` chain (e.g. `InSquarePlacement` + `HeightmapPlacement` + `BiomeFilter` + `CountPlacement`).
3. Capture the (deterministic) set of `BlockPos` per placement in a small JSON fixture.
4. Mip the result through the Voxy mip rule (5 lines of Python using `voxy_format`).
5. Compare the mip output against an empty-region mip: a single-feature silhouette measured in voxels, not pixels.

Cost: ~200 lines of Python, no new Java, no training, no model. The result is a single number per `(dim, biome, feature_config, modifier_chain)` -- the cheapest possible empirical "how much does this feature survive at L4?". **E1 is heuristic evidence about the mip rule only; it must not be reported as the authoritative vanilla→Voxy residual.** E2 waits on #233 (or equivalent independent evidence). The §9 follow-ups and §4.4 (bounded port of a single feature family) are the consumers of either number.

### 7.3 What the partition can do with this number

| Residual | Implication for #85 |
|---|---|
| ≈ 0 (feature mips to air) | **Omit** for L4/L3; consider **omit** for L2; for L1/L0 vanilla generates directly |
| Small non-zero (silhouette hint) | **Deterministic approximation** at L4/L3 (e.g. one column block per spike); **port or approx** at L2; **port** at L1/L0 |
| Large (feature fills its bounding box at mip) | **Port** at L4 if cost permits; **learned residual** as alternative |

The current **End vertical slice** demonstrates deterministic approximation for **base End terrain only** (`EndL4DeterministicCandidate` produces an `air | end_stone` vocabulary and **intentionally omits placed features, explicitly including obsidian pillars and gateways**, recorded as honest omission). It provides **no evidence yet** about the mip survival or approximation quality of `EndSpikeFeature`. The experiment above would produce the first such evidence.

---

## 8. Version-support recommendation (the ticket's §7)

> Adding a Minecraft/Voxy version has a bounded, auditable maintenance path rather than an open-ended worldgen rewrite.

Based on the audit:

| Change | Required regeneration | Required adapter change | Required model retraining |
|---|---|---|---|
| Voxy micro-patch (`0.2.11` → `0.2.12`) on the same MC version | None | Re-pin jar SHA-256; update imports in `RealVoxyVolumeWriter` if storage config moved | None, if Mipper unchanged (per §5.2 evidence) |
| Voxy minor (storage backend refactor) | Re-pin jar SHA-256; re-run storage audit | Mechanical import/config update; `VoxelVolumeWriter` interface unchanged | None |
| Voxy Mipper rule change | **Re-derive the post-ingest oracle target** (ADR 0015); existing fixtures become a different evidence population | None | **Full retraining** of all learned models |
| Voxy LOD geometry change (L0..L4 levels change) | Re-pin; re-audit `WorldEngine` key encoding | `Level` ↔ Voxy `lvl` mapping must be re-derived | **Full retraining** if Level-to-LOD mapping is learned |
| Minecraft minor (1.21.x → 1.21.x+1) | None, if `NoiseRouter` 15-field interface is preserved (likely; unverified) | None if interface preserved | None for port; **retraining** for any model that consumes biome/feature IDs |
| Minecraft minor (1.21.x → 1.22.x) -- assuming flat-router interface preserved | None | None | None for port |
| Minecraft minor with new biome | Re-derive `BiomeMapping` | Update canonical biome ID table; `BiomeMapping` shape unchanged | Retraining for any per-biome learned model |
| Minecraft minor with new `FeatureConfiguration` or `PlacementModifier` | Re-extract feature/placement profile | Add new `*Configuration` record to the Voxygen port (if ported) | None for port; retraining for learned per-feature |
| Minecraft minor with bespoke feature family added (e.g. a new coral type) | Re-extract feature profile | New `Feature` impl to port (if any Level needs it) | Optional learned residual for the new family |

The path is bounded: **every change has a regeneration vs adapter vs retraining classification**. None of the changes above is an "open-ended worldgen rewrite" if the partition (per #85) commits to `reuse vanilla` or `exact port` for the volatile surfaces (climate, height, surface, carver) and `learned approximation` only for the bespoke feature families that survive mip.

### 8.1 The required invariants for the recommendation to hold

- The `NoiseRouter` 15-field record shape is preserved across MC versions (strong expectation, unverified locally).
- The Voxy Mipper rule is preserved across Voxy minor versions (**verified only for the 2026-08-10 → 2026-08-29 audited window**; full-history stability unverified -- §9.1 item 2).
- The 32³ `WorldSection` geometry and packed-`long` layout are preserved across Voxy versions (**verified only for the same audited window**; broader history surveyed by file presence only -- §5.3).
- The `PlacedFeature.placeWithContext` shape is preserved across MC versions (strong expectation, unverified locally).
- The `BiomeMapping` canonical-ID shape is preserved (the Voxygen contract, not an upstream promise; re-derivable per MC version).

If any of these breaks, the recompute cost is the per-version port listed above -- bounded, not open-ended.

---

## 9. Uncertainties and cheapest next experiments

### 9.1 Uncertainties not resolvable from current evidence

1. **Cross-Minecraft-version noise/biome/feature stability** -- only one MC corpus local. Fix (a **required #235 deliverable**, not a follow-up ticket): obtain one or more deliberately selected Minecraft comparison corpora via the existing `external/` provisioning path -- chosen to cross a meaningful biome/feature/worldgen change so stable machinery can be distinguished from release-specific behavior -- then re-run §§2-4 as a **semantic-responsibility diff** (generic placement machinery, configuration schemas, registry/profile content, representative bespoke feature families), not a class-name diff. Note: a server jar for another version existing in the repository tree is **not** a version-pinned decompiled comparison corpus; the corpus must be procured and pinned with the same provenance shape as §1.1.
2. **Whether Voxy's Mipper rule has been stable across the full 2-year Voxy history** -- only the 19-day audit window is verified here. Cheapest fix: `git -C external/voxy log -p -- src/main/java/me/cortex/voxy/common/world/other/Mipper.java` across `origin/master..origin/dev`; classify each commit as mip-rule-change vs unrelated edit.
3. **Whether ADR 0015's "post-ingest Voxy parity" holds for an L2 (8-block voxel) mip of an end-island End biome** -- the existing End vertical slice proves L4/L3 for `end_highlands` chorus only; L2/L1/L0 across more biomes is unmeasured. Cheapest fix: extend `EndDimensionSynthesizer` L2/L1 walk to non-chorus End biomes and measure against the existing flight-template End regions.
4. **The cost of porting the 16 `PlacementModifier` impls** -- the audit shows the size (~50 KB) and the abstract shape, but no measurement of Voxygen-on-Java vs vanilla-Java runtime for the same placed-feature chain. Cheapest fix: a single `PlacedFeatureBenchmark` test that runs one `BiomeFilter + CountPlacement + InSquarePlacement` chain in `external/minecraft-src` against the Voxygen port and compares `t/chain`.

### 9.2 Open seams (preserved-futures)

- **Per-dimension `Mipper` override**: the audit confirms the mip rule is shared across dimensions, but a per-dimension override (e.g. End vs Overworld spike-mip) is a preserve-futures item.
- **Per-Learned-residual cost bound**: the cheap experiment in §7.2 measures residual size; the cost bound (training time + data volume + runtime) is not measured here and is a preserve-futures item for the feature-generation skill (#234).
- **Cross-Voxy-version target re-derivation script**: when the Mipper rule does change, the regeneration script should be a single command that re-derives the post-ingest oracle target from a `git ref` of Voxy. This is not in scope for #235 but is the natural extension of §5.5.

### 9.3 The two issues that consume this research

- **#85 (`worldgen-partition`)** -- the HITL partition decision. The candidate seam table in §4 and the Voxygen version-support recommendation in §8 are the partition's input. The partition does **not** happen here.
- **#234 (future feature-generation skill)** -- the feature-learning skill. The §3.3 stability table and the §7 cheap experiment are the skill's input. The skill does **not** happen here.

---

## 10. Winner-rule check

The ticket's success criterion:

> The research succeeds if it materially narrows #85 by identifying boundaries where:
> - stable/shared computation can be owned once without frequent semantic rewrites;
> - version-specific content is moved into data/profile adapters or a bounded learned residual;
> - ML output space is dramatically smaller than full block prediction;
> - L4/L3 runtime remains compatible with rapid broad-area generation on ordinary hardware;
> - adding a Minecraft/Voxy version has a bounded, auditable maintenance path rather than an open-ended worldgen rewrite.
>
> A valid outcome may conclude that some apparently generic machinery is too coupled or volatile to port, or that some biome-specific logic is cheap/stable enough that ML is unnecessary. Do not force the hybrid hypothesis to win.

The audit:

- ✓ §3.1-3.2: stable/shared machinery is small and a strong shared-code candidate (16 `PlacementModifier` + 38 `FeatureConfiguration` records + ~50 `Feature` impls + `PlacedFeature.placeWithContext` shape = ~250-300 KB total).
- ✓ §3.3, §4: version-specific content is overwhelmingly data (~316 KB of registries + 33 KB of `BiomeDefaultFeatures` wiring).
- ✗ **Not yet measured -- ML output space**: §6.3/§7 *expect* the ML output space to be dramatically smaller than full block prediction for any single bespoke feature family (a column-block mask for `EndSpikeFeature` is ~5% of the bits a full `VoxelVolume` would carry), but §7.2's probe has not been executed and the authoritative residual requires #233's oracle. This criterion is **unchecked** until a number exists.
- ✓ §5.2: L4/L3 runtime (post-ingest Voxy mip target) is unchanged across the **audited** Voxy window (`337b919d` → `02dfb1b7`), so existing L4/L3 generation remains compatible over that interval. Broader Voxy-history stability is unverified (§5.3, §9.1 item 2).
- ✓ §8: version-support recommendation has bounded regeneration / adapter / retraining classifications per change type (with the §8.1 invariants explicitly marked verified-window-only where applicable).
- ✗ **Not yet satisfied -- research-program §1**: the cross-Minecraft-version responsibility diff has **not** been produced (only one MC corpus is vendored; no comparison corpus procured). Every cross-MC-version stability claim in this document is therefore bounded to `none-local` evidence, and the winner-rule verdict below is provisional until that diff lands.

The research also records:

- A **do-not-port** finding: the 10 feature registries + 11 placement registries + 4 biome registries are data and should be a profile, not code (§3.3).
- A **do-not-learn** finding: the 16 `PlacementModifier` machinery is stable and cheap on 1.21.11 evidence — learning it is unjustified; whether to port it is #85's call (§3.2).
- A **decision-deferred** finding: the 50 `Feature` impls are bespoke and each is a separate empirical question for #85; no blanket port/learn/omit decision is made here.

The hybrid hypothesis is supported for **most** responsibilities **on 1.21.11-only evidence** and is **not** forced: §6.3 records that `EndSpikeFeature` could be cheap enough to port, and the End vertical slice proves "deterministic approximation" works for base End terrain (features are honestly omitted, not approximated — §7.3). This verdict is **provisional** pending the cross-MC-version diff (research-program §1) and the residual measurement (§7.2 E2 / #233). The partition (#85) can choose, with measured evidence, between deterministic approximation and learned residual per family.

---

## 11. Evidence index (primary sources)

**Minecraft 1.21.11 / 26.1-snapshot-11** (all under `external/minecraft-src/src/net/minecraft/...`, CFR 0.152 decompiled corpus, jar SHA-256 `556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822`):

- `world/level/chunk/status/ChunkStatus.java:28-41`, `ChunkPyramid.java:18` -- ordering DAG (companion DAG doc §1)
- `world/level/levelgen/NoiseRouter.java:17`, `NoiseRouterData.java:24-293` -- 15-field record + bootstrap
- `world/level/levelgen/DensityFunction.java:20-107`, `DensityFunctions.java:49-398` -- combinator zoo
- `world/level/levelgen/NoiseChunk.java:42-437` -- cell grid + caches
- `world/level/levelgen/NoiseSettings.java:23`, `NoiseGeneratorSettings.java:35,70` -- per-dimension presets
- `world/level/levelgen/RandomState.java:28-132` -- seeded wiring
- `world/level/levelgen/synth/{NormalNoise,PerlinNoise,ImprovedNoise,SimplexNoise,BlendedNoise}.java` -- octave stack
- `world/level/levelgen/Aquifer.java:28-265` -- Voronoi spacing
- `world/level/levelgen/OreVeinifier.java:15` -- vein block-state filler
- `world/level/levelgen/SurfaceSystem.java:77`, `SurfaceRules.java` -- surface veneer
- `world/level/biome/{BiomeSource,MultiNoiseBiomeSource,Climate,TheEndBiomeSource}.java` -- biome placement
- `world/level/levelgen/placement/{PlacedFeature,PlacementModifier,PlacementContext,BiomeFilter,BlockPredicateFilter,CountOnEveryLayerPlacement,CountPlacement,EnvironmentScanPlacement,FixedPlacement,HeightRangePlacement,HeightmapPlacement,InSquarePlacement,NoiseBasedCountPlacement,NoiseThresholdCountPlacement,RarityFilter,RandomOffsetPlacement,RepeatingPlacement,SurfaceRelativeThresholdFilter,SurfaceWaterDepthFilter,CaveSurface,PlacementFilter,PlacementModifierType}.java` -- placement machinery (16 modifiers)
- `world/level/levelgen/feature/{Feature,ConfiguredFeature,FeaturePlaceContext,FeatureCountTracker}.java` + ~50 `Feature` impls including `TreeFeature.java`, `DripstoneClusterFeature.java`, `DripstoneUtils.java`, `EndSpikeFeature.java`, `EndPodiumFeature.java`, `EndPlatformFeature.java`, `EndGatewayFeature.java`, `GeodeFeature.java`, `FossilFeature.java`, `FossilFeatureConfiguration.java`, `CoralClawFeature.java`, `CoralFeature.java`, `CoralMushroomFeature.java`, `CoralTreeFeature.java`, `BasaltColumnsFeature.java`, `BasaltPillarFeature.java`, `BambooFeature.java`, `AbstractHugeMushroomFeature.java`, `DesertWellFeature.java`, `BlueIceFeature.java`, `BonusChestFeature.java`, `BlockBlobFeature.java`, `BlockColumnFeature.java`, `BlockPileFeature.java`, `DeltaFeature.java`, `DiskFeature.java`, `FallenTreeFeature.java`, `FillLayerFeature.java`, etc.
- `world/level/levelgen/feature/configurations/*.java` (38 `FeatureConfiguration` records)
- `world/level/levelgen/carver/*.java` -- carver machinery
- `data/worldgen/{NoiseData,TerrainProvider,SurfaceRuleData,BiomeDefaultFeatures,Carvers,DimensionTypes}.java` -- registry data
- `data/worldgen/features/*.java` (10 files: AquaticFeatures, CaveFeatures, EndFeatures, FeatureUtils, MiscOverworldFeatures, NetherFeatures, OreFeatures, PileFeatures, TreeFeatures, VegetationFeatures)
- `data/worldgen/placement/*.java` (11 files: AquaticPlacements, CavePlacements, EndPlacements, MiscOverworldPlacements, NetherPlacements, OrePlacements, PlacementUtils, TreePlacements, VegetationPlacements, VillagePlacements)
- `data/worldgen/biome/*.java` (4 files: BiomeData, EndBiomes, NetherBiomes, OverworldBiomes)

**Voxy** (`external/voxy` git refs):

- `337b919d` (2026-08-10) -- audited Voxy 0.2.11-alpha
- `02dfb1b7` (2026-08-29) -- current `origin/dev`
- `581d48e2` (2024-07-26, master), `e8dd71de` (2025-05-29, inverted_nether), `2327c6ba` (2025-06-18, mc_1215), `5458d9f9` (2025-09-15, mc_1217_mesh3), `a06fc754` (2025-10-04, mc_1217), `cad8d593` (2026-02-04, mc12110), `7e924a45` (2026-04-15, revz), `59b62bee` (2026-05-25, 12111), `91c96528` (2026-07-04, 2622), `4643445e` (2026-07-21, 2612)
- `git diff --stat 337b919d..02dfb1b7` and per-file `git diff` for `SaveLoadSystem3.java`, `WorldUpdater.java`, `Mapper.java`

**Fabric API** (`external/fabric-api`, `gradle.properties version=0.143.11 minecraft_version=26.1-snapshot-11`).

**Voxygen** (`java/src/main/java/...`):

- `voxy/WorldNoiseAccess.java` -- single L1 server-noise entry; canonical factory bundle
- `voxy/AnchorSampler.java:68-78` -- `BiomeMapping.toCanonicalId` (54-entry alpha for OW)
- `voxy/HeightmapFallbackGenerator.java` -- stateless semantic fallback (synthetic path)
- `voxy/LodGenerationService.java:218-220,482,610-730,739-760` -- metric counters + column-context null-guard
- `world/noise/NoiseRouterSamplerFactory.java:40-180` -- factory + hot-swap
- `world/noise/{VanillaNoiseRouterSampler,VanillaHeightmapProvider,VanillaBiomeProvider}.java` -- vanilla adapters
- `world/noise/RouterField.java:17-61` -- 15-field `COUNT=15` enum
- `voxy/VoxelVolumeWriter.java` (interface), `RealVoxyVolumeWriter.java` (impl), `InMemoryVolumeWriter.java` (test impl) -- the L3 deep seam
- `voxy/VoxyCompat.java` -- Voxy surface
- ADR 0013, 0014, 0015 -- sampler-direct End base terrain; dimension-partitioned synthesizer seam; post-ingest Voxy mip parity target

**Voxygen python** (`python/voxel_tree/`):

- `voxy_format.py` -- encode/decode primitives mirroring the audited jar
- `voxy_format/decoder.py` -- round-trip tests
- `tests/test_voxy_format.py` -- signed 24/8-bit, asymmetric nibbles, Morton bijection, YZX reshape
- `contracts/{registry,spec}.py` -- frozen `ModelContract` versioning
- `data-cli.py` -- pregen/voxy-import pipeline

---

## 12. Cross-references

- Companion DAG doc: `minecraft-1.21.11-worldgen-dag-overworld-nether-end.md` (sibling)
- Companion seams doc (per-class internals): `../reference/upstream/minecraft-1.21.11-worldgen-seams.md`
- Companion Voxy storage doc: `../reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md`
- Companion Voxy format doc: `../reference/upstream/VOXY-FORMAT.md`
- Companion L1 availability contract: `l1-availability-contract.md`
- Companion Layer-2 batch subtree-sharing: `port-vanilla-batch-subtree-sharing.md`
- Companion terrain-signal lattices (executable mirror): `../reference/upstream/minecraft-1.21.11-terrain-signal-lattices.md`
- Voxygen ADRs: 0013 (sampler-direct End base terrain), 0014 (dimension-partitioned synthesizer seam), 0015 (post-ingest Voxy mip parity)
- Wayfinder issues: #22 (map), #82 (decomposition), #83 (lattices), #85 (partition -- this research feeds it), #233 (oracle -- referenced), #234 (feature-generation skill -- referenced), #235 (this research)

---
*Version-pinned research for Minecraft 26.1-snapshot-11 (`556C0FA7…436822`) and Voxy 0.2.11-alpha (`337b919d`, jar `63d174…7920c`) plus Voxy 11-branch git-history evidence, as inspected 2026-08-28. Research only -- not partition decisions. Newer Minecraft or Voxy versions require a separately versioned document.*
