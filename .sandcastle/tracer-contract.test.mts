import { describe, it, expect } from "vitest";
import { missingTracerConcepts, validateTracerContract, TRACER_CONCEPTS } from "./tracer-contract.mts";
import { isEligible } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";

function issue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 1,
    title: "Test",
    state: "open",
    labels: ["agent:implement"],
    assignees: [],
    blockedByCount: 0,
    ...overrides,
  };
}

// Body that satisfies all 7 concepts using canonical headings + aliases from #56
const CANONICAL_BODY = `
## Problem
Bounded observable outcome: fix tracer contract slicing

## Scope — tracer bullet (end-to-end)
Define generic contract as concepts, not brittle headings: every ready-for-agent issue must have a bounded observable outcome, no unresolved design decision, explicit acceptance criteria, explicit verification path, dependencies/blockers, small enough for one fresh implementation session, and prefer vertical/tracer-bullet decomposition. Tracer bullet vertical slice end-to-end.

## Acceptance criteria
- Generic contract doc exists defining 7 concepts
- dispatch validates concepts before claiming an agent:implement issue
- npm test has passing test
- At least one ready-for-agent issue demonstrates the contract

## Verification
gh issue view <new-issue> --json body --jq .body contains required concepts
npm run typecheck && npm test green
Manual: open a draft PR for a sample slice

## Dependencies
Blocked by #46/#47 reconciliation (done 2026-08-15). Small enough for one session.

No unresolved design decision — design is decided.
`;

// Alias variant: uses Goal / Done when instead of Problem/Scope/Acceptance
const ALIAS_BODY = `
## Goal
Bounded outcome: deliver one vertical slice end-to-end tracer bullet

## Done when
- contract doc exists
- acceptance criteria met
- verification path executed

## Verification
Run npm run typecheck && npm test, check acceptance criteria and dependencies.

## Dependencies
Depends on #46, blocked by nothing. No unresolved design — decided.

Small enough for one session, single session sizing, vertical slice tracer.
`;

const SCHEMA_BODY = `
\`\`\`json
{
  "boundedOutcome": "deliver tracer slice end-to-end",
  "noUnresolvedDesign": "design is decided",
  "acceptanceCriteria": ["contract doc exists", "tests pass"],
  "verification": "npm run typecheck && npm test",
  "dependencies": "blocked by #46 (done)",
  "sizing": "small enough for one fresh implementation session",
  "shape": "vertical tracer-bullet slice end-to-end"
}
\`\`\`
`;

// Minimal #56-like excerpt that must pass (issue uses Problem/Scope/Acceptance criteria/Verification, not Goal/Done when)
const ISSUE_56_EXCERPT = `
## Problem
ready-for-agent tickets currently have no enforced decomposition contract.

## Why this matters now
Matt-derived gap: the factory needs a generic AFK implementation-ticket contract / tracer-bullet decomposition rule.

## Scope — tracer bullet (end-to-end)
- Define the generic contract as concepts, not brittle headings: every ready-for-agent issue must have a bounded observable outcome, no unresolved design decision, explicit acceptance criteria, explicit verification path, dependencies/blockers, small enough for one fresh implementation session, and prefer vertical/tracer-bullet decomposition. Recommend canonical headings but validator must accept aliases or a defined schema — #56 itself uses Problem/Scope/Acceptance criteria/Verification, not Goal/Done when, so the validator must pass #56.
- Implement enforcement: update docs/agents/ templates and .sandcastle/ dispatch to validate the concepts (not literal Goal/Done when strings). Fail-closed rule: an ordinary issue carrying agent:implement must satisfy the AFK-ready contract before claim; ready-for-agent remains triage/readiness, agent:implement is execution authorization (do not make ready-for-agent a second execution gate).
- Add factory tests: npm run typecheck && npm test includes a check that sample ready-for-agent issues pass the concept validation (e.g., .sandcastle/tracer-contract.test.mts covering aliases/schema).

## Acceptance criteria
- Generic contract doc exists defining the 7 concepts, anti-patterns, and that aliases are accepted.
- .sandcastle/ dispatch validates concepts before claiming an agent:implement issue (fails closed if any concept missing).
- npm test has a passing test that enforces the contract on fixtures, including alias-tolerant cases and that the fail-closed rule triggers on agent:implement without the contract.
- At least one ready-for-agent issue demonstrates the contract and passes Factory / Merge Oracle evidence.

## Verification
- gh issue view <new-issue> --json body --jq .body contains the required concepts (acceptance/verification/dependencies present, not literal heading match).
- npm run typecheck && npm test green, including new tracer-contract test.
- Manual: open a draft PR for a sample slice; ci:oracle plan classifies evidence correctly; an agent:implement issue missing a concept is refused at claim.

## Dependencies
Blocked by #46/#47 reconciliation (done 2026-08-15). Small enough for one session, vertical tracer bullet.
No unresolved design decision — design is decided.
`;

