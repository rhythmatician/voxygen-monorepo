# 0003 — Correct distant terrain converges to exact vanilla

Date: 2026-08-16
Status: Accepted

## Context

Voxygen needs a quality target before partitioning vanilla worldgen responsibilities among reuse, exact ports, deterministic approximations, learned approximations, and omissions. A plausible Minecraft-like landscape could be cheap and visually coherent at a fixed distance yet contradict the seed's eventual vanilla terrain, causing mountains, coastlines, fluids, or canopy to reshape as Levels refine.

Correctness and execution ownership are separate questions. Voxy Render L0 is a block-resolution semantic rendering representation, while an Authoritative Chunk is a playable Minecraft chunk with staged worldgen and lifecycle obligations. Refusing Render L0 could expose a visibly coarse L1 handoff near the vanilla render boundary; producing Render L0 and later recomputing the same exact terrain for a playable chunk could waste work. Neither concern proves that vanilla must own L0 or that Voxygen can already produce an Authoritative Chunk.

A single voxel-accuracy loss is also misleading. Geometry and fluid topology dominate distant perception; a height field cannot express caves or overhangs; material mistakes differ perceptually by Level and dimension; omitted stages create characteristic disagreement; and lighting or texture can confound RGB comparisons. Fixed thresholds or corpus sizes chosen before the measurement apparatus exists would turn guesses into acceptance policy.

## Decision

Correct Distant Terrain means that every Voxygen render representation L4..L0 approximates Authoritative Terrain: the exact terrain semantics defined by the frozen vanilla seed and worldgen profile. The vanilla contract owns the truth, but this decision does not assign computation ownership. Downstream choices preserve vanilla-only execution, Voxygen Render L0 followed by vanilla chunk generation, shared exact generation consumed by both systems, and an exact Voxygen Authoritative Chunk path; the worldgen partition and runtime policy choose among them. Correctness is lexicographic: visible geometry/silhouette and the Topology Bundle dominate Ground Surface, which dominates exposed material family, followed by exact canonical identity only where a Fidelity Profile claims it. Octree and coverage validity are hard invariants. Pop is first-class but secondary.

Acceptance combines voxel/domain observables with geometry-only screen-space observables derived from runtime Level selection; RGB is excluded. The Correctness Metric Suite defines topology and fluid occupancy, projected silhouette/occupancy, Ground Surface error, exposed material family, claimed exact identity, structural validity, consecutive-Level Pop, and per-Level Vanilla Convergence. Metric roles are explicit and numerical budgets remain unset until trustworthy distributions exist.

Ground Surface is derived from an exact-vanilla reference after removing non-ground vegetation and canopy, including trunks. Fluid surface is separate, and occupancy/silhouette retain responsibility for geometry a 2D field cannot represent.

