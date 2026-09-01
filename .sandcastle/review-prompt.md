# TASK

Review the code changes on branch `{{BRANCH}}` against the original issue contract (fresh context) for #{{ISSUE_NUMBER}}: {{ISSUE_TITLE}}.
Candidate SHA: `{{CANDIDATE_SHA}}` — this exact SHA is the authoritative candidate under review. Verify the implementation at that exact SHA matches the issue's acceptance criteria, not just the diff's self-description.

# CONTEXT

## Original issue contract — authoritative intent (fresh context)

This section is the **authoritative source of intent**. It is fetched live for every review via `gh issue view {{ISSUE_NUMBER}} --json body --jq .body` and injected as `{{ISSUE_BODY}}` by the factory host. Do not use a cached or stale copy — if the injected body and `gh` output differ, the `gh` output is authoritative.

**Issue #{{ISSUE_NUMBER}}: {{ISSUE_TITLE}}**

{{ISSUE_BODY}}

> If the body is empty or unavailable, fetch it inside the sandbox with `gh issue view {{ISSUE_NUMBER}} --json body --jq .body` and treat that as authoritative. Never infer acceptance criteria from diff/commits alone.

## Candidate identity

- Branch: `{{BRANCH}}`
- Exact candidate SHA: `{{CANDIDATE_SHA}}`
- Review worktree: disposable read-only context from that exact SHA

{{PRIOR_FINDINGS_SECTION}}

## Branch diff (truncated for prompt size — fetch full diff with tools if needed)

!`git diff --stat {{TARGET_BRANCH}}...{{CANDIDATE_SHA}}; echo "---"; git diff {{TARGET_BRANCH}}...{{CANDIDATE_SHA}} | head -c 60000; echo ""; echo "[diff truncated at 60kB — run git diff {{TARGET_BRANCH}}...{{CANDIDATE_SHA}} via Bash tool for full diff]"`

## Commits on this branch

!`git log {{TARGET_BRANCH}}..{{CANDIDATE_SHA}} --oneline --max-count=20`

## Coding standards

See `.sandcastle/CODING_STANDARDS.md` — read it via Read tool when needed (not inlined to avoid argv overflow).

# REVIEW PROCESS — READ ONLY

This review runs in a disposable read-only sandbox from the exact candidate SHA. You MUST NOT:

- Edit, commit, or repair code or docs
- Modify tracked or untracked candidate source state
- Create commits or change HEAD
- Use GitHub write operations (GH_TOKEN and GITHUB_TOKEN are empty; write capability is forbidden)

Persistent tracked/untracked source mutation, commit, or HEAD movement in this review worktree INVALIDATES the review and the host will treat the verdict as FACTORY_ERROR even if a parseable verdict was emitted. The real candidate branch must remain unchanged.

You MAY:

- Inspect code, diffs, tests, and existing evidence
- Run non-destructive read/inspect commands (git show, git diff, cat, grep, npm run typecheck, npm test in read-only mode, etc.) that do not mutate candidate source state

# OUTPUT — machine-readable ReviewVerdict

You MUST emit a machine-readable verdict as JSON inside `<verdict>` tags. The factory gates merge on `approved` — `approved=true` means eligible for merger, `approved=false` means do NOT merge, preserve branch, and mark `agent:blocked` with findings.

Schema lives in `.sandcastle/review-verdict.mts` (`reviewVerdictSchema` via Zod) — keep prompt and code in sync.

Schema (`ReviewVerdict`):

```json
{
  "candidateSha": "40-char hex SHA matching CANDIDATE_SHA above",
  "approved": true,
  "findings": [{ "axis": "combined", "severity": "blocking", "invariant": "criterion text or invariant", "failureMode": "how it fails", "evidence": ["file:line or test name"], "requiredProof": "what would prove resolution" }],
  "acceptanceCriteriaMet": [{ "criterion": "verbatim criterion text", "met": true, "evidence": "file:line or test name" }],
  "priorFindings": [{ "findingId": "F-abc123", "status": "resolved", "evidence": ["fix evidence at new SHA"], "replacementFindingIndex": 0 }],
  "summary": "one-paragraph summary of contract alignment"
}
```

Rules:

- `candidateSha` MUST equal `{{CANDIDATE_SHA}}` exactly.
- `findings`: every mismatch between contract and implementation. Each finding MUST have `axis` (use "combined" — other axes reserved for later), `severity` ("blocking", "nit", "suggestion"), `invariant`, `failureMode`, `evidence` (array), `requiredProof`. Evidence text and order do not affect finding identity — the host computes stable IDs from axis+invariant+failureMode+requiredProof.
- `acceptanceCriteriaMet`: one entry per acceptance criterion extracted from the issue body, including unchecked boxes. Copy criterion text verbatim where possible.
- `priorFindings`: ONLY for fresh re-review. Initial review MUST NOT contain priorFindings. Fresh re-review MUST classify every prior blocking finding ID exactly once as "resolved" (with evidence against new SHA), "unresolved" (remains blocking), or "superseded" (with valid replacementFindingIndex pointing to a new finding in this verdict).
- `approved` is `true` only if EVERY acceptance criterion is `met=true` and there are zero `blocking` findings (including unresolved prior findings and blocking replacement findings). Host gating independently enforces this — your `approved` boolean alone is never sufficient.
- Always emit the `<verdict>` block, even when you find blocking issues.
- Example rejected: `<verdict>{"candidateSha": "abc...40hex", "approved": false, "findings": [{"axis":"combined","severity":"blocking","invariant":"gadget criterion X","failureMode":"missing gadget for X","evidence":["no code path covers X"],"requiredProof":"implement gadget and add test"}],"acceptanceCriteriaMet": [{"criterion": "gadget does X", "met": false, "evidence": "no code path covers X"}], "summary": "Implementation diverges from acceptance criterion X"}</verdict>`
- Example approved: `<verdict>{"candidateSha": "abc...40hex", "approved": true, "findings": [], "acceptanceCriteriaMet": [{"criterion": "gadget does X", "met": true, "evidence": "src/foo.ts:42 and test foo.test.ts"}], "summary": "All criteria met"}</verdict>`
- Example re-review resolved: `<verdict>{"candidateSha": "abc...40hex","approved": true,"findings":[],"acceptanceCriteriaMet":[{"criterion":"A","met":true,"evidence":"src/a.ts:1"}],"priorFindings":[{"findingId":"F-abc123","status":"resolved","evidence":["src/a.ts:10 fixes X"]}],"summary":"Prior finding resolved"}</verdict>`

Do not close the issue. Once complete, output <promise>COMPLETE</promise>.
