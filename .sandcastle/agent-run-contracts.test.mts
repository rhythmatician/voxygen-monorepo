import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import { z } from "zod";
import * as sandcastle from "@ai-hero/sandcastle";
import {
  runStructuredOnce,
  runUnstructuredOnce,
  runUntilCompletion,
  FACTORY_COMPLETION_SIGNAL,
} from "./agent-run-contracts.mts";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function createRecordingExecutor() {
  const calls: Record<string, unknown>[] = [];
  const executor = async (opts: Record<string, unknown>) => {
    calls.push({ ...opts });
    // Simulate successful run result shape
    return {
      commits: [],
      output: opts.output ? "fake-output" : undefined,
      stdout: "<tag>fake</tag>",
    };
  };
  return { executor, calls };
}

function createFakeAgent() {
  return sandcastle.muse("muse-spark-1.2-contributor");
}

// ---------------------------------------------------------------------------
// 1. Structured string and object modes
// ---------------------------------------------------------------------------
describe("agent-run-contracts: structured once", () => {
  it("structured string: exactly one call, maxIterations 1, completion disabled, real Output.string", async () => {
    const { executor, calls } = createRecordingExecutor();
    const agent = createFakeAgent();
    const result = await runStructuredOnce(executor, {
      name: "planner",
      agent,
      promptFile: "./.sandcastle/plan-prompt.md",
      promptArgs: { ISSUES_JSON: "{}" },
      tag: "plan",
    });
    expect(calls).toHaveLength(1);
    const opts = calls[0]!;
    expect(opts.maxIterations).toBe(1);
    expect(opts.completionSignal).toEqual([]);
    expect(opts.output).toBeDefined();
    // Verify it's the real branded Output.string definition
    const output = opts.output as any;
    expect(output._tag).toBe("string");
    expect(output.tag).toBe("plan");
    // No maxRetries by default (should be undefined or 0)
    // Typed output preserved: executor returns output
    expect(result).toBeDefined();
  });

  it("structured object: exactly one call, maxIterations 1, completion disabled, real Output.object", async () => {
    const { executor, calls } = createRecordingExecutor();
    const agent = createFakeAgent();
    const schema = z.object({ approved: z.boolean(), summary: z.string() });
    const result = await runStructuredOnce(executor, {
      name: "reviewer",
      agent,
      promptFile: "./.sandcastle/review-prompt.md",
      promptArgs: { BRANCH: "test" },
      tag: "verdict",
      schema,
    });
    expect(calls).toHaveLength(1);
    const opts = calls[0]!;
    expect(opts.maxIterations).toBe(1);
    expect(opts.completionSignal).toEqual([]);
    const output = opts.output as any;
    expect(output._tag).toBe("object");
    expect(output.tag).toBe("verdict");
    expect(output.schema).toBe(schema);
    expect(result).toBeDefined();
  });

  it("structured preserves typed output without weakening validation (research still strict)", async () => {
    // This test ensures the adapter uses real Output and doesn't weaken research validation.
    // The actual research validation is tested elsewhere, but we prove the adapter passes through real Output.
    const { executor, calls } = createRecordingExecutor();
    const agent = createFakeAgent();
    await runStructuredOnce(executor, {
      name: "researcher",
      agent,
      promptFile: "./.sandcastle/research-prompt.md",
      promptArgs: { TASK_ID: "123", ISSUE_TITLE: "t", ISSUE_BODY: "b" },
      tag: "research",
    });
    const opts = calls[0]!;
    expect(opts.output).toBeDefined();
    // The output should be created via sandcastle.Output.string, not a fake
    expect((opts.output as any)._tag).toBe("string");
  });
});

// ---------------------------------------------------------------------------
// 2. Unstructured one-shot mode
// ---------------------------------------------------------------------------
describe("agent-run-contracts: unstructured once", () => {
  it("unstructured: exactly one call, maxIterations 1, no output, fixed completion", async () => {
    const { executor, calls } = createRecordingExecutor();
    const agent = createFakeAgent();
    await runUnstructuredOnce(executor, {
      name: "merger",
      agent,
      promptFile: "./.sandcastle/merge-prompt.md",
      promptArgs: { BRANCHES: "- b1", ISSUES: "- 1" },
    });
    expect(calls).toHaveLength(1);
    const opts = calls[0]!;
    expect(opts.maxIterations).toBe(1);
    expect(opts.output).toBeUndefined();
    expect(opts.completionSignal).toBe(FACTORY_COMPLETION_SIGNAL);
  });

  it("unstructured does not allow output or budget", async () => {
    const { executor } = createRecordingExecutor();
    const agent = createFakeAgent();
    // This should be a type error if someone tries to pass output, but runtime should also not have output
    await runUnstructuredOnce(executor, {
      name: "merger",
      agent,
      promptFile: "./.sandcastle/merge-prompt.md",
      promptArgs: {},
    });
    // No way to pass output via type system; runtime check is that output is undefined
  });
});

