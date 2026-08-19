import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

export const EXPECTED_SANDCASTLE_SOURCE_SHA = "95f3a5c7c0ff7c5848bb6f13edaa2ed14e2a6ee4";
export const EXPECTED_SANDCASTLE_SOURCE_PREFIX = EXPECTED_SANDCASTLE_SOURCE_SHA.slice(0, 7);
export const EXPECTED_SANDCASTLE_RUNTIME_INDEX_SHA256 = "a7af6f105174005f166e24c7eddc270901885d5b0ff8d4b526cdc92f7fc73d70";

const RUNTIME_PACKAGE_ROOT = path.join(process.cwd(), "node_modules", "@ai-hero", "sandcastle");
const RUNTIME_REQUIREMENTS = ["createSandbox", "run", "Output", "muse"];

export function isExpectedSandcastleSourceHead(head: string): boolean {
  if (!head) return false;
  return head.startsWith(EXPECTED_SANDCASTLE_SOURCE_PREFIX);
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

export function hasExpectedSandcastleRuntimeSymbols(distPath: string): boolean {
  if (!distPath || !fs.existsSync(distPath)) return false;
  const distContent = fs.readFileSync(distPath, "utf8");
  return RUNTIME_REQUIREMENTS.every((symbol) => distContent.includes(symbol));
}

export function missingSandcastleRuntimeSymbols(distPath: string): string[] {
  if (!distPath || !fs.existsSync(distPath)) return [...RUNTIME_REQUIREMENTS];
  const distContent = fs.readFileSync(distPath, "utf8");
  return RUNTIME_REQUIREMENTS.filter((symbol) => !distContent.includes(symbol));
}

export function verifySandcastleRuntimeDist(distPath: string): { ok: boolean; missing: string[] } {
  const missing = missingSandcastleRuntimeSymbols(distPath);
  return {
    ok: missing.length === 0,
    missing,
  };
}

export function resolveSha256OfFile(filePath: string): string {
  if (!filePath || !fs.existsSync(filePath)) return "";
  const hash = createHash("sha256");
  hash.update(fs.readFileSync(filePath));
  return hash.digest("hex");
}

export function hasExpectedSandcastleRuntimeDistHash(distPath: string): boolean {
  if (!distPath) return false;
  return resolveSha256OfFile(distPath) === EXPECTED_SANDCASTLE_RUNTIME_INDEX_SHA256;
}
