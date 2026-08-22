# 0007 — Sandcastle is the common AFK execution substrate; Wayfinder owns purpose and frontier

Date: 2026-08-21
Status: Accepted
Supersedes: ADR 0006 — Wayfinder plans work; Sandcastle executes implementation work

## Context

After ADR 0006, Sandcastle executed only implementation work (`agent:implement`) while Wayfinder research (`wayfinder:research`) ran through an interactive `/wayfinder` session. That forced frontier research for map #22 to serialize through a human-guided session, preventing the factory from claiming and executing several independent research tickets in parallel. The production targets #159, #160, and #161 require independent parallel execution.

The factory already provides: deterministic issue fetching, claim via `assignee + agent:in-progress`, managed worktree/sandbox creation from a frozen `origin/main` base, isolated worker fan-out, host-side GitHub writes via the GitHub capability boundary, and `FACTORY_ERROR` stop semantics. Research needs the same substrate but with separate lifecycle semantics: optional commits preserved on research branch (not integrated), no review/merger/PR/auto-merge, strict evidence-backed structured output, host publication, required parent-map pointer, and retryable release on infrastructure failure.

Label namespaces accumulated risk: `agent:*` authorizes execution, `wayfinder:*` describes purpose, and `ready-for-agent` marks triage/readiness. Without explicit research authorization, `ready-for-agent + wayfinder:research` could be misread as executable, and `agent:research` without `wayfinder:research` or combined `agent:implement + agent:research` would silently mis-route.

## Decision

**Sandcastle owns AFK execution generally; Wayfinder owns purpose and frontier semantics.**

- Sandcastle is the common AFK substrate for both implementation and research. Wayfinder labels continue to describe purpose; `agent:*` labels authorize execution; `ready-for-agent` remains triage and never authorizes execution alone.
- Implementation authorization remains `agent:implement` (plus tracer-bullet contract) → implementer → reviewer → merger → push → PR.
- Add `agent:research` as explicit authorization for the research profile. Research eligibility requires all of: open, `wayfinder:research` + `agent:research`, no `agent:in-progress`/`agent:blocked`, no assignee, native `blocked_by` known and zero. `agent:research` without `wayfinder:research` or combined `agent:implement + agent:research` fails closed with a deterministic reason.
- Research lifecycle is separate: discover eligible research tickets → host claim (`assignee + agent:in-progress`) → dedicated managed worktree/sandbox per ticket from frozen factory base → single Muse researcher with host-fetched issue body → strict structured result (`summary, findings[{claim,evidence,source}], recommendation, uncertainties[], followUps[]`) → host-side issue comment publication → concise parent-map pointer when body contains `Part of #N` (required when present) → close (retaining `agent:research`, removing only transient claim) + cleanup. Research success does not require commits. Research may produce commits on its dedicated branch for durable knowledge artifacts such as `CONTEXT.md`, `GLOSSARY.md`, ADRs, or version-bound reference documents, but those commits must not enter implementation review, merger, batch integration, push, PR creation, or autonomous-merge paths. Workers receive model/network + repo access but no GitHub write credential; all tracker writes occur on the host via the GitHub capability boundary. Result and parent-pointer comments carry deterministic idempotency markers (`<!-- research-result:N -->`, `<!-- research-parent-pointer:N->M -->`) making duplicate publications identifiable on retry (retry currently publishes a second comment with the same marker; deduplication is not automatic, but markers allow a future run to detect an already-published result without hiding retry behavior). Environment selection is behind a small per-issue profile seam (`getResearchEnvironment(issue)`) so a future research ticket can request a different image/resources without coupling purpose to executor; the profile’s `image` is wired per worker via `docker({ env, image })`. An explicit unknown/insufficient-evidence conclusion is a successful result when uncertainties and missing evidence are stated.
- In one outer iteration, launch all eligible research tickets concurrently via the existing isolated worker fan-out; do not add a new scheduler. Implementation dispatch remains behaviorally unchanged.
- Invalid structured output or infrastructure/provider/protocol/publication/parent-pointer/close failure is `FACTORY_ERROR` (preserve logs, release transient claim/assignee, retain `agent:research`, leave open retryable, stop outer loop, no `agent:blocked`). Siblings' successes are not erased. Successful close removes only `agent:in-progress` and assignee, retaining `agent:research` as historical authorization; ticket is not redispatched because it is closed.

## Alternatives considered

- Keep research exclusive to interactive `/wayfinder` sessions: preserves the ADR 0006 boundary but serializes independent frontier tickets and blocks #159–#161 parallel proof.
- Add a second factory/scheduler for research: duplicates worktree/sandbox, claim, and `FACTORY_ERROR` machinery; rejected in favor of reusing the existing fan-out and profile seam.
- Introduce a generic workflow engine or environment orchestration subsystem: over-engineers a bounded vertical slice that needs only one profile seam.

## Consequences

- Dispatch classification deterministically distinguishes `implementation`, `research`, and `conflicting`/`ineligible` without changing existing `agent:implement` decisions.
- `wayfinder:research + agent:research` becomes Sandcastle-executable in parallel; `ready-for-agent + wayfinder:research` without `agent:research` remains ineligible.
- Research success does not require commits; optional commits are preserved on the research branch and excluded from review/merger/PR/batch-integration/auto-merge; integration remains implementation-only.
- The repository label `agent:research` must exist; post-merge rollout applies it to #159, #160, #161 and runs Sandcastle once as production acceptance.
- Protected factory/root files changed require human review and must not grant autonomous merge authority.

## When to reconsider

If research needs a truly distinct environment or resource class that the current profile seam cannot express, or if Wayfinder HITL tickets require machine-enforced checklist validation beyond `¬agent:research`, introduce explicit resource or HITL labels rather than re-coupling purpose to executor.
