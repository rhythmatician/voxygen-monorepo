import { describe, it, expect } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { execSync, execFileSync } from "node:child_process";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";

/**
 * Regression for A1: post-merger ambient CWD invalidation.
 *
 * The merger creates a batch worktree at `.sandcastle/worktrees/...`
 * with `cwd: batchWorktreePath`. If the host process had chdir'd into
 * that worktree (either via Sandcastle internals or via explicit
 * `process.chdir`), then deleting the worktree makes `process.cwd()`
 * fail with `fatal: Unable to read current working directory`.
 * All subsequent host git invocations that omit `cwd: REPO_ROOT`
 * then fail, which is exactly what was observed after batch PR
 * creation for #182/#183 and #152/#181.
 *
 * The fix is to (a) restore to REPO_ROOT before any post-merger git
 * invocation and (b) pass `cwd: REPO_ROOT` explicitly for every
 * host git command on the post-merger + invariant path.
 *
 * After extraction, the real executable code lives in the submitImplementation adapter.
 */
describe("Regression: post-merger deleted CWD does not break host git", () => {
  it("main.mts restores REPO_ROOT before post-publication git and uses explicit cwd — inspects real submitImplementation adapter", () => {
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    const adapterStart = main.indexOf("const submitImplementation = async");
    expect(adapterStart).toBeGreaterThan(-1);
    const adapterEnd = main.indexOf("// Capture preparation research error", adapterStart);
    expect(adapterEnd).toBeGreaterThan(adapterStart);
    const adapter = main.slice(adapterStart, adapterEnd);

    // 1) merger failure path: after markFactoryError, before worktree removal — executable code in adapter
    expect(adapter).toContain(
      "await markFactoryError(failure.id, failure.branch, failure.reason);\n      }\n      process.chdir(REPO_ROOT);"
    );
    // 2) after merger success, before Host-side publication — executable code in adapter (no longer break-dependent)
    expect(adapter).toContain(
      "process.chdir(REPO_ROOT);\n\n    // Host-side: push batch branch + PR + auto-merge. Never push caller's branch."
    );
    // 3) before auto-merge diffSpec classification — explicit restoration
    expect(adapter).toContain(
      "if (prNumber) {\n                  process.chdir(REPO_ROOT);\n                  const diffSpec = branchHelpers.buildProtectedRootDiffSpec(factoryBaseSha, batchBranch);"
    );
    // 4) finally invariant restore before getcwd-dependent checks — executable with stabilization comments
    expect(adapter).toContain(
      "} finally {\n    // Stabilization A1: never rely on ambient cwd after merger worktree may have been deleted."
    );
    expect(adapter).toContain(
      "// Restore to stable REPO_ROOT before any getcwd()-dependent git invocation."
    );
    expect(adapter).toContain(
      "process.chdir(REPO_ROOT);\n    // Enforce invariant via helper: caller checkout never moved"
    );

    // Explicit cwd must be used for every host git on the post-merger path — inspect adapter, not dummy
    expect(adapter).toMatch(/execSync\(`git diff --name-only \$\{diffSpec\}`[^)]*cwd:\s*REPO_ROOT/);
    // batch worktree removal and branch deletion use explicit cwd
    expect(adapter).toMatch(/execSync\(`git worktree remove --force \$\{batchWorktreePath\}`[^)]*cwd:\s*REPO_ROOT/);
    expect(adapter).toMatch(/execSync\(`git branch -D \$\{batchBranch\}`[^)]*cwd:\s*REPO_ROOT/);
    // Caller isolation checks use helper with REPO_ROOT
    expect(adapter).toContain("branchHelpers.verifyCallerUnchanged(REPO_ROOT");
    expect(adapter).toContain("branchHelpers.cleanupBatchWorktree(REPO_ROOT");
  });

  it("host git helpers survive when process cwd is a deleted batch worktree", { timeout: 20000 }, async () => {
    const helpers = await import("./branch-helpers.mts");
    const tmp = await mkdtemp(path.join(tmpdir(), "batch-cwd-"));
    const originalCwd = process.cwd();
    try {
      execSync("git init -q", { cwd: tmp });
      execSync('git config user.email "test@test.com"', { cwd: tmp });
      execSync('git config user.name "test"', { cwd: tmp });
      await writeFile(path.join(tmp, "base.txt"), "base");
      execSync("git add base.txt && git commit -qm base", { cwd: tmp });
      execSync("git branch -M main", { cwd: tmp });
      const baseSha = execSync("git rev-parse HEAD", { cwd: tmp, encoding: "utf8" }).trim();
      const callerBranch = "main";
      const callerSha = baseSha;

      const batchBranch = "sandcastle/batch-999-regression";
      const worktreePath = helpers.createBatchWorktree(tmp, batchBranch, baseSha);
      expect(fs.existsSync(worktreePath)).toBe(true);

      // Simulate the buggy condition: host process cwd is inside the batch worktree
      process.chdir(worktreePath);
      expect(process.cwd()).toBe(worktreePath);

      // Now delete the worktree via the helper that uses explicit cwd: tmp
      // This succeeds even though cwd is inside the directory being removed
      helpers.cleanupBatchWorktree(tmp, worktreePath);
      expect(fs.existsSync(worktreePath)).toBe(false);

      // At this point process.cwd() is a deleted directory — getcwd fails
      let cwdDeleted = false;
      try {
        process.cwd();
      } catch (e) {
        cwdDeleted = true;
        expect(String(e)).toMatch(/No such file|Unable to read current working directory/);
      }
      // On some platforms getcwd may still return stale string but subsequent git without cwd fails
      // Either way, verify that implicit-cwd git fails and explicit-cwd git succeeds
      let implicitFailed = false;
      try {
        execSync("git branch --show-current", { encoding: "utf8" });
      } catch (e) {
        implicitFailed = true;
        const msg = e instanceof Error ? e.message : String(e);
        expect(msg).toMatch(/No such file|Unable to read current working directory|fatal/);
      }

      // Explicit restoration to tmp must succeed and cwd becomes tmp
      process.chdir(tmp);
      expect(process.cwd()).toBe(tmp);

      // Explicit cwd must succeed regardless of deleted ambient cwd
      const branchAfter = execSync("git branch --show-current", { encoding: "utf8", cwd: tmp }).trim();
      expect(branchAfter).toBe(callerBranch);

      const diffSpec = helpers.buildProtectedRootDiffSpec(baseSha, batchBranch);
      const diffOut = execSync(`git diff --name-only ${diffSpec}`, { encoding: "utf8", cwd: tmp });
      // batch branch is at base, no diff
      expect(diffOut.trim()).toBe("");

      const check = helpers.verifyCallerUnchanged(tmp, callerBranch, callerSha);
      expect(check.ok).toBe(true);

      // If implicit failed was not observed (platform-dependent), at least explicit succeeded
      // and we have proven the deleted-cwd scenario; if implicit succeeded, getcwd may not throw
      // but the regression still validates explicit-cwd path
      if (cwdDeleted) expect(implicitFailed).toBe(true);

      // Cleanup: restore cwd before removing tmp
      try { process.chdir(originalCwd); } catch {}
      try { process.chdir(tmp); } catch {}
      try { process.chdir(originalCwd); } catch {}
      const stillBranch = execFileSync("git", [ "branch", "--list", batchBranch ], { encoding: "utf8", cwd: tmp }).toString().trim();
      if (stillBranch) execFileSync("git", [ "branch", "-D", batchBranch ], { stdio: "ignore", cwd: tmp });
      try { execSync("git worktree prune", { stdio: "ignore", cwd: tmp }); } catch {}
    } finally {
      try { process.chdir(originalCwd); } catch {}
      await rm(tmp, { recursive: true, force: true });
      // Ensure we leave cwd valid
      try { process.chdir(originalCwd); } catch {}
    }
  });
});