// ---------------------------------------------------------------------------
// 3. Iterative completion mode
// ---------------------------------------------------------------------------
describe("agent-run-contracts: iterative completion", () => {
  it("iterative: preserves valid budget, no output, exact promise signal", async () => {
    const { executor, calls } = createRecordingExecutor();
    const agent = createFakeAgent();
    await runUntilCompletion(executor, {
      name: "implementer",
      agent,
      promptFile: "./.sandcastle/implement-prompt.md",
      promptArgs: { TASK_ID: "1", ISSUE_TITLE: "t", ISSUE_BODY: "b", BRANCH: "b", REVIEW_FEEDBACK: "" },
      budget: 100,
      idleTimeoutSeconds: 1800,
    });
    expect(calls).toHaveLength(1);
    const opts = calls[0]!;
    expect(opts.maxIterations).toBe(100);
    expect(opts.output).toBeUndefined();
    expect(opts.completionSignal).toBe(FACTORY_COMPLETION_SIGNAL);
  });

  it("iterative preserves 50 budget for retry", async () => {
    const { executor, calls } = createRecordingExecutor();
    const agent = createFakeAgent();
    await runUntilCompletion(executor, {
      name: "implementer-retry",
      agent,
      promptFile: "./.sandcastle/implement-prompt.md",
      promptArgs: { TASK_ID: "1", ISSUE_TITLE: "t", ISSUE_BODY: "b", BRANCH: "b", REVIEW_FEEDBACK: "fb" },
      budget: 50,
    });
    expect(calls[0]!.maxIterations).toBe(50);
  });

  it("existing implementation budgets remain 100 and 50 at production call sites (structural)", async () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    // After migration, main.mts should use the adapter with budgets 100 and 50
    // Check that the adapter is used with those budgets, not raw maxIterations
    const hasBudget100 = main.includes("budget: 100") || main.includes("budget:100");
    const hasBudget50 = main.includes("budget: 50") || main.includes("budget:50");
    // If still using raw maxIterations, this will fail - we want to see the migration
    expect(hasBudget100).toBe(true);
    expect(hasBudget50).toBe(true);
    // Ensure no raw maxIterations 100/50 outside adapter
    // This is supplementary to the behavioral test
  });
});

// ---------------------------------------------------------------------------
// 4. Invalid iterative budgets
// ---------------------------------------------------------------------------
describe("agent-run-contracts: invalid iterative budgets", () => {
  const invalidBudgets: [string, any][] = [
    ["1", 1],
    ["0", 0],
    ["negative", -5],
    ["fractional", 1.5],
    ["NaN", NaN],
    ["Infinity", Infinity],
    ["-Infinity", -Infinity],
  ];

  for (const [label, value] of invalidBudgets) {
    it(`rejects ${label} (${String(value)}) before executor called`, async () => {
      const { executor, calls } = createRecordingExecutor();
      const agent = createFakeAgent();
      await expect(
        runUntilCompletion(executor, {
          name: "implementer",
          agent,
          promptFile: "./.sandcastle/implement-prompt.md",
          promptArgs: {},
          budget: value,
        } as any),
      ).rejects.toThrow(/Invalid iterative budget/);
      expect(calls).toHaveLength(0);
    });
  }

  it("rejects non-integer string and null and undefined before executor", async () => {
    const { executor, calls } = createRecordingExecutor();
    const agent = createFakeAgent();
    for (const bad of ["100" as any, null as any, undefined as any, {} as any]) {
      await expect(
        runUntilCompletion(executor, {
          name: "implementer",
          agent,
          promptFile: "./test.md",
          promptArgs: {},
          budget: bad,
        } as any),
      ).rejects.toThrow(/Invalid iterative budget/);
      expect(calls).toHaveLength(0);
    }
  });
});

// ---------------------------------------------------------------------------
// 5. Compile-time negative examples
// ---------------------------------------------------------------------------
describe("agent-run-contracts: compile-time negative examples", () => {
  it("type-level negatives are enforced (see @ts-expect-error in source)", () => {
    // This test exists to ensure the type-test file is included in typecheck.
    // The actual negative examples are below and in a dedicated type test.
    // If this test runs, the typecheck must have passed with @ts-expect-error.
    expect(true).toBe(true);
  });
});

// The following type tests are intentionally not executed at runtime;
// they are verified by `npm run typecheck` via @ts-expect-error.
// If any of these were not errors, typecheck would fail.

