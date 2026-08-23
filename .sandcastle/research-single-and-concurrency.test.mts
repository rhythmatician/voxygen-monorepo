import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import { RESEARCH_MAX_ITERATIONS, RESEARCH_OUTPUT_TAG, orchestrateResearchBatch } from "./research-lifecycle.mts";
import { coordinateMixedProfileBatch, startMixedProfileBatch } from "./mixed-profile-coordinator.mts";
import { extractResearchResult } from "./research-result.mts";

/**
 * Production-shaped regression: single iteration
 * - Sandcastle structured output requires maxIterations === 1
 * - One valid <research> must succeed in one sandbox.run call, not 30
 */
describe("Research single-iteration production-shaped", () => {
  it("RESEARCH_MAX_ITERATIONS is 1 and RESEARCH_OUTPUT_TAG is research", async () => {
    expect(RESEARCH_MAX_ITERATIONS).toBe(1);
    expect(RESEARCH_OUTPUT_TAG).toBe("research");
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    // After #192, research uses the typed adapter (runStructuredOnce) which injects maxIterations:1 and Output.string
    expect(main).toContain("runStructuredOnce");
    expect(main).toContain('tag: "research"');
    // The adapter owns the run-mode fields; main must not directly contain the old invalid combination
    expect(main).not.toContain('maxIterations: RESEARCH_MAX_ITERATIONS');
    // The adapter file itself still constructs the real Output
    const adapter = fs.readFileSync(".sandcastle/agent-run-contracts.mts", "utf8");
    expect(adapter).toContain("Output.string");
    expect(adapter).toContain("maxIterations: 1");
  });

  it("production research worker spec uses maxIterations 1: one valid <research> invokes sandbox.run exactly once", async () => {
    // Simulate Sandcastle's iteration semantics:
    // - With maxIterations 1, one valid structured output succeeds immediately
    // - With maxIterations 30 and no <promise>COMPLETE, Sandcastle would loop 30 times
    // Our production spec must be 1, so fake Sandcastle should be called once
    let sandboxRunCalls = 0;
    const fakeSandboxRun = async (spec: { maxIterations: number; output?: unknown }) => {
      sandboxRunCalls++;
      // Simulate Sandcastle behavior: if maxIterations !==1 and output is research without promise, loop
      if (spec.maxIterations !== 1) {
        // Simulate 30 iterations: would call agent 30 times
        sandboxRunCalls += 29; // simulate loop
        throw new Error("maxIterations 30 with <research> would loop 30 times without <promise>COMPLETE");
      }
      // Valid research output succeeds in one call
      return {
        output: `<research>{"summary":"s","findings":[{"claim":"c","evidence":"e","source":"s"}],"recommendation":"r","uncertainties":[],"followUps":[]}</research>`,
      };
    };

    // Use production constant
    const spec = { maxIterations: RESEARCH_MAX_ITERATIONS, output: { tag: RESEARCH_OUTPUT_TAG } };
    const result = await fakeSandboxRun(spec as any);
    expect(sandboxRunCalls).toBe(1);
    expect(result.output).toContain("<research>");
    const extracted = extractResearchResult({ output: result.output, text: result.output, stdout: result.output });
    expect(extracted).not.toBeNull();
    expect(extracted!.summary).toBe("s");
  });

  it("single valid <research> invokes Muse exactly once and proceeds immediately to publication (production orchestration)", async () => {
    const calls: string[] = [];
    let workerInvocations = 0;
    const fakeOps = {
      safeRunGh: async (args: string[]) => { calls.push(args.join(" ")); return true; },
      runGh: async (args: string[]) => { calls.push(args.join(" ")); return ""; },
    };
    const runWorker = async (issue: { id: string; branch: string }) => {
      workerInvocations++;
      const raw = `<research>{"summary":"s","findings":[{"claim":"c","evidence":"e","source":"s"}],"recommendation":"r","uncertainties":[],"followUps":[]}</research>`;
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      if (!extracted) throw new Error("invalid");
      return { result: extracted, rawText: raw };
    };
    const batch = await orchestrateResearchBatch({
      issues: [{ id: "991", branch: "sandcastle/issue-991", title: "R", body: "Part of #22" }],
      runWorker,
      ops: fakeOps,
    });
    expect(workerInvocations).toBe(1);
    expect(batch.succeededIds).toContain("991");
    expect(batch.hadFactoryError).toBe(false);
    expect(calls.some(c => c.includes("research-result:991"))).toBe(true);
    // Invalid JSON still strict FACTORY_ERROR
    const badWorker = async () => {
      const raw = `<research>not-json</research>`;
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      if (!extracted) throw new Error("invalid");
      return { result: extracted!, rawText: raw };
    };
    const badBatch = await orchestrateResearchBatch({
      issues: [{ id: "992", branch: "sandcastle/issue-992", title: "R", body: "Part of #22" }],
      runWorker: badWorker,
      ops: fakeOps,
    });
    expect(badBatch.hadFactoryError).toBe(true);
    expect(badBatch.failedIds).toContain("992");
  });
});

