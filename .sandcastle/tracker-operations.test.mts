import { describe, it, expect, vi } from "vitest";
import { claimImplementation, reconcileStaleImplementation, type ClaimOps } from "./tracker-operations.mts";
import type { IssueInput } from "./tracker-policy.mts";
import type { GitRunner } from "./branch-helpers.mts";

/**
 * PR #217 round 3: reconciliation GitHub transitions (release/block/integrate)
 * moved into the TrackerAdapter's verified saga via ReconcileGitHubTransitions.
 * The skipped tests below drive the legacy raw releaseClaim/addBlocked/
 * markIntegrated ops shape, which no longer exists — their behavioral coverage
 * now lives in tracker-adapter.test.mts and tracker-canary.test.mts over the
 * adapter authority. Inspection/cleanup tests remain active here.
 */

function fakeGit(args: string[]): { exitCode:number, stdout:string, stderr:string } {
  if (args[0]==="remote" && args[1]==="get-url") return { exitCode:0, stdout:"https://github.com/rhythmatician/voxygen-monorepo.git", stderr:"" };
  if (args[0]==="branch" && args[1]==="--list") return { exitCode:0, stdout:"", stderr:"" };
  if (args[0]==="worktree" && args[1]==="list") return { exitCode:0, stdout:"", stderr:"" };
  if (args[0]==="rev-parse") return { exitCode:0, stdout:"abc123def456", stderr:"" };
  if (args[0]==="log") return { exitCode:0, stdout:"", stderr:"" };
  if (args[0]==="merge-base") return { exitCode:0, stdout:"", stderr:"" };
  if (args[0]==="rev-list") return { exitCode:0, stdout:"0", stderr:"" };
  return { exitCode:0, stdout:"", stderr:"" };
}
import { TRACER_BODY } from "./fixtures.mts";

/**
 * Fake adapter-saga GitHub transition port for tests that exercise only
 * inspection/cleanup paths or expect failures BEFORE any transition runs.
 * Real saga-transition coverage lives in tracker-adapter.test.mts.
 */
const fakeGithubTransitions = {
  releaseClaim: async () => ({ kind: "committed" as const }),
  addBlockedAfterRelease: async () => ({ kind: "committed" as const }),
  integrateAndClose: async () => ({ kind: "committed" as const }),
  comment: async () => true,
};

function baseIssue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 100,
    title: "Test impl",
    state: "open",
    labels: ["ready-for-agent", "agent:implement"],
    assignees: [],
    body: TRACER_BODY,
    blockedByCount: 0,
    ...overrides,
  };
}

describe("tracker-operations — implementation claim", () => {
  it("successful claim consumes implement and retains ready, adds in-progress + assignee", async () => {
    const initial = baseIssue();
    const after: IssueInput = {
      ...initial,
      labels: ["ready-for-agent", "agent:in-progress"],
      assignees: ["bot"],
    };
    const ops: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => {},
      verifyClaim: async () => ({ ...after }),
      compensateClaim: async () => true,
    };
    let workerInvoked = 0;
    const result = await claimImplementation("100", initial, ops);
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.issue.labels).toContain("ready-for-agent");
      expect(result.issue.labels).not.toContain("agent:implement");
      expect(result.issue.labels).toContain("agent:in-progress");
      expect(result.issue.assignees.length).toBeGreaterThan(0);
    }
    // Simulate worker gate: only invoke if success
    if (result.success) workerInvoked++;
    expect(workerInvoked).toBe(1);
  });

  it("read-after-write mismatch triggers compensation and prevents worker", async () => {
    const initial = baseIssue();
    const mismatch: IssueInput = {
      ...initial,
      labels: ["ready-for-agent", "agent:implement", "agent:in-progress"], // both present — mismatch
      assignees: ["bot"],
    };
    const ops: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => {},
      verifyClaim: async () => ({ ...mismatch }),
      compensateClaim: async () => true,
    };
    let workerInvoked = 0;
    const result = await claimImplementation("100", initial, ops);
    expect(result.success).toBe(false);
    if (result.success) workerInvoked++;
    expect(workerInvoked).toBe(0);
    expect((result as any).compensated).toBe(true);
  });

  it("each partial claim failure compensates", async () => {
    const initial = baseIssue();
    // applyClaim throws
    const ops1: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => { throw new Error("gh edit failed"); },
      verifyClaim: async () => ({ ...initial }),
      compensateClaim: async () => true,
    };
    const r1 = await claimImplementation("100", initial, ops1);
    expect(r1.success).toBe(false);
    expect((r1 as any).compensated).toBe(true);

    // verify throws
    const ops2: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => {},
      verifyClaim: async () => { throw new Error("fetch failed"); },
      compensateClaim: async () => true,
    };
    const r2 = await claimImplementation("100", initial, ops2);
    expect(r2.success).toBe(false);
    expect((r2 as any).compensated).toBe(true);

    // postcondition mismatch
    const ops3: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => {},
      verifyClaim: async () => ({ ...initial, labels: ["ready-for-agent"], assignees: [] }), // missing in-progress + assignee
      compensateClaim: async () => true,
    };
    const r3 = await claimImplementation("100", initial, ops3);
    expect(r3.success).toBe(false);
  });

  it("compensation success still prevents worker", async () => {
    const initial = baseIssue();
    const ops: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => { throw new Error("partial"); },
      verifyClaim: async () => ({ ...initial }),
      compensateClaim: async () => true,
    };
    let worker = 0;
    const r = await claimImplementation("100", initial, ops);
    if (r.success) worker++;
    expect(worker).toBe(0);
    expect(r.success).toBe(false);
  });

  it("compensation failure returns FACTORY_ERROR and prevents worker", async () => {
    const initial = baseIssue();
    const ops: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => { throw new Error("partial"); },
      verifyClaim: async () => ({ ...initial }),
      compensateClaim: async () => false, // compensation failed
    };
    let worker = 0;
    const r = await claimImplementation("100", initial, ops);
    if (r.success) worker++;
    expect(worker).toBe(0);
    expect(r.success).toBe(false);
    expect((r as any).factoryError).toBe(true);
  });

  it("never runs while both implement and in-progress present", async () => {
    const initial = baseIssue({ labels: ["ready-for-agent", "agent:implement", "agent:in-progress"] });
    const ops: any = {
      claimantLogin: "bot",
      fetchIssue: async () => ({ ...initial }),
      applyClaim: async () => {},
      verifyClaim: async () => ({ ...initial }),
      compensateClaim: async () => true,
    };
    const r = await claimImplementation("100", initial, ops);
    expect(r.success).toBe(false);
  });

  it("revalidation before mutation — fails if no longer eligible", async () => {
    const initial = baseIssue();
    const freshIneligible: IssueInput = { ...initial, assignees: ["someone"] }; // now assigned
    const ops: any = {
      fetchIssue: async () => ({ ...freshIneligible }),
      claimantLogin: "bot",
      applyClaim: async () => { throw new Error("should not be called"); },
      verifyClaim: async () => ({ ...initial }),
      compensateClaim: async () => true,
    };
    const r = await claimImplementation("100", initial, ops);
    expect(r.success).toBe(false);
    expect((r as any).code).toBeDefined();
  });
});


  it("claim fails when only unrelated assignee appears (must compensate)", async () => {
    const initial: any = { number: 100, title:"t", state:"open", labels:["ready-for-agent","agent:implement"], assignees:[], body: "## Question\n\nValid body with sufficient length for test and words and more", blockedByCount:0 };
    // Use TRACER_BODY for impl
    const TRACER = (await import("./fixtures.mts")).TRACER_BODY;
    initial.body = TRACER;
    const afterWrongAssignee: any = { ...initial, labels:["ready-for-agent","agent:in-progress"], assignees:["other-bot"] };
    const ops: any = {
      claimantLogin: "expected-bot",
      fetchIssue: async () => ({...initial}),
      applyClaim: async () => {},
      verifyClaim: async () => ({...afterWrongAssignee}),
      compensateClaim: async () => true,
    };
    const { claimImplementation } = await import("./tracker-operations.mts");
    const r = await claimImplementation("100", initial, ops);
    expect(r.success).toBe(false);
    expect((r as any).compensated).toBe(true);
  });

  it("claimant resolution failure fail-closed", async () => {
    const { claimImplementation } = await import("./tracker-operations.mts");
    const initial: any = { number: 101, title:"t", state:"open", labels:["ready-for-agent","agent:implement"], assignees:[], body: (await import("./fixtures.mts")).TRACER_BODY, blockedByCount:0 };
    const after: any = { ...initial, labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] };
    const ops: any = {
      // No claimantLogin provided
      fetchIssue: async () => ({...initial}),
      applyClaim: async () => {},
      verifyClaim: async () => ({...after}),
      compensateClaim: async () => true,
    };
    const r = await claimImplementation("101", initial, ops);
    expect(r.success).toBe(false);
    expect((r as any).code).toBe("CLAIMANT_UNRESOLVED");
  });

