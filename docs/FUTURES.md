# Futures Registry — Voxygen

> **Not work state. Not a backlog. Not auto-loaded.**
> Ordinary coding agents do not load this file. Wayfinder and preserve-futures consult it selectively when architectural optionality matters. Current/actionable work stays in GitHub Issues (per `docs/agents/documentation.md:E`). In-scope uncertainty for a current effort belongs in that Wayfinder map's `Not yet specified` fog, not here.

## Purpose

Low-resolution registry of **known, concrete future directions** that would materially affect architecture if pursued. Keeps future constraints durable and retrievable without polluting present-day context or letting future plans masquerade as current requirements.

Hierarchy:

```text
docs/FUTURES.md (this file)
  low-res registry, NOT routinely loaded

  ↓ optional link for substantial futures

GitHub "future anchor" issue
  durable richer context/evidence, NOT executable (no ready-for-agent / agent:implement)

  ↓ when decided to pursue

fresh Wayfinder Map
  destination + decisions + fog → child issues

  ↓ as questions sharpen

Wayfinder child / ordinary delivery issues (current work)
```

Checkpoints that gate architectural pressure are scheduled by Wayfinder **before** downstream fan-out and assessed by `/preserve-futures` — not after every ticket.

## How to use

* Add a row only when a future is **concrete, materially affects architecture, and is worth preserving beyond a checkpoint** (`preserve-futures` candidate criteria). Otherwise it stays only in a checkpoint's `Future observation` output.
* Keep each description **low-resolution** (1-2 lines) — the anchor issue holds richer discussion.
* Anchors must not be executable and must not imply `ready-for-agent`. When a future becomes current, start a **fresh Wayfinder map** whose destination is that future; do not flip the anchor into an implementation ticket.
* Wayfinder/preserve-futures read this selectively; workers do not.

## Registry

| Future | Status | Anchor | Why it matters / evidence |
|---|---|---|---|
| Distant Horizons LOD alternative | candidate (not registered) | — | External LOD via `DistantHorizons.sqlite` (LZ4), modular `distant-horizons-core`, API churn 2.1→4.0 (GitLab `distant-horizons-team/distant-horizons` at v2.3.0b). Voxy is current LOD store (`docs/reference/upstream/VOXY-FORMAT.md`, `docs/external/distant-horizons-v2.3-external.md`). No forcing requirement today. Candidate per `java/.github/copilot-instructions/distant-horizons-integration.md` sanitized to `docs/external/`. |
| Single-pipeline authoritative terrain (maybe) | speculative — not registered, depends on #85 | — | Far-future fork where Voxygen produces **authoritative chunks** or exact generation is computed once and consumed by both renderer and vanilla. Today we preserve the distinction: *terrain for distant rendering* vs *authoritative vanilla chunk* the player eventually reaches (`CONTEXT.md`: `Correct Distant Terrain`, `Authoritative Chunk`, `Render L0`). If viable, would deduplicate to a single highly efficient pipeline (major arch fork, blocked on #85 vanilla worldgen value ownership). Retain as optionality, not requirement. |
| Structures-enabled worldgen | candidate (not registered) | — | Future support for `generate_structures=true`. Current scope uses `generate_structures=false`; enabling would affect structure starts/references, terrain adaptation/beardification, spatial halos, and distant-vs-authoritative structure interactions. Not a commitment now. |
| HotswapAgent for iterative Java development | deferred — evaluated 2026-08-17, not for L4 tracer | #125 grilling checkpoint | Considered `HotswapProjects/HotswapAgent` (`-javaagent`) to hot-redefine `GenerationSession`/`TerrainCandidate`/`RealVoxyVolumeWriter` without full `gradlew build` + restart. Deferred for L4 vertical slice: AFK Sandcastle workers do cold worktree builds so saves 0 min there, and Mixin/Loom + Voxy `Mapper` reflection seam risks “hotswapped works, cold fails” divergence vs deterministic scaffold proof. Revisit as bounded `research: hotswap vs DCEVM vs Fabric dev reload` only after tracer proves contiguous End L4 horizon and HITL iteration is measured slow; fix missing JDK in `.sandcastle/Dockerfile` first. |

## Candidate future anchors (unregistered)

*None currently registered.* When a checkpoint identifies a concrete future worth preserving, propose it here via a `preserve-futures` candidate — Wayfinder/human decides whether to create the durable GitHub anchor and link it above.

## See also

* `docs/agents/documentation.md` — work state in GitHub, external research (`F`), control-plane (`G`)
* `CONTEXT.md` — domain language only
* `docs/adr/` — architectural rationale (hard-to-reverse, surprising, traded-off)
* Wayfinder map — current destination/decisions/fog (selective context, not this file)
