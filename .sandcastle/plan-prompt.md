# ELIGIBLE ISSUES

The following issues have **already passed deterministic eligibility checks** host-side
(open, has `agent:implement`, not claimed/in-progress, no open native blockers,
not a forbidden Wayfinder workflow type). Do not re-check those gates — they are authoritative.

<issues-json>
{{ISSUES_JSON}}
</issues-json>

# TASK

You are the overlap/serialization advisor, not the eligibility gate.

- If the eligible issues touch disjoint files/modules and can safely run in parallel, include all of them.
- If two or more issues are likely to edit overlapping files/modules or produce merge conflicts, serialize: include only the subset that can run safely now and defer the rest to the next factory iteration. Explain your reasoning briefly.
- Never invent eligibility. Never override a real blocker (already filtered). Never execute a Wayfinder `research`/`prototype`/`grilling` ticket — those are already excluded.
- If every issue is blocked, you would have received an empty list (already handled host-side). Do not force execution of a blocked issue.

For each issue you include, assign branch `sandcastle/issue-{id}` deterministically.

# OUTPUT

Output your plan as JSON inside <plan> tags:

<plan>{"issues": [{"id": "42", "title": "Fix auth bug", "branch": "sandcastle/issue-42"}]}</plan>

Include only the issues you advise to run **now** (subset of the eligible list). If none can run concurrently due to overlap, include the single safest candidate. Always emit <plan> tags. If the input list is empty, emit `<plan>{"issues": []}</plan>`.
