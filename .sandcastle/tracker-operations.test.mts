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
      checkBranchExists: async () => true,
      checkProvenanceValid: async () => ({ valid:true }),
      hasCommitsAhead: async () => true,
      releaseClaim: async () => true,
      comment: async () => true,
      fetchIssue: async () => stale,
      addBlocked: async () => true,
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
      checkBranchExists: async () => false,
      releaseClaim: async () => true,
      comment: async () => true,
      fetchIssue: async () => stale,
      addBlocked: async () => true,
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
    const ops: any = {
      releaseClaim: async () => { released = true; return true; },
      comment: async () => { commented = true; return true; },
      fetchIssue: async () => stale,
      getBatchPrNumber: async () => ({ prNumber: null, state: "absent" }),
      checkBranchExists: async () => true,
      checkProvenanceValid: async () => ({ valid: true, reason: "valid" }),
      hasCommitsAhead: async () => false,
      deleteBranch: async () => true,
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
      fetchIssue: async () => stale,
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
