# ADR 0017: Sandcastle Is a Replaceable Execution Resource; Voxygen Owns Factory Policy

**Status:** Approved
**Date:** 2026-08-30
**Extends:** ADR 0005 — Factory authority and worker skills are separate layers
**Partially supersedes:** ADR 0007 — Sandcastle is the common AFK execution substrate; Wayfinder owns purpose and frontier
**Related:** ADR 0008, ADR 0009, ADR 0010; Wayfinder map #190

## Context

Voxygen uses `@ai-hero/sandcastle` as the execution substrate for AFK coding agents.

The term **Sandcastle** has become overloaded in the repository. It has been used to mean both:

1. the external Sandcastle package and Voxygen's thin fork of that package; and
2. Voxygen's project-owned software factory under `.sandcastle/*`, which uses the package to execute workers.

That ambiguity has caused responsibility drift.

Generic execution concerns such as sandbox lifecycle, worktree safety, provider invocation, process cancellation, and output capture have sometimes been discussed alongside Voxygen-specific concerns such as:

- GitHub issue discovery and claims;
- Wayfinder and tracker semantics;
- research versus implementation routing;
- qualification policy;
- retry and convergence policy;
- candidate proof;
- review and repair;
- reconciliation after interrupted attempts;
- PR publication and merge authorization.

Those are not one system.

Recent work on the `rhythmatician/sandcastle` fork made the distinction important. Some defects were genuine package-level defects: unsafe or insufficiently proven worktree cleanup, provider-specific Muse output handling, prompt transport, and lifecycle behavior. Other reliability requirements belong only to Voxygen's factory.

Without an explicit seam, every factory defect risks becoming another fork modification, while every package limitation risks being worked around independently in Voxygen. That would create two overlapping orchestration systems and make future upstream synchronization progressively harder.

ADR 0005 already establishes that deterministic Voxygen factory code owns safety-critical orchestration policy. ADR 0008 establishes a project-owned production factory state machine. ADR 0009 establishes project-owned review and evidence semantics. This ADR makes the package boundary explicit.

## Terminology

This ADR uses these terms deliberately.

### Sandcastle package

The external package:

```text
@ai-hero/sandcastle
```

plus only the minimum Voxygen-maintained fork delta required for:

* generic package correctness;
* provider adapters not yet supported upstream;
* temporary backports of upstreamable fixes.

### Voxygen factory

The project-owned control plane in:

```text
voxygen-monorepo/.sandcastle/*
```

including its production state machines, tracker adapters, evidence rules, role contracts, prompts, policy, and GitHub integration.

### Agent provider

A replaceable execution backend invoked through Sandcastle, for example:

```text
Muse Code
Codex
Claude Code
other future providers
```

A provider is not a factory authority.

## Decision

The architecture is:

```text
Voxygen factory control plane
  voxygen-monorepo/.sandcastle/*
        │
        │ project-owned typed execution requests
        ▼
Sandcastle execution package
  @ai-hero/sandcastle
  + minimal generic/provider fork delta
        │
        ▼
Agent provider
  Muse Code / Codex / Claude / ...
```

The governing rule is:

> **Sandcastle owns the correctness of executing one requested agent job and the resources it creates. Voxygen owns why that job exists, what authority it has, what its result means, and what happens next.**

Sandcastle returns execution facts.

Voxygen interprets those facts and makes factory decisions.

## Decision 1 — Sandcastle owns generic execution mechanics

The Sandcastle package owns the generic mechanics necessary to execute an agent request safely.

These responsibilities include:

### Sandbox lifecycle

Sandcastle owns:

* creation and destruction of the requested sandbox;
* sandbox-provider integration;
* sandbox filesystem/mount correctness;
* isolation guarantees advertised by the selected provider;
* generic lifecycle cleanup of resources it created.

A successful package result must not leave Sandcastle-owned resources in an unknown state without explicitly reporting that condition.

### Worktree and local Git lifecycle

Where Sandcastle creates or manages a worktree as part of an execution request, Sandcastle owns:

* safe worktree creation;
* correct branch/worktree association;
* preservation of unrelated caller and sibling worktrees;
* non-destructive handling of ambiguous ownership;
* cleanup only of resources it can legitimately identify as its own;
* preservation/reporting of work that cannot safely be removed;
* correct local synchronization behavior for the package's declared branch strategy;
* fresh verification of destructive lifecycle postconditions where needed for package correctness.

Sandcastle must never require Voxygen policy in order to avoid deleting or corrupting unrelated Git state.

### Agent process lifecycle

Sandcastle owns mechanisms for:

