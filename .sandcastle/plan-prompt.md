# ELIGIBLE ISSUES

The following issues have **already passed deterministic eligibility checks** host-side
(open, has `agent:implement`, not claimed/in-progress, no open native blockers,
not a forbidden Wayfinder workflow type (prototype/grilling/map/preserve-futures; research is AFK via Wayfinder, not Sandcastle), and for `wayfinder:task` (AFK Task) the triple-signal `Execution is carried into this map` in body). Do not re-check those gates — they are authoritative.

<issues-json>
{{ISSUES_JSON}}
</issues-json>

# TASK

You are the overlap/serialization advisor, not the eligibility gate.

- If the eligible issues touch disjoint files/modules and can safely run in parallel, include all of them.
- If two or more issues are likely to edit overlapping files/modules or produce merge conflicts, serialize: include only the subset that can run safely now and defer the rest to the next factory iteration. Explain your reasoning briefly.
- Never invent eligibility. Never override a real blocker (already filtered). Never execute a HITL Wayfinder ticket (`prototype`/`grilling`/HITL `task`) — those are already excluded; `research` is AFK via Wayfinder subagents, not Sandcastle.
- If every issue is blocked, you would have received an empty list (already handled host-side). Do not force execution of a blocked issue.

## Wayfinder Serialization Rule (Factory v0 — from #17/#18)

- When the eligible set contains two or more `wayfinder:task` issues that reference the same map (both contain `Part of #<same-map>` or both lack distinct map context), include **at most one `wayfinder:task` per map per iteration**. Defer additional same-map tickets to the next factory iteration. If the map cannot be parsed, treat any two `wayfinder:task` issues as same-map and serialize to one.
- Ordinary implementation issues (no `wayfinder:*` label) may still run concurrently via `Promise.allSettled`; only same-map Wayfinder tickets serialize.
- Parent-map mutation is single-writer: Wayfinder owns `gh issue edit <map> --body` for Notes; host merger does not close Wayfinder tickets (see `// TODO(factory-v1): Wayfinder close ownership` in `.sandcastle/main.mts`).
- For map extraction, look for `Part of #14` / `Part of #<number>` patterns in issue bodies; use the numeric map id as the grouping key.

For each issue you include, assign branch `sandcastle/issue-{id}` deterministically.

# OUTPUT

Output your plan as JSON inside <plan> tags:

<plan>{"issues": [{"id": "42", "title": "Fix auth bug", "branch": "sandcastle/issue-42"}]}</plan>

Include only the issues you advise to run **now** (subset of the eligible list). If none can run concurrently due to overlap, include the single safest candidate. Always emit <plan> tags. If the input list is empty, emit `<plan>{"issues": []}</plan>`.
