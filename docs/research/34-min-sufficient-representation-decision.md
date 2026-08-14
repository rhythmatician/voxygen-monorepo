# Research Decision — Minimum Sufficient Terrain Representation per Level (L4–L0)

> **Ticket:** #34 `wayfinder:research` · Part of #22 `Wayfinder Map — Voxygen Performance Program`
> **Branch:** `research/34-min-sufficient-representation` · **Status:** decision artifact (no training required)
> **Source of truth:** `docs/research/wayfinder-research-min-sufficient-representation.md` (draft, 153 lines) — this file is its published decision pointer.
> **Date:** 2026-08-14 · **Author:** rhythmatician (AFK research subagent, inline fallback after `lineage_integrity_failed`)

## 1. Investigation against primary sources

Every claim below traces to the source that owns it.

* Wayfinder map and frontier: #22 body (`gh issue view 22 --json body`, governing rule "Do not optimize an inference we can avoid…") and children #23–#28 (`wayfinder:task/research/grilling` list, verified via `gh api repos/.../issues/22/sub_issues` → 7 children `[23,24,25,26,27,28,34]`).
* Draft experiments/exit criteria: `docs/research/wayfinder-research-min-sufficient-representation.md:1-153` (§1 Goal, §2 Why now, §3 Experiments 1–7, §4 Measurements, §5 Deliverable, §6 Non-goals, Appendix seams).
* Why now seams: `RouterField.java:1-73` (15-field canonical boundary), `SectionNoiseData.java:1-87` (`float[15][4][2][4]=480`, `flatIndex=field*32+qx*8+qy*4+qz`), `VanillaNoiseRouterSampler.java:18-128` (quart centres `base+2,4,2`), `HeightmapData.java:1-50` (`worldSurface/oceanFloor 16×16`), `HeightmapProvider.java`, `GpuHeightmapProvider.java:114-152`, `HeightmapFallbackGenerator.java:1-338` (8-way `SurfaceType` over top-3 layers, `SEA_LEVEL=63`), `Level.java:1-41` (`L0..L4`, `regionSections()=1<<(value+1)`), `VoxelVolume.java:1-175` (`extent ∈ {16,32}`, `blockId 0=air`), `RealVoxyVolumeWriter.java:1-300` + `VoxyWorldBinding.java:34-62` (YZX `long[32768]`, VarHandle CAS), `LodGenerationService.java:36-1502` (demand queue, `generationRadius=32`), `python/voxel_tree/tasks/voxy/voxy_models.py:428,491,539,599,658,721` (five `VoxyL4..L0`, `513×32³`, `ParentEncoder(513→16)`), `python/voxel_tree/tasks/voxy/build_voxy_pairs.py:108,504` (`vocab_remap 1104→513`, `>> (level+1)` join), `python/voxel_tree/tasks/voxy/voxy_dataset.py`.
* Non-normative control: `docs/baselines/dense-voxy-v1.md:1-200` (SHA `26dd20e`, "Historical / control baseline — NOT architectural authority", per-Level conditioning shapes, `64 MiB` per `513×32³` forward).
* Audit: `docs/MINECRAFT_NOISE_TO_VOXY_RESEARCH.md:1-202` (worldgen DAG, 15-field table, sampling resolution §1.3, Heightmap seam §1.4, Surface rules §1.5, Voxy storage §2, shortcut pipeline §3, representation shift §4.1, hard invariant `mod-optimization.md:86` + `LodGenerationService:440-537` "must never trigger vanilla chunk generation").
* Corpus: `graphify-out/GRAPH_REPORT.md:1-14` (329 files, 4773 nodes) and `graphify-out/graph.json` communities `RouterField`, `SectionNoiseData`, `VoxyModelRunner`, `LodGenerationService`, `VoxelVolume`.
* Canonical language: `CONTEXT.md:1-28` (`SectionPos`, `Level`, `VoxelVolume`, `VoxelPredictionDecoder`, `VoxelVolumeWriter`, `WriteOutcome`), `GLOSSARY.md:6-310` (NoiseRouter/Router6 legacy, Heightmap, Biome, DensityFunction, WorldSection `32³ voxels`).
* Dispatch boundary: `/.sandcastle/dispatch.mts:16-45` (`FORBIDDEN_WAYFINDER_LABELS` includes `wayfinder:research`, `REQUIRED_LABEL=agent:implement`, Wayfinder task triple-signal `Execution is carried into this map`).

## 2. What this research decides (and does not)