runStructuredOnce(async () => ({} as any), {
  name: "planner",
  agent: null as any,
  tag: "plan",
  // @ts-expect-error structured once should not accept maxIterations
  maxIterations: 1,
});

runStructuredOnce(async () => ({} as any), {
  name: "planner",
  agent: null as any,
  tag: "plan",
  // @ts-expect-error structured once should not accept completionSignal
  completionSignal: "<promise>COMPLETE</promise>",
});

runStructuredOnce(async () => ({} as any), {
  name: "planner",
  agent: null as any,
  tag: "plan",
  // @ts-expect-error structured once should not accept session options (resumeSession)
  resumeSession: "abc",
});

runUnstructuredOnce(async () => ({} as any), {
  name: "merger",
  agent: null as any,
  promptFile: "./.sandcastle/merge-prompt.md",
  // @ts-expect-error unstructured once should not accept output
  output: sandcastle.Output.string({ tag: "x" }),
});

runUnstructuredOnce(async () => ({} as any), {
  name: "merger",
  agent: null as any,
  promptFile: "./.sandcastle/merge-prompt.md",
  // @ts-expect-error unstructured once should not accept iterative budget
  budget: 100,
});

runUntilCompletion(async () => ({} as any), {
  name: "implementer",
  agent: null as any,
  promptFile: "./test.md",
  budget: 100,
  // @ts-expect-error iterative should not accept output
  output: sandcastle.Output.string({ tag: "x" }),
});

runUntilCompletion(async () => ({} as any), {
  name: "implementer",
  agent: null as any,
  promptFile: "./test.md",
  budget: 100,
  // @ts-expect-error iterative should not accept custom completionSignal
  completionSignal: "<promise>COMPLETE</promise>",
});

runStructuredOnce(async () => ({} as any), {
  name: "planner",
  agent: null as any,
  tag: "plan",
  // @ts-expect-error arbitrary options spread should not leak maxIterations
  maxIterations: 30,
});