Material correctness uses a hierarchical, versioned, total mapping over the Canonical Block Registry and the source-closed feasible profiles established by [#26](https://github.com/rhythmatician/voxygen-monorepo/issues/26). Frequency may prioritize perceptual review but may not remove reachable blocks. Dimension-defining substrates must remain distinguishable at L4/L3 and may refine further at finer Levels; snow remains distinct from ice. Unmapped IDs are hard failures; `other` means explicitly reviewed without a dedicated family, and excessive observed `other` invalidates an experiment. [#27](https://github.com/rhythmatician/voxygen-monorepo/issues/27) and [#37](https://github.com/rhythmatician/voxygen-monorepo/issues/37) provide empirical evidence; [#38](https://github.com/rhythmatician/voxygen-monorepo/issues/38) owns adoption of concrete visual-equivalence classes and per-Level vocabularies.

An omitted stage is evaluated against the stage target the partition claims and separately against final vanilla. Causal allocation to an omitted responsibility requires a paired stage or counterfactual oracle; an omission label alone supports only an overlap statement.

Primary strata are dimension × Level. Biome and morphology are coverage tags. Seed is the independent sampling unit and regions are nested observations. Measurement validation begins with a one-seed End tracer using `generate_structures=false`, preserved in provenance. It exercises L4→L1 with every metric definition and provenance field enabled; preserves the manifest, reference and candidate artifacts, raw metrics, Level comparisons, and canonical geometry renders; and checks controlled obvious perturbations. Deterministic outputs require canonical-byte identity, while containers with irrelevant nondeterminism require explicitly defined semantic identity. The tracer makes no population claim.

After tracer repairs, Measurement Protocol v1 and its statistical analysis plan are frozen before evaluating a multi-seed pilot. The protocol fixes reference and candidate domains, exclusions, ground roles, exposed faces, fluid boundaries, views, empty-denominator handling, aggregation, fidelity, and provenance. Every result references its Fidelity Profile ID/hash and preserves raw paired observations keyed by seed × region × dimension × Level × stage target, with coverage tags and provenance; seed remains the independent sampling unit and regions remain nested observations. The pilot may then inform corpus size and numerical budgets, not post-outcome estimator choice. Protocol versions are immutable, and results with different hashes are not silently pooled.

For each stage × dimension × Level, first seek the cheapest useful deterministic or exact-vanilla scaffold. Prefer scaffold plus residual when one exists; a full learned approximation requires evidence, while a cell without a useful scaffold need not force a residual formulation.

Preserve shared exact generation as a candidate architecture: exact work performed ahead of the player may feed coarse/render representations and later continue into an Authoritative Chunk rather than being recomputed. This is a preserve-futures constraint, not a claim that Minecraft exposes a sufficient reuse seam or that Voxygen can already satisfy the full chunk lifecycle.

## Alternatives considered

* Accept plausible Minecraft-like terrain without seed fidelity: rejected because refinement could replace distant landmarks rather than reveal them.
* Optimize one scalar weighted voxel loss: rejected because it can trade catastrophic topology or silhouette failures for many cheap block matches and cannot express invariants.
* Compare only with final vanilla or mask omitted voxels: rejected because either punishes a candidate for work it did not claim or hides the visible cost of omission.
* Hand-author a fixed coarse material list in this decision: rejected because feasible profiles and perceptual evidence already have separate owners, and dimension-defining materials could be collapsed incorrectly.
* Begin with a fixed multi-seed corpus and thresholds: rejected because deterministic replay and metric face validity must be established before statistical evidence is trustworthy.
* Learn every Level in full: rejected because exact or deterministic scaffolds may provide cheaper seed fidelity and leave a smaller measurable residual.
* Decide now that vanilla exclusively owns both Render L0 and Authoritative Chunk execution: rejected as premature because it prejudges visual handoff quality and reuse; vanilla-only execution remains a downstream candidate.
* Promote approximate Render L0 directly into the playable world: rejected because it would change worldgen unless exact Authoritative Chunk semantics are proved.

## Consequences

* Worldgen partitions and experiments must select a versioned Fidelity Profile and stage target rather than letting artifacts declare convenient fidelity after evaluation.
* Worldgen partitioning must decide Render L0 execution and Authoritative Chunk execution separately; example `L0 = vanilla` cells are not presumptive decisions.
* Training objectives may use convenient domain losses, but promotion evidence must cover the acceptance observables and their lexicographic roles.
* A Render L0 candidate is learned-model output when its Fidelity Profile says so. Exact Authoritative Chunk parity is a separate oracle and pipeline-integrity gate; approximate Render L0 cannot satisfy it merely by looking correct.
* Taxonomy contents, metric implementations, statistical estimators, corpus size, and numerical budgets remain downstream evidence-backed decisions; this ADR fixes their constraints and sequencing.
* Changing a Measurement Protocol creates a new version and prevents silent pooling with earlier results.

## When to reconsider

Reconsider if the product intentionally stops preserving the frozen vanilla world's terrain semantics, if controlled perceptual evidence shows the hierarchy ranks visible failures incorrectly, or if a deterministic scaffold is measured to cost more or converge worse than a full learned alternative.
