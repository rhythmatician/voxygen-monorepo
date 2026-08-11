# LODiffusion — Copilot Instructions

## Architecture Overview
LODiffusion is a Fabric mod (Java 21, MC 1.21.11) that runs ONNX inference at game time to generate distant terrain. **Model training happens in VoxelTree (Python) — never here.** LODiffusion loads pre-trained ONNX models + JSON sidecar configs from `run/config/lodiffusion/`.

### Active inference path
`SparseOctreeModelRunner` (v7 contract, `sparse_octree.onnx`) is the **only** model runner. The legacy v5 `OctreeModelRunner` and `ProgressiveModelRunner` have been removed.

### Key packages (`com.rhythmatician.lodiffusion`)
| Package | Purpose | Key classes |
|---------|---------|-------------|
| `onnx/` | Model loading + inference | `SparseOctreeModelRunner`, `ConfigLoader`, `BlockVocabulary`, `ModelConfig` |
| `voxy/` | Voxy integration | `LodGenerationService`, `VoxyEngine`, `VoxySectionWriter`, `VoxyBlockMapper`, `AnchorSampler`, `ChunkScheduler` |
| `world/noise/` | Vanilla noise sampling (20 files) | `NoiseRouterSampler`, `RouterField`, `BiomeProvider`, `HeightmapProvider`, `ShadowValidatingSampler` |
| `terrain/` | Generation orchestration | `TerrainGenerator`, `CarveAdapter` |
| `gpu/` | GPU compute paths | `BiomePaletteSSBO`, `TerrainShaperMlpSsbo` |
| `cache/` | Feature caching | `FeatureCache` |

### Data flow
```
VoxyEngine → LodGenerationService → AnchorSampler (vanilla noise) → SparseOctreeModelRunner (ONNX)
                                                                           ↓
                                        VoxySectionWriter ← argmax logits + BlockVocabulary mapping
```

Split source sets: `src/main/` for server-safe code, `src/client/` for `LodiffusionClient`, `VoxyClientMixin`, `VoxyDebugState`.

---

## Development Workflow

### Micro-commit cycle
1. Write one small test → commit `test:`
2. Implement just enough → commit `feat:`
3. Fix/refactor → commit `fix:` or `refactor:`
4. Push frequently. PRs < 200 LOC, reviewable in < 10 min.

### Branch prefixes
`test/`, `feat/`, `fix/`, `docs/`

### Commit prefixes
`test:`, `feat:`, `fix:`, `docs:`, `refactor:`, `train:`

### Build & test
```bash
./gradlew clean lint test jacocoTestReport build   # full CI equivalent (lint must pass first)
./gradlew runClient                                 # launch Minecraft client
```
`build` includes `deployToRunMods` — no manual JAR copy needed.

---

## Testing

- **JUnit 5 + Mockito 5.12** (requires `-Dnet.bytebuddy.experimental=true` for Java 21)
- **JaCoCo 70% coverage threshold** enforced
- **Checkstyle** enforced
- Test locations:
  - `src/test/java/com/.../` — unit + contract tests per package
  - `src/test/java/data/` — synthetic dataset tests (e.g., `BiomeSamplingTest`)
  - `src/test/java/integration/` — Voxy API, NBT, region file integration tests
  - `src/test/java/fixtures/TestWorldFixtures.java` — shared test data factory
- Tags: `@Tag("ci")`, `@Tag("inference")` (DJL/ONNX), `@Tag("benchmark")` (excluded from default CI)

---

## ONNX Integration (NOT Training)

### What LODiffusion does
- Loads `sparse_octree.onnx` + `sparse_octree_config.json` sidecar via `ConfigLoader`
- `SparseOctreeModelRunner` runs single-model inference (v7 contract)
- Input: `noise_3d [1,15,4,2,4]`, `biome_ids`, `heightmap5` from `AnchorSampler`
- Output: 5-level octree (occ + block logits) → argmax → `VoxySectionWriter` → Voxy

### Model deployment (VoxelTree → LODiffusion)
```bash
# In VoxelTree:
voxel-tree --step export_sparse_octree --run --profile phase6
voxel-tree --step deploy_sparse_octree --run --profile phase6
# Copies ONNX + sidecar to ../LODiffusion/run/config/lodiffusion/
```
No code changes in LODiffusion needed — just redeploy models.

### Contract validation
`ConfigLoader` reads the sidecar JSON and validates the `contract` field. Missing contract → startup error.

### What NOT to do here
- Train models (VoxelTree owns training)
- Modify ONNX files directly (export from VoxelTree)
- Create alternative inference paths (`SparseOctreeModelRunner` is the only active path)
- Embed model weights as code (always load from external ONNX files)

---

## Critical Integration Points

### RouterField channel ordering
The **shared contract** lives at `../../router_field_contract.yaml` (workspace root, outside both repos). It defines the canonical 15-channel ordering, normalization, and spatial layout. Java's `RouterField.java` and Python's `NOISE_FIELDS` both validate against it. Any mismatch is a hard error.

### Block vocabulary
`BlockVocabulary.java` loads the 1104-entry vocabulary from the sidecar JSON. Must match VoxelTree's `voxy_vocab.json` (`minecraft:air = 0`).

### Voxy dependency
`compileOnly` on `voxy-0.2.11-alpha.jar` (local file in `mods/`). Requires manual update — no Maven resolution. Runtime detection via `VoxyDetection`/`ModDetection`.

---

## Java Conventions
- Java 21, Fabric Loom 1.13.6, Fabric API 1.21.11, DJL BOM 0.30.0
- Bitwise ops for chunk math (`chunkX >> 5`)
- Wrap file/NBT IO in try-with-resources
- Catch only specific exceptions; log useful info for data failures

---

## Key References
- `.github/copilot-instructions/anvil.md` — Anvil + NBT parsing
- `.github/copilot-instructions/chunk-extraction.md` — Chunk IO logic
- `.github/copilot-instructions/distant-horizons-integration.md` — DH APIs + fallback
- `docs/MODEL-CONTRACT.md` — Authoritative tensor contract
- `docs/PROJECT-OUTLINE.md` — Project plan
- `docs/INTEGRATION-REQUIREMENTS.md` — VoxelTree ↔ LODiffusion boundary spec
