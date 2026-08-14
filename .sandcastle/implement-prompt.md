# TASK

Fix issue {{TASK_ID}}: {{ISSUE_TITLE}}

Pull the issue with `gh issue view {{TASK_ID}} --comments`. Verify labels: it must have `agent:implement` and must NOT have a forbidden Wayfinder type (`wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, `wayfinder:map`, `wayfinder:preserve-futures`). If it does, stop and output <promise>COMPLETE</promise> without making changes — the host misrouted a HITL ticket.

Defense-in-depth second guard (triple-signal for `wayfinder:task`): after `gh issue view {{TASK_ID}} --comments` re-check, also verify that if the issue has both `wayfinder:task` and `agent:implement` but the body does NOT contain `Execution is carried into this map`, then log `SKIP(wayfinder:task: map Notes does not authorize AFK execution)` and output <promise>COMPLETE</promise> without making changes. This covers host misroute and encodes labels + map Notes as the only durable AFK signal (v0 proxy via ticket body; v1 can fetch map body via `gh api`).

Only work on the issue specified. Work on branch {{BRANCH}}. Make commits and run tests.

# CONTEXT

Last 10 commits:

<recent-commits>
!`git log -n 10 --format="%H%n%ad%n%B---" --date=short`
</recent-commits>

# EXECUTION

Explore the repo and fill context with relevant code and tests. Pay attention to contract tests under `java/src/contractTest/` and schemas under `spec/` — they are immutable oracles, never edit them to make tests pass.

Use Red-Green-Refactor where applicable.

Before committing, run `npm run typecheck` and `npm run test` (or the narrowest applicable gate `./dev/verify-all` when touching Java/Python oracles).

# COMMIT

Commit message must start with `RALPH:` and concisely record task + key decisions + files changed + blockers.

# COMPLETION

If task is incomplete, leave a comment on the issue with progress and what remains. Do NOT close the issue — the factory merger closes it after integration.

Once complete, output <promise>COMPLETE</promise>.

# FINAL RULES

ONLY WORK ON A SINGLE TASK. Do not touch unrelated issues. Respect `AGENT_RULES.md` — never weaken oracles or fixtures to make tests green.
