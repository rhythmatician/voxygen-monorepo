# TASK

Review the code changes on branch `{{BRANCH}}` against the original issue contract (fresh context) for #{{ISSUE_NUMBER}}: {{ISSUE_TITLE}}. Verify the implementation matches the issue's acceptance criteria, not just the diff's self-description.

# CONTEXT

## Original issue contract — authoritative intent (fresh context)

This section is the **authoritative source of intent**. It is fetched live for every review via `gh issue view {{ISSUE_NUMBER}} --json body --jq .body` and injected as `{{ISSUE_BODY}}` by the factory host. Do not use a cached or stale copy — if the injected body and `gh` output differ, the `gh` output is authoritative.

**Issue #{{ISSUE_NUMBER}}: {{ISSUE_TITLE}}**

{{ISSUE_BODY}}

> If the body is empty or unavailable, fetch it inside the sandbox with `gh issue view {{ISSUE_NUMBER}} --json body --jq .body` and treat that as authoritative. Never infer acceptance criteria from diff/commits alone.

## Branch diff (truncated for prompt size — fetch full diff with tools if needed)

!`git diff --stat {{TARGET_BRANCH}}...{{BRANCH}}; echo "---"; git diff {{TARGET_BRANCH}}...{{BRANCH}} | head -c 60000; echo ""; echo "[diff truncated at 60kB — run git diff {{TARGET_BRANCH}}...{{BRANCH}} via Bash tool for full diff]"`

## Commits on this branch

!`git log {{TARGET_BRANCH}}..{{BRANCH}} --oneline --max-count=20`

## Coding standards

See `.sandcastle/CODING_STANDARDS.md` — read it via Read tool when needed (not inlined to avoid argv overflow).

# REVIEW PROCESS

1. Understand intent from original issue contract and acceptance criteria in a fresh context, alongside diff, commits, and coding standards. The issue contract is authoritative — do not derive intent solely from diff/commits.
2. Extract every acceptance criterion from the issue body. For each, locate evidence in the diff/commits/tests that it is met; absence of evidence is a finding.
3. Check changed prose against `docs/agents/documentation.md`. Remove documentation that duplicates executable truth or GitHub work state. Prefer improving code, types, tests, or navigation over preserving an explanatory implementation doc.
4. Look for unnecessary complexity, redundant code, poor naming, or security issues.
5. Check correctness: does the implementation match the contract, handle edge cases, have tests, and avoid unsafe casts/`any`?
6. Apply standards from `@.sandcastle/CODING_STANDARDS.md`.
7. Preserve functionality — never change what the code does, only how, unless the contract requires a behavior change that the diff missed (then flag as `approved=false` rather than silently fixing intent).

# EXECUTION

If improvements found that preserve intent:

1. Make changes directly on this branch
2. Run `npm run typecheck` and `npm run test` to verify
3. Commit describing refinements

If already clean, do nothing.

# OUTPUT — machine-readable ReviewVerdict

You MUST emit a machine-readable verdict as JSON inside `<verdict>` tags. The factory gates merge on `approved` — `approved=true` means eligible for merger, `approved=false` means do NOT merge, preserve branch, and mark `agent:blocked` with findings.

Schema lives in `.sandcastle/review-verdict.mts` (`reviewVerdictSchema` via Zod) — keep prompt and code in sync.

Schema (`ReviewVerdict`):

```json
{
  "approved": true,
  "findings": [{ "message": "criterion X not met: ...", "severity": "blocking" }],
  "acceptanceCriteriaMet": [{ "criterion": "verbatim criterion text", "met": true, "evidence": "file:line or test name" }],
  "summary": "one-paragraph summary of contract alignment"
}
```

Rules:

- `approved` is `true` only if EVERY acceptance criterion is `met=true` and there are zero `blocking` findings. If any criterion is unmet or any blocking finding exists, set `approved=false`.
- `findings`: every mismatch between contract and implementation. Use `severity: "blocking"` for contract violations, `severity: "nit"` for style-only (`suggestion` is also allowed).
- `acceptanceCriteriaMet`: one entry per acceptance criterion extracted from the issue body, including unchecked boxes. Copy criterion text verbatim where possible.
- Always emit the `<verdict>` block, even when you also make refinement commits.
- Example rejected: `<verdict>{"approved": false, "findings": [{"message": "missing gadget for criterion '...'", "severity": "blocking"}], "acceptanceCriteriaMet": [{"criterion": "gadget does X", "met": false, "evidence": "no code path covers X"}], "summary": "Implementation diverges from acceptance criterion X"}</verdict>`
- Example approved: `<verdict>{"approved": true, "findings": [], "acceptanceCriteriaMet": [{"criterion": "gadget does X", "met": true, "evidence": "src/foo.ts:42 and test foo.test.ts"}], "summary": "All criteria met"}</verdict>`

Do not close the issue. Once complete, output <promise>COMPLETE</promise>.