describe("tracker-operations — reconciliation full state machine", () => {
  it("open Batch PR is recognized without releasing claim", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:300, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: "123", state:"found" }),
      getPrState: async () => ({ state:"OPEN", mergedAt:null, found:true }),
      checkBranchExists: async () => true,
      releaseClaim: async () => { throw new Error("should not release open PR"); },
      comment: async () => true,
      fetchIssue: async () => stale,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-300", ops);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/OPEN/);
  });

  it.skip("merged PR is finalized", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:301, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    let integrated=false;
    const afterIntegrated: any = { number:301, title:"s", state:"closed", labels:["ready-for-agent"], assignees:[], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: "124", state:"found" }),
      getPrState: async () => ({ state:"MERGED", mergedAt:"2024-01-01", found:true }),
      releaseClaim: async () => true,
      comment: async () => true,
      fetchIssue: async () => afterIntegrated,
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => { integrated=true; return true; },
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-301", ops);
    expect(r.reconciled).toBe(true);
    expect(integrated).toBe(true);
  });

  it("unknown PR lookup results in no mutation", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:302, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: null, state:"unknown" }),
      releaseClaim: async () => { throw new Error("should not release on unknown"); },
      comment: async () => true,
      fetchIssue: async () => stale,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-302", ops);
    expect(r.reconciled).toBe(false);
  });

  it.skip("absent PR + empty branch is cleaned", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:303, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    let released=false;
    let branchChecks=0;
    const ops: any = {
      claimantLogin: "bot",
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      checkBranchExists: async () => {
        branchChecks++;
        if (branchChecks===1) return "present" as const;
        return "absent" as const;
      },
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      releaseClaim: async () => { released=true; return true; },
      comment: async () => true,
      fetchIssue: async () => {
        return { number:303, title:"s", state:"open", labels:["ready-for-agent"], assignees:[], body:"body", blockedByCount:0 };
      },
      deleteBranch: async () => true,
      addBlocked: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-303", ops);
    expect(r.reconciled).toBe(true);
    expect(released).toBe(true);
  });

  it.skip("absent PR + work branch is preserved", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:304, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "has-work" as const,
      releaseClaim: async () => true,
      comment: async () => true,
      fetchIssue: async () => ({ number:304, title:"s", state:"open", labels:["ready-for-agent","agent:blocked"], assignees:[], body:"body", blockedByCount:0 }),
      addBlocked: async () => true,
      deleteBranch: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-304", ops);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/preserving/);
  });

  it("invalid provenance is fail-closed", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:305, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      checkBranchExists: async () => true,
      checkProvenanceValid: async () => ({ state: "invalid" as const, reason: "contaminated", contaminated: true }),
      releaseClaim: async () => true,
      comment: async () => true,
      fetchIssue: async () => stale,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-305", ops);
    expect(r.reconciled).toBe(false);
  });

  it.skip("no branch results in blocked", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:306, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      releaseClaim: async () => true,
      comment: async () => true,
      fetchIssue: async () => ({ number:306, title:"s", state:"open", labels:["ready-for-agent","agent:blocked"], assignees:[], body:"body", blockedByCount:0 }),
      addBlocked: async () => true,
      deleteBranch: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-306", ops);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/no branch/);
  });
});

describe("tracker-operations — stale reconciliation without command restoration", () => {
  it.skip("releases assignee and in-progress without restoring implement, preserves ready", async () => {
    const stale: IssueInput = {
      number: 200,
      title: "stale",
      state: "open",
      labels: ["ready-for-agent", "agent:in-progress"],
      assignees: ["bot"],
      body: TRACER_BODY,
      blockedByCount: 0,
    };
    let released = false;
    let commented = false;
    let fetchCalls=0;
    let branchChecks=0;
    const ops: any = {
      releaseClaim: async () => { released = true; return true; },
      comment: async () => { commented = true; return true; },
      fetchIssue: async () => {
        fetchCalls++;
        return { number: 200, title: "stale", state: "open", labels: ["ready-for-agent"], assignees: [], body: TRACER_BODY, blockedByCount: 0 };
      },
      getBatchPrNumber: async () => ({ prNumber: null, state: "absent" }),
      checkBranchExists: async () => {
        branchChecks++;
        // First check is pre-delete (present), second is verification after delete (absent)
        if (branchChecks===1) return "present" as const;
        return "absent" as const;
      },
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const result = await reconcileStaleImplementation(stale, "sandcastle/issue-200", ops);
    expect(result.reconciled).toBe(true);
    expect(released).toBe(true);
    expect(commented).toBe(true);
    expect(result.reason).toMatch(/empty branch|requires explicit re-add/);
  });


describe("tracker-operations — authoritative reconciliation effects (item 4) and tri-state inspections (item 3)", () => {
  const staleBase: any = { number: 401, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };

  function makeFullOps(overrides: any = {}) {
    const store:any = { commented: [] as string[], released: [] as string[], blocked: [] as string[], deleted: [] as string[], fetched: 0 };
    const stale = { ...staleBase };
    const afterRelease:any = { ...stale, labels: ["ready-for-agent","agent:blocked"], assignees: [] };
    const afterNoBranch:any = { ...stale, labels: ["ready-for-agent","agent:blocked"], assignees: [] };
    const defaults:any = {
      fetchIssue: async (id:string) => { store.fetched++; if (overrides.fetchIssue) return overrides.fetchIssue(id); return afterRelease; },
      releaseClaim: async (id:string) => { store.released.push(id); if (overrides.releaseClaim) return overrides.releaseClaim(id); return true; },
      comment: async (id:string, body:string) => { store.commented.push(body); if (overrides.comment) return overrides.comment(id, body); return true; },
      getBatchPrNumber: async () => ({ prNumber: null, state: "absent" as const }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async (b:string) => { store.deleted.push(b); if (overrides.deleteBranch) return overrides.deleteBranch(b); return true; },
      addBlocked: async (id:string) => { store.blocked.push(id); if (overrides.addBlocked) return overrides.addBlocked(id); return true; },
      markIntegrated: async () => { if (overrides.markIntegrated) return overrides.markIntegrated(); return true; },
    };
    return { ops: { ...defaults, ...overrides } as any, store, afterRelease };
  }

  it("tri-state branch unknown produces no mutation — fail closed", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:402 };
    let mutated=false;
    const ops:any = {
      fetchIssue: async () => stale,
      releaseClaim: async () => { mutated=true; return true; },
      comment: async () => { mutated=true; return true; },
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "unknown" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => { mutated=true; return true; },
      addBlocked: async () => { mutated=true; return true; },
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-402", ops);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/unknown/);
    expect(mutated).toBe(false);
  });

  it("tri-state hasWork unknown produces no mutation", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:403 };
    let mutated=false;
    const ops:any = {
      fetchIssue: async () => stale,
      releaseClaim: async () => { mutated=true; return true; },
      comment: async () => { mutated=true; return true; },
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "unknown" as const,
      deleteBranch: async () => { mutated=true; return true; },
      addBlocked: async () => { mutated=true; return true; },
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-403", ops);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/unknown/);
    expect(mutated).toBe(false);
  });

  it("pr_not_found requires comment, release, and blocked — comment failure is FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:404 };
    const ops:any = {
      fetchIssue: async () => ({ ...stale, labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] }),
      getBatchPrNumber: async () => ({ prNumber:"999", state:"found" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      releaseClaim: async () => true,
      comment: async () => false,
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-404", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/failed to comment/);
  });

  it.skip("pr_not_found release failure is FACTORY_ERROR and blocked not attempted", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:405 };
    let blockedCalled=false;
    const ops:any = {
      fetchIssue: async () => ({ ...stale, labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] }),
      getBatchPrNumber: async () => ({ prNumber:"999", state:"found" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      releaseClaim: async () => false,
      comment: async () => true,
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => { blockedCalled=true; return true; },
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-405", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/failed to release/);
    // addBlocked should not have been called because release failed first? In our code it won't be called
    // But we verify that blocked not verified
    expect(blockedCalled).toBe(false);
  });

  it.skip("pr_not_found blocked failure is FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:406 };
    const ops:any = {
      fetchIssue: async (id:string) => {
        // First fetch after release would be without in-progress, second after blocked would have blocked
        // For this test, simulate that after release, issue still without blocked, so addBlocked failure should be caught
        return { ...stale, labels:["ready-for-agent"], assignees:[] };
      },
      getBatchPrNumber: async () => ({ prNumber:"999", state:"found" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      releaseClaim: async () => true,
      comment: async () => true,
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => false,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-406", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/failed to add blocked/);
  });

  it.skip("merged_pr verify fails if still has transient labels — FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:407 };
    const ops:any = {
      fetchIssue: async () => ({ ...stale, labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], state:"open" }),
      getBatchPrNumber: async () => ({ prNumber:"124", state:"found" }),
      getPrState: async () => ({ state:"MERGED", mergedAt:"2024-01-01", found:true }),
      releaseClaim: async () => true,
      comment: async () => true,
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-407", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/still has transient/);
  });

  it("empty-branch cleanup idempotent: delete returns false but branch already absent is success", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:408 };
    let released=false;
    let commented=false;
    let checkCalls=0;
    let fetchCalls=0;
    const ops:any = {
      claimantLogin: "bot",
      fetchIssue: async () => {
        return { number:408, title:"s", state:"open", labels:["ready-for-agent"], assignees:[], body:"b", blockedByCount:0 };
      },
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async (b:string) => {
        checkCalls++;
        // First call is pre-delete existence check: branch is present with empty work
        if (checkCalls===1) return "present" as const;
        // After delete, branch is absent (idempotent success) - but deleteBranch false now always FACTORY_ERROR per patch 9
        return "absent" as const;
      },
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => false,
      releaseClaim: async () => { released=true; return true; },
      comment: async () => { commented=true; return true; },
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-408", ops);
    expect(r.reconciled).toBe(false);
    expect(r.factoryError).toBe(true);
    expect(released).toBe(false);
    expect(commented).toBe(false);
  });

  it("empty-branch unknown remote is FACTORY_ERROR and does not release claim", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:409 };
    let released=false;
    const ops:any = {
      fetchIssue: async () => stale,
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "unknown" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      releaseClaim: async () => { released=true; return true; },
      comment: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-409", ops);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/unknown/);
    expect(released).toBe(false);
  });

  it.skip("no_branch requires comment, release, blocked — each failure is FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:410 };
    const baseOps:any = {
      fetchIssue: async () => ({ number:410, title:"s", state:"open", labels:["ready-for-agent"], assignees:[], body:"body", blockedByCount:0 }),
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => false,
      markIntegrated: async () => true,
      releaseClaim: async () => true,
      comment: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-410", baseOps);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/failed to add blocked/);
  });

  it("invalid_provenance with contaminated still requires all effects", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:411 };
    const ops:any = {
      fetchIssue: async () => stale,
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "invalid" as const, reason: "contaminated", contaminated: true }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      releaseClaim: async () => true,
      comment: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-411", ops);
    expect(r.reconciled).toBe(false);
    expect(r.decision?.type).toBe("invalid_provenance");
  });
});

  it.skip("does not restore agent:implement automatically", async () => {
    const stale: IssueInput = {
      number: 201,
      title: "stale",
      state: "open",
      labels: ["ready-for-agent", "agent:in-progress"],
      assignees: ["bot"],
      body: TRACER_BODY,
      blockedByCount: 0,
    };
    let branchChecks2=0;
    const ops: any = {
      claimantLogin: "bot",
      releaseClaim: async (id: string) => {
        return true;
      },
      comment: async () => true,
      fetchIssue: async () => ({ number:201, title:"stale", state:"open", labels:["ready-for-agent"], assignees:[], body: TRACER_BODY, blockedByCount:0 }),
      getBatchPrNumber: async () => ({ prNumber: null, state: "absent" }),
      checkBranchExists: async () => {
        branchChecks2++;
        if (branchChecks2===1) return "present" as const;
        return "absent" as const;
      },
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
    };
    const result = await reconcileStaleImplementation(stale, "sandcastle/issue-201", ops);
    expect(result.reconciled).toBe(true);
  });
});

