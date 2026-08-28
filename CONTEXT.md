# Voxygen

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

**Dimension Generation Domain**: Per-dimension closed Y interval [minY, maxY) plus NoiseSettings (minY, height, cellWidth, cellHeight) and profile flags (aquifersEnabled, beardifier, blending) that define which rows are vanilla-real and which side effects are dead code for that dimension. Derived via WorldSectionCoord.worldSectionToBlockMin/Max(wsY, Level). End [0,128) create(0,128,2,1) aquifersEnabled=false; Overworld [-64,320) create(-64,384,1,2) true; Nether [0,128) create(0,128,1,2). _Avoid_: hard-coded END_MIN_Y/MAX_Y as global, global height, one domain.

**Feature Eligibility**: Level-aware predicate isEligible(blockX, blockZ, Level) ? bool that gates a placed block family (chorus_plant 197 / chorus_flower 196 / end_stone 359 and later oak_log / basalt / nether_wart) by biome and Level. Specialization BiomeEligibility.isChorusBiome(blockX,blockZ) is FeatureEligibility for chorus at all Levels; chorus is a placed feature and remains an independent responsibility under Worldgen Partition v1 (registered structures become Profile-Inactive -> N/A when generate_structures=false, placed features do not). _Avoid_: BiomeEligibility as chorus-only global, feature as biome, placed feature as Profile-Inactive.

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
