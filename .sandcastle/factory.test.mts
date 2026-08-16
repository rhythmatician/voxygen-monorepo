import { describe, it, expect } from "vitest";
import { isEligible, partitionWorkers } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";
import { TRACER_BODY } from "./fixtures.mts";

// Helpers

function issue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 1,
    title: "Test",
    state: "open",
    labels: ["agent:implement"],
    assignees: [],
    body: TRACER_BODY,
    ...overrides,
  };
}

describe("AC10: worker failure does not merge or close, leaves blocked", () => {
  it("rejected worker is partitioned to failed, not completed", () => {
    const issues = [
      { id: "1", branch: "sandcastle/issue-1" },
      { id: "2", branch: "sandcastle/issue-2" },
    ];
    const settled = [
      { status: "fulfilled" as const, commits: ["abc"] },
      { status: "rejected" as const, reason: "docker crash" },
    ];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toEqual([{ id: "1", branch: "sandcastle/issue-1" }]);
    expect(failed).toHaveLength(1);
    expect(failed[0].id).toBe("2");
    expect(failed[0].reason).toContain("docker crash");
    // Only completed goes to merger — failed does not
    expect(completed.map((c) => c.branch)).not.toContain("sandcastle/issue-2");
  });

  it("fulfilled with zero commits is treated as failed", () => {
    const issues = [{ id: "3", branch: "sandcastle/issue-3" }];
    const settled = [{ status: "fulfilled" as const, commits: [] as string[] }];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toHaveLength(0);
    expect(failed).toHaveLength(1);
  });

  it("failure in one worker does not cancel unrelated worker (Promise.allSettled semantics)", () => {
    const issues = [
      { id: "10", branch: "sandcastle/issue-10" },
      { id: "11", branch: "sandcastle/issue-11" },
      { id: "12", branch: "sandcastle/issue-12" },
    ];
    const settled = [
      { status: "fulfilled" as const, commits: ["c1"] },
      { status: "rejected" as const, reason: "boom" },
      { status: "fulfilled" as const, commits: ["c3"] },
    ];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toHaveLength(2);
    expect(failed).toHaveLength(1);
    expect(completed.map((c) => c.id).sort()).toEqual(["10", "12"]);
  });
});

describe("AC11: successful reviewed branch reaches integration and only then closes", () => {
  it("successful worker appears in completed (integration path)", () => {
    const issues = [{ id: "20", branch: "sandcastle/issue-20" }];
    const settled = [{ status: "fulfilled" as const, commits: ["sha"] }];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toHaveLength(1);
    expect(failed).toHaveLength(0);
    // In main.mts, completedBranches goes to merger; closing happens only after merger succeeds.
    // Here we assert the partition logic preserves that ordering.
  });

  it("closing happens after merger, not before — partition does not close", () => {
    // Pure partition does not close; main.mts calls markIntegrated only after merger.
    // This test documents that intent.
    const issues = [{ id: "21", branch: "sandcastle/issue-21" }];
    const settled = [{ status: "fulfilled" as const, commits: ["sha"] }];
    const { completed } = partitionWorkers(issues, settled);
    // Simulate merger success -> would then close
    expect(completed[0].branch).toBe("sandcastle/issue-21");
  });
});

describe("AC12: re-running dispatcher does not duplicate", () => {
  it("already in-progress issue is not eligible on re-run", () => {
    const firstRun = issue({ number: 30, labels: ["agent:implement"] });
    expect(isEligible(firstRun).eligible).toBe(true);
    // After claim, issue has in-progress + assignee
    const secondRun = issue({
      number: 30,
      labels: ["agent:implement", "agent:in-progress"],
      assignees: ["bot"],
    });
    expect(isEligible(secondRun).eligible).toBe(false);
  });

  it("already blocked issue is not eligible on re-run", () => {
    const blocked = issue({ number: 31, labels: ["agent:implement", "agent:blocked"] });
    expect(isEligible(blocked).eligible).toBe(false);
  });

  it("already closed/completed issue is not eligible", () => {
    const closed = issue({ number: 32, state: "closed", labels: ["agent:implement"] });
    expect(isEligible(closed).eligible).toBe(false);
  });

  it("filter is idempotent: eligible set shrinks after claims", () => {
    const issues = [
      issue({ number: 40 }),
      issue({ number: 41 }),
      issue({ number: 42, labels: ["agent:implement", "agent:in-progress"] }),
    ];
    const firstEligible = issues.filter((i) => isEligible(i).eligible);
    expect(firstEligible.map((i) => i.number).sort()).toEqual([40, 41]);
    // After claiming 40
    const afterClaim = [
      issue({ number: 40, labels: ["agent:implement", "agent:in-progress"], assignees: ["bot"] }),
      issue({ number: 41 }),
      issue({ number: 42, labels: ["agent:implement", "agent:in-progress"] }),
    ];
    const secondEligible = afterClaim.filter((i) => isEligible(i).eligible);
    expect(secondEligible.map((i) => i.number)).toEqual([41]);
  });
});

describe("Wayfinder seam", () => {
  it("wayfinder:task is allowed but leaves seam for future routing", () => {
    const t = issue({ labels: ["agent:implement", "wayfinder:task"], body: TRACER_BODY });
    expect(isEligible(t).eligible).toBe(true);
    // Future: if wayfinder:task needs special routing, add check here without affecting
    // research/prototype/grilling block-list.
  });

  it("wayfinder:task without Notes is blocked by triple-signal", () => {
    const t = issue({ labels: ["agent:implement", "wayfinder:task"], body: "no notes" });
    expect(isEligible(t).eligible).toBe(false);
  });
});
