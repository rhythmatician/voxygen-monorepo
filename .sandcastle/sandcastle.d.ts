// Minimal ambient declarations for Sandcastle runtime (host-side).
// The real implementation is provided by @ai-hero/sandcastle at runtime
// (file:../../sandcastle on host, baked into Docker image). This stub lets
// `npm run typecheck` pass inside containers where the sibling checkout is not
// mounted, without affecting runtime behavior.

// any: external Sandcastle boundary -- the sibling checkout is not mounted
// inside the Docker sandbox, so this stub is intentionally permissive.
// Call sites narrow immediately via Zod schemas (e.g. planSchema) and
// typed helpers in main.mts; `any` does not propagate beyond the boundary.

declare module "@ai-hero/sandcastle" {
  export function run(opts: Record<string, unknown>): Promise<{
    output: any;
    commits: string[];
    [key: string]: unknown;
  }>;
  export function createSandbox(opts: Record<string, unknown>): Promise<{
    run(opts: Record<string, unknown>): Promise<{ commits: string[]; stdout: string; [key: string]: unknown }>;
    close(): Promise<void>;
  }>;
  export function muse(model: string): unknown;
  export const Output: {
    object(opts: Record<string, unknown>): unknown;
  };
  const _default: unknown;
  export default _default;
}

declare module "@ai-hero/sandcastle/sandboxes/docker" {
  export function docker(opts?: Record<string, unknown>): unknown;
}
