# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."` — `main` is protected (direct pushes blocked, PRs require `Factory / Merge Oracle` — see `.github/workflows/factory-ci.yml` + branch protection, squash only). **Sandcastle (`agent:implement`) always via PR `closes #N` after `origin/main`;** `wayfinder:grilling`/`prototype` may close via issue updates alone. Native `blocked_by` gates on `closed`, not on `origin/main`.

Infer the repo from `git remote -v` — `gh` does this automatically when run inside a clone.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests; `/triage` reads this flag.)_

When set to `yes`, PRs run through the same labels and states as issues, using the `gh pr` equivalents:

- **Read a PR**: `gh pr view <number> --comments` and `gh pr diff <number>` for the diff.
- **List external PRs for triage**: `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments` then keep only `authorAssociation` of `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, or `NONE` (drop `OWNER`/`MEMBER`/`COLLABORATOR`).
- **Comment / label / close**: `gh pr comment`, `gh pr edit --add-label`/`--remove-label`, `gh pr close`.

GitHub shares one number space across issues and PRs, so a bare `#42` may be either — resolve with `gh pr view 42` and fall back to `gh issue view 42`.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: a single issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body. `gh issue create --label wayfinder:map`.
- **Child ticket**: an issue linked to the map as a GitHub sub-issue (`gh api` on the sub-issues endpoint). Where sub-issues aren't enabled, add the child to a task list in the map body and put `Part of #<map>` at the top of the child body. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`) — see `CONTEXT.md` and `docs/adr/0010-preserve-upstream-wayfinder-triage-and-sandcastle-label-semantics.md` (ADR 0007 partially superseded) for current executor semantics: `research` is AFK via Sandcastle research profile (`wayfinder:research` alone, eligible: open, exactly one Wayfinder type `wayfinder:research`, unassigned, no `agent:in-progress`/`agent:blocked`, `blocked_by=0`, body contract); `prototype`/`grilling` are HITL-only; `task` uses triage — HITL Task = `wayfinder:task` + `ready-for-human`, AFK Task = `wayfinder:task` + `ready-for-agent` + tracer contract + one-shot `agent:implement`. Only AFK Task may carry `agent:implement`. Once claimed, the ticket is assigned to the driving dev. Wayfinder type, triage readiness, Sandcastle command, transient state, and native claim/`blocked_by` are distinct per ADR 0010.
- **Blocking**: GitHub's **native issue dependencies** — the canonical, UI-visible representation. Add an edge with `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, where `<blocker-db-id>` is the blocker's numeric **database id** (`gh api repos/<owner>/<repo>/issues/<n> --jq .id`, _not_ the `#number` or `node_id`). GitHub reports `issue_dependencies_summary.blocked_by` (open blockers only — the live gate). Where dependencies aren't available, fall back to a `Blocked by: #<n>, #<n>` line at the top of the child body. A ticket is unblocked when every blocker is closed.
- **Frontier query**: list the map's open children (`gh issue list --state open`, scoped to the map's sub-issues / task list), drop any with an open blocker (`issue_dependencies_summary.blocked_by > 0`, or an open issue in the `Blocked by` line) or an assignee; first in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me` — the session's first write.
- **Resolve**: `gh issue comment <n> --body "<answer>"`, then `gh issue close <n>`, then append a context pointer (gist + link) to the map's Decisions-so-far.

## AFK execution via Sandcastle

Sandcastle is the common AFK execution substrate for implementation and research. `wayfinder:*` describes purpose; triage describes durable readiness; `agent:*` are one-shot commands or transient state; native assignee/`blocked_by` own concurrency and dependencies (ADR 0010).

- **Implementation**: `ready-for-agent` + one-shot `agent:implement` (plus tracer-bullet contract where required). Eligibility requires both durable readiness and command, plus open, unassigned, unblocked, no conflicting transient state, valid tracer, and no contradictory Wayfinder combination. Claim consumes `agent:implement` to `ready-for-agent` + `agent:in-progress` + assignee (verified, compensated fail-closed, never restores implement). Stale reconciliation releases assignee/`agent:in-progress` without restoring command, preserving branch. Dispatched via implementer → reviewer → merger → push → PR.
- **Research**: `wayfinder:research` alone (open, exactly one Wayfinder type `wayfinder:research`, unassigned, no `agent:in-progress`/`agent:blocked`, `blocked_by=0`, body contract). No `agent:research` or `ready-for-agent` required; historical `ready-for-agent` residue is removable. Dispatched via Sandcastle parallel research profile: isolated worktree/sandbox per ticket from frozen `origin/main`, Muse researcher with host-fetched body, strict structured result (`summary, findings[{claim,evidence,source}], recommendation, uncertainties[], followUps[]`), host-side publication + required parent-map pointer (`Part of #N`) + close (no retention of retired `agent:research`). Research retains distinct lifecycle: one result, one publication, one parent pointer, one close; no implementation review, merger, PR, or auto-merge. Result and parent-pointer comments carry idempotency markers. Invalid output or infrastructure/provider/publication/parent-pointer/close failure is `FACTORY_ERROR` (release transient claim, no `agent:blocked`, retryable, stop outer loop). `agent:research` is retired; `wayfinder:preserve-futures` is retired as `wayfinder:task` + `ready-for-agent`.

## Labels

Retired: `agent:research` and `wayfinder:preserve-futures` are retired per ADR 0010 and must not be created on new work. Use `wayfinder:research` alone for research and `wayfinder:task` + `ready-for-agent` with checkpoint body for futures checkpoints. Migration command `.sandcastle/tracker-migration.mts` removes residue and deletes retired repository labels after no open issue depends on them.

Current canonical labels (see `.sandcastle/tracker-policy.mts` and ADR 0010):
- Wayfinder types: `wayfinder:map`, `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, `wayfinder:task`
- Triage durable: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`
- One-shot commands: `agent:implement`, `agent:review` (optional `agent:explore`)
- Transient: `agent:in-progress`, `agent:blocked`
- Native: assignee (concurrency claim), `blocked_by` (product/planning dependency)

Reference ADR 0010 for label roles. Do not parse exact map Notes sentences or create speculative Research children; keep unready questions in map fog until sharp.
## Research input contract

Research tickets (`wayfinder:research`) are eligible for Sandcastle research profile only when their body satisfies the research input contract, per CONTEXT.md “Research Ticket” and ADR 0010.

**Contract — pure `validateResearchTicketInput(body)` in `.sandcastle/tracker-policy.mts`:**

- Body must contain a substantive nonempty `## Question` heading (case-insensitive, `## Question`).
- Content after the heading must be at least 20 characters and 5 words, with substantive alphabetic text.
- Examples: #163 fresh-world-scenario-automation, #86 refinement-topology, #68 voxygen-dev-loop, #66 ml-data-supply-research, #37 vocab-audit all contain `## Question` with multi-paragraph evidence-seeking questions.
- Trivial bodies such as `please investigate this` (despite passing a 10-char/2-word heuristic) fail because they lack a `## Question` section or are too short to be substantive.
- No tracer/bullet contract required for research; tracer is implementation-only.

This validator is used by production `isResearchEligible()` and tests. It prevents empty or placeholder `wayfinder:research` issues from authorizing AFK work while keeping the check pure and owned.

Historical bodies without a Question section are ineligible and must be triaged before dispatch.
