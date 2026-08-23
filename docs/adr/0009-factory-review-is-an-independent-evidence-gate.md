# 0009 — Factory review is an independent evidence gate; repair is a separate phase

Date: 2026-08-22
Status: Accepted
Extends: ADR 0005 — Factory authority and worker skills are separate layers

## Context

The factory originally used one reviewer context to inspect the issue contract, tests, implementation, standards, and architecture. That reviewer was also allowed to edit the branch, commit refinements, run tests, and then approve its own result.

The parallel research rollout showed that a candidate can satisfy its explicit acceptance checklist and pass its suite while still containing a harness gap: tests may exercise a duplicate implementation, omit a lifecycle ordering, or simulate an external runtime incorrectly. A stronger model may discover such defects more often, but Voxygen is a hobby project that deliberately uses affordable workers. Reliability must therefore come primarily from role separation, deterministic evidence, and executable constraints rather than exceptional model capability.

## Decision

**Authoritative review is read-only and evidence-gated. Any repair is performed by a separate worker and followed by a fresh review.**

The factory records the candidate branch, exact candidate SHA, and worktree state before review. An authoritative reviewer receives no permission to modify the candidate. A reviewer mutation or invalid result is a `FACTORY_ERROR`, not an approval or semantic rejection.

Blocking findings use a structured identity and preserve at least:

* review axis;
* invariant or acceptance criterion;
* failure mode;
* concrete evidence;
* required proof for resolution.

A separate fixer receives the structured findings. After the fixer commits changes and deterministic verification is rerun, a fresh reviewer context classifies every prior blocking finding as resolved, unresolved, or superseded and may report new findings. A reviewer never approves its own edits.

Review depth is selected deterministically from the candidate's risk surface.

For protected factory, CI, build, agent-protocol, lifecycle, concurrency, credential, worktree, publication, and other control-plane changes, review uses isolated axes:

1. **Spec** — whether the candidate implements the authoritative issue contract without omission or scope drift.
2. **Verification** — whether the evidence reaches the production seam, proves important failure behavior, covers state and ordering, and leaves no material harness gap.
3. **Health Regression** — whether the candidate creates duplicate authority, dead or test-only architecture, hidden lifecycle requirements, increased change amplification, or materially weaker verification.

The axes do not see one another's conclusions before they finish. Their outputs remain distinguishable; one axis cannot compensate for a blocking failure in another.

Routine changes with strong executable checks may use one bounded combined reviewer to control cost. Protected control-plane changes pay for the independent axes because their failure can compromise all later autonomous work.

Reviewers receive a deterministic candidate-evidence manifest bound to the exact SHA. The manifest distinguishes facts from gaps and includes, where applicable:

* commands and machine-readable results;
* changed production and test surfaces;
* production consumers of new modules;
* test reachability to changed production seams;
* source-text-only assertions identified as structural rather than behavioral proof;
* representative failure or fault-sensitivity evidence;
* runtime, image, toolchain, and prompt or methodology identity.

Review methodology is an explicit versioned input to the run. Skills and prompts may teach the reviewer how to reason, but their identity is pinned and recorded rather than left to mutable installation state or spontaneous model invocation.

Models and agent harnesses are replaceable execution resources. No provider is part of the safety authority. Provider changes are evaluated against the same evidence and escaped-defect corpus; they do not alter the factory's invariants or merge rules.

Protected control-plane candidates retain independent human approval. Local AFK review is intended to make that human review uneventful, not to remove it.

## Considered options

### Let the reviewer fix and approve the same branch

Rejected. It weakens independence, obscures which evidence applies to which SHA, and encourages the reviewer to validate its own repair.

### Use one general reviewer for every concern

Rejected for control-plane work. A single context tends to prioritize explicit acceptance criteria and green tests while underweighting test representativeness, ordering, and architectural duplication.

### Use the strongest available model for every review

Rejected as the primary reliability strategy. It raises recurring cost and still leaves safety dependent on probabilistic insight rather than enforceable evidence.

### Replace Muse Code with another harness immediately

Rejected without comparative evidence. A different harness or model may improve defect recall, but it does not remove the need for production-seam tests, independent roles, and deterministic evidence.

### Run the full independent review profile on every change

Rejected as disproportionate for a hobby project. Review depth is adaptive so routine work remains affordable while factory changes receive stronger scrutiny.

## Consequences

The factory gains an explicit reviewer-to-fixer-to-fresh-review loop rather than a reviewer self-edit loop.

Review receipts become attributable to one exact candidate SHA and one pinned methodology identity.

Control-plane work costs additional Spark calls and wall time, but those costs are concentrated where an escaped defect can waste many later sessions or corrupt factory state.

Affordable models can remain useful because the harness supplies the evidence, separates concerns, and mechanically enforces what should not depend on model judgment.

The same escaped-defect corpus can compare Muse Spark, Codex CLI, or future providers without changing the architectural contract.

Human approval remains the final authority for protected roots even after local reviewers approve.

## When to reconsider

Reconsider if the agent platform provides mechanically enforced read-only review, typed finding resolution, exact-SHA evidence binding, and independent reviewer contexts as native primitives.

Reconsider the adaptive profile thresholds when measured escaped-defect recall, false blocking, cost, or latency show that a different allocation provides better reliability for the project.