* **Goal — per #34:** For each Level L4..L0, answer what information must be represented, what can be computed directly from Minecraft worldgen, what requires learned approximation, what can be procedural/sparse, and what representation produces acceptable rendered parity at lowest measured cost. Heterogeneous outcome allowed: `L4 shortcut / L3 shortcut / L2 hybrid / L1 dense residual / L0 deferred` are *examples*, not answers (`wayfinder-research-min-sufficient-representation.md:25-32`, #34 body Question/Goal).
* **Not implementation:** Do not build the `SHARED WORLDGEN SCAFFOLD → painter / residual / feature proxies → LOD-AWARE RASTERIZER` pipeline, do not promote `Dense Voxy v1` to contract, do not implement #24/#25 or #7 (`wayfinder-research-min-sufficient-representation.md:125-131`, #34 Non-goals).
* **Use cheap oracles first:** Prefer structural/oracle experiments 1–7 below; no full production retraining to answer the question (`#34 Experiments`).

## 3. Experiments — cheap structural/oracle first (no training required)

All against existing GT `voxy_sections(level,ws_x,y,z,labels32[32³])` joined via `>> (level+1)` with `vocab_remap.json 1104→513` (`build_voxy_pairs.py:108,504`, `voxy_dataset.py`). For each Level `L4..L1` (`L0` to verify whether learned L0 needed vs. vanilla ownership):

1. **Perfect height + water** — derive `height[32,32]/waterMask` from `FINAL_DENSITY` zero-crossing + `SEA_LEVEL=63` (and `PRELIMINARY_SURFACE_LEVEL` + aquifer fields where cheap). Reconstruct `32³` as `air above, solid below, water between`. Source: `RouterField.java:45-62` (density group), `HeightmapData.java`, `GpuHeightmapProvider.java:114-152`.
2. **+ perfect surface/material** — add biome-conditioned surface band (top-3 via oracle `SurfaceType` from GT column or GT material). This is `MATERIAL PAINTER` in 2D. Source: `HeightmapFallbackGenerator.java:26-85` (`GRASS/SAND/RED_SAND/GRAVEL/STONE/SNOW/PODZOL/MYCELIUM`), `GLOSSARY.md:Heightmap`.
3. **+ simplified feature proxies** — oracle feature blocks via block-type set `{logs,leaves,ice,mushroom…}` as dense mask or `instance{type,x,z,size}` stamped by LOD proxy (L4 canopy mass → L3 coarse mass → L2 template → L1 detailed). Measure sparse vs dense parity. Source: `MINECRAFT_NOISE_TO_VOXY_RESEARCH.md:§4`, `mod-optimization.md:41-46` (instance vs 36M-logit dense head).
4. **Remaining 3D residual** — `GT ⊖ reconstruction` after 1–3: sparse 3D exception field (overhangs, arches, exposed cavities, floating shapes, externally-visible caves). Source: `MINECRAFT_NOISE_TO_VOXY_RESEARCH.md:§4.2-4.3`.
5. **Rendered-pixel impact** — same 1–4 but screen-space error at intended viewing distance (L4 5–20 km silhouette vs L1 near-field), not just voxel argmax. Source: `dense-voxy-v1.md:§6` (rendered-pixel metric).
6. **Native worldgen cost vs inference cost** — benchmark `NoiseRouter`/`HeightmapData` eval (`VanillaNoiseRouterSampler` CPU vs `GpuNoiseRouterSampler` `CompletableFuture.get(500ms)` + `WARN_LOG_INTERVAL_MS=5000`) against per-Level ONNX latency (`VoxyModelRunner hasOccupancy=false`, `intraOp=4`). Answers "is learning cheaper than simplified vanilla eval?" Source: `GpuNoiseRouterSampler.java:37`, `LodGenerationService.java:36-135`.
7. **Conditional information / residual entropy** — `H(residual | height,biome,y)` per Level, ideally `H(residual | previous levels' scaffold)`. Directly measures what learned model still invents. Source: `wayfinder-research-min-sufficient-representation.md:88`.

**Shared vs chained conditioning (compare):** (a) *shared description* (each Level from same `height/biome/water/material` scaffold) vs (b) *chained raster* (`L4 32³ → upsample → L3 …` as in baseline `ParentEncoder` path `parent_blocks [32³] int64 → ParentEncoder(513→16) → concat` `voxy_models.py:74`). Hypothesis: (a) removes serialization/upsampling/error propagation (`wayfinder-research-min-sufficient-representation.md:90`, `MINECRAFT_NOISE_TO_VOXY_RESEARCH.md:4.1`).

## 4. Measurements — same harness as dense control

Use thin harness from `dense-voxy-v1.md:§6-7` so claims comparable:

