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

**Error Character and Honest Omission**: Evaluate a candidate against its declared stage target and separately against final vanilla so omission cost remains visible. Attribute error causally to an omitted responsibility only with a paired stage or counterfactual oracle; otherwise report spatial overlap with the omitted responsibility without causal allocation. _Avoid_: mask-only omission, final-only score, causal claim from an omission tag, double attribution.

**Pop and Vanilla Convergence**: Pop is consecutive-Level visible transition error (L_n versus L_{n-1}); Vanilla Convergence is each Level's disagreement with eventual exact vanilla, retaining stage and omission context. Pop is secondary to correctness; both are acceptance-bearing under their Fidelity Profile. _Avoid_: single pop number, RGB transition error, hidden omission cost.

**Scaffold Preference / Residual Default**: Preference for a useful deterministic or exact-vanilla scaffold plus a learned residual over full learned prediction, unless evidence favors full prediction or no useful scaffold exists. _Avoid_: residual without a useful scaffold, predeclared full versus residual by Level, full learned by default.

### Wayfinder

**Wayfinder Map**: Single issue labelled `wayfinder:map` that indexes a destination, decisions-so-far, and fog. _Avoid_: roadmap, backlog.

**Wayfinder Ticket**: Child issue of the Wayfinder Map labelled `wayfinder:<type>` where `<type>` is one of `research`, `prototype`, `grilling`, `task`. Purpose and executor are orthogonal.

**Research Ticket**: Wayfinder ticket of type `research` — AFK reading of docs/APIs/local KB to surface a fact a decision waits on. Resolved by a research subagent. _Avoid_: research task, HITL research.

**Prototype Ticket / Grilling Ticket**: Wayfinder tickets of type `prototype` and `grilling` — HITL only. Prototype raises fidelity with a cheap artifact; grilling is conversation. Require a live human. _Avoid_: AFK prototype, AFK grilling.

**Wayfinder Task**: Wayfinder ticket of type `task` — manual work that must happen before a decision can be made. Purpose is to do; it unblocks a decision, not delivers the destination. Executor is orthogonal: a Wayfinder Task is either HITL or AFK. _Avoid_: wayfinder:task as standalone executor signal, hitl-task, afk-task.

**HITL Task**: A Wayfinder Task executed with a human in the loop (checklist handed to human). Labelled `wayfinder:task` without `agent:implement`; lives in Wayfinder, never dispatched by Sandcastle. _Avoid_: hitl-task as separate type.

**AFK Task**: A Wayfinder Task authorized for AFK execution. Labelled `wayfinder:task` + `agent:implement` + durable map Notes signal `Execution is carried into this map` (v0 proxied via ticket body) + tracer-bullet contract. Dispatched by Sandcastle. _Avoid_: afk-task as separate label, wayfinder:task without agent:implement.
