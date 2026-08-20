import fs from "node:fs";
import path from "node:path";

export const EXPECTED_SANDCASTLE_SOURCE_SHA = "95f3a5c7c0ff7c5848bb6f13edaa2ed14e2a6ee4";
export const EXPECTED_SANDCASTLE_SOURCE_PREFIX = EXPECTED_SANDCASTLE_SOURCE_SHA.slice(0, 7);

const RUNTIME_PACKAGE_ROOT = path.join(process.cwd(), "node_modules", "@ai-hero", "sandcastle");
const CALLABLE_RUNTIME_EXPORTS = ["createSandbox", "run", "muse"] as const;

export function isExpectedSandcastleSourceHead(head: string): boolean {
  return head === EXPECTED_SANDCASTLE_SOURCE_SHA;
}

export function resolveSandcastleRuntimePackagePath(): string {
  return RUNTIME_PACKAGE_ROOT;
}

export function resolveSandcastleRuntimeDistPath(packageRoot: string = RUNTIME_PACKAGE_ROOT): string {
  const packageJsonPath = path.join(packageRoot, "package.json");
  if (!fs.existsSync(packageJsonPath)) return "";
  try {
    const pkg = JSON.parse(fs.readFileSync(packageJsonPath, "utf8"));
    const main = typeof pkg.main === "string" && pkg.main.trim().length > 0 ? pkg.main : "./dist/index.js";
    const distPath = path.join(packageRoot, main);
    return fs.existsSync(distPath) ? distPath : "";
  } catch {
    return "";
  }
}

export function verifySandcastleRuntimeExports(runtime: unknown): { ok: boolean; missing: string[] } {
  const runtimeRecord = runtime && typeof runtime === "object" ? runtime as Record<string, unknown> : {};
  const missing = CALLABLE_RUNTIME_EXPORTS.filter((symbol) => typeof runtimeRecord[symbol] !== "function") as string[];
  const output = runtimeRecord.Output && typeof runtimeRecord.Output === "object"
    ? runtimeRecord.Output as Record<string, unknown>
    : {};
  if (typeof output.object !== "function") missing.push("Output.object");
  if (typeof output.string !== "function") missing.push("Output.string");
  return {
    ok: missing.length === 0,
    missing,
  };
}
