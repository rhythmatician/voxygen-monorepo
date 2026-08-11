## 🔭 **LODiffusion — Minecraft Mod (Fabric 1.21.11)**

### 🎯 **Mission**

Render plausible terrain for far chunks via an **octree‑based LOD pipeline** driven by **VoxelTree** models, keeping strong parity with vanilla and tight compatibility with **Distant Horizons** (DH). Priorities: correctness → stability → speed.

---

## 🗺️ **Project Roadmap**

### **PHASE 0 — Developer Infrastructure (Complete ✅)**

* Fabric mod scaffolding (Java 17, Gradle 8.x), CI (Actions) + JaCoCo + Lint
* TDD setup (JUnit 5 + Mockito), deterministic test fixtures and seeds

---

### **PHASE 1 — Core LOD Engine & Runtime Contracts (✅ Complete)**

**Goal:** Replace the old "single diffusion pass" with an **octree‑traversal pipeline** and shared input contract with VoxelTree.

> ✅ All Phase 1 components (`OctreeModelRunner`, `LodGenerationService`, etc.) are implemented and exercised by integration/unit tests in the current codebase.

**What’s new**

* **Three octree models**: `octree_init` (L4 seed), `octree_refine` (shared for L3→L2→L1), `octree_leaf` (final L0 leaf 16³) – vanilla handles empty/ambient LOD0
* **Top-down distance-gated pipeline** (new target architecture): L4 init all, then L3/L2/L1/ L0 conditionally as player nears. Avoid `mipSection()` for generated data.
* **Shared conditioning inputs** (identical across all models):

  * `x_height_planes` **[1,5,16,16]** float32 — surface, ocean\_floor, slope\_x, slope\_z, curvature
  * `x_biome` **[1,16,16]** int64 — vanilla biome index per (x,z)
  * `x_y_index` **[1]** int64 — vertical slab index [0,23]
* **Per-stage parent prior** (refinement models only):

  * `x_parent` **[1,1,P,P,P]** float32 — previous stage output; P ∈ {1,2,4} (absent for Init)
* **Outputs (per stage)**: `block_logits [1,N,D,D,D]`, `air_mask [1,1,D,D,D]` where D∈{1,2,4,8}; Java runner upsamples final stage 2× to 16³

**Rules**

* **ONNX models produce static shapes.** Octree leaves are 32³; `OctreeModelRunner` handles upsampling and spawning child tasks.
* **Vanilla `carve()` at LOD0 only.** Distant terrain skips carve; near terrain (LOD0) calls vanilla carve to finalize caves/aquifers/structures.

**Deliverables**

* `LodGenerationService` (octree controller + spiral ordering), `OctreeModelRunner` (three‑model inference), `VoxyBlockMapper` + `VoxySectionWriter` (post-process + write), `AnchorSampler` / `NoiseTap` (feature capture)

---

### **PHASE 2 — World Integration & Noise Capture (✅ Complete)**

**Goal:** Gather the *same* signals vanilla has at generation time, cache at source granularity (no upsampling).

**Components**

* **NoiseTap** (runtime sampler):

  * Heightmaps: 16×16 (surface, ocean\_floor, slope\_x, slope\_z, curvature) → `x_height_planes [1,5,16,16]` float32
  * Biomes: 16×16 vanilla biome indices → `x_biome [1,16,16]` int64
  * Vertical slab index → `x_y_index [1]` int64 [0,23]
* **FeatureCache**:

  * In-memory LRU keyed by `ChunkPos`
  * Optional sidecar: `lod_cache/<dim>/<region>/c.<x>.<z>.nf.bin` (or `.npz`)
  * Strict immutability (except `x_parent` from prev stage)

**Testing**

* Parity tests: cached fields vs direct API reads (epsilon match)
* Determinism: same seed + coords → same cached tensors

---

### **PHASE 3 — DJL Inference & Model Lifecycle (✅ Implemented)**

**Goal:** Robust, fast, memory-safe inference for four models.

**Tasks (completed)**

