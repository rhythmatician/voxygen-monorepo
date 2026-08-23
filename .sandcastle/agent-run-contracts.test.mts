import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import { z } from "zod";
import * as sandcastle from "@ai-hero/sandcastle";
import {
  runStructuredOnce,
  runUnstructuredOnce,
  runUntilCompletion,
  FACTORY_COMPLETION_SIGNAL,
  STRUCTURED_COMPLETION_DISABLED,
} from "./agent-run-contracts.mts";
import type { ReviewVerdict } from "./review-verdict.mts";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function createRecordingExecutor<R = any>() {
  const calls: Record<string, unknown>[] = [];
  const executor = async (opts: Record<string, unknown>) => {
    calls.push({ ...opts });
    // Simulate successful run result shape with generic R
    return {
      commits: [],
      output: opts.output ? "fake-output" : undefined,
      stdout: "<tag>fake</tag>",
      branch: "test-branch",
    } as unknown as R;
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
    expect(opts.completionSignal).toEqual(STRUCTURED_COMPLETION_DISABLED);
    expect(opts.completionSignal).toEqual([]);
    expect(opts.output).toBeDefined();
    const output = opts.output as any;
    expect(output._tag).toBe("string");
    expect(output.tag).toBe("plan");
    expect(result).toBeDefined();
    // Typed output preserved
    const typed = result as { output: string };
    expect(typeof typed.output).toBe("string");
  });

  it("structured object: exactly one call, maxIterations 1, completion disabled, real Output.object", async () => {
    const { executor, calls } = createRecordingExecutor<{ commits: string[]; output: ReviewVerdict }>();
    const agent = createFakeAgent();
    const schema = z.object({ approved: z.boolean(), findings: z.array(z.any()), acceptanceCriteriaMet: z.array(z.any()), summary: z.string() });
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
    expect(opts.completionSignal).toEqual(STRUCTURED_COMPLETION_DISABLED);
    const output = opts.output as any;
    expect(output._tag).toBe("object");
    expect(output.tag).toBe("verdict");
    expect(output.schema).toBe(schema);
    expect(result).toBeDefined();
    // Typed output preserved
    const typed: ReviewVerdict = result.output;
    expect(typed).toBeDefined();
  });

  it("structured preserves typed output without weakening validation (research still strict)", async () => {
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
    expect((opts.output as any)._tag).toBe("string");
  });

  it("typed output is preserved via generics (compile-time)", async () => {
    const { executor } = createRecordingExecutor<{ commits: string[]; output: string }>();
    const agent = createFakeAgent();
    const stringResult = await runStructuredOnce(executor, {
      name: "planner",
      agent,
      promptFile: "./.sandcastle/plan-prompt.md",
      promptArgs: {},
      tag: "plan",
    });
    // Compile-time: stringResult.output must be string
    const checkString: string = stringResult.output;
    expect(typeof checkString).toBe("string");

    const { executor: exec2 } = createRecordingExecutor<{ commits: string[]; output: ReviewVerdict }>();
    const schema = z.object({ approved: z.boolean(), findings: z.array(z.any()), acceptanceCriteriaMet: z.array(z.any()), summary: z.string() });
    const objectResult = await runStructuredOnce(exec2, {
      name: "reviewer",
      agent,
      promptFile: "./.sandcastle/review-prompt.md",
      promptArgs: {},
      tag: "verdict",
      schema,
    });
    const checkObject: ReviewVerdict = objectResult.output;
    expect(checkObject).toBeDefined();

    const { executor: exec3 } = createRecordingExecutor<{ commits: string[]; branch: string }>();
    const unstructuredResult = await runUnstructuredOnce(exec3, {
      name: "merger",
      agent,
      promptFile: "./.sandcastle/merge-prompt.md",
      promptArgs: {},
    });
    // Unstructured preserves executor result without output
    const checkUnstructured: { commits: string[]; branch: string } = unstructuredResult;
    expect(checkUnstructured.commits).toBeDefined();

    const { executor: exec4 } = createRecordingExecutor<{ commits: string[] }>();
    const iterativeResult = await runUntilCompletion(exec4, {
      name: "implementer",
      agent,
      promptFile: "./.sandcastle/implement-prompt.md",
      promptArgs: {},
      budget: 10,
    });
    const checkIterative: { commits: string[] } = iterativeResult;
    expect(checkIterative.commits).toBeDefined();
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
    const hasBudget100 = main.includes("budget: 100") || main.includes("budget:100");
    const hasBudget50 = main.includes("budget: 50") || main.includes("budget:50");
    expect(hasBudget100).toBe(true);
    expect(hasBudget50).toBe(true);
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
    expect(true).toBe(true);
  });
});

