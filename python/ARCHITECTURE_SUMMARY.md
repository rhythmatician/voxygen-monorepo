Comprehensive Architecture Research Summary
1. Full Directory Trees
LODiffusion/ (Java Fabric Mod — Runtime)
LODiffusion/
├── build.gradle                          # Fabric Loom 1.13.6, DJL 0.30.0 BOM
├── gradle.properties                     # MC 1.21.11, Fabric 0.141.3
├── settings.gradle
├── gradlew / gradlew.bat
├── README.md
├── data-cli.py                           # Data extraction helper
├── standard_minecraft_blocks.json        # Block catalogue
├── fabric.mod.json
├── CAVE_DENSITY_FUNCTIONS.glsl           # Reference GLSL for cave ports
├── EXAMPLE_CAVE_CARVING_USAGE.glsl
├── mods/
│   └── voxy-0.2.11-alpha.jar            # Voxy compile-time dependency
├── src/main/java/
│   ├── com/rhythmatician/lodiffusion/
│   │   ├── HelloTerrainMod.java          # Mod entry point
│   │   ├── Config.java                   # Runtime config (JSON toggle)
│   │   ├── DefaultLODQuery.java
│   │   ├── LODQuery.java
│   │   ├── ModDetection.java
│   │   ├── cache/
│   │   │   └── FeatureCache.java
│   │   ├── command/
│   │   │   └── LodiffusionCommand.java   # /lodiffusion in-game command
│   │   ├── gpu/
│   │   │   ├── BiomePaletteSSBO.java     # GPU biome palette buffer
│   │   │   ├── BiomePaletteSerializer.java
│   │   │   └── TerrainShaperMlpSsbo.java # MLP weights in SSBO
│   │   ├── network/                      # (empty)
│   │   ├── onnx/
│   │   │   ├── BlockVocabulary.java       # 1104-entry block vocab
│   │   │   ├── ConfigLoader.java          # JSON sidecar loader
│   │   │   ├── InferenceDeviceSelector.java # DirectML / CPU selection
│   │   │   ├── InferenceResult.java       # float[1,V,16,16,16] result record
│   │   │   ├── ModelConfig.java           # Sidecar config model
│   │   │   ├── OctreeModelRunner.java     # 3-model octree (898 lines) [v5]
│   │   │   ├── SparseOctreeModelRunner.java # Single sparse model (911 lines) [v7]
│   │   │   └── SparseOctreeBiomeDataGeneration.java # Stub/TODO doc
│   │   ├── terrain/
│   │   │   ├── TerrainGenerator.java      # Interface (generateChunk)
│   │   │   ├── CarveAdapter.java          # Interface (carve), NOOP impl
│   │   │   ├── adapter/ (empty)
│   │   │   └── infer/ (empty)
│   │   ├── util/
│   │   │   ├── DebugUtils.java
│   │   │   └── PerformanceMonitor.java
│   │   ├── voxy/
│   │   │   ├── AnchorSampler.java         # v5 anchor conditioning (heightmap+biome)
│   │   │   ├── BiomeMapping.java          # Biome name → canonical ID
│   │   │   ├── ChunkScheduler.java
│   │   │   ├── HeightmapFallbackGenerator.java # Ultra-fast no-model fallback
│   │   │   ├── LodGenerationQueue.java
│   │   │   ├── LodGenerationService.java  # Main orchestrator (1862 lines)
│   │   │   ├── OctreeColumnContext.java    # Per-column conditioning bundle
│   │   │   ├── OctreeQueue.java           # Priority queue per LOD level
│   │   │   ├── OctreeRuntimeStats.java
│   │   │   ├── OctreeTask.java
│   │   │   ├── SectionTask.java
│   │   │   ├── VoxelizedSectionSnapshot.java
│   │   │   ├── VoxyBlockMapper.java       # Model→Voxy block ID mapping
│   │   │   ├── VoxyCompat.java            # Reflection-based Voxy API
│   │   │   ├── VoxyDatasetExportService.java
│   │   │   ├── VoxyDetection.java
│   │   │   ├── VoxyEngine.java
│   │   │   ├── VoxyProcessingAPI.java
│   │   │   ├── VoxySectionWriter.java     # Model output → Voxy 64-bit voxels (678 lines)
│   │   │   ├── VoxyWorldBinding.java
│   │   │   ├── WorldNoiseAccess.java      # Server-side heightmap/biome access
│   │   │   └── WorldSectionCoord.java
│   │   └── world/
│   │       ├── ChunkDataExtractor.java
│   │       └── noise/
│   │           ├── BiomeProvider.java      # Interface (classifyBiomes)
│   │           ├── GpuBiomeProvider.java
│   │           ├── GpuHeightmapProvider.java
│   │           ├── GpuNoiseDispatchQueue.java
│   │           ├── GpuNoiseRouterSampler.java # GPU path w/ CPU fallback
│   │           ├── HeightmapData.java
│   │           ├── HeightmapProvider.java  # Interface (sampleHeightmaps)
│   │           ├── NoiseRouterSampler.java # Interface (sampleSection → SectionNoiseData)
│   │           ├── NoiseRouterSamplerFactory.java # Hot-swap factory (238 lines)
│   │           ├── ParityConfig.java
│   │           ├── ParityReporter.java
│   │           ├── RouterField.java        # Enum: 15 canonical noise fields
│   │           ├── SectionNoiseData.java   # float[960] record (15×4×4×4)
│   │           ├── ShadowValidatingSampler.java
│   │           ├── UpstreamNoiseContext.java # Bundle: sampler+heightmap+biome
│   │           ├── VanillaBiomeProvider.java
│   │           ├── VanillaHeightmapProvider.java
│   │           └── VanillaNoiseRouterSampler.java # CPU reference baseline
│   ├── io/github/lodiffusion/worldgen/
│   │   ├── QuartNoiseCompute.java
│   │   ├── ShaderProgramManager.java
│   │   ├── ShaderSectionWriter.java
│   │   ├── ShaderSSBOManager.java         # 393 lines, GPU buffer mgmt
│   │   ├── ShadowRouterExtractor.java     # 533 lines, vanilla→GPU mirror
│   │   ├── TerrainComputeDispatcher.java  # 355 lines, compute dispatch
│   │   └── WorldGenEventHandler.java
│   └── net/lodiffusion/
│       ├── mixin/voxy/
│       │   ├── VoxelizedSectionCaptureMixin.java
│       │   └── VoxyShadowBridgeMixin.java
│       └── shadow/
│           ├── ShadowRouterJobQueue.java
│           └── VoxyRequestDecoder.java
├── src/main/resources/assets/lodiffusion/shaders/worldgen/
│   ├── terrain_compute.comp              # Main compute shader
│   ├── quart_noise_compute.comp
│   ├── improved_noise.glsl               # Vanilla ImprovedNoise port
│   ├── perlin_noise.glsl                 # Multi-octave Perlin
│   ├── normal_noise.glsl                 # Dual-Perlin blend
│   ├── mc_cave_noise_helpers.glsl        # Cave helper stubs
│   └── mc_spaghetti_cave_functions.glsl  # Spaghetti cave stubs
└── src/test/java/                        # JUnit 5 tests
VoxelTree/ (Python ML Training)
VoxelTree/
├── pyproject.toml                        # Python 3.12+, torch>=2.0, onnx + all deps (managed via uv)
├── uv.lock                               # Locked dependencies
├── README.md
├── conversation.md
├── data/                                 # Git-ignored training data
│   ├── chunks/ pairs/ linked/ test_world/
│   ├── voxy/ voxy_subset/ voxy_training/
├── docs/
│   ├── MASTER_PLAN.md                    # 4-phase progressive training strategy
│   ├── MODEL-CONTRACT.md                 # v5 / v7 tensor contracts (513 lines)
│   ├── MINECRAFT_TERRAIN_DAG_COMPLETE.md
│   ├── NOISE-DESIGN.md
│   ├── NOISETAP-INTERFACE.md
│   ├── VOXY-FORMAT.md
│   ├── DEPENDENCIES.md
│   ├── vanilla_worldgen_dag.mmd
│   └── REFERENCE_PATTERNS_ANALYSIS.md
├── tools/
│   ├── data-harvester/                   # In-game data collection
│   ├── fabric-server/
│   ├── voxeltree_cubiomes_cli/
│   └── README.md
├── voxel_tree/                           # Main Python package
│   ├── __init__.py / __main__.py
│   ├── cli.py                            # CLI entry point
│   ├── step_runner.py
│   ├── config/
│   │   └── voxy_vocab.json               # Canonical block vocabulary
│   ├── contracts/
│   │   ├── catalog.py / cli.py / registry.py / spec.py
│   │   └── tests/
│   ├── preprocessing/
│   ├── utils/
│   │   ├── biome_mapping.py              # 54 overworld biomes
│   │   ├── list_biomes.py
│   │   ├── progress.py
│   │   ├── rcon.py
│   │   └── router_field.py              # Python RouterField enum mirror
│   ├── gui/                              # PySide6 training GUI
│   └── tasks/
│       ├── voxy_reader.py                # RocksDB Voxy reader
│       ├── biome/
│       │   ├── train_biome_classifier.py # 6→64→64→54 MLP
│       │   └── export_biome.py
│       ├── density/
│       │   ├── train_density.py          # 6→128→128→2 MLP
│       │   └── export_density.py
│       ├── heightmap/
│       │   ├── train_heightmap.py        # 96→128→64→32 MLP
│       │   └── export_heightmap.py
│       ├── terrain_shaper/
│       │   ├── train_terrain_shaper.py   # 4→32→32→3 MLP (Stage 0)
│       │   ├── extract_terrain_shaper_weights.py
│       │   └── convert_noise_dumps_to_npz.py
│       ├── octree/                       # v5 3-model pipeline
│       │   ├── models.py                 # OctreeInitModel/Refine/Leaf (1028 lines)
│       │   ├── dataset.py
│       │   ├── train.py
│       │   ├── export.py / deploy*.py
│       │   ├── optimize_onnx.py
│       │   └── tests/
│       └── sparse_octree/               # v7 single sparse model
│           ├── sparse_octree.py          # SparseOctreeModel + Fast variant (390 lines)
│           ├── sparse_octree_train.py    # Dataset + loss + training loop
│           ├── sparse_octree_targets.py  # Build octree supervision targets
│           ├── build_sparse_octree_pairs.py
│           ├── export_voxy.py   # → sparse_octree.onnx (527 lines)
│           ├── train.py / distill.py / calibrate.py / diagnose.py
│           └── tests/
2. Key Classes & Purposes
Java Runtime (LODiffusion)
Class	Lines	Purpose
LodGenerationService	1862	Main orchestrator — drives the octree pipeline breadth-first from L4→L0, manages workers, priority queues, surface margin culling
SparseOctreeModelRunner	911	v7 single-model runner — loads sparse_octree.onnx, runs inference with float[960] noise input, greedy top-down pruning decode
OctreeModelRunner	898	v5 3-model runner — init/refine/leaf ONNX chain with 32³ block logits + occupancy mask
VoxySectionWriter	678	Converts model output → 64-bit Voxy voxels, injects into WorldEngine
ShadowRouterExtractor	533	Walks vanilla NoiseRouter via reflection, serializes permutation tables/octave configs → GPU SSBOs
ShaderSSBOManager	393	Uploads noise data to GPU SSBOs (bindings 0–6+)
TerrainComputeDispatcher	355	Dispatches terrain_compute.comp shader
NoiseRouterSamplerFactory	238	Hot-swappable factory: "vanilla" / "gpu" / "shadow" backends
VanillaNoiseRouterSampler	~120	CPU reference: evaluates all 15 DensityFunction handles at quart resolution
GpuNoiseRouterSampler	121	GPU path with CPU fallback (timeout + rate-limited warning)
RouterField (enum)	~80	15 canonical noise fields — the sole data contract between noise generation and downstream
SectionNoiseData (record)	~90	float[960] tensor: [15 fields × 4×4×4 cells] — immutable noise snapshot
UpstreamNoiseContext (record)	~80	Bundles NoiseRouterSampler + HeightmapProvider + BiomeProvider
HeightmapFallbackGenerator	431	Ultra-fast no-model fallback (heightmap + biome → stone/water/air with surface rules)
BlockVocabulary	—	1104-entry vocab mapping model class → Voxy block ID
Python Training (VoxelTree)
File	Purpose
sparse_octree.py	SparseOctreeModel (128D hidden, 5-level) + SparseOctreeFastModel (72D, factorized heads)
octree/models.py	v5 OctreeInitModel (2D enc→3D dec), OctreeRefineModel (3D U-Net), OctreeLeafModel
train_density.py	DensityMLP: 6 climate → 2 density outputs
train_biome_classifier.py	BiomeClassifier MLP: 6 climate → 54 biome logits
train_heightmap.py	Heightmap predictor: 96 → 128 → 64 → 32
train_terrain_shaper.py	Stage 0: 4→32→32→3 spline approximator (done, MSE 0.00067)
export_voxy.py	Checkpoint → ONNX export with sidecar config
3. Current Octree Implementation
Sparse Octree (v7 — active)
Node structure: 5 levels (L4=root, 1 node → L0=leaf, 4096 nodes = 16³)
Output per node: split logit (expand/leaf decision) + label logits (block class)
Traversal: Greedy top-down. sigmoid(split_logit) > threshold (0.43) → expand to 8 children. Otherwise fill sub-region with argmax label.
Node ordering: Breadth-first octant: n = a3*512 + a2*64 + a1*8 + a0 where each a is octant 0–7
Block coords from node index: bx = (a3&1)*8 | (a2&1)*4 | (a1&1)*2 | (a0&1), similar for by/bz
Noise encoding: _NoiseEncoder MLP flattens noise_2d + noise_3d + biome embeddings → context vector
Per-level conditioning: _OctreePosEmb (learnable level + y + z + x embeddings) + _LevelFiLM (scale/shift modulation from global context)
Child projection: child_proj: hidden → hidden*8, reshaped to 8 child features
v5 3-Model Octree (legacy, still has Java runner)
Init Model: L4. 2D encoder → 3D decoder backbone. Inputs: heightmap [N,5,32,32], biome [N,32,32], y_position [N]. Output: block_logits [N,V,32,32,32] + occ_logits [N,8]
Refine Model: Level-shared (L3/L2/L1). Adds parent_blocks [N,32,32,32] + level [N] inputs
Leaf Model: L0. Same as refine minus level, no occ head
Critical bugs in Java integration: Parent embedding not implemented, octant extraction not implemented (see ONNX_COMPATIBILITY_REPORT)
4. Current Model/Inference Code
ONNX Usage
Runtime: DJL (Deep Java Library) 0.30.0 BOM with onnxruntime-engine
Provider selection: InferenceDeviceSelector tries DirectML first, CPU fallback
Thread config: interOpNumThreads=1, intraOpNumThreads=4
Model files: .onnx + _config.json sidecars in LODiffusion/run/config/lodiffusion/
Tensor Shapes (v7 sparse octree — active path)
Input: noise_3d float32[1, 15, 4, 4, 4] = 960 floats
Optional inputs: noise_2d float32[1, n2d, 4, 4], biome_ids int32[1, 4, 4, 4]
Output: 10 tensors: split_L{4..0} + label_L{4..0}
Split shapes: [1, 1], [1, 8], [1, 64], [1, 512], [1, 4096]
Label shapes: [1, N, C] where C = block vocab size
Tensor Shapes (v5 3-model — legacy)
Heightmap [N,5,32,32], biome [N,32,32], y_position [N]
Block logits [N,V,32,32,32], occ logits [N,8]
5. Pipeline / Sampler Orchestration
LodGenerationService (main orchestrator)
Generates terrain around the player using breadth-first octree traversal
Supports both OctreeModelRunner (v5) and SparseOctreeModelRunner (v7)
Stage 0 (L4 init) parallelized across STAGE_0_PARALLELISM workers
Stages L3→L0 single-threaded cascade (parent dependency)
Prioritized by Manhattan distance from player
Surface margin culling: skips sections far above/below estimated surface
Falls back to HeightmapFallbackGenerator when no ONNX models available
NoiseRouterSamplerFactory (noise backend)
Hot-swappable: "vanilla" (CPU), "gpu", "shadow" (GPU with CPU validation)
Creates UpstreamNoiseContext bundle (sampler + heightmap + biome providers)
Re-reads config on every getSampler() call for runtime switching
v7 Staged Pipeline Execution Order
VanillaNoiseRouterSampler → SectionNoiseData (960 floats)
density MLP — predict preliminary_surface + final_density
biome_classifier MLP — predict biome class per quart cell
heightmap_predictor MLP — predict surface + ocean floor heights
sparse_octree — predict 5-level block hierarchy
6. Density / Noise / Biome Code
Noise
RouterField enum: 15 fields — 6 climate (temp, veg, continents, erosion, depth, ridges), 2 density (preliminary_surface, final_density), 4 aquifer (barrier, floodedness, spread, lava), 3 ore (vein_toggle, vein_ridged, vein_gap)
SectionNoiseData: Immutable float[960] at quart resolution (4×4×4 per field)
VanillaNoiseRouterSampler: Evaluates all 15 DensityFunctions via vanilla API at cell centres
GPU path: ShadowRouterExtractor → SSBOs → terrain_compute.comp (GLSL), with Steps 1–6 done (surface density), caves/slide/squeeze are TODO (NN targets)
Density
GLSL: Steps 1-6 complete (continents, erosion, ridges → terrain shaper MLP → depth/jaggedness → sloped cheese → final density). Cave pipeline (Step 8) and slide/blend/squeeze (Step 9) planned as NN targets.
Python: DensityMLP (6→128→128→2) predicts preliminary_surface_level + final_density from 6 climate channels
TerrainShaperMLP: 4→32→32→3, trained on 2M spline evaluations (MSE 0.00067), weights in SSBO binding 9
Biome
BiomeProvider interface: classifyBiomes(sx, sy, sz, noiseData) → int[4][4][4]
VanillaBiomeProvider: Uses vanilla MultiNoiseBiomeSource 6-parameter lookup
BiomeClassifier MLP: 6→64→64→54 (54 overworld biomes)
BiomeMapping: Name→canonical ID, used by AnchorSampler
Biome handling is explicitly dual-path in PLAN.md: vanilla-authoritative now, learned-capable later
Heightmap
HeightmapProvider interface: sampleHeightmaps(sx, sz) → HeightmapData
VanillaHeightmapProvider: Derives via vanilla ChunkNoiseSampler (CPU)
Heightmap predictor MLP: 96→128→64→32 (16 surface + 16 ocean floor at 4×4 quart)
7. Existing Two-Model / Split-Model Patterns
Already present in code:
v5 3-model pipeline: Init/Refine/Leaf — three separate ONNX models for different LOD levels
v7 4-model staged pipeline: density → biome_classifier → heightmap_predictor → sparse_octree — lightweight MLPs + main block predictor
Path A / Path B split (PLAN.md): GPU compute shader (density field) vs. ONNX block prediction (OGN models) — currently disconnected
Dual backend sampler: VanillaNoiseRouterSampler (CPU) / GpuNoiseRouterSampler (GPU) — hot-swappable
Planned in PLAN.md (not yet implemented):
MaterialResolver + SurfaceDecorator split — solid/air/water choice vs. grass/sand/snow surface layers
Aquifer stage — dedicated coarse-grid fluid placement
Carving stage — candidate-mask approach, post-density
Uncertainty-gated fallback — per-decision fallback to deterministic logic when model confidence is low
Progressive grokking pipeline — Stage 0 (terrain shaper) → Stage 1 (density+caves) → Stage 2 (block classifier) → Stage 3 (multi-LOD end-to-end)
8. Build System Details
Java (LODiffusion)
Build: Fabric Loom 1.13.6, Gradle
MC version: 1.21.11, Yarn 1.21.11+build.4, Fabric Loader 0.18.4
ONNX Runtime: DJL BOM 0.30.0 (ai.djl.onnxruntime:onnxruntime-engine)
Key deps: Hephaistos 2.1.2 (NBT), Gson 2.11.0, JUnit 5.10.2, Mockito 5.12.0
Voxy: compileOnly files('mods/voxy-0.2.11-alpha.jar') — reflection-based integration
Java: JDK 17+
Python (VoxelTree)
Python: 3.11+
ML: PyTorch ≥ 2.0, torchvision, onnx, onnxruntime
Data: numpy, scipy, h5py, anvil-parser2
GUI: PySide6 ≥ 6.6
Testing: pytest, pytest-cov
Build: setuptools via pyproject.toml
9. Conflicts / Gaps vs. Planned Refactor
Critical Gaps:
Gap	Description	Impact
v5 parent embedding	OctreeModelRunner passes raw int64 block IDs but ONNX expects pre-embedded float32. parent_embedding.npz lookup is NOT implemented in Java	v5 refine/leaf models produce garbage output
Octant extraction	Extracting 16³ from parent 32³ + 2× upsample is NOT implemented in Java	v5 refinement cascade is broken
Path A↔B disconnected	GPU density shader and ONNX block prediction run independently	No density→block conditioning path exists
ShiftedNoise TODO	XZ coordinate distortion (Step 7) not yet in GLSL	Surface density may have axis-aligned artifacts
Cave pipeline TODO	Steps 8–9 (caves, slide, squeeze) not in shader or NN	No cave generation yet
Aquifer system	No implementation at all	Water/lava placement missing from generated terrain
Surface rules	Not implemented — HeightmapFallbackGenerator has basic biome-based rules but they're heuristic	No accurate grass/sand/snow block placement
Feature placement	No trees, structures, decorations	Out of scope for first milestone
Source-of-Truth Drift:
Area	Conflict
Contract versions	MODEL-CONTRACT.md lists v5/v7 but Java code has both old and new runners. v3 progressive pipeline code may still exist but is superseded
Block vocab size	v5 default = 1104, v7 models = 1040, biome classifier = 54 classes — sizes vary
Sparse model spatial_y	Legacy v6 = 2, v7 = 4. Config sidecar handles this, but training data must match
Noise channel count	Legacy = 13 channels (4×2×4), v7 = 15 channels (4×4×4). Both are supported via config
Biome data generation	SparseOctreeBiomeDataGeneration.java is a stub/TODO — biome_ids for training NPZs not yet fully wired
Alignment with Planned Two-Model Refactor:
The PLAN.md describes a staged hybrid pipeline (deterministic upstream + learned downstream). The current codebase has the skeleton:

NoiseRouterSampler → SectionNoiseData is the "worldgen IR" at quart resolution ✅
v7 staged models (density, biome, heightmap, sparse_octree) align with the staged graph concept ✅
HeightmapFallbackGenerator is a primitive "uncertainty-gated fallback" ✅
GpuNoiseRouterSampler with CPU fallback is the dual-path pattern ✅
But the planned refactor adds layers that don't exist yet:

MaterialResolver + SurfaceDecorator split — must be carved out of the monolithic block classifier
Aquifer stage — new model/module needed
Carving stage — new model/module needed
Per-stage validation harnesses — only final-output tests exist today
Residual/correction architecture — current models predict absolute outputs, not residuals
Uncertainty gating — no confidence/entropy measurement in current inference code