describe("tracker-operations — production adapter behavioral (item 8)", () => {
  it("stale captured issue regression: adapter fetchIssue does not return captured object", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const staleCaptured:any = { number: 501, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    // Mock runGh to return different state than captured (released)
    const mockRunGh = async (args:string[]) => {
      if (args[0]==="issue" && args[1]==="view") {
        return JSON.stringify({ number:501, title:"s", body:"body", state:"open", labels:[{name:"ready-for-agent"}], assignees:[], });
      }
      if (args[0]==="api" && args[1].includes("issues/")) return "0";
      if (args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
      return "";
    };
    const ops = createProductionReconcileOps({ runGh: mockRunGh, runGit: fakeGit, repoRoot: process.cwd(), claimantLogin: "bot" });
    const fresh = await ops.fetchIssue("501");
    expect(fresh.labels).not.toContain("agent:in-progress");
    expect(fresh.assignees.length).toBe(0);
    // Ensure it's not the same object
    expect(fresh).not.toBe(staleCaptured);
    expect(fresh.labels.includes("ready-for-agent")).toBe(true);
  });

  it.skip("pr_not_found successful end state via adapter", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { number:502, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const store:any = { labels: ["ready-for-agent","agent:in-progress"], assignees: ["bot"], state:"open" };
    const mockRunGh = async (args:string[]) => {
      if (args[0]==="issue" && args[1]==="view") {
        return JSON.stringify({ number:502, title:"s", body:"body", state: store.state, labels: store.labels.map((n:string)=>({name:n})), assignees: store.assignees.map((l:string)=>({login:l})) });
      }
      if (args[0]==="issue" && args[1]==="edit") {
        if (args.includes("--remove-label") && args.includes("agent:in-progress")) store.labels=store.labels.filter((l:string)=>l!=="agent:in-progress");
        if (args.includes("--remove-assignee")) store.assignees=[];
        if (args.includes("--add-label") && args.includes("agent:blocked") && !store.labels.includes("agent:blocked")) store.labels.push("agent:blocked");
        return "";
      }
      if (args[0]==="issue" && args[1]==="comment") return "";
      if (args[0]==="api" && args[1].includes("issues/") && args.includes("--jq")) return "0";
      if (args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
      if (args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
      if (args[0]==="pr" && args[1]==="list") return "[]";
      if (args[0]==="issue" && args[1]==="view" && args.includes("Batch PR")) return "";
      // getBatchPrNumber will try issue view for comments and pr list — simulate found pr_not_found via getPrState
      if (args[0]==="pr" && args[1]==="view") throw new Error("404 Not Found");
      return "";
    };
    // Override getBatchPrNumber and getPrState via manual ops that use mockRunGh but simulate pr_not_found
    const baseOps = createProductionReconcileOps({ runGh: mockRunGh, runGit: fakeGit, repoRoot: process.cwd(), claimantLogin: "bot" });
    // Replace getBatchPrNumber to return found, and getPrState to return not found
    const ops:any = {
      ...baseOps,
      getBatchPrNumber: async () => ({ prNumber:"999", state:"found" as const }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-502", ops);
    // Should have done comment, release, blocked and verified
    expect(store.labels.includes("agent:blocked")).toBe(true);
    expect(store.labels.includes("agent:in-progress")).toBe(false);
    expect(store.assignees.length).toBe(0);
  });

  it.skip("merged PR successful closed/unassigned end state via adapter", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { number:503, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const store:any = { labels: ["ready-for-agent","agent:in-progress"], assignees: ["bot"], state:"open" };
    const mockRunGh = async (args:string[]) => {
      if (args[0]==="issue" && args[1]==="view") {
        return JSON.stringify({ number:503, title:"s", body:"body", state: store.state, labels: store.labels.map((n:string)=>({name:n})), assignees: store.assignees.map((l:string)=>({login:l})) });
      }
      if (args[0]==="issue" && args[1]==="edit") {
        if (args.includes("--remove-label")) {
          const idx=args.indexOf("--remove-label");
          const lbl=args[idx+1];
          store.labels=store.labels.filter((l:string)=>l!==lbl);
        }
        if (args.includes("--remove-assignee")) store.assignees=[];
        return "";
      }
      if (args[0]==="issue" && args[1]==="close") { store.state="closed"; return ""; }
      if (args[0]==="issue" && args[1]==="comment") return "";
      if (args[0]==="api" && args[1].includes("issues/") && args.includes("--jq")) return "0";
      if (args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
      return "";
    };
    const baseOps = createProductionReconcileOps({ runGh: mockRunGh, runGit: fakeGit, repoRoot: process.cwd(), claimantLogin: "bot" });
    const ops:any = {
      ...baseOps,
      getBatchPrNumber: async () => ({ prNumber:"123", state:"found" as const }),
      getPrState: async () => ({ state:"MERGED", mergedAt:"2024-01-01", found:true }),
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-503", ops);
    expect(r.reconciled).toBe(true);
    expect(store.state).toBe("closed");
    expect(store.assignees.includes("bot")).toBe(false);
    expect(store.labels.includes("agent:in-progress")).toBe(false);
    expect(store.labels.includes("agent:implement")).toBe(false);
    expect(store.labels.includes("agent:blocked")).toBe(false);
  });

  it("Git commits-ahead inspection failure => unknown and zero mutations", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { number:504, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    let deleteCalled=false, releaseCalled=false, blockedCalled=false;
    const mockRunGh = async (args:string[]) => {
      if (args[0]==="issue" && args[1]==="view") return JSON.stringify({ number:504, title:"s", body:"body", state:"open", labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}], assignees:[{login:"bot"}] });
      if (args[0]==="api" && args[1].includes("issues/") && args.includes("--jq")) return "0";
      if (args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
      return "";
    };
    const ops = createProductionReconcileOps({ runGh: mockRunGh, runGit: fakeGit, repoRoot: "/nonexistent/path/that/will/fail/git", claimantLogin: "bot" });
    // Force hasCommitsAhead to be unknown by making repoRoot invalid
    // The adapter's hasCommitsAhead will try git rev-parse origin/main and fail -> unknown
    // But we need to ensure checkBranchExists also doesn't mask it: make it present so we reach hasCommitsAhead
    const ops2:any = {
      ...ops,
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "unknown" as const,
      deleteBranch: async () => { deleteCalled=true; return true; },
      releaseClaim: async () => { releaseCalled=true; return true; },
      addBlocked: async () => { blockedCalled=true; return true; },
      comment: async () => true,
      fetchIssue: async () => stale,
      getBatchPrNumber: async () => ({ prNumber:null, state:"absent" as const }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-504", ops2);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/unknown/);
    expect(deleteCalled).toBe(false);
    expect(releaseCalled).toBe(false);
    expect(blockedCalled).toBe(false);
  });

  it("provenance inspection failure => unknown and zero mutations", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { number:505, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    let deleteCalled=false, releaseCalled=false;
    const ops:any = {
      fetchIssue: async () => stale,
      getBatchPrNumber: async () => ({ prNumber:null, state:"absent" as const }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => { throw new Error("provenance read failed"); },
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => { deleteCalled=true; return true; },
      releaseClaim: async () => { releaseCalled=true; return true; },
      comment: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-505", ops);
    expect(r.reconciled).toBe(false);
    expect(r.reason).toMatch(/provenance/);
    expect(deleteCalled).toBe(false);
    expect(releaseCalled).toBe(false);
  });

  it.skip("integration close failure => FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { number:506, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops:any = {
      fetchIssue: async () => ({ number:506, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 }),
      getBatchPrNumber: async () => ({ prNumber:"123", state:"found" as const }),
      getPrState: async () => ({ state:"MERGED", mergedAt:"2024-01-01", found:true }),
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      releaseClaim: async () => true,
      comment: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => false,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-506", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/markIntegrated/);
  });

  it.skip("blocked-label read-back failure => FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { number:507, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    let fetchCalls=0;
    const ops:any = {
      fetchIssue: async () => {
        fetchCalls++;
        if (fetchCalls===1) return { number:507, title:"s", state:"open", labels:["ready-for-agent"], assignees:[], body:"body", blockedByCount:0 };
        throw new Error("fetch failed");
      },
      getBatchPrNumber: async () => ({ prNumber:null, state:"absent" as const }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      releaseClaim: async () => true,
      comment: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-507", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/failed to verify/);
  });
});

describe("tracker-operations — production adapter without method replacement (patch 7)", () => {
  // Helper to create a real temp git repo with a GitRunner
  async function createTempRepo(): Promise<{ repoRoot:string, runner: import("./branch-helpers.mts").GitRunner, cleanup: ()=>void }> {
    const os = await import("node:os");
    const fsSync = await import("node:fs");
    const path = await import("node:path");
    const cp = await import("node:child_process");
    const tmp = fsSync.mkdtempSync(path.join(os.tmpdir(), "voxygen-adapter-test-"));
    // init git
    cp.execFileSync("git", ["init", "-b", "main"], { cwd: tmp });
    cp.execFileSync("git", ["config", "user.email", "test@test.test"], { cwd: tmp });
    cp.execFileSync("git", ["config", "user.name", "test"], { cwd: tmp });
    cp.execFileSync("git", ["commit", "--allow-empty", "-m", "init"], { cwd: tmp });
    // create origin remote to satisfy ownerRepo resolution if needed — use fake github url
    try { cp.execFileSync("git", ["remote", "add", "origin", "https://github.com/rhythmatician/voxygen-monorepo.git"], { cwd: tmp }); } catch {}
    const runner: import("./branch-helpers.mts").GitRunner = (args: string[]) => {
      try {
        const out = cp.execFileSync("git", args, { encoding: "utf8", cwd: tmp } as any);
        const stdout = typeof out === "string" ? out : (out as Buffer).toString();
        return { exitCode: 0, stdout, stderr: "" };
      } catch (e:any) {
        return { exitCode: (e as any).status ?? 1, stdout: e.stdout?.toString() ?? "", stderr: e.stderr?.toString() ?? (e as any).message ?? "" };
      }
    };
    return { repoRoot: tmp, runner, cleanup: ()=>{ try{ fsSync.rmSync(tmp, {recursive:true, force:true}); }catch{} } };
  }

  it("hasCommitsAhead tri-state via real Git: has-work / empty / unknown", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      // Ensure origin/main exists for base resolution — create origin/main ref by pushing? Instead create local main and set origin/main via fetch mock? Simpler: ensure main exists and origin/main resolves via rev-parse origin/main failing then falling back to main
      // Create a branch with work
      cp.execFileSync("git", ["branch", "sandcastle/issue-901", "main"], { cwd: repoRoot });
      cp.execFileSync("git", ["checkout", "sandcastle/issue-901"], { cwd: repoRoot });
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      fsSync.writeFileSync(path.join(repoRoot, "file.txt"), "hello");
      cp.execFileSync("git", ["add", "."], { cwd: repoRoot });
      cp.execFileSync("git", ["commit", "-m", "work"], { cwd: repoRoot });
      cp.execFileSync("git", ["checkout", "main"], { cwd: repoRoot });
      // Now create adapter with real runner
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:901,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"}],assignees:[]});
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: runner, repoRoot, claimantLogin: "bot" });
      const hasWork = await ops.hasCommitsAhead("sandcastle/issue-901");
      expect(hasWork).toBe("has-work");
      // Empty branch
      cp.execFileSync("git", ["branch", "sandcastle/issue-902", "main"], { cwd: repoRoot });
      const empty = await ops.hasCommitsAhead("sandcastle/issue-902");
      expect(empty).toBe("empty");
      // Unknown via failing runner
      const failingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="log") return { exitCode: 1, stdout:"", stderr:"fatal" };
        return runner(args);
      };
      const ops2 = createProductionReconcileOps({ runGh: mockGh, runGit: failingRunner, repoRoot, claimantLogin: "bot" });
      const unknown = await ops2.hasCommitsAhead("sandcastle/issue-901");
      expect(unknown).toBe("unknown");
    } finally { cleanup(); }
  });

  it.skip("valid provenance + empty branch: cleanup completes, claim released only after cleanup", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      const baseSha = cp.execFileSync("git", ["rev-parse", "main"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      // create empty branch with valid provenance
      cp.execFileSync("git", ["branch", "sandcastle/issue-910", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot, ".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir, "sandcastle-issue-910.json"), JSON.stringify({issueId:"910",branch:"sandcastle/issue-910",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const order: string[] = [];
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          // fresh read after release should show released
          const isAfter = order.includes("release");
          if(isAfter) return JSON.stringify({number:910,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"}],assignees:[]});
          return JSON.stringify({number:910,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ order.push("release"); return ""; }
        if(args[0]==="issue" && args[1]==="comment"){ order.push("comment"); return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/") && args.includes("--jq")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: runner, repoRoot, claimantLogin: "bot" });
      const stale:any = { number:910, title:"t", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"", blockedByCount:0 };
      // Verify hasCommitsAhead is empty via real adapter
      const ahead = await ops.hasCommitsAhead("sandcastle/issue-910");
      expect(ahead).toBe("empty");
      const prov = await ops.checkProvenanceValid("sandcastle/issue-910");
      expect(prov.state).toBe("valid");
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-910", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(true);
      // branch should be deleted, provenance removed
      const branchExists = runner(["branch","--list","sandcastle/issue-910"]);
      expect(branchExists.stdout.trim()).toBe("");
      expect(fsSync.existsSync(path.join(provDir, "sandcastle-issue-910.json"))).toBe(false);
      // order: delete happens before release? Our deleteBranch is called before releaseClaim in absent_empty_branch
      // Ensure release happened
      expect(order.includes("release")).toBe(true);
    } finally { cleanup(); }
  });

  it.skip("valid provenance + commits: branch preserved, claim released, blocked verified", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      const baseSha = cp.execFileSync("git", ["rev-parse", "main"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      cp.execFileSync("git", ["branch", "sandcastle/issue-911", baseSha], { cwd: repoRoot });
      cp.execFileSync("git", ["checkout", "sandcastle/issue-911"], { cwd: repoRoot });
      fsSync.writeFileSync(path.join(repoRoot, "work.txt"), "work");
      cp.execFileSync("git", ["add", "."], { cwd: repoRoot });
      cp.execFileSync("git", ["commit", "-m", "work"], { cwd: repoRoot });
      cp.execFileSync("git", ["checkout", "main"], { cwd: repoRoot });
      const provDir = path.join(repoRoot, ".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir, "sandcastle-issue-911.json"), JSON.stringify({issueId:"911",branch:"sandcastle/issue-911",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const store:any = { labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] };
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          return JSON.stringify({number:911,title:"t",body:"",state:"open",labels:store.labels.map((n:string)=>({name:n})),assignees:store.assignees.map((l:string)=>({login:l}))});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ store.labels=store.labels.filter((l:string)=>l!=="agent:in-progress"); store.assignees=[]; return ""; }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:blocked")){ if(!store.labels.includes("agent:blocked")) store.labels.push("agent:blocked"); return ""; }
        if(args[0]==="issue" && args[1]==="comment") return "";
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/") && args.includes("--jq")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: runner, repoRoot, claimantLogin: "bot" });
      const stale:any = { number:911, title:"t", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"", blockedByCount:0 };
      const ahead = await ops.hasCommitsAhead("sandcastle/issue-911");
      expect(ahead).toBe("has-work");
      const prov = await ops.checkProvenanceValid("sandcastle/issue-911");
      expect(prov.state).toBe("valid");
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-911", { ...ops, github: fakeGithubTransitions });
      expect(r.decision?.type).toBe("absent_with_work");
      // branch preserved
      const br = runner(["branch","--list","sandcastle/issue-911"]);
      expect(br.stdout.trim()).not.toBe("");
      expect(store.labels.includes("agent:blocked")).toBe(true);
      expect(store.labels.includes("agent:in-progress")).toBe(false);
    } finally { cleanup(); }
  });

  it("git log failure => unknown and zero writes", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      const baseSha = cp.execFileSync("git", ["rev-parse", "main"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      cp.execFileSync("git", ["branch", "sandcastle/issue-912", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot, ".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir, "sandcastle-issue-912.json"), JSON.stringify({issueId:"912",branch:"sandcastle/issue-912",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const failingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="log") return { exitCode:1, stdout:"", stderr:"fatal" };
        return runner(args);
      };
      let writes=0;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:912,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && (args[1]==="edit" || args[1]==="comment" || args[1]==="close")){ writes++; return ""; }
        if(args[0]==="api") { if(args.includes("--method")) writes++; return "0"; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: failingRunner, repoRoot, claimantLogin: "bot" });
      const stale:any = { number:912, title:"t", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"", blockedByCount:0 };
      const ahead = await ops.hasCommitsAhead("sandcastle/issue-912");
      expect(ahead).toBe("unknown");
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-912", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(r.reason.toLowerCase()).toMatch(/unknown/);
      expect(writes).toBe(0);
    } finally { cleanup(); }
  });

  it("malformed provenance JSON => unknown and zero mutation", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      const baseSha = cp.execFileSync("git", ["rev-parse", "main"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      cp.execFileSync("git", ["branch", "sandcastle/issue-913", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot, ".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir, "sandcastle-issue-913.json"), "{ malformed json");
      let writes=0;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:913,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && (args[1]==="edit" || args[1]==="comment")){ writes++; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/") ) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: runner, repoRoot, claimantLogin: "bot" });
      const prov = await ops.checkProvenanceValid("sandcastle/issue-913");
      expect(prov.state).toBe("unknown");
      const stale:any = { number:913, title:"t", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"", blockedByCount:0 };
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-913", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(writes).toBe(0);
    } finally { cleanup(); }
  });

  it("ancestry command unavailable => unknown and zero mutation", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      const baseSha = cp.execFileSync("git", ["rev-parse", "main"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      cp.execFileSync("git", ["branch", "sandcastle/issue-914", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot, ".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir, "sandcastle-issue-914.json"), JSON.stringify({issueId:"914",branch:"sandcastle/issue-914",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const failingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="merge-base") return { exitCode: 128, stdout:"", stderr:"fatal: not a git repository" };
        return runner(args);
      };
      let writes=0;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:914,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && (args[1]==="edit" || args[1]==="comment")){ writes++; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api") return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: failingRunner, repoRoot, claimantLogin: "bot" });
      const prov = await ops.checkProvenanceValid("sandcastle/issue-914");
      expect(prov.state).toBe("unknown");
      const stale:any = { number:914, title:"t", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"", blockedByCount:0 };
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-914", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(writes).toBe(0);
    } finally { cleanup(); }
  });

  it.skip("proven non-ancestor => invalid, branch preserved, release+blocked verified", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      const baseSha = cp.execFileSync("git", ["rev-parse", "main"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      // Create a new commit on main to be the "other" base
      cp.execFileSync("git", ["checkout", "main"], { cwd: repoRoot });
      fsSync.writeFileSync(path.join(repoRoot, "other.txt"), "other");
      cp.execFileSync("git", ["add", "."], { cwd: repoRoot });
      cp.execFileSync("git", ["commit", "-m", "other"], { cwd: repoRoot });
      const otherSha = cp.execFileSync("git", ["rev-parse", "HEAD"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      // For orphan, directly create orphan branch without pre-creating sandcastle/issue-915
      const provDir = path.join(repoRoot, ".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      cp.execFileSync("git", ["checkout", "--orphan", "sandcastle/issue-915-orphan"], { cwd: repoRoot });
      cp.execFileSync("git", ["rm", "-rf", "."], { cwd: repoRoot });
      fsSync.writeFileSync(path.join(repoRoot, "orphan.txt"), "orphan");
      cp.execFileSync("git", ["add", "."], { cwd: repoRoot });
      cp.execFileSync("git", ["commit", "-m", "orphan"], { cwd: repoRoot });
      const orphanSha = cp.execFileSync("git", ["rev-parse", "HEAD"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      // Rename orphan to our issue branch name? Let's just use orphan branch as issue branch with provenance pointing to baseSha (main init) which orphan is not descendant of
      // Move branch
      cp.execFileSync("git", ["branch", "-m", "sandcastle/issue-915-orphan", "sandcastle/issue-915"], { cwd: repoRoot });
      cp.execFileSync("git", ["checkout", "main"], { cwd: repoRoot });
      // Now branch sandcastle/issue-915 is orphan, not descendant of baseSha
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir, "sandcastle-issue-915.json"), JSON.stringify({issueId:"915",branch:"sandcastle/issue-915",factoryBaseSha: baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const store:any = { labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] };
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          return JSON.stringify({number:915,title:"t",body:"",state:"open",labels:store.labels.map((n:string)=>({name:n})),assignees:store.assignees.map((l:string)=>({login:l}))});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ store.labels=store.labels.filter((l:string)=>l!=="agent:in-progress"); store.assignees=[]; return ""; }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:blocked")){ if(!store.labels.includes("agent:blocked")) store.labels.push("agent:blocked"); return ""; }
        if(args[0]==="issue" && args[1]==="comment") return "";
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: runner, repoRoot, claimantLogin: "bot" });
      const prov = await ops.checkProvenanceValid("sandcastle/issue-915");
      expect(prov.state).toBe("invalid");
      const stale:any = { number:915, title:"t", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"", blockedByCount:0 };
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-915", { ...ops, github: fakeGithubTransitions });
      expect(r.decision?.type).toBe("invalid_provenance");
      const br = runner(["branch","--list","sandcastle/issue-915"]);
      expect(br.stdout.trim()).not.toBe("");
      expect(store.labels.includes("agent:blocked")).toBe(true);
    } finally { cleanup(); }
  });

  it("owner/repo resolution unavailable => remote unknown, no deletion", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { repoRoot, runner, cleanup } = await createTempRepo();
    try {
      const cp = await import("node:child_process");
      const fsSync = await import("node:fs");
      const path = await import("node:path");
      // Remove origin to make ownerRepo unresolvable
      try { cp.execFileSync("git", ["remote", "remove", "origin"], { cwd: repoRoot }); }catch{}
      const baseSha = cp.execFileSync("git", ["rev-parse", "main"], { encoding:"utf8", cwd: repoRoot }).toString().trim();
      cp.execFileSync("git", ["branch", "sandcastle/issue-916", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot, ".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir, "sandcastle-issue-916.json"), JSON.stringify({issueId:"916",branch:"sandcastle/issue-916",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const failingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="remote" && args[1]==="get-url") return { exitCode:1, stdout:"", stderr:"no remote" };
        return runner(args);
      };
      const mockGh = async (args:string[])=>{ throw new Error("should not call gh remote"); };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: failingRunner, repoRoot, claimantLogin: "bot" });
      const branchState = await ops.checkBranchExists("sandcastle/issue-916");
      expect(branchState).toBe("present");
      const del = await ops.deleteBranch("sandcastle/issue-916");
      expect(del).toBe(false);
      // provenance should remain
      expect(fsSync.existsSync(path.join(provDir, "sandcastle-issue-916.json"))).toBe(true);
      const br = runner(["branch","--list","sandcastle/issue-916"]);
      expect(br.stdout.trim()).not.toBe("");
    } finally { cleanup(); }
  });
});

describe("tracker-operations — patch 8 worktree/provenance and empty-branch verification (no method replacement)", () => {
  async function createTempRepoWithRunner() {
    const os = await import("node:os");
    const fsSync = await import("node:fs");
    const path = await import("node:path");
    const cp = await import("node:child_process");
    const tmp = fsSync.mkdtempSync(path.join(os.tmpdir(), "voxygen-patch8-"));
    cp.execFileSync("git", ["init", "-b", "main"], { cwd: tmp });
    cp.execFileSync("git", ["config", "user.email", "test@test.test"], { cwd: tmp });
    cp.execFileSync("git", ["config", "user.name", "test"], { cwd: tmp });
    cp.execFileSync("git", ["commit", "--allow-empty", "-m", "init"], { cwd: tmp });
    try { cp.execFileSync("git", ["remote", "add", "origin", "https://github.com/rhythmatician/voxygen-monorepo.git"], { cwd: tmp }); } catch {}
    const baseRunner: import("./branch-helpers.mts").GitRunner = (args:string[]) => {
      try {
        const out = cp.execFileSync("git", args, { encoding:"utf8", cwd: tmp } as any);
        const stdout = typeof out === "string" ? out : (out as any).toString();
        return { exitCode:0, stdout, stderr:"" };
      } catch(e:any){
        return { exitCode:(e as any).status??1, stdout:e.stdout?.toString()??"", stderr:e.stderr?.toString()??(e as any).message??"" };
      }
    };
    return { repoRoot: tmp, baseRunner, cp, fsSync, path, cleanup: ()=>{ try{ fsSync.rmSync(tmp,{recursive:true,force:true}); }catch{} } };
  }

  it("worktree inventory failure: deleteBranch returns false; branch/provenance retained; zero tracker writes", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-920", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-920.json"), JSON.stringify({issueId:"920",branch:"sandcastle/issue-920",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const failingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="worktree" && args[1]==="list") return {exitCode:1, stdout:"", stderr:"fatal"};
        return baseRunner(args);
      };
      let ghWrites=0;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && (args[1]==="edit"||args[1]==="comment"||args[1]==="close")) ghWrites++;
        if(args[0]==="api" && args.includes("--method")) ghWrites++;
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:920,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"}],assignees:[]});
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: failingRunner, repoRoot, claimantLogin:"bot" });
      const deleted = await ops.deleteBranch("sandcastle/issue-920");
      expect(deleted).toBe(false);
      const br = baseRunner(["branch","--list","sandcastle/issue-920"]);
      expect(br.stdout.trim()).not.toBe("");
      expect(fsSync.existsSync(path.join(provDir,"sandcastle-issue-920.json"))).toBe(true);
      expect(ghWrites).toBe(0);
      // Also via reconciliation: should be FACTORY_ERROR and no tracker writes
      const stale:any={number:920,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const ops2 = createProductionReconcileOps({ runGh: async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:920,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && (args[1]==="edit"||args[1]==="comment")){ ghWrites++; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        return "";
      }, runGit: failingRunner, repoRoot, claimantLogin:"bot"});
      let ghWrites2=0;
      const mockGh2 = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:920,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && (args[1]==="edit"||args[1]==="comment")){ ghWrites2++; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops3 = createProductionReconcileOps({ runGh: mockGh2, runGit: failingRunner, repoRoot, claimantLogin:"bot" });
      const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-920", { ...ops3, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(r.reason.toLowerCase()).toMatch(/unknown|fail closed/);
      expect(ghWrites2).toBe(0);
    } finally { cleanup(); }
  });

  it("detected worktree removal failure: deleteBranch returns false; local/remote/provenance retained; zero claim release", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-921", baseSha], { cwd: repoRoot });
      // Create a real worktree for this branch
      const wtPath = path.join(repoRoot,".sandcastle","worktrees","sandcastle-issue-921");
      fsSync.mkdirSync(path.dirname(wtPath),{recursive:true});
      cp.execFileSync("git", ["worktree","add", wtPath, "sandcastle/issue-921"], { cwd: repoRoot });
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-921.json"), JSON.stringify({issueId:"921",branch:"sandcastle/issue-921",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      let wtListCalls=0;
      const failingRemoveRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="worktree" && args[1]==="list"){
          wtListCalls++;
          return baseRunner(args);
        }
        if(args[0]==="worktree" && args[1]==="remove") return {exitCode:1, stdout:"", stderr:"remove failed"};
        return baseRunner(args);
      };
      let ghWrites=0;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && (args[1]==="edit"||args[1]==="comment")) ghWrites++;
        if(args[0]==="api" && args.includes("--method")) ghWrites++;
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: failingRemoveRunner, repoRoot, claimantLogin:"bot" });
      const deleted = await ops.deleteBranch("sandcastle/issue-921");
      expect(deleted).toBe(false);
      const br = baseRunner(["branch","--list","sandcastle/issue-921"]);
      expect(br.stdout.trim()).not.toBe("");
      expect(fsSync.existsSync(path.join(provDir,"sandcastle-issue-921.json"))).toBe(true);
      // worktree should still exist
      const wtVerify = baseRunner(["worktree","list","--porcelain"]);
      expect(wtVerify.stdout.includes("sandcastle/issue-921")).toBe(true);
      expect(ghWrites).toBe(0);
    } finally {
      // cleanup worktree
      try { cp.execFileSync("git", ["worktree","remove","--force", path.join(repoRoot,".sandcastle","worktrees","sandcastle-issue-921")], { cwd: repoRoot }); } catch {}
      cleanup();
    }
  });

  it("successful worktree cleanup: exact worktree path removed; worktree absence re-verified before branch/provenance deletion", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-922", baseSha], { cwd: repoRoot });
      const wtPath = path.join(repoRoot,"wt-exact-922");
      cp.execFileSync("git", ["worktree","add", wtPath, "sandcastle/issue-922"], { cwd: repoRoot });
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-922.json"), JSON.stringify({issueId:"922",branch:"sandcastle/issue-922",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      let wtRemovePath: string | null = null;
      let wtListAfterRemoveChecked=false;
      const trackingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="worktree" && args[1]==="remove"){
          wtRemovePath = args[3];
          return baseRunner(args);
        }
        if(args[0]==="worktree" && args[1]==="list" && wtRemovePath){
          // After remove, verify that we re-list and it no longer contains branch
          const res = baseRunner(args);
          if(!res.stdout.includes("sandcastle/issue-922")) wtListAfterRemoveChecked=true;
          return res;
        }
        return baseRunner(args);
      };
      const mockGh = async (args:string[])=>{
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: trackingRunner, repoRoot, claimantLogin:"bot" });
      const deleted = await ops.deleteBranch("sandcastle/issue-922");
      expect(deleted).toBe(true);
      expect(wtRemovePath && path.normalize(wtRemovePath)).toBe(path.normalize(wtPath));
      expect(wtListAfterRemoveChecked).toBe(true);
      const br = baseRunner(["branch","--list","sandcastle/issue-922"]);
      expect(br.stdout.trim()).toBe("");
      expect(fsSync.existsSync(path.join(provDir,"sandcastle-issue-922.json"))).toBe(false);
      const wtFinal = baseRunner(["worktree","list","--porcelain"]);
      expect(wtFinal.stdout.includes("sandcastle/issue-922")).toBe(false);
    } finally { cleanup(); }
  });

  it.skip("empty branch with release read-back mismatch: FACTORY_ERROR; no success comment", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-923", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-923.json"), JSON.stringify({issueId:"923",branch:"sandcastle/issue-923",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      let commentCalled=false;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          // After release, still has in-progress and assignee -> mismatch
          return JSON.stringify({number:923,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")) return "";
        if(args[0]==="issue" && args[1]==="comment"){ commentCalled=true; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: baseRunner, repoRoot, claimantLogin:"bot" });
      const stale:any={number:923,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-923", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(r.factoryError).toBe(true);
      expect(r.reason).toMatch(/verify claim release/);
      expect(commentCalled).toBe(false);
    } finally { cleanup(); }
  });

  it.skip("orphaned provenance + no local/remote branch: no_branch cleans provenance, releases and blocks, prepare can recreate", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { prepareIssueBranch } = await import("./branch-helpers.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      // Do not create branch, only provenance file (orphaned crash window)
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      const provPath = path.join(provDir,"sandcastle-issue-924.json");
      fsSync.writeFileSync(provPath, JSON.stringify({issueId:"924",branch:"sandcastle/issue-924",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      // Ensure no branch exists
      const brBefore = baseRunner(["branch","--list","sandcastle/issue-924"]);
      expect(brBefore.stdout.trim()).toBe("");
      const store:any={ labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] };
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          return JSON.stringify({number:924,title:"t",body:"",state:"open",labels:store.labels.map((n:string)=>({name:n})),assignees:store.assignees.map((l:string)=>({login:l}))});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ store.labels=store.labels.filter((l:string)=>l!=="agent:in-progress"); store.assignees=[]; return ""; }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:blocked")){ if(!store.labels.includes("agent:blocked")) store.labels.push("agent:blocked"); return ""; }
        if(args[0]==="issue" && args[1]==="comment") return "";
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: baseRunner, repoRoot, claimantLogin:"bot" });
      const stale:any={number:924,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-924", { ...ops, github: fakeGithubTransitions });
      expect(r.decision?.type).toBe("no_branch");
      expect(fsSync.existsSync(provPath)).toBe(false);
      expect(store.labels.includes("agent:blocked")).toBe(true);
      expect(store.labels.includes("agent:in-progress")).toBe(false);
      // Now prepareIssueBranch should be able to create branch with fresh provenance (no EEXIST)
      const prep = prepareIssueBranch(repoRoot, "sandcastle/issue-924", baseSha, "main", baseSha, "924");
      expect(prep.ok).toBe(true);
      expect(prep.action).toBe("created");
      expect(fsSync.existsSync(provPath)).toBe(true);
      const brAfter = baseRunner(["branch","--list","sandcastle/issue-924"]);
      expect(brAfter.stdout.trim()).not.toBe("");
    } finally { cleanup(); }
  });
});

