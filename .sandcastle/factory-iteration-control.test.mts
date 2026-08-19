import { describe, it, expect } from "vitest";
import {
  makeIterationControl,
  planIssuesForIteration,
  parseQualificationArgs,
  resolveIterationLimit,
  planQualificationIssue,
} from "./factory-iteration-control.mts";

function issue(overrides: { number: number; title: string }) {
  return {
    number: overrides.number,
    title: overrides.title,
    body: "",
    state: "open" as const,
    labels: ["agent:implement"],
    assignees: [],
    blockedByCount: 0,
  };
}

describe("Qualification selector", () => {
  it("picks exactly the explicitly requested eligible issue and ignores others", () => {
    const eligible = [
      issue({ number: 151, title: "issue 151" }),
      issue({ number: 152, title: "issue 152" }),
    ];
    const control = parseQualificationArgs(["node", "main.mts", "--issue", "152"]);
    expect(control.requestedIssueNumber).toBe("152");

    const selected = planQualificationIssue(eligible, control);
    expect(selected.plannedIssues).toEqual([
      { id: "152", title: "issue 152", branch: "sandcastle/issue-152" },
    ]);
  });

  it("does not substitute or fallback when the requested issue is not eligible", () => {
    const eligible = [
      issue({ number: 151, title: "issue 151" }),
      issue({ number: 152, title: "issue 152" }),
    ];
    const control = parseQualificationArgs(["node", "main.mts", "--issue", "999"]);
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
    expect(control.requestedIssueNumber).toBeUndefined();
    expect(control.invalidRequestedIssue).toBe("not-a-number");

    const controlFromEntry = makeIterationControl(10, ["node", "main.mts", "--issue", "not-a-number"]);
    expect(controlFromEntry.maxIterations).toBe(0);

    const eligible = [issue({ number: 151, title: "issue 151" }), issue({ number: 152, title: "issue 152" })];
    const iterationPlan = planIssuesForIteration(eligible, controlFromEntry);
    expect(iterationPlan.mode).toBe("qualify-invalid");
    expect(iterationPlan.skipIteration).toBe(true);
    expect(iterationPlan.plannedIssues).toHaveLength(0);
  });

  it("missing --issue value fails closed and disables iteration", () => {
    const control = parseQualificationArgs(["node", "main.mts", "--issue"]);
    expect(control.requestedIssueNumber).toBeUndefined();
    expect(control.invalidRequestedIssue).toBe("--issue");
    const controlFromEntry = makeIterationControl(10, ["node", "main.mts", "--issue"]);
    expect(controlFromEntry.maxIterations).toBe(0);
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
    expect(control.requestedIssueNumber).toBe("151");
  });
});
