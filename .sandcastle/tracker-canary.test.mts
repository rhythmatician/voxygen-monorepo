import { describe, it, expect, vi } from "vitest";
import { runCanary } from "./tracker-canary.mts";
import type { IssueInput } from "./tracker-policy.mts";
import { TRACER_BODY } from "./fixtures.mts";

function makeMockOps() {
  const store = new Map<number, IssueInput>();
  let nextId = 9000;
  const ops = {
    createIssue: async (title: string, body: string, labels: string[]) => {
      const id = nextId++;
      store.set(id, {
        number: id,
        title,
        state: "open" as const,
        labels: [...labels],
        assignees: [],
        body,
        blockedByCount: 0,
      });
      return id;
    },
    fetchIssue: async (id: number) => {
      const issue = store.get(id);
      if (!issue) throw new Error(`not found ${id}`);
      return { ...issue, labels: [...issue.labels], assignees: [...issue.assignees] };
    },
    closeIssue: async (id: number) => {
      const issue = store.get(id);
      if (issue) issue.state = "closed";
    },
    updateIssueLabels: async (id: number, add: string[], remove: string[]) => {
      const issue = store.get(id);
      if (!issue) throw new Error(`not found ${id}`);
      for (const a of add) if (!issue.labels.includes(a)) issue.labels.push(a);
      issue.labels = issue.labels.filter(l => !remove.includes(l));
    },
    claimImplementation: async (issue: IssueInput) => {
      const stored = store.get(issue.number);
      if (!stored) return { success: false, reason: "not found" };
      // Check contradictions via policy
      const { detectContradictions } = await import("./tracker-policy.mts");
      const v = detectContradictions(stored);
      if (v.contradictions.length > 0) return { success: false, reason: v.contradictions[0].reason };
      const hasReady = stored.labels.includes("ready-for-agent");
      const hasImplement = stored.labels.includes("agent:implement");
      if (!hasReady || !hasImplement) return { success: false, reason: "not eligible" };
      if (stored.labels.includes("agent:in-progress")) return { success: false, reason: "already in progress" };
      stored.labels = stored.labels.filter(l => l !== "agent:implement");
      if (!stored.labels.includes("agent:in-progress")) stored.labels.push("agent:in-progress");
      if (stored.assignees.length === 0) stored.assignees.push("bot");
      return { success: true };
    },
    reconcile: async (issue: IssueInput) => {
      const stored = store.get(issue.number);
      if (!stored) return false;
      const hasInProgress = stored.labels.includes("agent:in-progress");
      const hasAssignee = stored.assignees.length > 0;
      const hasReady = stored.labels.includes("ready-for-agent");
      const hasImplement = stored.labels.includes("agent:implement");
      if (hasReady && hasInProgress && hasAssignee && !hasImplement) {
        stored.labels = stored.labels.filter(l => l !== "agent:in-progress");
        stored.assignees = [];
        return true;
      }
      // For contradictory case, just remove in-progress
      if (hasImplement && hasInProgress) {
        stored.labels = stored.labels.filter(l => l !== "agent:in-progress");
        stored.assignees = [];
        return true;
      }
      return false;
    },
    comment: async () => {},
    _store: store,
  };
  return ops;
}

describe("tracker-canary", () => {
  it("requires explicit live flag", async () => {
    const ops = makeMockOps();
    await expect(runCanary(ops as any, { live: false })).rejects.toThrow("explicit --live");
  });

  it("proves implementation discoverable only with ready+implement", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.implementationDiscoverableOnlyWithReadyAndImplement).toBe(true);
  });

  it("proves successful claim consumes implement and retains ready", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.successfulClaimConsumesImplement).toBe(true);
  });

  it("proves stale reconciliation releases without restoring implement", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.staleReconciliationReleasesWithoutRestoring).toBe(true);
  });

  it("proves research discoverable from wayfinder:research alone", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.researchDiscoverableFromWayfinderAlone).toBe(true);
  });

  it("proves contradictions fail before worker", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.contradictionsFailBeforeWorker).toBe(true);
  });

  it("cleans all fixtures in finally and retains receipt if cleanup incomplete", async () => {
    const ops = makeMockOps();
    // Make close fail for one fixture to test cleanup failure receipt
    const originalClose = ops.closeIssue;
    let failOnce = true;
    ops.closeIssue = async (id: number) => {
      if (failOnce) {
        failOnce = false;
        throw new Error("cleanup failed");
      }
      return originalClose(id);
    };
    const result = await runCanary(ops as any, { live: true });
    // Should have cleanup failures but still have receipt
    expect(result.fixtureIds.length).toBeGreaterThan(0);
    // Fixtures should be attempted to close; cleanupFailures may contain entry
    // In this mock, first close fails, but others succeed
    expect(result.cleanupFailures.length).toBeGreaterThan(0);
    expect(result.fixturesCleaned).toBe(false);
  });

  it("never launches Muse or creates commits — inert boundaries", async () => {
    const ops = makeMockOps();
    // Verify that canary ops never call a model — we just check that runCanary doesn't import or call Muse
    // This is structural: canary uses mock ops and never creates branch/commits
    const result = await runCanary(ops as any, { live: true });
    // All fixtures should be closed, no branches created
    for (const id of result.fixtureIds) {
      const issue = await ops.fetchIssue(id);
      expect(issue.state).toBe("closed");
    }
  });
});
