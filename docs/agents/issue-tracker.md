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
- **Child ticket**: an issue linked to the map as a GitHub sub-issue (`gh api` on the sub-issues endpoint). Where sub-issues aren't enabled, add the child to a task list in the map body and put `Part of #<map>` at the top of the child body. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`) — see `CONTEXT.md` and `docs/adr/0007-sandcastle-common-afk-substrate.md` for current executor semantics: `research` is AFK via Sandcastle research profile (`wayfinder:research` + `agent:research`, eligible: open, unassigned, unblocked, unclaimed, `blocked_by=0`); `prototype`/`grilling` are HITL-only; `task` is orthogonal — HITL Task = `wayfinder:task` without `agent:implement`, AFK Task = `wayfinder:task` + `agent:implement` + `Execution is carried into this map` + tracer-bullet contract. Once claimed, the ticket is assigned to the driving dev.
- **Blocking**: GitHub's **native issue dependencies** — the canonical, UI-visible representation. Add an edge with `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, where `<blocker-db-id>` is the blocker's numeric **database id** (`gh api repos/<owner>/<repo>/issues/<n> --jq .id`, _not_ the `#number` or `node_id`). GitHub reports `issue_dependencies_summary.blocked_by` (open blockers only — the live gate). Where dependencies aren't available, fall back to a `Blocked by: #<n>, #<n>` line at the top of the child body. A ticket is unblocked when every blocker is closed.
- **Frontier query**: list the map's open children (`gh issue list --state open`, scoped to the map's sub-issues / task list), drop any with an open blocker (`issue_dependencies_summary.blocked_by > 0`, or an open issue in the `Blocked by` line) or an assignee; first in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me` — the session's first write.
- **Resolve**: `gh issue comment <n> --body "<answer>"`, then `gh issue close <n>`, then append a context pointer (gist + link) to the map's Decisions-so-far.

## AFK execution via Sandcastle

Sandcastle is the common AFK execution substrate for both implementation and research. `wayfinder:*` describes purpose; `agent:*` authorizes execution.

- **Implementation**: `agent:implement` (plus tracer-bullet contract where required). Dispatched via `agent:implement` eligibility, implementer → reviewer → merger → push → PR.
- **Research**: `agent:research` + `wayfinder:research` (open, unassigned, unblocked, unclaimed, `blocked_by=0`). `ready-for-agent` is triage only. `agent:research` without `wayfinder:research` or `agent:implement + agent:research` fails closed. Dispatched via Sandcastle parallel research profile: isolated worktree/sandbox per ticket from frozen `origin/main`, Muse researcher with host-fetched body, strict structured result (`summary, findings[{claim,evidence,source}], recommendation, uncertainties[], followUps[]`), host-side publication + parent-map pointer (`Part of #N`), close + claim cleanup, no commits/review/merger. Invalid output or infrastructure failure is `FACTORY_ERROR` (release, retryable, stop outer loop).

## Labels

- **Create research authorization label**: `gh label create agent:research --description "AFK research authorized for Sandcastle research profile" --color BFD4F2` (or `gh api repos/{owner}/{repo}/labels -f name=agent:research -f color=BFD4F2 -f description="..."`). Verify with `gh label list --json name`.
- **Rollout post-merge for #159/#160/#161**: after protected-root PR merges, confirm label exists, apply `gh issue edit 159 --add-label agent:research` (repeat for 160, 161), then run `npm run sandcastle` once — the three tickets launch in parallel via the research profile and do not enter the implementation pipeline.
