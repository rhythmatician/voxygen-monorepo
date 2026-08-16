# 0001 — Wayfinder task executor is orthogonal to purpose (A1)

Date: 2026-08-16
Status: Accepted

## Context

`wayfinder:task` had lost meaning: it was the only Wayfinder type allowed through Sandcastle, but its body says “HITL or AFK” while dispatch required a triple-signal (`wayfinder:task` + `agent:implement` + `Execution is carried into this map`) to treat it as AFK. Everything is now under the single map #22, so `wayfinder:task` ≈ “part of map” is tautological, and `wayfinder:research` was blocked as HITL while the Wayfinder skill defines it as AFK (research subagent).

Factory-boundary decision #60 asks to keep purpose/lifecycle ⊥ executor/authorization ⊥ environment/resources independent and to avoid new executor classes. Label sprawl risk if we split `wayfinder:task` into `hitl-task`/`afk-task`.

## Decision

Keep the 4-type skill taxonomy and make executor orthogonal via labels:

* `wayfinder:research` — **AFK only**, Wayfinder research subagent, never `agent:implement`. Factory v0 no longer blocks it — it is simply not a Sandcastle dispatch target.
* `wayfinder:prototype`, `wayfinder:grilling` — **HITL only**, never `agent:implement`, forbidden in Sandcastle.
* `wayfinder:task` — **purpose** (“does work to unblock a decision”), executor orthogonal:
  * **HITL Task** = `wayfinder:task` without `agent:implement` (Wayfinder, checklist to human)
  * **AFK Task** = `wayfinder:task` + `agent:implement` + map Notes signal + tracer-bullet contract (Sandcastle)

No new `hitl-task`/`afk-task` labels. `agent:implement` remains the sole AFK authorization signal (fail-closed via tracer contract).

## Alternatives considered

* Replace `wayfinder:task` with `wayfinder:hitl-task` + `wayfinder:afk-task`: couples purpose and executor, diverges from skill, splits history and prefix matches.
* Add a second `exec:afk`/`exec:hitl` namespace: redundant with `agent:implement` which already is the AFK gate; would require dual maintenance.

## Consequences

* Dispatch must allow `wayfinder:research` (remove from FORBIDDEN) but still not dispatch it via Sandcastle — it has no `agent:implement` so it remains ineligible; Sandcastle simply stops misclassifying it as HITL.
* `wayfinder:task` without `agent:implement` is a valid HITL task, not an error — dispatch ineligibility reason changes to “missing agent:implement” (already the case), not “forbidden type”.
* Existing open `wayfinder:task` #25/#61/#64 must be given an executor label to be unambiguous (done separately).
* CONTEXT.md holds the canonical glossary (Wayfinder Task, HITL Task, AFK Task, Research Ticket).

## When to reconsider

If AFK research needs Sandcastle dispatch (e.g., heavy compute beyond subagent), or if `wayfinder:task` HITL tasks need machine-enforced checklist validation — then introduce an explicit HITL label beyond `¬agent:implement`.