describe("tracer contract concepts", () => {
  it("has exactly 7 concepts", () => {
    expect(TRACER_CONCEPTS).toHaveLength(7);
    expect(TRACER_CONCEPTS.map((c) => c.id).sort()).toEqual(
      [
        "acceptance-criteria",
        "bounded-outcome",
        "dependencies-blockers",
        "no-unresolved-design",
        "small-for-one-session",
        "verification-path",
        "vertical-tracer-bullet",
      ].sort(),
    );
  });

  it("canonical body with all concepts passes", () => {
    expect(missingTracerConcepts(CANONICAL_BODY)).toEqual([]);
    expect(validateTracerContract(CANONICAL_BODY).ok).toBe(true);
  });

  it("alias-tolerant body (Goal/Done when) passes — not brittle headings", () => {
    expect(missingTracerConcepts(ALIAS_BODY)).toEqual([]);
  });

  it("structured schema body passes via alias patterns", () => {
    expect(missingTracerConcepts(SCHEMA_BODY)).toEqual([]);
  });

  it("issue #56 excerpt itself passes (Problem/Scope/Acceptance criteria/Verification)", () => {
    const missing = missingTracerConcepts(ISSUE_56_EXCERPT);
    expect(missing, `missing: ${missing.join(", ")}`).toEqual([]);
  });

  it("detects missing concepts — empty body fails all 7", () => {
    const missing = missingTracerConcepts("");
    expect(missing).toHaveLength(7);
  });

  it("detects partially missing — no verification fails that concept", () => {
    const body = `
## Problem
bounded observable outcome

## Acceptance criteria
done when green

## Dependencies
blocked by none

no unresolved design decided
small enough for one session
vertical tracer bullet slice
`;
    // missing verification-path
    expect(missingTracerConcepts(body)).toContain("verification-path");
    expect(missingTracerConcepts(body)).not.toContain("bounded-outcome");
  });

  it("each concept can be individually missing and detected", () => {
    const bodies: Record<string, string> = {
      "bounded-outcome": `
no unresolved design decided
acceptance criteria
verification path
dependencies blocked by none
small enough for one session
vertical tracer bullet
`,
      "no-unresolved-design": `
bounded observable outcome scope
acceptance criteria
verification
dependencies
small enough for one session
vertical tracer
`,
      "acceptance-criteria": `
bounded outcome scope
no unresolved decided
verification path
dependencies
small enough for one session
vertical tracer bullet
`,
      "verification-path": `
bounded outcome scope
no unresolved decided
acceptance criteria
dependencies
small enough for one session
vertical tracer
`,
      "dependencies-blockers": `
bounded outcome scope
no unresolved decided
acceptance criteria
verification
small enough for one session
vertical tracer
`,
      "small-for-one-session": `
bounded outcome scope
no unresolved decided
acceptance criteria
verification
dependencies blocked by
vertical tracer bullet
`,
      "vertical-tracer-bullet": `
bounded outcome scope
no unresolved decided
acceptance criteria
verification
dependencies
small enough for one session
`,
    };
    for (const [id, body] of Object.entries(bodies)) {
      expect(missingTracerConcepts(body), `should miss ${id}`).toContain(id);
    }
  });
});

