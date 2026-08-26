# 0012 — Adapt top-down refinement to Voxy's child-existence topology

Date: 2026-08-26
Status: Accepted

## Context

Voxygen writes coarse terrain before fine terrain, while Voxy's renderer interprets each `WorldSection.nonEmptyChildren` bit as the existence of a separate next-finer `WorldSection`. Voxel occupancy inside the current section and child-section existence are different facts. Confusing them makes Voxy descend from valid coarse geometry into children that do not exist, producing visible void.

## Decision

Voxygen will adapt its top-down refinement to Voxy's topology contract rather than reinterpret that contract or take ownership of a Voxy fork. Therefore:

- voxel occupancy must never be encoded as `nonEmptyChildren`;
- a generated section with no known next-finer sections is a leaf with `nonEmptyChildren = 0`, regardless of its own voxel contents;
- a nonzero child bit may state only that the corresponding next-finer section exists; and
- voxel-geometry changes and child-topology changes remain distinct facts in our integration.

## Alternatives and trade-offs

Replacing or forking Voxy remains technically possible, but it would make Voxygen responsible for the storage and rendering contract that the mod deliberately delegates to Voxy. This ADR does not choose publication timing, atomicity, transaction ownership, native/generated coordination, render-readiness criteria, lifecycle hooks, scheduling, prioritization, or refinement thresholds.
