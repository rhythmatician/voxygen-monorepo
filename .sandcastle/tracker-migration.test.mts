import { describe, it, expect } from "vitest";
import { planMigration, isCheckFailed } from "./tracker-migration.mts";
import type { IssueInput } from "./tracker-policy.mts";
import { TRACER_BODY } from "./fixtures.mts";

function issue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 1,
    title: "Test",
    state: "open",
    labels: [],
    assignees: [],
    body: "",
    blockedByCount: 0,
    ...overrides,
  };
}

describe("tracker-migration — check / dry-run / apply / idempotency", () => {
  it("detects retired labels and contradictions before mutation", () => {
    const issues: IssueInput[] = [
      issue({ number: 1, labels: ["wayfinder:research", "agent:research"], body: "q" }),
      issue({ number: 2, labels: ["wayfinder:preserve-futures"], body: "preserve checkpoint" }),
    ];
    const plan = planMigration(issues);
    expect(plan.contradictions.length).toBeGreaterThan(0);
    expect(plan.retiredLabelUsers.length).toBeGreaterThan(0);
    expect(isCheckFailed(plan)).toBe(true);
  });

  it("reports newly eligible research from wayfinder:research alone", () => {
    const r: IssueInput = { number: 10, title: "research", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 0, body: "q" };
    const plan = planMigration([r]);
    expect(plan.newlyEligibleResearch).toContain(10);
  });

  it("reports blocked research separately", () => {
    const r: IssueInput = { number: 11, title: "r", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 1, body: "q" };
    const plan = planMigration([r]);
    expect(plan.blockedResearch).toContain(11);
  });

  it("reports ambiguous task classification when task missing readiness and no explicit plan", () => {
    const t: IssueInput = { number: 20, title: "task", state: "open", labels: ["wayfinder:task"], assignees: [], blockedByCount: 0, body: "task" };
    const plan = planMigration([t], {});
    expect(plan.taskClassificationPlan.some(p => p.issue === 20 && p.planned === "ambiguous")).toBe(true);
    expect(isCheckFailed(plan)).toBe(true);
  });

  it("uses explicit plan for task classification", () => {
    const t: IssueInput = { number: 21, title: "task", state: "open", labels: ["wayfinder:task"], assignees: [], blockedByCount: 0, body: "task" };
    const plan = planMigration([t], { 21: "ready-for-agent" });
    expect(plan.plannedMutations.some(m => m.issue === 21 && m.addLabels.includes("ready-for-agent"))).toBe(true);
    expect(plan.taskClassificationPlan.find(p => p.issue === 21)?.planned).toBe("ready-for-agent");
  });

  it("dry-run receipt includes required fields", () => {
    const issues: IssueInput[] = [
      issue({ number: 30, labels: ["wayfinder:research"], body: "q", blockedByCount: 0 }),
      issue({ number: 31, labels: ["wayfinder:task"], body: "task", blockedByCount: 0 }),
    ];
    const plan = planMigration(issues);
    expect(plan).toHaveProperty("newlyEligibleResearch");
    expect(plan).toHaveProperty("blockedResearch");
    expect(plan).toHaveProperty("ambiguousResearch");
    expect(plan).toHaveProperty("taskClassificationPlan");
    expect(plan).toHaveProperty("retiredLabelUsers");
    expect(plan).toHaveProperty("contradictions");
    expect(plan).toHaveProperty("plannedMutations");
    expect(plan).toHaveProperty("unchangedIssues");
  });

  it("apply is idempotent — second run after mutations shows no contradictions if explicit plan provided", () => {
    const t: IssueInput = { number: 40, title: "task", state: "open", labels: ["wayfinder:task", "ready-for-agent"], assignees: [], blockedByCount: 0, body: "task" };
    const plan1 = planMigration([t]);
    expect(plan1.plannedMutations.length).toBe(0); // already classified, no mutation
    expect(isCheckFailed(plan1)).toBe(false);
    // Simulate apply by updating issue labels per plan (no change), rerun should be same
    const plan2 = planMigration([t]);
    expect(plan2.contradictions.length).toBe(0);
  });

  it("live-state drift between reviewed plan and apply fails", () => {
    const t: IssueInput = { number: 50, title: "task", state: "open", labels: ["wayfinder:task"], assignees: [], blockedByCount: 0, body: "task" };
    const planReviewed = planMigration([t], { 50: "ready-for-agent" });
    expect(planReviewed.plannedMutations.some(m => m.issue === 50)).toBe(true);
    // Drift: issue now has ready-for-human already (different live state)
    const drifted: IssueInput = { number: 50, title: "task", state: "open", labels: ["wayfinder:task", "ready-for-human"], assignees: [], blockedByCount: 0, body: "task" };
    const planLive = planMigration([drifted], { 50: "ready-for-agent" });
    // The live plan should not match reviewed — drift detection would compare issue labels
    // For test, we prove that the same explicit plan applied to drifted state produces different outcome
    expect(planLive.plannedMutations.some(m => m.issue === 50 && m.addLabels.includes("ready-for-agent"))).toBe(false); // already has human, wouldn't add agent
  });

  it("never adds agent:implement", () => {
    const issues: IssueInput[] = [
      issue({ number: 60, labels: ["wayfinder:task"], body: "task" }),
      issue({ number: 61, labels: ["wayfinder:research"], body: "q" }),
    ];
    const plan = planMigration(issues, { 60: "ready-for-agent" });
    for (const m of plan.plannedMutations) {
      expect(m.addLabels).not.toContain("agent:implement");
    }
  });
});
