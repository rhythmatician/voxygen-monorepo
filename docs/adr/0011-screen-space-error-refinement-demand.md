# 0011 — Refinement demand is selected by screen-space error, not per-level radii

Date: 2026-08-24
Status: Accepted (amended 2026-08-26)

## Amendment (2026-08-26): shipped policy is a fixed-projection approximation

The implementation does not consume runtime projection values. `DefaultEndRefinement`
uses configured constants (`endRefinementFocalPx` = 1000, `endRefinementSubDivPx` = 64,
`endRefinementRenderDistanceBlocks` = 8192), so the descent test reduces to a fixed
per-level distance threshold:

```
dist < size * focalPx / subDivisionPx
```

With production defaults this is ~4000 blocks for a 256-block L3 node, ~2000 for L2,
~1000 for L1, ~500 for L0. Window size, framebuffer dimensions, FOV, camera
orientation, frustum culling, and Hi-Z occlusion have no effect on selection.

This is a deliberate simplification, not the full viewport-dependent policy described
in the original Decision below:

- **Kept from Voxy's shape**: one fixed coarsest-level ring; per-node descent ordered
  near-first; closest-point AABB distance; XZ-cylindrical coverage culling.
- **Intentionally omitted**: projected-area-vs-viewport-area normalization, frustum
  culling, Hi-Z occlusion. The CPU selector feeds a deterministic producer, not a
  render-list builder, so over-selection near the threshold is safe and budgeted.
- **Consequence**: acceptance evidence should interpret thresholds as fixed distances
  per level, not as screen-space measurements. Passing actual client projection
  parameters into the selector remains the future work that would realize the
  original policy below.

The original Context, Decision, Alternatives, and Consequences are retained as the
historical rationale. Where they conflict with this amendment, the amendment is
authoritative for the shipped implementation.

## Context

ADR 0004 established coarse-first coverage with proximity-driven refinement but left the refinement-demand policy ("which regions at which Levels are needed now") as a downstream decision. The End slice Stage 1 used a single fixed 11×11 L4 ring. Expanding to L1–L4 (Stage 2) forced the question: do we assign a fixed generation radius per Level, or something else?

We surveyed how Voxy (our LOD store vendor, vendored reference source) solves this. Voxy uses **no per-level radii**: exactly one fixed radius exists — a circular ring of L4 root nodes (`RenderDistanceTracker`/`RingTracker`, `sectionRenderDistance` = 16 × 512-block cells). Everything L0–L3 is demanded by per-frame octree descent from those roots using **projected screen-space area** of each node's AABB against a threshold (`subDivisionSize² / viewportArea`), after frustum and Hi-Z occlusion culling. A node that should refine but has no children keeps rendering its own coarse mesh while pushing a request; work drains through a priority queue ordered near-first with throughput-scaled budgets.

## Decision

Voxygen's refinement demand for the End slice follows the same shape, CPU-side:

1. **One fixed ring, coarsest level only.** The existing fixed player-centred ring remains, serving L4 coverage. No per-Level radii are introduced.
2. **Refinement demand is computed by screen-space error descent.** From each covered L4 region, child node AABBs are tested: if a child's projected screen-space area exceeds a threshold, a refinement request for that child is enqueued at the next-finer Level. This recurses L4→L3→L2→L1. The test runs on player movement/session ticks on the CPU — no GPU traversal machinery.
3. **Coarsest-available fallback.** A refinement request never invalidates or blocks its coarser parent; consumers read whatever Level exists. Holes are impossible by construction.
4. **Budgeted, deduplicated enqueue.** Refinement requests flow through the existing `ShadowRouterJobQueue` (dedup/backpressure) with near-first ordering and a bounded emission rate per selection pass, so the outer ring stays affordable.

The screen-space threshold and budget constants start as named constants owned by code/tests, tunable by evidence from fly-around acceptance.

## Considered options

- **Fixed radii per Level** (e.g., L3 within 8 sections, L2 within 4, …): rejected — it hard-codes a view-distance assumption into generation, produces square-ring artifacts, and re-derives badly what screen-space error computes naturally (a voxel's visual relevance depends on distance *and* size *and* viewport).
- **Port Voxy's GPU traversal shader**: rejected — we need demand selection for a deterministic producer, not render-list building; the CPU-side criterion captures the same geometry at our scale.
- **Distance-only thresholds without viewport**: rejected — couples selection to an invented "viewing distance" rather than the actual runtime Level-selection geometry (see CONTEXT.md, Training vs Acceptance Observables).

## Consequences

- The End slice's Stage 2 producer family stays model-free and shared across Levels; only the demand selector changes.
- Selection stability relies on request deduplication and budget scaling (Voxy has no explicit hysteresis); if boundary thrash appears in fly-around evidence, explicit refine/coarsen hysteresis is the first remedy — not radius reintroduction.
- Y handling mirrors Voxy: L4 roots cover full-height columns, distance tests are XZ-cylindrical, vertical detail emerges from 3D descent bounds.
