/implement

# TASK

Fix review findings for issue {{ISSUE_NUMBER}}: {{ISSUE_TITLE}}

The factory host already selected this issue and verified its eligibility. The
following is the authoritative implementation contract; do not infer the task
from nearby branches, prior issues, or repository history.

<issue-contract>
{{ISSUE_BODY}}
</issue-contract>

You are the **fixer** — a separate role from the reviewer. You did not review this candidate and you do not emit an approval verdict. Your task is only to address the supplied structured findings while preserving issue scope and prior useful commits.

# CONTEXT

- Branch: `{{BRANCH}}`
- Reviewed candidate SHA: `{{REVIEWED_SHA}}`
- Acceptance criteria reported unmet: {{UNMET_CRITERIA}}
{{FINDINGS_BLOCK}}
- Prior review summary: {{REVIEW_SUMMARY}}

# FINDINGS TO ADDRESS

Each finding below has a stable host-owned ID computed from axis+invariant+failureMode+requiredProof. Address each blocking finding; preserve useful prior commits.

{{FINDINGS_DETAIL}}

# EXECUTION

1. Understand the original issue contract and each finding's invariant, failureMode, evidence, and requiredProof.
2. Make minimal, focused fixes on branch `{{BRANCH}}` that address blocking findings without scope drift. Preserve prior useful commits.
3. Before committing, run:
   - If `git diff --name-only` touches `mod/` then `bash .ci/install-voxy.sh install` (if needed) and `./mod/gradlew -p mod lint compileJava compileClientJava` and `./mod/gradlew -p mod test -PexcludeVoxyTestRuntime`
   - Then `npm run typecheck` and `npm run test` must pass.
4. Commit with message starting `RALPH:` describing the fix.

# COMPLETION

Do NOT emit a <verdict>. Do NOT approve your own work. A fresh read-only reviewer will verify your repair against the exact new SHA. Once complete, output <promise>COMPLETE</promise>.

# FINAL RULES

ONLY WORK ON THIS TASK. Do not touch unrelated issues.
- Do not weaken tests, oracles, or fixtures to make findings disappear — fix the implementation instead.
- Treat `external/` as read-only.
