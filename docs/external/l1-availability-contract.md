# Research: L1 availability contract — WorldNoiseAccess → fallback matrix

> **Status:** wayfinder:research — AFK, version-bound — do not edit to describe a different code revision
> doc-type: external-reference
> source-revision: b22c79b (HEAD) + Minecraft 1.21.11 (26.1-snapshot-11, CFR 0.152, `external/minecraft-src/src`) + Voxy 0.2.11-alpha (`voxy-0.2.11-alpha.jar`)
> **Upstream project:** Minecraft (Mojang) / Voxy / Fabric API — queried via local `java/` seam
> **Source corpus inspected:** `java/src/main/java/com/rhythmatician/lodiffusion/voxy/WorldNoiseAccess.java`, `java/src/main/java/com/rhythmatician/lodiffusion/voxy/AnchorSampler.java`, `java/src/main/java/com/rhythmatician/lodiffusion/voxy/HeightmapFallbackGenerator.java`, `java/src/main/java/com/rhythmatician/lodiffusion/voxy/LodGenerationService.java` (buildColumnContext / buildHeightmap / counters), `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/UpstreamNoiseContext.java`, `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/NoiseRouterSamplerFactory.java`, `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/VanillaHeightmapProvider.java`, `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/VanillaBiomeProvider.java`, `java/src/main/java/com/rhythmatician/lodiffusion/world/noise/VanillaNoiseRouterSampler.java`, `java/src/client/java/com/rhythmatician/lodiffusion/LodiffusionClient.java`, `external/minecraft-src/src/net/minecraft/world/gen/chunk/ChunkNoiseSampler.java`, `external/minecraft-src/src/net/minecraft/world/gen/noise/NoiseConfig.java`, `external/fabric-api` (lifecycle hooks only)
> **Research date:** 2026-08-16
> **Wayfinder map:** #22 · ticket #91 · companion evidence `research-scratch.md` §2, `research-vanilla-seams.md` §§2-4

> **Scope:** Documents **only** the Layer-1 seam `WorldNoiseAccess` and its fallback contract. Does not design Layer-2 ported batch/GPU (`NoiseRouterSampler`) or Layer-3 write path (`VoxelVolumeWriter`). All claims below are grounded in files listed above at the pinned revision.
> **Invalidation rule:** If `WorldNoiseAccess`, `LodGenerationService.buildColumnContext`, `NoiseRouterSamplerFactory`, or `HeightmapFallbackGenerator` change semantics, or Minecraft `NoiseConfig`/`ChunkNoiseSampler` API changes, re-verify this document against the new sources; do not silently edit to describe a different revision.

---

## TL;DR — one matrix, one rule, no second stack

**Only `WorldNoiseAccess.tryCreate(ServerWorld)` is the server-noise entry.** Everything downstream (`AnchorSampler`, `VanillaNoiseRouterSampler`, `VanillaHeightmapProvider`, `VanillaBiomeProvider`, and via `NoiseRouterSamplerFactory.getUpstreamContext()` the `UpstreamNoiseContext` triple) hangs off the `NoiseConfig`/`BiomeSource`/`ServerWorld` it exposes. **Fabric adds only lifecycle hooks** (`ServerLifecycle`, `ClientPlayConnectionEvents.INIT`, `ClientTickEvents`) — not values.

