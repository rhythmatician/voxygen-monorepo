# Tracer-bullet ticket contract — AFK implementation

Every `agent:implement` issue must be a **tracer bullet**: a bounded vertical slice that an AFK agent can complete in one fresh session without inventing scope, gated conceptually (not by brittle headings) before claim. `ready-for-agent` remains triage/readiness; `agent:implement` is execution authorization. The validator fails closed if any concept is missing.

## The 7 concepts

An AFK-ready ticket must convey each concept. Recommended canonical headings are shown, but the validator is alias-tolerant and accepts headings, synonyms, or a structured schema. The authoritative alias patterns live in `.sandcastle/tracer-contract.mts`; this table summarizes them for authors.

| # | Concept (id) | Canonical heading | Aliases |
|---|--------------|-------------------|---------|
| 1 | Bounded observable outcome (`bounded-outcome`) | Scope / Goal | `bounded observable outcome`, `observable outcome`, `outcome`, `goal`, `objective`, `problem`, `scope` |
| 2 | No unresolved design decision (`no-unresolved-design`) | Decision / Design | `no unresolved design decision`, `no unresolved`, `no open questions`, `design is decided`, `decided` |
| 3 | Explicit acceptance criteria (`acceptance-criteria`) | Acceptance criteria / Done when | `acceptance criteria`, `acceptance`, `done when` |
| 4 | Explicit verification path (`verification-path`) | Verification | `verification`, `verification path`, `verify`, `how to verify`, `validation` |
| 5 | Dependencies / blockers (`dependencies-blockers`) | Dependencies | `dependencies`, `dependency`, `blocker`, `blocked by` |
| 6 | Small enough for one session (`small-for-one-session`) | Scope (sizing) | `small enough`, `one implementation session`, `one fresh implementation session`, `one session`, `single session`, `sized for one` |
| 7 | Prefer vertical / tracer-bullet (`vertical-tracer-bullet`) | Scope (shape) | `vertical`, `tracer bullet`, `tracer-bullet`, `tracer`, `end-to-end`, `slice` |

A structured front-matter / fenced JSON/YAML schema is also accepted when it carries the same concept keys (e.g. `boundedOutcome`, `acceptanceCriteria`, `verification`, `dependencies`, `sizing`, `shape`), because the alias patterns match key names.

## Anti-patterns

- "Build the whole system/feature" without a slice — violates #1, #6, #7.
- Acceptance described only as "works correctly" with no checkable criteria — violates #3, #4.
- "Decide the design during implementation" or `TBD` left open — violates #2.
- No mention of blockers or assuming they will be discovered during work — violates #5.

## Validation

Implemented in `.sandcastle/tracer-contract.mts` (alias-tolerant detection) and enforced in `.sandcastle/dispatch.mts` fail-closed on `agent:implement`. `ready-for-agent` alone is not an execution gate.
