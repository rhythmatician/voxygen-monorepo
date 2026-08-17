# 0004 — Coarse-first coverage with proximity-driven refinement

Date: 2026-08-17
Status: Proposed

## Context

Voxygen's product goal is not merely to generate distant terrain cheaply. It is to make a new world feel immediately vast while allowing the representation around the player to refine naturally toward vanilla detail.

Those goals create two simultaneous pressures:

1. Missing distant terrain should be filled as quickly as possible.
2. Terrain near the player, especially at the vanilla-render boundary, must not remain unnecessarily coarse.

An L0-first generation strategy works against the first goal. If Voxygen computes block-resolution terrain over a large world-space area before obtaining coarse representations, it performs the most detailed work before producing the representation needed first at the horizon. The current fallback path demonstrates this problem: it constructs L0 `16³` semantic volumes and writes them through `writeSection`, after which Voxy may derive coarser representations. Voxygen already has a separate `writeRegion` seam for writing a semantic `32³` volume directly at an explicit Level.

However, simply reversing the order globally would create the opposite failure. Completing an entire L4 horizon before beginning refinement could leave extremely coarse terrain immediately adjacent to detailed vanilla chunks. At L4, one Voxy voxel represents `16³` Minecraft blocks, so such a handoff would be visibly unacceptable.

ADR 0003 established the correctness direction: every Render Level must converge toward Authoritative Terrain, exact or deterministic scaffolds should be preferred where useful, and learned approximation should earn its role by evidence rather than being assumed. It did not decide the runtime ordering of coverage and refinement.

The runtime therefore needs a scheduling principle that serves both horizon latency and near-player visual quality without requiring one universal generation mechanism.

## Decision

Voxygen Render L4..L0 generation is **target-Level-native and scheduled along two concurrent fronts: coarse coverage and proximity-driven refinement**.

### Coarse coverage

Voxygen should generate the Level required for distant coverage directly.

If L4 is sufficient for a distant region, Voxygen must be able to produce and write L4 without first computing L3, L2, L1, or L0 for that region.

L0 is not a mandatory precursor, canonical intermediate representation, or universal source from which coarser Render Levels must be derived.

A production path must not compute fine terrain solely to manufacture a coarser representation needed by the renderer.

### Proximity-driven refinement

Coarse-first does **not** mean:

> finish all L4 work, then all L3 work, then all L2 work, and so on.

Refinement proceeds concurrently with expanding coarse coverage.

As terrain becomes more relevant through proximity and visibility, its required refinement increases. Near-player terrain therefore competes aggressively for runtime budget even while coarse coverage continues farther outward.

The vanilla-render boundary is refinement-critical. Voxygen should not leave an unnecessarily coarse representation adjacent to detailed vanilla terrain when an appropriate finer representation can be produced within the runtime budget.

The desired spatial shape is therefore approximately:

```text
player
  │
  │ vanilla / authoritative rendered terrain
  │████████████████
  │
  │ finest required Voxygen representation
  │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓
  │
  │ progressively coarser refinement
  │▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒
  │
  │░░░░░░░░░░░░░░░░░░░░░░░
  │
  │ coarse horizon coverage
  │.................................
```

The scheduler therefore advances two conceptually independent frontiers:

```text
coverage frontier   → outward
refinement frontier → toward regions of greater proximity/visibility need
```

Neither frontier waits for the other to complete globally.

### Generation mechanism is independent of scheduling

The scheduler decides **what region and Level are needed**.

Terrain-production logic decides **how that semantic terrain is produced**.

Storage adaptation decides **how the semantic result is persisted**.

For a requested region and Level, the Worldgen Partition may assign responsibilities to:

* reuse vanilla;
* exact port;
* deterministic approximation;
* learned approximation;
* omit or defer.

Deterministic reconstruction is a first-class production mechanism rather than synonymous with fallback behavior.

Learned inference is an optional production mechanism rather than an architectural requirement. A Level, responsibility, or entire dimension may require no ONNX model when exact or deterministic computation satisfies its fidelity and runtime claims.

### Target-Level-native does not require independent Levels

This decision does not choose the cross-Level refinement topology.

A finer representation may later depend on:

* shared exact vanilla signals;
* a shared deterministic scaffold;
* a coarser representation;
* a learned residual;
* shared cached state;
* or another dependency selected by the refinement-topology decision.

The invariant is narrower:

> Voxygen does not require L0 computation merely to provide a coarser requested Render Level.

### Fallback is not the production architecture

The existing fallback path demonstrated that selected worldgen signals can be transformed deterministically into semantic terrain without learned inference.

