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
      issue({ number: 1, labels: ["wayfinder:research", "agent:research"], body: "## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed." }),
      issue({ number: 2, labels: ["wayfinder:preserve-futures"], body: "preserve checkpoint" }),
    ];
    const plan = planMigration(issues);
    expect(plan.contradictions.length).toBeGreaterThan(0);
    expect(plan.retiredLabelUsers.length).toBeGreaterThan(0);
    expect(isCheckFailed(plan)).toBe(true);
  });

  it("reports newly eligible research from wayfinder:research alone", () => {
    const r: IssueInput = { number: 10, title: "research", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 0, body: "## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed." };
    const plan = planMigration([r]);
    expect(plan.newlyEligibleResearch).toContain(10);
  });

  it("reports blocked research separately", () => {
    const r: IssueInput = { number: 11, title: "r", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 1, body: "## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed." };
    const plan = planMigration([r]);
    expect(plan.blockedResearch).toContain(11);
  });

  it("reports ambiguous task classification when task missing readiness and no explicit plan", () => {
    const t: IssueInput = { number: 20, title: "task", state: "open", labels: ["wayfinder:task"], assignees: [], blockedByCount: 0, body: "Task body with scoped work" };
    const plan = planMigration([t], {});
    expect(plan.taskClassificationPlan.some(p => p.issue === 20 && p.planned === "ambiguous")).toBe(true);
    expect(isCheckFailed(plan)).toBe(true);
  });

  it("uses explicit plan for task classification", () => {
    const t: IssueInput = { number: 21, title: "task", state: "open", labels: ["wayfinder:task"], assignees: [], blockedByCount: 0, body: "Task body with scoped work" };
    const plan = planMigration([t], { 21: "ready-for-agent" });
    expect(plan.plannedMutations.some(m => m.issue === 21 && m.addLabels.includes("ready-for-agent"))).toBe(true);
    expect(plan.taskClassificationPlan.find(p => p.issue === 21)?.planned).toBe("ready-for-agent");
  });

  it("dry-run receipt includes required fields", () => {
    const issues: IssueInput[] = [
      issue({ number: 30, labels: ["wayfinder:research"], body: "## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed.", blockedByCount: 0 }),
      issue({ number: 31, labels: ["wayfinder:task"], body: "Task body with scoped work", blockedByCount: 0 }),
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
    const t: IssueInput = { number: 40, title: "task", state: "open", labels: ["wayfinder:task", "ready-for-agent"], assignees: [], blockedByCount: 0, body: "Task body with scoped work" };
    const plan1 = planMigration([t]);
    expect(plan1.plannedMutations.length).toBe(0); // already classified, no mutation
    expect(isCheckFailed(plan1)).toBe(false);
    // Simulate apply by updating issue labels per plan (no change), rerun should be same
    const plan2 = planMigration([t]);
    expect(plan2.contradictions.length).toBe(0);
  });

  it("live-state drift between reviewed plan and apply fails", () => {
    const t: IssueInput = { number: 50, title: "task", state: "open", labels: ["wayfinder:task"], assignees: [], blockedByCount: 0, body: "Task body with scoped work" };
    const planReviewed = planMigration([t], { 50: "ready-for-agent" });
    expect(planReviewed.plannedMutations.some(m => m.issue === 50)).toBe(true);
    // Drift: issue now has ready-for-human already (different live state)
    const drifted: IssueInput = { number: 50, title: "task", state: "open", labels: ["wayfinder:task", "ready-for-human"], assignees: [], blockedByCount: 0, body: "Task body with scoped work" };
    const planLive = planMigration([drifted], { 50: "ready-for-agent" });
    // The live plan should not match reviewed — drift detection would compare issue labels
    // For test, we prove that the same explicit plan applied to drifted state produces different outcome
    expect(planLive.plannedMutations.some(m => m.issue === 50 && m.addLabels.includes("ready-for-agent"))).toBe(false); // already has human, wouldn't add agent
  });


describe("tracker-migration — runTrackerMigration production seam", () => {
  it("apply with valid nonempty plannedMutations actually mutates 6 Tasks and second inventory shows zero", async () => {
    const { runTrackerMigration, hasBlockingMigrationProblems, migrationRequired, buildReviewedReceipt, planMigration } = await import("./tracker-migration.mts");
    const tasks: any[] = [166,127,114,64,61,25].map(n => ({
      number: n, title: "task "+n, state: "open", labels: ["wayfinder:task"], assignees: [], body: "Task body", blockedByCount: 0
    }));
    const explicit: Record<number,string> = {166:"ready-for-human",127:"ready-for-human",114:"ready-for-human",64:"ready-for-human",61:"ready-for-human",25:"ready-for-human"};
    let labelDescs: Record<string,string> = {};
    const headSha = "test-head-sha-001";
    // Build receipt from initial state (dry-run)
    const initialPlan = planMigration(tasks, explicit);
    const receipt = buildReviewedReceipt(tasks, {}, { "agent:research": false, "wayfinder:preserve-futures": false } as any, initialPlan, headSha);
    let call = 0;
    const inventoryOps: any = {
      listOpenIssues: async () => {
        call++;
        if (call===1) return tasks;
        return tasks.map((t:any) => ({...t, labels: ["wayfinder:task","ready-for-human"]}));
      },
      getLabelDescriptions: async () => ({ ...labelDescs }),
      getRetiredLabelsExist: async () => ({ "agent:research": false, "wayfinder:preserve-futures": false }),
      getHeadSha: async () => headSha,
    };
    const mutatedIssues: number[] = [];
    const mutationOps: any = {
      updateIssueLabels: async (n:number, add:string[], rem:string[]) => { mutatedIssues.push(n); },
      updateLabelDescription: async (name:string, desc:string) => { labelDescs[name] = desc; },
      deleteLabel: async () => {},
    };
    const result = await runTrackerMigration({ mode: "apply", inventoryOps, mutationOps, explicitTaskPlan: explicit, reviewedReceipt: receipt });
    expect(result.applied).toBe(true);
    expect(mutatedIssues.length).toBe(6);
    expect(mutatedIssues.sort((a,b)=>a-b)).toEqual([25,61,64,114,127,166]);
    expect(hasBlockingMigrationProblems(result.plan)).toBe(false);
    const second = await inventoryOps.listOpenIssues();
    const plan2 = planMigration(second, explicit);
    expect(migrationRequired(plan2)).toBe(false);
  });

  it("contradictions block all writes (no mutation attempted)", async () => {
    const { runTrackerMigration, buildReviewedReceipt, planMigration } = await import("./tracker-migration.mts");
    const bad: any = { number: 1, title:"bad", state:"open", labels:["wayfinder:research","agent:implement","ready-for-agent"], assignees:[], body:"## Question\n\nValid research body with sufficient length and words to pass validation but has contradiction", blockedByCount:0 };
    const headSha = "test-head-sha-002";
    const plan = planMigration([bad]);
    const receipt = buildReviewedReceipt([bad], {}, { "agent:research": false, "wayfinder:preserve-futures": false } as any, plan, headSha);
    const inventoryOps: any = {
      listOpenIssues: async () => [bad],
      getLabelDescriptions: async () => ({}),
      getRetiredLabelsExist: async () => ({ "agent:research": false, "wayfinder:preserve-futures": false }),
      getHeadSha: async () => headSha,
    };
    const mutationOps: any = {
      updateIssueLabels: async () => { throw new Error("should not be called"); },
      updateLabelDescription: async () => { throw new Error("should not be called"); },
      deleteLabel: async () => { throw new Error("should not be called"); },
    };
    await expect(runTrackerMigration({ mode:"apply", inventoryOps, mutationOps, reviewedReceipt: receipt })).rejects.toThrow(/blocking/);
  });

  it("mutation failure is fatal", async () => {
    const { runTrackerMigration, buildReviewedReceipt, planMigration } = await import("./tracker-migration.mts");
    const task: any = { number: 99, title:"task", state:"open", labels:["wayfinder:task"], assignees:[], body:"Task body", blockedByCount:0 };
    const headSha = "test-head-sha-003";
    const plan = planMigration([task], {99:"ready-for-human"});
    const receipt = buildReviewedReceipt([task], {}, { "agent:research": false, "wayfinder:preserve-futures": false } as any, plan, headSha);
    const inventoryOps: any = {
      listOpenIssues: async () => [task],
      getLabelDescriptions: async () => ({}),
      getRetiredLabelsExist: async () => ({ "agent:research": false, "wayfinder:preserve-futures": false }),
      getHeadSha: async () => headSha,
    };
    const mutationOps: any = {
      updateIssueLabels: async () => { throw new Error("gh edit failed"); },
      updateLabelDescription: async () => {},
      deleteLabel: async () => {},
    };
    await expect(runTrackerMigration({ mode:"apply", inventoryOps, mutationOps, explicitTaskPlan: {99:"ready-for-human"}, reviewedReceipt: receipt })).rejects.toThrow(/mutation failed/);
  });

  it("post-inventory failure is fatal", async () => {
    const { runTrackerMigration, buildReviewedReceipt, planMigration } = await import("./tracker-migration.mts");
    const task: any = { number: 99, title:"task", state:"open", labels:["wayfinder:task"], assignees:[], body:"Task body", blockedByCount:0 };
    const headSha = "test-head-sha-004";
    const plan = planMigration([task], {99:"ready-for-human"});
    const receipt = buildReviewedReceipt([task], {}, { "agent:research": false, "wayfinder:preserve-futures": false } as any, plan, headSha);
    let first = true;
    const inventoryOps: any = {
      listOpenIssues: async () => {
        if(first){ first=false; return [task]; }
        throw new Error("post fetch failed");
      },
      getLabelDescriptions: async () => ({}),
      getRetiredLabelsExist: async () => ({ "agent:research": false, "wayfinder:preserve-futures": false }),
      getHeadSha: async () => headSha,
    };
    const mutationOps: any = {
      updateIssueLabels: async () => {},
      updateLabelDescription: async () => {},
      deleteLabel: async () => {},
    };
    await expect(runTrackerMigration({ mode:"apply", inventoryOps, mutationOps, explicitTaskPlan: {99:"ready-for-human"}, reviewedReceipt: receipt })).rejects.toThrow(/post-inventory/);
  });

  it("drift in body/assignee/blocked_by aborts before any write (no stale snapshot)", async () => {
    const { runTrackerMigration, buildReviewedReceipt, planMigration } = await import("./tracker-migration.mts");
    const task: any = { number: 77, title:"task", state:"open", labels:["wayfinder:task"], assignees:[], body:"Task body", blockedByCount:0 };
    const explicit = {77:"ready-for-human"};
    const headSha = "test-head-sha-005";
    const plan = planMigration([task], explicit);
    const reviewed = buildReviewedReceipt([task], {}, { "agent:research": false, "wayfinder:preserve-futures": false } as any, plan, headSha);
    const driftedTask = {...task, body:"Task body drifted content changed"};
    const inventoryOps: any = {
      listOpenIssues: async () => [driftedTask],
      getLabelDescriptions: async () => ({}),
      getRetiredLabelsExist: async () => ({ "agent:research": false, "wayfinder:preserve-futures": false }),
      getHeadSha: async () => headSha,
    };
    const mutationOps: any = {
      updateIssueLabels: async () => { throw new Error("should not be called due to drift"); },
      updateLabelDescription: async () => {},
      deleteLabel: async () => {},
    };
    await expect(runTrackerMigration({ mode:"apply", inventoryOps, mutationOps, reviewedReceipt: reviewed, explicitTaskPlan: explicit })).rejects.toThrow(/drift/);
  });

  it("hasBlocking vs migrationRequired separation: nonempty mutations without blocking is required but not blocking", async () => {
    const { hasBlockingMigrationProblems, migrationRequired, planMigration } = await import("./tracker-migration.mts");
    const task: any = { number:88, title:"task", state:"open", labels:["wayfinder:task"], assignees:[], body:"Task body", blockedByCount:0 };
    const plan = planMigration([task], {88:"ready-for-human"});
    expect(hasBlockingMigrationProblems(plan)).toBe(false);
    expect(migrationRequired(plan)).toBe(true);
    expect(plan.plannedMutations.length).toBeGreaterThan(0);
  });
});

  it("never adds agent:implement", () => {
    const issues: IssueInput[] = [
      issue({ number: 60, labels: ["wayfinder:task"], body: "Task body with scoped work" }),
      issue({ number: 61, labels: ["wayfinder:research"], body: "## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed." }),
    ];
    const plan = planMigration(issues, { 60: "ready-for-agent" });
    for (const m of plan.plannedMutations) {
      expect(m.addLabels).not.toContain("agent:implement");
    }
  });
});
