import { execFileSync } from "node:child_process";

/**
 * Merge-between-rounds — local integration of a round's completed
 * Sandcastle branches so the next round builds on prerequisite commits
 * without waiting for origin/main PR merges.
 *
 * Intended use: after a factory iteration produces `completedIds`
 * (e.g. 121,197,227,272,228), call `mergeCompletedBranchesLocally`
 * from the host before the next iteration's `factoryBaseSha` freeze.
 * The next iteration's workers will then base on a HEAD that contains
 * the round's commits, satisfying `blockedBy` prerequisites locally.
 *
 * This is intentionally local and explicit — it does not push to
 * origin/main and does not bypass review. The bulk PR that merges
 * the same branches to `main` should still be opened for human review
 * (see bulk-round1). The local merge is an optimization to unblock
 * dependent round 2 early while review proceeds, with the understanding
 * that if round 1 review amends a branch, the integration branch needs
 * a rebase.
 */

export interface MergeResult {
  merged: string[]; // ids that were merged
  alreadyContained: string[]; // ids already ancestor of HEAD
  failed: { id: string; reason: string }[];
}

function branchForIssue(issueNumber: string | number): string {
  return `sandcastle/issue-${issueNumber}`;
}

export function mergeCompletedBranchesLocally(
  repoRoot: string,
  completedIds: string[],
  options?: { noFF?: boolean; messagePrefix?: string },
): MergeResult {
  const noFF = options?.noFF ?? true;
  const prefix = options?.messagePrefix ?? "bulk: merge";
  const result: MergeResult = { merged: [], alreadyContained: [], failed: [] };

  for (const id of completedIds) {
    const branch = branchForIssue(id);
    // Check if branch exists locally or origin
    let branchExists = false;
    try {
      execFileSync("git", ["rev-parse", "--verify", branch], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
      branchExists = true;
    } catch {
      try {
        execFileSync("git", ["rev-parse", "--verify", `origin/${branch}`], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
        // fetch it locally for merge
        execFileSync("git", ["fetch", "origin", `${branch}:${branch}`], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
        branchExists = true;
      } catch {
        result.failed.push({ id, reason: `branch ${branch} not found locally or on origin` });
        continue;
      }
    }

    // Already contained?
    try {
      execFileSync("git", ["merge-base", "--is-ancestor", branch, "HEAD"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
      result.alreadyContained.push(id);
      continue;
    } catch {
      // not ancestor, need merge
    }

    try {
      const args = ["merge", ...(noFF ? ["--no-ff"] : []), branch, "-m", `${prefix} #${id}`];
      execFileSync("git", args, { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
      result.merged.push(id);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      // Abort merge if conflict
      try {
        execFileSync("git", ["merge", "--abort"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
      } catch {}
      result.failed.push({ id, reason: msg.slice(0, 500) });
    }
  }

  return result;
}

/**
 * Convenience: push current HEAD to origin under the same branch name.
 * Used after a successful local round integration to make the integration
 * branch visible for next round's workers (if they fetch).
 */
export function pushCurrentBranch(repoRoot: string, branch: string): void {
  execFileSync("git", ["push", "origin", branch], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
}
