# Documentation Authority & Traceability Index

> One retrieval surface for repository prose. States authority order and each retained
> document's traceability role. Pointers only — content lives at the target.
> Policy basis: `docs/agents/documentation.md`. Admission enforced by `.ci/docs-policy.mts` (R-02).

## Authority order

1. **Code, tests, contracts, configuration** — current behavior and mechanics.
2. **GitHub Issues / PRs** — requirements and work state.
3. **`CONTEXT.md`** — canonical domain language.
4. **`docs/adr/`** — architectural rationale (accepted ADRs).
5. **`GLOSSARY.md`** — cross-system term disambiguation (defers to `CONTEXT.md` on conflict).
6. **`docs/reference/upstream/`, `docs/external/`** — version-bound grounding for external systems.
7. **Historical evidence** — retained historical artifacts and git history.

## Traceability roles

### Requirement

* GitHub Issues (`rhythmatician/voxygen-monorepo`) — authoritative requirement and work state; not committed docs.

### Architecture / specification

* `CONTEXT.md` — domain language only.
* `docs/adr/0001-wayfinder-task-executor-orthogonal.md`
* `docs/adr/0002-drop-router6-conditioning.md`
* `docs/adr/0003-correct-distant-terrain.md`
* `docs/adr/0004-coarse-first-proximal-refinement.md`
* `docs/adr/0005-factory-authority-and-worker-skills.md`
* `docs/adr/0006-wayfinder-sandcastle-lifecycle-boundary.md` (superseded by 0007)
* `docs/adr/0007-sandcastle-common-afk-substrate.md`
* `docs/adr/0008-factory-iteration-is-one-tested-production-state-machine.md`
* `docs/adr/0009-factory-review-is-an-independent-evidence-gate.md`
* `docs/adr/0010-preserve-upstream-wayfinder-triage-and-sandcastle-label-semantics.md`
* `docs/adr/0011-screen-space-error-refinement-demand.md` (amended: shipped policy is a fixed-projection approximation)
* `docs/adr/0012-respect-voxy-child-existence-semantics.md`

### Implementation

* Executable artifacts only — code, types, tests, schemas/contracts, configuration/build definitions. No prose inventory.

### Verification

* Tests (`java/src/test`, `python/voxel_tree/tests`, `*.test.mts`) and CI policy gates (`.ci/checks.json`, `.ci/docs-policy.mts` R-02).

### Grounding / reference

* `docs/reference/upstream/README.md` — scope rules for version-bound upstream references.
* `docs/reference/upstream/VOXY-FORMAT.md` — Voxy 0.2.11-alpha on-disk/in-memory format (jar SHA-256 pinned).
* `docs/reference/upstream/voxy-0.2.11-alpha-storage-and-lod-seams.md` — Voxy 0.2.11-alpha storage/LOD seams.
* `docs/reference/upstream/minecraft-1.21.11-worldgen-seams.md` — Minecraft 1.21.11 worldgen seams.
* `docs/reference/upstream/minecraft-1.21.11-terrain-signal-lattices.md` — 1.21.11 signal lattices (executable mirror in `voxel_tree.contracts.terrain_signals`).
* `docs/external/l1-availability-contract.md` — WorldNoiseAccess availability contract (wayfinder:research, version-bound).
* `docs/external/minecraft-1.21.11-worldgen-dag-overworld-nether-end.md` — 1.21.11 worldgen DAG per dimension.
* `docs/external/port-vanilla-batch-subtree-sharing.md` — subtree-sharing research for Layer-2 batch port.
* `external/` — read-only mirrored upstream sources (`voxy`, `minecraft-src`, `fabric-api`, `ogn`).

### Historical evidence

* `docs/external/distant-horizons-v2.3-external.md` — retained historical artifact; see its own header for date/scope/successor.
* `docs/adr/*` — ADRs are historical decision records; a superseded ADR is marked superseded with a successor link, not rewritten.

### Future optionality

* `docs/FUTURES.md` — registry of concrete future directions; not current work; consulted selectively by Wayfinder/preserve-futures.

## Control plane (not product documentation)

* `AGENTS.md`, `docs/agents/*`, `.muse/skills/*`, `.sandcastle/*` — factory control-plane artifacts; human-approved per `docs/agents/documentation.md` §G. Indexed here for retrieval only; they do not carry product truth.