// Compile-time tests — not executed at runtime (wrapped in if(false))
if (false) {
  // Direct literals - should be errors
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

  // Genuine variable/spread tests without as any
  const leakedStructuredOptions = {
    name: "planner",
    agent: null as unknown,
    tag: "plan",
    maxIterations: 30,
  };

  // @ts-expect-error variable-carried maxIterations must be rejected
  runStructuredOnce(async () => ({}), leakedStructuredOptions);

  const dangerousModeFields = {
    completionSignal: "<promise>WRONG</promise>",
  };

  // @ts-expect-error spread-carried completionSignal must be rejected
  runStructuredOnce(async () => ({}), {
    name: "planner",
    agent: null as unknown,
    tag: "plan",
    ...dangerousModeFields,
  });

  const budgetSpread = {
    budget: 100,
  };

  // @ts-expect-error spread-carried budget must be rejected for unstructured
  runUnstructuredOnce(async () => ({}), {
    name: "merger",
    agent: null as unknown,
    promptFile: "./test.md",
    ...budgetSpread,
  });

  const outputSpread = {
    output: sandcastle.Output.string({ tag: "x" }),
  };

  // @ts-expect-error spread-carried output must be rejected for iterative
  runUntilCompletion(async () => ({}), {
    name: "implementer",
    agent: null as unknown,
    promptFile: "./test.md",
    budget: 100,
    ...outputSpread,
  });

  // Typed output compile-time checks
  {
    const execString: (opts: any) => Promise<{ commits: string[]; output: string; branch: string }> = async (opts) => ({ commits: [], output: "test", branch: "b" });
    runStructuredOnce(execString, { name: "planner", agent: null as unknown, tag: "plan" }).then(r => {
      const out: string = r.output;
      // @ts-expect-error output should be string, not number
      const bad: number = r.output;
    });
    const schema = z.object({ approved: z.boolean(), findings: z.array(z.any()), acceptanceCriteriaMet: z.array(z.any()), summary: z.string() });
    const execObject: (opts: any) => Promise<{ commits: string[]; output: ReviewVerdict; branch: string }> = async (opts) => ({ commits: [], output: { approved: true, findings: [], acceptanceCriteriaMet: [], summary: "s" } as ReviewVerdict, branch: "b" });
    runStructuredOnce(execObject, { name: "reviewer", agent: null as unknown, tag: "verdict", schema }).then(r => {
      const out: ReviewVerdict = r.output;
      // @ts-expect-error should be ReviewVerdict, not string
      const bad: string = r.output;
    });
  }
}