* Per-stage `voxel accuracy / surface MAE / silhouette IoU / material agreement / feature recall` + rendered-pixel metric (5).
* Per-component cost: conditioning (`noise`/`heightmap`/`biome`), inference (if any), decode (`decodeArgmaxFromLogits` `VoxyModelRunner.java:596` → `VoxelPredictionDecoder.fromOctreeArgmax`), writer (`RealVoxyVolumeWriter.writeRegion` YZX/CAS), queue latency, spawn→horizon; peak RSS/GC; `64 MiB` per `513×32³` forward reference (`dense-voxy-v1.md:§6`).
* At minimum per Level: terrain/occupancy fidelity; surface/height fidelity; semantic/block fidelity; topology/continuity failures; visible artifacts at Level's screen-space scale; runtime work; conditioning/input footprint; output/data-transfer footprint; inference count; progressive refinement into next Level (`#34 Measurements`).

## 5. Per-Level exit criterion — disposition table

Research is complete only when artifact states for **each** of L4,L3,L2,L1,L0 (per `#34 Done when` and `wayfinder-research-min-sufficient-representation.md:112-122`):

| Field per Level | L4 | L3 | L2 | L1 | L0 |
|---|---|---|---|---|---|
| **Selected representation family** | *to be filled by oracle* — scaffold/shortcut vs hybrid vs dense residual vs deferred | same | same | same | same |
| **Why sufficient** | numbers: surface MAE / silhouette IoU / rendered-pixel at 5–20 km + cost | at L3 distance | at L2 distance | at L1 near-field | at L0 1:1 |
| **Information enters** | `height[32,32]+waterMask+biome[32,32]+y+material band?` vs `SectionNoiseData[15,4,2,4]` | same decision | same | `noise_3d[15,16,8,16]`? | `noise_3d[15,8,4,8]`? |
| **Learned approximation?** | 2D painter? / absent? | 2D? | sparse 3D? | dense residual? | none/vanilla? |
| **If learned: 2D / 3D / hybrid / dense residual / absent** | hypothesis: L4 2D or absent | L3 2D/light | L2 hybrid | L1 dense residual | L0 deferred |
| **Runtime inference remains** | e.g. `height+material` deterministic + optional canopy mass | similar | `+ 8³ residual`? | full `32³` where residual large | vanilla chunk gen? |
| **Handed to next finer Level** | shared scaffold (`height/biome/water/material`) | refined height+material | + feature instances + coarse residual `8³` | + fine residual `16³/32³` sparse | — |
| **#24 survives?** | `does not apply` if shortcut else `applies partially` | same 4-way | same | `applies unchanged/partially` if dense residual compatible | same |
| **#25 survives?** | same 4-way (ONNX ArgMax→Gather only if dense logits exist) | same | same | same | same |
| **15-field RouterField required?** | `no` if heightfield suffices (§1.2-1.4) | maybe `PRELIMINARY/FINAL_DENSITY` only | `yes` for aquifers/veins? | likely `yes` for caves | `yes` if learned else `no` |
| **Unresolved risks / prototype needed** | e.g. canopy fidelity at horizon | feature recall | sparse residual sparsity threshold | cave-overhang residual size `H(...)` | vanilla ownership vs learned quality |

**Illustrative (not prescriptive) sufficiency from draft `§4`:** `L4 → deterministic scaffold + canopy proxy (≈91–99% sufficiency)`, `L3 → scaffold + lightweight corrections/features`, `L2 → scaffold + sparse 3D residual + instances`, `L1 → richer 3D learned residual`, `L0 → retain dense / defer / unnecessary` — or vindication of dense U-Nets if residual large and vanilla eval not cheaper (`wayfinder-research-min-sufficient-representation.md:98-106`).

**Routing:** `shortcut wins → design new contracts` vs `dense survives → #24 → #25` vs `heterogeneous` above (`wayfinder-research-min-sufficient-representation.md:20-32`, `dense-voxy-v1.md:§8`). Outcome routes frontier without committing prematurely to "all dense" or "all shortcut."

### Dense Voxy v1 boundary (non-normative)

* Five independent `VoxyL4..L0` U-Nets, `513×32³` heads (`L4 24×32×32` Y-trimmed), `ParentEncoder(513→16)`, `has_occupancy=false` (`voxy_models.py:721`, `voxy_export.py:398`, `dense-voxy-v1.md:§2`). Footprints: L4 `128×64×128` cells → `6×8×8 2D`, L1 `16×8×16` native 3D, L0 `8×4×8` (`dense-voxy-v1.md:§3`). Control only — do not freeze to `int[]` YZX/Morton (`VoxelVolume.java` opaque XYZ).

### RouterField boundary (not obsolete)

