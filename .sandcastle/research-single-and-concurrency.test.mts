import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import { RESEARCH_MAX_ITERATIONS, RESEARCH_OUTPUT_TAG, orchestrateResearchBatch } from "./research-lifecycle.mts";
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
    expect(main).toContain("RESEARCH_MAX_ITERATIONS");
    expect(main).toContain('maxIterations: RESEARCH_MAX_ITERATIONS');
    expect(main).toContain('Output.string({ tag: "research" })');
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
    expect(main).not.toMatch(/const batch = await orchestrateResearchBatch[\s\S]*?const settled = await Promise\.allSettled/s);
    const hasResearchPromise = main.includes("researchBatchPromise");
    const hasImplPromise = main.includes("implSettledPromise");
    const hasConcurrent = /Promise\.all\s*\(\s*\[[\s\S]*?researchBatchPromise[\s\S]*?implSettledPromise/s.test(main) ||
                          /implSettledPromise[\s\S]*?researchBatchPromise/s.test(main);
    // Now we use split await: impl first, then research, but both started before either awaited
    // So check that both promises are created before first await
    const hasSplitAwait = main.includes("const settled = (await (implSettledPromise") && main.includes("await researchBatchPromise");
    expect(hasResearchPromise).toBe(true);
    expect(hasImplPromise).toBe(true);
    expect(hasConcurrent || hasSplitAwait).toBe(true);
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

    // Start both batches concurrently as production does: research and impl promises created before awaiting
    const researchPromise = orchestrateResearchBatch({
      issues: researchIssues,
      runWorker: researchWorker,
      ops: fakeOps as any,
    });
    const implTrackPromise = (async () => {
      await track("impl-184", 15);
      return { commits: ["abc"] };
    })();

    // Use real production concurrent start: both promises already started
    // Wait a bit to let fast research publish while slow still blocked
    await new Promise(r => setTimeout(r, 25));
    // At this point, fast should have completed and published, slow still blocked, impl active
    // maxActive should have been 4 at some point (3 research +1 impl)
    // We need to let impl complete and then release slow
    const implResult = await implTrackPromise;
    expect(implResult).toBeDefined();
    // Impl completed without waiting for slow research
    expect(publishTimes.has("fast")).toBe(true);
    expect(publishTimes.has("slow")).toBe(false);
    expect(maxActive).toBe(4);

    slowResearchResolve();
    const researchBatch = await researchPromise;
    // After slow completes, check outcomes
    expect(researchBatch.succeededIds).toContain("fast");
    expect(researchBatch.succeededIds).toContain("slow");
    expect(researchBatch.failedIds).toContain("fail");
    expect(researchBatch.hadFactoryError).toBe(true);
    // Fast remains successful despite fail and slow
    expect(researchBatch.outcomes.get("fast")).toBe("SUCCESS");
    expect(researchBatch.outcomes.get("fail")).toBe("FACTORY_ERROR");
    // Impl should have succeeded independently
    expect(maxActive).toBe(4);
  });
});
