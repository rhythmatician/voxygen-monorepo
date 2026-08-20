import { describe, it, expect } from "vitest";
import fs from "node:fs";
import {
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  EXPECTED_SANDCASTLE_SOURCE_SHA,
  verifySandcastleRuntimeExports,
  isExpectedSandcastleSourceHead,
} from "./sandcastle-runtime-provenance.mts";

describe("Sandcastle runtime provenance guard", () => {
  it("locks runtime verification to the exact Muse prompt-transport fix", () => {
    expect(EXPECTED_SANDCASTLE_SOURCE_SHA).toBe("a521f19f7c1b64d2548c8f3bc1cffcd69b29662e");
    expect(EXPECTED_SANDCASTLE_SOURCE_PREFIX).toBe("a521f19");
    expect(isExpectedSandcastleSourceHead("a521f19f7c1b64d2548c8f3bc1cffcd69b29662e")).toBe(true);
    expect(isExpectedSandcastleSourceHead("a521f1999")).toBe(false);
    expect(isExpectedSandcastleSourceHead("ba521f19")).toBe(false);
    expect(isExpectedSandcastleSourceHead("")).toBe(false);
  });

  it("verifies required exports without depending on a sibling checkout", () => {
    const runtime = verifySandcastleRuntimeExports({
      createSandbox: () => undefined,
      run: () => undefined,
      Output: { object: () => undefined, string: () => undefined },
      muse: () => undefined,
    });
    expect(runtime.ok).toBe(true);
    expect(runtime.missing).toEqual([]);

    expect(verifySandcastleRuntimeExports({ createSandbox: () => undefined }).missing).toEqual([
      "run",
      "muse",
      "Output.object",
      "Output.string",
    ]);
    expect(verifySandcastleRuntimeExports({
      createSandbox: null,
      run: "not-callable",
      muse: {},
      Output: { object: null, string: "not-callable" },
    }).missing).toEqual(["createSandbox", "run", "muse", "Output.object", "Output.string"]);
  });

  it("requires exact source identity before Doctor may reuse cached proof", () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    expect(main).toMatch(/if \(!isExpectedSandcastleSourceHead\(sandcastleHead\)\)/);
    expect(main.indexOf("if (!isExpectedSandcastleSourceHead(sandcastleHead))")).toBeLessThan(
      main.indexOf("const cachePath = '.sandcastle/.doctor-cache.json'"),
    );
  });
});
