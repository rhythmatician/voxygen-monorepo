import { describe, it, expect } from "vitest";
import fs from "node:fs";
import { execSync } from "node:child_process";
import { mkdtemp, rm, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

describe("Doctor ephemeral-resource cleanup — try/finally + startup reconciliation scoped to doctor-*", () => {
  it("reconcileStaleDoctorResources cleans stale doctor-* worktree dir + branch and is idempotent (temp repo)", async () => {
    const tmp = await mkdtemp(join(tmpdir(), "doctor-reconcile-"));
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });

      // Simulate stale doctor worktree directory (not registered) + stale branch
      const staleBranch = `doctor-${Date.now()}-stale-fixture`;
      const stalePath = join(tmp, ".sandcastle", "worktrees", staleBranch);
      await mkdir(stalePath, { recursive: true });
      await writeFile(join(stalePath, ".git"), "gitdir: bogus");
      execSync(`git branch ${staleBranch}`, { cwd: tmp });
      expect(fs.existsSync(stalePath)).toBe(true);
      expect(execSync(`git branch --list '${staleBranch}'`, { cwd: tmp, encoding: 'utf8' }).trim()).toContain(staleBranch);

      // Run helper directly in this tmp repo (change cwd)
      const origCwd = process.cwd();
      process.chdir(tmp);
      const { reconcileStaleDoctorResources } = await import("./doctor-helpers.mts");
      await reconcileStaleDoctorResources();
      process.chdir(origCwd);

      expect(fs.existsSync(stalePath)).toBe(false);
      const branchesAfter = execSync(`git branch --list 'doctor-*'`, { cwd: tmp, encoding: 'utf8' }).trim();
      expect(branchesAfter).not.toContain(staleBranch);
      const worktreeListAfter = execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' });
      expect(worktreeListAfter).not.toMatch(/\.sandcastle\/worktrees\/doctor-/);
      expect(worktreeListAfter).not.toContain(staleBranch);

      // Idempotent second run
      process.chdir(tmp);
      await reconcileStaleDoctorResources();
      await reconcileStaleDoctorResources();
      process.chdir(origCwd);
      expect(fs.existsSync(stalePath)).toBe(false);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }, 15000);

  it("reconcileStaleDoctorResources cleans registered doctor worktree (git worktree add) case", async () => {
    const tmp = await mkdtemp(join(tmpdir(), "doctor-reg-"));
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });

      const staleBranch = `doctor-${Date.now()}-reg`;
      const stalePath = join(tmp, ".sandcastle", "worktrees", staleBranch);
      // Create a real worktree registration
      execSync(`git worktree add -f --no-checkout "${stalePath}" -b ${staleBranch}`, { cwd: tmp });
      expect(fs.existsSync(stalePath)).toBe(true);
      expect(execSync(`git branch --list '${staleBranch}'`, { cwd: tmp, encoding: 'utf8' }).trim()).toContain(staleBranch);
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).toContain(stalePath);

      const origCwd = process.cwd();
      process.chdir(tmp);
      const { reconcileStaleDoctorResources } = await import("./doctor-helpers.mts");
      await reconcileStaleDoctorResources();
      process.chdir(origCwd);

      expect(fs.existsSync(stalePath)).toBe(false);
      expect(execSync(`git branch --list '${staleBranch}'`, { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).not.toContain(stalePath);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }, 15000);

  it("cleanupDoctorBranchAndWorktree is scoped strictly to doctor-* and is idempotent", async () => {
    const tmp = await mkdtemp(join(tmpdir(), "doctor-scope-"));
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });
      execSync('git branch feature/human-keep', { cwd: tmp });
      const origCwd = process.cwd();
      process.chdir(tmp);
      const { cleanupDoctorBranchAndWorktree } = await import("./doctor-helpers.mts");
      // Must NOT delete human branch
      cleanupDoctorBranchAndWorktree("feature/human-keep");
      cleanupDoctorBranchAndWorktree("human-keep");
      expect(execSync("git branch --list 'feature/human-keep'", { cwd: tmp, encoding: 'utf8' }).trim()).toContain("human-keep");
      // Idempotent on non-existent doctor branch
      expect(() => cleanupDoctorBranchAndWorktree("doctor-nonexistent-12345")).not.toThrow();
      expect(() => cleanupDoctorBranchAndWorktree("doctor-nonexistent-12345")).not.toThrow();
      process.chdir(origCwd);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }, 10000);

  it("reconcile leaves non-doctor worktrees untouched", async () => {
    const tmp = await mkdtemp(join(tmpdir(), "doctor-nondoctor-"));
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });
      const batchPath = join(tmp, ".sandcastle", "worktrees", "batch-keep");
      execSync(`git worktree add -b sandcastle/issue-9999 "${batchPath}" HEAD`, { cwd: tmp });
      const origCwd = process.cwd();
      process.chdir(tmp);
      const { reconcileStaleDoctorResources } = await import("./doctor-helpers.mts");
      await reconcileStaleDoctorResources();
      await reconcileStaleDoctorResources();
      process.chdir(origCwd);
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).toContain("batch-keep");
      expect(execSync("git branch --list 'doctor-*'", { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");
      // cleanup
      execSync(`git worktree remove --force "${batchPath}"`, { cwd: tmp });
      execSync('git branch -D sandcastle/issue-9999', { cwd: tmp });
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }, 15000);

  it("stale doctor-* cleanup also occurs when Doctor would otherwise take cache-hit path", async () => {
    const tmp = await mkdtemp(join(tmpdir(), "doctor-cache-hit-"));
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });
      const sha = execSync('git rev-parse HEAD', { cwd: tmp, encoding: 'utf8' }).trim();
      // Create stale doctor resource that should be cleaned even though cache would hit
      const staleBranch = `doctor-${Date.now()}-cache-hit`;
      const stalePath = join(tmp, ".sandcastle", "worktrees", staleBranch);
      await mkdir(stalePath, { recursive: true });
      await writeFile(join(stalePath, ".git"), "gitdir: bogus");
      execSync(`git branch ${staleBranch}`, { cwd: tmp });
      // Write a valid doctor cache that would cause early return in runDoctor
      await mkdir(join(tmp, ".sandcastle"), { recursive: true });
      await writeFile(join(tmp, ".sandcastle", ".doctor-cache.json"), JSON.stringify({ sha, imageId: "test-image", imageDigest: "", passed: true, at: new Date().toISOString() }));
      expect(fs.existsSync(stalePath)).toBe(true);
      const origCwd = process.cwd();
      process.chdir(tmp);
      const { reconcileStaleDoctorResources, assertNoStaleDoctorResources } = await import("./doctor-helpers.mts");
      // Simulate runDoctor startup sequence: reconcile then mandatory ASSERT before cache-hit
      // Before reconcile, assert must be FAIL (proves cache-hit would be blocked)
      const before = assertNoStaleDoctorResources();
      expect(before.ok).toBe(false);
      expect(before.leftover.join(",")).toMatch(/doctor-.*cache-hit/);
      // This is exactly what runDoctor does at its very top, before checking cache
      await reconcileStaleDoctorResources();
      // After reconcile, mandatory startup postcondition must pass before cache-hit is allowed
      const after = assertNoStaleDoctorResources();
      expect(after.ok).toBe(true);
      expect(after.leftover).toEqual([]);
      process.chdir(origCwd);
      // Even though cache would have hit, stale resources must be gone
      expect(fs.existsSync(stalePath)).toBe(false);
      expect(execSync(`git branch --list '${staleBranch}'`, { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).not.toContain(staleBranch);
      // Verify cache file still exists and is valid (we didn't delete it)
      expect(fs.existsSync(join(tmp, ".sandcastle", ".doctor-cache.json"))).toBe(true);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }, 15000);

  it("startup postcondition is fail-closed — stale resource blocks cache-hit, inspection errors do not masquerade as clean", async () => {
    const tmp = await mkdtemp(join(tmpdir(), "doctor-failclosed-"));
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });
      const staleBranch = `doctor-${Date.now()}-failclosed`;
      const stalePath = join(tmp, ".sandcastle", "worktrees", staleBranch);
      await mkdir(stalePath, { recursive: true });
      await writeFile(join(stalePath, ".git"), "gitdir: bogus");
      execSync(`git branch ${staleBranch}`, { cwd: tmp });
      const origCwd = process.cwd();
      process.chdir(tmp);
      const { assertNoStaleDoctorResources } = await import("./doctor-helpers.mts");
      // With stale leftover, assert must fail — this is the gate that prevents a cached PASS
      const blocked = assertNoStaleDoctorResources();
      expect(blocked.ok).toBe(false);
      expect(blocked.leftover.some((l) => l.includes(staleBranch))).toBe(true);
      process.chdir(origCwd);
      //Inspection-error path: run assert in a non-git directory (git worktree list will fail) → must be inspection-error, not clean
      const nonGit = await mkdtemp(join(tmpdir(), "doctor-nongit-"));
      try {
        const orig2 = process.cwd();
        process.chdir(nonGit);
        const { assertNoStaleDoctorResources: assert2 } = await import("./doctor-helpers.mts");
        const errResult = assert2();
        expect(errResult.ok).toBe(false);
        expect(errResult.leftover.join(",")).toMatch(/inspection-error/);
        process.chdir(orig2);
      } finally {
        await rm(nonGit, { recursive: true, force: true });
      }
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }, 15000);
});
