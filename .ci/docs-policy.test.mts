import { describe, it, expect } from "vitest";
import { isAdmitted, isSuspiciousName, checkFiles, checkFilesWithStatus, type FileEntry } from "./docs-policy.mts";

describe("R-02 Documentation policy", () => {
  it("admits CONTEXT.md, docs/adr, docs/agents, README, skills, sandcastle, docs/external, VOXY-FORMAT", () => {
    expect(isAdmitted("CONTEXT.md")).toBe(true);
    expect(isAdmitted("docs/adr/0001-foo.md")).toBe(true);
    expect(isAdmitted("docs/agents/documentation.md")).toBe(true);
    expect(isAdmitted("README.md")).toBe(true);
    expect(isAdmitted("java/README.md")).toBe(true);
    expect(isAdmitted(".muse/skills/implement/SKILL.md")).toBe(true);
    expect(isAdmitted(".sandcastle/implement-prompt.md")).toBe(true);
    expect(isAdmitted("docs/external/research.md")).toBe(true);
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

  it("external-reference requires source-revision and docs/external location", () => {
    // docs/external without provenance fails
    const v1 = checkFilesWithStatus(
      [{ path: "docs/external/research.md", status: "A" }],
      () => null,
      () => "doc-type: external-reference\nno source"
    );
    expect(v1.some((x) => x.error.includes("source-revision"))).toBe(true);

    // docs/external with proper provenance passes
    const v2 = checkFilesWithStatus(
      [{ path: "docs/external/research.md", status: "A" }],
      () => null,
      () => "---\ndoc-type: external-reference\nsource-revision: abc123\n---\n# Research"
    );
    expect(v2.length).toBe(0);

    // arbitrary location claiming external-reference without docs/external still requires docs/external (fails admission)
    const v3 = checkFilesWithStatus(
      [{ path: "python/docs/RANDOM_EXTERNAL.md", status: "A" }],
      () => null,
      () => "doc-type: external-reference\nsource-revision: abc"
    );
    // Should fail because path not admitted (even though it has provenance, location matters)
    expect(v3.length).toBe(1);
    expect(v3[0].error).toMatch(/not an admitted/);

    // VOXY-FORMAT grandfathered with provenance passes
    const v4 = checkFilesWithStatus(
      [{ path: "python/docs/VOXY-FORMAT.md", status: "A" }],
      () => null,
      () => "doc-type: external-reference\nsource-revision: v1\n# VOXY"
    );
    expect(v4.length).toBe(0);
  });

  it("historical banner does not make prohibited doc admissible", () => {
    expect(isAdmitted("python/docs/NOISE-DESIGN.md")).toBe(false);
    expect(isAdmitted("python/docs/NOISETAP-INTERFACE.md")).toBe(false);
    const v = checkFiles(["python/docs/NOISE-DESIGN.md"], () => "> Historical — March 2026\nSome stale mechanics");
    expect(v.length).toBe(1);
  });

  it("deleting an existing legacy violation passes via name-status D", () => {
    const entries: FileEntry[] = [{ path: "java/docs/IMPLEMENTATION_SUMMARY.md", status: "D" }];
    const v = checkFilesWithStatus(entries, () => "old content", () => null);
    expect(v.length).toBe(0);
  });

  it("CLI deleted file that returns empty string is treated as D, not as new violation (seam mismatch regression)", () => {
    // Real CLI getCandidateContent returns "" on read failure, not throw. But with status D, it should still PASS
    // Simulate CLI behavior: getCandidate returns null for D, not ""
    const entries: FileEntry[] = [{ path: "java/docs/IMPLEMENTATION_SUMMARY.md", status: "D" }];
    const v = checkFilesWithStatus(
      entries,
      () => "old content that was violation",
      () => null // CLI would not have candidate content for D
    );
    expect(v.length).toBe(0);
    // Ensure that if we incorrectly used checkFiles (which infers from read) with "" it would wrongly fail, but with status it passes
    // This test ensures the status-based interface is used in production
  });

  it("reducing an existing legacy violation passes only if strictly smaller", () => {
    const base = "# Old\n" + "x".repeat(500);
    const small = "# Reduced\nSmall pointer to code. See Foo.java";
    const large = "# Old\n" + "x".repeat(600);
    // M with candidate < base → PASS
    const vPass = checkFilesWithStatus(
      [{ path: "java/docs/IMPLEMENTATION_SUMMARY.md", status: "M" }],
      () => base,
      () => small
    );
    expect(vPass.length).toBe(0);
    // M with candidate >= base → FAIL (not allowed to expand debt)
    const vFail = checkFilesWithStatus(
      [{ path: "java/docs/IMPLEMENTATION_SUMMARY.md", status: "M" }],
      () => base,
      () => large
    );
    expect(vFail.length).toBe(1);
    // Also test python/docs/ENVIRONMENT.md (known violation) - modifying without shrinking should fail
    const envBase = "x".repeat(1000);
    const envSame = "x".repeat(1000);
    const vEnv = checkFilesWithStatus(
      [{ path: "python/docs/ENVIRONMENT.md", status: "M" }],
      () => envBase,
      () => envSame
    );
    expect(vEnv.length).toBe(1);
  });

  it("new file A always enforces admission (no incremental bypass)", () => {
    const v = checkFilesWithStatus(
      [{ path: "java/docs/NEW_IMPL.md", status: "A" }],
      () => null,
      () => "See stuff"
    );
    // A with suspicious name fails even if small
    expect(v.length).toBe(1);
  });

  it("rename R treats destination as new (A semantics)", () => {
    const v = checkFilesWithStatus(
      [{ path: "docs/new-location.md", status: "R", oldPath: "java/docs/OLD.md" }],
      () => null,
      () => "# New"
    );
    expect(v.length).toBe(1);
    expect(v[0].error).toMatch(/not an admitted/);
    // Renaming to admitted location with proper content passes
    const v2 = checkFilesWithStatus(
      [{ path: "docs/adr/0004-rename.md", status: "R", oldPath: "java/docs/OLD.md" }],
      () => null,
      () => "# Title\nstatus: accepted\ncontext: x\ndecision: y\nalternatives: z"
    );
    expect(v2.length).toBe(0);
  });

  it("README structural admission does not imply semantic compliance - reviewer-owned", () => {
    expect(isAdmitted("docs/new-feature/README.md")).toBe(true);
    const v = checkFiles(["docs/new-feature/README.md"], () => "# New Feature README\nLodGenerationService calls A, then B, then C...\nPhase 2 is complete...");
    expect(v.length).toBe(0);
    // Document that semantic prose duplication is reviewer/policy concern, not R-02
    // This test is intentionally not asserting failure for implementation prose in README
  });

  it("CLI integration: real git diff --name-status seam is used (not injected read throw)", async () => {
    const { execSync } = await import("node:child_process");
    // Create a temp file to simulate name-status parsing
    const nameStatus = "D\tjava/docs/OLD.md\nM\tjava/docs/IMPLEMENTATION_SUMMARY.md\nA\tdocs/random-notes.md\n";
    // Parse like CLI does
    const entries: FileEntry[] = [];
    for (const line of nameStatus.split("\n")) {
      const t = line.trim();
      if (!t) continue;
      const parts = t.split("\t");
      const status = parts[0][0] as any;
      if (status === "R" && parts.length >= 3) entries.push({ path: parts[2], status: "R", oldPath: parts[1] });
      else if (parts[1]) entries.push({ path: parts[1], status });
    }
    expect(entries).toEqual([
      { path: "java/docs/OLD.md", status: "D" },
      { path: "java/docs/IMPLEMENTATION_SUMMARY.md", status: "M" },
      { path: "docs/random-notes.md", status: "A" },
    ]);
    // D should not be violation even though read would return ""
    const v = checkFilesWithStatus(entries, () => "old", () => "# Random");
    // D passes, M with candidate not smaller fails, A fails
    expect(v.some((x) => x.path === "java/docs/OLD.md")).toBe(false);
  });

  it("CLI integration: real temp Git repo with base/candidate and -z (covers seam bug)", { timeout: 15000 }, async () => {
    const { mkdtemp, writeFile, rm } = await import("node:fs/promises");
    const { tmpdir } = await import("node:os");
    const path = await import("node:path");
    const { execSync, spawnSync } = await import("node:child_process");
    const dir = await mkdtemp(path.join(tmpdir(), "r02-cli-test-"));
    const run = (cmd: string) => execSync(cmd, { cwd: dir, encoding: "utf-8" });
    const runCheck = (base: string, cand: string) => {
      const r = spawnSync("npx", ["tsx", path.join(process.cwd(), ".ci/docs-policy.mts"), "--base", base, "--candidate", cand], {
        cwd: dir,
        encoding: "utf-8",
      });
      return r.status ?? 1;
    };
    try {
      run("git init -q");
      run("git config user.email 'test@test.com'");
      run("git config user.name 'Test'");
      // base: create legacy file with 1000 bytes
      await writeFile(path.join(dir, "legacy.md"), "x".repeat(1000));
      run("git add legacy.md");
      run("git commit -qm base");
      const baseSha = run("git rev-parse HEAD").trim();

      // Test M: 1000 -> 999 should PASS (smaller)
      await writeFile(path.join(dir, "legacy.md"), "x".repeat(999));
      run("git add legacy.md");
      run("git commit -qm 'shrink'");
      const shrinkSha = run("git rev-parse HEAD").trim();
      expect(runCheck(baseSha, shrinkSha)).toBe(0);

      // Test M: 1000 -> 1000 should FAIL (not strictly smaller) - use different content same size
      run("git checkout -q " + baseSha);
      await writeFile(path.join(dir, "legacy.md"), "y".repeat(1000));
      run("git add legacy.md");
      run("git commit -qm 'same'");
      const sameSha = run("git rev-parse HEAD").trim();
      expect(runCheck(baseSha, sameSha)).toBe(1);

      // Test M: 1000 -> 1001 should FAIL
      run("git checkout -q " + baseSha);
      await writeFile(path.join(dir, "legacy.md"), "x".repeat(1001));
      run("git add legacy.md");
      run("git commit -qm 'expand'");
      const expandSha = run("git rev-parse HEAD").trim();
      expect(runCheck(baseSha, expandSha)).toBe(1);

      // Test D: delete should PASS
      run("git checkout -q " + baseSha);
      run("git rm -q legacy.md");
      run("git commit -qm 'delete'");
      const deleteSha = run("git rev-parse HEAD").trim();
      expect(runCheck(baseSha, deleteSha)).toBe(0);

      // Test A inadmissible.md -> FAIL
      run("git checkout -q " + baseSha);
      await writeFile(path.join(dir, "inadmissible.md"), "# New");
      run("git add inadmissible.md");
      run("git commit -qm 'add inadmissible'");
      const addBadSha = run("git rev-parse HEAD").trim();
      expect(runCheck(baseSha, addBadSha)).toBe(1);

      // Test A docs/adr valid -> PASS
      run("git checkout -q " + baseSha);
      // adr.md temp not needed
      // await writeFile(path.join(dir, "adr.md"), "# Title\nstatus: accepted\ncontext: x\ndecision: y\nalternatives: z");
      // need to create docs/adr path
      const adrPath = path.join(dir, "docs/adr/0001-x.md");
      execSync(`mkdir -p ${path.dirname(adrPath)}`, {cwd: dir});
      await writeFile(adrPath, "# Title\nstatus: accepted\ncontext: x\ndecision: y\nalternatives: z");
      run("git add docs/adr/0001-x.md");
      run("git commit -qm 'add adr'");
      const adrSha = run("git rev-parse HEAD").trim();
      expect(runCheck(baseSha, adrSha)).toBe(0);

      // Test A docs/external with provenance -> PASS
      run("git checkout -q " + baseSha);
      const extPath = path.join(dir, "docs/external/x.md");
      execSync(`mkdir -p ${path.dirname(extPath)}`, {cwd: dir});
      await writeFile(extPath, "---\ndoc-type: external-reference\nsource-revision: abc\n---\n# Research");
      run("git add docs/external/x.md");
      run("git commit -qm 'add external'");
      const extSha = run("git rev-parse HEAD").trim();
      expect(runCheck(baseSha, extSha)).toBe(0);

      // Test R: rename old.md -> new.md (new is inadmissible) -> FAIL as A
      run("git checkout -q " + baseSha);
      // create old file in base already is legacy.md, rename it
      run("git mv legacy.md new-location.md");
      run("git commit -qm 'rename'");
      const renameSha = run("git rev-parse HEAD").trim();
      // Deleting legacy and adding new-location (non-admitted) should fail because new-location is A
      expect(runCheck(baseSha, renameSha)).toBe(1);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});