| Runtime | `noiseAccess` | Height source | Biome source | Density / Router | Metric incremented | Consumer safety |
|---|---|---|---|---|---|---|
| **Integrated server** (singleplayer / LAN host) — `MinecraftServer != null`, `ServerWorld` for `dimKey` exists, `NoiseConfig` available | non-null (`WorldNoiseAccess`) bound to `ServerWorld`/`ChunkGenerator`/`NoiseConfig`/`BiomeSource` | `sampleBothHeightmaps(sectionX,sectionZ)` — one `ChunkNoiseSampler(4)` yields **both** `WORLD_SURFACE_WG` + `OCEAN_FLOOR_WG` (5 `sampleStartDensity`/`sampleEndDensity` calls per 16×16 chunk vs 512 with per-column samplers → ~64×; see `WorldNoiseAccess.java:210-320`, `VanillaHeightmapProvider.java:70-160`) | `sampleBiomeNames(sectionX,sectionZ, heightmap)` → quart `4×4` → expanded `16×16`, then `BiomeMapping.toCanonicalId` (54-entry alpha) (`WorldNoiseAccess.java:364-400`, `AnchorSampler.java:63-85`) | All 15 `RouterField` via `VanillaNoiseRouterSampler.sampleSection` at `4×2×4` quart (`SectionNoiseData` 480 floats) — same 15 `DensityFunction`s vanilla uses (`VanillaNoiseRouterSampler.java:60-120`) | `noiseAccessSections` (`LodGenerationService.java:220,668`) | `LodGenerationService.buildColumnContext` takes this path only when `noiseAccess != null`; `AnchorSampler.sampleFromNoise` assumes non-null (NPE if mis-called) — guard lives at the service, not per-consumer duplication |
| **Dedicated-client / null / pre-world-bind** — `MinecraftServer == null` or `ServerWorld == null` or `NoiseConfig == null` (dedicated server without integrated server, or before `LodGenerationService.start()` binds) | `null` (both `tryCreate(MinecraftServer, World)` and `tryCreate(ServerWorld)` are null-safe, log and return null; `WorldNoiseAccess.java:104-155`) | **Synthetic fallback** — `LodGenerationService.buildHeightmap(sectionX,sectionZ)` multi-octave sine (`SEA_LEVEL=62`, `HEIGHT_AMPLITUDE=24`, 3 octaves at 0.005/0.007/0.013/0.011/0.037/0.029 rad/block → 40-90 block-Y, clamped 0-320; `LodGenerationService.java:739-760`) **or** loaded-chunk fallback `AnchorSampler.sampleHeightmap(chunk)` if a `ChunkStatus.FULL` chunk happens to be loaded | **Constant biome** — `int[16][16]` filled with `1` (or `AnchorSampler.sampleBiomes(chunk)` if chunk present; `LodGenerationService.java:678-695`) | **No router** — `SectionNoiseData` unavailable; model path falls through to `HeightmapFallbackGenerator` (stateless 7 `SurfaceType` → 12 `FallbackBlockIds` → `pickBlockId`) or synthetic-conditioned ONNX path without router fields | `syntheticDataSections` (no noise, no chunk) or `realDataSections` (loaded chunk without noise) (`LodGenerationService.java:218-219,680,691`) | All consumers guarded: `buildColumnContext` checks `noiseAccess != null` before touching `UpstreamNoiseContext`/`AnchorSampler.sampleFromNoise`; synthetic branch never dereferences `noiseAccess`; `HeightmapFallbackGenerator` is stateless and never reads `NoiseConfig` |
| **Degenerate vanilla generator** (e.g. flat/superflat) — `generator` not `NoiseChunkGenerator` | non-null `WorldNoiseAccess` but heightmap fast-path bails | `sampleHeightmap(sectionX,sectionZ, Heightmap.Type.*)` per-column `generator.getHeight(...)` (`WorldNoiseAccess.java:214-216`) | same as integrated row | same as integrated row (router still available if `NoiseConfig` exists) | `noiseAccessSections` | same guard as integrated row |

**Rule:** Every consumer of `WorldNoiseAccess` must be **null-safe via the single service guard** (`if (noiseAccess != null) { … } else { loaded-chunk ? real : synthetic }` in `LodGenerationService.buildColumnContext`, `LodGenerationService.java:620-710`). No consumer invents its own `tryCreate` or second `ChunkNoiseSampler`/`DensityFunction` tree. There is **no second sampling stack** — grep of `ChunkNoiseSampler` shows only `WorldNoiseAccess.sampleBothHeightmaps` and `VanillaHeightmapProvider.sampleHeightmaps` (the latter is the same vanilla path exposed via `UpstreamNoiseContext`); `HeightmapFallbackGenerator` uses zero noise APIs. Fabric's `ClientChunkEvents`/`ServerLifecycleEvents`/`ClientTickEvents` only decide *when* to bind/drain (`LodiffusionClient.java:41-90`, `GpuNoiseDispatchQueue.java:23`), not *what* height/biome values are.

