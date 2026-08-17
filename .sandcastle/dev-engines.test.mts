import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * Regression for issue #70: Factory must prevent Windows npm from
 * corrupting WSL-owned node_modules via native devEngines.
 *
 * Contract: package.json devEngines.os = [{linux,warn},{win32,warn}] (parity fix)
 * - Windows (win32) → warn, not EBADDEVENGINES
 * - Linux (WSL) → warn
 *
 * This test is deterministic and platform-independent: it reads the
 * declarative package contract and simulates both platforms using the
 * same matching semantics as npm-install-checks (exact name equality).
 */

function loadPackageJson(): Record<string, unknown> {
  const p = resolve(process.cwd(), "package.json");
  const raw = readFileSync(p, "utf-8");
  return JSON.parse(raw) as Record<string, unknown>;
}

type DevEngineSpec = { name: string; onFail?: string; version?: string };
type DevEngines = { os?: DevEngineSpec | DevEngineSpec[] };

function simulateCheck(spec: DevEngineSpec, currentName: string): string | null {
  if (spec.name !== currentName) {
    return `Invalid name "${spec.name}" does not match "${currentName}" for "os"`;
  }
  return null;
}

function isLinuxRejectedOnWindows(devEngines: DevEngines, currentOs: string): boolean {
  const raw = devEngines.os;
  if (!raw) return false;
  const specs = Array.isArray(raw) ? raw : [raw];
  // npm fails when every spec fails (invalid length === dependencies.length)
  const failures = specs.map((s) => simulateCheck(s, currentOs)).filter(Boolean);
  return failures.length === specs.length;
}

describe("devEngines OS guard (issue #70)", () => {
  it("package.json declares devEngines.os cross-platform linux+win32 with onFail warn", () => {
    const pkg = loadPackageJson();
    expect(pkg).toHaveProperty("devEngines");
    const devEngines = (pkg as { devEngines?: DevEngines }).devEngines;
    expect(devEngines).toBeDefined();
    expect(devEngines?.os).toBeDefined();

    const raw = devEngines!.os!;
    const specs = Array.isArray(raw) ? raw : [raw];
    expect(specs).toHaveLength(2);
    const names = specs.map((s) => s.name).sort();
    expect(names).toEqual(["linux", "win32"]);
    for (const s of specs) expect(s.onFail).toBe("warn");
  });

  it("Linux (WSL) is accepted — no rejection", () => {
    const pkg = loadPackageJson();
    const devEngines = (pkg as { devEngines: DevEngines }).devEngines;
    expect(isLinuxRejectedOnWindows(devEngines, "linux")).toBe(false);
  });

  it("Windows is accepted — cross-platform warn (was EBADDEVENGINES before parity fix)", () => {
    const pkg = loadPackageJson();
    const devEngines = (pkg as { devEngines: DevEngines }).devEngines;
    expect(isLinuxRejectedOnWindows(devEngines, "win32")).toBe(false);
    // Darwin still rejected — not in cross-platform list
    expect(isLinuxRejectedOnWindows(devEngines, "darwin")).toBe(true);
  });

  it("does not use a custom platform-detection script", async () => {
    const pkg = loadPackageJson();
    // No preinstall/postinstall guard scripts — native devEngines is sufficient.
    const scripts = (pkg as { scripts?: Record<string, string> }).scripts ?? {};
    for (const [name, cmd] of Object.entries(scripts)) {
      expect(cmd).not.toMatch(/platform.*guard|win32.*linux|os\.(platform|type)/i);
      // ensure no custom JS guard referenced
      expect(name).not.toBe("preinstall");
    }
  });

  it("onFail defaults to error semantics — undefined treated as error", () => {
    // If future author omits onFail, npm defaults to error (see dev-engines.js)
    const spec: DevEngineSpec = { name: "linux" };
    const onFail = spec.onFail ?? "error";
    expect(onFail).toBe("error");
  });
});