* **ONNX loader (DJL ONNX Runtime)**: shared `ModelZoo`, lazy load per model – implemented and exercised by `OctreeModelRunner.loadAll()`
* **`OctreeModelRunner`**: map `AnchorSampler` output → ONNX inputs, run init/refine/leaf rounds – full breadth‑first pipeline in production
* **Refinement loop**:

  1. `init_to_lod4` (D=1) → `x_parent` for next stage
  2. `refine_lod4_to_lod3` (D=2) → `refine_lod3_to_lod2` (D=4) → `refine_lod2_to_lod1` (D=8)
  3. Upsample or split leaf output; write to Voxy via `VoxySectionWriter`
* **Perf controls**: per-stage timers (via `PerformanceMonitor`), NDManager pooling, configurable memory/thread caps

> Note: warm inference on an under‑trained model is already ≈60 ms/patch; cold startup still high but will improve once models are fully trained.

**Acceptance criteria (work in progress)**

* Numerical parity with VoxelTree's `*_test_vectors.npz` – infrastructure exists but golden vectors still need export and Java harness implementation
* Total per-chunk inference time < 100ms on target CPU (currently met with prototype weights)

---

### **PHASE 4 — DH Integration & LOD Policy (🆕 In-progress)**

**Goal:** Only generate as much as needed for current DH LOD.

**Features (core implemented, policy tuning ongoing)**

* `LODManagerCompat` / `DistantHorizonsCompat`: compile‑only DH LOD query wrappers already integrated and tested in CI
* **Work policy**:

  * LOD4/3/2: build `x_parent` progressively from each stage output (already happening)
  * LOD1→0 promotion: run final model (16³) + **vanilla carve()** (proof‑of‑concept present)
* **Edge blending**: planned use of `air_mask` for smooth borders; implementation pending when seam strategy finalised
* **Switches**: vanilla vs AI, per-LOD enable/disable, overlay debug – configuration scaffolding present

---

### **PHASE 5 — UI, Debug, and Metrics (🆕 Planned)**

* Toggles: model packs on/off, optional channels on/off
* Visual overlays: `air_mask`, seam highlighters, shadow router output inspector
* Counters: cache hit/miss, sampling ms, inference ms per stage

---

### **PHASE 6 — Packaging & Distribution (🆕 Planned)**

* Bundle: 4 × `.onnx` (`init_to_lod4`, `refine_lod4_to_lod3`, `refine_lod3_to_lod2`, `refine_lod2_to_lod1`), 4 × `_config.json`, `pipeline_manifest.json`, 4 × `_test_vectors.npz`
* Settings: JSON/TOML for toggles + paths
* Releases: Modrinth/CurseForge artifacts; version gate on Fabric/Yarn

---

## 🧱 **Module Breakdown (Updated)**

| Module                    | Purpose                                                                 | Status                      |
| ------------------------- | ----------------------------------------------------------------------- | --------------------------- |
| `NoiseTap` / `NoiseDumperCommand` | Capture vanilla signals: router6, heightmaps, biomes (`/dumpnoise`)  | ✅ Implemented              |
| `AnchorSampler`           | Sample height planes + router6 for model input                          | ✅ Implemented              |
| `LodGenerationService`    | 4-pass LOD generation (LOD4→LOD1), spiral ordering, parent cache        | ✅ Implemented              |
| `FeatureCache`            | Chunk-level anchor cache (in-memory LRU + optional disk sidecar)        | ✅ Implemented              |
| `VoxyBlockMapper`         | Map model vocab indices → Voxy block IDs via `model_config.json`        | ✅ Implemented              |
| `PerformanceMonitor`      | Lightweight timers and logging helpers for profiling                   | ✅ Added                    |
| `VoxySectionWriter`       | Argmax → air mask → pack voxels → push to Voxy via reflection            | ✅ Implemented              |
| `VoxyCompat`              | Pure-reflection bridge to Voxy API (no compile-time dependency)          | ✅ Implemented              |
| `BlockVocabulary`         | Load block→ID mapping from `model_config.json`                          | ✅ Implemented              |
| `DistantHorizonsCompat`   | DH LOD queries + safe guards                                            | ✅ Implemented              |
| `LodiffusionCommand`      | In-game control: `/lodiffusion status\|toggle\|performance\|reload`       | ✅ Implemented              |
| `Diagnostics`             | Per-section timers, performance counters, debug overlay                  | ✅ Implemented (expanded)   |
| **Shadow Router (GPU path)** | | |
| `ShadowRouterExtractor`   | Walk live vanilla `NoiseRouter` at world load; serialize all noise params to `ShadowRouterData` NIO buffers for SSBO upload | ✅ Implemented |
| `ShaderSSBOManager`       | Allocate & upload 8 GPU SSBOs (bindings 0–7) from `ShadowRouterData`; holds GPU buffer lifetime | ✅ Implemented |
| `TerrainComputeDispatcher` | Issue one GL compute dispatch per chunk column; update `RouterConfig` UBO with chunk coords | ✅ Implemented |
| `ShadowRouterJobQueue`    | Thread-safe priority queue fed by `VoxyShadowBridgeMixin`; supplies work to dispatcher | ✅ Implemented |
| `WorldGenEventHandler`    | Bootstrap shadow router at world load: extract → upload SSBOs → build biome palette | ✅ Implemented |