* starting the provider process;
* cancellation;
* idle/process/completion timeout machinery;
* child-process cleanup within the execution boundary;
* reporting process exit and timeout outcomes.

Voxygen chooses policy values such as timeout duration and retry eligibility.

### Provider adapters

A Sandcastle provider adapter owns translation between the common execution interface and a provider such as Muse Code.

Provider-specific package responsibilities may include:

* command invocation;
* prompt transport;
* escaping/argv constraints;
* structured or streaming output parsing;
* reconstruction of assistant output;
* session capture/resume;
* completion-signal detection across provider output forms;
* provider-specific process cleanup.

These are valid fork responsibilities when upstream does not support the provider.

### Execution results

Sandcastle owns truthful reporting of execution facts available at its boundary, such as:

* sandbox/run/session identity;
* worktree and branch;
* base/head or commit information available from the requested branch strategy;
* commits created;
* stdout / structured output;
* process exit;
* timeout/cancellation outcome;
* preserved worktree/resource state;
* cleanup result.

Sandcastle must not present unknown or unverified execution state as known success.

## Decision 2 — Voxygen owns factory policy and authority

Voxygen's `.sandcastle/*` control plane owns every project-specific decision about work selection, authority, interpretation, and continuation.

This includes:

### Tracker and Wayfinder semantics

Voxygen owns:

* issue discovery;
* Wayfinder ticket types;
* triage labels;
* `agent:*` command/transient-state semantics;
* native `blocked_by` interpretation;
* issue eligibility;
* claims and assignees;
* stale-claim reconciliation;
* issue comments, closure, and tracker mutations.

The Sandcastle package has no knowledge of these concepts.

### Work profiles

Voxygen owns the distinction among:

* implementation;
* research;
* review;
* fixer/repair;
* merger/publication;
* qualification;
* future worker profiles.

Sandcastle merely executes the requested agent job.

### Agent-run contracts

Voxygen owns the project-level typed operations established by #192, including the semantic distinction among:

```text
structured once
unstructured once
iterative completion
```

Raw Sandcastle options must remain behind project-owned adapters where exposing them directly would allow invalid Voxygen role combinations.

Sandcastle provides the primitives. Voxygen defines which primitive is valid for each role.

### Authorization and capabilities

Voxygen owns:

* whether a worker is allowed to run;
* which repository or branch it is allowed to affect;
* GitHub read/write authority;
* whether remote Git publication is allowed;
* qualification restrictions;
* merge authority;
* continuation authority.

A prompt or provider cannot grant these permissions.

### Retry and convergence policy

Voxygen owns:

* iteration budgets;
* retry eligibility;
* retry classifications;
* semantic failure versus `FACTORY_ERROR`;
* convergence breakers;
* explicit reauthorization after interrupted work;
* whether another worker may run after a result.

Sandcastle implements mechanisms such as timeout and cancellation but does not decide policy.

### Candidate identity and proof

Voxygen owns:

* frozen factory/base identity;
* candidate SHA identity;
* required verification commands;
* evidence obligations;
* environment identity requirements;
* interpretation of structural versus behavioral proof;
* readiness for review;
* promotion evidence.

Sandcastle may report commits and execution facts but does not decide that a candidate is complete or proven.

### Review semantics

Voxygen owns:

* authoritative read-only review;
* reviewer/fixer separation;
* fresh re-review;
* finding identity;
* prior-finding resolution;
* approval/rejection semantics;
* review depth by risk;
* methodology provenance;
* whether a reviewed SHA may proceed.

Sandcastle may execute reviewer or fixer agents. It does not define the review protocol.

### Publication and merge

Voxygen owns:

* `git push` authorization for factory-produced candidates;
* PR creation;
* issue closure;
* candidate publication;
* merge eligibility;
* protected-root human approval.

Remote publication must not occur as an implicit side effect of generic Sandcastle cleanup or qualification.

## Decision 3 — Package operations produce facts, never project verdicts

The conceptual package boundary is:

```text
ExecutionRequest
  repository/worktree context
  provider
  prompt
  environment/capabilities
  branch strategy
  timeout/cancellation configuration
        │
        ▼
     Sandcastle
        │
        ▼
ExecutionReceipt
  execution/session identity
  branch/worktree facts
  commit facts
  output
  process outcome
  timeout/cancel outcome
  preserved-resource state
  cleanup outcome
```

The Sandcastle package must not be the authority that produces project-level conclusions such as:

```text
issue is complete
candidate satisfies Voxygen
review passed
retry this issue
close this issue
publish this candidate
continue the factory
merge this PR
```

Those conclusions are computed by the Voxygen factory from execution facts plus project-owned policy.

## Decision 4 — Shared concerns have separate mechanism and policy owners

