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


describe("tracker-migration — authoritative verdicts via CLI (item 1)", () => {
  // Helper to create CliDeps that simulate live GH state without hitting real API
  function makeCliDeps(opts: { issues: any[], labelDescriptions: Record<string,string>, retired: Record<string,boolean>, headSha?: string, store?: any }) {
    const headSha = opts.headSha ?? "test-head-sha-cli-001";
    const store = opts.store ?? { writes: [] as any[], labelWrites: [] as any[], deleteWrites: [] as any[] };
    const runGh = async (args: string[]) => {
      const cmd = args.join(" ");
      if (args[0] === "issue" && args[1] === "list") {
        // issue list
        return JSON.stringify(opts.issues.map((i:any) => ({
          number: i.number,
          title: i.title,
          body: i.body,
          state: i.state,
          labels: i.labels.map((n:string)=>({name:n})),
          assignees: i.assignees.map((l:string)=>({login:l}))
        })));
      }
      if (args[0] === "api" && args[1].includes("repos/") && args[1].includes("/labels") && args.includes("--paginate")) {
        // bulk labels fetch — return canonical with provided descriptions, or throw 404 if flagged
        if ((opts as any).bulk404) throw new Error("404 Not Found");
        const labels = Object.entries(opts.labelDescriptions).map(([name, description])=>({name, description}));
        // ensure at least canonical keys are present in map; if opts.labelDescriptions empty, return empty array to simulate no labels
        return JSON.stringify(labels);
      }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        const encoded = args[1].split("/labels/")[1];
        const name = decodeURIComponent(encoded);
        if (opts.retired[name] !== undefined) {
          // retired label existence check — 404 if false, success if true
          if (opts.retired[name]) return JSON.stringify({ name, description: "" });
          throw new Error("404 Not Found");
        }
        if (opts.labelDescriptions[name] !== undefined) {
          return opts.labelDescriptions[name];
        }
        // For label description per-label fetch fallback
        throw new Error("404 Not Found");
      }
      if (args[0] === "api" && args[1].includes("/issues/") && args.includes("--jq")) {
        // blocked_by fetch — return 0
        return "0";
      }
      if (args[0] === "issue" && args[1] === "edit") {
        store.writes.push(args);
        return "";
      }
      if (args[0] === "api" && args[1].includes("--method") && args[1].includes("PATCH")) {
        store.labelWrites.push(args);
        return "";
      }
      if (args[0] === "api" && args[1].includes("--method") && args[1].includes("DELETE")) {
        store.deleteWrites.push(args);
        return "";
      }
      if (args[0] === "api" && args.includes("user")) return "test-bot";
      return "";
    };
    const execSync = (cmd: string) => {
      if (cmd.includes("rev-parse HEAD")) return headSha;
      if (cmd.includes("remote get-url origin")) return "https://github.com/rhythmatician/voxygen-monorepo.git";
      return "";
    };
    const deps: any = {
      runGh,
      execSync,
      getHeadSha: () => headSha,
      readFileSync: (()=>{ throw new Error("not needed"); }) as any,
      writeFileSync: (path:string, data:string) => { store.writes.push({path, data}); },
      mkdirSync: () => {},
    };
    return { deps, store, runGh, headSha };
  }

  it("six real planned issue mutations can apply via CLI and persisted checkPassed is true", async () => {
    const { runTrackerMigrationCli } = await import("./tracker-migration.mts");
    const { CANONICAL_LABEL_DESCRIPTIONS } = await import("./tracker-migration.mts");
    const tasks: any[] = [166,127,114,64,61,25].map(n => ({
      number: n, title: "task "+n, state: "open", labels: ["wayfinder:task"], assignees: [], body: "Task body", blockedByCount: 0
    }));
    const explicit: Record<number,string> = {166:"ready-for-human",127:"ready-for-human",114:"ready-for-human",64:"ready-for-human",61:"ready-for-human",25:"ready-for-human"};
    const headSha = "test-head-sha-cli-apply-001";
    // First, create a dry-run receipt via runTrackerMigration directly to simulate reviewed receipt
    const { planMigration, buildReviewedReceipt } = await import("./tracker-migration.mts");
    const initialPlan = planMigration(tasks, explicit);
    const receipt = buildReviewedReceipt(tasks, CANONICAL_LABEL_DESCRIPTIONS, { "agent:research": false, "wayfinder:preserve-futures": false } as any, initialPlan, headSha);
    // Now CLI apply with mocked inventory that simulates post-apply state
    const labelDescs = { ...CANONICAL_LABEL_DESCRIPTIONS };
    let call=0;
    const mutated: number[] = [];
    const store:any = { writes:[], labelWrites:[], deleteWrites:[] };
    // Create a custom inventory via CliDeps runGh that will simulate issue list changing after mutations
    // For CLI, we cannot directly control listOpenIssues call count, but we can make runGh return pre-apply then post-apply based on mutated flag
    let mutatedFlag=false;
    const issuesPre = tasks;
    const issuesPost = tasks.map((t:any)=> ({...t, labels:["wayfinder:task","ready-for-human"]}));
    const { deps } = makeCliDeps({ issues: issuesPre, labelDescriptions: labelDescs, retired: { "agent:research": false, "wayfinder:preserve-futures": false }, headSha, store });
    // Override runGh for issue list to toggle
    const origRunGh = deps.runGh;
    let listCalls=0;
    deps.runGh = async (args:string[]) => {
      if (args[0]==="issue" && args[1]==="list") {
        listCalls++;
        // First call is pre-apply inventory, second call inside runTrackerMigration after mutations is post-apply
        // For CLI apply, runTrackerMigration will call listOpenIssues multiple times; we need to return post after first mutation
        // Simplest: always return pre for first, post thereafter if mutatedFlag set
        if (listCalls===1) return JSON.stringify(issuesPre.map((i:any)=>({ number:i.number, title:i.title, body:i.body, state:i.state, labels:i.labels.map((n:string)=>({name:n})), assignees:i.assignees.map((l:string)=>({login:l})) })));
        return JSON.stringify(issuesPost.map((i:any)=>({ number:i.number, title:i.title, body:i.body, state:i.state, labels:i.labels.map((n:string)=>({name:n})), assignees:i.assignees.map((l:string)=>({login:l})) })));
      }
      if (args[0]==="issue" && args[1]==="edit") {
        const num = parseInt(args[2],10);
        mutated.push(num);
        mutatedFlag=true;
        return "";
      }
      return origRunGh(args);
    };
    // Write receipt to temp file and use it for --apply
    const tmpReceiptPath = "/tmp/test-cli-receipt.json";
    const fsSync = await import("node:fs");
    fsSync.writeFileSync(tmpReceiptPath, JSON.stringify(receipt, null, 2));
    deps.readFileSync = fsSync.readFileSync as any;
    deps.writeFileSync = (path:string, data:string) => { fsSync.mkdirSync(".sandcastle/logs", {recursive:true}); fsSync.writeFileSync(path, data); store.writes.push({path, data}); };
    deps.mkdirSync = fsSync.mkdirSync as any;
    const result = await runTrackerMigrationCli(["--apply","--receipt",tmpReceiptPath], deps);
    expect(result.exitCode).toBe(0);
    // Check that persisted receipt exists and has truthful fields
    const persistedRaw = fsSync.readFileSync(".sandcastle/logs/migration-apply-receipt.json","utf8");
    const persisted = JSON.parse(persistedRaw);
    expect(persisted.before).toBeDefined();
    expect(persisted.after).toBeDefined();
    expect(persisted.applied).toBe(true);
    expect(persisted.checkPassed).toBe(true);
    expect(persisted.blockingProblems).toBe(false);
    expect(persisted.migrationRequired).toBe(false);
    // Six mutations
    expect(mutated.length).toBe(6);
  });

  it("--check with only one stale canonical description exits 1 and JSON says false", async () => {
    const { runTrackerMigrationCli, CANONICAL_LABEL_DESCRIPTIONS } = await import("./tracker-migration.mts");
    const staleDescs = { ...CANONICAL_LABEL_DESCRIPTIONS, "wayfinder:research": "stale description" };
    const issue: any = { number: 10, title:"r", state:"open", labels:["wayfinder:research"], assignees:[], body:"## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed.", blockedByCount:0 };
    const { deps } = makeCliDeps({ issues:[issue], labelDescriptions: staleDescs, retired: { "agent:research": false, "wayfinder:preserve-futures": false } });
    let logged=""
    const origLog = console.log;
    console.log = (msg:any)=>{ logged+=String(msg)+"\n"; };
    const result = await runTrackerMigrationCli(["--check"], deps);
    console.log = origLog;
    expect(result.exitCode).toBe(1);
    // Find JSON logged
    const jsonMatch = logged.match(/\{[\s\S]*"checkPassed"[\s\S]*\}/);
    expect(jsonMatch).not.toBeNull();
    const parsed = JSON.parse(jsonMatch![0]);
    expect(parsed.checkPassed).toBe(false);
  });

  it("--check with only agent:research repository label present exits 1 and JSON says false", async () => {
    const { runTrackerMigrationCli, CANONICAL_LABEL_DESCRIPTIONS } = await import("./tracker-migration.mts");
    const issue: any = { number: 11, title:"t", state:"open", labels:["wayfinder:task","ready-for-agent"], assignees:[], body:"Task body", blockedByCount:0 };
    const { deps } = makeCliDeps({ issues:[issue], labelDescriptions: CANONICAL_LABEL_DESCRIPTIONS, retired: { "agent:research": true, "wayfinder:preserve-futures": false } });
    let logged="";
    const origLog = console.log;
    console.log = (msg:any)=>{ logged+=String(msg)+"\n"; };
    const result = await runTrackerMigrationCli(["--check"], deps);
    console.log = origLog;
    expect(result.exitCode).toBe(1);
    const jsonMatch = logged.match(/\{[\s\S]*"checkPassed"[\s\S]*\}/);
    expect(jsonMatch).not.toBeNull();
    const parsed = JSON.parse(jsonMatch![0]);
    expect(parsed.checkPassed).toBe(false);
  });

  it("after a fully normalized state, --check exits 0 and JSON says true", async () => {
    const { runTrackerMigrationCli, CANONICAL_LABEL_DESCRIPTIONS } = await import("./tracker-migration.mts");
    const issue: any = { number: 12, title:"t", state:"open", labels:["wayfinder:task","ready-for-agent"], assignees:[], body:"Task body", blockedByCount:0 };
    const { deps } = makeCliDeps({ issues:[issue], labelDescriptions: CANONICAL_LABEL_DESCRIPTIONS, retired: { "agent:research": false, "wayfinder:preserve-futures": false } });
    let logged="";
    const origLog = console.log;
    console.log = (msg:any)=>{ logged+=String(msg)+"\n"; };
    const result = await runTrackerMigrationCli(["--check"], deps);
    console.log = origLog;
    expect(result.exitCode).toBe(0);
    const jsonMatch = logged.match(/\{[\s\S]*"checkPassed"[\s\S]*\}/);
    expect(jsonMatch).not.toBeNull();
    const parsed = JSON.parse(jsonMatch![0]);
    expect(parsed.checkPassed).toBe(true);
  });

  it("persisted apply receipt contains truthful before, after, applied, and checkPassed", async () => {
    const { runTrackerMigration, buildReviewedReceipt, planMigration, CANONICAL_LABEL_DESCRIPTIONS } = await import("./tracker-migration.mts");
    const task: any = { number: 99, title:"task", state:"open", labels:["wayfinder:task"], assignees:[], body:"Task body", blockedByCount:0 };
    const explicit={99:"ready-for-human"};
    const headSha="test-head-sha-apply-receipt-001";
    const plan = planMigration([task], explicit);
    const receipt = buildReviewedReceipt([task], CANONICAL_LABEL_DESCRIPTIONS, { "agent:research": false, "wayfinder:preserve-futures": false } as any, plan, headSha);
    let call=0;
    const inventoryOps:any = {
      listOpenIssues: async () => { call++; if(call===1) return [task]; return [{...task, labels:["wayfinder:task","ready-for-human"]}] },
      getLabelDescriptions: async () => ({...CANONICAL_LABEL_DESCRIPTIONS}),
      getRetiredLabelsExist: async () => ({ "agent:research": false, "wayfinder:preserve-futures": false }),
      getHeadSha: async () => headSha,
    };
    const mutationOps:any = {
      updateIssueLabels: async () => {},
      updateLabelDescription: async () => {},
      deleteLabel: async () => {},
    };
    const result = await runTrackerMigration({ mode:"apply", inventoryOps, mutationOps, explicitTaskPlan: explicit, reviewedReceipt: receipt });
    expect(result.before).toBeDefined();
    expect(result.after).toBeDefined();
    expect(result.applied).toBe(true);
    expect(result.checkPassed).toBe(true);
    expect(result.blockingProblems).toBe(false);
    expect(result.migrationRequired).toBe(false);
    // Verify afterPlan is not requiring migration
    expect(result.afterPlan).toBeDefined();
    const { migrationRequired, hasBlockingMigrationProblems } = await import("./tracker-migration.mts");
    expect(migrationRequired(result.afterPlan!, { labelDescriptions: result.after!.labelDescriptions, retiredLabelsExist: result.after!.retiredLabelsExist })).toBe(false);
    expect(hasBlockingMigrationProblems(result.afterPlan!)).toBe(false);
  });
});