---

## 1. Factory and bundle contract

`NoiseRouterSamplerFactory` is the **only** place that materializes the L1/L2 bundle.

- **Canonical factory:** `create(ServerWorld, ChunkGenerator, BiomeSource, NoiseConfig)` (`NoiseRouterSamplerFactory.java:40-70`) — captures the four vanilla handles atomically. `1-arg create(NoiseConfig)` is `@Deprecated` and cannot build `UpstreamNoiseContext` (throws `IllegalStateException` if `getUpstreamContext()` is called; `NoiseRouterSamplerFactory.java:140-180`).
- **Hot-swap:** `getSampler()` and `getUpstreamContext()` poll `Config.terrainBackend()` on every call, double-checked lock, `close()` old sampler, invalidate `activeContext` (`NoiseRouterSamplerFactory.java:90-160`). No caller caches a stale sampler; `backendName()` triple `"vanilla_cpu / vanilla_cpu / vanilla_cpu"` (or `gpu / gpu / gpu` when `GpuNoiseRouterSampler`/`GpuHeightmapProvider`/`GpuBiomeProvider` are wired) is the only observable.
- **Sole contact surface:** `UpstreamNoiseContext(NoiseRouterSampler, HeightmapProvider, BiomeProvider)` (`UpstreamNoiseContext.java:15-40`) validates non-null, `close()`s all three, and is the only import downstream code may hold — `NoiseRouter`/`DensityFunction`/`WorldNoiseAccess` never leak past the factory (enforced by package javadoc).
- **`WorldNoiseAccess` is the factory's input, not its replacement.** `LodGenerationService.start()` does `noiseAccess = WorldNoiseAccess.tryCreate(server, world); if (noiseAccess != null) samplerFactory = NoiseRouterSamplerFactory.create(noiseAccess.serverWorld(), noiseAccess.generator(), noiseAccess.biomeSource(), noiseAccess.noiseConfig())` (`LodGenerationService.java:482-495`). If `tryCreate` returned null, `samplerFactory` stays null and `buildColumnContext` never touches `getUpstreamContext()`.

## 2. Heightmap + biome sampling — one fast path, one fallback

### 2.1 Real path (integrated server)

- `sampleBothHeightmaps(sectionX,sectionZ)` (`WorldNoiseAccess.java:212-320`): one `ChunkNoiseSampler(hCells=4, …)` covering the whole `16×16` chunk (4 horizontal cells × `cellWidth=4`, `cellHeight` from `GenerationShapeConfig.trimHeight(serverWorld)` — overworld `4×8`). Loops `sampleStartDensity()` + 4× `sampleEndDensity(o)` + `onSampledCellCorners(r,p)` + per-block `interpolateY/X/Z` + `sampleBlockState()` top-down, recording first `WORLD_SURFACE_WG` (`NOT_AIR`) and `OCEAN_FLOOR_WG` (`MATERIAL_MOTION_BLOCKING`) per column. Thread-safe: each call owns its sampler state.
- `sampleHeightmap(sectionX,sectionZ, Heightmap.Type)` (`WorldNoiseAccess.java:328-345`): per-column `generator.getHeight(..., serverWorld, noiseConfig)` — pure computation, slower, used only for non-`NoiseChunkGenerator` flat worlds.
- `sampleBiomeNames(sectionX,sectionZ, heightmap)` (`WorldNoiseAccess.java:364-400`): samples quart lattice `bx = baseX+qx*4+2, bz = baseZ+qz*4+2`, `surfaceY = heightmap[qx*4][qz*4]`, then `biomeSource.getBiome(bx>>2, surfaceY>>2, bz>>2, noiseConfig.getMultiNoiseSampler())` → registry key string → `BiomeMapping.toCanonicalId` (`AnchorSampler.java:68-78`).
- Height-plane derivation (`AnchorSampler.computeHeightPlanes`, `AnchorSampler.java:150-220`): downsamples `16×16 → 4×4` at stride 4 (density-cell resolution), normalizes by `HEIGHT_RANGE=320`, computes `slopeX/slopeZ/curvature` via central/one-sided differences on the `4×4` normalized surface, ocean floor via `min(surface, SEA_LEVEL)` fallback if `oceanFloorHm == null`.

