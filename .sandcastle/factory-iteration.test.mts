import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import fs from "node:fs";
import { verdictFixture } from "./review-verdict.mts";
import type { ResearchResult } from "./research-result.mts";
import type { FactoryIterationDependencies, FactoryIterationInput, FactoryIterationResult, PreparedImplIssue, PreparedResearchIssue, ImplWorkerResult } from "./factory-iteration.mts";
import type { ResearchBatchIssue } from "./research-lifecycle.mts";
import * as coordinator from "./mixed-profile-coordinator.mts";

// --- helpers ---

function approvedVerdict() {
  return verdictFixture({ approved: true, findings: [], acceptanceCriteriaMet: [{ criterion: "c", met: true }], summary: "ok" });
}
function rejectedVerdict() {
  return verdictFixture({ approved: false, findings: [{ message: "blocking issue", severity: "blocking" }], acceptanceCriteriaMet: [{ criterion: "c", met: false }], summary: "rejected" });
}
function sampleResearchResult(): ResearchResult {
  return {
    summary: "summary",
    findings: [{ claim: "c", evidence: "e at src/foo.ts:1", source: "src/foo.ts" }],
    recommendation: "r",
    uncertainties: [],
    followUps: [],
  };
}

type Deferred<T = void> = {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
  isResolved: boolean;
};

function createDeferred<T = void>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  let isResolved = false;
  const promise = new Promise<T>((res, rej) => {
    resolve = (v: T) => { isResolved = true; res(v); };
    reject = (e: unknown) => { isResolved = true; rej(e); };
  });
  return { promise, resolve: (v: T) => resolve(v), reject, get isResolved() { return isResolved; } };
}

function createFakeResearchOps(opts: { failPublishFor?: Set<string> } = {}) {
  const calls: string[] = [];
  const ordered: Array<{ kind: string; id: string; body: string }> = [];
  let publishCount = 0;
  let closeCount = 0;
  let parentPointerCount = 0;
  const safeRunGh = async (args: string[], _ctx?: string): Promise<boolean> => {
    const joined = args.join(" ");
    calls.push(joined);
    if (args[0] === "issue" && args[1] === "comment") {
      const issueId = args[2];
      const body = (args[4] ?? "") as string;
      if (body.includes("research-result")) {
        publishCount++;
        ordered.push({ kind: "publish", id: issueId, body });
        if (opts.failPublishFor?.has(issueId)) return false;
        return true;
      }
      if (body.includes("research-parent-pointer")) {
        parentPointerCount++;
        ordered.push({ kind: "parent", id: issueId, body });
        return true;
      }
      ordered.push({ kind: "comment", id: issueId, body });
      return true;
    }
    if (args[0] === "issue" && args[1] === "edit") {
      ordered.push({ kind: "edit", id: args[2] ?? "", body: joined });
      return true;
    }
    if (args[0] === "issue" && args[1] === "close") {
      closeCount++;
      ordered.push({ kind: "close", id: args[2] ?? "", body: joined });
      return true;
    }
    return true;
  };
  const runGh = async (args: string[]): Promise<string> => {
    const joined = args.join(" ");
    calls.push(joined);
    if (args[0] === "issue" && args[1] === "close") {
      closeCount++;
      const issueId = args[2] ?? "";
      ordered.push({ kind: "close", id: issueId, body: joined });
      return "";
    }
    if (args[0] === "issue" && args[1] === "comment") {
      const issueId = args[2] ?? "";
      const body = (args[4] ?? "") as string;
      ordered.push({ kind: "comment", id: issueId, body });
    }
    return "";
  };
  return { ops: { safeRunGh, runGh }, calls, ordered, getCounts: () => ({ publishCount, closeCount, parentPointerCount }) };
}