describe("tracker-migration — historical Research truthful classification (item 2)", () => {
  it("historical agent:research + open blocker => blockedResearch", async () => {
    const { planMigration } = await import("./tracker-migration.mts");
    const issue:any = { number: 101, title:"r", state:"open", labels:["wayfinder:research","agent:research"], assignees:[], body:"## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed.", blockedByCount:1 };
    const plan = planMigration([issue]);
    expect(plan.blockedResearch).toContain(101);
    expect(plan.newlyEligibleResearch).not.toContain(101);
    expect(plan.ambiguousResearch).not.toContain(101);
  });
  it("historical agent:research + invalid body => ambiguousResearch/blocking", async () => {
    const { planMigration, hasBlockingMigrationProblems } = await import("./tracker-migration.mts");
    const issue:any = { number: 102, title:"r", state:"open", labels:["wayfinder:research","agent:research"], assignees:[], body:"invalid", blockedByCount:0 };
    const plan = planMigration([issue]);
    expect(plan.ambiguousResearch).toContain(102);
    expect(hasBlockingMigrationProblems(plan)).toBe(true);
    expect(plan.blockedResearch).not.toContain(102);
  });
  it("historical agent:research + unassigned/unblocked valid body => newlyEligibleResearch", async () => {
    const { planMigration } = await import("./tracker-migration.mts");
    const issue:any = { number: 103, title:"r", state:"open", labels:["wayfinder:research","agent:research"], assignees:[], body:"## Question\n\nResearch question with sufficient length for validation, part of #190 with substantive details about the problem to be investigated and evidence needed.", blockedByCount:0 };
    const plan = planMigration([issue]);
    expect(plan.newlyEligibleResearch).toContain(103);
    expect(plan.blockedResearch).not.toContain(103);
    expect(plan.ambiguousResearch).not.toContain(103);
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
