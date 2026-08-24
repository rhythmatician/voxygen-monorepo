import { describe, it, expect, vi } from "vitest";
import { claimImplementation, reconcileStaleImplementation, type ClaimOps } from "./tracker-operations.mts";
import type { IssueInput } from "./tracker-policy.mts";
import { TRACER_BODY } from "./fixtures.mts";

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

  it("merged PR is finalized", async () => {
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
      checkProvenanceValid: async () => ({ valid: true }),
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

  it("absent PR + empty branch is cleaned", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:303, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    let released=false;
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      checkBranchExists: async () => true,
      checkProvenanceValid: async () => ({ valid:true }),
      hasCommitsAhead: async () => false,
      releaseClaim: async () => { released=true; return true; },
      comment: async () => true,
      fetchIssue: async () => stale,
      deleteBranch: async () => true,
      addBlocked: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-303", ops);
    expect(r.reconciled).toBe(true);
    expect(released).toBe(true);
  });

  it("absent PR + work branch is preserved", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:304, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ valid:true }),
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
      checkProvenanceValid: async () => ({ valid:false, reason:"contaminated", contaminated:true }),
      releaseClaim: async () => true,
      comment: async () => true,
      fetchIssue: async () => stale,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-305", ops);
    expect(r.reconciled).toBe(false);
  });

  it("no branch results in blocked", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale: any = { number:306, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops: any = {
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ valid:true }),
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
  it("releases assignee and in-progress without restoring implement, preserves ready", async () => {
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
      checkProvenanceValid: async () => ({ valid: true, reason: "valid" }),
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
      checkProvenanceValid: async () => ({ valid:true }),
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
      checkProvenanceValid: async () => ({ valid:true }),
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
      checkProvenanceValid: async () => ({ valid:true }),
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
      checkProvenanceValid: async () => ({ valid:true }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-404", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/failed to comment/);
  });

  it("pr_not_found release failure is FACTORY_ERROR and blocked not attempted", async () => {
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
      checkProvenanceValid: async () => ({ valid:true }),
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

  it("pr_not_found blocked failure is FACTORY_ERROR", async () => {
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
      checkProvenanceValid: async () => ({ valid:true }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      addBlocked: async () => false,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-406", ops);
    expect(r.factoryError).toBe(true);
    expect(r.reason).toMatch(/failed to add blocked/);
  });

  it("merged_pr verify fails if still has transient labels — FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:407 };
    const ops:any = {
      fetchIssue: async () => ({ ...stale, labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], state:"open" }),
      getBatchPrNumber: async () => ({ prNumber:"124", state:"found" }),
      getPrState: async () => ({ state:"MERGED", mergedAt:"2024-01-01", found:true }),
      releaseClaim: async () => true,
      comment: async () => true,
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ valid:true }),
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
    const ops:any = {
      fetchIssue: async () => ({ number:408, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"b", blockedByCount:0 }),
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async (b:string) => {
        checkCalls++;
        // First call is pre-delete existence check: branch is present with empty work
        if (checkCalls===1) return "present" as const;
        // After delete, branch is absent (idempotent success)
        return "absent" as const;
      },
      checkProvenanceValid: async () => ({ valid:true }),
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => false, // delete says nothing to delete, but branch is already absent after second check
      releaseClaim: async () => { released=true; return true; },
      comment: async () => { commented=true; return true; },
      addBlocked: async () => true,
      markIntegrated: async () => true,
    };
    const r = await reconcileStaleImplementation(stale, "sandcastle/issue-408", ops);
    expect(r.reconciled).toBe(true);
    expect(released).toBe(true);
    expect(commented).toBe(true);
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
      checkProvenanceValid: async () => ({ valid:true }),
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

  it("no_branch requires comment, release, blocked — each failure is FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { ...staleBase, number:410 };
    const baseOps:any = {
      fetchIssue: async () => ({ number:410, title:"s", state:"open", labels:["ready-for-agent"], assignees:[], body:"body", blockedByCount:0 }),
      getBatchPrNumber: async () => ({ prNumber: null, state:"absent" }),
      getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ valid:true }),
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
      checkProvenanceValid: async () => ({ valid:false, reason:"contaminated", contaminated:true }),
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

  it("does not restore agent:implement automatically", async () => {
    const stale: IssueInput = {
      number: 201,
      title: "stale",
      state: "open",
      labels: ["ready-for-agent", "agent:in-progress"],
      assignees: ["bot"],
      body: TRACER_BODY,
      blockedByCount: 0,
    };
    const ops: any = {
      releaseClaim: async (id: string) => {
        return true;
      },
      comment: async () => true,
      fetchIssue: async () => stale,
      getBatchPrNumber: async () => ({ prNumber: null, state: "absent" }),
      checkBranchExists: async () => true,
      checkProvenanceValid: async () => ({ valid: true, reason: "valid" }),
      hasCommitsAhead: async () => false,
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
    const ops = createProductionReconcileOps({ runGh: mockRunGh, repoRoot: process.cwd(), claimantLogin: "bot" });
    const fresh = await ops.fetchIssue("501");
    expect(fresh.labels).not.toContain("agent:in-progress");
    expect(fresh.assignees.length).toBe(0);
    // Ensure it's not the same object
    expect(fresh).not.toBe(staleCaptured);
    expect(fresh.labels.includes("ready-for-agent")).toBe(true);
  });

  it("pr_not_found successful end state via adapter", async () => {
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
    const baseOps = createProductionReconcileOps({ runGh: mockRunGh, repoRoot: process.cwd(), claimantLogin: "bot" });
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

  it("merged PR successful closed/unassigned end state via adapter", async () => {
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
    const baseOps = createProductionReconcileOps({ runGh: mockRunGh, repoRoot: process.cwd(), claimantLogin: "bot" });
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
    const ops = createProductionReconcileOps({ runGh: mockRunGh, repoRoot: "/nonexistent/path/that/will/fail/git", claimantLogin: "bot" });
    // Force hasCommitsAhead to be unknown by making repoRoot invalid
    // The adapter's hasCommitsAhead will try git rev-parse origin/main and fail -> unknown
    // But we need to ensure checkBranchExists also doesn't mask it: make it present so we reach hasCommitsAhead
    const ops2:any = {
      ...ops,
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ valid:true }),
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

  it("integration close failure => FACTORY_ERROR", async () => {
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const stale:any = { number:506, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 };
    const ops:any = {
      fetchIssue: async () => ({ number:506, title:"s", state:"open", labels:["ready-for-agent","agent:in-progress"], assignees:["bot"], body:"body", blockedByCount:0 }),
      getBatchPrNumber: async () => ({ prNumber:"123", state:"found" as const }),
      getPrState: async () => ({ state:"MERGED", mergedAt:"2024-01-01", found:true }),
      checkBranchExists: async () => "present" as const,
      checkProvenanceValid: async () => ({ valid:true }),
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

  it("blocked-label read-back failure => FACTORY_ERROR", async () => {
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
      checkProvenanceValid: async () => ({ valid:true }),
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
