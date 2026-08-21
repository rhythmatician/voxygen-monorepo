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
      expect(execSync(`git branch --list ${staleBranch}`, { cwd: tmp, encoding: 'utf8' }).trim()).toContain(staleBranch);

      // Pass tmp as explicit repoRoot — no process.chdir
      const { reconcileStaleDoctorResources } = await import("./doctor-helpers.mts");
      await reconcileStaleDoctorResources(tmp);

      expect(fs.existsSync(stalePath)).toBe(false);
       const branchesAfter = execSync("git branch --list doctor-*", { cwd: tmp, encoding: 'utf8' }).trim();
      expect(branchesAfter).not.toContain(staleBranch);
      const worktreeListAfter = execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' });
      expect(worktreeListAfter).not.toMatch(/\.sandcastle\/worktrees\/doctor-/);
      expect(worktreeListAfter).not.toContain(staleBranch);

      // Idempotent second run — pass repoRoot explicitly
      await reconcileStaleDoctorResources(tmp);
      await reconcileStaleDoctorResources(tmp);
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
      expect(execSync(`git branch --list ${staleBranch}`, { cwd: tmp, encoding: 'utf8' }).trim()).toContain(staleBranch);
       expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).toContain(staleBranch);

      const { reconcileStaleDoctorResources } = await import("./doctor-helpers.mts");
      await reconcileStaleDoctorResources(tmp);

      expect(fs.existsSync(stalePath)).toBe(false);
      expect(execSync(`git branch --list ${staleBranch}`, { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");
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
      const { cleanupDoctorBranchAndWorktree } = await import("./doctor-helpers.mts");
      // Must NOT delete human branch — pass tmp explicitly
      cleanupDoctorBranchAndWorktree(tmp, "feature/human-keep");
      cleanupDoctorBranchAndWorktree(tmp, "human-keep");
      expect(execSync("git branch --list feature/human-keep", { cwd: tmp, encoding: 'utf8' }).trim()).toContain("human-keep");
      // Idempotent on non-existent doctor branch
      expect(() => cleanupDoctorBranchAndWorktree(tmp, "doctor-nonexistent-12345")).not.toThrow();
      expect(() => cleanupDoctorBranchAndWorktree(tmp, "doctor-nonexistent-12345")).not.toThrow();
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
      const { reconcileStaleDoctorResources } = await import("./doctor-helpers.mts");
      await reconcileStaleDoctorResources(tmp);
      await reconcileStaleDoctorResources(tmp);
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).toContain("batch-keep");
      expect(execSync("git branch --list doctor-*", { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");
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
      const { reconcileStaleDoctorResources, assertNoStaleDoctorResources } = await import("./doctor-helpers.mts");
      // Simulate runDoctor startup sequence: reconcile then mandatory ASSERT before cache-hit — pass repoRoot explicitly
      // Before reconcile, assert must be FAIL (proves cache-hit would be blocked)
      const before = assertNoStaleDoctorResources(tmp);
      expect(before.ok).toBe(false);
      expect(before.leftover.join(",")).toMatch(/doctor-.*cache-hit/);
      // This is exactly what runDoctor does at its very top, before checking cache
      await reconcileStaleDoctorResources(tmp);
      // After reconcile, mandatory startup postcondition must pass before cache-hit is allowed
      const after = assertNoStaleDoctorResources(tmp);
      expect(after.ok).toBe(true);
      expect(after.leftover).toEqual([]);
      // Even though cache would have hit, stale resources must be gone
      expect(fs.existsSync(stalePath)).toBe(false);
      expect(execSync(`git branch --list ${staleBranch}`, { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");
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
      const { assertNoStaleDoctorResources } = await import("./doctor-helpers.mts");
      // With stale leftover, assert must fail — this is the gate that prevents a cached PASS — pass repoRoot explicitly
      const blocked = assertNoStaleDoctorResources(tmp);
      expect(blocked.ok).toBe(false);
      expect(blocked.leftover.some((l) => l.includes(staleBranch))).toBe(true);
      //Inspection-error path: run assert in a non-git directory (git worktree list will fail) → must be inspection-error, not clean — pass non-git dir as repoRoot
      const nonGit = await mkdtemp(join(tmpdir(), "doctor-nongit-"));
      try {
        const { assertNoStaleDoctorResources: assert2 } = await import("./doctor-helpers.mts");
        const errResult = assert2(nonGit);
        expect(errResult.ok).toBe(false);
        expect(errResult.leftover.join(",")).toMatch(/inspection-error/);
      } finally {
        await rm(nonGit, { recursive: true, force: true });
      }
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }, 15000);

  it("disappearing-cwd regression — doctor cleanup succeeds even when process cwd is deleted doctor worktree, explicit repoRoot keeps host git working", async () => {
    const tmp = await mkdtemp(join(tmpdir(), "doctor-cwd-"));
    const originalCwd = process.cwd();
    let doctorWorktree = "";
    let humanWorktree = "";
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });

      // Create a real registered doctor worktree (as Doctor does)
      const doctorBranch = `doctor-${Date.now()}-cwd-reg`;
      doctorWorktree = join(tmp, ".sandcastle", "worktrees", doctorBranch);
      execSync(`git worktree add -f --no-checkout "${doctorWorktree}" -b ${doctorBranch}`, { cwd: tmp });
      expect(fs.existsSync(doctorWorktree)).toBe(true);

      // Also create a human/non-doctor worktree that must survive
      humanWorktree = join(tmp, ".sandcastle", "worktrees", "batch-keep");
      execSync(`git worktree add -b sandcastle/issue-9999 "${humanWorktree}" HEAD`, { cwd: tmp });
      expect(fs.existsSync(humanWorktree)).toBe(true);

      // Simulate the disappearing-cwd condition: chdir into the doctor worktree
      process.chdir(doctorWorktree);
      expect(process.cwd()).toBe(doctorWorktree);

      // Restore/stabilize to repo root as production runDoctor finally does, BEFORE cleanup deletes the worktree
      // If production failed to chdir before cleanup, cwd would be a deleted directory and host git would fail.
      // Here we prove the explicit-repoRoot path works even when cwd was inside the deletable tree.
      try { process.chdir(tmp); } catch (e) { throw new Error(`cwd restore failed: ${e}`); }
      expect(process.cwd()).toBe(tmp);

      // Now simulate the worktree being deleted while cwd was previously inside it
      // In production this is sandbox.close() + cleanupDoctorBranchAndWorktree
      // To fully reproduce, chdir into worktree then have cleanup delete it, but we already restored above.
      // Second sub-case: chdir into worktree and then remove it without restoring first, proving explicit cwd saves host git.
      process.chdir(doctorWorktree);
      // Remove worktree via explicit repoRoot cwd — this would fail if helper used ambient cwd
      const { cleanupDoctorBranchAndWorktree, assertNoStaleDoctorResources } = await import("./doctor-helpers.mts");
      // Call cleanup with explicit repoRoot while cwd is still inside the to-be-deleted directory
      cleanupDoctorBranchAndWorktree(tmp, doctorBranch);
      // Immediately restore cwd to repoRoot (production finally does this before assert)
      try { process.chdir(tmp); } catch {}
      expect(process.cwd()).toBe(tmp);

      // Prove postconditions via explicit repoRoot — must succeed despite prior cwd having been deleted
      const result = assertNoStaleDoctorResources(tmp);
      expect(result.ok).toBe(true);
      expect(result.leftover).toEqual([]);

      // Prove no registered doctor worktree, no doctor directory, no doctor branch
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).not.toContain(doctorBranch);
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).not.toContain(doctorWorktree);
      expect(fs.existsSync(doctorWorktree)).toBe(false);
      expect(execSync(`git branch --list ${doctorBranch}`, { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");

      // Prove subsequent host git command from explicit repoRoot still succeeds (would have failed with ambient cwd)
      const wdList = execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' });
       expect(wdList).toContain("batch-keep");
       expect(wdList).toContain("sandcastle/worktrees/batch-keep");

      // Prove human/non-doctor worktrees remain untouched
      expect(fs.existsSync(humanWorktree)).toBe(true);
      expect(execSync('git worktree list --porcelain', { cwd: tmp, encoding: 'utf8' })).toContain("batch-keep");
       expect(execSync("git branch --list doctor-*", { cwd: tmp, encoding: 'utf8' }).trim()).toBe("");

      // Cleanup human worktree
      execSync(`git worktree remove --force "${humanWorktree}"`, { cwd: tmp });
      execSync('git branch -D sandcastle/issue-9999', { cwd: tmp });
    } finally {
      try { process.chdir(originalCwd); } catch {}
      await rm(tmp, { recursive: true, force: true });
    }
  }, 15000);
});
