import { describe, it, expect } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";
import { findParityWithoutProvenance, isParityClaimingFilename, hasProvenance } from "./parity-provenance.mts";

describe("parity provenance guard — ADR 0015", () => {
  it("flags parity-named files without provenance", () => {
    expect(isParityClaimingFilename("MyParityTest.java")).toBe(true);
    expect(isParityClaimingFilename("RoundTripTest.java")).toBe(true);
    expect(isParityClaimingFilename("VanillaConvergenceTest.java")).toBe(true);
    expect(isParityClaimingFilename("PostIngestParityTest.java")).toBe(true);
    expect(isParityClaimingFilename("RegularTest.java")).toBe(false);
    expect(isParityClaimingFilename("EndChorusSyntheticMipperConsistencyTest.java")).toBe(false);
  });

  it("recognizes provenance markers and voxyIntegrationTest boundary", () => {
    expect(hasProvenance("import OracleFixture", "java/src/test/java/Foo.java")).toBe(true);
    expect(hasProvenance("VanillaVoxyOracle", "java/src/test/java/Foo.java")).toBe(true);
    expect(hasProvenance("no marker", "java/src/voxyIntegrationTest/java/FooParityTest.java")).toBe(true);
    expect(hasProvenance("no marker", "java/src/test/java/Foo.java")).toBe(false);
  });

  it("detects a deliberately bogus parity test and passes a good one", () => {
    const bogus = {
      path: "java/src/test/java/com/rhythmatician/lodiffusion/BogusParityTest.java",
      content: `
        class BogusParityTest {
          // Claims parity but uses synthetic expected — no real oracle
          void fakeParity() { int expected = 42; }
        }
      `,
    };
    const good = {
      path: "java/src/test/java/com/rhythmatician/lodiffusion/GoodParityTest.java",
      content: `
        import com.rhythmatician.lodiffusion.oracle.OracleFixture;
        class GoodParityTest { OracleFixture f; }
      `,
    };
    const integration = {
      path: "java/src/voxyIntegrationTest/java/com/rhythmatician/lodiffusion/IntegrationParityTest.java",
      content: `class IntegrationParityTest {}`,
    };
    expect(findParityWithoutProvenance([bogus])).toHaveLength(1);
    expect(findParityWithoutProvenance([good])).toHaveLength(0);
    expect(findParityWithoutProvenance([integration])).toHaveLength(0);
    expect(findParityWithoutProvenance([bogus, good, integration]).map((v) => v.filename)).toEqual(["BogusParityTest.java"]);
  });

  it("exempts the guard itself", () => {
    const guard = {
      path: "java/src/test/java/com/rhythmatician/lodiffusion/oracle/ParityProvenanceGuardTest.java",
      content: `class ParityProvenanceGuardTest {}`,
    };
    expect(findParityWithoutProvenance([guard])).toHaveLength(0);
  });

  it("repo currently has no parity-without-provenance violations (real CI gate)", () => {
    // Scan the actual repo test sources — this is the real CI gate for npm test.
    // If a violating file is committed, this test fails in the factory lane.
    // Only test sources are scanned — production ParityConfig etc. in src/main are not test claims.
    let files: string[] = [];
    try {
      const out = execSync("git ls-files --cached --others --exclude-standard -- java/src python", { encoding: "utf8" });
      files = out.split("\n").filter(Boolean);
    } catch {
      // No git — skip
      return;
    }
    const entries: Array<{ path: string; content: string }> = [];
    for (const p of files) {
      if (!p.endsWith(".java") && !p.endsWith(".py")) continue;
      // Only scan test sources: java/src/test and voxyIntegrationTest; for python only test files
      if (p.startsWith("java/")) {
        if (!p.includes("src/test") && !p.includes("voxyIntegrationTest")) continue;
      } else if (p.startsWith("python/")) {
        if (!p.includes("/tests/") && !p.includes("test_") && !p.includes("_test.py")) continue;
      } else {
        continue;
      }
      // Exclude build artifacts that slipped through
      if (p.includes("/build/") || p.includes("/.gradle/") || p.includes("/.venv/") || p.includes("/node_modules/")) continue;
      try {
        if (!existsSync(p)) continue;
        const content = readFileSync(p, "utf8");
        entries.push({ path: p, content });
      } catch {
        // ignore unreadable
      }
    }
    const violations = findParityWithoutProvenance(entries);
    expect(violations, `Parity/roundTrip/vanillaConvergence tests without independent-oracle provenance found:\n${violations.map((v) => v.message).join("\n")}`).toEqual([]);
  });
});
