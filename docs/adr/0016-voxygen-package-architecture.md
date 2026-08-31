# ADR 0016: Voxygen Package Architecture — Canonical Root, Backend Flatness, and Legacy Shims

**Status:** Approved
**Date:** 2026-08-30  
**Branch:** `refactor/voxygen-package-architecture`  
**Wayfinder map:** #246 (tickets #247, #252, #255) — destination is Voxygen as the canonical mod namespace with a leaf Voxy adapter and a neutral storage seam. This ADR makes the three structural decisions that were implicit in the code motion explicit so they can be ratified as doctrine rather than rediscovered from a test file.

## Context

The working tree previously contained an aborted parallel-package attempt (`com.rhythmatician.lodiffusion.voxy` alongside `com.rhythmatician.voxygen` plus a grab-bag `lodiffusion.voxy` authority). That attempt was reverted. This slice establishes `com.rhythmatician.voxygen` as the single authority before any further placed-feature work (End chorus authority, Worldgen Partition v1 #85).

The migration moved every production type that previously lived in `com.rhythmatician.lodiffusion.voxy` (main source set) into the new hierarchy, deleted the legacy package, and left the Fabric mod id `lodiffusion` unchanged while moving the entrypoint to `com.rhythmatician.voxygen.HelloTerrainMod`.

## Decision 1 — Canonical package root is `com.rhythmatician.voxygen`

**Decision:** The canonical Java root for all Voxygen-owned code is `com.rhythmatician.voxygen`. No new production code is added under `com.rhythmatician.lodiffusion` except the enumerated shims below.

**Consequences:**
- All new packages are created under `com.rhythmatician.voxygen`: `semantic`, `worldgen`, `terrain`, `features/placement+projection+end/nether/overworld`, `generation/session+scheduling+refinement+dimension/{end,nether,overworld}`, `inference/onnx+gpu`, `output` (neutral seam), and `backend/voxy` (leaf adapter only).
- Fabric `fabric.mod.json` entrypoint is `com.rhythmatician.voxygen.HelloTerrainMod`; the shim `com.rhythmatician.lodiffusion.HelloTerrainMod` delegates to it and preserves the old FQN for reflective callers. Mod id remains `lodiffusion`; file names `lodiffusion.accesswidener` and `lodiffusion.mixins.json` are unchanged (they are resource names, not package names).
- **Out of scope — other mods' namespaces:** `com.voxeltree.*` (the DataHarvester support mod, now under `tools/data-harvester` (moved per #271, independent Gradle build at `tools/data-harvester`)) is a separate Fabric mod with its own mod id and no dependency on the Voxygen mod. It is not a legacy Voxygen root and is never swept into the `com.rhythmatician` convergence; its destination is owned by the `tools/` re-home decision (map #246 ticket #256). Likewise `me.cortex.voxy.*` remains governed by ADR 0012's foreign-bridge semantics, not by this ADR.
- The ArchUnit boundary `PackageBoundaryTest` imports `com.rhythmatician.voxygen` and asserts that `features/semantic/worldgen/terrain/inference/generation` do not depend on `backend.voxy`, and that `semantic` does not depend on `backend.voxy`. The test is non-vacuous (`coreCount > 10`).

**Alternatives considered:** Keep `lodiffusion` as the code root and add Voxygen as a subpackage — rejected because it preserves the legacy authority and reintroduces the parallel-package ambiguity.

## Decision 2 — `backend.voxy` is FLAT

**Decision:** `com.rhythmatician.voxygen.backend.voxy` is a **flat leaf** package. It contains the Voxy adapter types (Voxy detection, engine, compat, block mapping, ID maps, topology ownership, traversal patch, world binding, dataset export, node retry, overlay encoding, processing API, canonical maps, snapshot, and `RealVoxyVolumeWriter`) but does **not** split into subpackages `storage`, `mapping`, `lifecycle`, `rendering`, `compat`, etc.

**Rationale:** The Voxy backend is a single external integration point (reflection + Voxy API) owned by one team. Splitting it prematurely would create deep-module boundaries without a proven seam. The flat layout keeps the adapter cohesive and makes the leaf invariant enforceable: no code in `features`, `semantic`, `worldgen`, `terrain`, `inference`, or `generation` may depend on `backend.voxy` (enforced by ArchUnit). If a future need for a sub-boundary emerges (e.g., a distinct storage vs. mapping seam), it will be introduced with a dedicated ADR and a new deep module, not by ad-hoc subpackages.

**Consequences:**
- `PackageBoundaryTest` and `ArchitectureGuardrailsTest` can enforce the leaf invariant with a single package expression `com.rhythmatician.voxygen.backend.voxy..`.
- Future maintainers should not add subpackages under `backend.voxy` without updating this ADR and the ArchUnit rules.

## Decision 3 — Legacy shims (provisional list) and remaining legacy roots

**Decision (provisional):** The only production Java that may remain under the legacy root `com.rhythmatician.lodiffusion` in `src/main/java` is the enumerated shims:

```
LEGACY_SHIMS = {
  com.rhythmatician.lodiffusion.HelloTerrainMod,   // shim delegating to voxygen.HelloTerrainMod
  com.rhythmatician.lodiffusion.Config,            // runtime config (still lodiffusion-namespaced)
  com.rhythmatician.lodiffusion.ModDetection,      // mod detection (still lodiffusion-namespaced)
  com.rhythmatician.lodiffusion.LodiffusionClient, // client entrypoint (still lodiffusion, references Voxy)
  com.rhythmatician.lodiffusion.command.LodiffusionCommand
}
```

plus the resource-owned roots that are not Java packages: `com.rhythmatician.lodiffusion.util`, `world`, `gpu`, `onnx`, `config` (these contain helpers that have not yet been evaluated for Voxygen ownership and are out of scope for this slice).

**Is this list complete or provisional?** **Provisional.** The five entries above are the only types that were intentionally retained as shims during this slice. The broader `lodiffusion` utility/world/gpu/onnx packages still contain production Java that has not been triaged. A follow-up slice will evaluate each of those packages against the ownership map (`semantic`, `worldgen`, `inference`, `generation`, `terrain`, `output`) and either move them or explicitly add them to the shim list with a reason. Until then, the `PackageBoundaryTest` check `noProductionJavaRemainsUnderLegacyLodiffusionExceptShims` is intentionally narrow: it **only** fails if any file remains under `com.rhythmatician.lodiffusion.voxy` (the old authority). It does not yet fail on `util`/`world`/`gpu`/`onnx`.

**Client source set note:** The client source set `src/client/java` still contains three classes under the legacy package:

- `com.rhythmatician.lodiffusion.voxy.LodOverlayClient`
- `com.rhythmatician.lodiffusion.voxy.VoxyDebugState`
- `com.rhythmatician.lodiffusion.voxy.VoxyNativeLodStats`

and the mixin `com.rhythmatician.lodiffusion.voxy.LodOverlayNodeIdQueueProvider`. These were not moved in this slice because they are client-rendering shims that depend on Fabric client APIs and are not part of the `generation`/`backend` seam. They remain under `lodiffusion.voxy` **in the client source set only**. A future slice may move them to `com.rhythmatician.voxygen.client` or `backend.voxy.client` once the client boundary is decided. The `PackageBoundaryTest` does not check the client source set.

**Consequences:**
- Any new production file added under `com.rhythmatician.lodiffusion.voxy` in `src/main/java` will cause `PackageBoundaryTest` to fail.
- Adding a new shim requires updating both this ADR and the `LEGACY_SHIMS` set in `PackageBoundaryTest`.

## Relationship to Wayfinder map #246

This slice implements the code motion required by map #246's destination without committing it to `main`. Tickets #247 (semantic + output seam), #252 (worldgen + generation), and #255 (backend leaf) are represented by the code motion itself; the full old→new location table and the complete access-widening list are review evidence carried in the PR description, not in repository prose.

## References

- Branch: `refactor/voxygen-package-architecture`
- `java/src/test/java/com/rhythmatician/voxygen/arch/PackageBoundaryTest.java` — enforces decisions 1–3 in code.
- `ArchitectureGuardrailsTest` enforces semantic/output seam purity; `PackageBoundaryTest` remains the
  enforcement home for ADR 0016 Decisions 1–3 and repository/package ownership rules.
- Wayfinder map #246, tickets #247, #252, #255.
- Access-widening policy: no further widening to make a test compile; co-locate the test or leave the member package-private and note the gap. Any new widening requires an ADR amendment before merge.
