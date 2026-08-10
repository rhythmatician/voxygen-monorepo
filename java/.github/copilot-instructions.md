# Copilot Assistant Instructions for LODiffusion

## 🔁 Development Workflow

Copilot must adhere to the **test-first, micro-commit strategy**:

### Before Each Feature
```bash
git fetch && git checkout main && git pull
````

* Ensure `git status` shows a clean working tree
* Create a focused branch with a clear prefix:

  * `test/add-xyz-test`
  * `feat/implement-abc`
  * `fix/resolve-def`
  * `docs/update-ghi`

```bash
git checkout -b test/add-xyz-test
```

### Development Cycle (Every 15–20 minutes)

1. **Write one small test** → commit (`test:`)
2. **Implement only enough to pass** → commit (`feat:`)
3. **Fix issues, refactor, or cleanup** → commit (`fix:` or `refactor:`)
4. **Push frequently** to enable CI and backups

```bash
git add .
git commit -m "test: add vanilla heightmap sampling"
git push origin your-branch-name
```

### Finalize Branch

1. Run:

   ```bash
   ./gradlew clean lint test jacocoTestReport build
   ```

   *Note: `lint` must pass before `test` or `build`.*
2. PR must:

   * Be under 200 LOC
   * Be reviewable in under 10 minutes
   * Pass all CI stages
   * Have no unresolved Copilot review threads

---

## 🔬 Testing & CI Discipline

### Test Rules

* Tests may live in:

  * `src/test/java/com/...` — core unit and integration tests
  * `src/test/java/data/` — synthetic dataset tests (e.g., `BiomeSamplingTest`)
  * `src/test/java/benchmark/` — performance and inference benchmarks
* Use **JUnit 5** and **Mockito**
* Target **70%+ code coverage per commit**
* Use tags for clarity:

  * `@Tag("ci")` — regular CI tests
  * `@Tag("inference")` — DJL/ONNX integration
  * `@Tag("benchmark")` — long-running benchmarks (excluded from default CI)

### CI Jobs

Each commit/PR runs:

1. **Lint**: `./gradlew lint` — must pass first
2. **Test + Coverage**: `./gradlew test jacocoTestReport`
   *Runs all `src/test/java/**` unless `@Tag("benchmark")` is excluded by config*
3. **Build Mod**: `./gradlew build` (only if lint + test pass)

Local equivalent:

```bash
./gradlew clean lint test jacocoTestReport build
```

---

## 🧠 Mod Responsibilities

### Chunk Generation (Octree / OGN)
- `LodGenerationService` is the runtime entrypoint for terrain generation:
  - Populates `OctreeQueue` with L4 root tasks
  - Coordinates per-level worker threads
  - Delegates inference to `OctreeModelRunner` (init / refine / leaf)
- `OctreeQueue` manages breadth-first task scheduling with deduplication and priority
- `OctreeModelRunner` loads `octree_init.onnx`, `octree_refine.onnx`, `octree_leaf.onnx`
  and runs inference; single-sample calls use thread-local `OctreeInferenceBuffers`
- **LOD chaining is required**: each refinement builds on the prior LOD's argmax output

### Distant Horizons Integration
- Runtime detection only (via `ModDetection.isDistantHorizonsLoaded()`)
- API dependency is `compileOnly`
- Use `LODManager.getChunkLOD(...)` for LOD level detection
- Implement fallback wrappers in `LODManagerCompat`, `DistantHorizonsCompat`

---

## 🧠 ONNX Model Integration (NOT Training)

**CRITICAL:** Model training happens in **VoxelTree**, not LODiffusion. LODiffusion loads pre-trained ONNX models.

### 🏗️ What LODiffusion Does
- Loads four progressive ONNX models exported by VoxelTree
- Chains them at runtime via `ProgressiveModelRunner`
- Feeds sampled vanilla features via `AnchorSampler`
- Writes results to Voxy via `VoxySectionWriter`
- Integrates with DH LOD levels via `DistantHorizonsCompat`

### 📦 Key Classes
- **`ProgressiveModelRunner`** (`onnx/`): Loads 4 models; chains inference stages; handles tensor I/O
- **`LodGenerationService`** (`voxy/`): Background service generating terrain in spiral order around player
- **`AnchorSampler`** (`config/`): Extracts `x_height_planes [1,5,16,16]`, `x_biome [1,16,16]`, `x_y_index [1]` from vanilla worldgen
- **`VoxySectionWriter`** (`voxy/`): Argmax logits → voxel chunks → push to Voxy via reflection
- **`VoxyBlockMapper`** (`onnx/`): Maps model block indices to Voxy block IDs using `*_config.json` mappings
- **`DistantHorizonsCompat`** (`dh/`): Runtime DH LOD queries (compileOnly dependency)

### ✅ Model Deployment Workflow

1. **VoxelTree trains & exports** (VoxelTree project):
   ```bash
   python scripts/export_lod.py --output production/vN
   python verify_lodiffusion_v1.py production/vN  # validate shapes + hashes
   python scripts/deploy_models.py production/vN  # copy to LODiffusion config
   ```

2. **Validate in LODiffusion**:
   - Startup: `ProgressiveModelRunner` loads all 4 ONNX files + validates against `pipeline_manifest.json`
   - Smoke test: compares test vectors from each `*_test_vectors.npz` against DJL inference output
   - Fail fast: any shape mismatch or missing file → startup error (logged, not silent)

3. **No Code Changes in LODiffusion** — just redeploy models via script above

### 📐 Tensor Contract (VoxelTree ↔ LODiffusion)

Full spec in `docs/MODEL-CONTRACT.md`. Summary:

| Model | Init inputs | Parent (refine only) | Output D | Java post-proc |
|---|---|---|---|---|
| `init_to_lod4` | height_planes, biome, y_index | — | 1 | — |
| `refine_lod4_to_lod3` | same | `[1,1,1,1,1]` | 2 | — |
| `refine_lod3_to_lod2` | same | `[1,1,2,2,2]` | 4 | — |
| `refine_lod2_to_lod1` | same | `[1,1,4,4,4]` | 8 | upsample 2× to 16³ |

### 🧪 Integration Tests
- `ProgressiveModelRunnerTest`: mock inference, verify output shapes
- `AnchorSamplerTest`: mock vanilla world data, verify tensor construction
- `VoxySectionWriterTest`: mock Voxy API, verify argmax → block mapping

### 🚫 What NOT to Do Here
- Train models (VoxelTree owns training)
- Modify ONNX files directly (export from VoxelTree)
- Create alternative inference paths (`ProgressiveModelRunner` is the only path)
- Embed model weights as code (always load from external ONNX files)

---

## 🧪 Implementation Patterns

### Java Conventions
- Java 17 required
- Fabric API 1.21+
- Use bitwise ops for chunk math (`chunkX >> 5`)
- Wrap file/NBT IO in try-with-resources
- Isolate logic: `DiffusionModel`, `ChunkSampler`, `LODQuery`, etc.
- Ensure `build.gradle` includes:
  - `java`, `jacoco` plugins
  - `test { useJUnitPlatform() }`
  - All dependency versions pinned

### Error Handling
- Catch only specific exceptions
- Log useful info for NBT/data failures

---

## 🧵 Git Branching & PR Discipline

### Micro-Commit Strategy
- Commit every 15–20 minutes
- One logical change per commit:
  - Add test → `test:`
  - Implement → `feat:`
  - Fix → `fix:`
  - Doc → `docs:`

### PR Requirements
- PRs must:
  - Contain only one logical change
  - Touch <200 LOC
  - Be reviewable in <10 minutes
  - Be auto-mergeable if:
    - ✅ Only `docs/`, `*.md`, `.github/` files changed
    - ✅ All CI checks pass
    - ✅ No Copilot threads open

### Commit Prefixes
- `test:` - New or updated tests (Java or Python)
- `feat:` - New feature implementation (Java or Python)
- `fix:` - Bug fix
- `docs:` - Markdown or outline update
- `train:` - Model training pipeline changes

---

## ☁️ Safe Shell Access

### Auto-approved Shell Commands
```bash
ls, git, grep, sed, awk,
curl -X GET, curl --request GET
```

### Prompt First (Copilot Must Ask)
- All POST/PUT/DELETE
- Any command modifying files or system state

---

## 🗂️ File Index
- `.github/copilot-instructions/anvil.md` — Anvil + NBT parsing
- `.github/copilot-instructions/chunk-extraction.md` — Chunk IO logic
- `.github/copilot-instructions/development.md` — Misc best practices
- `.github/copilot-instructions/distant-horizons-integration.md` — DH APIs + fallback
- `docs/CI-CHECKLIST.md` — Copilot’s own PR checklist
- `docs/PROJECT-OUTLINE.md` — Full project plan (4 model progressive pipeline)
- `docs/instructions.md` — Developer instructions
- `docs/INTEGRATION-REQUIREMENTS.md` — VoxelTree ↔ LODiffusion boundary spec
- `docs/MODEL-CONTRACT.md` — Authoritative tensor contract (shapes, I/O, deployment)