describe("Research independent per-ticket publication", () => {
  it("fast research publishes and closes while slow researcher still blocked (no global barrier)", async () => {
    // This test proves the fix for global completion barrier:
    // With old code, all workers done then loop publishing sequential, so fast #1 waits for slow #2
    // With new per-pipeline, fast #1 publishes immediately
    const publishTimes = new Map<string, number>();
    const fakeOps = {
      safeRunGh: async (args: string[]) => {
        const body = args[4] || "";
        if (body.includes("research-result")) {
          const m = body.match(/research-result:(\S+)/);
          if (m) {
            const id = m[1].replace(/[^a-zA-Z0-9-]/g, "");
            publishTimes.set(id, Date.now());
          }
        }
        return true;
      },
      runGh: async (args: string[]) => {
        if (args[1] === "close") {
          const id = args[2];
          publishTimes.set(`close-${id}`, Date.now());
        }
        return "";
      },
    };
    let slowResolve: () => void = () => {};
    const slowBarrier = new Promise<void>(res => { slowResolve = res; });
    const runWorker = async (issue: { id: string; branch: string }) => {
      if (issue.id === "slow") {
        await slowBarrier;
      } else {
        await new Promise(r => setTimeout(r, 10));
      }
      const raw = `<research>{"summary":"s","findings":[{"claim":"c","evidence":"e","source":"s"}],"recommendation":"r","uncertainties":[],"followUps":[]}</research>`;
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      return { result: extracted!, rawText: raw };
    };
    const batchPromise = orchestrateResearchBatch({
      issues: [
        { id: "fast1", branch: "sandcastle/issue-fast1", title: "R", body: "Part of #22" },
        { id: "slow", branch: "sandcastle/issue-slow", title: "R", body: "Part of #22" },
        { id: "fast2", branch: "sandcastle/issue-fast2", title: "R", body: "Part of #22" },
      ],
      runWorker,
      ops: fakeOps as any,
    });
    // Give fast workers time to complete and publish while slow still blocked
    await new Promise(r => setTimeout(r, 50));
    // At this point, fast1 and fast2 should have published, slow not yet
    expect(publishTimes.has("fast1")).toBe(true);
    expect(publishTimes.has("fast2")).toBe(true);
    expect(publishTimes.has("slow")).toBe(false);
    // Now release slow
    slowResolve();
    const batch = await batchPromise;
    expect(batch.succeededIds.sort()).toEqual(["fast1","fast2","slow"]);
    expect(publishTimes.has("slow")).toBe(true);
    // Verify fast publish happened before slow publish
    const fastTime = publishTimes.get("fast1")!;
    const slowTime = publishTimes.get("slow")!;
    expect(fastTime).toBeLessThan(slowTime);
  });
});

