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
    expect(hasProvenance("OracleFixtureWriter.read", "mod/src/test/java/Foo.java")).toBe(true);
    expect(hasProvenance("REAL_CAPTURE", "mod/src/test/java/Foo.java")).toBe(true);
    expect(hasProvenance("VanillaVoxyOracle", "mod/src/test/java/Foo.java")).toBe(true);
    expect(hasProvenance("import OracleFixture", "mod/src/test/java/Foo.java")).toBe(false);
    expect(hasProvenance("SYNTHETIC_TEST", "mod/src/test/java/Foo.java")).toBe(false);
    expect(hasProvenance("OracleFixture SYNTHETIC_TEST", "mod/src/test/java/Foo.java")).toBe(false);
    expect(hasProvenance("REAL_CAPTURE and SYNTHETIC_TEST", "mod/src/test/java/Foo.java")).toBe(true);
    expect(hasProvenance("no marker", "mod/src/voxyIntegrationTest/java/FooParityTest.java")).toBe(false);
    expect(hasProvenance("REAL_CAPTURE", "mod/src/voxyIntegrationTest/java/FooParityTest.java")).toBe(true);
    expect(hasProvenance("no marker", "mod/src/test/java/Foo.java")).toBe(false);
  });

  it("detects a deliberately bogus parity test and passes a good one", () => {
    const bogus = {
      path: "mod/src/test/java/com/rhythmatician/lodiffusion/BogusParityTest.java",
      content: `
        class BogusParityTest {
          // Claims parity but uses synthetic expected — no real oracle
          void fakeParity() { int expected = 42; }
        }
      `,
    };
    const syntheticBogus = {
      path: "mod/src/test/java/com/rhythmatician/lodiffusion/SyntheticParityTest.java",
      content: `
        import com.rhythmatician.lodiffusion.oracle.OracleFixture;
        import com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory;
        class SyntheticParityTest {
          void fakeParity() {
            var f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(null);
            var c = f.evidenceKind(); // SYNTHETIC_TEST
          }
        }
      `,
    };
    const good = {
      path: "mod/src/test/java/com/rhythmatician/lodiffusion/GoodParityTest.java",
      content: `
        import com.rhythmatician.lodiffusion.oracle.OracleFixture;
        class GoodParityTest {
          void realParity() {
            var f = OracleFixtureWriter.read(null);
            var k = OracleFixture.EvidenceKind.REAL_CAPTURE;
          }
        }
      `,
    };
    const integrationBare = {
      path: "mod/src/voxyIntegrationTest/java/com/rhythmatician/lodiffusion/BareIntegrationParityTest.java",
      content: `class BareIntegrationParityTest {}`,
    };
    const integration = {
      path: "mod/src/voxyIntegrationTest/java/com/rhythmatician/lodiffusion/IntegrationParityTest.java",
      content: `class IntegrationParityTest { REAL_CAPTURE }`,
    };
    expect(findParityWithoutProvenance([bogus])).toHaveLength(1);
    expect(findParityWithoutProvenance([syntheticBogus])).toHaveLength(1);
    expect(findParityWithoutProvenance([good])).toHaveLength(0);
    expect(findParityWithoutProvenance([integrationBare])).toHaveLength(1);
    expect(findParityWithoutProvenance([integration])).toHaveLength(0);
    expect(findParityWithoutProvenance([bogus, syntheticBogus, good, integrationBare, integration]).map((v) => v.filename)).toEqual(["BogusParityTest.java", "SyntheticParityTest.java", "BareIntegrationParityTest.java"]);
  });

  it("exempts the guard itself", () => {
    const guard = {
      path: "mod/src/test/java/com/rhythmatician/lodiffusion/oracle/ParityProvenanceGuardTest.java",
      content: `class ParityProvenanceGuardTest {}`,
    };
    expect(findParityWithoutProvenance([guard])).toHaveLength(0);
  });

  it("repo currently has no parity-without-provenance violations (real CI gate)", { timeout: 10000 }, () => {
    // Scan the actual repo test sources — this is the real CI gate for npm test.
    // If a violating file is committed, this test fails in the factory lane.
    // Only test sources are scanned — production ParityConfig etc. in src/main are not test claims.
    let files: string[] = [];
    try {
      const out = execSync("git ls-files --cached --others --exclude-standard -- mod/src training", { encoding: "utf8" });
      files = out.split("\n").filter(Boolean);
    } catch {
      // No git — skip
      return;
    }
    const entries: Array<{ path: string; content: string }> = [];
    for (const p of files) {
      if (!p.endsWith(".java") && !p.endsWith(".py")) continue;
      // Only scan test sources: mod/src/test and voxyIntegrationTest; for training only test files
      if (p.startsWith("mod/")) {
        if (!p.includes("src/test") && !p.includes("voxyIntegrationTest")) continue;
      } else if (p.startsWith("training/")) {
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
