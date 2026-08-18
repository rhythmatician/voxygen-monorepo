# 0002 — Drop router6 conditioning (retain biome+heightmap)

Date: 2026-03-XX (decision), 2026-08-16 (recorded)
Status: Accepted

## Context

The original model accepted `x_router6 [1,6,16,16] float32` — six 2D noise maps (temperature, vegetation/humidity, continentalness, erosion, depth, ridges) sampled from Minecraft's multi-noise / NoiseRouter via cubiomes. All 53K+ NPZ training files contain only `labels16`, `biome_patch`, `heightmap_patch`, `y_index` — router6 was approximated via `approximate_router6_from_biome()` (inverse biome→noise mapping). Feature-bundle LOD generation is L4→L1 only; L0 (16³ block-level) is vanilla. The shadow-router and NoiseTap wiring were unbuilt infrastructure blocking training.

Extracted from `python/docs/NOISE-DESIGN.md` (Historical — March 2026; file deleted per the approved #42 inventory — this ADR is the retained rationale) — superseded architecture tables (No LOD0, four transitions) are not preserved here. Successors: `CONTEXT.md` (canonical language), per-Level contracts L0–L4 from PR #36, dense baseline.

## Decision

Remove `x_router6` entirely from architecture, ONNX contract, and data pipeline. Conditioning is `height_planes [B,5,16,16] + biome_idx [B,16,16] + y_index [B] + parent_voxel [B,1,P,P,P]` (refinement). v3 ONNX contract is init (`height+biome+y → block_logits`) and refinement (`+parent`). `AnchorConditioningFusion` narrows from 3 streams to 2 (thirds, not quarters; biome stream enlarged). Cubiomes CLI `climate` command, `router6_patch` in NPZ, and `NoiseTap`→`UnifiedModelRunner` wiring are deferred.

## Why

* **Biome already encodes router6 outcome.** `biome = f(temperature, vegetation, continentalness, erosion, depth, ridges)` — many-to-one; heightmap already captures depth/continentalness/erosion/ridges combined. Supplying both is redundant for coarse LODs.
* **No real router6 data.** Training reconstructed noise from biome — circular/redundant, loses nothing by dropping.
* **No LOD0 generation.** Coarse L4→L1 terrain needs only biome+heightmap+y_index; router6 value would only matter at L0 cave/overhang detail (vanilla-owned).
* **Unblocks pipeline.** Eliminates unbuilt cubiomes `climate`, NoiseTap, and offline extractor blockers with zero new tooling.

## Alternatives considered

* Keep 3-stream fusion (height+router6+biome) and extend cubiomes CLI with `climate` (6 floats per coord via `sampleBiomeNoise`), re-extract 53K NPZs, widen ONNX to `x_router6 [1,6,16,16]`, wire `NoiseTap` — rejected as redundant + data-missing + infra-heavy.
* Hybrid: keep router6 for L2→L1 only — rejected, same redundancy at coarse scale.

## Consequences

* `LodGenerationService` needs real heightmaps/biomes/y_index from chunk/NoiseTap cache (not synthetic), no router6 tensor in `UnifiedModelRunner`.
* Existing Python `chunk_extractor.py` / `dataset_respec.py` router6 normalisation and Java `NoiseTap.java` (15 fields, 4 tiers) remain but unused — available for future ablation.
* Reintroduction only justified if L0 (8³→16³) is added or an L2→L1 ablation shows measurable gain with real noise data (requires cubiomes `climate`, re-extraction, 3-stream widen, contract update, `NoiseTap` wiring — see deferred design in archived `NOISE-DESIGN.md`).

## When to reconsider

If block-level L0 generation is added or a controlled ablation proves raw noise beats biome-only at coarse LOD with *real* (not approximated) data — reintroduce via the deferred CLI+extraction+fusion+contract path.