Old 6-channel `router6` (`temperature,vegetation,continentalness,erosion,depth,ridges`) dropped March 2026 per `GLOSSARY.md:Router6` and `python/docs/NOISE-DESIGN.md`, but 15-field `RouterField`/`SectionNoiseData` boundary (`TEMPERATURE,VEGETATION,CONTINENTS,EROSION,DEPTH,RIDGES,PRELIMINARY_SURFACE_LEVEL,FINAL_DENSITY,BARRIER,FLUID_LEVEL_FLOODEDNESS,FLUID_LEVEL_SPREAD,LAVA,VEIN_TOGGLE,VEIN_RIDGED,VEIN_GAP` `RouterField.java:17-62`) remains materially present. This research determines per-Level whether those 3D fields are durable contract; that blocks parked #7 RouterField single-source-generation (do not solve #7 here) (`#34 Dependencies`, `MINECRAFT_NOISE_TO_VOXY_RESEARCH.md:1.2`).

### #24 / #25 conditional branches

#24 live L0–L4 contracts / canonical block-ID output and #25 ONNX ArgMax→canonical IDs (`logits → ArgMax → local ID → Gather(local_to_canonical) → canonical ID`, `int64 32³ 256 KiB` vs `FP32 513×32³ 64 MiB`) are *not* rejected — they apply conditionally where dense/learned compatible form survives; where L4/L3 move to scaffold they do not apply uniformly (`#34 Dependencies`, `#22 Preserve Futures checkpoint: after export/runtime before vocab-audit`). Requires per-Level 4-way statement above.

## 6. Dependencies / informs

* **Does NOT block #23** `T_horizon` fresh-spawn benchmark + telemetry — useful regardless (`#34 Dependencies`).
* **Informs:** #24/#25 applicability; future model slimming/architecture; conditioning-cache design (Phase 4); RouterField contract (#7); scheduler cost assumptions; which Levels receive expensive inference (`#34 Relationship to #22`).
* **Feeds from #26/#27** vocab-audits when they land; oracle experiments start immediately against existing GT (`voxy_sections` via `build_voxy_pairs.py:504`).
* **Baseline:** `dense-voxy-v1.md` + thin harness `baseline_metrics.json` (`dense-voxy-v1.md:§6-7`).

## 7. Non-goals / guardrails

* Train full new production family to answer question; optimize ONNX kernels; Java micro-optimization; implement #24/#25 or RouterField single-source; decide all Levels share one architecture; delete dense baseline; assume identical visual fidelity at every Level; turn speculative architecture into contract (`#34 Non-goals`, `wayfinder-research-min-sufficient-representation.md:125-131`).
* Preserve invariant: distant LOD must never trigger vanilla chunk generation — sample via `NoiseConfig/DensityFunction` or shadow-router SSBOs (`mod-optimization.md:86`, `LodGenerationService:440-537`, `HeightmapFallbackGenerator` proof `height+biome+SEA_LEVEL` suffices for fallback `VoxelVolume(16)`).

## 8. How to reproduce / next steps

1. Pin seeds/worlds, run experiments 1–7 via same harness (`dense-voxy-v1.md:§7`) on fixed `voxy_sections` GT, joining via `vocab_remap.json` LUT (`build_voxy_pairs.py:108`).
2. Record `voxel accuracy / surface MAE / silhouette IoU / rendered-pixel` per stage + `conditioning/inference/decode/writer/queue` P50/P95 + `H(residual|…)` per Level with method hashes/seeds.
3. Fill §5 table with numbers and explicit routing decision; graduate Wayfinder fog and rewrite frontier; keep this file and `dense-voxy-v1.md` unchanged, create `docs/baselines/shortcut-v1.md` for winner if needed (`dense-voxy-v1.md:§9`).

Primary seams for next work: `RouterField.java`, `SectionNoiseData.java`, `VanillaNoiseRouterSampler.java`, `GpuNoiseRouterSampler.java`, `HeightmapData.java`, `HeightmapProvider.java`, `Level.java`, `VoxelVolume.java`, `RealVoxyVolumeWriter.java`, `HeightmapFallbackGenerator.java`, `LodGenerationService.java`, `voxy_models.py`, `voxy_export.py`, `build_voxy_pairs.py`, `voxy_dataset.py`, `CanonicalRegistries.java`, `external/voxy/.../Mapper.java` (full index `MINECRAFT_NOISE_TO_VOXY_RESEARCH.md:§5`).

---

*This artifact publishes `docs/research/wayfinder-research-min-sufficient-representation.md` as decision pointer; keep body in sync per `#34 Source artifact`. Type: `wayfinder:research`.*
