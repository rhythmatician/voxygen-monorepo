import { describe, it, expect } from "vitest";
import {
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  EXPECTED_SANDCASTLE_SOURCE_SHA,
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
});
