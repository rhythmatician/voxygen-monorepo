# 0006 — Wayfinder plans work; Sandcastle executes implementation work

Date: 2026-08-18
Status: Superseded by ADR 0007 — Sandcastle is the common AFK execution substrate
Supersedes: ADR 0001 — Wayfinder task executor is orthogonal to purpose (A1)

## Context

Wayfinder and Sandcastle serve different parts of the development process.

Wayfinder exists to navigate large, uncertain efforts with a human in the loop. It identifies the destination, exposes unresolved decisions, records those decisions, manages fog of war, and progressively produces a route toward work that can be executed.

Sandcastle exists to autonomously execute sufficiently specified implementation work.

Previous factory design attempted to make execution authorization orthogonal to Wayfinder ticket type. ADR 0001 therefore allowed selected `wayfinder:task` tickets to become Sandcastle targets when accompanied by `agent:implement` and additional signals.

That preserved label orthogonality, but blurred a more important lifecycle boundary: a Wayfinder ticket is part of planning and decision resolution, while a Sandcastle ticket should already contain sufficiently resolved implementation work.

The `/wayfinder` skill is especially unsuitable as a Sandcastle worker methodology because Wayfinder owns human-in-the-loop planning behavior and tracker mutations such as claiming decision tickets, recording resolutions, closing tickets, creating newly surfaced tickets, and rewiring the map.

The software factory should consume the output of Wayfinder, not execute Wayfinder itself.

## Decision

**Wayfinder is outside the Sandcastle software factory.**

Wayfinder is the human-governed planning process by which uncertain work becomes sufficiently specified implementation work.

Sandcastle executes implementation tickets produced or clarified by that process.

The lifecycle is:

```text
large / uncertain objective
        │
        ▼
     Wayfinder
   HITL planning
 decisions / research
  fog resolution
        │
        ▼
sufficiently specified
implementation ticket
        │
        ▼
    Sandcastle
 autonomous execution
```

### Sandcastle does not invoke `/wayfinder`

No Sandcastle implementation, reviewer, planner, retry, or qualification worker invokes `/wayfinder`.

Wayfinder sessions remain human-driven planning sessions outside the factory.

Wayfinder may use its own research or other supporting skills as defined by the Wayfinder workflow. Those workers are part of the planning process, not Sandcastle dispatch.

### Wayfinder labels do not authorize Sandcastle execution

A `wayfinder:*` label identifies a planning/decision artifact.

It does not make that issue a Sandcastle job.

Sandcastle eligibility requires an independently specified implementation-work authorization contract.

`agent:implement`, or its eventual successor, expresses authorization for autonomous implementation work. It does not convert an unresolved Wayfinder decision into executable work.

If Wayfinder determines that implementation work is needed, that work is handed off as a separate implementation ticket with sufficient acceptance criteria for Sandcastle. It is not a `wayfinder:*` decision ticket, though it may link to the planning decision that produced it.

### Sandcastle's planner is not Wayfinder

Sandcastle's planner/selector has a narrow execution concern:

> Given already-valid executable tickets, which eligible job should run?

It does not:

* establish project destinations;
* resolve architectural uncertainty;
* conduct HITL grilling;
* manage fog of war;
* create a decision map;
* decide what implementation ought to exist;
* substitute itself for human planning.

Where deterministic selection is possible, Sandcastle should prefer deterministic eligibility and ordering logic over generative planning.

An LLM planner, if retained, is advisory within that bounded selection problem.

### Uncertainty flows back out of the factory

If an implementation worker discovers that its ticket requires a material unresolved product or architectural decision, it must not silently become a Wayfinder session.

The implementation attempt should surface the blocked/underspecified condition through the factory's normal outcome mechanism.

The unresolved question returns to the human planning process.

Sandcastle does not autonomously expand its mandate from implementation into roadmap or architectural decision-making.

### Wayfinder remains the source of large-scale planning

Wayfinder remains appropriate for:

* uncertain multi-session efforts;
* architectural decision discovery;
* HITL grilling;
* prototypes used to make decisions;
* planning research;
* identifying and sequencing decision dependencies;
* converting fog into sufficiently specified executable work.

Sandcastle begins after those decisions are sufficiently resolved.

## Alternatives considered

### Allow AFK Wayfinder tasks to run through Sandcastle

Rejected and supersedes ADR 0001.

Although executor authorization can be modeled independently from purpose labels, doing so blurs the planning/execution lifecycle and allows the factory to enter a workflow whose semantics include decision-making and tracker mutation.

### Use `/wayfinder` as Sandcastle's planner

Rejected.

Wayfinder plans an uncertain journey. Sandcastle selection chooses among already-executable jobs. They operate at different abstraction levels.

### Let implementers invoke `/wayfinder` when tickets are unclear

Rejected.

This permits an autonomous implementation worker to silently expand its authority from execution into planning. Underspecified work must return to the HITL planning boundary.

### Encode the entire roadmap directly into Sandcastle

Rejected.

Sandcastle is an execution system, not the repository's architectural planner or work-state authority.

## Consequences

ADR 0001 is superseded.

Dispatch rules should no longer contain special logic whose purpose is to make Wayfinder decision-ticket types executable through Sandcastle.

Wayfinder remains free to create or motivate implementation tickets that Sandcastle later executes.

The factory becomes simpler: it operates on already-authorized executable work instead of understanding the internal lifecycle of the Wayfinder map.

The Sandcastle planner can remain narrow and deterministic.

Factory qualification no longer needs to exercise Wayfinder behavior.

Wayfinder's tracker-write behavior does not need to be simulated inside Sandcastle's read-only qualification mode.

If Sandcastle encounters genuine unresolved planning uncertainty, it stops or reports the issue rather than performing autonomous Wayfinding.

## Guiding principle

> **Wayfinder decides what should become executable. Sandcastle executes what has already become executable.**

Or, more simply:

> **Wayfinder makes the tickets. Sandcastle knocks them out.**

## When to reconsider

Reconsider if a future autonomous planning system develops a separately specified permission, review, and safety model sufficient to perform Wayfinder-class decisions without HITL oversight.

Such a system should still be treated as a planning layer, not casually embedded inside Sandcastle's implementation worker.