Some concerns cross the boundary. They are divided explicitly.

### Worktree preservation

Sandcastle:

> This worktree was created/claimed by this execution. It cannot be safely removed. Here is the preserved resource and cleanup outcome.

Voxygen:

> This was an interrupted implementation attempt. Preserve provenance, reconcile tracker state, stop progression, and require the appropriate reauthorization.

### Timeout

Sandcastle:

> This execution exceeded the configured timeout and was cancelled according to the execution contract.

Voxygen:

> This role receives this timeout budget, and this timeout is classified as this factory outcome.

### Completion signal

Sandcastle:

> The configured signal was or was not present in provider output and the process terminated in this state.

Voxygen:

> `<promise>COMPLETE</promise>` is the contract for this particular iterative worker role.

### Structured output

Sandcastle:

> Extract/return the requested structured output according to the package API.

Voxygen:

> Research must run structured-once and the returned value must satisfy the Voxygen `ResearchResult` contract; failure means `FACTORY_ERROR`.

### Commit production

Sandcastle:

> These commits were created by the execution under the selected branch strategy.

Voxygen:

> These commits constitute a candidate for issue N at SHA X and may or may not proceed to proof, review, publication, or merge.

## Decision 5 — Fork changes must pass a responsibility test

The `rhythmatician/sandcastle` fork is not a second Voxygen control plane.

A durable fork change is permitted only when it belongs to at least one of these classes:

### `provider-adapter`

Required to support a provider through Sandcastle's generic execution contract.

Examples:

* Muse prompt transport;
* Muse JSON/stream parsing;
* Muse session handling;
* provider-specific completion-output reconstruction.

### `generic-safety`

A package-level correctness or lifecycle invariant applicable to Sandcastle independently of Voxygen.

Examples:

* do not delete an unrelated worktree;
* fail closed when destructive cleanup ownership is unknown;
* do not claim successful cleanup when postconditions are unknown;
* correctly reap package-owned child processes.

Generic safety fixes should normally be proposed upstream when practical.

### `temporary-upstream-backport`

A fix already accepted upstream, or clearly intended for upstream, temporarily carried because Voxygen needs it before the next upstream release.

No fork category named or equivalent to:

```text
voxygen-policy
factory-policy
wayfinder-policy
review-policy
tracker-policy
```

is permitted.

If a proposed fork change requires understanding Voxygen issue labels, Wayfinder, qualification semantics, candidate evidence, review policy, or merge policy, it belongs in `voxygen-monorepo/.sandcastle/*`.

## Decision 6 — Use the upstreamability test when ownership is unclear

When ownership is uncertain, ask:

> **Would this still be a valid Sandcastle bug or feature if Voxygen did not exist and the caller used Claude Code on an unrelated repository?**

If yes, the package/fork is a plausible owner.

If no, Voxygen is the default owner.

Examples:

| Concern                                          | Owner                       |
| ------------------------------------------------ | --------------------------- |
| Sandcastle deletes another worktree              | Sandcastle                  |
| Sandcastle cannot faithfully capture Muse output | Sandcastle provider adapter |
| Muse prompt exceeds argv                         | Sandcastle provider adapter |
| Sandcastle leaks its child process               | Sandcastle                  |
| `wayfinder:research` eligibility                 | Voxygen                     |
| `agent:implement` command consumption            | Voxygen                     |
| qualification semantics                          | Voxygen                     |
| candidate proof ledger                           | Voxygen                     |
| exact-SHA review evidence                        | Voxygen                     |
| reviewer → fixer → fresh review                  | Voxygen                     |
| issue reconciliation                             | Voxygen                     |
| PR publication policy                            | Voxygen                     |

## Decision 7 — Voxygen depends on an exact Sandcastle revision

Voxygen must not treat the mutable `rhythmatician/sandcastle/main` branch as its runtime contract.

The installed/fetched Sandcastle dependency used by the factory must resolve to an exact reviewed revision through the repository's normal dependency mechanism.

Factory evidence that depends on Sandcastle behavior must record that exact runtime identity.

Changing the Sandcastle revision is a control-plane dependency change and receives appropriate verification.

Where correctness depends on package behavior, use:

* project-owned adapter tests;
* bounded runtime-contract tests against the exact installed revision;
* focused qualification/canary evidence where simulation is insufficient.

## Decision 8 — Keep raw package semantics behind the Voxygen adapter

Production Voxygen roles should not spread raw Sandcastle configuration throughout `.sandcastle/*`.

The project-owned execution adapter established by #192 remains the semantic boundary between Voxygen roles and the package.

Its purpose is not to reimplement Sandcastle. It narrows package capabilities into combinations that are valid for Voxygen.

