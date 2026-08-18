# Documentation Policy — Factory Control Plane

> Repository prose must not become a second source of truth for facts the repository itself can express executably.
> Code explains mechanics. Documentation explains meaning.

## A. Executable artifacts own current reality

Authoritative source for current behavior, structure, interfaces, file locations, contracts, configuration, supported states, and implementation status is, in order as applicable:

* code and types
* tests
* schemas/contracts
* configuration/build definitions
* generated artifacts derived from those sources

Documentation **MUST NOT** restate those facts merely to make the codebase easier for an agent to understand. If the code needs a large Markdown explanation to be understandable, improve the code: naming, types, interfaces, module boundaries, tests, or package structure.

Prohibited as standalone durable docs (unless they qualify under F):

```
IMPLEMENTATION_SUMMARY.md
ARCHITECTURE_SUMMARY.md
DELIVERABLES.md
PROJECT_OUTLINE.md
TODO.md
CURRENT_STATUS.md
INTEGRATION_CHECKLIST.md
```

## B. CONTEXT.md owns domain language — and only domain language

`CONTEXT.md` answers:

* What does `SectionPos` mean in Voxygen?
* What does `Level` mean?
* What conceptual distinction exists between a semantic volume and Voxy storage?
* Which term is canonical, and which synonyms should agents avoid?

It **MUST NOT** contain: implementation status, file inventories, class inventories, TODOs, plans, implementation instructions, “currently implemented by X”, “planned”, “complete”, detailed algorithms readable from code.

`CONTEXT.md` should be totally devoid of implementation details. It is a glossary and nothing else.

## C. ADRs own architectural rationale

An ADR preserves what code cannot tell a future reader: problem, alternatives considered, why one won, trade-off accepted, when to reconsider.

Only create an ADR when all three hold:

1. hard to reverse
2. surprising without context
3. result of a genuine trade-off

ADRs are historical decision records, not living descriptions. If superseded, mark superseded and point to replacement; do not continuously rewrite old ADRs.

## D. Navigation documentation is allowed, but must remain thin

README/agent-navigation may answer: What is this repo/package? Where is authoritative implementation for X? What command gets me started? Where do I go next?

It **MUST** point to authoritative material instead of reproducing it.

Good:

```
Runtime generation lives under java/.../voxy/.
Model contracts live under python/voxel_tree/contracts/.
Canonical terminology is in CONTEXT.md.
Architectural decisions are in docs/adr/.
```

Bad:

```
LodGenerationService calls A, then B, then C...
Phase 2 is complete and Phase 3 will add...
```

## E. Work state belongs in GitHub

These **MUST NOT** become committed docs: implementation plans, TODO lists, milestones, current status, workstream status, investigations in progress, handoffs, completion reports, deliverables lists, acceptance checklists tracking unfinished work, “Phase N” tracking, planned implementation descriptions.

Those belong in GitHub Issues and PRs. Issue contains spec/work state, PR/commits contain implementation history. Closing the issue resolves state. Do not preserve another manually synchronized Markdown copy.

## F. Durable external research is a narrow exception

May be committed only if explicitly grounded: exact upstream version/commit/hash, evidence or source locations, clear statement it describes that external revision, no claim it describes current Voxygen architecture, preferably executable tests validating claims.

`VOXY-FORMAT.md` is the model: Voxy version, JAR SHA-256, upstream commit, specific source evidence.

## G. Agent instructions and skills are control-plane artifacts

```
AGENTS.md
docs/agents/*
.muse/skills/*
.sandcastle/*prompt.md
.sandcastle/CODING_STANDARDS.md
```

These control the factory and require independent human approval. Ordinary product documentation and tests do not become privileged merely because they are important; protected roots are explicit in `.ci/checks.json` and enforced through base-branch CODEOWNERS review.

## H. No duplication

Every fact gets one authoritative home. If something already has a source of truth, link to it. Do not copy the same explanation into README, CONTEXT.md, AGENTS.md, a skill, and an implementation guide.

## I. The admission test

Before committing prose, answer: *Why can this information not live in code, tests, configuration, a contract, GitHub, or an existing canonical document?*

| Information | Home |
|---|---|
| Current behavior/mechanics | Code/tests/contracts/config |
| Domain language | `CONTEXT.md` |
| Architectural rationale | ADR |
| Navigation/onboarding | Thin README/navigation |
| Documentation authority/traceability index | `docs/INDEX.md` |
| Concrete future directions | `docs/FUTURES.md` |
| Current/planned work | GitHub Issue/PR |
| External expensive-to-reconstruct evidence | Version-pinned reference |
| Agent behavior | Factory control plane |
| Anything else | Do not commit it |
