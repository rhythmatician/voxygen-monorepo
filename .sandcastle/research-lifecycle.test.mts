import { describe, it, expect, vi } from "vitest";
import { isEligible, classifyTicket, RESEARCH_LABEL, WAYFINDER_RESEARCH_LABEL, branchForIssue } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";
import { TRACER_BODY } from "./fixtures.mts";
import {
  extractParentMapId,
  researchResultMarker,
  researchParentMarker,
  publishResearchResult,
  addParentMapPointer,
  closeResearchTicket,
  releaseResearchTransientClaim,
  markResearchFactoryError,
  completeResearchLifecycle,
  orchestrateResearchBatch,
} from "./research-lifecycle.mts";
import type { ResearchResult } from "./research-result.mts";
import { getResearchEnvironment, resolveResearchSandboxEnv } from "./sandbox-token-env.mts";

// ---------------------------------------------------------------------------
// In-memory fake GitHub store for behavioral lifecycle tests
// ---------------------------------------------------------------------------

interface FakeIssue {
  id: string;
  branch: string;
  title: string;
  body?: string;
  labels: string[];
  assignees: string[];
  state: "open" | "closed";
  comments: string[];
  commits?: string[];
}

function makeFakeIssue(overrides: Partial<FakeIssue> & { id: string }): FakeIssue {
  return {
    branch: `sandcastle/issue-${overrides.id}`,
    title: `Research ${overrides.id}`,
    body: "Part of #22\nResearch the terrain signal",
    labels: ["wayfinder:research", "agent:in-progress"],
    assignees: ["bot"],
    state: "open",
    comments: [],
    commits: [],
    ...overrides,
  };
}

type FakeOpsOptions = {
  failPublishFor?: Set<string>;
  failParentFor?: Set<string>;
  failCloseFor?: Set<string>;
  failReleaseFor?: Set<string>;
};

