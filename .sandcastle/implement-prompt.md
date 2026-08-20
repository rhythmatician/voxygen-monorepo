/implement

# TASK

Fix issue {{TASK_ID}}: {{ISSUE_TITLE}}

The factory host already selected this issue and verified its eligibility. The
following is the authoritative implementation contract; do not infer the task
from nearby branches, prior issues, or repository history.

<issue-contract>
{{ISSUE_BODY}}
</issue-contract>

Only work on the issue specified. Work on branch {{BRANCH}}. Make commits and run tests.

Reviewer feedback (if any, on retry): {{REVIEW_FEEDBACK}}
If feedback is present, address all findings and unmet criteria above in this retry; keep prior commits and amend with fixes.

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
