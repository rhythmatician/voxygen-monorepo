import { describe, it, expect, vi } from "vitest";
import { runReviewerPass } from "./review-pass.mts";
import { verdictFixture } from "./review-verdict.mts";

describe("runReviewerPass", () => {
  it("extracts approved machine-readable verdict from reviewer stdout and returns it", async () => {
    const verdict = verdictFixture({ approved: true, findings: [], acceptanceCriteriaMet: [{ criterion: "A", met: true }] });
    const runReviewer = vi.fn().mockResolvedValue({
      stdout: `<verdict>${JSON.stringify(verdict)}</verdict>`,
      output: { ok: true },
    });
    const onReviewerFailure = vi.fn();
    const onInvalidVerdict = vi.fn();

    const result = await runReviewerPass({
      issueId: "1",
      issueTitle: "Run reviewer pass",
      branch: "sandcastle/issue-1",
      attempt: 0,
      issueBody: "A body",
      allCommits: ["abc123"],
      runReviewer,
      onReviewerFailure,
      onInvalidVerdict,
    });

    expect(result.commits).toEqual(["abc123"]);
    expect(result.verdict).toEqual(verdict);
    expect(onReviewerFailure).not.toHaveBeenCalled();
    expect(onInvalidVerdict).not.toHaveBeenCalled();
  });

  it("treats malformed reviewer output as null verdict and invokes invalid-verdict hook", async () => {
    const runReviewer = vi.fn().mockResolvedValue({
      stdout: "not-a-verdict",
      output: { nonsense: true },
    });
    const onReviewerFailure = vi.fn();
    const onInvalidVerdict = vi.fn();

    const result = await runReviewerPass({
      issueId: "2",
      issueTitle: "Malformed verdict",
      branch: "sandcastle/issue-2",
      attempt: 0,
      issueBody: "A body",
      allCommits: ["def456"],
      runReviewer,
      onReviewerFailure,
      onInvalidVerdict,
    });

    expect(result.verdict).toBeNull();
    expect(result.reviewText).toBe("not-a-verdict");
    expect(onReviewerFailure).not.toHaveBeenCalled();
    expect(onInvalidVerdict).toHaveBeenCalledTimes(1);
    expect(onInvalidVerdict.mock.calls[0][2]).toContain("reviewer produced no machine-readable verdict");
  });

  it("treats reviewer runtime failure as null verdict and invokes failure hook", async () => {
    const runReviewer = vi.fn().mockRejectedValue(new Error("reviewer process crashed"));
    const onReviewerFailure = vi.fn();
    const onInvalidVerdict = vi.fn();

    const result = await runReviewerPass({
      issueId: "3",
      issueTitle: "Reviewer failed",
      branch: "sandcastle/issue-3",
      attempt: 1,
      issueBody: "A body",
      allCommits: ["ghi789"],
      runReviewer,
      onReviewerFailure,
      onInvalidVerdict,
    });

    expect(result.verdict).toBeNull();
    expect(result.reviewText).toContain("reviewer process crashed");
    expect(onReviewerFailure).toHaveBeenCalledTimes(1);
    expect(onInvalidVerdict).not.toHaveBeenCalled();
  });
});
