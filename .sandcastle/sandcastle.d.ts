// Minimal ambient declarations for Sandcastle runtime (host-side).
// The real implementation is provided by @ai-hero/sandcastle at runtime
// (file:../../sandcastle on host, baked into Docker image). This stub lets
// `npm run typecheck` pass inside containers where the sibling checkout is not
// mounted, without affecting runtime behavior.
declare module "@ai-hero/sandcastle" {
  export function run(opts: any): Promise<any>;
  export function createSandbox(opts: any): Promise<any>;
  export function muse(model: string): any;
  export const Output: {
    object(opts: any): any;
  };
  const _default: any;
  export default _default;
}

declare module "@ai-hero/sandcastle/sandboxes/docker" {
  export function docker(opts?: any): any;
}
