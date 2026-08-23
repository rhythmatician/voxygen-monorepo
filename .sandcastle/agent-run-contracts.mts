import * as sandcastle from "@ai-hero/sandcastle";
import type { StandardSchemaV1 } from "@standard-schema/spec";

export const FACTORY_COMPLETION_SIGNAL = "<promise>COMPLETE</promise>" as const;
export const STRUCTURED_COMPLETION_DISABLED = [] as const;

type AgentProvider = unknown;

type CommonRunOptions = {
  readonly name: string;
  readonly agent: AgentProvider;
  readonly prompt?: string;
  readonly promptFile?: string;
  readonly promptArgs?: Record<string, string | number | boolean>;
  readonly idleTimeoutSeconds?: number;
  readonly completionTimeoutSeconds?: number;
  readonly logging?: unknown;
  readonly hooks?: unknown;
  readonly sandbox?: unknown;
  readonly branchStrategy?: unknown;
  readonly cwd?: string;
  readonly signal?: AbortSignal;
  readonly timeouts?: unknown;
  readonly copyToWorktree?: string[];
};

type Executor = (opts: Record<string, unknown>) => Promise<any>;

function pickAllowed(src: Record<string, unknown>): Record<string, unknown> {
  const allowedKeys = [
    "name",
    "agent",
    "prompt",
    "promptFile",
    "promptArgs",
    "idleTimeoutSeconds",
    "completionTimeoutSeconds",
    "logging",
    "hooks",
    "sandbox",
    "branchStrategy",
    "cwd",
    "signal",
    "timeouts",
    "copyToWorktree",
  ] as const;
  const out: Record<string, unknown> = {};
  for (const k of allowedKeys) {
    if (k in src) out[k] = src[k];
  }
  return out;
}

export type StructuredStringOptions = CommonRunOptions & {
  readonly tag: string;
};

export type StructuredObjectOptions<T> = CommonRunOptions & {
  readonly tag: string;
  readonly schema: StandardSchemaV1<unknown, T>;
};

export async function runStructuredOnce(
  executor: Executor,
  opts: StructuredStringOptions,
): Promise<any>;
export async function runStructuredOnce<T>(
  executor: Executor,
  opts: StructuredObjectOptions<T>,
): Promise<any>;
export async function runStructuredOnce<T>(
  executor: Executor,
  opts: StructuredStringOptions | StructuredObjectOptions<T>,
): Promise<any> {
  const { tag, schema, ...rest } = opts as StructuredStringOptions &
    StructuredObjectOptions<T> &
    Record<string, unknown>;
  if (typeof tag !== "string" || tag.length === 0) {
    throw new Error("runStructuredOnce requires a non-empty tag");
  }
  const hasSchema = "schema" in opts && (opts as any).schema != null;
  const output = hasSchema
    ? sandcastle.Output.object({ tag, schema: (opts as StructuredObjectOptions<T>).schema })
    : sandcastle.Output.string({ tag });
  const allowed = pickAllowed(rest as Record<string, unknown>);
  const runOpts: Record<string, unknown> = {
    ...allowed,
    maxIterations: 1,
    output,
    completionSignal: [] as unknown as string[],
  };
  return executor(runOpts);
}

export type UnstructuredOptions = CommonRunOptions;

export async function runUnstructuredOnce(
  executor: Executor,
  opts: UnstructuredOptions,
): Promise<any> {
  const allowed = pickAllowed(opts as unknown as Record<string, unknown>);
  const runOpts: Record<string, unknown> = {
    ...allowed,
    maxIterations: 1,
    completionSignal: FACTORY_COMPLETION_SIGNAL,
  };
  return executor(runOpts);
}

export type IterativeOptions = CommonRunOptions & {
  readonly budget: number;
};

export async function runUntilCompletion(
  executor: Executor,
  opts: IterativeOptions,
): Promise<any> {
  const { budget, ...rest } = opts as IterativeOptions & Record<string, unknown>;
  if (!Number.isFinite(budget) || !Number.isInteger(budget) || budget <= 1) {
    throw new Error(`Invalid iterative budget: ${String(budget)}. Must be finite integer > 1`);
  }
  const allowed = pickAllowed(rest as Record<string, unknown>);
  const runOpts: Record<string, unknown> = {
    ...allowed,
    maxIterations: budget,
    completionSignal: FACTORY_COMPLETION_SIGNAL,
  };
  return executor(runOpts);
}
