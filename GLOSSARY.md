# Voxygen glossary

Voxygen generates distant Minecraft terrain via learned octree diffusion and writes semantic voxel volumes into a pluggable LOD store (today Voxy).

## Language

### Coordinates & Levels

**SectionPos**: Section-grid coordinate where one unit = 16 Minecraft blocks (sectionX = blockX >> 4); canonical position for all generation and writing. _Avoid_: chunk section, chunk pos, WorldSection coord, wsX, voxel section.

**Level**: LOD refinement level L0..L4 where L0 is finest; validated volume dimensions, never inferred. Level is not storage -- Voxy WorldSection (32^3) remains a private consolidation detail, never a Level. _Avoid_: FULL32, storage level, WorldSection level, Voxy level.

### Semantic Volume

**Canonical Block Registry**: Versioned mapping of block identities to stable canonical IDs (0 = air) shared between Python training and Java runtime; must be proved identical via explicit version/hash in contract metadata, not per-volume. _Avoid_: vocab index, Voxy block ID, packed ID.

**Canonical Biome Registry**: Alphabetically-ordered 54-entry mapping of overworld biomes to canonical IDs 0..53 (255 = unknown) shared between Python and Java; must be proved identical via explicit version/hash in contract metadata. _Avoid_: Voxy biome ID, biome registry entry.

**VoxelVolume**: Semantic dense XYZ cube of canonical (blockId, biomeId) accessible by x/y/z behind an opaque API; valid extents 16 and 32; backing representation (primitive arrays or otherwise) stays private and not frozen. _Avoid_: long[] yzx, packed voxel, Voxy voxel, 32768.

**VoxelPredictionDecoder**: Inference-boundary module that decodes model outputs (logits/argmax) into semantic VoxelVolume; the only place that understands model output layout. _Avoid_: writer argmax, logits in writer.

### Writing

**VoxelVolumeWriter**: Deep module seam between generation and storage with two explicit operations: writeSection(SectionPos, VoxelVolume[16]) for L0 and writeRegion(SectionPos origin, Level, VoxelVolume[32]) for 32^3 regions. YZX transpose, VarHandle/CAS, and WorldSection lifecycle remain private behind the adapter. _Avoid_: VoxySectionWriter, VoxyCompat, VoxyEngine, VoxyWorldBinding, FULL32.

**WriteOutcome**: Result of a writer operation: WRITTEN, SKIPPED_AIR, SKIPPED_EXISTS; invalid non-null values throw IllegalArgumentException and null references throw NullPointerException; missing backend throws unchecked VolumeUnavailableException (extends IllegalStateException). _Avoid_: SKIPPED_BOUNDS, SKIPPED_INVALID.

### Generation

**Dimension**: Registry-key dimension identity minecraft:the_end / minecraft:the_nether / minecraft:overworld that selects frozen profile, generation domain, and synthesizer. Not a Level, not a SectionPos, not a World. _Avoid_: world, dimension type, Level as dimension.

**Dimension Generation Domain**: Per-dimension half-open Y interval [minY, maxY) plus NoiseSettings (minY, height, noiseSizeHorizontal, noiseSizeVertical) and profile flags (aquifersEnabled, beardifier, blending) that define which rows are vanilla-real and which side effects are dead code for that dimension. Derived via WorldSectionCoord.worldSectionToBlockMin/Max(wsY, Level). End [0,128) noiseSize(2,1) cell 8×4 create(0,128,2,1) aquifersEnabled=false; Overworld [-64,320) noiseSize(1,2) cell 4×8 create(-64,384,1,2) true; Nether [0,128) noiseSize(1,2) cell 4×8 create(0,128,1,2). _Avoid_: hard-coded END_MIN_Y/MAX_Y as global, global height, one domain.

**BiomeEligibility**: Chorus-local predicate `isChorusBiome(blockX, blockZ)` gated by `end_highlands` for End out-island columns. Not a generic feature gate; placed features remain independent responsibilities per Worldgen Partition v1.

**Dimension Synthesizer**: Deep module seam synthesize(Level, SectionPos) ? VoxelVolume[32] per Dimension that produces A?C without B (seed + surface + eligibility ? semantic volume without materializing a chunk), owns Mipper rule and L4/L3 honest omission per Fidelity Profile, and shares Mipper + CanonicalVoxyMaps + CanonicalRegistries. Not EndChorusSynthesizer as global, not BiomeSynthesizer as top seam, not Worldsection Synthesizer as duplicate of writeRegion. _Avoid_: EndChorusSynthesizer as global, BiomeSynthesizer as top seam, Worldsection Synthesizer as duplicate of VoxelVolumeWriter.writeRegion, GenerationSession if(End) branch.

### Correctness

**Correct Distant Terrain**: Every Voxygen render representation L4..L0 must approximate the Authoritative Terrain for the same seed and frozen worldgen profile at the fidelity it claims; a mountain seen at distance must still be that mountain when reached. _Avoid_: plausible terrain, Minecraft-like, generic heightmap.

**Authoritative Terrain**: Exact terrain semantics defined by the frozen vanilla seed and worldgen profile, independent of which system performs the computation. Vanilla owns the truth, not necessarily all computation. _Avoid_: vanilla-computed as the definition of correct, approximate authoritative terrain.

**Render L0**: Block-resolution semantic terrain used for Voxy rendering; it may exist before or alongside the playable chunk but is not by itself an Authoritative Chunk. _Avoid_: vanilla chunk, playable chunk, proof of worldgen parity.

**Authoritative Chunk**: Playable Minecraft chunk whose terrain semantics and required chunk lifecycle state satisfy Authoritative Terrain; its computation may be vanilla, shared exact work, or an exact Voxygen path. _Avoid_: Render L0, approximate learned chunk, vanilla-only execution.

**Vanilla Convergence**: Degree to which each Level approaches Authoritative Terrain so refinement reveals rather than contradicts it; it assigns neither Render L0 nor Authoritative Chunk execution ownership. _Avoid_: plausible-only convergence, L0 ownership implied by correctness.

**Lexicographic Correctness Hierarchy**: Visible geometry, silhouette, and topology dominate; Ground Surface follows; exposed material family follows that; exact canonical block identity matters only where a Fidelity Profile claims it. Octree and coverage validity are invariants, while Pop is secondary acceptance. _Avoid_: scalar-weighted single loss, uniform voxel loss, all errors equal, octree validity as loss weight.

**Training vs Acceptance Observables**: Voxel/domain observables support training and diagnosis; acceptance also includes Level-appropriate screen-space geometry as silhouette, projected occupancy, and depth rather than RGB, with canonical views corresponding to runtime Level-selection geometry. _Avoid_: RGB terrain correctness, domain-only acceptance, invented viewing distances.