### 2.2 Fallback path (dedicated client / null)

- `LodGenerationService.buildColumnContext` priority (`LodGenerationService.java:610-730`): (1) `noiseAccess != null` → `samplerFactory.getUpstreamContext()` → `HeightmapData` + `BiomeNames` → `computeHeightPlanes` → `noiseAccessSections++`; (2) else `tryGetLoadedChunk(FULL)` → `AnchorSampler.sampleHeightmap(chunk)` + `sampleBiomes(chunk)` → `realDataSections++`; (3) else `buildHeightmap(sectionX,sectionZ)` + `int[16][16]{1}` → `syntheticDataSections++`. The column result is cached in `columnContextCache` (`ConcurrentHashMap<Long, ColumnContext>`) keyed by `(sx,sZ)` so all Y sections in a column share one heightmap/biome sample.
- `buildHeightmap` (`LodGenerationService.java:739-760`): deterministic sine `h = 62 + 12·sin(0.005·bx+1.7)·cos(0.007·bz+0.3) + 6·sin(0.013·bx+3.1)·sin(0.011·bz+2.2) + 2.88·cos(0.037·bx+0.9)·sin(0.029·bz+4.1)` clamped `0..320`. Adjacent sections share consistent terrain because `bx/bz` are global block coords (`sectionX*16+lx`).
- `HeightmapFallbackGenerator` (`HeightmapFallbackGenerator.java:30-250`): stateless semantic fallback for the write path (not the heightmap path) — 7 `SurfaceType` (GRASS/SAND/RED_SAND/GRAVEL/STONE/SNOW/PODZOL/MYCELIUM) derived from canonical biome index, `pickBlockId(worldY, groundBlockY, waterSurfaceBlockY, surfaceType, FallbackBlockIds)` implements `y<0→deepslate, 0..surface-3→stone, top 3→SurfaceType, ground≤y<waterSurface→water, y≥ground && y≥SEA_LEVEL && snowy→snowLayer else air`. Uses pre-resolved `FallbackBlockIds` via `Mapper.getIdForBlockState` reflection; never touches `NoiseConfig`.

## 3. Null-safety and metric contract

- `WorldNoiseAccess.tryCreate(MinecraftServer, World)` (`WorldNoiseAccess.java:104-122`): `if (server==null) log+return null; dimKey=clientWorld.getRegistryKey(); serverWorld=server.getWorld(dimKey); if (serverWorld==null) log+return null; return tryCreate(serverWorld)` — null-safe, never throws.
- `WorldNoiseAccess.tryCreate(ServerWorld)` (`WorldNoiseAccess.java:140-155`): `gen = serverWorld.getChunkManager().getChunkGenerator(); nc = tryGetNoiseConfig(serverWorld) // serverWorld.getChunkManager().getNoiseConfig()`; `if (nc==null) log+return null; return new WorldNoiseAccess(...)` — try/catch around whole body returns null on any exception.
- **Consumer null-safety is centralized:** `AnchorSampler.sampleFromNoise(WorldNoiseAccess,…)` assumes non-null (dereferences `sampleBothHeightmaps`); `VanillaNoiseRouterSampler`/`VanillaHeightmapProvider`/`VanillaBiomeProvider` constructors require non-null `NoiseConfig`/`BiomeSource`/`ServerWorld`. No consumer does its own `tryCreate`. The only null check in the codebase is at `LodGenerationService.buildColumnContext` and `runWorker` bind — grep for `noiseAccess != null` shows exactly two sites (`LodGenerationService.java:630,482`). Test `L1AvailabilityContractTest` (see `java/src/test/java/com/rhythmatician/lodiffusion/voxy/L1AvailabilityContractTest.java`) proves `WorldNoiseAccess.tryCreate(null,…)` returns null without NPE and the synthetic fallback is deterministic without a second noise stack.
- **Metrics:** `AtomicInteger realDataSections / syntheticDataSections / noiseAccessSections / skippedAirSections` (`LodGenerationService.java:218-220`) reset on `start()`, incremented in `buildColumnContext`, exposed via `LodGenerationService.getInstance()` for `PerformanceMonitor`/`TerrainGenerationBenchmark` telemetry. No other counters exist — this triple is the availability matrix in code.