describe("dispatch fail-closed on agent:implement without tracer contract", () => {
  it("agent:implement without contract is ineligible (tracer contract missing)", () => {
    const i = issue({ labels: ["agent:implement"], body: "just a one-liner with no concepts" });
    const res = isEligible(i);
    expect(res.eligible).toBe(false);
    if (!res.eligible) expect(res.reason).toContain("tracer contract missing");
  });

  it("agent:implement with full contract is eligible", () => {
    const i = issue({ labels: ["agent:implement"], body: CANONICAL_BODY });
    expect(isEligible(i)).toEqual({ eligible: true });
  });

  it("ready-for-agent alone is not execution-gated by tracer (still ineligible for missing label, not tracer)", () => {
    const i = issue({ labels: ["ready-for-agent"], body: "no concepts" });
    const res = isEligible(i);
    expect(res.eligible).toBe(false);
    if (!res.eligible) expect(res.reason).toContain("missing required label");
    expect(res.eligible ? "" : (res as { reason: string }).reason).not.toContain("tracer contract");
  });

  it("ready-for-agent + agent:implement without contract still fail-closed on tracer", () => {
    const i = issue({ labels: ["ready-for-agent", "agent:implement"], body: "empty" });
    const res = isEligible(i);
    expect(res.eligible).toBe(false);
    if (!res.eligible) expect(res.reason).toContain("tracer contract missing");
  });

  it("ready-for-agent + agent:implement with alias contract passes", () => {
    const i = issue({ labels: ["ready-for-agent", "agent:implement"], body: ALIAS_BODY });
    expect(isEligible(i).eligible).toBe(true);
  });

  it("undefined body fails closed for agent:implement", () => {
    const i = issue({ labels: ["agent:implement"], body: undefined });
    const res = isEligible(i);
    expect(res.eligible).toBe(false);
    if (!res.eligible) expect(res.reason).toContain("tracer contract missing");
  });

  it("tracer is checked after Wayfinder gates (forbidden label wins); research is not forbidden — see ADR 0001", () => {
    const forbidden = issue({ labels: ["agent:implement", "wayfinder:grilling"], body: CANONICAL_BODY });
    const resForbidden = isEligible(forbidden);
    expect(resForbidden.eligible).toBe(false);
    if (!resForbidden.eligible) expect(resForbidden.reason).toContain("forbidden Wayfinder type");
    const research = issue({ labels: ["agent:implement", "wayfinder:research"], body: CANONICAL_BODY });
    expect(isEligible(research).eligible).toBe(true);
  });

  it("wayfinder:task + agent:implement without tracer still fails (triple-signal checked before tracer)", () => {
    // no body => triple-signal fails first
    const i = issue({ labels: ["agent:implement", "wayfinder:task"], body: "no map signal and no tracer" });
    const res = isEligible(i);
    expect(res.eligible).toBe(false);
    if (!res.eligible) expect(res.reason).toContain("wayfinder:task: map Notes does not authorize");
  });

  it("wayfinder:task with map signal but without tracer fails on tracer", () => {
    const i = issue({
      labels: ["agent:implement", "wayfinder:task"],
      body: "Execution is carried into this map but no other concepts",
    });
    const res = isEligible(i);
    expect(res.eligible).toBe(false);
    if (!res.eligible) expect(res.reason).toContain("tracer contract missing");
  });

  it("wayfinder:task with both map signal and tracer passes", () => {
    const body = CANONICAL_BODY + "\nExecution is carried into this map\n";
    const i = issue({ labels: ["agent:implement", "wayfinder:task"], body });
    expect(isEligible(i).eligible).toBe(true);
  });
});
