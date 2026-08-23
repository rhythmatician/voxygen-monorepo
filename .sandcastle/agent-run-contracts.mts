import * as sandcastle from "@ai-hero/sandcastle";
import type { StandardSchemaV1 } from "@standard-schema/spec";

export const FACTORY_COMPLETION_SIGNAL = "<promise>COMPLETE</promise>" as const;
export const STRUCTURED_COMPLETION_DISABLED = [] as const;

type AgentProvider = unknown;

type ForbiddenModeFields = {
  readonly maxIterations?: never;
  readonly output?: never;
  readonly completionSignal?: never;
  readonly resumeSession?: never;
  readonly forkSession?: never;
  readonly budget?: never;
};

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
} & ForbiddenModeFields;

type Executor<R> = (options: Record<string, unknown>) => Promise<R>;

type WithOutput<R, T> = Omit<R, "output"> & { readonly output: T };

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
  readonly schema?: never;
};

export type StructuredObjectOptions<T> = CommonRunOptions & {
  readonly tag: string;
  readonly schema: StandardSchemaV1<unknown, T>;
};

export function runStructuredOnce<R>(
  executor: Executor<R>,
  opts: StructuredStringOptions,
): Promise<WithOutput<R, string>>;
export function runStructuredOnce<R, T>(
  executor: Executor<R>,
  opts: StructuredObjectOptions<T>,
): Promise<WithOutput<R, T>>;
export async function runStructuredOnce<R, T>(
  executor: Executor<R>,
  opts: StructuredStringOptions | StructuredObjectOptions<T>,
): Promise<WithOutput<R, T | string>> {
  const tag = (opts as unknown as StructuredStringOptions).tag;
  const schema = (opts as unknown as StructuredObjectOptions<T>).schema;
  if (typeof tag !== "string" || tag.length === 0) {
    throw new Error("runStructuredOnce requires a non-empty tag");
  }
  const hasSchema = schema != null;
  const output = hasSchema
    ? sandcastle.Output.object({ tag, schema: schema as StandardSchemaV1<unknown, T> })
    : sandcastle.Output.string({ tag });
  const allowed = pickAllowed(opts as unknown as Record<string, unknown>);
  const runOpts: Record<string, unknown> = {
    ...allowed,
    maxIterations: 1,
    output,
    completionSignal: STRUCTURED_COMPLETION_DISABLED as unknown as string[],
  };
  const result = await executor(runOpts as Record<string, unknown>);
  return result as WithOutput<R, T | string>;
}

export type UnstructuredOptions = CommonRunOptions;

export async function runUnstructuredOnce<R>(
  executor: Executor<R>,
  opts: UnstructuredOptions,
): Promise<R> {
  const allowed = pickAllowed(opts as unknown as Record<string, unknown>);
  const runOpts: Record<string, unknown> = {
    ...allowed,
    maxIterations: 1,
    completionSignal: FACTORY_COMPLETION_SIGNAL,
  };
  const result = await executor(runOpts as Record<string, unknown>);
  return result as R;
}

export type IterativeOptions = Omit<CommonRunOptions, "budget"> & {
  readonly budget: number;
};

export async function runUntilCompletion<R>(
  executor: Executor<R>,
  opts: IterativeOptions,
): Promise<R> {
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
  const result = await executor(runOpts as Record<string, unknown>);
  return result as R;
}