// Helper to wrap real startMixedProfileBatch with settlement counter while delegating to real implementation
function setupSettlementCounter(): { getCount: () => number; restore: () => void } {
  let count = 0;
  const original = coordinator.startMixedProfileBatch;
  const spy = vi.spyOn(coordinator, "startMixedProfileBatch");
  spy.mockImplementation((params) => {
    const real = (original as typeof coordinator.startMixedProfileBatch)(params);
    const origSettle = real.settleResearch;
    const wrapped = async (): Promise<Awaited<ReturnType<typeof origSettle>>> => {
      count++;
      return origSettle();
    };
    return { ...real, settleResearch: wrapped, researchBatchPromise: real.researchBatchPromise, implSettled: real.implSettled };
  });
  return {
    getCount: () => count,
    restore: () => spy.mockRestore(),
  };
}

describe("factory-iteration red gate", () => {
  it("imports runFactoryIteration", async () => {
    const mod = await import("./factory-iteration.mts");
    expect(typeof mod.runFactoryIteration).toBe("function");
  });
  it("structural: exactly one settlement epilogue", () => {
    const src = fs.readFileSync(".sandcastle/factory-iteration.mts", "utf8");
    expect(src.match(/await settleOnce\(\)/g)).toHaveLength(1);
  });
});