**Topology Bundle**: Primary topology observables combine solid/empty occupancy with distinct water and lava occupancy/boundary observables so replacing fluid with air or land cannot receive full topology credit. _Avoid_: solid-only topology, fluid as empty.

**Ground Surface**: Lossy 2D exact-vanilla reference formed after removing non-ground vegetation and canopy, including trunks; for a dry column it is the uppermost terrain-supporting solid exposed toward air, and for a submerged column the uppermost terrain-supporting solid beneath the fluid column. Fluid surface is separate; caves, arches, overhang interiors, and canopy remain topology or silhouette concerns. _Avoid_: WORLD_SURFACE_WG as final ground truth, canopy height, 2D cave encoding.

**Ground Role Classification**: Versioned acceptance-owned classification of canonical blocks by ground semantics, orthogonal to visible material family; roles may overlap rather than form a forced mutually exclusive enumeration. Vanilla tags are evidence, not the classification authority. _Avoid_: visual family as ground role, solid means ground.

**Hierarchical Material Taxonomy**: Versioned total mapping from the Canonical Block Registry into progressively coarser perceptual families. Reachable blocks do not disappear by frequency; dimension-defining substrates remain distinguishable at L4/L3 and may refine further at finer Levels; snow and ice remain distinct; `other` means explicitly reviewed without a dedicated family, while unmapped IDs are invalid and excessive observed `other` invalidates the experiment. _Avoid_: hand-completed flat family list, rarity culling, all substrates as rock, unmapped as other.

**Fidelity Profile**: Predeclared versioned contract, identified by ID/hash, stating which topology, surface, material-family depth, and exact-identity fidelities are acceptance-bearing at each Level; richer unclaimed measurements are diagnostic. _Avoid_: artifact-selected fidelity, hard-coded exact L1, diagnostic implies acceptance.

**Correctness Metric Suite**: Versioned definitions for the Topology Bundle, silhouette/projected occupancy, Ground Surface error, exposed material-family accuracy, exact canonical-block accuracy where claimed, octree/coverage validity, Pop, and Vanilla Convergence. Metric existence is distinct from role: primary acceptance, Level-dependent acceptance, hard invariant, secondary acceptance, or diagnostic. Numerical budgets are separate. _Avoid_: one aggregate score, metric existence as automatic gate, premature thresholds.

**Measurement Protocol**: Immutable-by-version identity of the semantics used to produce correctness evidence; results from different protocol versions are distinct evidence populations. _Avoid_: mutable protocol, post-outcome definition changes, pooled protocol versions.

**Stratification**: Primary acceptance strata are dimension × LOD Level (L4..L0, not Y level); biome and morphology are coverage tags rather than Cartesian acceptance axes; seed is the independent sampling unit and regions are nested observations. _Avoid_: Y/altitude strata, full biome Cartesian, regions as independent seeds, single global metric.

**Measurement Tracer**: Minimal non-statistical experiment establishing replay and face validity before a Measurement Protocol is frozen; it makes no population, threshold, confidence, or generalization claim. _Avoid_: one-seed pilot, tracer thresholds, repeatable wrong number as validity.

**Flyover**: Real-client visual acceptance run that observes distant-terrain coverage and refinement from a configurable sequence of player positions. It is the final check for what the player can actually see, not a substitute for deterministic headless tests. _Avoid_: fixed-coordinate test, unit test, benchmark, manual flight as the definition.

**Error Character and Honest Omission**: Evaluate a candidate against its declared stage target and separately against final vanilla so omission cost remains visible. Attribute error causally to an omitted responsibility only with a paired stage or counterfactual oracle; otherwise report spatial overlap with the omitted responsibility without causal allocation. _Avoid_: mask-only omission, final-only score, causal claim from an omission tag, double attribution.

**Pop and Vanilla Convergence**: Pop is consecutive-Level visible transition error (L_n versus L_{n-1}); Vanilla Convergence is each Level's disagreement with eventual exact vanilla, retaining stage and omission context. Pop is secondary to correctness; both are acceptance-bearing under their Fidelity Profile. _Avoid_: single pop number, RGB transition error, hidden omission cost.

**Scaffold Preference / Residual Default**: Preference for a useful deterministic or exact-vanilla scaffold plus a learned residual over full learned prediction, unless evidence favors full prediction or no useful scaffold exists. _Avoid_: residual without a useful scaffold, predeclared full versus residual by Level, full learned by default.

**Worldgen Partition v1**: Decision matrix for Voxygen's Render L4..L0 responsibilities under separate frozen Overworld, Nether, and End profile identities with `generate_structures=false`; it does not assign playable-chunk generation. Its dependency header requires exact frozen-profile, dimension, seed, and authoritative seeded-worldgen semantics without requiring permanent reuse of Minecraft's `RandomState` Java object. Each active responsibility is classified as reuse vanilla, exact port, deterministic approximation, learned approximation (`residual` or `full`), or omit/defer. _Avoid_: L0 means Authoritative Chunk, one cross-dimension profile, playable terrain claim, structures enabled implicitly, RandomState object identity as contract.

**Profile-Inactive Responsibility**: Minecraft responsibility disabled by the frozen worldgen profile itself; recorded as N/A rather than omitted because it produces no Authoritative Terrain under that profile. Registered structures are profile-inactive when `generate_structures=false`; placed features remain separate responsibilities regardless of generation-step naming. _Avoid_: omit-current-profile, deferred responsibility, structure-named feature.

**Partition Responsibility**: Replaceable semantic responsibility or value boundary with independently specifiable inputs and halo, a clear semantic output, independently measurable error and avoidable cost, and useful reuse, caching, or approximation semantics. It may split a worldgen stage or combine internal values; raw router fields are not automatically responsibilities. _Avoid_: one row per ChunkStatus, one row per NoiseRouter field, one row per configured feature.

**Claim Role / Dependency Role**: Orthogonal roles of a Partition Responsibility at a Level. Claim role means its output is acceptance-bearing under the predeclared Fidelity Profile; dependency role means active downstream computation consumes it without making that output an acceptance gate. A responsibility with neither role may be eliminated or deferred; coupled dependencies are resolved jointly. _Avoid_: consumed means claimed, indirect effect makes acceptance-bearing, upstream applicability decided alone.

