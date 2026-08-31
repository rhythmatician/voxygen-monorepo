import { describe, it, expect } from "vitest";
import fs from "node:fs";
import {
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  EXPECTED_SANDCASTLE_SOURCE_SHA,
  verifySandcastleRuntimeExports,
  isExpectedSandcastleSourceHead,
} from "./sandcastle-runtime-provenance.mts";

describe("Sandcastle runtime provenance guard", () => {
  it("locks runtime verification to the exact reviewed Sandcastle revision", () => {
    expect(EXPECTED_SANDCASTLE_SOURCE_SHA).toBe("4692bc0681d2e9228bdaaaecd6f16c8058c4bb0f");
    expect(EXPECTED_SANDCASTLE_SOURCE_PREFIX).toBe("4692bc0");
    expect(isExpectedSandcastleSourceHead("4692bc0681d2e9228bdaaaecd6f16c8058c4bb0f")).toBe(true);
    expect(isExpectedSandcastleSourceHead("4692bc099")).toBe(false);
    expect(isExpectedSandcastleSourceHead("b4692bc0")).toBe(false);
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