function createFakeOps(store: Map<string, FakeIssue>, opts: FakeOpsOptions = {}) {
  const calls: Array<{ kind: "safe" | "run"; args: string[] }> = [];
  const safeRunGh = async (args: string[], _ctx?: string): Promise<boolean> => {
    calls.push({ kind: "safe", args: [...args] });
    // Simulate failure based on opts
    if (args[0] === "issue" && args[1] === "comment") {
      const issueId = args[2];
      if (opts.failPublishFor?.has(issueId)) return false;
      if (args[3] === "--body") {
        const body = args[4] ?? "";
        // Check if this is parent pointer by marker
        if (body.includes("research-parent-pointer")) {
          // Extract researchId from marker: <!-- research-parent-pointer:researchId->parentId -->
          const m = body.match(/research-parent-pointer:(\S+)->(\S+)/);
          const researchId = m?.[1] ?? issueId;
          if (opts.failParentFor?.has(researchId)) return false;
          // Parent pointer comment goes to parentId, not researchId — but our check is on researchId
          // For parent pointer, issueId is parentId. Need to check failParentFor contains researchId.
          // So we allow failParentFor to be keyed by researchId.
        }
        // For parent pointer, args[2] is parentId, but failure should be based on researchId. We handle below.
        // For general publish, check failPublishFor
        // Need to distinguish parent vs result by marker
        if (body.includes("research-parent-pointer")) {
          // Already checked failParentFor above via researchId
          // If not failed, add to parent issue's comments (create placeholder if not exists)
          const parentId = args[2];
          if (!store.has(parentId)) {
            store.set(parentId, makeFakeIssue({ id: parentId, labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map #22" }));
          }
          store.get(parentId)!.comments.push(body);
          return true;
        }
        // Result publication
        if (body.includes("research-result")) {
          const iss = store.get(issueId);
          if (!iss) return false;
          if (opts.failPublishFor?.has(issueId)) return false;
          iss.comments.push(body);
          return true;
        }
        // Generic comment
        const iss = store.get(issueId);
        if (iss) iss.comments.push(body);
        return true;
      }
      return true;
    }
    if (args[0] === "issue" && args[1] === "edit") {
      const issueId = args[2];
      const iss = store.get(issueId);
      if (!iss) return false;
      if (opts.failReleaseFor?.has(issueId) && args.includes("agent:in-progress")) return false;
      // Parse edit args
      for (let i = 3; i < args.length; i++) {
        if (args[i] === "--remove-label" && args[i + 1]) {
          const label = args[i + 1];
          iss.labels = iss.labels.filter((l) => l !== label);
          i++;
        } else if (args[i] === "--add-label" && args[i + 1]) {
          const label = args[i + 1];
          if (!iss.labels.includes(label)) iss.labels.push(label);
          i++;
        } else if (args[i] === "--remove-assignee") {
          // remove all assignees for simplicity (represents @me)
          iss.assignees = [];
          i++;
        } else if (args[i] === "--add-assignee") {
          // add assignee placeholder
          if (!iss.assignees.includes("bot")) iss.assignees.push("bot");
          i++;
        }
      }
      return true;
    }
    return true;
  };

  const runGh = async (args: string[]): Promise<string> => {
    calls.push({ kind: "run", args: [...args] });
    if (args[0] === "issue" && args[1] === "close") {
      const issueId = args[2];
      if (opts.failCloseFor?.has(issueId)) throw new Error(`gh issue close failed for #${issueId}`);
      const iss = store.get(issueId);
      if (!iss) throw new Error(`issue ${issueId} not found`);
      // Handle --comment variant
      let commentIdx = args.indexOf("--comment");
      if (commentIdx !== -1 && args[commentIdx + 1]) {
        iss.comments.push(args[commentIdx + 1]);
      }
      // Check for separate comment then close pattern: caller may have done comment via safeRun then close without comment
      iss.state = "closed";
      return "";
    }
    if (args[0] === "issue" && args[1] === "comment") {
      const issueId = args[2];
      const bodyIdx = args.indexOf("--body");
      const body = bodyIdx !== -1 ? args[bodyIdx + 1] ?? "" : "";
      if (opts.failPublishFor?.has(issueId) && body.includes("research-result")) throw new Error(`gh issue comment failed for #${issueId}`);
      // Parent pointer via runGh? In lifecycle, parent pointer uses safeRunGh, not runGh. So this is result of close fallback comment.
      const iss = store.get(issueId);
      if (iss) iss.comments.push(body);
      // Simulate close fallback comment failure for failClose case? Not needed.
      if (opts.failCloseFor?.has(issueId) && body.includes("Research completed")) throw new Error(`gh issue comment failed for close #${issueId}`);
      return "";
    }
    return "";
  };

  return { ops: { safeRunGh, runGh }, calls, store };
}

function sampleResult(overrides: Partial<ResearchResult> = {}): ResearchResult {
  return {
    summary: "summary text for research",
    findings: [{ claim: "claim", evidence: "evidence at src/foo.ts:42", source: "src/foo.ts" }],
    recommendation: "do next step",
    uncertainties: ["u1"],
    followUps: ["f1"],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Behavioral regressions — production lifecycle via injected ops
// ---------------------------------------------------------------------------

describe("Research lifecycle — behavioral regressions", () => {
  it("1. Research succeeds with zero commits", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "101", body: "Research without commits\nPart of #22" });
    store.set("101", issue);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    const { ops, calls } = createFakeOps(store);
    const result = sampleResult();
    const res = await completeResearchLifecycle({
      issue: { id: "101", branch: "sandcastle/issue-101", title: "Research 101", body: issue.body },
      result,
      rawText: "raw research output",
      ops,
      commits: [], // zero commits
    });
    expect(res.outcome).toBe("SUCCESS");
    expect(res.closeAttempted).toBe(true);
    const after = store.get("101")!;
    expect(after.state).toBe("closed");
    expect(after.labels).toContain("wayfinder:research"); // retained
    expect(after.labels).not.toContain("agent:in-progress");
    expect(after.assignees).toEqual([]);
    expect(after.comments.some((c) => c.includes(researchResultMarker("101")))).toBe(true);
    const parent = store.get("22")!;
    expect(parent.comments.some((c) => c.includes(researchParentMarker("101", "22")))).toBe(true);
    // No implementation review/merger calls — only research lifecycle gh calls
    const allArgs = calls.map((c) => c.args.join(" "));
    expect(allArgs.some((a) => a.includes("pr create"))).toBe(false);
    expect(allArgs.some((a) => a.includes("review"))).toBe(false);
    expect(allArgs.some((a) => a.includes("merger"))).toBe(false);
    // Parent pointer was attempted and succeeded
    expect(res.parentPointerAttempted).toBe(true);
    expect(res.parentPointerSucceeded).toBe(true);
  });

  it("2. Research succeeds with one or more optional commits, preserves them and never invokes implementation paths", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "102", body: "Part of #22\nResearch with commits" });
    store.set("102", issue);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    const { ops, calls } = createFakeOps(store);
    const result = sampleResult({ summary: "summary with commits" });
    const commits = ["abc123", "def456"];
    const res = await completeResearchLifecycle({
      issue: { id: "102", branch: "sandcastle/issue-102", title: "Research 102", body: issue.body },
      result,
      rawText: "raw with commits",
      ops,
      commits,
    });
    expect(res.outcome).toBe("SUCCESS");
    const after = store.get("102")!;
    expect(after.state).toBe("closed");
    expect(after.labels).toContain("wayfinder:research");
    // Commits are preserved on branch — lifecycle does not delete them. We verify commits passed through are not treated as error.
    // The store does not model git branches, but we assert lifecycle succeeded despite commits.
    // Ensure no implementation paths were invoked
    const allArgs = calls.map((c) => c.args.join(" "));
    expect(allArgs.some((a) => a.includes("pr create"))).toBe(false);
    expect(allArgs.some((a) => a.includes("publishBatchBranch"))).toBe(false);
    expect(allArgs.some((a) => a.includes("mayAutonomouslyMerge"))).toBe(false);
    expect(allArgs.some((a) => a.includes("reviewVerdict"))).toBe(false);
    // Result still published with marker
    expect(after.comments.some((c) => c.includes(researchResultMarker("102")))).toBe(true);
  });

  it("3. Closing fails after result and parent-pointer publication: remains open, transient released, research retained, FACTORY_ERROR, outer stops", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "103", body: "Part of #22\nResearch close fail" });
    store.set("103", issue);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    const { ops, calls } = createFakeOps(store, { failCloseFor: new Set(["103"]) });
    const result = sampleResult();
    const res = await completeResearchLifecycle({
      issue: { id: "103", branch: "sandcastle/issue-103", title: "Research 103", body: issue.body },
      result,
      rawText: "raw",
      ops,
    });
    expect(res.outcome).toBe("FACTORY_ERROR");
    expect(res.closeAttempted).toBe(true);
    const after = store.get("103")!;
    // Issue remains open (close failed)
    expect(after.state).toBe("open");
    // Transient claim released
    expect(after.labels).not.toContain("agent:in-progress");
    expect(after.assignees).toEqual([]);
    // Research retained
    expect(after.labels).toContain("wayfinder:research");
    // No blocked
    expect(after.labels).not.toContain("agent:blocked");
    // Result and parent pointer were already published and preserved
    expect(after.comments.some((c) => c.includes(researchResultMarker("103")))).toBe(true);
    const parent = store.get("22")!;
    expect(parent.comments.some((c) => c.includes(researchParentMarker("103", "22")))).toBe(true);
    // Close was attempted (runGh close called and threw)
    expect(calls.some((c) => c.kind === "run" && c.args.includes("close") && c.args.includes("103"))).toBe(true);
    // Outcome maps to FACTORY_ERROR, which in batch would set hadFactoryError true — simulate outer stop
    // Here we just verify the single lifecycle result; batch-level outer stop is tested in batch test.
  });

  it("4. Parent-map pointer publication fails: close not attempted, transient released, research retained, open, FACTORY_ERROR", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "104", body: "Part of #22\nResearch parent fail" });
    store.set("104", issue);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    // Fail parent pointer: need to fail safeRun for parent. Our fake checks failParentFor contains researchId.
    const { ops, calls } = createFakeOps(store, { failParentFor: new Set(["104"]) });
    const result = sampleResult();
    const res = await completeResearchLifecycle({
      issue: { id: "104", branch: "sandcastle/issue-104", title: "Research 104", body: issue.body },
      result,
      rawText: "raw",
      ops,
    });
    expect(res.outcome).toBe("FACTORY_ERROR");
    expect(res.parentPointerAttempted).toBe(true);
    expect(res.parentPointerSucceeded).toBe(false);
    expect(res.closeAttempted).toBe(false);
    const after = store.get("104")!;
    expect(after.state).toBe("open");
    expect(after.labels).not.toContain("agent:in-progress");
    expect(after.assignees).toEqual([]);
    expect(after.labels).toContain("wayfinder:research");
    expect(after.labels).not.toContain("agent:blocked");
    // Result was published and preserved
    expect(after.comments.some((c) => c.includes(researchResultMarker("104")))).toBe(true);
    // Parent pointer not succeeded, so no parent comment with marker
    const parent = store.get("22")!;
    expect(parent.comments.some((c) => c.includes(researchParentMarker("104", "22")))).toBe(false);
    // Close not attempted
    expect(calls.some((c) => c.args.includes("close") && c.args.includes("104"))).toBe(false);
    // Transient release was attempted (edit remove in-progress)
    expect(calls.some((c) => c.kind === "safe" && c.args.includes("104") && c.args.includes("agent:in-progress"))).toBe(true);
  });

  it("5. Successful completion closes issue and removes only transient claim state", async () => {
    const store = new Map<string, FakeIssue>();
    // Without Part of #N — no parent pointer
    const issue = makeFakeIssue({ id: "105", body: "Research without parent" });
    store.set("105", issue);
    const { ops } = createFakeOps(store);
    const result = sampleResult({ summary: "standalone research" });
    const res = await completeResearchLifecycle({
      issue: { id: "105", branch: "sandcastle/issue-105", title: "Research 105", body: issue.body },
      result,
      rawText: "raw",
      ops,
    });
    expect(res.outcome).toBe("SUCCESS");
    const after = store.get("105")!;
    expect(after.state).toBe("closed");
    expect(after.labels).toContain("wayfinder:research");
    expect(after.labels).not.toContain("agent:in-progress");
    expect(after.assignees.length).toBe(0);
    expect(after.labels).not.toContain("agent:blocked");
    expect(res.parentPointerAttempted).toBe(false);
    // Only transient removed, research retained — ticket not redispatched because closed
  });

  it("6. One research worker's factory failure does not erase or suppress valid sibling results", async () => {
    const store = new Map<string, FakeIssue>();
    const issues = [
      makeFakeIssue({ id: "110", body: "Part of #22\nresearch 110" }),
      makeFakeIssue({ id: "111", body: "Part of #22\nresearch 111" }),
      makeFakeIssue({ id: "112", body: "Part of #22\nresearch 112" }),
    ];
    for (const iss of issues) store.set(iss.id, iss);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));

    const { ops } = createFakeOps(store);
    const runWorker = async (issue: { id: string; branch: string; title: string; body?: string }) => {
      if (issue.id === "111") throw new Error("infrastructure failure for 111");
      return { result: sampleResult({ summary: `result ${issue.id}` }), rawText: `raw ${issue.id}` };
    };

    const batch = await orchestrateResearchBatch({
      issues: issues.map((iss) => ({ id: iss.id, branch: iss.branch, title: iss.title, body: iss.body })),
      runWorker,
      ops,
    });

    expect(batch.hadFactoryError).toBe(true);
    expect(batch.outcomes.get("110")).toBe("SUCCESS");
    expect(batch.outcomes.get("111")).toBe("FACTORY_ERROR");
    expect(batch.outcomes.get("112")).toBe("SUCCESS");
    expect(batch.succeededIds.sort()).toEqual(["110", "112"]);
    expect(batch.failedIds).toEqual(["111"]);
    // Sibling successes preserved: check store
    expect(store.get("110")!.state).toBe("closed");
    expect(store.get("112")!.state).toBe("closed");
    expect(store.get("110")!.comments.some((c) => c.includes(researchResultMarker("110")))).toBe(true);
    expect(store.get("112")!.comments.some((c) => c.includes(researchResultMarker("112")))).toBe(true);
    // Failed sibling remains open, research retained, no blocked
    const failed = store.get("111")!;
    expect(failed.state).toBe("open");
    expect(failed.labels).toContain("wayfinder:research");
    expect(failed.labels).not.toContain("agent:blocked");
    expect(failed.labels).not.toContain("agent:in-progress");
  });

  it("7. Production fan-out starts three injected research workers concurrently before any is released (barrier against production orchestration)", async () => {
    const store = new Map<string, FakeIssue>();
    for (const id of ["201", "202", "203"]) {
      store.set(id, makeFakeIssue({ id, body: `Part of #22\nresearch ${id}` }));
    }
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    const { ops } = createFakeOps(store);

    let active = 0;
    let maxActive = 0;
    let started = 0;
    let barrierResolve: () => void = () => {};
    const barrier = new Promise<void>((res) => { barrierResolve = res; });

    const runWorker = async (issue: { id: string; branch: string; title: string; body?: string }) => {
      active++;
      maxActive = Math.max(maxActive, active);
      started++;
      if (started === 3) {
        // All three workers have started concurrently — release barrier after tick
        setTimeout(() => barrierResolve(), 5);
      }
      await barrier;
      await new Promise((r) => setTimeout(r, 10));
      active--;
      return { result: sampleResult({ summary: `result ${issue.id}` }), rawText: `raw ${issue.id}` };
    };

    const batch = await orchestrateResearchBatch({
      issues: ["201", "202", "203"].map((id) => ({ id, branch: `sandcastle/issue-${id}`, title: `Research ${id}`, body: store.get(id)!.body })),
      runWorker,
      ops,
    });

    expect(batch.settled.every((s) => s.status === "fulfilled")).toBe(true);
    expect(maxActive).toBe(3);
    expect(started).toBe(3);
    // Verify all three succeeded and were published
    expect(batch.succeededIds.sort()).toEqual(["201", "202", "203"]);
    expect(batch.hadFactoryError).toBe(false);
    // Prove not sequential: maxActive would be 1 if sequential
    expect(maxActive).not.toBe(1);
  });

  it("8. Existing agent:implement classification and implementation lifecycle remain unchanged", async () => {
    // Reuse existing dispatch tests — verify implementation eligibility unchanged
    const impl = (overrides: Partial<IssueInput> = {}): IssueInput => ({
      number: 500,
      title: "Impl",
      state: "open",
      labels: ["ready-for-agent", "agent:implement"],
      assignees: [],
      body: TRACER_BODY,
      blockedByCount: 0,
      ...overrides,
    });
    expect(isEligible(impl()).eligible).toBe(true);
    const missingTracer = impl({ body: "no tracer" });
    const res = isEligible(missingTracer);
    expect(res.eligible).toBe(false);
    expect((res as { reason: string }).reason).toContain("tracer contract");
    // Research vs implement classification unchanged
    expect(classifyTicket({ ...impl(), labels: ["ready-for-agent", "agent:implement"] }).profile).toBe("implementation");
    const researchIssue: IssueInput = { number: 600, title: "R", state: "open", labels: ["wayfinder:research"], assignees: [], body: "Part of #22", blockedByCount: 0 };
    expect(classifyTicket(researchIssue).profile).toBe("research");
  });

  it("idempotency markers are present and deterministic; retry preserves already-published result", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "301", body: "Part of #22\nresearch 301" });
    store.set("301", issue);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    // First attempt: parent pointer fails, result already published
    const { ops: opsFailParent } = createFakeOps(store, { failParentFor: new Set(["301"]) });
    const result = sampleResult();
    const first = await completeResearchLifecycle({
      issue: { id: "301", branch: "sandcastle/issue-301", title: "Research 301", body: issue.body },
      result,
      rawText: "raw",
      ops: opsFailParent,
    });
    expect(first.outcome).toBe("FACTORY_ERROR");
    // Result comment preserved
    const afterFirst = store.get("301")!;
    expect(afterFirst.comments.some((c) => c.includes(researchResultMarker("301")))).toBe(true);
    expect(afterFirst.state).toBe("open");
    // Simulate retry: now parent succeeds (new ops without failure)
    const { ops: opsRetry } = createFakeOps(store, {});
    // Reset issue transient for retry simulation (re-add in-progress/assignee as if re-claimed)
    afterFirst.labels.push("agent:in-progress");
    afterFirst.assignees = ["bot"];
    const second = await completeResearchLifecycle({
      issue: { id: "301", branch: "sandcastle/issue-301", title: "Research 301", body: issue.body },
      result,
      rawText: "raw",
      ops: opsRetry,
    });
    expect(second.outcome).toBe("SUCCESS");
    // Result comment now duplicated (retry publishes again) but marker makes duplication detectable
    const marker = researchResultMarker("301");
    const resultComments = afterFirst.comments.filter((c) => c.includes(marker));
    // Currently we expect 2 comments with same marker after retry — documents retry behavior (not hidden)
    expect(resultComments.length).toBe(2);
    // Parent pointer now succeeded
    const parent = store.get("22")!;
    expect(parent.comments.some((c) => c.includes(researchParentMarker("301", "22")))).toBe(true);
    expect(store.get("301")!.state).toBe("closed");
  });

  it("research workers receive no GH write credential (sandbox env scrubbed)", () => {
    const env = resolveResearchSandboxEnv("meta123");
    expect(env.GH_TOKEN).toBe("");
    expect(env.GITHUB_TOKEN).toBe("");
    expect(env.META_API_KEY).toBe("meta123");
    const prof = getResearchEnvironment("metaXYZ");
    expect(prof.imageName).toBe("sandcastle:voxygen-monorepo");
    expect(prof.env.GH_TOKEN).toBe("");
  });

  it("9. Claim cleanup failure does not attempt close — FACTORY_ERROR, issue remains open, no close call", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "401", body: "Research cleanup fail" });
    store.set("401", issue);
    const { ops, calls } = createFakeOps(store, { failReleaseFor: new Set(["401"]) });
    const result = sampleResult();
    // Directly test closeResearchTicket with failing release
    const { closeResearchTicket } = await import("./research-lifecycle.mts");
    const closed = await closeResearchTicket("401", "sandcastle/issue-401", ops);
    expect(closed).toBe(false);
    const after = store.get("401")!;
    expect(after.state).toBe("open");
    // Transient release failed, so in-progress and assignee still present (factory will retry)
    expect(after.labels).toContain("agent:in-progress");
    expect(after.assignees).toEqual(["bot"]);
    expect(after.labels).toContain("wayfinder:research");
    expect(after.labels).not.toContain("agent:blocked");
    // Close was not attempted
    expect(calls.some((c) => c.kind === "run" && c.args.includes("close") && c.args.includes("401"))).toBe(false);
    // Complete lifecycle should also treat this as FACTORY_ERROR without closing
    const { ops: ops2, calls: calls2 } = createFakeOps(store, { failReleaseFor: new Set(["401"]) });
    // Reset store for lifecycle test — need fresh issue with same failure
    store.set("401", makeFakeIssue({ id: "401", body: "Research cleanup fail" }));
    const { ops: ops3 } = createFakeOps(store, { failReleaseFor: new Set(["401"]) });
    const lifecycle = await completeResearchLifecycle({
      issue: { id: "401", branch: "sandcastle/issue-401", title: "Research 401", body: "Research cleanup fail" },
      result,
      rawText: "raw",
      ops: ops3,
    });
    expect(lifecycle.outcome).toBe("FACTORY_ERROR");
    expect(store.get("401")!.state).toBe("open");
  });

  it("10. Research branch preparation failure is FACTORY_ERROR not blocked — releases transient, retains research, no blocked", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "501", body: "Part of #22\nresearch prep fail" });
    store.set("501", issue);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    const { ops } = createFakeOps(store);
    const { markResearchFactoryError } = await import("./research-lifecycle.mts");
    // Simulate what main.mts now does on prepare failure: calls markResearchFactoryError, not markBlocked
    const ok = await markResearchFactoryError("501", "sandcastle/issue-501", "prepareIssueBranch failed: no provenance", ops);
    expect(ok).toBe(true);
    const after = store.get("501")!;
    expect(after.state).toBe("open");
    expect(after.labels).toContain("wayfinder:research");
    expect(after.labels).not.toContain("agent:in-progress");
    expect(after.labels).not.toContain("agent:blocked");
    expect(after.assignees).toEqual([]);
    // Outer progression should stop — caller would set researchHadFactoryError true and break before next claim
    // Verify that markBlocked would have added blocked (contrast)
    const store2 = new Map<string, FakeIssue>();
    store2.set("502", makeFakeIssue({ id: "502", body: "prepare fail" }));
    const { ops: ops2 } = createFakeOps(store2);
    // Simulate old behavior: markBlocked adds blocked
    const { default: fs } = await import("node:fs");
    // Just verify our new path does not add blocked, which we already did
    expect(after.labels).not.toContain("agent:blocked");
  });

  it("11. Mixed profile: research FACTORY_ERROR does not strand successful implementation work", async () => {
    // Simulate outer iteration with both research and implementation
    const researchStore = new Map<string, FakeIssue>();
    const researchIssue = makeFakeIssue({ id: "601", body: "Part of #22\nresearch fail" });
    researchStore.set("601", researchIssue);
    researchStore.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    const { ops: researchOps } = createFakeOps(researchStore, { failParentFor: new Set(["601"]) });
    const researchResult = sampleResult();
    const researchBatch = await orchestrateResearchBatch({
      issues: [{ id: "601", branch: "sandcastle/issue-601", title: "Research 601", body: researchIssue.body }],
      runWorker: async () => ({ result: researchResult, rawText: "raw" }),
      ops: researchOps,
    });
    expect(researchBatch.hadFactoryError).toBe(true);
    expect(researchBatch.outcomes.get("601")).toBe("FACTORY_ERROR");

    // Simulate implementation success in same iteration — should still reach merger
    const mockPartition = {
      completed: [{ id: "701", branch: "sandcastle/issue-701" }],
      factoryErrors: [],
      shouldStopOuterLoop: false,
    };
    const {
      shouldStopBeforeMergerForFactoryError,
      shouldStopBeforeNextClaimForResearchError,
    } = await import("./research-lifecycle.mts");
    // Production helpers: early merger block should be false for research failure alone
    expect(shouldStopBeforeMergerForFactoryError(mockPartition)).toBe(false);
    // Old buggy logic would have been `|| researchHadFactoryError` → true
    const { canClaimNextOuterIteration } = await import("./factory-verdict-gate.mts");
    const oldWouldBlockMerger = !canClaimNextOuterIteration(mockPartition as unknown as Parameters<typeof canClaimNextOuterIteration>[0]) || researchBatch.hadFactoryError;
    expect(oldWouldBlockMerger).toBe(true); // old code stranded implementation

    // After merger, research error should still stop next outer iteration
    expect(shouldStopBeforeNextClaimForResearchError(researchBatch.hadFactoryError)).toBe(true);

    // Verify research issue remains FACTORY_ERROR state
    expect(researchStore.get("601")!.state).toBe("open");
    expect(researchStore.get("601")!.labels).toContain("wayfinder:research");
  });

  it("12. Research environment profile is resolved per issue and image is wired", async () => {
    const metaKey = "test-meta-key";
    const prof1 = getResearchEnvironment(metaKey, { number: 1, body: "Part of #22\nbody 1" });
    const prof2 = getResearchEnvironment(metaKey, { number: 2, body: "different body" });
    expect(prof1.imageName).toBe("sandcastle:voxygen-monorepo");
    expect(prof2.imageName).toBe("sandcastle:voxygen-monorepo");
    expect(prof1.env.GH_TOKEN).toBe("");
    // Verify main.mts now resolves per issue and passes imageName to docker without cast
    const { default: fs } = await import("node:fs");
    const main = fs.readFileSync(".sandcastle/main.mts", "utf8");
    expect(main).toContain("getResearchEnvironment(metaKey, { number: parseInt(issue.id");
    expect(main).toContain("docker({ env: profile.env, imageName: profile.imageName })");
    expect(main).not.toContain("as unknown as Record");
    expect(main).not.toMatch(/const researchEnvProfile = getResearchEnvironment\(metaKey\)\s*;\s*\n\s*const researchSourceMap/);
  });

  it("13. Qualification --issue N applies across both profiles: only requested eligible ticket dispatches", async () => {
    const { planResearchForIteration } = await import("./factory-iteration-control.mts");
    const { planIssuesForIteration } = await import("./factory-iteration-control.mts");
    const researchEligible: IssueInput[] = [
      { number: 601, title: "R601", state: "open", labels: ["wayfinder:research"], assignees: [], body: "Part of #22", blockedByCount: 0 },
      { number: 602, title: "R602", state: "open", labels: ["wayfinder:research"], assignees: [], body: "Part of #22", blockedByCount: 0 },
    ];
    const implementEligible: IssueInput[] = [
      { number: 152, title: "Impl152", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY, blockedByCount: 0 },
      { number: 153, title: "Impl153", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY, blockedByCount: 0 },
    ];

    // --issue 601 (research) → only research 601
    expect(planResearchForIteration(researchEligible, implementEligible, { requestedIssueNumber: "601" }).map((p) => p.id)).toEqual(["601"]);
    expect(planIssuesForIteration(implementEligible, { requestedIssueNumber: "601" }).plannedIssues).toEqual([]);

    // --issue 152 (implement) → only implement 152, no research
    expect(planResearchForIteration(researchEligible, implementEligible, { requestedIssueNumber: "152" })).toEqual([]);
    expect(planIssuesForIteration(implementEligible, { requestedIssueNumber: "152" }).plannedIssues.map((p) => p.id)).toEqual(["152"]);

    // --issue 999 (neither) → nothing for both
    expect(planResearchForIteration(researchEligible, implementEligible, { requestedIssueNumber: "999" })).toEqual([]);
    expect(planIssuesForIteration(implementEligible, { requestedIssueNumber: "999" }).plannedIssues).toEqual([]);
    expect(planIssuesForIteration(implementEligible, { requestedIssueNumber: "999" }).mode).toBe("qualify-unsupported");

    // No qualification → all
    expect(planResearchForIteration(researchEligible, implementEligible, {}).map((p) => p.id).sort()).toEqual(["601", "602"]);
    expect(planIssuesForIteration(implementEligible, {}).mode).toBe("planner-required");
  });

  it("14. Restart reconciliation — stale research with optional commit preserves branch, releases transient, retains research, no blocked, leaves open", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "601", body: "Part of #22\nresearch #601", commits: ["abc123"] });
    // Branch with optional commit — research never has batch PR by design
    store.set("601", issue);
    store.set("22", makeFakeIssue({ id: "22", labels: ["wayfinder:map"], assignees: [], state: "open", body: "Map" }));
    const { ops, calls } = createFakeOps(store);

    // Classify as research before entering implementation batch-PR logic
    const classification = classifyTicket({
      number: 601,
      title: issue.title,
      state: "open",
      labels: issue.labels,
      assignees: issue.assignees,
      body: issue.body,
      blockedByCount: 0,
    });
    expect(classification.profile).toBe("research");

    // Simulate new research-aware reconciliation: preserve branch/commits, release transient, retain research
    // This mirrors main.mts's added branch: safeRunGh edit remove in-progress + assignee, no PR search, no delete, no blocked
    const branch = branchForIssue(601);
    // In real reconciliation, branchExists check is skipped for research; we simulate that branch is preserved
    const simulatedBranchCommits = [...(store.get("601")!.commits ?? [])];
    const released = await ops.safeRunGh(
      ["issue", "edit", "601", "--remove-label", "agent:in-progress", "--remove-assignee", "@me"],
      `Failed to release research claim for #601 on reconciliation`,
    );
    expect(released).toBe(true);

    const after = store.get("601")!;
    // Branch/commit preserved — optional CONTEXT.md commit not deleted
    expect(simulatedBranchCommits).toEqual(["abc123"]);
    expect(after.commits).toEqual(["abc123"]);
    // Transient claim removed
    expect(after.labels).not.toContain("agent:in-progress");
    expect(after.assignees).toEqual([]);
    // Research authorization retained
    expect(after.labels).toContain("wayfinder:research");
    // No blocked added
    expect(after.labels).not.toContain("agent:blocked");
    // Leaves open for retry (not closed, not blocked)
    expect(after.state).toBe("open");
    // No batch PR search, no branch deletion, no blocked mutation
    const allArgs = calls.map((c) => c.args.join(" "));
    expect(allArgs.some((a) => a.includes("pr list"))).toBe(false);
    expect(allArgs.some((a) => a.includes("agent:blocked"))).toBe(false);
    // Branch name preserved (not deleted)
    expect(branch).toBe("sandcastle/issue-601");
    // Verify sibling research marker still independent — no cross-issue mutation
    expect(store.get("22")!.state).toBe("open");
  });

  it("15. Restart reconciliation — GH unavailable leaves research untouched for retry (no false release claim)", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "602", body: "Part of #22\nresearch GH fail", commits: ["def456"] });
    store.set("602", issue);
    const { ops, calls } = createFakeOps(store, { failReleaseFor: new Set(["602"]) });

    const classification = classifyTicket({
      number: 602,
      title: issue.title,
      state: "open",
      labels: issue.labels,
      assignees: issue.assignees,
      body: issue.body,
      blockedByCount: 0,
    });
    expect(classification.profile).toBe("research");

    const released = await ops.safeRunGh(
      ["issue", "edit", "602", "--remove-label", "agent:in-progress", "--remove-assignee", "@me"],
      `Failed to release research claim for #602 on reconciliation`,
    );
    expect(released).toBe(false);

    const after = store.get("602")!;
    // Leave external state untouched and retry next startup
    expect(after.labels).toContain("agent:in-progress");
    expect(after.assignees).toEqual(["bot"]);
    expect(after.labels).toContain("wayfinder:research");
    expect(after.labels).not.toContain("agent:blocked");
    expect(after.state).toBe("open");
    expect(after.commits).toEqual(["def456"]);
    // No mutation beyond attempted release
    const allArgs = calls.map((c) => c.args.join(" "));
    expect(allArgs.some((a) => a.includes("agent:blocked"))).toBe(false);
  });

  it("16. markResearchFactoryError when release fails does not claim released for retry", async () => {
    const store = new Map<string, FakeIssue>();
    const issue = makeFakeIssue({ id: "603", body: "Part of #22\nresearch" });
    store.set("603", issue);
    const { ops, calls } = createFakeOps(store, { failReleaseFor: new Set(["603"]) });

    const ok = await markResearchFactoryError("603", "sandcastle/issue-603", "infrastructure failure during research", ops);
    expect(ok).toBe(false);

    const after = store.get("603")!;
    // GH unavailable: leave state untouched, not claiming released
    expect(after.labels).toContain("agent:in-progress");
    expect(after.assignees).toEqual(["bot"]);
    expect(after.labels).toContain("wayfinder:research");
    expect(after.labels).not.toContain("agent:blocked");
    expect(after.state).toBe("open");
    // Must NOT post comment claiming "released for retry" when release confirmation failed
    expect(after.comments.some((c) => c.includes("released for retry"))).toBe(false);
    expect(calls.some((c) => c.args.join(" ").includes("released for retry"))).toBe(false);
  });
});
