# TASK

Fix issue {{TASK_ID}}: {{ISSUE_TITLE}}

Pull the issue with `gh issue view {{TASK_ID}} --comments` and verify:

- It must have `agent:implement`.
- It must NOT have a forbidden Wayfinder type (`wayfinder:prototype`, `wayfinder:grilling`, `wayfinder:map`, `wayfinder:preserve-futures`) — `wayfinder:research` is AFK via Wayfinder subagents, not Sandcastle (see ADR 0001; it has no `agent:implement` so it is already ineligible here). If it does, stop and output <promise>COMPLETE</promise> without making changes — the host misrouted a HITL ticket.

Defense-in-depth second guard (triple-signal for `wayfinder:task` AFK Task = wayfinder:task + agent:implement + signal; HITL Task = wayfinder:task without agent:implement — see CONTEXT.md):

- After `gh issue view {{TASK_ID}} --comments`, if the issue has both `wayfinder:task` and `agent:implement` but the body does NOT contain `Execution is carried into this map`, log `SKIP(wayfinder:task: map Notes does not authorize AFK execution)` and output <promise>COMPLETE</promise> without making changes.
- This covers host misroute and encodes labels + map Notes as the only durable AFK signal (v0 proxies via ticket body; v1 can fetch map body via `gh api`).

Only work on the issue specified. Work on branch {{BRANCH}}. Make commits and run tests.

Reviewer feedback (if any, on retry): {{REVIEW_FEEDBACK}}
If feedback is present, address all findings and unmet criteria above in this retry; keep prior commits and amend with fixes.

You have the wayfinder skill installed. Invoke it with `/wayfinder` or follow
`.muse/skills/wayfinder/SKILL.md` directly. The vendored skills at
`.muse/skills/*` are installed via `muse skills install --scope user` in the
sandbox ready hook.

# CONTEXT

Last 10 commits:

<recent-commits>
!`git log -n 10 --format="%H%n%ad%n%B---" --date=short`
</recent-commits>

# EXECUTION

Explore the repo and fill context with relevant code and tests.

Use Red-Green-Refactor where applicable.

Before committing, run:
- If `git diff --name-only` touches `java/` (or `java` files changed in this run), then `bash .ci/install-voxy.sh install` (if needed) and `./java/gradlew -p java lint compileJava compileClientJava` and `./java/gradlew -p java test -PexcludeVoxyTestRuntime` must pass (mirrors factory-ci.yml Java lane).
- Then `npm run typecheck` and `npm run test` must pass.

# DOCUMENTATION

Repository prose is not a shadow source of truth. Before committing any Markdown change, apply `docs/agents/documentation.md`. Current mechanics belong in executable artifacts; current/planned work belongs in the GitHub issue/PR. Do not create implementation summaries, plans, status, TODO, handoff, deliverables, or checklist documents. Authoritative source: code/tests/contracts/config or GitHub.

# COMMIT

Commit message must start with `RALPH:` and concisely record task + key decisions + files changed + blockers.

# COMPLETION

If task is incomplete, leave a comment on the issue with progress and what remains. Do NOT close the issue — the factory merger closes it after integration.

Once complete, output <promise>COMPLETE</promise>.

# FINAL RULES

ONLY WORK ON A SINGLE TASK. Do not touch unrelated issues.

- Do not weaken tests, oracles, or fixtures to make CI pass unless the issue explicitly changes the contract — fix the implementation instead.
- Treat `external/` as read-only — see `external/README.md`.
- After repeated failure, stop and report evidence without destructively reverting the working tree.