Conceptually:

```text
Voxygen role
   │
   ▼
project-owned typed execution contract
   │
   ▼
Sandcastle package request
```

Package upgrades or provider differences should normally be absorbed at this boundary rather than leaking into tracker, review, or factory-policy modules.

## Decision 9 — Package defects interrupt factory work; they do not migrate factory policy into the fork

If Voxygen development exposes a genuine package defect:

1. stop the affected factory execution path;
2. reproduce the defect at the smallest generic Sandcastle seam;
3. file/fix it in the fork and, where appropriate, upstream;
4. pin a reviewed fixed revision;
5. return to the Voxygen factory issue.

Do not solve the defect by teaching Sandcastle Voxygen policy.

Conversely, do not patch around a generic package safety violation independently at every Voxygen call site when the package itself owns the invariant.

## Relationship to earlier ADRs

### ADR 0005

Retained and strengthened.

ADR 0005 remains authoritative that deterministic Voxygen factory code owns safety-critical orchestration and agent output is untrusted input.

This ADR adds the missing distinction between the Voxygen factory and the external execution package beneath it.

### ADR 0007

Partially superseded.

The retained intent is:

* Sandcastle is the common execution substrate used for AFK workers;
* implementation and Research may both use that substrate;
* workers receive isolated execution environments;
* tracker writes remain host-side.

The phrase:

> Sandcastle owns AFK execution generally

must no longer be interpreted as giving the **Sandcastle package** ownership of Voxygen's research lifecycle, tracker semantics, publication, failure policy, review, or factory continuation.

Those responsibilities belong to the Voxygen factory.

### ADR 0008

Retained.

The production Voxygen factory state machine remains project-owned and behaviorally tested through its production seam. Sandcastle is an injected execution dependency of that state machine.

### ADR 0009

Retained.

Review policy, evidence identity, reviewer independence, repair convergence, and human protected-root approval remain Voxygen responsibilities. Sandcastle only executes the requested reviewer/fixer jobs.

### ADR 0010

Retained.

Wayfinder type, triage state, Sandcastle command labels, native claims, and blocker semantics are Voxygen tracker/control-plane policy. They are not Sandcastle package concepts.

## Consequences

### Positive

* The fork has a bounded reason to exist.
* Generic Sandcastle bugs can be upstreamed instead of becoming permanent Voxygen architecture.
* Voxygen policy remains testable without depending on provider internals.
* Provider replacement does not alter factory semantics.
* Package upgrades have one principal integration seam.
* #190 can harden the Voxygen factory without silently becoming a Sandcastle rewrite.
* Future reviewers have an explicit rule for deciding which repository owns a defect.
* The project can converge back toward upstream Sandcastle over time.

### Costs

* Voxygen must maintain a deliberate typed integration layer rather than calling arbitrary Sandcastle APIs throughout the control plane.
* Some defects require coordination across two repositories.
* Exact Sandcastle runtime identity becomes part of factory provenance.
* Generic package fixes may require both a local fork patch and an upstream contribution before the fork delta can later be removed.

These costs are preferable to maintaining two overlapping factory frameworks.

## Required reconciliation

After accepting this ADR:

1. Reconcile #190 so each active/future route item clearly belongs to the Voxygen control plane or is identified as a separate Sandcastle package prerequisite.
2. Treat #211, #197, #202, #198, and #199 as Voxygen factory responsibilities; none should require new fork policy.
3. Treat `rhythmatician/sandcastle#6` as a package-level lifecycle-safety prerequisite.
4. Audit the current `rhythmatician/sandcastle` fork delta and classify each retained commit as:

   * `provider-adapter`;
   * `generic-safety`;
   * `temporary-upstream-backport`;
   * move to Voxygen;
   * or delete.
5. Pin Voxygen to one exact reviewed Sandcastle revision before resuming real factory execution.
6. Update ADR 0007's header/supersession note to point to ADR 0017 for the package-versus-factory responsibility clarification.
7. Keep project-owned raw-Sandcastle call sites behind the #192 execution-contract seam.
8. Prefer upstream PRs for generic fork fixes when practical.

## Guardrail

Before adding behavior to the Sandcastle fork, answer all three questions:

1. What generic execution/package invariant or provider contract owns this behavior?
2. Would the behavior still be required for a non-Voxygen Sandcastle caller?
3. Why can this not be implemented as Voxygen factory policy above the package?

If those questions do not have clear answers, the change belongs in `voxygen-monorepo/.sandcastle/*`.

## Guiding principle

> **Sandcastle executes the job. Voxygen decides the job, the authority, the meaning of the result, and the next state.**