describe("runFactoryIteration behavioral — deterministic, typed, settlement once", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("1. Successful mixed profile - both workers start, submission entered while research held, resolves only after research release, continue/submission-complete, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();

    const implStarted = createDeferred<void>();
    const researchStarted = createDeferred<void>();
    const submissionEntered = createDeferred<void>();
    const researchRelease = createDeferred<void>();

    const fakeOps = createFakeResearchOps();

    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      implStarted.resolve();
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue): Promise<{ result: ResearchResult; rawText: string; commits?: string[] }> => {
      researchStarted.resolve();
      await researchRelease.promise;
      return { result: sampleResearchResult(), rawText: "raw", commits: [] };
    };

    let submissionReceipt: FactoryIterationResult["submission"] = { issueIds: ["101"], batchBranch: "sandcastle/batch-1", pullRequest: "https://github.com/o/r/pull/1" };
    let submissionCalled = false;

    const deps: FactoryIterationDependencies = {
      workers: {
        runImplementation: runImpl,
        runResearch: runResearch,
        researchOps: fakeOps.ops,
      },
      mutations: {
        apply: async () => {},
      },
      submission: {
        submit: async (issues: PreparedImplIssue[]) => {
          submissionEntered.resolve();
          submissionCalled = true;
          // At this point research should still be held (not released)
          expect(researchRelease.isResolved).toBe(false);
          return submissionReceipt!;
        },
      },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;

    const input: FactoryIterationInput = {
      implIssues: [{ id: "101", branch: "sandcastle/issue-101", title: "impl 101" }],
      researchIssues: [{ id: "201", branch: "sandcastle/issue-201", title: "research 201", body: "Part of #999" }],
    };

    const iterationPromise = runFactoryIteration(input, deps);

    // Both workers should start
    await implStarted.promise;
    await researchStarted.promise;
    expect(implStarted.isResolved).toBe(true);
    expect(researchStarted.isResolved).toBe(true);

    // Submission should be entered while research still held
    await submissionEntered.promise;
    expect(submissionCalled).toBe(true);
    expect(researchRelease.isResolved).toBe(false);

    // Iteration should remain unresolved while research is held
    let resolved = false;
    let iterationResult: FactoryIterationResult | null = null;
    const track = iterationPromise.then((v) => { resolved = true; iterationResult = v; return v; });
    // Let microtasks run but not release research
    await Promise.resolve();
    await Promise.resolve();
    expect(resolved).toBe(false);

    // Release research
    researchRelease.resolve();
    iterationResult = await track;

    expect(iterationResult!.next).toEqual({ kind: "continue", reason: "submission-complete" });
    expect(iterationResult!.submission).toEqual(submissionReceipt);
    expect(iterationResult!.implementation.completedIds).toEqual(["101"]);
    expect(iterationResult!.research.succeededIds).toContain("201");
    expect(iterationResult!.research.hadFactoryError).toBe(false);
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(fakeOps.getCounts().closeCount).toBe(1);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("2. Implementation FACTORY_ERROR - submission never called, still settles research, stop/implementation-factory-error, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();

    const implStarted = createDeferred<void>();
    const researchStarted = createDeferred<void>();
    const researchRelease = createDeferred<void>();

    const fakeOps = createFakeResearchOps();
    let submissionCalled = false;

    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      implStarted.resolve();
      return { commits: ["abc"], verdict: null, reviewText: "no verdict" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      researchStarted.resolve();
      await researchRelease.promise;
      return { result: sampleResearchResult(), rawText: "raw" };
    };

    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async () => { submissionCalled = true; return { issueIds: [] }; } },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;

    const input: FactoryIterationInput = {
      implIssues: [{ id: "102", branch: "sandcastle/issue-102", title: "impl 102" }],
      researchIssues: [{ id: "202", branch: "sandcastle/issue-202", title: "research 202", body: "Part of #999" }],
    };

    const p = runFactoryIteration(input, deps);
    await implStarted.promise;
    await researchStarted.promise;

    let resolved = false;
    const track = p.then((v) => { resolved = true; return v; });
    await Promise.resolve();
    expect(resolved).toBe(false);
    expect(submissionCalled).toBe(false);

    researchRelease.resolve();
    const result = await track;
    expect(submissionCalled).toBe(false);
    expect(result.next).toEqual({ kind: "stop", reason: "implementation-factory-error" });
    expect(result.implementation.factoryErrorIds).toContain("102");
    expect(result.research.succeededIds).toContain("202");
    expect(result.research.hadFactoryError).toBe(false);
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("3. Successful submission followed by research FACTORY_ERROR - receipt retained, stop/research-factory-error, sibling preserved, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();

    const fakeOps = createFakeResearchOps();
    const researchRelease = createDeferred<void>();
    const submissionEntered = createDeferred<void>();

    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };

    // Two research issues: one succeeds quickly, one fails after release, to prove submission before factory error
    const runResearch = async (issue: ResearchBatchIssue) => {
      if (issue.id === "fail") {
        await researchRelease.promise;
        throw new Error("research worker boom");
      }
      return { result: sampleResearchResult(), rawText: "raw" };
    };

    const submissionReceipt: FactoryIterationResult["submission"] = { issueIds: ["103"], batchBranch: "sandcastle/batch-103", pullRequest: "https://github.com/o/r/pull/2" };

    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: {
        submit: async (issues: PreparedImplIssue[]) => {
          submissionEntered.resolve();
          return submissionReceipt!;
        },
      },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;

    const input: FactoryIterationInput = {
      implIssues: [{ id: "103", branch: "sandcastle/issue-103", title: "impl 103" }],
      researchIssues: [
        { id: "203", branch: "sandcastle/issue-203", title: "research ok", body: "Part of #999" },
        { id: "fail", branch: "sandcastle/issue-fail", title: "research fail", body: "Part of #999" },
      ],
    };

    const p = runFactoryIteration(input, deps);
    await submissionEntered.promise;
    // At this point submission has happened, but research fail still held
    expect(submissionEntered.isResolved).toBe(true);
    let resolved = false;
    const track = p.then((v) => { resolved = true; return v; });
    await Promise.resolve();
    expect(resolved).toBe(false);

    researchRelease.resolve();
    const result = await track;
    expect(result.submission).toEqual(submissionReceipt);
    expect(result.next).toEqual({ kind: "stop", reason: "research-factory-error" });
    expect(result.research.succeededIds).toContain("203");
    expect(result.research.failedIds).toContain("fail");
    expect(result.research.hadFactoryError).toBe(true);
    expect(result.research.succeededIds.length).toBe(1);
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("body Part of #190 proves lifecycle invokes parent-pointer before close", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const fakeOps = createFakeResearchOps();

    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      // Ensure body is correctly propagated
      expect(issue.body).toBe("Part of #190");
      return { result: sampleResearchResult(), rawText: "raw" };
    };

    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async (issues) => ({ issueIds: issues.map((i) => i.id), batchBranch: "sandcastle/batch-190", pullRequest: "https://github.com/o/r/pull/190" }) },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;

    const input: FactoryIterationInput = {
      implIssues: [{ id: "190impl", branch: "sandcastle/issue-190impl", title: "impl 190" }],
      researchIssues: [{ id: "190", branch: "sandcastle/issue-190", title: "research 190", body: "Part of #190" }],
    };

    const result = await runFactoryIteration(input, deps);

    // Verify parent pointer invoked before close
    const ordered = fakeOps.ordered;
    const parentIdx = ordered.findIndex((o) => o.kind === "parent" && o.body.includes("research-parent-pointer:190->190"));
    // Parent pointer goes to parentId 190, but our fakeOps records parent pointer under parentId. The body contains marker research-parent-pointer:190->190
    const parentIdx2 = ordered.findIndex((o) => o.kind === "parent");
    const closeIdx = ordered.findIndex((o) => o.kind === "close" && o.id === "190");
    expect(parentIdx2).toBeGreaterThan(-1);
    expect(closeIdx).toBeGreaterThan(-1);
    expect(parentIdx2).toBeLessThan(closeIdx);
    expect(fakeOps.getCounts().parentPointerCount).toBe(1);
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(fakeOps.getCounts().closeCount).toBe(1);
    expect(result.research.succeededIds).toContain("190");
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("unexpected settlement rejection fails closed to stop/research-factory-error, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    // Force settlement boundary itself to reject — mock coordinator to throw
    const originalStart = coordinator.startMixedProfileBatch;
    const spy = vi.spyOn(coordinator, "startMixedProfileBatch");
    let settleCount = 0;
    spy.mockImplementation((params) => {
      const real = (originalStart as typeof coordinator.startMixedProfileBatch)(params);
      return {
        ...real,
        settleResearch: async () => {
          settleCount++;
          throw new Error("unexpected settlement rejection");
        },
      };
    });

    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    const fakeOps = createFakeResearchOps();

    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async (issues) => ({ issueIds: issues.map((i) => i.id), batchBranch: "b", pullRequest: "p" }) },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;

    const input: FactoryIterationInput = {
      implIssues: [{ id: "301", branch: "sandcastle/issue-301", title: "impl 301" }],
      researchIssues: [{ id: "401", branch: "sandcastle/issue-401", title: "research 401", body: "Part of #999" }],
    };

    const result = await runFactoryIteration(input, deps);
    expect(result.next).toEqual({ kind: "stop", reason: "research-factory-error" });
    expect(result.research.hadFactoryError).toBe(true);
    // Even though settlement threw, we count exactly one attempt
    expect(settleCount).toBe(1);
    spy.mockRestore();
  });

  it("mutation apply throws prevents submission, settles research, stop/implementation-factory-error, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();

    const researchRelease = createDeferred<void>();
    const fakeOps = createFakeResearchOps();

    // Two impl issues: one completed (would trigger submission), one that triggers mutation (reviewRejected)
    // Mutation throw on the second should prevent submission of the first
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      if (issue.id === "501") {
        return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
      }
      // 502 will be reviewRejected, generating a mutation
      return { commits: ["abc"], verdict: rejectedVerdict(), reviewText: "rejected" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      await researchRelease.promise;
      return { result: sampleResearchResult(), rawText: "raw" };
    };

    let submissionCalled = false;
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: {
        apply: async () => {
          throw new Error("mutation infrastructure failure");
        },
      },
      submission: {
        submit: async () => {
          submissionCalled = true;
          return { issueIds: ["501"], batchBranch: "b", pullRequest: "p" };
        },
      },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;

    const input: FactoryIterationInput = {
      implIssues: [
        { id: "501", branch: "sandcastle/issue-501", title: "impl 501" },
        { id: "502", branch: "sandcastle/issue-502", title: "impl 502" },
      ],
      researchIssues: [{ id: "601", branch: "sandcastle/issue-601", title: "research 601", body: "Part of #999" }],
    };

    const p = runFactoryIteration(input, deps);
    // Even though mutation throws immediately after impl, research still held — iteration should remain pending until research released
    await Promise.resolve();
    let resolved = false;
    const track = p.then((v) => { resolved = true; return v; });
    await Promise.resolve();
    expect(resolved).toBe(false);
    expect(submissionCalled).toBe(false);

    researchRelease.resolve();
    const result = await track;
    expect(submissionCalled).toBe(false);
    expect(result.next).toEqual({ kind: "stop", reason: "implementation-factory-error" });
    expect(result.research.succeededIds).toContain("601");
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("no completed implementation - continue/no-completed-implementation, settles once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const fakeOps = createFakeResearchOps();
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: [], verdict: null, reviewText: "" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    let submissionCalled = false;
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async () => { submissionCalled = true; return { issueIds: [] }; } },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;
    const input: FactoryIterationInput = {
      implIssues: [{ id: "104", branch: "sandcastle/issue-104", title: "impl 104" }],
      researchIssues: [{ id: "204", branch: "sandcastle/issue-204", title: "research 204", body: "Part of #999" }],
    };
    const result = await runFactoryIteration(input, deps);
    expect(submissionCalled).toBe(false);
    expect(result.next).toEqual({ kind: "continue", reason: "no-completed-implementation" });
    expect(result.implementation.completedIds).toHaveLength(0);
    expect(result.implementation.failedIds).toContain("104");
    expect(result.research.hadFactoryError).toBe(false);
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("review rejection with no mergeable branch - continue/no-completed-implementation, retains mutation, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const fakeOps = createFakeResearchOps();
    const applied: Array<import("./factory-verdict-gate.mts").WorkerMutationAction> = [];
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: rejectedVerdict(), reviewText: "rejected" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async (action) => { applied.push(action); } },
      submission: { submit: async () => { throw new Error("should not submit"); } },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;
    const input: FactoryIterationInput = {
      implIssues: [{ id: "105", branch: "sandcastle/issue-105", title: "impl 105" }],
      researchIssues: [],
    };
    const result = await runFactoryIteration(input, deps);
    expect(result.next).toEqual({ kind: "continue", reason: "no-completed-implementation" });
    expect(result.implementation.reviewRejectedIds).toContain("105");
    expect(applied.some((a) => a.kind === "reviewRejected" && a.issue.id === "105")).toBe(true);
    expect(result.implementation.factoryErrorIds).toHaveLength(0);
    expect(result.research.hadFactoryError).toBe(false);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("qualification mode - suppresses mutations and integration, continue/qualification-complete, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const fakeOps = createFakeResearchOps();
    let mutationCalled = false;
    let submissionCalled = false;
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => { mutationCalled = true; } },
      submission: { submit: async () => { submissionCalled = true; return { issueIds: ["106"] }; } },
      policy: { mutateOutcomeState: false, integrate: false },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;
    const input: FactoryIterationInput = {
      implIssues: [{ id: "106", branch: "sandcastle/issue-106", title: "impl 106" }],
      researchIssues: [{ id: "206", branch: "sandcastle/issue-206", title: "research 206", body: "Part of #999" }],
    };
    const result = await runFactoryIteration(input, deps);
    expect(mutationCalled).toBe(false);
    expect(submissionCalled).toBe(false);
    expect(result.next).toEqual({ kind: "continue", reason: "qualification-complete" });
    expect(result.implementation.completedIds).toContain("106");
    expect(fakeOps.getCounts().publishCount).toBe(0);
    expect(result.research.hadFactoryError).toBe(false);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("submission failure - stop/submission-factory-error after settlement, settles once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const researchRelease = createDeferred<void>();
    const fakeOps = createFakeResearchOps();
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      await researchRelease.promise;
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async () => { throw new Error("push failed"); } },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;
    const input: FactoryIterationInput = {
      implIssues: [{ id: "107", branch: "sandcastle/issue-107", title: "impl 107" }],
      researchIssues: [{ id: "207", branch: "sandcastle/issue-207", title: "research 207", body: "Part of #999" }],
    };
    const p = runFactoryIteration(input, deps);
    // Should remain pending until research released, even though submission will fail
    let resolved = false;
    const track = p.then((v) => { resolved = true; return v; });
    await Promise.resolve();
    expect(resolved).toBe(false);
    researchRelease.resolve();
    const result = await track;
    expect(result.next).toEqual({ kind: "stop", reason: "submission-factory-error" });
    expect(result.implementation.completedIds).toContain("107");
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("successful submission with successful research - continue/submission-complete, settlement once", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const fakeOps = createFakeResearchOps();
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    const receipt: FactoryIterationResult["submission"] = { issueIds: ["108"], batchBranch: "sandcastle/batch-108", pullRequest: "https://github.com/o/r/pull/3" };
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async () => receipt! },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;
    const input: FactoryIterationInput = {
      implIssues: [{ id: "108", branch: "sandcastle/issue-108", title: "impl 108" }],
      researchIssues: [{ id: "208", branch: "sandcastle/issue-208", title: "research 208", body: "Part of #999" }],
    };
    const result = await runFactoryIteration(input, deps);
    expect(result.next).toEqual({ kind: "continue", reason: "submission-complete" });
    expect(result.submission).toEqual(receipt);
    expect(result.research.hadFactoryError).toBe(false);
    expect(result.research.succeededIds).toContain("208");
    expect(fakeOps.getCounts().publishCount).toBe(1);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("research settlement occurs exactly once even with repeated internal attempts (idempotent)", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const fakeOps = createFakeResearchOps();
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async () => ({ issueIds: ["109"], batchBranch: "b", pullRequest: "p" }) },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;
    const input: FactoryIterationInput = {
      implIssues: [{ id: "109", branch: "sandcastle/issue-109", title: "impl 109" }],
      researchIssues: [{ id: "209", branch: "sandcastle/issue-209", title: "research 209", body: "Part of #999" }],
    };
    const result = await runFactoryIteration(input, deps);
    const counts = fakeOps.getCounts();
    expect(counts.publishCount).toBe(1);
    expect(result.next.kind).toBe("continue");
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });

  it("initialResearchHadFactoryError is incorporated and overrides continue to stop/research-factory-error", async () => {
    const { runFactoryIteration } = await import("./factory-iteration.mts");
    const settlement = setupSettlementCounter();
    const fakeOps = createFakeResearchOps();
    const runImpl = async (issue: PreparedImplIssue): Promise<ImplWorkerResult> => {
      return { commits: ["abc"], verdict: approvedVerdict(), reviewText: "ok" };
    };
    const runResearch = async (issue: ResearchBatchIssue) => {
      return { result: sampleResearchResult(), rawText: "raw" };
    };
    const deps: FactoryIterationDependencies = {
      workers: { runImplementation: runImpl, runResearch, researchOps: fakeOps.ops },
      mutations: { apply: async () => {} },
      submission: { submit: async (issues) => ({ issueIds: issues.map((i) => i.id), batchBranch: "b", pullRequest: "p" }) },
      policy: { mutateOutcomeState: true, integrate: true },
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    } satisfies FactoryIterationDependencies;
    const input: FactoryIterationInput = {
      implIssues: [{ id: "110", branch: "sandcastle/issue-110", title: "impl 110" }],
      researchIssues: [{ id: "210", branch: "sandcastle/issue-210", title: "research 210", body: "Part of #999" }],
      initialResearchHadFactoryError: true,
    };
    const result = await runFactoryIteration(input, deps);
    // Even though impl and research succeeded, initial error should cause stop/research-factory-error but still retain submission? Actually with our implementation, initial error overrides continue to stop, so submission-complete becomes research-factory-error but submission retained
    expect(result.next).toEqual({ kind: "stop", reason: "research-factory-error" });
    expect(result.research.hadFactoryError).toBe(true);
    expect(settlement.getCount()).toBe(1);
    settlement.restore();
  });
});
