# 0005 — Factory authority and worker skills are separate layers

Date: 2026-08-18
Status: Accepted

## Context

Voxygen's software factory uses AI agents to implement and review repository work.

Early factory iterations placed too much procedural intelligence in large role prompts. Prompts attempted to describe both:

1. the factory protocol — what issue is owned, what operations are allowed, what outcome the factory expects; and
2. engineering methodology — how to diagnose bugs, practice TDD, implement work, and review changes.

This creates duplication with the engineering skills already used interactively by maintainers, makes prompts difficult to audit, and allows important engineering discipline to drift between human-driven and factory-driven sessions.

The opposite design is also unsafe: skills and prompts are executed by language models and therefore cannot be authoritative for factory safety. A prompt asking an agent not to merge, not to claim another issue, or to emit a valid reviewer verdict is not equivalent to enforcing those rules mechanically.

The factory therefore needs a boundary between deterministic orchestration authority and agent methodology.

## Decision

The Voxygen software factory has three distinct layers:

1. **Factory control plane**
2. **Role bootstrap prompt**
3. **Worker skill workflow**

Their authorities are distinct, with responsibilities deliberately separated.

### Factory control plane is authoritative

Deterministic factory code owns all safety-critical orchestration semantics, including:

* issue eligibility and claiming;
* branch and worktree provenance;
* allowed state transitions;
* retry and loop limits;
* distinction between semantic outcomes and factory/infrastructure failures;
* merge authorization;
* qualification-run limits;
* GitHub read/write capability;
* planner fallback;
* stop/no-advance behavior;
* preservation of useful work and evidence after failure.

Agent output is untrusted input to this state machine.

A skill or prompt cannot authorize a state transition merely by asserting that it occurred.

For example:

* a valid semantic review rejection may permit another implementation iteration;
* a missing or malformed review result is a factory error;
* a review skill cannot authorize a merge;
* an implementation agent cannot claim another issue;
* a prompt instruction not to mutate GitHub is not a substitute for withholding GitHub write capability.

### Role bootstrap prompts provide ephemeral context and protocol

Factory-specific prompts should remain small.

They provide information that varies by run, such as:

* issue/specification;
* role;
* branch;
* factory base SHA;
* repository root;
* relevant acceptance constraints;
* available capabilities;
* forbidden operations;
* required handoff/result schema.

They may direct the worker to invoke an appropriate skill.

They should not reproduce general engineering methodology already owned by a skill.

The intended shape is:

```text
factory control plane
        │
        ▼
role bootstrap prompt
        │
        ▼
skill workflow
        │
        ▼
agent
```

### Skills own engineering methodology

Skills are the standard operating procedures for AI workers.

The preferred workflows are:

#### New implementation or specified behavior

Start the implementation worker through `/implement`.

`/implement` may use `/tdd` at appropriate seams, run focused verification, perform its own `/code-review`, and commit its work.

The implementation worker's self-review is advisory quality control. It is not the factory's authoritative acceptance gate.

#### Reported bug or regression

Use `/diagnosing-bugs` to first construct a tight red-capable reproduction of the actual defect.

Once the correct behavior and seam are established, convert the minimized reproduction into a regression test and apply the fix.

Do not replace diagnosis with speculative production edits.

#### Test-driven implementation

Use `/tdd` for new behavior and for regression slices once the relevant behavior and seam are known.

The required discipline is behavior-first and vertical:

```text
one behavior
→ one failing test
→ one minimal implementation
→ green
→ next behavior
```

Testing pain is treated as architecture feedback rather than justification for implementation-coupled tests.

#### Authoritative factory review

The independent reviewer uses `/code-review` as its engineering review methodology.

The review skill's findings are then classified through a separate factory result protocol.

The factory accepts only explicitly valid semantic outcomes such as:

* `APPROVE`
* `CHANGES_REQUESTED`

Failure to produce a valid result is a factory/infrastructure failure, not a semantic rejection.

The factory parser and state machine remain authoritative regardless of what prose the reviewer emits.

### Skills do not define permissions

No skill owns:

* GitHub mutation authority;
* issue claim authority;
* merge authority;
* factory continuation authority;
* retry limits;
* qualification limits.

Those capabilities are granted or withheld by the factory.

This permits the same engineering methodology to operate safely under different execution modes, including read-only qualification.

### Prefer existing skills over duplicated prompts

Do not copy the contents of `/implement`, `/tdd`, `/diagnosing-bugs`, `/code-review`, or similar reusable methodologies into `.sandcastle/*prompt.md`.

If reusable Voxygen-specific methodology emerges that genuinely does not belong to an existing skill, it may become a small repository-owned skill.

Do not create such wrapper skills merely to avoid composing an existing skill with a role prompt.

### Skill selection must be explicit where required

Skills marked `disable-model-invocation: true` cannot be assumed to activate spontaneously.

Where such a skill is part of the worker contract, the factory or role bootstrap must invoke it explicitly.

### Skill metadata must match supported work

Factory routing must not depend on misleading skill metadata.

## Alternatives considered

### Put the entire worker procedure in custom Sandcastle prompts

Rejected.

This duplicates versioned skills, makes prompts large and difficult to audit, and creates methodology drift between interactive and factory-driven engineering.

### Allow skills to own factory state transitions

Rejected.

Skills execute through probabilistic agents. Safety-critical state, permissions, claiming, merging, and continuation must be deterministic.

### Use only skills with no role-specific prompt

Rejected.

Skills describe reusable methodology but do not contain the ephemeral issue, branch, provenance, permissions, and machine-result protocol required for a particular factory run.

### Create Voxygen-specific wrapper skills for every factory role

Rejected as premature.

Existing skills plus small role bootstrap prompts provide the required composition. A custom skill should exist only when durable Voxygen-specific methodology emerges.

## Consequences

Sandcastle prompts become smaller and primarily carry context and protocol.

Engineering methodology can improve independently in the skills repository without rewriting factory orchestration prompts.

The factory can test orchestration independently of agent methodology by substituting deterministic fake workers.

Worker sessions can use the same engineering practices maintainers use interactively.

Review findings and factory verdicts become separate concepts.

An implementation worker's `/code-review` is self-review only; the factory reviewer remains the independent acceptance gate.

Skill versions and changes are control-plane dependencies and should receive the same deliberate review expected of other agent-control artifacts.

Factory trust depends primarily on executable state-machine contracts, not prompt compliance.

## Guiding principle

> **Skills teach workers how to work. Prompts tell them what job they have. Factory code decides what is allowed and what happened.**

## When to reconsider

Reconsider if agent platforms provide mechanically enforced skill contracts or typed capability systems that can safely replace portions of the factory control plane.

Reconsider if repeated role-specific methodology cannot be expressed cleanly by composing existing skills with small bootstrap prompts.

Reconsider if independent evidence shows that implementer self-review provides no useful signal relative to its cost.