describe("Research + implementation concurrency via production coordinator", () => {
  it("research and implementation batches run concurrently via production Promise.all", async () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    const factoryIteration = fs.readFileSync(".sandcastle/factory-iteration.mts", "utf8");
    expect(main).not.toMatch(/const batch = await orchestrateResearchBatch[\s\S]*?const settled = await Promise\.allSettled/s);
    // After extraction, main uses runFactoryIteration, factory-iteration uses startMixedProfileBatch
    expect(main).toContain("runFactoryIteration");
    expect(main).toMatch(/import\s*\{[^}]*runFactoryIteration[^}]*\}\s*from\s*["']\.\/factory-iteration\.mts["']/);
    expect(main).toMatch(/await runFactoryIteration\(/);
    expect(factoryIteration).toContain("startMixedProfileBatch");
    expect(factoryIteration).toMatch(/startMixedProfileBatch\(/);
    expect(factoryIteration).toContain("mixed.implSettled");
    expect(factoryIteration).toContain("mixed.settleResearch");
    // Production must use the genuine helper so tests exercise identical code via factory-iteration
    const usesHelper = factoryIteration.includes("startMixedProfileBatch");
    expect(usesHelper).toBe(true);
  });

  it("production mixed-profile: 3 research + 1 impl achieve 4-way overlap, impl not blocked by slow research, fast research publishes early, failure isolated", async () => {
    let active = 0;
    let maxActive = 0;
    const publishTimes = new Map<string, number>();
    const fakeOps = {
      safeRunGh: async (args: string[]) => {
        if (args[3] === "--body" && args[4]?.includes("research-result")) {
          const m = args[4].match(/research-result:(\S+)/);
          if (m) publishTimes.set(m[1], Date.now());
        }
        return true;
      },
      runGh: async () => "",
    };
    let slowResearchResolve: () => void = () => {};
    const slowBarrier = new Promise<void>(res => { slowResearchResolve = res; });

    const track = async (label: string, ms: number) => {
      active++;
      maxActive = Math.max(maxActive, active);
      await new Promise(r => setTimeout(r, ms));
      active--;
    };

    const researchWorker = async (issue: { id: string; branch: string }) => {
      if (issue.id === "slow") {
        await track(`research-${issue.id}`, 5);
        await slowBarrier;
        await track(`research-${issue.id}-publish`, 5);
      } else if (issue.id === "fail") {
        await track(`research-${issue.id}`, 10);
        throw new Error("fail");
      } else {
        await track(`research-${issue.id}`, 10);
      }
      const raw = `<research>{"summary":"s","findings":[{"claim":"c","evidence":"e","source":"s"}],"recommendation":"r","uncertainties":[],"followUps":[]}</research>`;
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      return { result: extracted!, rawText: raw };
    };

    const implWorker = async (issue: { id: string; branch: string }) => {
      await track(`impl-${issue.id}`, 20);
      return { commits: ["abc"], verdict: { approved: true } } as any;
    };

    const researchIssues = [
      { id: "fast", branch: "sandcastle/issue-fast", title: "R", body: "Part of #22" },
      { id: "slow", branch: "sandcastle/issue-slow", title: "R", body: "Part of #22" },
      { id: "fail", branch: "sandcastle/issue-fail", title: "R", body: "Part of #22" },
    ];

    // Use production seam so tests exercise identical code to main.mts
    const mixed = startMixedProfileBatch({
      researchIssues,
      implIssues: [{ id: "184", branch: "sandcastle/issue-184", title: "Impl" }],
      runResearchWorker: researchWorker as any,
      runImplWorker: implWorker as any,
      ops: fakeOps as any,
      shouldMutateOutcomeState: true,
    });

    // Wait a bit to let fast research publish while slow still blocked — impl runs concurrently
    await new Promise(r => setTimeout(r, 25));
    const implSettled = await mixed.implSettled;
    expect(implSettled[0].status).toBe("fulfilled");
    // Impl completed without waiting for slow research
    expect(publishTimes.has("fast")).toBe(true);
    expect(publishTimes.has("slow")).toBe(false);
    expect(maxActive).toBe(4);

    slowResearchResolve();
    const { researchBatch } = await mixed.settleResearch();
    // After slow completes, check outcomes
    expect(researchBatch!.succeededIds).toContain("fast");
    expect(researchBatch!.succeededIds).toContain("slow");
    expect(researchBatch!.failedIds).toContain("fail");
    expect(researchBatch!.hadFactoryError).toBe(true);
    // Fast remains successful despite fail and slow
    expect(researchBatch!.outcomes.get("fast")).toBe("SUCCESS");
    expect(researchBatch!.outcomes.get("fail")).toBe("FACTORY_ERROR");
    // Impl should have succeeded independently
    expect(maxActive).toBe(4);
  });
});

describe("Production coordinator research-only and failure isolation", () => {
  it("research-only: 3 research, 0 impl — all researchers launch before next iteration, fast publishes independently", async () => {
    const publishOrder: string[] = [];
    const fakeOps = {
      safeRunGh: async (args: string[]) => {
        if (args[3] === "--body" && args[4]?.includes("research-result")) {
          const m = args[4].match(/research-result:(\S+)/);
          if (m) publishOrder.push(m[1].replace(/[^a-zA-Z0-9]/g, ""));
        }
        return true;
      },
      runGh: async () => "",
    };
    let slowResolve: () => void = () => {};
    const slowBarrier = new Promise<void>(res => { slowResolve = res; });
    const runResearch = async (issue: { id: string; branch: string }) => {
      if (issue.id === "slow") await slowBarrier;
      else await new Promise(r => setTimeout(r, 10));
      const raw = `<research>{"summary":"s","findings":[{"claim":"c","evidence":"e","source":"s"}],"recommendation":"r","uncertainties":[],"followUps":[]}</research>`;
      const { extractResearchResult } = await import("./research-result.mts");
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      return { result: extracted!, rawText: raw };
    };
    const batchPromise = orchestrateResearchBatch({
      issues: [
        { id: "fast1", branch: "sandcastle/issue-fast1", title: "R", body: "Part of #22" },
        { id: "slow", branch: "sandcastle/issue-slow", title: "R", body: "Part of #22" },
        { id: "fast2", branch: "sandcastle/issue-fast2", title: "R", body: "Part of #22" },
      ],
      runWorker: runResearch,
      ops: fakeOps as any,
    });
    // Fast should publish before slow is released
    await new Promise(r => setTimeout(r, 30));
    expect(publishOrder).toContain("fast1");
    expect(publishOrder).toContain("fast2");
    expect(publishOrder).not.toContain("slow");
    slowResolve();
    const batch = await batchPromise;
    expect(batch.succeededIds.sort()).toEqual(["fast1","fast2","slow"]);
    // Verify no next iteration before all settle — batch only resolves after all 3 settle
    // This proves epilogue: research-only does not continue before all settle
    expect(batch.hadFactoryError).toBe(false);
  });

  it("mixed + impl failure: 3 research, 1 impl FACTORY_ERROR — research still settled correctly, no new work claimed", async () => {
    const fakeOps = {
      safeRunGh: async () => true,
      runGh: async () => "",
    };
    let active = 0;
    let maxActive = 0;
    const track = async (ms: number) => {
      active++;
      maxActive = Math.max(maxActive, active);
      await new Promise(r => setTimeout(r, ms));
      active--;
    };
    const runResearch = async (issue: { id: string; branch: string }) => {
      if (issue.id === "fail") {
        await track(10);
        throw new Error("research fail");
      }
      await track(15);
      const raw = `<research>{"summary":"s","findings":[{"claim":"c","evidence":"e","source":"s"}],"recommendation":"r","uncertainties":[],"followUps":[]}</research>`;
      const { extractResearchResult } = await import("./research-result.mts");
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      return { result: extracted!, rawText: raw };
    };
    const runImpl = async () => {
      await track(20);
      throw new Error("impl factory error");
    };
    // Use production seam: startMixedProfileBatch so tests exercise identical code to main.mts
    const mixed = startMixedProfileBatch({
      researchIssues: [
        { id: "r1", branch: "sandcastle/issue-r1", title: "R", body: "Part of #22" },
        { id: "r2", branch: "sandcastle/issue-r2", title: "R", body: "Part of #22" },
        { id: "fail", branch: "sandcastle/issue-fail", title: "R", body: "Part of #22" },
      ],
      implIssues: [{ id: "184", branch: "sandcastle/issue-184", title: "Impl" }],
      runResearchWorker: runResearch as any,
      runImplWorker: runImpl as any,
      ops: fakeOps,
      shouldMutateOutcomeState: true,
    });
    const implSettled = await mixed.implSettled;
    const { researchBatch, researchHadFactoryError } = await mixed.settleResearch();
    expect(implSettled[0].status).toBe("rejected");
    expect(researchBatch).not.toBeNull();
    expect(researchBatch!.succeededIds).toContain("r1");
    expect(researchBatch!.succeededIds).toContain("r2");
    expect(researchBatch!.failedIds).toContain("fail");
    expect(researchHadFactoryError).toBe(true);
    // Max concurrency should be 4 (3 research +1 impl) despite impl failure
    expect(maxActive).toBe(4);
  });

  it("mixed + impl success via coordinator: impl does not wait for slow research", async () => {
    const fakeOps = { safeRunGh: async () => true, runGh: async () => "" };
    let slowResolve: () => void = () => {};
    const slowBarrier = new Promise<void>(res => { slowResolve = res; });
    let implEndTime = 0;
    let slowPublishTime = 0;
    const runResearch = async (issue: { id: string; branch: string }) => {
      if (issue.id === "slow") {
        await slowBarrier;
      } else {
        await new Promise(r => setTimeout(r, 10));
      }
      const raw = `<research>{"summary":"s","findings":[{"claim":"c","evidence":"e","source":"s"}],"recommendation":"r","uncertainties":[],"followUps":[]}</research>`;
      const { extractResearchResult } = await import("./research-result.mts");
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      // Record publish time via ops side effect
      if (issue.id === "slow") slowPublishTime = Date.now();
      return { result: extracted!, rawText: raw };
    };
    // Wrap ops to capture slow publish
    const wrappedOps = {
      safeRunGh: async (args: string[]) => {
        if (args[4]?.includes("research-result:slow")) slowPublishTime = Date.now();
        return true;
      },
      runGh: async () => "",
    };
    const runImpl = async () => {
      await new Promise(r => setTimeout(r, 20));
      implEndTime = Date.now();
      return { commits: ["abc"], verdict: { approved: true } };
    };
    // Use production seam: startMixedProfileBatch genuinely starts both together
    const mixed = startMixedProfileBatch({
      researchIssues: [
        { id: "fast", branch: "sandcastle/issue-fast", title: "R", body: "Part of #22" },
        { id: "slow", branch: "sandcastle/issue-slow", title: "R", body: "Part of #22" },
      ],
      implIssues: [{ id: "184", branch: "sandcastle/issue-184", title: "Impl" }],
      runResearchWorker: runResearch as any,
      runImplWorker: runImpl as any,
      ops: wrappedOps as any,
      shouldMutateOutcomeState: true,
    });
    const implSettledPromise = mixed.implSettled;
    // Impl should finish before slow research's publish — impl not blocked by slow research
    await new Promise(r => setTimeout(r, 30));
    // At this point impl should have finished (20ms) but slow still blocked
    expect(implEndTime).toBeGreaterThan(0);
    // Slow publish not yet
    expect(slowPublishTime).toBe(0);
    // Ensure impl settled without waiting for slow research
    const implSettled = await implSettledPromise;
    expect(implSettled[0].status).toBe("fulfilled");
    expect(implSettled[0].status).toBe("fulfilled");
    slowResolve();
    const { researchBatch } = await mixed.settleResearch();
    expect(researchBatch!.succeededIds).toContain("fast");
    expect(researchBatch!.succeededIds).toContain("slow");
    // Impl end time should be before slow publish time
    expect(implEndTime).toBeLessThan(slowPublishTime);
  });
});

describe("Coordinator structural seam — production consumer", () => {
  it("mixed-profile-coordinator has a non-test production consumer", () => {
    const files = fs.readdirSync(".sandcastle").filter(f => f.endsWith(".mts") && !f.endsWith(".test.mts") && f !== "mixed-profile-coordinator.mts");
    const consumers = files.filter(f => {
      const content = fs.readFileSync(`.sandcastle/${f}`, "utf8");
      return content.includes('from "./mixed-profile-coordinator') || content.includes("from './mixed-profile-coordinator");
    });
    expect(consumers).toContain("factory-iteration.mts");
    expect(consumers).not.toContain("main.mts");
    // Verify the exact exported seam is used via factory-iteration
    const factoryIteration = fs.readFileSync(".sandcastle/factory-iteration.mts", "utf8");
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    expect(factoryIteration).toMatch(/import\s*\{[^}]*startMixedProfileBatch[^}]*\}\s*from\s*["']\.\/mixed-profile-coordinator\.mts["']/);
    expect(factoryIteration).toMatch(/const mixed = startMixedProfileBatch\(/);
    expect(factoryIteration).toMatch(/await mixed\.implSettled/);
    expect(factoryIteration).toMatch(/await mixed\.settleResearch\(\)/);
    expect(main).toMatch(/import\s*\{[^}]*runFactoryIteration[^}]*\}\s*from\s*["']\.\/factory-iteration\.mts["']/);
    expect(main).toMatch(/await runFactoryIteration\(/);
    // Ensure duplicate scheduling is removed — main must not directly orchestrate research or impl
    expect(main).not.toMatch(/researchBatchPromise\s*=\s*orchestrateResearchBatch/);
    expect(main).not.toMatch(/implSettledPromise\s*=\s*Promise\.allSettled/);
    expect(main).not.toMatch(/const mixed = startMixedProfileBatch/);
    expect(main).not.toContain("mixed.implSettled");
    expect(main).not.toContain("mixed.settleResearch");
  });
});

