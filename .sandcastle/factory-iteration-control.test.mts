import { describe, it, expect } from "vitest";
import {
  makeIterationControl,
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

  it("normal mode preserves default planning behavior and does not engage qualification", () => {
    const control = makeIterationControl(10, ["node", "main.mts"]);
    expect(control.maxIterations).toBe(10);
    expect(control.requestedIssueNumber).toBeUndefined();
  });

  it("accepts #151 issue syntax and normalizes to 151", () => {
    const control = parseQualificationArgs(["node", "main.mts", "--issue", "#151"]);
    expect(control.requestedIssueNumber).toBe("151");
  });
});

