import { describe, it, expect } from "vitest";
import * as fs from "node:fs";

/**
 * Regression 1: Research worker must be single iteration (maxIterations: 1)
 * - Sandcastle structured output requires maxIterations === 1
 * - <research> extraction tag is not a completion signal; <promise>COMPLETE is.
 * - With maxIterations 30, valid <research> on iteration 1 loops 29 more times (1 hour wasted)
 */
describe("Research single-iteration regression", () => {
  it("researcher uses maxIterations 1 (not 30) with Output.string tag research", () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    // Find researcher block
    const researcherBlock = main.match(/name:\s*"researcher"[\s\S]{0,300}maxIterations:\s*(\d+)[\s\S]{0,500}output:\s*sandcastle\.Output\.string\(\{\s*tag:\s*"research"\s*\}\)/);
    expect(researcherBlock, "researcher block with maxIterations and Output.string research not found").not.toBeNull();
    const iterations = Number(researcherBlock![1]);
    expect(iterations).toBe(1);
    // Also ensure not 30 anywhere for researcher
    expect(main).not.toMatch(/name:\s*"researcher"[\s\S]*?maxIterations:\s*30/);
  });

  it("single valid <research> invokes Muse exactly once and proceeds immediately to publication", async () => {
    // Behavioral: simulate sandcastle loop — with maxIterations 1, one valid <research> should succeed immediately
    // With maxIterations 30 and no <promise>COMPLETE, sandcastle would loop 30 times
    // This test proves the factory does not loop: it calls runWorker once and publishes
    const { orchestrateResearchBatch } = await import("./research-lifecycle.mts");
    const { extractResearchResult } = await import("./research-result.mts");
    // Fake store
    const calls: string[] = [];
    let workerInvocations = 0;
    const fakeOps = {
      safeRunGh: async (args: string[]) => { calls.push(args.join(" ")); return true; },
      runGh: async (args: string[]) => { calls.push(args.join(" ")); return ""; },
    };
    const runWorker = async (issue: { id: string; branch: string }) => {
      workerInvocations++;
      // Simulate sandcastle returning valid <research> on first invocation (no <promise>COMPLETE)
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
    // Should have invoked exactly once, not 30 times, and succeeded
    expect(workerInvocations).toBe(1);
    expect(batch.succeededIds).toContain("991");
    expect(batch.hadFactoryError).toBe(false);
    // Publication should have happened (research-result marker comment)
    expect(calls.some(c => c.includes("research-result:991"))).toBe(true);
    // Structured output validation still strict — invalid JSON should be FACTORY_ERROR, not success
    const badWorker = async () => {
      const raw = `<research>not-json</research>`;
      const extracted = extractResearchResult({ output: raw, text: raw, stdout: raw });
      if (!extracted) throw new Error("invalid");
      return { result: extracted, rawText: raw };
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

/**
 * Regression 2: Implementation and research must run overlapped (4-way concurrency)
 * - Current code: await researchBatch then await implBatch => max 3 concurrent, #184 idle
 * - Fixed: start both promises together => max 4 concurrent (3 research + 1 impl)
 */
describe("Research + implementation concurrency regression", () => {
  it("research and implementation batches run concurrently (4-way overlap), not sequentially", async () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    // Sequential bug: const batch = await orchestrateResearchBatch(...)\n ... const settled = await Promise.allSettled
    const sequentialPattern = /const batch = await orchestrateResearchBatch[\s\S]*?const settled = await Promise\.allSettled/s;
    expect(main).not.toMatch(sequentialPattern);

    // Must have concurrent launch: both promises started before await
    // Check for concurrent Promise.all that awaits both research and impl together
    const hasResearchPromise = main.includes("researchBatchPromise") || main.includes("researchPromise");
    const hasImplPromise = main.includes("implSettledPromise") || main.includes("implPromise");
    const hasConcurrentAll = /Promise\.all\s*\(\s*\[[\s\S]*?researchBatchPromise[\s\S]*?implSettledPromise/s.test(main) ||
                          /Promise\.all\s*\(\s*\[[\s\S]*?implSettledPromise[\s\S]*?researchBatchPromise/s.test(main) ||
                          /Promise\.all\s*\(\s*\[[\s\S]*?orchestrateResearchBatch[\s\S]*?Promise\.allSettled/s.test(main);
    expect(hasResearchPromise, "missing researchBatchPromise").toBe(true);
    expect(hasImplPromise, "missing implSettledPromise").toBe(true);
    expect(hasConcurrentAll, "main.mts should launch research and implementation batches concurrently via Promise.all([researchBatchPromise, implSettledPromise])").toBe(true);
  });

  it("concurrent launch achieves 4-way overlap (3 research + 1 impl simultaneously active)", async () => {
    // Behavioral harness: simulate factory's concurrent orchestration
    // This test will fail if implementation waits for research (sequential), pass if concurrent
    const { orchestrateResearchBatch } = await import("./research-lifecycle.mts");
    let active = 0;
    let maxActive = 0;
    const track = async (label: string) => {
      active++;
      maxActive = Math.max(maxActive, active);
      await new Promise(r => setTimeout(r, 40));
      active--;
    };
    // Research workers: 3 that each take 40ms
    const researchIssues = ["201","202","203"].map(id => ({ id, branch: `sandcastle/issue-${id}`, title: `R${id}`, body: "Part of #22" }));
    const fakeOps = { safeRunGh: async () => true, runGh: async () => "" };
    const researchWorker = async (issue: { id: string }) => {
      await track(`research-${issue.id}`);
      return { result: { summary: "s", findings: [{ claim: "c", evidence: "e", source: "s" }], recommendation: "r", uncertainties: [], followUps: [] }, rawText: "raw" };
    };
    // Implementation worker: 1 that takes 40ms, overlaps with research
    const implWorker = async (issue: { id: string; branch: string }) => {
      await track(`impl-${issue.id}`);
      return { commits: ["abc"], verdict: { approved: true } } as any;
    };

    // Simulate sequential bug: await research then await impl => maxActive = 3
    const sequential = async () => {
      active = 0; maxActive = 0;
      await orchestrateResearchBatch({ issues: researchIssues, runWorker: researchWorker, ops: fakeOps });
      await Promise.allSettled([{ id: "184", branch: "sandcastle/issue-184" }].map(implWorker));
      return maxActive;
    };
    const seqMax = await sequential();
    expect(seqMax).toBe(3); // sequential cannot reach 4

    // Simulate fixed concurrent: Promise.all([researchBatch, implBatch])
    const concurrent = async () => {
      active = 0; maxActive = 0;
      const researchPromise = orchestrateResearchBatch({ issues: researchIssues, runWorker: researchWorker, ops: fakeOps });
      const implPromise = Promise.allSettled([{ id: "184", branch: "sandcastle/issue-184" }].map(implWorker));
      await Promise.all([researchPromise, implPromise]);
      return maxActive;
    };
    const concMax = await concurrent();
    expect(concMax).toBe(4); // concurrent reaches 4

    // Now verify that the factory's actual main.mts would achieve 4 if it uses concurrent pattern
    // This assertion will pass only after main.mts is fixed
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    const isConcurrent = !/const batch = await orchestrateResearchBatch[\s\S]*?const settled = await Promise\.allSettled/.test(main);
    expect(isConcurrent).toBe(true);
    // If sequential, the factory's maxActive would be 3, not 4 — we prove the fix enables 4
    expect(concMax).toBeGreaterThan(seqMax);
  });
});
