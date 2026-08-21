import { execFileSync } from "node:child_process";
import { chmodSync, mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { publishBatchBranch } from "./batch-publication.mts";
import {
  canClaimNextOuterIteration,
  partitionMergerInfrastructureFailure,
  partitionToMutationPlan,
} from "./factory-verdict-gate.mts";

function git(cwd: string, args: string[]): string {
  return execFileSync("git", args, { cwd, encoding: "utf8" }).trim();
}

function createPublicationFixture(): {
  repoRoot: string;
  batchWorktreePath: string;
  batchBranch: string;
  issueBranch: string;
} {
  const root = mkdtempSync(join(tmpdir(), "batch-publication-"));
  const remote = join(root, "origin.git");
  const repoRoot = join(root, "repo");
  const batchWorktreePath = join(root, "batch-worktree");
  const batchBranch = "sandcastle/batch-151-test";
  const issueBranch = "sandcastle/issue-151";

  mkdirSync(repoRoot);
  git(root, ["init", "--bare", remote]);
  git(repoRoot, ["init", "-b", "main"]);
  git(repoRoot, ["config", "user.email", "factory@example.invalid"]);
  git(repoRoot, ["config", "user.name", "Factory Test"]);
  writeFileSync(join(repoRoot, "base.txt"), "base\n");
  git(repoRoot, ["add", "base.txt"]);
  git(repoRoot, ["commit", "-m", "base"]);
  git(repoRoot, ["remote", "add", "origin", remote]);
  git(repoRoot, ["push", "origin", "main"]);
  git(repoRoot, ["branch", issueBranch]);
  git(repoRoot, ["worktree", "add", "-b", batchBranch, batchWorktreePath, "main"]);
  writeFileSync(join(batchWorktreePath, "merged.txt"), "merged\n");
  git(batchWorktreePath, ["add", "merged.txt"]);
  git(batchWorktreePath, ["commit", "-m", "batch merge"]);

  return { repoRoot, batchWorktreePath, batchBranch, issueBranch };
}

describe("batch publication", () => {
  it("fails closed before PR creation when the real batch push fails", async () => {
    const fixture = createPublicationFixture();
    const hookPath = join(fixture.repoRoot, ".git", "hooks", "pre-push");
    writeFileSync(hookPath, "#!/bin/sh\necho 'captured batch push rejection' >&2\nexit 1\n");
    chmodSync(hookPath, 0o755);
    execFileSync("git", ["update-index", "--refresh"], { cwd: fixture.repoRoot });
    const createPullRequest = vi.fn(async () => "unexpected");

    await expect(
      publishBatchBranch({ ...fixture, createPullRequest }),
    ).rejects.toThrow("captured batch push rejection");

    expect(createPullRequest).not.toHaveBeenCalled();
    expect(git(fixture.repoRoot, ["branch", "--list", fixture.issueBranch])).toContain(
      fixture.issueBranch,
    );
    expect(git(fixture.repoRoot, ["branch", "--list", fixture.batchBranch])).toContain(
      fixture.batchBranch,
    );
    expect(git(fixture.repoRoot, ["ls-remote", "--heads", "origin", fixture.batchBranch])).toBe("");

    const failure = partitionMergerInfrastructureFailure(
      [{ id: "151", branch: fixture.issueBranch }],
      "Batch publication failed: captured batch push rejection",
    );
    expect(partitionToMutationPlan(failure)).toEqual([
      expect.objectContaining({ kind: "factoryError", issue: { id: "151", branch: fixture.issueBranch } }),
    ]);
    expect(canClaimNextOuterIteration(failure)).toBe(false);
  });

  it("verifies the exact remote batch SHA before PR creation", async () => {
    const fixture = createPublicationFixture();
    const localSha = git(fixture.repoRoot, ["rev-parse", fixture.batchBranch]);
    const createPullRequest = vi.fn(async () => {
      const remoteSha = git(fixture.repoRoot, [
        "ls-remote",
        "--heads",
        "origin",
        fixture.batchBranch,
      ]).split(/\s+/)[0];
      expect(remoteSha).toBe(localSha);
      return "https://example.invalid/pull/151";
    });

    const result = await publishBatchBranch({ ...fixture, createPullRequest });

    expect(result.localSha).toBe(localSha);
    expect(result.remoteSha).toBe(localSha);
    expect(result.pullRequest).toBe("https://example.invalid/pull/151");
    expect(createPullRequest).toHaveBeenCalledOnce();
  });
});
