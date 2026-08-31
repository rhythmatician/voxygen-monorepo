import { describe, expect, it } from "vitest";
import fs from "node:fs";

describe("qualification remote-write policy", () => {
  const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
  const branchHelpers = fs.readFileSync(".sandcastle/branch-helpers.mts", "utf8");

  it("qualification skips production reconciliation mutations", () => {
    expect(main).toContain(
      "if (QUALIFICATION_LIFECYCLE.mutateOutcomeState) {\n      await reconcileInProgressIssues();",
    );
    // GC is a production mutation (branch/worktree/remote deletes) and must also be gated
    expect(main).toContain("runSandcastleGC");
    expect(main).toMatch(/if \(QUALIFICATION_LIFECYCLE\.mutateOutcomeState\)[\s\S]*?runSandcastleGC/);
  });

  it("qualification forbids stale-branch remote deletion while normal callers retain it", () => {
    expect(main).toContain(
      "branchHelpers.prepareIssueBranch(REPO_ROOT, p.branch, factoryBaseSha, callerBranch, callerSha, p.id, QUALIFICATION_LIFECYCLE.integrate)",
    );
    expect(branchHelpers).toContain("allowRemoteDelete = true");
    expect(branchHelpers).toMatch(
      /if \(allowRemoteDelete\) \{[\s\S]*?\["push", "origin", "--delete", branch\][\s\S]*?awaitGetRemoteDelete\(repoRoot, branch\)/,
    );
  });
});
