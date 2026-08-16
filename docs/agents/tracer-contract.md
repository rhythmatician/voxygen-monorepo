# Tracer-bullet ticket contract — AFK implementation

Every `agent:implement` issue must be a **tracer bullet**: a bounded vertical slice that an AFK agent can complete in one fresh session without inventing scope, gated conceptually (not by brittle headings) before claim. `ready-for-agent` remains triage/readiness; `agent:implement` is execution authorization. The validator fails closed if any concept is missing.

## The 7 concepts

An AFK-ready ticket must convey each concept. Recommended canonical headings are shown, but the validator is alias-tolerant and accepts headings, synonyms, or a structured schema.

| # | Concept | Canonical heading | Aliases accepted by validator |
|---|---------|-------------------|-------------------------------|
| 1 | Bounded observable outcome | Scope / Goal | `bounded observable outcome`, `observable outcome`, `outcome`, `goal`, `objective`, `problem`, `scope` |
| 2 | No unresolved design decision | Decision / Design | `no unresolved design decision`, `no unresolved`, `no open questions`, `design is decided`, `decided` |
| 3 | Explicit acceptance criteria | Acceptance criteria / Done when | `acceptance criteria`, `acceptance`, `done when` |
| 4 | Explicit verification path | Verification | `verification`, `verification path`, `verify`, `how to verify`, `validation` |
| 5 | Dependencies / blockers | Dependencies | `dependencies`, `dependency`, `blocker`, `blocked by` |
| 6 | Small enough for one session | Scope (sizing) | `small enough`, `one implementation session`, `one fresh implementation session`, `one session`, `single session`, `sized for one` |
| 7 | Prefer vertical / tracer-bullet decomposition | Scope (shape) | `vertical`, `tracer bullet`, `tracer-bullet`, `tracer`, `end-to-end`, `slice` |

A structured front-matter / fenced JSON/YAML schema is also accepted when it carries the same concept keys (e.g. `boundedOutcome`, `acceptanceCriteria`, `verification`, `dependencies`, `sizing`, `shape`), because the alias patterns match key names.

## Anti-patterns

- "Build the whole system/feature" without a slice — violates #1, #6, #7.
- Acceptance described only as "works correctly" with no checkable criteria — violates #3, #4.
- "Decide the design during implementation" or `TBD` left open — violates #2.
- No mention of blockers or assuming they will be discovered during work — violates #5.

## Validation

`.sandcastle/tracer-contract.mts` implements alias-tolerant concept detection (see file). `.sandcastle/dispatch.mts` calls it fail-closed **only** when `agent:implement` is present, returning `tracer contract missing: …` if any concept is absent. `ready-for-agent` alone is not an execution gate.

This issue (#56) itself passes validation — it uses `Problem`/`Scope`/`Acceptance criteria`/`Verification`/`Dependencies`, not `Goal`/`Done when`, yet satisfies all 7 concepts.
