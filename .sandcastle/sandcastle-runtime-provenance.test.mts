import { describe, it, expect } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  EXPECTED_SANDCASTLE_SOURCE_SHA,
  EXPECTED_SANDCASTLE_RUNTIME_INDEX_SHA256,
  resolveSha256OfFile,
  hasExpectedSandcastleRuntimeDistHash,
  resolveSandcastleRuntimeDistPath,
  verifySandcastleRuntimeDist,
  missingSandcastleRuntimeSymbols,
  isExpectedSandcastleSourceHead,
} from "./sandcastle-runtime-provenance.mts";

describe("Sandcastle runtime provenance guard", () => {
  it("locks runtime verification to the intended 95f3a5c baseline", () => {
    expect(EXPECTED_SANDCASTLE_SOURCE_SHA).toBe("95f3a5c7c0ff7c5848bb6f13edaa2ed14e2a6ee4");
    expect(EXPECTED_SANDCASTLE_SOURCE_PREFIX).toBe("95f3a5c");
    expect(isExpectedSandcastleSourceHead("95f3a5c7c0ff7c5848bb6f13edaa2ed14e2a6ee4")).toBe(true);
    expect(isExpectedSandcastleSourceHead("95f3a5c99")).toBe(true);
    expect(isExpectedSandcastleSourceHead("a95f3a5c")).toBe(false);
    expect(isExpectedSandcastleSourceHead("")).toBe(false);
  });

  it("verifies runtime artifact has required stock Sandcastle symbols (not fork-specific liveness strings)", () => {
    const distPath = resolveSandcastleRuntimeDistPath();
    expect(distPath).toBeTruthy();
    const missing = missingSandcastleRuntimeSymbols(distPath);
    expect(missing).toEqual([]);
    const runtime = verifySandcastleRuntimeDist(distPath);
    expect(runtime.ok).toBe(true);
    expect(runtime.missing).toEqual([]);
  });

  it("verifies deterministic runtime dist SHA-256 and rejects tampered runtime", () => {
    const distPath = resolveSandcastleRuntimeDistPath();
    expect(distPath).toBeTruthy();
    expect(resolveSha256OfFile(distPath)).toBe(EXPECTED_SANDCASTLE_RUNTIME_INDEX_SHA256);
    expect(hasExpectedSandcastleRuntimeDistHash(distPath)).toBe(true);

    const scratchDir = fs.mkdtempSync(path.join(os.tmpdir(), "sandcastle-runtime-tamper-"));
    const tamperedPath = path.join(scratchDir, "index.js");
    fs.copyFileSync(distPath, tamperedPath);
    fs.appendFileSync(tamperedPath, "\n// test tamper\n");
    expect(hasExpectedSandcastleRuntimeDistHash(tamperedPath)).toBe(false);
  });
});
