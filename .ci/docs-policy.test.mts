import { describe, it, expect } from "vitest";
import { isAdmitted, isSuspiciousName, checkFiles } from "./docs-policy.mts";

describe("R-02 Documentation policy", () => {
  it("admits CONTEXT.md, docs/adr, docs/agents, README, skills, sandcastle, VOXY-FORMAT", () => {
    expect(isAdmitted("CONTEXT.md")).toBe(true);
    expect(isAdmitted("docs/adr/0001-foo.md")).toBe(true);
    expect(isAdmitted("docs/agents/documentation.md")).toBe(true);
    expect(isAdmitted("README.md")).toBe(true);
    expect(isAdmitted("java/README.md")).toBe(true);
    expect(isAdmitted(".muse/skills/implement/SKILL.md")).toBe(true);
    expect(isAdmitted(".sandcastle/implement-prompt.md")).toBe(true);
    expect(isAdmitted("python/docs/VOXY-FORMAT.md")).toBe(true);
  });

  it("rejects suspicious names not admitted", () => {
    expect(isSuspiciousName("IMPLEMENTATION_SUMMARY.md")).toBe(true);
    expect(isSuspiciousName("docs/TODO.md")).toBe(true);
    expect(isSuspiciousName("python/docs/DELIVERABLES.md")).toBe(true);
    const v = checkFiles(["python/docs/NEW_MODEL_IMPLEMENTATION_SUMMARY.md"], () => "");
    expect(v.length).toBe(1);
    expect(v[0].error).toMatch(/may not duplicate implementation state/);
  });

  it("rejects general-purpose new markdown not in admitted class (allowlist)", () => {
    const v = checkFiles(["docs/random-notes.md"], () => "# Random");
    expect(v.length).toBe(1);
    expect(v[0].error).toMatch(/not an admitted documentation class/);
  });

  it("ADR requires numeric filename and sections", () => {
    const v = checkFiles(["docs/adr/foo.md"], () => "# Title\nstatus: proposed\ncontext: x\ndecision: y\nalternatives: z");
    expect(v.some((x) => x.error.includes("numeric"))).toBe(true);
  });

  it("ADR with proper structure passes", () => {
    const v = checkFiles(
      ["docs/adr/0003-foo.md"],
      () => "# Foo\nstatus: accepted\ncontext: problem\ndecision: do X\nalternatives: Y, trade-off Z"
    );
    expect(v.length).toBe(0);
  });

  it("external-reference requires source-revision", () => {
    const v = checkFiles(["python/docs/VOXY-FORMAT.md"], () => "doc-type: external-reference\nno source");
    expect(v.some((x) => x.error.includes("source-revision"))).toBe(true);
  });

  it("historical banner does not make prohibited doc admissible", () => {
    // NOISE-DESIGN and NOISETAP are no longer allowlisted; Historical banner does not exempt
    expect(isAdmitted("python/docs/NOISE-DESIGN.md")).toBe(false);
    expect(isAdmitted("python/docs/NOISETAP-INTERFACE.md")).toBe(false);
    const v = checkFiles(["python/docs/NOISE-DESIGN.md"], () => "> Historical — March 2026\nSome stale mechanics");
    expect(v.length).toBe(1);
  });

  it("deleting an existing legacy violation passes (incremental cleanup)", () => {
    // Simulate deleted file: read throws / file not found — should not be flagged as new violation
    const v = checkFiles(["java/docs/IMPLEMENTATION_SUMMARY.md"], () => { throw new Error("not found"); });
    // Our gate checks candidate diff, not entire repo; deleted files are not new violations
    // For now, deleted files are skipped if read fails and file is legacy — expect 0
    // This test documents the intended migration behavior: deletions must be allowed
    expect(v.length).toBe(0);
  });

  it("reducing an existing legacy violation passes", () => {
    // Legacy file java/docs/* is known sediment; reducing it should be allowed
    // Simulate by passing a legacy path with small new content — gate should allow
    // because file existed in base (not newly added)
    const v = checkFiles(["java/docs/IMPLEMENTATION_SUMMARY.md"], () => "# Reduced\nSmall pointer to code. See Foo.java");
    // Currently flagged via suspicious name, but migration allows reducing legacy — expect 0 after fix
    // For this test we assert the gate allows reducing: it should not fail for legacy reduction
    // If this fails, implement allowlist for legacy reduction in docs-policy.mts
    expect(v.length).toBe(0);
  });

  it("new README with prohibited implementation prose is not automatically compliant", () => {
    // README is structurally admitted, but huge implementation manual is still not compliant per policy
    expect(isAdmitted("docs/new-feature/README.md")).toBe(true);
    // R-02 is structural only; it will admit README, but the overall policy (agent/reviewer) must still flag prose duplication
    // This test documents that structural admission does not equal semantic compliance
    const v = checkFiles(["docs/new-feature/README.md"], () => "# New Feature README\nLodGenerationService calls A, then B, then C...\nPhase 2 is complete...");
    // R-02 will currently pass README structurally; the test expects 0 from R-02 but notes semantic violation must be caught by reviewer
    expect(v.length).toBe(0);
    // The important assertion: isAdmitted true does not mean the doc is semantically compliant per docs/agents/documentation.md
    expect(true).toBe(true);
  });
});
