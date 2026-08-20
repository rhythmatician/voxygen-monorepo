import { describe, it, expect } from "vitest";
import fs from "node:fs";
import {
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  EXPECTED_SANDCASTLE_SOURCE_SHA,
  verifySandcastleRuntimeExports,
  isExpectedSandcastleSourceHead,
} from "./sandcastle-runtime-provenance.mts";

describe("Sandcastle runtime provenance guard", () => {
  it("locks runtime verification to the intended 95f3a5c baseline", () => {
    expect(EXPECTED_SANDCASTLE_SOURCE_SHA).toBe("95f3a5c7c0ff7c5848bb6f13edaa2ed14e2a6ee4");
    expect(EXPECTED_SANDCASTLE_SOURCE_PREFIX).toBe("95f3a5c");
    expect(isExpectedSandcastleSourceHead("95f3a5c7c0ff7c5848bb6f13edaa2ed14e2a6ee4")).toBe(true);
    expect(isExpectedSandcastleSourceHead("95f3a5c99")).toBe(false);
    expect(isExpectedSandcastleSourceHead("a95f3a5c")).toBe(false);
    expect(isExpectedSandcastleSourceHead("")).toBe(false);
  });

  it("verifies required exports without depending on a sibling checkout", () => {
    const runtime = verifySandcastleRuntimeExports({
      createSandbox: () => undefined,
      run: () => undefined,
      Output: {},
      muse: () => undefined,
    });
    expect(runtime.ok).toBe(true);
    expect(runtime.missing).toEqual([]);

    expect(verifySandcastleRuntimeExports({ createSandbox: () => undefined }).missing).toEqual(["run", "Output", "muse"]);
  });

  it("requires exact source identity before Doctor may reuse cached proof", () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    expect(main).toMatch(/if \(!isExpectedSandcastleSourceHead\(sandcastleHead\)\)/);
    expect(main.indexOf("if (!isExpectedSandcastleSourceHead(sandcastleHead))")).toBeLessThan(
      main.indexOf("const cachePath = '.sandcastle/.doctor-cache.json'"),
    );
  });
});
