import { describe, it, expect } from "vitest";
import {
  isEligible,
  filterEligible,
  branchForIssue,
  FORBIDDEN_WAYFINDER_LABELS,
} from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";
import { TRACER_BODY } from "./fixtures.mts";

function issue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 1,
    title: "Test issue",
    state: "open",
    labels: ["agent:implement"],
    assignees: [],
    body: TRACER_BODY,
    ...overrides,
  };
}

describe("branchForIssue", () => {
  it("uses deterministic sandcastle/issue-{id} format", () => {
    expect(branchForIssue(42)).toBe("sandcastle/issue-42");
    expect(branchForIssue("7")).toBe("sandcastle/issue-7");
  });
});

describe("isEligible", () => {
  it("AC1: ready-for-agent without agent:implement is not dispatched", () => {
    const i = issue({ labels: ["ready-for-agent"] });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("AC2: eligible normal implementation issue is dispatched", () => {
    const i = issue({ labels: ["agent:implement"] });
    expect(isEligible(i)).toEqual({ eligible: true });
  });

  it("AC2: eligible with ready-for-agent + agent:implement is dispatched", () => {
    const i = issue({ labels: ["ready-for-agent", "agent:implement"] });
    expect(isEligible(i).eligible).toBe(true);
  });

  it("AC3: natively blocked issue is not dispatched", () => {
    const i = issue({ blockedByCount: 1 });
    expect(isEligible(i).eligible).toBe(false);
    const ii = issue({ blockedByCount: 2 });
    expect(isEligible(ii).eligible).toBe(false);
  });

  it("AC3: blockedByCount 0 is dispatched", () => {
    const i = issue({ blockedByCount: 0 });
    expect(isEligible(i).eligible).toBe(true);
  });

  it("AC4: if every candidate is blocked, plan is empty", () => {
    const issues = [issue({ number: 1, blockedByCount: 1 }), issue({ number: 2, blockedByCount: 1 })];
    expect(filterEligible(issues)).toHaveLength(0);
  });

  it("AC5: already claimed/in-progress issue is not dispatched again (label)", () => {
    const i = issue({ labels: ["agent:implement", "agent:in-progress"] });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("AC5: already claimed via assignee is not dispatched", () => {
    const i = issue({ assignees: ["someone"] });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("AC5: already blocked label is not dispatched", () => {
    const i = issue({ labels: ["agent:implement", "agent:blocked"] });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("AC5: closed issue is not dispatched", () => {
    const i = issue({ state: "closed" });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("AC6: wayfinder:prototype cannot enter generic worker", () => {
    const i = issue({ labels: ["agent:implement", "wayfinder:prototype"] });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("AC7: wayfinder:grilling cannot enter generic worker", () => {
    const i = issue({ labels: ["agent:implement", "wayfinder:grilling"] });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("AC8: wayfinder:research is AFK via Wayfinder subagents, not Sandcastle — with agent:implement it follows normal tracer gate (see ADR 0001)", () => {
    // research is not forbidden; without agent:implement it is ineligible at gate 2, with it it must pass tracer like any other AFK ticket.
    const withoutImplement = issue({ labels: ["wayfinder:research"], body: "Execution is carried into this map" });
    expect(isEligible(withoutImplement).eligible).toBe(false);
    const withImplementNoTracer = issue({ labels: ["agent:implement", "wayfinder:research"], body: "no tracer" });
    expect(isEligible(withImplementNoTracer).eligible).toBe(false);
    const r = isEligible(withImplementNoTracer);
    if (!r.eligible) expect(r.reason).toContain("tracer contract missing");
  });

  it("wayfinder:map and preserve-futures also blocked", () => {
    for (const label of FORBIDDEN_WAYFINDER_LABELS) {
      const i = issue({ labels: ["agent:implement", label] });
      expect(isEligible(i).eligible, label).toBe(false);
    }
  });

  it("AC9: two eligible independent issues can be scheduled concurrently", () => {
    const issues = [issue({ number: 1 }), issue({ number: 2 })];
    const eligible = filterEligible(issues);
    expect(eligible).toHaveLength(2);
  });

  it("wayfinder:task is allowed (seam for future routing)", () => {
    // wayfinder:task is AFK-task — allowed if explicitly authorized via triple-signal + tracer.
    const i = issue({ labels: ["agent:implement", "wayfinder:task"], body: TRACER_BODY });
    expect(isEligible(i).eligible).toBe(true);
  });

  it("wayfinder:task without Notes is not dispatched (triple-signal gate)", () => {
    const i = issue({ labels: ["agent:implement", "wayfinder:task"], body: "Part of #14" });
    const res = isEligible(i);
    expect(res.eligible).toBe(false);
    if (!res.eligible) {
      expect(res.reason).toContain("wayfinder:task: map Notes does not authorize");
    }
  });

  it("wayfinder:task without body is not dispatched", () => {
    const i = issue({ labels: ["agent:implement", "wayfinder:task"], body: undefined });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("wayfinder:task without agent:implement remains ineligible (missing REQUIRED_LABEL)", () => {
    const i = issue({ labels: ["wayfinder:task"], body: "Execution is carried into this map" });
    expect(isEligible(i).eligible).toBe(false);
  });

  it("duplicate prevention: re-running dispatcher on same issue does not re-dispatch if already in-progress", () => {
    const original = issue({ number: 5, labels: ["agent:implement"] });
    expect(isEligible(original).eligible).toBe(true);
    // After claim, labels include in-progress
    const claimed = issue({ number: 5, labels: ["agent:implement", "agent:in-progress"], assignees: ["bot"] });
    expect(isEligible(claimed).eligible).toBe(false);
  });
});
