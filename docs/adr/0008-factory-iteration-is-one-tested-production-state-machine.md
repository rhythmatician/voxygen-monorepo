# 0008 — A factory iteration is one tested production state machine

Date: 2026-08-22
Status: Accepted
Extends: ADR 0005 — Factory authority and worker skills are separate layers

## Context

ADR 0005 makes deterministic factory code authoritative for issue state, retries, failure classification, continuation, and merge authorization. The first parallel research rollout exposed a further boundary that was not mechanically enforced: tests could prove the desired behavior against injected workers, source-text assertions, or a coordinator used only by tests while the production entry point retained different control flow.

That distinction is especially dangerous after an iteration launches asynchronous work. Research, implementation, review, publication, and reconciliation may complete in different orders, while an ordinary `break`, `continue`, process signal, or failure path can leave claims or background work unresolved. A capable reviewer may notice such defects, but factory correctness must not depend on a model reconstructing the whole control flow from a large script.

## Decision

**Each claimed factory iteration has one callable production state machine, and behavioral tests exercise that exact state machine.**

The production command-line entry point is a thin shell around the state machine. It may perform startup, configuration, and presentation, but it does not maintain a second implementation of post-claim lifecycle behavior.

The state machine owns:

* concurrent launch of authorized worker profiles;
* ordering between worker completion, review, publication, integration, and outer-loop progression;
* classification of semantic outcomes versus `FACTORY_ERROR`;
* settlement or cancellation of every task group launched by the iteration;
* the typed decision to continue, stop, retry, or submit completed work.

Once an iteration launches asynchronous work, every path out of that iteration passes through one epilogue that settles or cancels the launched task group. Raw control-flow exits must not bypass that boundary.

Behavioral tests import the same production coordinator used by the command-line entry point. A helper used only by tests is not evidence for production behavior. Source-text assertions and static shape checks may supplement behavioral proof, but they cannot be the sole proof of lifecycle behavior.

External boundaries such as GitHub, sandboxes, agents, clocks, signals, filesystems, and process execution are injected into the production state machine for deterministic scenario testing. The test matrix covers success, partial failure, interruption, retry, publication failure, and ordering-sensitive combinations through that production seam.

Agent-runtime modes are represented by project-owned typed adapters that make incompatible contracts difficult or impossible to express. In particular, a one-result structured-output run and an iterative completion-promise run are distinct operations rather than combinations of loosely related options.

Where correctness depends on the installed Sandcastle runtime, Docker image, toolchain, or another external execution contract, deterministic state-machine tests are supplemented by a bounded exact-runtime canary. Neither a model statement nor a simulated interpretation of an external API is authoritative evidence.

## Considered options

### Keep orchestration in a monolithic top-level script

Rejected. It makes lifecycle behavior depend on nonlocal control flow and makes every later `break`, `continue`, or failure path part of the concurrency protocol.

### Test a simplified or duplicated coordinator

Rejected. It can demonstrate that the intended algorithm works while production continues to execute different code.

### Rely on reviewer intelligence to find lifecycle mistakes

Rejected as a safety boundary. Review remains useful, but the factory must mechanically expose and test its state transitions.

### Introduce a generic workflow engine

Rejected as unnecessary. The factory needs one explicit, typed iteration state machine, not a general-purpose orchestration platform.

## Consequences

Factory refactors must preserve one production owner for iteration lifecycle behavior.

New worker profiles integrate through the same state machine rather than adding parallel top-level control flow.

Scenario tests become higher-value than tests that merely inspect source shape or replay the desired algorithm with unrelated promises.

Some dependencies must be injected and some top-level code must move behind typed interfaces. That additional structure is accepted because it makes production behavior observable and prevents the test harness from becoming a second implementation.

A green suite earns confidence only for paths reached through the production state machine and for external contracts exercised by their bounded canaries.

## When to reconsider

Reconsider if the language or execution platform provides mechanically enforced structured concurrency and typed workflow semantics that can replace part of the project-owned state machine without reintroducing a separate test implementation.