**Partition Decision State**: A partition cell is either resolved to one of the five dispositions or explicitly unresolved with a bounded candidate set, exact missing evidence, cheapest resolving experiment or source question, and predeclared winner rule. For every downstream decision or artifact, unresolved metadata records which relevant boundaries remain stable and which are blocked; the same candidates may preserve semantic output shape while changing cache identity, runtime ownership, ONNX existence, or another seam. Unresolved is not a sixth disposition and need not invent a numeric threshold before its Measurement Protocol exists. _Avoid_: forced primary candidate, fabricated learned mode, intrinsic implementation-open label, undecided as disposition.

**Shared Partition Decision**: Named disposition and evidence family referenced by explicit dimension x Level cells when recorded profile differences do not change that disposition or the relevant contract boundary. Applicability and relevant differences are part of the reference; literal equivalence of Minecraft's internal computation is unnecessary, while any difference affecting correctness, cost, availability, halo/cache behavior, or the winner rule requires a cell-specific override. _Avoid_: implicit global row, duplicated unexplained cells, shared means identical router.

### Wayfinder

**Wayfinder Map**: Single issue labelled `wayfinder:map` that indexes a destination, decisions-so-far, and fog. _Avoid_: roadmap, backlog.

**Wayfinder Ticket**: Child issue of the Wayfinder Map labelled `wayfinder:<type>` where `<type>` is one of `research`, `prototype`, `grilling`, `task`. Purpose describes frontier; `agent:implement` authorizes AFK Task via Sandcastle, while `wayfinder:research` alone authorizes research dispatch. _Avoid_: purpose as executor, agent:* authorizes every execution.

**Research Ticket**: Wayfinder ticket of type `research` — AFK evidence-backed research to surface a fact a decision waits on. Eligible for Sandcastle research profile when open, exactly one Wayfinder type `wayfinder:research`, unassigned, no `agent:in-progress` or `agent:blocked`, known `blocked_by === 0`, and body satisfies research input contract. No `agent:research` or `ready-for-agent` required; historical `ready-for-agent` residue is removable but not authoritative. Executed via Sandcastle parallel research profile (isolated worktree/sandbox, strict structured result, host publication, required parent-map pointer when `Part of #N` present, close; no implementation review, merger, PR, or auto-merge). Research retains distinct lifecycle: one result, one publication, one parent pointer, one close. _Avoid_: HITL research, research without wayfinder:research, agent:research, research should not commit.

**Prototype Ticket / Grilling Ticket**: Wayfinder tickets of type `prototype` and `grilling` — HITL only. Prototype raises fidelity with a cheap artifact; grilling is conversation. Require a live human. Never AFK; `ready-for-agent` or `agent:implement` with these types fails closed. _Avoid_: AFK prototype, AFK grilling.

**Wayfinder Task**: Wayfinder ticket of type `task` — manual work that must happen before a decision can be made. Purpose is to do; it unblocks a decision, not delivers the destination. Executor is orthogonal and expressed via triage: HITL Task = `wayfinder:task` + `ready-for-human`; AFK Task = `wayfinder:task` + `ready-for-agent` + tracer-bullet contract, launched via one-shot `agent:implement`. Exactly one readiness required, never both; only AFK form may accept `agent:implement`. Map Notes sentence `Execution is carried into this map` is prose, not machine authorization. _Avoid_: wayfinder:task as standalone executor signal, hitl-task, afk-task.

**HITL Task**: A Wayfinder Task with `wayfinder:task` + `ready-for-human`. Executed with human in the loop; never dispatched by Sandcastle. _Avoid_: hitl-task as separate type.

**AFK Task**: A Wayfinder Task with `wayfinder:task` + `ready-for-agent` + tracer contract, authorized for AFK execution via one-shot `agent:implement`. Dispatched by Sandcastle through same implementation profile as ordinary AFK issues. _Avoid_: afk-task as separate label, wayfinder:task without ready-for-agent.

**Sandcastle**: Common AFK execution substrate for implementation (`ready-for-agent` + one-shot `agent:implement`, consumed on claim to `ready-for-agent` + `agent:in-progress` + assignee) and research (`wayfinder:research` alone). Wayfinder owns purpose and frontier; triage owns durable readiness; `agent:*` owns one-shot commands or transient machine state (`agent:in-progress`, `agent:blocked`); native assignee/`blocked_by` own concurrency and dependencies. Reference ADR 0010. _Avoid_: Sandcastle as implementation-only, duplicate agent:research, wayfinder:* as authorization, ready-for-agent as non-authoritative.

## Cross-system terminology