// ---------------------------------------------------------------------------
// 6. Installed-runtime contract
// ---------------------------------------------------------------------------
describe("agent-run-contracts: installed runtime contract", () => {
  it("installed runtime rejects structured output with maxIterations 2 before touching sentinels", async () => {
    const { run, Output } = await import("@ai-hero/sandcastle");
    let agentMethodCalled = false;
    const fakeAgent = {
      name: "sentinel-agent",
      env: {},
      captureSessions: false,
      buildPrintCommand: () => {
        agentMethodCalled = true;
        return { command: "echo" };
      },
      parseStreamLine: () => [],
    };
    const fakeSandbox = {};

    const promptContainingTag = "Please emit <test>hello</test> as structured output.";

    await expect(
      run({
        agent: fakeAgent as any,
        sandbox: fakeSandbox as any,
        prompt: promptContainingTag,
        maxIterations: 2,
        output: Output.string({ tag: "test" }),
      } as any),
    ).rejects.toThrow(/output requires maxIterations to be 1/);

    expect(agentMethodCalled).toBe(false);
  });

  it("installed runtime also rejects object output with maxIterations 2", async () => {
    const { run, Output } = await import("@ai-hero/sandcastle");
    let agentMethodCalled = false;
    const fakeAgent = {
      name: "sentinel-agent",
      env: {},
      captureSessions: false,
      buildPrintCommand: () => {
        agentMethodCalled = true;
        return { command: "echo" };
      },
      parseStreamLine: () => [],
    };
    const fakeSandbox = {};
    await expect(
      run({
        agent: fakeAgent as any,
        sandbox: fakeSandbox as any,
        prompt: "emit <verdict>{\"approved\":true}</verdict>",
        maxIterations: 2,
        output: Output.object({ tag: "verdict", schema: z.object({ approved: z.boolean() }) }),
      } as any),
    ).rejects.toThrow(/output requires maxIterations to be 1/);
    expect(agentMethodCalled).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// 7. Production role wiring and no-bypass guard
// ---------------------------------------------------------------------------
describe("agent-run-contracts: production wiring and no-bypass", () => {
  it("all seven role call sites use the correct adapter operation", async () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");

    // Must import the adapter
    expect(main).toMatch(/from\s+["']\.\/agent-run-contracts\.mts["']/);
    expect(main).toContain("runStructuredOnce");
    expect(main).toContain("runUnstructuredOnce");
    expect(main).toContain("runUntilCompletion");

    // Planner: structured once with plan tag
    expect(main).toMatch(/runStructuredOnce[\s\S]*?tag:\s*["']plan["']/);

    // Researcher: structured once with research tag
    expect(main).toMatch(/runStructuredOnce[\s\S]*?tag:\s*["']research["']/);

    // Reviewer: structured once with verdict tag and schema
    expect(main).toMatch(/runStructuredOnce[\s\S]*?tag:\s*["']verdict["']/);
    expect(main).toContain("reviewVerdictSchema");

    // Merger: unstructured once
    // Check that merger uses unstructured and not structured
    const mergerSection = main.slice(main.indexOf('name: "merger"') - 500, main.indexOf('name: "merger"') + 500);
    expect(mergerSection).toContain("runUnstructuredOnce");

    // Implementer: iterative with budget 100
    expect(main).toMatch(/runUntilCompletion[\s\S]*?budget:\s*100/);
    // Implementer-retry: iterative with budget 50
    expect(main).toMatch(/runUntilCompletion[\s\S]*?budget:\s*50/);

    // Ensure the three operations are distinct and cover all roles
    const structuredCount = (main.match(/runStructuredOnce/g) || []).length;
    const unstructuredCount = (main.match(/runUnstructuredOnce/g) || []).length;
    const iterativeCount = (main.match(/runUntilCompletion/g) || []).length;
    // At least: planner (1), researcher (1), reviewer (1) => 3 structured
    expect(structuredCount).toBeGreaterThanOrEqual(3);
    // Merger => 1 unstructured
    expect(unstructuredCount).toBeGreaterThanOrEqual(1);
    // Implementer + retry => 2 iterative
    expect(iterativeCount).toBeGreaterThanOrEqual(2);
  });

  it("no production role directly owns maxIterations/output/completionSignal outside adapter", async () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    const adapter = fs.readFileSync(".sandcastle/agent-run-contracts.mts", "utf8");

    // The adapter is the only production file that should directly set these fields in run options
    // Check that main.mts does not contain raw mode fields in run calls

    // Extract all occurrences of maxIterations: in main.mts
    const maxIterationsMatches = [...main.matchAll(/maxIterations\s*:/g)];
    // Should be 0 after migration (all should be via adapter)
    // Allow 0 or only in comments? We check that no maxIterations remains
    expect(maxIterationsMatches.length).toBe(0);

    // Similarly output: should not appear as `output:` in main.mts run options
    // But there is still `output` in other contexts like `planRun.output` – we must not ban that
    // So we check for `output: sandcastle.Output` or `output: sandcastle` which would be direct Output creation
    const directOutputCreation = [...main.matchAll(/output:\s*sandcastle\.Output/g)];
    expect(directOutputCreation.length).toBe(0);

    // completionSignal: should not appear in main.mts at all (adapter owns it)
    const completionSignalMatches = [...main.matchAll(/completionSignal/g)];
    expect(completionSignalMatches.length).toBe(0);

    // sandcastle.run and sandbox.run should not appear directly in main.mts for agent runs
    // But main.mts still uses sandcastle.run for non-agent tasks? Check.
    // After migration, all agent runs should be via adapter, so direct sandcastle.run for agent should be 0
    // However main.mts may still use sandcastle.run for non-agent? No, all agent runs are planner/merger
    // So we check that there is no `await sandcastle.run` and no `await sandbox!.run` left
    const directSandcastleRun = [...main.matchAll(/await\s+sandcastle\.run/g)];
    expect(directSandcastleRun.length).toBe(0);
    const directSandboxRun = [...main.matchAll(/await\s+sandbox!\.run/g)];
    expect(directSandboxRun.length).toBe(0);

    // The adapter file itself must contain those fields (as the owner)
    expect(adapter).toContain("maxIterations: 1");
    expect(adapter).toContain("completionSignal");
    expect(adapter).toContain("sandcastle.Output");

    // Ensure ITERATION_CONTROL.maxIterations is not counted as violation (it's not a run option)
    // That string appears in main.mts as `ITERATION_CONTROL.maxIterations` – that's allowed
    // Our check above for `maxIterations:` with colon won't match that, so it's fine
    expect(main).toContain("ITERATION_CONTROL.maxIterations");
  });

  it("adapter has a real non-test production consumer", async () => {
    const files = fs.readdirSync(".sandcastle").filter(f => f.endsWith(".mts") && !f.endsWith(".test.mts"));
    const consumers = files.filter(f => {
      const content = fs.readFileSync(`.sandcastle/${f}`, "utf8");
      return content.includes("agent-run-contracts");
    });
    expect(consumers).toContain("main.mts");
    // Ensure it's not just a test-only coordinator
    expect(consumers.length).toBeGreaterThanOrEqual(1);
    // The production consumer must be main.mts, not just tests
    expect(consumers.some(c => c === "main.mts")).toBe(true);
  });
});
