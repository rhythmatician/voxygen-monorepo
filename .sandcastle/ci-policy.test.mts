import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  classifyChanges,
  codeOwnerPatterns,
  decideMerge,
  humanApprovalReasons,
  mayAutonomouslyMerge,
  requiredChecks,
  requiresHumanApproval,
} from "./ci-policy.mts";

const evidenceFor = (files: string[], candidateSha = "abc") =>
  requiredChecks(classifyChanges(files)).map((checkId) => ({ checkId, candidateSha, status: "PASS" as const }));
const contractChecks = [
  "J-01", "J-02", "J-03", "J-04",
  "P-01", "P-02", "P-03", "P-04",
  "X-01", "X-02", "X-03", "X-04", "X-05", "X-06", "X-07", "X-08", "X-09",
];
const javaIntegrationChecks = ["I-01", "I-03", "I-04"];

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

  it.each([
    ".sandcastle/ci-policy.mts",
    ".ci/checks.json",
    ".github/workflows/factory-ci.yml",
    ".github/CODEOWNERS",
    ".github/hooks/rtk-rewrite.json",
    ".muse/skills/implement/SKILL.md",
    "docs/agents/documentation.md",
    "AGENTS.md",
    "package.json",
    "package-lock.json",
    "tsconfig.json",
    "java/build.gradle",
    "java/gradle.properties",
    "java/gradle/wrapper/gradle-wrapper.properties",
    "java/gradlew",
    "java/gradlew.bat",
    "java/settings.gradle",
    "python/pyproject.toml",
    "python/uv.lock",
  ])("requires factory evidence and independent approval for control-plane file %s", (file) => {
    const files = [file];
    const classes = classifyChanges(files);
    const unapproved = decideMerge({ candidateSha: "abc", baseSha: "base", files, evidence: evidenceFor(files) });
    const approved = decideMerge({ candidateSha: "abc", baseSha: "base", files, evidence: evidenceFor(files), humanApproved: true });
    expect(classes).toContain("C1_FACTORY");
    expect(requiredChecks(classes)).toEqual(expect.arrayContaining(["F-01", "F-02", "F-03", "F-04"]));
    expect(unapproved.status).toBe("FAIL");
    expect(unapproved.humanApproval).toEqual({ required: true, reasons: ["software-factory-control-plane"] });
    expect(approved.status).toBe("PASS");
    expect(mayAutonomouslyMerge(files)).toBe(false);
  });

  it.each(["CONTEXT.md", "docs/adr/0003-example.md"])(
    "prevents a candidate from rewriting accepted normative input %s and using it to justify the same diff",
    (file) => {
      const files = [file];
      expect(classifyChanges(files)).not.toContain("C1_FACTORY");
      expect(humanApprovalReasons(files)).toEqual(["accepted-policy-roots"]);
      expect(requiresHumanApproval(files)).toBe(true);
      expect(mayAutonomouslyMerge(files)).toBe(false);
      expect(decideMerge({ candidateSha: "abc", baseSha: "base", files, evidence: evidenceFor(files) }).status).toBe("FAIL");
      expect(decideMerge({ candidateSha: "abc", baseSha: "base", files, evidence: evidenceFor(files), humanApproved: true }).status).toBe("PASS");
    },
  );

  it("allows an ordinary Java Voxy test to merge autonomously while retaining Java, contract, and integration evidence", () => {
    const files = ["java/src/test/java/com/rhythmatician/lodiffusion/voxy/L1AvailabilityContractTest.java"];
    const classes = classifyChanges(files);
    const checks = requiredChecks(classes);
    expect(classes).toEqual(expect.arrayContaining(["C1_JAVA", "C2", "C3"]));
    expect(requiresHumanApproval(files)).toBe(false);
    expect(mayAutonomouslyMerge(files)).toBe(true);
    expect(checks).toEqual(expect.arrayContaining([...contractChecks, ...javaIntegrationChecks]));
  });

  it("classifies PR #99's changed-file set as autonomous", () => {
    const files = [
      ".gitignore",
      "java/src/test/java/com/rhythmatician/lodiffusion/voxy/L1AvailabilityContractTest.java",
    ];
    expect(requiresHumanApproval(files)).toBe(false);
    expect(mayAutonomouslyMerge(files)).toBe(true);
    expect(requiredChecks(classifyChanges(files))).toEqual(expect.arrayContaining([...contractChecks, ...javaIntegrationChecks]));
  });

  it("allows ordinary Python contract tests to merge autonomously while retaining Python and contract evidence", () => {
    const files = ["python/voxel_tree/contracts/tests/test_contracts.py"];
    const classes = classifyChanges(files);
    const checks = requiredChecks(classes);
    expect(classes).toEqual(expect.arrayContaining(["C1_PYTHON", "C2", "C7"]));
    expect(requiresHumanApproval(files)).toBe(false);
    expect(mayAutonomouslyMerge(files)).toBe(true);
    expect(checks).toEqual(expect.arrayContaining(contractChecks));
  });

  it("fails safe for a mixed product and protected factory diff", () => {
    const files = ["java/src/main/java/example/Product.java", ".ci/checks.json"];
    expect(classifyChanges(files)).toEqual(expect.arrayContaining(["C1_JAVA", "C1_FACTORY"]));
    expect(requiresHumanApproval(files)).toBe(true);
    expect(mayAutonomouslyMerge(files)).toBe(false);
  });

  it("keeps CODEOWNERS exactly aligned with the configured human-approval boundary", () => {
    const contents = readFileSync(new URL("../.github/CODEOWNERS", import.meta.url), "utf8");
    const actualCodeOwnerPatterns = contents.split(/\r?\n/).map((line) => line.trim()).filter((line) => line && !line.startsWith("#")).map((line) => line.split(/\s+/)[0]!).sort();
    expect(actualCodeOwnerPatterns).toEqual(codeOwnerPatterns());
    expect(contents.match(/@rhythmatician/g)?.length).toBe(actualCodeOwnerPatterns.length);
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
});