---

## 🔗 **Interface with VoxelTree (Exact Contract)**

**VoxelTree delivers (contract `lodiffusion.v5.octree`):**

1. Three ONNX model files (opset 17, static shapes):
   - `octree_init.onnx`
   - `octree_refine.onnx`
   - `octree_leaf.onnx`
2. Three sidecar configs: `octree_init_config.json`, `octree_refine_config.json`, `octree_leaf_config.json`
   * Each contains: input/output names, block vocabulary (`block_mapping`), normalization specs
3. `pipeline_manifest.json` — lists all required files; validated at startup
4. Three test-vector files: `*_test_vectors.npz` (golden inputs → outputs per model)

**Per-model tensor contract:**

| Model | `x_height_planes` | `x_biome` | `x_y_index` | `x_parent` | Output `block_logits` | Output `occ_logits` |
|-------|------------------|-----------|--------------|------------|---------------------|-------------------|
| `octree_init`   | [1,5,32,32] float32 | [1,32,32] int64 | [1] int64 | — | [1,N,32,32,32] | [1,8] |
| `octree_refine` | [1,5,32,32] float32 | [1,32,32] int64 | [1] int64 | [1,32,32,32] int64* | [1,N,32,32,32] | [1,8] |
| `octree_leaf`   | [1,5,32,32] float32 | [1,32,32] int64 | [1] int64 | [1,32,32,32] int64* | [1,N,32,32,32] | — |

*`x_parent` for refine/leaf is upsampled parent octant (32³) as int IDs.

**LODiffusion guarantees:**

* `OctreeModelRunner` loads the three models, runs init/refine/leaf calls, and flattens/splits leaf outputs into Voxy sections.
* `VoxyBlockMapper` reads `block_mapping` from `*_config.json` and maps model indices → Voxy block IDs at startup
* `VoxySectionWriter` pushes argmax results into Voxy via reflection (VoxyCompat)
* `pipeline_manifest.json` validated at startup; load fails if any required file is missing or hash-mismatched
* Respect static shapes (fail fast on mismatch)

**Data flow (per chunk):**

```
AnchorSampler.capture() → x_height_planes [1,5,32,32], x_biome [1,32,32], x_y_index [1]
   ↓
OctreeModelRunner:
   octree_init           → root blocks[32³] + occ_mask[8]
   spawn child tasks for occupied octants
   octree_refine (recursively for L3→L2→L1→L0) using parent blocks
   octree_leaf           → leaf 32³ block logits
   split leaf into eight 16³ sections
   ↓
VoxySectionWriter → Voxy (LOD1–LOD4 only; LOD0 = vanilla)
```

---

## ⚙️ **Performance Targets & Policies**

> **Note:** `PerformanceMonitor` utility added to the mod; per-stage timings are logged in production runs.

* **Sampling/cache** (first touch): ≤ 20–35 ms (depends on optional channels)
* **Inference** (all models combined, near player): ≤ 100 ms/patch on mid-range CPU
* **Memory**: ≤ \~2 MB/patch (NDArray pooling), LRU of \~128 chunks (configurable)
* **Determinism**: identical inputs → identical outputs (unit test enforced)

---

## ✅ **What We’re Keeping / Dropping**

* ✅ Keep vanilla **carve()**; only run at **LOD0**
* ✅ Cache at source: 16×16 height planes (`x_height_planes`), 16×16 biome IDs (`x_biome`), y-slab index (`x_y_index`)