That principle survives.

The current fallback implementation does not become the main generator by accumulation of responsibilities. Its L0-oriented execution model, emergency behavior, and historical assumptions are not promoted into the production contract.

Production deterministic producers must instead respect explicit Level semantics and the coarse-coverage/proximity-refinement scheduling model established here.

## Alternatives considered

### Generate L0 first and derive every coarser Level afterward

Rejected as the default generation architecture.

It performs unnecessary fine work before providing distant coverage, couples horizon latency to many fine-grained generation and write operations, and makes the renderer wait for detail that is not yet visible.

The path remains valid when L0 already exists for an independent reason, including vanilla ingestion.

### Generate the complete L4 horizon before performing any refinement

Rejected.

Although this minimizes time to coarse coverage in isolation, it can leave coarse terrain next to highly detailed vanilla terrain and create an unacceptable visible handoff.

Coarse coverage and refinement must proceed concurrently.

### Always prioritize the nearest terrain first

Rejected.

Pure proximity ordering can spend excessive computation refining a small inner region while large parts of the visible horizon remain absent.

Near-player refinement is urgent, but it must compete with continued coarse coverage rather than completely displacing it.

### Require learned generation at every Level

Rejected.

ADR 0003 already establishes that exact and deterministic scaffolds should be considered before full learned approximation. Learned computation should exist only where the Worldgen Partition and runtime evidence justify it.

### Treat deterministic reconstruction only as an emergency fallback

Rejected.

A deterministic producer may be the preferred production implementation for a responsibility or Level when it meets the relevant fidelity claims more cheaply than learned or exact alternatives.

### Couple demand, generation, model lifecycle, fallback behavior, and storage in one service

Rejected.

These concerns vary independently. Coupling them causes a new terrain-production strategy to alter unrelated scheduling and lifecycle behavior and encourages mode-specific branching throughout the runtime.

### Commit now to one cross-Level topology

Rejected as premature.

Coarse-first coverage with proximity-driven refinement is compatible with independent Level generation, chained residual refinement, shared deterministic scaffolds, shared semantic latents, or other evidence-backed topologies.

## Consequences

Voxygen's primary runtime unit of work becomes a requested world-space region at an explicit Render Level rather than an implicit demand for L0 terrain.

Direct higher-Level generation and writes are first-class runtime capabilities.

The scheduler must represent at least two distinct forms of urgency:

* **coverage urgency** — missing terrain needed to establish contiguous visible distance;
* **refinement urgency** — existing terrain that is too coarse for its current proximity or visual importance.

The exact priority function, distance bands, visibility policy, starvation rules, and budgets remain downstream scheduling decisions.

A fast L4 horizon is not sufficient evidence of a successful scheduler if visibly coarse terrain persists at the vanilla boundary.

Conversely, a beautifully refined near-player ring is not sufficient if large visible areas remain absent.

`T_horizon` therefore remains a central latency measure, but scheduling evidence must also capture the quality and timing of near-player refinement and the vanilla/LOD handoff.

Worldgen producers must expose explicit target-Level semantics. A producer may support one Level, several Levels, or a reusable responsibility consumed by multiple Levels.

Learned models and ONNX artifacts become consequences of specific learned responsibilities selected by the Worldgen Partition. Runtime configurations with no learned producer are valid.

Model resources must not be initialized merely because terrain generation is active; dependencies belong to the production mechanisms that require them.

Demand policy, terrain production, and storage adaptation should remain separable architectural concerns. Concrete Java interface names and package structure remain implementation decisions owned by code and tests.

Shared deterministic or exact work may be cached and reused across Levels when profitable. This decision does not require duplicate computation merely to preserve target-Level-native generation.

The current fallback remains useful as an emergency path and as historical evidence that deterministic reconstruction is viable. It is not the architectural template for runtime ordering.

## Guiding principle

> **Generate no more detail than distance requires, but no less detail than proximity requires.**

Equivalently:

> **Do not generate detail merely to manufacture distance. Generate the Level the renderer needs, while refining the terrain the player is approaching.**

## When to reconsider

Reconsider if evidence shows that direct coarse generation produces unacceptable refinement discontinuities that cannot be addressed by the selected refinement topology.

Reconsider if a shared finer computation is demonstrably cheaper end-to-end than direct coarse generation while still meeting both horizon-latency and near-player-refinement requirements.

Reconsider if the renderer or storage backend no longer supports independent higher-Level representations.

Reconsider if runtime measurements show that the distinction between coverage urgency and refinement urgency does not materially improve either visible-horizon latency or handoff quality.
