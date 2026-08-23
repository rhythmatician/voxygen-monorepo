# 0010 — Preserve upstream Wayfinder, triage, and Sandcastle label semantics

Date: 2026-08-22
Status: Accepted
Partially supersedes:

- ADR 0001 — Wayfinder task executor is orthogonal to purpose (A1)
- ADR 0007 — Sandcastle is the common AFK execution substrate; Wayfinder owns purpose and frontier

Relates to:

- ADR 0005 — Factory authority and worker skills are separate layers
- ADR 0008 — A factory iteration is one tested production state machine

## Context

Voxygen borrows Wayfinder and Sandcastle from Matt Pocock rather than designing a planning and agent-execution vocabulary from scratch. The project should preserve those conventions unless a demonstrated Voxygen requirement forces a narrow adaptation.

The relevant upstream conventions are distinct but cooperating:

- Wayfinder labels describe the kind of planning ticket: `wayfinder:map`, `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, or `wayfinder:task`.
- Triage labels describe durable workability state: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, or `wontfix`.
- Sandcastle `agent:*` labels are commands or transient execution state. In the upstream workflows, `agent:implement` and `agent:explore` trigger a run and are consumed when the run starts; `agent:in-progress` and `agent:blocked` expose machine state.

This comparison is grounded in the project forks at the time of this decision:

- `rhythmatician/mattpocock-skills` revision `c98fd438f9ea76b8510fad09d282148ed76bf645`, especially `skills/engineering/wayfinder/SKILL.md` and `skills/engineering/triage/SKILL.md`.
- `rhythmatician/sandcastle` revision `eeae29e42e2b03430acee9cd564ee7bbe24bf782`, especially `.github/workflows/agent-implement.yml` and `.github/workflows/agent-explore.yml`.

Voxygen accumulated additional signals while adapting the systems:

- `agent:research` duplicated the AFK meaning already fixed by `wayfinder:research`.
- `agent:implement` became durable queue membership and sole AFK authorization instead of a one-shot Sandcastle command.
- `ready-for-agent` was documented as non-authoritative even though the upstream triage convention defines it as fully specified and ready for AFK work.
- An exact map-note sentence, `Execution is carried into this map`, became a hidden machine authorization bit.
- `wayfinder:preserve-futures` extended the four-type Wayfinder taxonomy even though Wayfinder specifies a gating futures checkpoint as an AFK `Task` child issue.

The first production research rollout exposed the operational cost. Three open, unblocked, unassigned `wayfinder:research` tickets were invisible to Sandcastle until the separately invented `agent:research` label was created and applied. The duplicate labels did not provide useful independence; they created invalid intermediate states and rollout ceremony.

Voxygen still needs a small adapter because the borrowed systems do not provide a complete off-the-shelf integration. Wayfinder expects Research tickets to run AFK in parallel, while the generic upstream Sandcastle implementation workflow is standalone-issue oriented and refuses sub-issues. The adapter should bridge that gap without inventing a new label ontology.

## Decision

**Voxygen preserves the upstream meanings of Wayfinder types, triage states, and Sandcastle commands. Local labels are added only when they express a genuinely independent dimension that the upstream vocabulary cannot represent.**

### Label roles are distinct

| Role | Labels | Meaning |
| --- | --- | --- |
| Wayfinder ticket type | `wayfinder:map`, `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, `wayfinder:task` | The ticket's role in the planning map |
| Triage state | `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix` | Durable readiness and whether AFK or human execution is appropriate |
| Sandcastle command | `agent:implement`, `agent:review`, optionally `agent:explore` | A one-shot request to start a workflow |
| Sandcastle transient state | `agent:in-progress`, `agent:blocked` | Visible state of a machine attempt, not product dependency state |

A label must not silently take over another role. In particular:

- `ready-for-agent` means fully specified and AFK-capable.
- `agent:implement` means start the implementation workflow now.
- `wayfinder:task` means the work unblocks a decision; it says nothing by itself about AFK versus HITL execution.

### Fixed Wayfinder types keep their upstream executor semantics

Wayfinder already defines three ticket types whose executor is fixed:

| Wayfinder type | Executor |
| --- | --- |
| `wayfinder:research` | AFK |
| `wayfinder:prototype` | HITL |
| `wayfinder:grilling` | HITL |

These tickets do not require a second label that restates the same executor. New Research tickets should not require `ready-for-agent`; new Prototype and Grilling tickets should not require `ready-for-human`. Matching redundant labels may be tolerated during migration, but they are not authoritative and must not be required by dispatch.

Wayfinder-created child tickets are planning artifacts rather than ordinary untriaged intake. The triage state machine remains authoritative for ordinary issues and for Wayfinder Tasks, whose executor is not fixed by type.

### `wayfinder:research` is the research dispatch signal

A Research ticket is eligible for Voxygen's Sandcastle research profile when all of the following hold:

- the issue is open;
- it has `wayfinder:research`;
- it is unassigned and has no active machine claim;
- it has no `agent:blocked` state requiring intervention;
- the native `blocked_by` count is known and zero;
- its research question satisfies the research-profile input contract.

No `agent:research` label is required. `agent:research` is retired.

Creating a `wayfinder:research` child is the Wayfinder act that declares the question sharp and AFKable. The Voxygen adapter may discover and run all eligible Research children concurrently, matching Wayfinder's instruction to fire Research subagents in parallel. Research keeps its distinct lifecycle: one structured result, optional isolated commits, host-side publication, parent-map pointer, close on success, and no implementation review, merger, PR, or auto-merge path.

The generic upstream `agent:explore` command is not repurposed as a second Wayfinder research label. It may remain available for a standalone non-Wayfinder exploration workflow if the repository later needs one.

### `ready-for-agent` and `agent:implement` form a state-and-command pair

An implementation issue at rest is labelled `ready-for-agent` once its brief and acceptance contract are sufficient for AFK execution.

Adding `agent:implement` is the explicit command to launch the Sandcastle implementation lifecycle. Implementation eligibility therefore requires both:

```text
ready-for-agent + agent:implement
```

On successful claim, the factory consumes the command label and exposes transient state:

```text
ready-for-agent + agent:implement
        ↓ claim
ready-for-agent + agent:in-progress
```

The issue remains `ready-for-agent` because readiness is durable. `agent:implement` must be re-added to request a later retry. This follows the upstream Sandcastle convention and prevents `agent:implement` from carrying both readiness and launch semantics.

The implementation profile additionally requires the issue to be open, unassigned, unblocked by native dependencies, free of conflicting machine state, and compliant with Voxygen's implementation/tracer contract.

### Wayfinder Tasks use a genuine pair of independent labels

`wayfinder:task` is the only Wayfinder type whose executor is variable. Its durable classification therefore uses a pair:

```text
wayfinder:task + ready-for-agent
```

for an AFK Task, or:

```text
wayfinder:task + ready-for-human
```

for a HITL Task.

A Task must not carry both readiness states. An AFK Task enters the implementation workflow only when `agent:implement` is added as the one-shot command:

```text
wayfinder:task + ready-for-agent + agent:implement
```

The map's Notes may still say that execution is carried into the map, because that is useful planning context. Sandcastle must not parse an exact sentence from the map or ticket body as an additional authorization bit.

### Futures checkpoints remain Wayfinder Tasks

A gating `/preserve-futures` checkpoint is represented as:

```text
wayfinder:task + ready-for-agent
```

with the checkpoint purpose and `/preserve-futures` invocation named in the issue body. It uses the tracker's normal native blocking relationship to gate downstream work.

`wayfinder:preserve-futures` is retired as a ticket-type label because it is not part of Wayfinder's four-type taxonomy.

### Claims and blockers retain one owner each

- The native assignee is the concurrency claim, matching Wayfinder. An open, unassigned ticket is unclaimed.
- `agent:in-progress` is a transient visible machine state. It supplements the assignee for operational visibility but does not replace the native claim.
- Native `blocked_by` relationships represent product and planning dependencies.
- `agent:blocked` represents a failed or refused machine attempt requiring intervention. It must not substitute for native dependency blocking. Profile-specific `FACTORY_ERROR` handling may release a retryable issue without applying `agent:blocked`.

### Sandcastle core remains generic; the Voxygen adapter owns repository semantics

Sandcastle's library primitives do not need to understand Wayfinder. Voxygen's repository adapter maps tracker state to the appropriate profile:

- eligible `wayfinder:research` children map to the research profile;
- `ready-for-agent + agent:implement` maps to the implementation profile;
- `ready-for-human`, `wayfinder:prototype`, and `wayfinder:grilling` do not map to AFK execution;
- `wayfinder:map` is never executable.

Generic implementation routing does not require a Wayfinder label. It may execute an ordinary issue or an AFK `wayfinder:task` child through the same implementation profile. This is a narrow Voxygen deviation from the upstream standalone-only implementation workflow because this project intentionally allows execution to continue inside selected Wayfinder maps.

A repository-level consistency validator may fail closed on contradictory combinations, but it must not introduce more labels to do so.

### Canonical combinations

| Work item | Durable labels | Launch command |
| --- | --- | --- |
| Wayfinder map | `wayfinder:map` | none |
| Wayfinder Research | `wayfinder:research` | automatic frontier dispatch |
| Wayfinder Prototype | `wayfinder:prototype` | none; HITL |
| Wayfinder Grilling | `wayfinder:grilling` | none; HITL |
| AFK Wayfinder Task | `wayfinder:task` + `ready-for-agent` | add `agent:implement` |
| HITL Wayfinder Task | `wayfinder:task` + `ready-for-human` | none |
| Ordinary AFK implementation issue | `ready-for-agent` | add `agent:implement` |
| Ordinary HITL issue | `ready-for-human` | none |

Contradictory combinations fail closed. At minimum these include:

- more than one `wayfinder:<type>` label on one ticket;
- `wayfinder:research` with `agent:implement`;
- `wayfinder:prototype` or `wayfinder:grilling` with `ready-for-agent` or `agent:implement`;
- `wayfinder:task` with both `ready-for-agent` and `ready-for-human`;
- `agent:implement` without `ready-for-agent`;
- an active command label together with `agent:in-progress`;
- the retired `agent:research` or `wayfinder:preserve-futures` labels on newly created work.

Matching redundant readiness labels on fixed Wayfinder types are non-authoritative migration residue, not dispatch signals. They should be removed opportunistically rather than causing otherwise safe historical tickets to fail.

## Migration

The label-policy implementation and issue migration must land as one protected control-plane change so the tracker and dispatcher do not temporarily disagree.

The migration must:

1. stop creating and requiring `agent:research`;
2. dispatch Research directly from eligible `wayfinder:research` children;
3. delete or deprecate the repository label `agent:research` after open issues are normalized;
4. replace `wayfinder:preserve-futures` with `wayfinder:task + ready-for-agent` and a body-level checkpoint description;
5. remove the machine-readable `Execution is carried into this map` gate while retaining the Notes convention as prose;
6. require `ready-for-agent + agent:implement` for implementation dispatch;
7. consume `agent:implement` when an issue is claimed, following upstream Sandcastle command semantics;
8. classify every open `wayfinder:task` with exactly one of `ready-for-agent` or `ready-for-human`;
9. keep native assignees and `blocked_by` relationships authoritative for claims and dependencies;
10. update dispatch, reconciliation, documentation, label descriptions, and production-state-machine tests together;
11. add behavioral tests proving that irrelevant Wayfinder metadata does not alter ordinary implementation eligibility and that one valid Research result runs once, publishes once, and closes through the production coordinator;
12. run a bounded exact-runtime canary for mixed implementation and Research dispatch, as required by ADR 0008.

## Considered options

### Retain `agent:research` as explicit authorization

Rejected. In this project Research tickets are Wayfinder children and Wayfinder already defines Research as AFK. The second label duplicated the same fact, created missing-label rollout states, and did not enable a useful independent combination.

### Use only `agent:*` routing labels and remove triage readiness

Rejected. This diverges from the borrowed triage convention and makes `agent:implement` carry both durable readiness and the imperative to launch. It also obscures issues that are AFK-ready but intentionally not launched yet.

### Require `ready-for-agent` or `ready-for-human` on every Wayfinder ticket

Rejected. Research, Prototype, and Grilling already fix the executor in the Wayfinder type. Requiring a second label would duplicate those fixed invariants. The pair is useful only for `wayfinder:task`, where executor is genuinely independent.

### Add new `exec:afk` and `exec:hitl` labels

Rejected. They duplicate `ready-for-agent` and `ready-for-human`, expand the state space, and deviate from the upstream vocabulary without adding capability.

### Keep `agent:implement` as durable queue membership

Rejected. Upstream Sandcastle treats it as a workflow trigger and consumes it on transition to `agent:in-progress`. Durable queue membership conflates readiness with launch authorization and makes retries less explicit.

### Let Sandcastle parse Wayfinder map Notes as authorization

Rejected. Natural-language planning notes are not a stable machine protocol. Eligibility must be visible from canonical tracker state and profile-specific body contracts.

## Consequences

The repository returns to a smaller, upstream-aligned vocabulary.

Research tickets become immediately AFKable when Wayfinder creates a sharp, unblocked Research question. There is no separate authorization rollout step and no `wayfinder:research`/`agent:research` drift.

AFK implementation becomes intentionally two-step: triage establishes `ready-for-agent`; adding `agent:implement` launches the workflow. This leaves a visible pool of fully specified work without forcing immediate execution.

Wayfinder Tasks preserve the important executor orthogonality from ADR 0001, but the executor is expressed with the canonical triage pair rather than presence or absence of an implementation command and a hidden map-note string.

The separate research lifecycle, common Sandcastle substrate, isolated workers, host-side tracker writes, structured results, parent-map publication, and `FACTORY_ERROR` semantics from ADR 0007 remain in force. Only its label-authorization model is superseded.

Some historical issues may temporarily carry redundant labels. Dispatch ignores matching redundancy and fails closed only on contradictions while migration completes.

Protected factory changes remain subject to ADR 0008's production-state-machine tests, exact-runtime canary, and human approval requirements.

## When to reconsider

Reconsider the single-label Research rule if Voxygen begins creating substantial research outside Wayfinder, or if a future Wayfinder version allows Research tickets that are not AFK.

Reconsider the state-and-command pair if Sandcastle gains a native durable queue distinct from its workflow-trigger labels.

Reconsider the Voxygen sub-issue adapter if upstream Sandcastle adds first-class Wayfinder child execution with equivalent lifecycle and safety guarantees.

Do not add a new label merely because a workflow implementation needs a convenient branch. First prove that the label represents an independent user-visible state that cannot be derived safely from the existing tracker model.
