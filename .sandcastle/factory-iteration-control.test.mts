import { describe, it, expect } from "vitest";
import {
  makeIterationControl,
  planIssuesForIteration,
  parseQualificationArgs,
  resolveIterationLimit,
  planQualificationIssue,
  qualificationLifecyclePolicy,
  issueBodyForPlannedIssue,
} from "./factory-iteration-control.mts";

function issue(overrides: { number: number; title: string; body?: string }) {
  return {
    number: overrides.number,
    title: overrides.title,
    body: overrides.body ?? "",
    state: "open" as const,
    labels: ["agent:implement"],
    assignees: [],
    blockedByCount: 0,
  };
}

describe("planned issue contract", () => {
  it("recovers the selected host issue body by planned issue id", () => {
    const eligible = [issue({ number: 990007, title: "synthetic", body: "authoritative local contract" })];

    expect(issueBodyForPlannedIssue("990007", eligible)).toBe("authoritative local contract");
  });

  it("fails closed when a planned issue has no eligible host contract", () => {
    expect(() => issueBodyForPlannedIssue("990008", [])).toThrow("No eligible issue contract found for #990008");
  });

  it("fails closed when the selected host issue contract is empty", () => {
    expect(() => issueBodyForPlannedIssue("990008", [issue({ number: 990008, title: "empty" })])).toThrow(
      "No eligible issue contract found for #990008",
    );
  });
});

