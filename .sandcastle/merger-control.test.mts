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
    // Factory-error release now routes through the tracker adapter's verified
    // saga (releaseAfterFactoryError) — main.mts no longer composes raw edits.
    expect(productionSource).toContain("releaseAfterFactoryError");
    // Inspect real submitImplementation adapter — executable code, not dummy comments
    const adapterStart = productionSource.indexOf("const submitImplementation = async");
    expect(adapterStart).toBeGreaterThan(-1);
    const adapterEnd = productionSource.indexOf("// Capture preparation research error", adapterStart);
    expect(adapterEnd).toBeGreaterThan(adapterStart);
    const adapter = productionSource.slice(adapterStart, adapterEnd);
    // merger failure paths: worktree creation and sandcastle merger both route through partitionMergerInfrastructureFailure + markFactoryError + throw
    const mergerMatches = adapter.match(/partitionMergerInfrastructureFailure\(/g) ?? [];
    expect(mergerMatches.length).toBeGreaterThanOrEqual(2);
    const markMatches = adapter.match(/await markFactoryError\(/g) ?? [];
    expect(markMatches.length).toBeGreaterThanOrEqual(2);
    expect(adapter).not.toContain("await markBlocked(");
    // Merger failure throws and is caught by state machine as submission-factory-error, not break here
    expect(adapter).toContain("throw new Error(reason)");
    expect(adapter).toContain("await publishBatchBranch(");
  });

  it("routes publication failure through FACTORY_ERROR and is converted to stop/submission-factory-error", () => {
    const productionSource = readFileSync(".sandcastle/main.mts", "utf8");
    const adapterStart = productionSource.indexOf("const submitImplementation = async");
    expect(adapterStart).toBeGreaterThan(-1);
    const adapterEnd = productionSource.indexOf("// Capture preparation research error", adapterStart);
    expect(adapterEnd).toBeGreaterThan(adapterStart);
    const adapter = productionSource.slice(adapterStart, adapterEnd);

    expect(adapter).toContain("await publishBatchBranch(");
    expect(adapter).toContain("partitionMergerInfrastructureFailure(completedIssues, reason)");
    expect(adapter).toContain("await markFactoryError(failure.id, failure.branch, failure.reason)");
    expect(adapter).toContain("throw new Error(reason)");
    expect(adapter).not.toContain("git push origin");
    expect(adapter).not.toContain("PR creation skipped");

    // State machine converts thrown publication failure to stop/submission-factory-error
    const factorySrc = readFileSync(".sandcastle/factory-iteration.mts", "utf8");
    expect(factorySrc).toContain('submission-factory-error');
    expect(factorySrc).toContain("await submission.submit");
    // main must switch directly on result.next, not reinterpret finalNext
    expect(productionSource).toContain("if (result.next.kind === \"stop\")");
    expect(productionSource).toContain('result.next.reason === "submission-factory-error"');
    expect(productionSource).not.toContain("let finalNext = result.next");
    expect(productionSource).not.toContain("if (publicationFailed) break;");
  });
});