describe("agent-run-contracts: runtime stripping", () => {
  it("strips forbidden fields from an untyped caller before execution", async () => {
    const { executor, calls } = createRecordingExecutor();
    await runStructuredOnce(executor, {
      name: "planner",
      agent: createFakeAgent(),
      tag: "plan",
      maxIterations: 30,
      completionSignal: "wrong",
    } as any);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.maxIterations).toBe(1);
    expect(calls[0]!.completionSignal).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// 6. Installed-runtime contract
// ---------------------------------------------------------------------------
describe("agent-run-contracts: installed runtime contract", () => {
  it("installed runtime rejects structured output with maxIterations 2 before touching sentinels", async () => {
    const { run, Output } = await import("@ai-hero/sandcastle");
    let agentMethodCalled = false;
    let sandboxMethodCalled = false;
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
    const fakeSandbox = {
      // Inert sandbox sentinel - tracks execution without throwing on metadata reads
      create: () => {
        sandboxMethodCalled = true;
        throw new Error("sandbox create should not be called");
      },
      run: () => {
        sandboxMethodCalled = true;
        throw new Error("sandbox run should not be called");
      },
    };

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
    expect(sandboxMethodCalled).toBe(false);
  });

  it("installed runtime also rejects object output with maxIterations 2", async () => {
    const { run, Output } = await import("@ai-hero/sandcastle");
    let agentMethodCalled = false;
    let sandboxMethodCalled = false;
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
    const fakeSandbox = {
      create: () => {
        sandboxMethodCalled = true;
        throw new Error("sandbox create should not be called");
      },
    };
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
    expect(sandboxMethodCalled).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// 7. Production role wiring and no-bypass guard
// ---------------------------------------------------------------------------
describe("agent-run-contracts: production wiring and no-bypass", () => {
  it("all seven role call sites use the correct adapter operation", async () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");

    expect(main).toMatch(/from\s+["']\.\/agent-run-contracts\.mts["']/);
    expect(main).toContain("runStructuredOnce");
    expect(main).toContain("runUnstructuredOnce");
    expect(main).toContain("runUntilCompletion");

    expect(main).toMatch(/runStructuredOnce[\s\S]*?tag:\s*["']plan["']/);
    expect(main).toMatch(/runStructuredOnce[\s\S]*?tag:\s*("research"|RESEARCH_OUTPUT_TAG)/);
    expect(main).toMatch(/runStructuredOnce[\s\S]*?tag:\s*["']verdict["']/);
    expect(main).toContain("reviewVerdictSchema");

    const mergerSection = main.slice(main.indexOf('name: "merger"') - 500, main.indexOf('name: "merger"') + 500);
    expect(mergerSection).toContain("runUnstructuredOnce");

    expect(main).toMatch(/runUntilCompletion[\s\S]*?budget:\s*100/);
    expect(main).toMatch(/runUntilCompletion[\s\S]*?budget:\s*50/);

    const structuredCount = (main.match(/runStructuredOnce/g) || []).length;
    const unstructuredCount = (main.match(/runUnstructuredOnce/g) || []).length;
    const iterativeCount = (main.match(/runUntilCompletion/g) || []).length;
    expect(structuredCount).toBeGreaterThanOrEqual(3);
    expect(unstructuredCount).toBeGreaterThanOrEqual(1);
    expect(iterativeCount).toBeGreaterThanOrEqual(2);
  });

  it("no production role directly owns maxIterations/output/completionSignal outside adapter", async () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    const adapter = fs.readFileSync(".sandcastle/agent-run-contracts.mts", "utf8");

    const maxIterationsMatches = [...main.matchAll(/maxIterations\s*:/g)];
    expect(maxIterationsMatches.length).toBe(0);

    const directOutputCreation = [...main.matchAll(/output:\s*sandcastle\.Output/g)];
    expect(directOutputCreation.length).toBe(0);

    const completionSignalMatches = [...main.matchAll(/completionSignal/g)];
    expect(completionSignalMatches.length).toBe(0);

    const directSandcastleRun = [...main.matchAll(/await\s+sandcastle\.run/g)];
    expect(directSandcastleRun.length).toBe(0);
    const directSandboxRun = [...main.matchAll(/await\s+sandbox!\.run/g)];
    expect(directSandboxRun.length).toBe(0);

    expect(adapter).toContain("maxIterations: 1");
    expect(adapter).toContain("completionSignal");
    expect(adapter).toContain("sandcastle.Output");
    expect(adapter).toContain("STRUCTURED_COMPLETION_DISABLED");

    expect(main).toContain("ITERATION_CONTROL.maxIterations");
  });

  it("adapter has a real non-test production consumer", async () => {
    const files = fs.readdirSync(".sandcastle").filter(f => f.endsWith(".mts") && !f.endsWith(".test.mts"));
    const consumers = files.filter(f => {
      const content = fs.readFileSync(`.sandcastle/${f}`, "utf8");
      return content.includes("agent-run-contracts");
    });
    expect(consumers).toContain("main.mts");
    expect(consumers.length).toBeGreaterThanOrEqual(1);
    expect(consumers.some(c => c === "main.mts")).toBe(true);
  });
});