describe("Qualification selector", () => {
  it("picks exactly the explicitly requested eligible issue and ignores others", () => {
    const eligible = [
      issue({ number: 151, title: "issue 151" }),
      issue({ number: 152, title: "issue 152" }),
    ];
    const control = parseQualificationArgs(["node", "main.mts", "--issue", "152"]);
    expect(control).toEqual({ kind: "qualify", issueNumber: "152", issueNumbers: ["152"] });

    const resolved = makeIterationControl(10, ["node", "main.mts", "--issue", "152"]);
    const selected = planQualificationIssue(eligible, resolved);
    expect(selected.plannedIssues).toEqual([
      { id: "152", title: "issue 152", branch: "sandcastle/issue-152" },
    ]);
  });

  it("does not substitute or fallback when the requested issue is not eligible", () => {
    const eligible = [
      issue({ number: 151, title: "issue 151" }),
      issue({ number: 152, title: "issue 152" }),
    ];
    const control = makeIterationControl(10, ["node", "main.mts", "--issue", "999"]);
    const selected = planQualificationIssue(eligible, control);
    expect(selected.plannedIssues).toHaveLength(0);
  });

  it("caps outer iteration at 1 when qualification is requested", () => {
    const control = makeIterationControl(10, ["node", "main.mts", "--issue", "151"]);
    expect(control.maxIterations).toBe(1);
    expect(control.requestedIssueNumber).toBe("151");
    expect(resolveIterationLimit(10, { requestedIssueNumber: "151" })).toBe(1);
    expect(resolveIterationLimit(10, {})).toBe(10);
  });

  it("uses the qualified-only iteration plan when requested issue is present", () => {
    const eligible = [
      issue({ number: 151, title: "issue 151" }),
      issue({ number: 152, title: "issue 152" }),
    ];
    const control = makeIterationControl(10, ["node", "main.mts", "--issue", "152"]);
    const iterationPlan = planIssuesForIteration(eligible, control);
    expect(iterationPlan.mode).toBe("qualified");
    expect(iterationPlan.skipIteration).toBe(false);
    expect(iterationPlan.plannedIssues).toEqual([
      { id: "152", title: "issue 152", branch: "sandcastle/issue-152" },
    ]);
  });

  it("rejects qualification request for ineligible issue and skips iteration without substitution", () => {
    const eligible = [issue({ number: 151, title: "issue 151" }), issue({ number: 152, title: "issue 152" })];
    const control = makeIterationControl(10, ["node", "main.mts", "--issue", "999"]);
    const iterationPlan = planIssuesForIteration(eligible, control);
    expect(iterationPlan.mode).toBe("qualify-unsupported");
    expect(iterationPlan.skipIteration).toBe(true);
    expect(iterationPlan.plannedIssues).toHaveLength(0);
  });

  it("malformed --issue fails closed and disables iteration", () => {
    const control = parseQualificationArgs(["node", "main.mts", "--issue", "not-a-number"]);
    expect(control).toEqual({ kind: "invalid", reason: "not-a-number" });
    const controlFromEntry = makeIterationControl(10, ["node", "main.mts", "--issue", "not-a-number"]);
    expect(controlFromEntry.requestedIssueNumber).toBeUndefined();
    expect(controlFromEntry.qualification).toEqual({ kind: "invalid", reason: "not-a-number" });
    const eligible = [issue({ number: 151, title: "issue 151" }), issue({ number: 152, title: "issue 152" })];
    expect(planQualificationIssue(eligible, controlFromEntry).plannedIssues).toHaveLength(0);
  });

  it("missing --issue value fails closed and disables iteration", () => {
    const control = parseQualificationArgs(["node", "main.mts", "--issue"]);
    expect(control).toEqual({ kind: "invalid", reason: "--issue" });
    const controlFromEntry = makeIterationControl(10, ["node", "main.mts", "--issue"]);
    expect(controlFromEntry.qualification).toEqual({ kind: "invalid", reason: "--issue" });
    expect(planQualificationIssue([], controlFromEntry).plannedIssues).toHaveLength(0);
  });

  it("normal mode preserves default planning behavior and does not engage qualification", () => {
    const control = makeIterationControl(10, ["node", "main.mts"]);
    expect(control.maxIterations).toBe(10);
    expect(control.requestedIssueNumber).toBeUndefined();
  });

  it("qualification request that cannot be satisfied never dispatches other issues in that iteration", () => {
    const eligible = [issue({ number: 151, title: "issue 151" }), issue({ number: 152, title: "issue 152" })];
    const control = makeIterationControl(10, ["node", "main.mts", "--issue", "999"]);

    expect(control.maxIterations).toBe(1);

    let dispatchedIssues: string[] = [];
    for (let iteration = 0; iteration < control.maxIterations; iteration += 1) {
      const plan = planIssuesForIteration(eligible, control);
      if (plan.skipIteration) {
        continue;
      }
      dispatchedIssues = plan.plannedIssues.map((i) => i.id);
    }

    expect(dispatchedIssues).toHaveLength(0);
  });

  it("accepts #151 issue syntax and normalizes to 151", () => {
    const control = parseQualificationArgs(["node", "main.mts", "--issue", "#151"]);
    expect(control).toEqual({ kind: "qualify", issueNumber: "151", issueNumbers: ["151"] });
  });

  it("qualification lifecycle policy suppresses external claim/outcome/integration", () => {
    const qualify = makeIterationControl(10, ["node", "main.mts", "--issue", "151"]);
    const policy = qualificationLifecyclePolicy(qualify.qualification);
    expect(policy).toEqual({
      claimExternalState: false,
      mutateOutcomeState: false,
      integrate: false,
    });
  });

  it("supports comma-separated and repeated --issue for parallel hard-limit", () => {
    const eligible = [
      issue({ number: 151, title: "issue 151" }),
      issue({ number: 152, title: "issue 152" }),
      issue({ number: 153, title: "issue 153" }),
    ];
    const control = parseQualificationArgs(["node", "main.mts", "--issue", "151,152", "--issue", "153"]);
    expect(control).toEqual({ kind: "qualify", issueNumber: "151", issueNumbers: ["151", "152", "153"] });
    const resolved = makeIterationControl(10, ["node", "main.mts", "--issue", "151,152"]);
    expect(resolved.requestedIssueNumbers).toEqual(["151", "152"]);
    expect(resolved.maxIterations).toBe(1);
    const selected = planQualificationIssue(eligible, resolved);
    expect(selected.plannedIssues.map((p) => p.id)).toEqual(["151", "152"]);
    const researchPlanned = planQualificationIssue(eligible, makeIterationControl(10, ["node", "main.mts", "--issue", "152,151"]));
    // order preserved as requested
    expect(researchPlanned.plannedIssues.map((p) => p.id)).toEqual(["152", "151"]);
  });

  it("normal lifecycle policy retains all qualification side effects", () => {
    const control = makeIterationControl(10, ["node", "main.mts"]);
    const policy = qualificationLifecyclePolicy(control.qualification);
    expect(policy).toEqual({
      claimExternalState: true,
      mutateOutcomeState: true,
      integrate: true,
    });
  });

  it("invalid qualification uses the same no-mutation lifecycle policy as qualify", () => {
    const invalid = makeIterationControl(10, ["node", "main.mts", "--issue", "not-a-number"]);
    const policy = qualificationLifecyclePolicy(invalid.qualification);
    expect(policy).toEqual({
      claimExternalState: false,
      mutateOutcomeState: false,
      integrate: false,
    });
  });
});