> **Scope:** Only terms that appear when you generate, store, or render terrain in this monorepo.
> Read alongside the [canonical project language](#language), `docs/reference/upstream/VOXY-FORMAT.md` (grounded Voxy audit), and the version-bound upstream references `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md` and `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md` for detailed version-specific behavior.
> Sources are noted per entry: `minecraft-src` = decompiled `net.minecraft.*`, `voxy` = `external/voxy/src/main/java/me/cortex/voxy/**`, `fabric` = `external/fabric-api`, `project` = `java/src/main/java/com/rhythmatician/lodiffusion/**`.

> **Authority hierarchy:**
> 1. The [Language section](#language) above is authoritative for project language and architectural meanings.
> 2. This cross-system terminology section provides supporting definitions and disambiguation. If it conflicts with the Language section, the Language section wins.
> 3. Version-bound upstream references (`docs/reference/upstream/VOXY-FORMAT.md`, `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md`, `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md`) — authoritative for detailed version-specific external behavior.
> 4. External source corpus (`external/minecraft-src`, `external/voxy`) — mirrored upstream sources.

> **Status legend:** `[External]` stable external system · `[Current]` current project canonical · `[Legacy]` historical / deprecated but still referenced · `[Planned]` design not yet implemented

---

### 0. Unit Conventions — Read This First

Dimension shorthand is a common source of coordinate/layout bugs. Enforce these rules everywhere in docs and code comments:

- **Never write `WorldSection is 32^3` without specifying the unit.** Always write `32^3 voxels` for the storage grid. Always write `blocks` for world-space extents.
- **Block vs voxel vs section are different lattices.** See the hierarchy below; do not mix them without naming the lattice.

```
Minecraft
    Chunk
        vertical column, 16 x worldHeight x 16 blocks
        └── Subchunks
              16^3 blocks each (code alias: Chunk Sections / LevelChunkSection)

Voxy
    WorldSection
        32^3 voxels at every LOD
        ├── L0 voxel = 1^3 blocks   -> region =  32^3 blocks
        ├── L1 voxel = 2^3 blocks   -> region =  64^3 blocks
        ├── L2 voxel = 4^3 blocks   -> region = 128^3 blocks
        ├── L3 voxel = 8^3 blocks   -> region = 256^3 blocks
        └── L4 voxel = 16^3 blocks  -> region = 512^3 blocks
```

| Term | Grid dimensions | World-space size | Meaning |
|------|-----------------|------------------|---------|
| Minecraft chunk | 16 x worldHeight x 16 blocks | 16 x worldHeight x 16 blocks | Vertical XZ column |
| Minecraft subchunk (chunk section) | 16^3 blocks | 16^3 blocks | One 16x16x16 portion of a chunk — called **subchunk** in this repo (code: Chunk Section) |
| Voxy WorldSection | 32^3 voxels | depends on LOD | Voxy storage/rendering unit |
| Voxy WorldSection @ L0 | 32^3 voxels | 32^3 blocks | 1 voxel = 1 block |
| Voxy WorldSection @ L1 | 32^3 voxels | 64^3 blocks | 1 voxel = 2^3 blocks |
| Voxy WorldSection @ L2 | 32^3 voxels | 128^3 blocks | 1 voxel = 4^3 blocks |
| Voxy WorldSection @ L3 | 32^3 voxels | 256^3 blocks | 1 voxel = 8^3 blocks |
| Voxy WorldSection @ L4 | 32^3 voxels | 512^3 blocks | 1 voxel = 16^3 blocks |

---

### 1. Minecraft Vanilla — Chunk Lattice

#### Chunk [External]

```
chunk
    ALWAYS a vertical 16x16 XZ column.
    It is not 16x16x16.
```

The canonical vertical column `16 x worldHeight x 16` blocks (XZ footprint 16x16). In 1.18+ the overworld height is -64 inclusive to 320 exclusive (i.e. -64..319 inclusive) -> 384 block Y values -> 24 vertical sections. A chunk is identified by `ChunkPos(x, z)` where `x = floor(blockX / 16)`, `z = floor(blockZ / 16)`. Persisted as `LevelChunk` (fully generated) or `ProtoChunk` (in-progress). Source: `net.minecraft.world.level.chunk.LevelChunk`, `ProtoChunk`, `net.minecraft.world.level.ChunkPos`.

#### ChunkPos [External]
Immutable `record ChunkPos(int x, int z)` — XZ chunk coordinate. Encodes no Y. Helpers: `ChunkPos.containing(BlockPos)`, `ChunkPos.pack(x,z)`, region helpers `REGION_SIZE = 32` chunks. Source: `net.minecraft.world.level.ChunkPos`.

#### Subchunk (Chunk Section / LevelChunkSection) [External]

```
subchunk
    A 16x16x16 block cube within a chunk.
    Code aliases: Chunk Section (Yarn), LevelChunkSection (Mojang).
```

In this repo we call the 16^3 cube a **subchunk** — your preferred vanilla term. Code aliases are **`LevelChunkSection`** (Mojang) / **`ChunkSection`** (Yarn); Bedrock/community docs also use `subchunk` for the same object. A chunk holds `Sections = worldHeight/16` sections (24 in current overworld). Each section stores block states in a `PalettedContainer<BlockState>` (palette + bit-packing) and a `PalettedContainer<Holder<Biome>>` at 4x4x4 quart resolution -> 64 biome entries per section. Source: `net.minecraft.world.level.chunk.LevelChunkSection`, `net.minecraft.world.level.chunk.PalettedContainer`.

> Do not generalize chunk composition upward: a Voxy WorldSection is not a chunk.

#### SectionPos (Minecraft) [External]

```
SectionPos
    XYZ coordinate of a Minecraft subchunk (chunk section).
    Each increment corresponds to 16 blocks on that axis.
```

Immutable `record SectionPos(int x, int y, int z)` where each coordinate = `block >> 4` (section index in all axes). This *does* include Y, unlike `ChunkPos`. Static helpers: `SectionPos.blockToSectionCoord(int block)`, `SectionPos.of(BlockPos)`, `SectionPos.asLong(x,y,z)`. Used as the key for per-section data: `LevelChunkSection` array (`index = y - minSectionY`), block light, biomes-at-quart. Heightmaps are **not** per-section — they are per-chunk XZ structures (`16x16` per `ChunkPos`) stored on the chunk, not on the section. Source: `net.minecraft.core.SectionPos`.

#### SectionPos (Project — Canonical) [Current]
`java/src/main/java/com/rhythmatician/lodiffusion/voxy/SectionPos.java` — Voxygen's canonical position type. Semantics: **identical** to Minecraft's `SectionPos` at L0 (`block >> 4`), used as the single source of truth for generation and writing. `GLOSSARY.md` forbids calling it "chunk pos" or "WorldSection coord" — it is always `SectionPos`. Wraps `(x,y,z)` for both `VoxelVolumeWriter.writeSection` (16^3) and `writeRegion` (32^3 origin).

#### BlockPos / BlockState [External]
`BlockPos(x,y,z)` = integer world block coordinate. `BlockState` = block type + properties (e.g. `minecraft:grass_block[snowy=false]`). Block states are palette-indexed inside `LevelChunkSection`; there are ~30k distinct block-state IDs in vanilla registry but only a few hundred appear in natural terrain.
#### Heightmap [External]
A `16x16` **per-chunk** (XZ) array storing the highest Y for a predicate. See `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §1` for version-bound validity. In 1.21.11, `WORLD_SURFACE_WG` / `OCEAN_FLOOR_WG` (Usage WORLDGEN) become valid at `ChunkStatus.NOISE`; `WORLD_SURFACE` / `OCEAN_FLOOR` / `MOTION_BLOCKING` / `MOTION_BLOCKING_NO_LEAVES` become valid at `CARVERS`. Stored on `ChunkAccess` keyed by `ChunkPos`, not `SectionPos`. Source: `net.minecraft.world.level.levelgen.Heightmap`.

#### Biome / BiomeSource / MultiNoiseBiomeSource [External]
`Biome` is a registry entry (e.g. `minecraft:plains`). Biomes in chunk sections are stored at quart resolution (4-block cells). Vanilla overworld biome placement uses `MultiNoiseBiomeSource` which evaluates 6 climate `DensityFunction`s (see Noise Router) and looks up the nearest biome via `Climate.ParameterPoint`. In Voxygen/Python the canonical set is 54 overworld biomes alphabetically mapped to IDs `0..53` (255 = unknown); see `GLOSSARY.md` / `BiomeMapping.java`. Source: `net.minecraft.world.level.biome.*`.

#### NoiseRouter / Noise Router [External]
`NoiseRouter` is a 15-field record on `NoiseGeneratorSettings` — see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §2` for the full field list and grouping: Climate 6 (`temperature, vegetation, continents, erosion, depth, ridges`), Density 2 (`preliminarySurfaceLevel, finalDensity` where `finalDensity > 0.0` decides solid — `Aquifer.computeSubstance` returns `null` for `density > 0.0`, allowing the dimension `defaultBlock` (`END_STONE` for The End) to occupy the location per `NoiseGeneratorSettings:70`; `SURFACE_DENSITY_THRESHOLD = 1.5625` is the `slopedCheese` range-choice threshold separating surface-with-entrances from underground logic per `NoiseRouterData:184`, not the final solid/empty threshold), Aquifer 4 (`barrier, fluidLevelFloodedness, fluidLevelSpread, lava`), Veins 3 (`veinToggle, veinRidged, veinGap`). `NoiseRouter.mapAll(Visitor)` rewrites the whole `DensityFunction` tree. Source: `net.minecraft.world.level.levelgen.NoiseRouter:17`, `Aquifer.java:38/135`, `NoiseChunk.java:158-172`, `NoiseBasedChunkGenerator.java:365`, `NoiseRouterData.java:29`.

#### DensityFunction [External]
A functional interface `double compute(FunctionContext)` with an AST-like type hierarchy. Can be sampled directly (`DensityFunction.sample()`) or baked. In Voxygen, `WorldNoiseAccess` / legacy `NoiseTap` samples these per-chunk to obtain heightmap/biome/router channels. The cubiomes equivalent is `sampleBiomeNoise()` returning fixed-point `NP_TEMPERATURE..NP_WEIRDNESS` (divide by 10000). Source: `net.minecraft.world.level.levelgen.DensityFunction`.

#### NoiseConfig / NoiseGeneratorSettings / RandomState [External]
`NoiseGeneratorSettings` is the datapack record that configures a dimension — see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §5` for the full shape (`noiseSettings, noiseRouter, surfaceRule, defaultBlock, seaLevel, aquifersEnabled, oreVeinsEnabled, useLegacyRandomSource`). `NoiseSettings` is per-dimension (Overworld `-64,384,1,2` → cell `4×8`; Nether `0,128,1,2` same; End `0,128,2,1` → `8×4` swapped) and `clampToHeightAccessor` defines the valid lattice. `RandomState` owns the seeded wiring — `PositionalRandomFactory` fork, `ConcurrentHashMap<ResourceKey<NoiseParameters>,NormalNoise>` cache, and `Visitor mapAll` that injects `NormalNoise` into the tree exactly once. Source: `net.minecraft.world.level.levelgen.NoiseGeneratorSettings:35`, `NoiseSettings.java:23`, `RandomState.java:28`.

#### ChunkGenerator / NoiseBasedChunkGenerator [External]
Abstract class responsible for turning a `ChunkPos` -> `ChunkAccess`. Vanilla overworld uses `NoiseBasedChunkGenerator` which runs: aquifer -> noise routing -> surface rules -> carvers -> features/structures. Custom generators extend this; Mixins typically `@Inject` into its methods. Source: `net.minecraft.world.level.levelgen.chunk.ChunkGenerator`.

#### Surface Rule / MaterialRule [External]
Datapack-driven `MaterialRule` tree (formerly surface builder) that assigns final block states to the noise-defined terrain surface (grass, dirt, sand, deepslate ...) based on depth/steepness/biome/water. Formerly `SurfaceRules`. Source: `net.minecraft.world.level.levelgen.SurfaceRules`.

#### Aquifer / FluidStatus [External]
Sub-system that floods terrain below sea level and carves lava lakes. The router's `barrier` and `fluidLevel` density functions feed the aquifer sampler. In abandoned `NoiseTap` tiers this would have been `aquifer3` (`surface/flooded/lava`). Source: `net.minecraft.world.level.levelgen.Aquifer`.

#### Carver (Cave / Ravine / Canyon) [External]
World-gen pass that etches caves after noise terrain. Two families: `CaveCarver` (noodle/cave) and `CanyonCarver`. Expensive to sample; Voxygen defers it (Phase-2 `cavePrior` was `[1,4,4,4]` coarse likelihood). Source: `net.minecraft.world.level.levelgen.carver.*`.

#### ChunkStatus / ChunkPyramid [External]
The generation stage enum — see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §1` for ordering and validity: `EMPTY(0) → STRUCTURE_STARTS → STRUCTURE_REFERENCES → BIOMES → NOISE(4) → SURFACE(5) → CARVERS(6) → FEATURES(7) → INITIALIZE_LIGHT → LIGHT → SPAWN → FULL(11)` with `WORLDGEN_HEIGHTMAPS` valid at `NOISE` and `FINAL_HEIGHTMAPS` at `CARVERS`. `ChunkStatus` ordering governs neighbor requirements. Source: `net.minecraft.world.level.chunk.status.ChunkStatus:28`.

#### LevelHeightAccessor [External]
Reports `getMinY()`, `getHeight()`, `getSectionsCount()`, `getMinSection()`. Overworld in 1.21.11: `minY=-64`, `height=384`, `sections=24`, `minSection=-4` (see `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md §5/§10` for per-dimension `NoiseSettings` that define the underlying cell lattice). Source: `net.minecraft.world.level.LevelHeightAccessor`.

---

### 2. Voxy — Sparse Voxel LOD Store [External]

Version audited: `reference-code/voxy` v0.2.11-alpha. See `docs/reference/upstream/VOXY-FORMAT.md` for full byte-level spec.

#### Voxel [External]

```
voxel
    One cell in a Voxy WorldSection.

    A voxel is not inherently one Minecraft block.

    At L0: 1 voxel represents 1x1x1 blocks.
    At L1: 1 voxel represents 2x2x2 blocks.
    At L2: 1 voxel represents 4x4x4 blocks.
    At L3: 1 voxel represents 8x8x8 blocks.
    At L4: 1 voxel represents 16x16x16 blocks.
```

Without this distinction phrases like `32^3 section` are ambiguous — always qualify `32^3 voxels` vs `32^3 blocks`.

#### WorldSection [External]

```
WorldSection
    Voxy concept only.
    ALWAYS 32x32x32 voxels.
    Never call it a chunk, subchunk, or chunk section.
```

The sole persistent storage unit in Voxy — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §1` for geometry (32³ voxels, `long[32768]` YZX `(y<<10)|(z<<5)|x`, coordinates `(lvl,x,y,z)` lvl 0..4) and lifecycle. Source: `voxy/common/world/WorldSection.java`.

At L0 only, a 32^3-voxel WorldSection spans 32^3 Minecraft blocks, which spatially corresponds to 2x2x2 subchunks. At higher LODs the WorldSection still contains exactly 32^3 voxels, but represents a progressively larger block-space volume — it does not "contain" those chunk sections as stored data.

BAD: `WorldSection = 32^3`

GOOD: `WorldSection = 32^3 voxels`

GOOD: `An L2 WorldSection is 32^3 voxels representing a 128^3-block world-space region.`

#### LOD / Level (Voxy) vs. Project Level [External vs Current]
- **Voxy `lvl` [External]**: `0` finest (1 voxel = 1 block, section covers 32 blocks per axis), `4` coarsest (1 voxel = 16 blocks, section covers 512 blocks per axis). In general a WorldSection at `lvl=n` covers `32 x 2^n` world blocks per axis.
- **Project `Level` [Current]** (`java/.../voxy/Level.java`): LOD refinement/scale `L0..L4`, `L0` finest. For `writeRegion`, `Level` determines the world-space scale represented by each semantic voxel (`voxelSize = 1 << level`, `regionBlocks = 32 << level`). It is never inferred from `VoxelVolume` extent. The operation determines the required extent (`writeSection` requires 16^3, `writeRegion` requires 32^3). Validated; never inferred. Level is not storage — Voxy `WorldSection` (32^3) remains a private consolidation detail, never `Level`. Source: `GLOSSARY.md` + `voxy/Level.java`.

Equivalent world-space footprints (dimensional equivalence, not storage composition):

| Voxy `lvl` | Voxel size | World footprint per WorldSection | Equivalent world-space footprint |
|---|---|---|---|
| 0 | 1 block | 32^3 blocks | 32^3 blocks = 2x2x2 subchunks (spatial correspondence) |
| 1 | 2 blocks | 64^3 blocks | 64^3 blocks = 4x4x4 subchunks extent |
| 2 | 4 blocks | 128^3 blocks | 128^3 blocks = 8x8x8 subchunks extent |
| 3 | 8 blocks | 256^3 blocks | 256^3 blocks = 16x16x16 subchunks extent |
| 4 | 16 blocks | 512^3 blocks | 512^3 blocks = 32x32x32 subchunks extent |

Do not say a higher-level WorldSection "contains" thousands of chunk sections — those Minecraft sections are not stored inside the Voxy WorldSection.

#### WorldSection Key (64-bit packed ID) [External]
`WorldEngine.getWorldSectionId(lvl,x,y,z)` packing — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §2` ( `((lvl&0xF)<<60)|(y&0xFF)<<52|(z&0xFFFFFF)<<28|(x&0xFFFFFF)<<4`, 4 spare bits, decoders sign-extend). Used as RocksDB key. Source: `voxy/common/world/WorldEngine.java`.

#### VoxelizedSection [External]
**Ingestion-only** container (`voxy/common/voxelization/VoxelizedSection.java`) holding the 5-level mip pyramid for a single `16^3` subchunk — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §5` for layout (`long[4681]`, offsets `0/4096/4608/4672/4680`, `WorldConversionFactory.convert()` → `mipSection()` → `WorldUpdater.insertUpdate()`). Never stored on disk.

#### Mapper — Block/Biome Mapping and 64-bit Voxel Encoding [External]
`voxy/common/world/other/Mapper.java` — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §3-§4` for bit layout (`63..56` light `block<<4|sky`, `55..47` biome 9b, `46..27` blockId 20b, `AIR=0` via block bits) and per-world sequential identity (persisted via `storage.putIdMapping()`, not vanilla registry ID).

#### Mipper / Mip (Mipper.java) [External]
Voxy's LOD downsampler — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §6` for algorithm (opacity-biased selection, score `(blockOpacity << 4) | cornerPriority` `I111=7..I000=0`, highest wins; all-air averages light). Opaque blocks win over transparent under this rule. Source: `voxy/common/world/other/Mipper.java`.

#### nonEmptyChildren / Octant Mask [External]
`byte nonEmptyChildren` on each `WorldSection` — 8-bit octant mask — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §7` for bit index `Ixyz` and geometry. `0b00000000`=empty (skipped). Source: `voxy/common/world/WorldSection.java`.

#### Octant [External]
One of the 8 children of a WorldSection. For parent `(px,py,pz)` at level `L`, child `octant in 0..7` is at `childX=(px<<1)|(octant&1)`, `childY=(py<<1)|((octant>>2)&1)`, `childZ=(pz<<1)|((octant>>1)&1)` at level `L-1`. Octants are extracted as `16^3` sub-cubes of a parent `32^3`-voxel grid (then 2x upsampled for refinement model input).

#### WorldEngine / ActiveSectionTracker / WorldUpdater [External]
See `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §8-§9` for lifecycle: `WorldEngine` owns `SectionStorage`/`Mapper`/`ActiveSectionTracker` (`acquire`, `markDirty`, `MAX_LOD_LAYER=4`), `ActiveSectionTracker` MRU cache (1024 default, 2048 if ≥4 GiB) → save queue, `WorldUpdater`/`SectionSavingService` flush. Source: `voxy/common/world/WorldEngine.java`.

#### SectionStorage / SectionSerializationStorage / RocksDB + ZSTD [External]
`SectionStorage` pluggable backend — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §10` for default composition (`RocksDBStorageBackend` + `CompressionStorageAdaptor(ZSTD level 1)` + `SectionSerializationStorage`). Alternatives: LMDB, Redis, in-memory.

#### Serialization Format and Morton (Z-curve) Order [External]
Serialized section — see `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md §11` for layout (`SaveLoadSystem3` little-endian, YZX-linear `(y<<10)|(z<<5)|x`, metadata low 2 bytes lutLen + next byte nonEmptyChildren). `SaveLoadSystem` (older) is big-endian. Morton helpers exist but are not the storage path. Tested specs: `docs/reference/upstream/VOXY-FORMAT.md`, `python/voxel_tree/voxy_format`.

---

### 3. Fabric — Mod Loader and Interop (terrain-relevant subset) [External]

#### Fabric Loader [External]
The bootstrap that loads mods on vanilla. Reads `fabric.mod.json` from each jar, resolves dependencies, and invokes entrypoints. Not terrain-specific but required for every terrain mod (Voxy, Sodium compat, LODiffusion). Version pinned via `fabric-loader` in `gradle.properties`.

#### fabric.mod.json [External]
Mod metadata + entrypoint manifest. For LODiffusion / Voxy:
```json
{ "schemaVersion":1, "id":"lodiffusion", "entrypoints": { "client":["com.rhythmatician.lodiffusion.LodiffusionClient"], "main":["com.rhythmatician.lodiffusion.HelloTerrainMod"] } }
```
`client` runs on `MinecraftClient`, `main`/`server` on dedicated server. `depends: { "fabricloader": ">=0.18.4", "minecraft":"1.21.11", "java":">=21" } (verified from `java/src/main/resources/fabric.mod.json` 2026-08-10)`.

#### Fabric API [External] (`external/fabric-api`)
A collection of hooks/events that mods use instead of raw Mixins where possible. Terrain-adjacent modules include `fabric-api: fabric-events-lifecycle` (server tick, chunk load/unload), `fabric-rendering-v1` (render hooks). Voxygen uses Fabric API for `ClientChunkEvents`, `ServerLifecycleEvents`, and Sodium/Flashback compat shims. Fabric API itself adds no terrain generation logic — it is plumbing.

#### Mixin (SpongePowered Mixin) [External]
Bytecode injection framework bundled by Fabric Loader. Terrain mods inject into `ChunkGenerator`, `ClientChunkCache`, `LevelRenderer`, `LayerLightSectionStorage`, `SodiumChunkRenderer`, etc. Voxy's mixins in `voxy/client/mixin/**` intercept chunk load/unload and light updates to maintain `WorldSection` cache coherency. Annotations: `@Mixin(TargetClass.class)`, `@Inject(method="...", at=@At("HEAD"))`, `@Overwrite`, `@Shadow`. Mixins are the reason `external/minecraft-src` exists — decompiled mapped source needed to locate injection points.

#### Fabric Loom (gradle plugin loom) [External]
Gradle plugin that deobfuscates/maps Minecraft jars, remaps mod code per Yarn/Mojang mappings, and runs `genSources` to produce `external/minecraft-src`. Powers `gradlew build` and `gradlew genSources`.

---

### 4. Voxygen Project Canonicals (bridge between Minecraft and Voxy) [Current unless noted]

| Project term | What it is | Maps to | Avoid saying |
|---|---|---|---|
| **SectionPos** | `record SectionPos(int x,int y,int z)` where block>>4. Single position type for generation/writing at L0. | Minecraft `SectionPos` at L0 | chunk pos, WorldSection coord, wsX (prefer `subchunk` over `chunk section` in prose) |
| **Level** | LOD refinement/scale `L0..L4`, `L0` finest. For `writeRegion`, `Level` determines the world-space scale represented by each semantic voxel (`voxelSize = 1 << level`). It is never inferred from `VoxelVolume` extent; the operation determines the required extent. Validated; never inferred. Level is not storage — Voxy `WorldSection` (32^3 voxels) remains a private consolidation detail, never `Level`. | Voxy `lvl` numerically, but kept distinct in code | FULL32, storage level, Voxy level |
| **VoxelVolume** | Semantic dense XYZ cube of canonical `(blockId, biomeId)`, accessed through an opaque coordinate API `blockId(x,y,z)` / `biomeId(x,y,z)`. Valid operation-specific extents are currently 16 and 32. Backing storage and linearization are implementation details (primitive arrays or otherwise) and are not part of the contract. | semantic terrain volume; adapted from/to backend-specific representations (see `RealVoxyVolumeWriter` for Voxy `WorldSection.long[32768]` details) | `long[] yzx`, packed voxel, Voxy voxel, `int[]` with fixed linearization, 32768, `x+y*E+z*E*E` |
| **Canonical Block Registry** | Versioned stable mapping block identities -> stable canonical IDs (0 = air). In this repo the canonical ID **is** the stable `BlockVocabulary` canonical index (built from `block_mapping` / `config/voxy_vocab.json`); "Canonical Block Registry" is the preferred project name for that same number. There is a single ID space; do not invent a parallel "vocab index" registry. Must be proved identical Python <-> Java via explicit version/hash in contract metadata, not per-volume (will be verified; not yet enforced per-volume). | Voxy `Mapper` block table *per world* (not reused — do not conflate) | Voxy block ID, packed ID, unqualified "vocab index" (use "canonical ID" or "BlockVocabulary canonical index" if you must qualify) |
| **Canonical Biome Registry** | 54-entry alphabetically-ordered overworld biome map `0..53`, 255=unknown, shared Python+Java. Must be proved identical via version/hash in contract metadata (will be verified; not yet enforced). | Voxy `Mapper` biome table / vanilla `Biome` registry (not reused) | Voxy biome ID |
| **VoxelVolumeWriter** | Deep module seam between generation and storage with two explicit operations: `writeSection(SectionPos, VoxelVolume[16])` and `writeRegion(SectionPos origin, Level, VoxelVolume[32])`. No extent is inferred; contract violations throw `IllegalArgumentException`, binding unavailability throws unchecked `VolumeUnavailableException`. Hides storage details; `WorldSection` mapping stays private. | Storage backends (see below) | VoxySectionWriter, VoxyCompat, VoxyEngine direct |
| **HeightPlanes / HeightmapFallbackGenerator** | `[5,32,32]` tensor `(surface, ocean_floor, slope_x, slope_z, curvature)` tiled per WorldSection. Fallback synthesizes when chunk not loaded. | Minecraft `Heightmap` WORLD_SURFACE_WG etc. | heightmap 16x16 only |
| **WorldNoiseAccess / AnchorSampler** | Runtime sampler that produces `(HeightPlanes, biome[32x32], y_index, level)` per section from loaded chunks or `NoiseConfig` sampling. Successor to abandoned `NoiseTap`. | `NoiseTap` (legacy), `NoiseConfig` sampling | router6, RouterField |
| **Router6 / Noise Router (legacy) [Legacy]** | Former 6-channel conditioning `temperature, vegetation, continentalness, erosion, depth, ridges` sampled from `DensityFunction`s via `NoiseTap`. **Dropped March 2026** — redundant with biome+heightmap (see `docs/adr/0002-drop-router6-conditioning.md`; original `python/docs/NOISE-DESIGN.md` deleted, rationale retained in the ADR). Remains in interfaces for reference. | Minecraft `NoiseRouter` 6 climate DensityFunctions | still required |

---

### 5. Voxygen Write Path — Operations and Adapters [Current]

#### writeSection [Current]
`WriteOutcome writeSection(SectionPos pos, VoxelVolume volume)` on `VoxelVolumeWriter`. Writes one L0 `16^3` section. Requires `volume.extent() == 16`. Origin `pos` is the section's own coordinate. Contract violations throw `IllegalArgumentException`. Returns `WriteOutcome` (`WRITTEN` / `SKIPPED_AIR` / `SKIPPED_EXISTS`). Source: `java/.../voxy/VoxelVolumeWriter.java`.

#### writeRegion [Current]
`WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume)` on `VoxelVolumeWriter`. Writes one `32^3`-voxel octree region at the given `Level`. Requires `volume.extent() == 32` and that `origin` is aligned to the region grid (`origin % level.regionSections() == 0` per axis). `Level` controls voxel scale (`1 << level` blocks per voxel), not volume size. Never inferred from the volume. Source: `java/.../voxy/VoxelVolumeWriter.java`, `Level.java#isAligned`.

#### VoxelVolumeWriter (interface) [Current]
Deep seam with exactly the two operations above and no other write entry points. Implementations must not add overloads that infer `Level` from extent. Binding unavailability throws unchecked `VolumeUnavailableException` (extends `IllegalStateException`). See also `WriteOutcome` and `VolumeUnavailableException`.

#### RealVoxyVolumeWriter [Current]
The production `VoxelVolumeWriter` (`java/src/main/java/com/rhythmatician/lodiffusion/voxy/RealVoxyVolumeWriter.java`) that encodes semantic `(blockId, biomeId)` via `VoxyBlockMapper` / `BlockVocabulary` and writes into Voxy's `WorldSection` store. **This is the only place where** YZX linearization `(y<<10)|(z<<5)|x`, `long[]` packing, `VarHandle`/CAS, reflection, light defaults, and `WorldSection` / `WorldEngine` mapping live. Callers behind the `VoxelVolumeWriter` interface never see these details. Obtained via `VoxyWorldBinding` when Voxy is present; otherwise the binding is unavailable and writes throw `VolumeUnavailableException`. Sources: `java/.../voxy/RealVoxyVolumeWriter.java`, `VoxyCompat.java`, `VoxyBlockMapper.java`.

#### InMemoryVolumeWriter [Current]
Test/contract adapter for `VoxelVolumeWriter` (`java/.../voxy/InMemoryVolumeWriter.java`). Records semantic `WriteRecord` entries — `SectionRecord(SectionPos, VoxelVolume)` and `RegionRecord(SectionPos origin, Level, VoxelVolume)` — as opaque `VoxelVolume` snapshots keyed by position/level. Implements the intended semantic writer guards for contract testing (all-air -> `SKIPPED_AIR`, second write to same position -> `SKIPPED_EXISTS`) but **never stores or emulates Voxy packed `long[]` or `WorldSection` internals**. Deterministic and free of Minecraft/Voxy classes; can be marked unavailable to test error paths.

#### VoxelPredictionDecoder [Current]
Inference-boundary module (`java/src/main/java/com/rhythmatician/lodiffusion/voxy/VoxelPredictionDecoder.java`) that decodes model outputs (logits/argmax) into a semantic `VoxelVolume`. The only place that understands model output layout. The writer never does argmax. Avoid: "writer argmax", "logits in writer".

#### WriteOutcome / VolumeUnavailableException [Current]
`WriteOutcome` is `WRITTEN | SKIPPED_AIR | SKIPPED_EXISTS`. `SKIPPED_AIR` and `SKIPPED_EXISTS` are normal runtime decisions, not errors. Contract violations throw `IllegalArgumentException`; binding unavailability throws unchecked `VolumeUnavailableException` (extends `IllegalStateException`, not declared). Avoid: `SKIPPED_BOUNDS`, `SKIPPED_INVALID`, checked exception.

---

### 6. Quick Disambiguation

- **"Chunk" vs "Subchunk" vs "WorldSection"** — Chunk = XZ column (16 x worldHeight x 16 blocks); Subchunk (= Chunk Section / LevelChunkSection) = 16^3 block cube inside a chunk; WorldSection = Voxy 32^3-voxel storage tile. Say "chunk" only for the XZ column, "subchunk" for 16^3 blocks, "WorldSection" for Voxy's 32^3 voxels. At L0 only, one WorldSection spatially corresponds to 2x2x2 subchunks; at higher LODs the correspondence is dimensional equivalence, not containment.
- **"Subchunk"** — In this repo, **subchunk** is the preferred term in all prose (docs, comments, agent output) for the 16³ block cube. Code/API names remain `LevelChunkSection` (Mojang) / `ChunkSection` (Yarn).
- **"Noise Router"** — vanilla's `NoiseRouter` DensityFunction graph; not a Voxygen runtime input since router6 was removed. When docs say "noise router" they usually mean the 6 climate fields.
- **"Level" vs "LOD" vs "lvl"** — `L0` finest in Voxygen; higher number = coarser. Some renderers invert this. Voxy file uses field name `lvl`; Voxygen uses type `Level`. Always state the scale. `Level` never inferred from `VoxelVolume` extent; the operation (`writeSection` vs `writeRegion`) determines the required extent and whether a `Level` is needed.
- **"Palette" vs "Mapper" vs "Registry"** — Palette = per-section `PalettedContainer` local compression; Mapper = Voxy per-world global `long` packing; Registry = vanilla `Registries.BLOCK / BIOME` authoritative IDs; Canonical Registry = Voxygen cross-language stable IDs (same number as `BlockVocabulary` canonical index — one ID space).
- **"VoxelVolume" backing** — Do not freeze to `int[]` or to `x+y*E+z*E*E` or to YZX. The contract is an opaque XYZ coordinate API; backing and linearization are implementation details (currently primitive arrays, but not frozen).
- **YZX vs XYZ vs Morton** — Voxy in-memory order is YZX `(y<<10)|(z<<5)|x` inside `RealVoxyVolumeWriter` only; `VoxelVolume` API is XYZ; serialized order for `SaveLoadSystem3` is YZX-linear (little-endian; Morton `lin2z`/`z2lin` helpers exist but are not the storage path — see `docs/reference/upstream/VOXY-FORMAT.md`). Convert only inside the writer.

---

### 7. Where to Learn More

- Version-bound upstream facts: `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md` (§1-§18) and `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md` (§1-§11)
- Chunk lattice and generation order: `external/minecraft-src/src/net/minecraft/world/level/chunk/**`, `net.minecraft.core.SectionPos`, `net.minecraft.world.level.ChunkPos`
- Noise: `net.minecraft.world.level.levelgen.NoiseRouter`, `DensityFunction`, `NoiseGeneratorSettings` + `docs/adr/0002-drop-router6-conditioning.md` (router6 removed; original NOISE-DESIGN.md deleted — historical rationale retained in the ADR)
- Voxy store internals: `external/voxy/src/main/java/me/cortex/voxy/common/world/WorldSection.java`, `WorldEngine.java`, `common/world/other/Mapper.java`, `Mipper.java`, `common/voxelization/VoxelizedSection.java`, `docs/reference/upstream/VOXY-FORMAT.md` + `python/voxel_tree/voxy_format`
- Canonical language: `GLOSSARY.md`
