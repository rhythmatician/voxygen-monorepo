import { describe, expect, it } from "vitest";
import { classifyChanges, decideMerge, mayAutonomouslyMerge, requiredChecks } from "./ci-policy.mts";

describe("factory CI policy", () => {
  it("classifies cross-language contracts cumulatively", () => {
    const classes = classifyChanges(["python/voxel_tree/contracts/spec.py"]);
    expect(classes).toEqual(expect.arrayContaining(["C1_PYTHON", "C2", "C7"]));
    expect(requiredChecks(classes)).toEqual(
      expect.arrayContaining(["R-01", "P-01", "P-02", "P-03", "P-04", "J-01", "J-02", "J-03", "J-04", "X-01", "X-02", "X-03", "X-04"]),
    );
  });

  it("never treats an empty evidence set as success", () => {
    const decision = decideMerge({ candidateSha: "abc", baseSha: "base", files: ["README.md"], evidence: [] });
    expect(decision.status).toBe("FAIL");
    expect(decision.missing).toContain("R-01");
  });

  it("rejects evidence from a stale candidate SHA", () => {
    const decision = decideMerge({
      candidateSha: "new", baseSha: "base", files: ["README.md"],
      evidence: [{ checkId: "R-01", candidateSha: "old", status: "PASS" }],
    });
    expect(decision.status).toBe("FAIL");
    expect(decision.invalid[0]).toContain("stale candidate SHA");
  });

  it("requires human approval for control-plane changes", () => {
    const files = [".github/workflows/factory-ci.yml"];
    const evidence = requiredChecks(classifyChanges(files)).map((checkId) => ({ checkId, candidateSha: "abc", status: "PASS" as const }));
    expect(decideMerge({ candidateSha: "abc", baseSha: "base", files, evidence, humanApproved: false }).status).toBe("FAIL");
    expect(decideMerge({ candidateSha: "abc", baseSha: "base", files, evidence, humanApproved: true }).status).toBe("PASS");
    expect(mayAutonomouslyMerge(files)).toBe(false);
  });

  it.each(["FAIL", "INFRASTRUCTURE_FAILURE", "CANCELLED", "FLAKY", "PENDING"] as const)("fails closed for %s evidence", (status) => {
    const evidence = [{ checkId: "R-01", candidateSha: "abc", status }];
    expect(decideMerge({ candidateSha: "abc", baseSha: "base", files: ["README.md"], evidence }).status).toBe("FAIL");
  });

  it("requires supply-chain evidence for dependency changes", () => {
    const checks = requiredChecks(classifyChanges(["package-lock.json"]));
    expect(checks).toContain("SEC-01");
    expect(checks).toEqual(expect.arrayContaining(["I-01", "I-03", "I-04"]));
  });

  it("treats protected domain-oracle tests as control-plane changes", () => {
    expect(classifyChanges(["python/voxel_tree/contracts/tests/test_contracts.py"])).toContain("C5");
  });
});
