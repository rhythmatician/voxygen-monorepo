# 0013 — Sampler-direct End base-terrain generation

Date: 2026-08-27
Status: Accepted

## Context

The End L4→L1 tracer produces base terrain through exact vanilla noise evaluation. The current mechanism reuses the vanilla pipeline wholesale: for each chunk it materializes a `ProtoChunk`, invokes `NoiseChunkGenerator.populateNoise()`, joins the future, and reads blocks out of the resulting chunk. This is correct but pays for work the tracer never consumes.

Reading the unobfuscated vanilla sources (`external/minecraft-src`) establishes what `populateNoise` actually does beyond density sampling (`NoiseBasedChunkGenerator.doFill`, lines ~289–352):

1. **Interpolated density walk** — the same `ChunkNoiseSampler` state machine (`sampleStartDensity` → `sampleEndDensity` → `onSampledCellCorners` → `interpolateY/X/Z`) our existing fast heightmap path already mirrors in `WorldNoiseAccess.sampleBothHeightmaps`.
2. **Section block writes** — writes into `LevelChunkSection`s of a chunk we then discard.
3. **Heightmap updates** — `OCEAN_FLOOR_WG` / `WORLD_SURFACE_WG` tracking we never consume on this path.
4. **Aquifer fluid postprocessing** — gated by `aquifer.shouldScheduleFluidUpdate()`.

For The End specifically:

- `NoiseGeneratorSettings.end()` constructs settings with `aquifersEnabled=false` (`NoiseGeneratorSettings.java:70`, positional mapping onto the record at `:35`). `NoiseChunk` therefore installs `Aquifer.createDisabled(...)` (`NoiseChunk.java:151–152`), so side effect 4 is dead code.
- `END_NOISE_SETTINGS = NoiseSettings.create(0, 128, 2, 1)` (`NoiseSettings.java:27`): vanilla's own generation domain for The End is exactly Y ∈ [0, 128). Rows outside a requested world-section's Y band are not merely unneeded by Voxygen — vanilla itself never generates them for this dimension.
- The beardifier is passed as `DensityFunctions.BeardifierMarker.INSTANCE` (no structure beards), and blending is `Blender.empty()`; no cross-row density coupling exists.

The per-block side-effect inventory is therefore closed over the band: every action outside the requested section's Y range either feeds a structure we discard or is gated off entirely for The End.

Flight telemetry corroborates redundancy: the height oracle probed 16 columns with zero positives (max observed surface Y = 0), confirming the tracer's demand sits at the bottom of the End's [0, 128) column.

## Decision

End base-terrain production uses a **direct `ChunkNoiseSampler(4)` walk restricted to the requested world-section's Y band** — one sampler per chunk, loop structure identical to `doFill`'s interpolation state machine, emitting blocks only where `blockY ∈ [sectionBlockMin, sectionBlockMax)`. Cells whose entire block range misses the band are skipped wholesale; their corner densities can never feed an in-band row.

The ProtoChunk + `populateNoise().join()` mechanism is retired for this path. It is not kept behind a flag; git history retains it.

The band is derived from `WorldSectionCoord.worldSectionToBlockMin/Max(wsY, level)`, not hardcoded — a future descent to `wsY = 1` covers [64, 128) through the same rule.

This applies to the End tracer only. Overworld/Nether paths keep full-column walks because those dimensions genuinely span larger generation domains where out-of-band rows are vanilla-real.

## Alternatives considered

* **Keep ProtoChunk reuse, batch chunks per session**: no direct win; the wasted per-block work scales with chunk count regardless of batching.
* **Trim generation height via config**: mutates shared vanilla semantics globally to serve one caller; high blast radius for a local win.
* **Skip only out-of-band *emission*, keep full-column interpolation**: safe but leaves most of the win on the table; cell-level skipping is sound because interpolation state advances per-cell, not per-column-history.
* **Parallel sibling production**: stacks later as a separate scheduling concern; orthogonal to this mechanism choice.

## Consequences

* Per-chunk cost drops by the ratio of skipped rows plus elimination of ProtoChunk allocation and chunk-join overhead (~2–4× expected on the L2→L1 path; measured before/after via flight telemetry).
* The pinned-path guard test pins the new sampler-walk method; the ProtoChunk path has no test coverage and no callers.
* The height oracle is retired as dead code.
* Future dimensions inheriting this mechanism must re-run the side-effect inventory: aquifer gating, beardifier presence, and generation-domain bounds are dimension-specific facts verified against unobfuscated sources, not assumptions carried forward.
* Exactness claim is unchanged: the emitted voxels are the same interpolated density decisions vanilla makes inside the band; correctness verification remains the real-Voxy topology suite plus flight-loop comparison against baseline output.