## 4. Fabric API — lifecycle only

Fabric adds **no terrain values**. Verified against `external/fabric-api` and `java/src/client/java/com/rhythmatician/lodiffusion/LodiffusionClient.java`:

- `ClientPlayConnectionEvents.INIT` → `preloadModel()` (`LodiffusionClient.java:43`);
- `ClientPlayConnectionEvents.JOIN` → `LOD_SERVICE.start(client.world, client.getServer())` which binds `WorldNoiseAccess` (`LodiffusionClient.java:51`);
- `ClientPlayConnectionEvents.DISCONNECT` → `LOD_SERVICE.stop()` (`LodiffusionClient.java:80`);
- `ClientTickEvents.END_CLIENT_TICK` → `updatePlayerPosition` + `GpuNoiseDispatchQueue.tickDrain()` (GL thread) (`LodiffusionClient.java:87`, `GpuNoiseDispatchQueue.java:23`).

The value tap is vanilla API (`NoiseConfig`, `DensityFunction`, `ChunkNoiseSampler`, `BiomeSource`, `LevelHeightAccessor`, `SectionPos`); Fabric is pure scheduling.

## 5. What this rules out

- **No second sampling stack:** No code path creates a `DensityFunction` tree or `ChunkNoiseSampler` outside `WorldNoiseAccess`/`VanillaHeightmapProvider`. `HeightmapFallbackGenerator` and `LodGenerationService.buildHeightmap` are noise-free. A grep for `getHeight(` outside `WorldNoiseAccess` shows only the flat-world fallback branch, not a parallel tap.
- **No per-consumer null checks:** Consumers do not defensively handle `null` `WorldNoiseAccess`; they rely on `LodGenerationService` to not call them. Adding ad-hoc null guards inside `AnchorSampler` or `VanillaNoiseRouterSampler` would hide the matrix; keep the single guard.
- **No heightmap without biomes:** The real path always samples both (`sampleBothHeightmaps` + `sampleBiomeNames` + `computeHeightPlanes` atomically in `buildColumnContext`). Splitting them would create partial-conditioning hazards for the model.

## 6. Verification

- **Doc verification:** Diff has shown this file was `A` (new) and passes `R-02` because it is under `docs/external/` with `doc-type: external-reference` and `source-revision`.
- **Test verification:** `L1AvailabilityContractTest` — 6 unit tests: `tryCreate(null) → null` (two overloads), synthetic fallback deterministic + range-clamped, `sampleFromNoise(null)` throws (proving guard lives at service), synthetic biomes constant. Run `gradlew test --tests "com.rhythmatician.lodiffusion.voxy.L1AvailabilityContractTest"` (no Voxy/Minecraft runtime required; pure JUnit + reflection for private `buildHeightmap`).
- **Negative check:** `rg -n "ChunkNoiseSampler|DensityFunction" java/src/main/java --type java` returns only the files listed in scope; no hidden second stack.

## 7. Open questions (out of scope for L1)

L1 does not answer Layer-2 tiling/lattice or Layer-3 write-path decisions — those are #83/#85/#86. Dimension threading (`dimKey`) is preserved end-to-end but Nether/End `NoiseSettings` swaps (`NoiseSettings.java:25` `8×4` vs `4×8`) are validated at the `NoiseRouterSampler` level, not at `WorldNoiseAccess` bind (which is dimension-agnostic once `ServerWorld` is resolved).
