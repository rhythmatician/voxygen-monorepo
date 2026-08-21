import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import {
  ROOT_VOXYGEN_SANDBOX_IMAGE,
  mergerDockerOptions,
} from "./merger-control.mts";
import {
  canClaimNextOuterIteration,
  partitionMergerInfrastructureFailure,
  partitionToMutationPlan,
} from "./factory-verdict-gate.mts";

describe("merger control", () => {
  it("uses the root Voxygen image when merger cwd is a batch worktree", () => {
    const batchWorktree =
      "C:/repo/voxygen-monorepo/.sandcastle/worktrees/sandcastle-batch-151-example";
    const options = mergerDockerOptions({ GH_TOKEN: "worker-token" });

    expect(batchWorktree).not.toContain(ROOT_VOXYGEN_SANDBOX_IMAGE);
    expect(options).toEqual({
      imageName: "sandcastle:voxygen-monorepo",
      env: { GH_TOKEN: "worker-token" },
    });
    expect(readFileSync(".sandcastle/main.mts", "utf8")).toContain(
      "sandbox: docker(mergerDockerOptions(WORKER_SANDBOX_ENV))",
    );
  });

  it("classifies merger infrastructure failure as FACTORY_ERROR and stops progression", () => {
    const approvedIssues = [
      { id: "151", branch: "sandcastle/issue-151" },
    ];
    const partition = partitionMergerInfrastructureFailure(
      approvedIssues,
      "merger sandbox image unavailable",
    );
    const mutations = partitionToMutationPlan(partition);

    expect(partition.completed).toEqual([]);
    expect(partition.factoryErrors).toEqual([
      expect.objectContaining({
        id: "151",
        branch: "sandcastle/issue-151",
        reason: "merger sandbox image unavailable",
      }),
    ]);
    expect(mutations).toEqual([
      expect.objectContaining({
        kind: "factoryError",
        issue: { id: "151", branch: "sandcastle/issue-151" },
      }),
    ]);
    expect(canClaimNextOuterIteration(partition)).toBe(false);

    const productionSource = readFileSync(".sandcastle/main.mts", "utf8");
    expect(productionSource).toContain(
      '"--remove-label", "agent:in-progress", "--remove-assignee", "@me"',
    );
    const phaseThree = productionSource.slice(
      productionSource.indexOf("// ----- Phase 3: Merge"),
      productionSource.indexOf("// Host-side: push batch branch"),
    );
    expect(phaseThree.match(/partitionMergerInfrastructureFailure\(/g)).toHaveLength(2);
    expect(phaseThree.match(/await markFactoryError\(/g)).toHaveLength(2);
    expect(phaseThree).not.toContain("await markBlocked(");
    expect(phaseThree.match(/\bbreak;/g)).toHaveLength(2);
  });

  it("routes publication failure through FACTORY_ERROR and stops progression", () => {
    const productionSource = readFileSync(".sandcastle/main.mts", "utf8");
    const publication = productionSource.slice(
      productionSource.indexOf("// Host-side: push batch branch"),
      productionSource.indexOf("// A local merge is not integration into main"),
    );

    expect(publication).toContain("await publishBatchBranch(");
    expect(publication).toContain("partitionMergerInfrastructureFailure(completedIssues, reason)");
    expect(publication).toContain("await markFactoryError(failure.id, failure.branch, failure.reason)");
    expect(publication).toContain("if (publicationFailed) break;");
    expect(publication).not.toContain("git push origin");
    expect(publication).not.toContain("PR creation skipped");
  });
});