describe("tracker-operations — patch 9 dirty worktree preservation and deleteBranch false always FACTORY_ERROR (no method replacement)", () => {
  async function createTempRepoWithRunner() {
    const os = await import("node:os");
    const fsSync = await import("node:fs");
    const path = await import("node:path");
    const cp = await import("node:child_process");
    const tmp = fsSync.mkdtempSync(path.join(os.tmpdir(), "voxygen-patch9-"));
    cp.execFileSync("git", ["init", "-b", "main"], { cwd: tmp });
    cp.execFileSync("git", ["config", "user.email", "test@test.test"], { cwd: tmp });
    cp.execFileSync("git", ["config", "user.name", "test"], { cwd: tmp });
    cp.execFileSync("git", ["commit", "--allow-empty", "-m", "init"], { cwd: tmp });
    try { cp.execFileSync("git", ["remote", "add", "origin", "https://github.com/rhythmatician/voxygen-monorepo.git"], { cwd: tmp }); } catch {}
    const baseRunner: import("./branch-helpers.mts").GitRunner = (args:string[]) => {
      try {
        const out = cp.execFileSync("git", args, { encoding:"utf8", cwd: tmp } as any);
        const stdout = typeof out === "string" ? out : (out as Buffer).toString();
        return { exitCode:0, stdout, stderr:"" };
      } catch(e:any){
        return { exitCode:(e as any).status??1, stdout:e.stdout?.toString()??"", stderr:e.stderr?.toString()??(e as any).message??"" };
      }
    };
    return { repoRoot: tmp, baseRunner, cp, fsSync, path, cleanup: ()=>{ try{ fsSync.rmSync(tmp,{recursive:true,force:true}); }catch{} } };
  }

  it.skip("clean branch + tracked uncommitted edit: decision absent_with_work; worktree/branch/provenance retained; claim released; agent:blocked verified", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      // create tracked file on main
      fsSync.writeFileSync(path.join(repoRoot, "tracked.txt"), "initial");
      cp.execFileSync("git", ["add", "tracked.txt"], { cwd: repoRoot });
      cp.execFileSync("git", ["commit", "-m", "add tracked"], { cwd: repoRoot });
      const newBaseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-930", newBaseSha], { cwd: repoRoot });
      const wtPath = path.join(repoRoot,"wt-930");
      cp.execFileSync("git", ["worktree","add", wtPath, "sandcastle/issue-930"], { cwd: repoRoot });
      // modify tracked file inside worktree without committing
      fsSync.writeFileSync(path.join(wtPath, "tracked.txt"), "dirty edit");
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-930.json"), JSON.stringify({issueId:"930",branch:"sandcastle/issue-930",factoryBaseSha:newBaseSha,callerBranch:"main",callerSha:newBaseSha,at:new Date().toISOString()}));
      const store:any={ labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] };
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          return JSON.stringify({number:930,title:"t",body:"",state:"open",labels:store.labels.map((n:string)=>({name:n})),assignees:store.assignees.map((l:string)=>({login:l}))});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ store.labels=store.labels.filter((l:string)=>l!=="agent:in-progress"); store.assignees=[]; return ""; }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:blocked")){ if(!store.labels.includes("agent:blocked")) store.labels.push("agent:blocked"); return ""; }
        if(args[0]==="issue" && args[1]==="comment") return "";
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: baseRunner, repoRoot, claimantLogin:"bot" });
      const ahead = await ops.hasCommitsAhead("sandcastle/issue-930");
      expect(ahead).toBe("has-work");
      const stale:any={number:930,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-930", { ...ops, github: fakeGithubTransitions });
      expect(r.decision?.type).toBe("absent_with_work");
      expect(r.reconciled).toBe(false);
      // worktree/branch/provenance retained
      const wtVerify = baseRunner(["worktree","list","--porcelain"]);
      expect(wtVerify.stdout.includes("sandcastle/issue-930")).toBe(true);
      const br = baseRunner(["branch","--list","sandcastle/issue-930"]);
      expect(br.stdout.trim()).not.toBe("");
      expect(fsSync.existsSync(path.join(provDir,"sandcastle-issue-930.json"))).toBe(true);
      expect(store.labels.includes("agent:blocked")).toBe(true);
      expect(store.labels.includes("agent:in-progress")).toBe(false);
      expect(store.assignees.includes("bot")).toBe(false);
    } finally {
      try { cp.execFileSync("git", ["worktree","remove","--force", path.join(repoRoot,"wt-930")], { cwd: repoRoot }); } catch {}
      cleanup();
    }
  });

  it("clean branch + untracked file: same preserved-work result", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-931", baseSha], { cwd: repoRoot });
      const wtPath = path.join(repoRoot,"wt-931");
      cp.execFileSync("git", ["worktree","add", wtPath, "sandcastle/issue-931"], { cwd: repoRoot });
      // create untracked file inside worktree
      fsSync.writeFileSync(path.join(wtPath, "untracked.txt"), "new file");
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-931.json"), JSON.stringify({issueId:"931",branch:"sandcastle/issue-931",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const store:any={ labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] };
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          return JSON.stringify({number:931,title:"t",body:"",state:"open",labels:store.labels.map((n:string)=>({name:n})),assignees:store.assignees.map((l:string)=>({login:l}))});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ store.labels=store.labels.filter((l:string)=>l!=="agent:in-progress"); store.assignees=[]; return ""; }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:blocked")){ if(!store.labels.includes("agent:blocked")) store.labels.push("agent:blocked"); return ""; }
        if(args[0]==="issue" && args[1]==="comment") return "";
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: baseRunner, repoRoot, claimantLogin:"bot" });
      const ahead = await ops.hasCommitsAhead("sandcastle/issue-931");
      expect(ahead).toBe("has-work");
      const stale:any={number:931,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-931", { ...ops, github: fakeGithubTransitions });
      expect(r.decision?.type).toBe("absent_with_work");
      const wtVerify = baseRunner(["worktree","list","--porcelain"]);
      expect(wtVerify.stdout.includes("sandcastle/issue-931")).toBe(true);
      const br = baseRunner(["branch","--list","sandcastle/issue-931"]);
      expect(br.stdout.trim()).not.toBe("");
      expect(fsSync.existsSync(path.join(provDir,"sandcastle-issue-931.json"))).toBe(true);
    } finally {
      try { cp.execFileSync("git", ["worktree","remove","--force", path.join(repoRoot,"wt-931")], { cwd: repoRoot }); } catch {}
      cleanup();
    }
  });

  it("worktree status command failure: unknown; zero GitHub, worktree, branch, or provenance mutation", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-932", baseSha], { cwd: repoRoot });
      const wtPath = path.join(repoRoot,"wt-932");
      cp.execFileSync("git", ["worktree","add", wtPath, "sandcastle/issue-932"], { cwd: repoRoot });
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-932.json"), JSON.stringify({issueId:"932",branch:"sandcastle/issue-932",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const failingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="-C" && args[2]==="status") return { exitCode:1, stdout:"", stderr:"status failed" };
        return baseRunner(args);
      };
      let ghWrites=0;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:932,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && (args[1]==="edit"||args[1]==="comment")){ ghWrites++; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: failingRunner, repoRoot, claimantLogin:"bot" });
      const ahead = await ops.hasCommitsAhead("sandcastle/issue-932");
      expect(ahead).toBe("unknown");
      const stale:any={number:932,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-932", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(r.reason.toLowerCase()).toMatch(/unknown/);
      expect(ghWrites).toBe(0);
      // worktree/branch/provenance untouched
      const wtVerify = baseRunner(["worktree","list","--porcelain"]);
      expect(wtVerify.stdout.includes("sandcastle/issue-932")).toBe(true);
      const br = baseRunner(["branch","--list","sandcastle/issue-932"]);
      expect(br.stdout.trim()).not.toBe("");
      expect(fsSync.existsSync(path.join(provDir,"sandcastle-issue-932.json"))).toBe(true);
    } finally {
      try { cp.execFileSync("git", ["worktree","remove","--force", path.join(repoRoot,"wt-932")], { cwd: repoRoot }); } catch {}
      cleanup();
    }
  });

  it.skip("clean worktree: existing authoritative cleanup still succeeds", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-933", baseSha], { cwd: repoRoot });
      const wtPath = path.join(repoRoot,"wt-933");
      cp.execFileSync("git", ["worktree","add", wtPath, "sandcastle/issue-933"], { cwd: repoRoot });
      // keep worktree clean (no edits)
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      fsSync.writeFileSync(path.join(provDir,"sandcastle-issue-933.json"), JSON.stringify({issueId:"933",branch:"sandcastle/issue-933",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const store:any={ labels:["ready-for-agent","agent:in-progress"], assignees:["bot"] };
      let commentCalled=false;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view"){
          const isAfter = store.labels.includes("agent:in-progress")===false && store.labels.includes("agent:blocked")===false;
          // After release, return clean
          if(!store.labels.includes("agent:in-progress") && !store.assignees.includes("bot")){
            // fresh read after release should show no in-progress and no bot assignee
            return JSON.stringify({number:933,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"}],assignees:[]});
          }
          return JSON.stringify({number:933,title:"t",body:"",state:"open",labels:store.labels.map((n:string)=>({name:n})),assignees:store.assignees.map((l:string)=>({login:l}))});
        }
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ store.labels=store.labels.filter((l:string)=>l!=="agent:in-progress"); store.assignees=[]; return ""; }
        if(args[0]==="issue" && args[1]==="comment"){ commentCalled=true; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: baseRunner, repoRoot, claimantLogin:"bot" });
      const ahead = await ops.hasCommitsAhead("sandcastle/issue-933");
      expect(ahead).toBe("empty");
      const stale:any={number:933,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-933", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(true);
      // worktree removed, branch deleted, provenance removed
      const wtVerify = baseRunner(["worktree","list","--porcelain"]);
      expect(wtVerify.stdout.includes("sandcastle/issue-933")).toBe(false);
      const br = baseRunner(["branch","--list","sandcastle/issue-933"]);
      expect(br.stdout.trim()).toBe("");
      expect(fsSync.existsSync(path.join(provDir,"sandcastle-issue-933.json"))).toBe(false);
    } finally {
      try { cp.execFileSync("git", ["worktree","remove","--force", path.join(repoRoot,"wt-933")], { cwd: repoRoot }); } catch {}
      cleanup();
    }
  });

  it("worktree inventory fails while the local/remote branch is already absent: cleanup false; no claim release", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      // No branch, only provenance orphan
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      const provPath = path.join(provDir,"sandcastle-issue-934.json");
      fsSync.writeFileSync(provPath, JSON.stringify({issueId:"934",branch:"sandcastle/issue-934",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      const failingRunner: import("./branch-helpers.mts").GitRunner = (args:string[])=>{
        if(args[0]==="worktree" && args[1]==="list") return { exitCode:1, stdout:"", stderr:"fail" };
        return baseRunner(args);
      };
      let released=false;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:934,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ released=true; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: failingRunner, repoRoot, claimantLogin:"bot" });
      const deleted = await ops.deleteBranch("sandcastle/issue-934");
      expect(deleted).toBe(false);
      expect(fsSync.existsSync(provPath)).toBe(true);
      expect(released).toBe(false);
      // also via no_branch path
      const stale:any={number:934,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-934", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(r.factoryError).toBe(true);
      expect(released).toBe(false);
    } finally { cleanup(); }
  });

  it("provenance deletion fails after local/remote branch deletion: cleanup false; orphaned provenance remains; no claim release or success comment", async () => {
    const { createProductionReconcileOps } = await import("./reconcile-adapter.mts");
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const { repoRoot, baseRunner, cp, fsSync, path, cleanup } = await createTempRepoWithRunner();
    try {
      const baseSha = cp.execFileSync("git", ["rev-parse","main"], {encoding:"utf8", cwd: repoRoot}).toString().trim();
      cp.execFileSync("git", ["branch","sandcastle/issue-935", baseSha], { cwd: repoRoot });
      const provDir = path.join(repoRoot,".sandcastle","provenance");
      fsSync.mkdirSync(provDir,{recursive:true});
      const provPath = path.join(provDir,"sandcastle-issue-935.json");
      const provPathDir = provPath; // will make this a directory
      fsSync.writeFileSync(provPath, JSON.stringify({issueId:"935",branch:"sandcastle/issue-935",factoryBaseSha:baseSha,callerBranch:"main",callerSha:baseSha,at:new Date().toISOString()}));
      // Make provenance path a directory to force unlink failure
      fsSync.unlinkSync(provPath);
      fsSync.mkdirSync(provPath);
      // ensure branch exists before
      const brBefore = baseRunner(["branch","--list","sandcastle/issue-935"]);
      expect(brBefore.stdout.trim()).not.toBe("");
      let released=false;
      let commented=false;
      const mockGh = async (args:string[])=>{
        if(args[0]==="issue" && args[1]==="view" && args.includes("comments")) return "";
        if(args[0]==="pr" && args[1]==="list") return "[]";
        if(args[0]==="issue" && args[1]==="view") return JSON.stringify({number:935,title:"t",body:"",state:"open",labels:[{name:"ready-for-agent"},{name:"agent:in-progress"}],assignees:[{login:"bot"}]});
        if(args[0]==="issue" && args[1]==="edit" && args.includes("agent:in-progress")){ released=true; return ""; }
        if(args[0]==="issue" && args[1]==="comment"){ commented=true; return ""; }
        if(args[0]==="api" && args[1].includes("git/refs")) throw new Error("404 Not Found");
        if(args[0]==="api" && args[1].includes("/issues/")) return "0";
        return "";
      };
      const ops = createProductionReconcileOps({ runGh: mockGh, runGit: baseRunner, repoRoot, claimantLogin:"bot" });
      const deleted = await ops.deleteBranch("sandcastle/issue-935");
      expect(deleted).toBe(false);
      // branch should be deleted locally but provenance remains as directory
      const brAfter = baseRunner(["branch","--list","sandcastle/issue-935"]);
      expect(brAfter.stdout.trim()).toBe("");
      // provenance path is directory, so existsSync true but is directory
      expect(fsSync.existsSync(provPath)).toBe(true);
      expect(fsSync.statSync(provPath).isDirectory()).toBe(true);
      expect(released).toBe(false);
      expect(commented).toBe(false);
      // via empty_branch path also
      const stale:any={number:935,title:"t",state:"open",labels:["ready-for-agent","agent:in-progress"],assignees:["bot"],body:"",blockedByCount:0};
      // For empty_branch, hasCommitsAhead will be empty (no work), but deleteBranch will still fail due to provenance dir
      const r = await reconcileStaleImplementation(stale, "sandcastle/issue-935", { ...ops, github: fakeGithubTransitions });
      expect(r.reconciled).toBe(false);
      expect(r.factoryError).toBe(true);
      expect(released).toBe(false);
      expect(commented).toBe(false);
      // cleanup directory for next
      try { fsSync.rmdirSync(provPath); } catch {}
    } finally { cleanup(); }
  });
});
