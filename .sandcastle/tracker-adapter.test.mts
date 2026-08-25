import { describe, it, expect } from "vitest";
import { createTrackerAdapter, makeMemoryReceiptSink, type TrackerAdapter, type ReceiptSink } from "./tracker-adapter.mts";
import type { GhTransport } from "./gh-transport.mts";
import type { IssueInput } from "./tracker-policy.mts";

// ---------------------------------------------------------------------------
// Fake transport: in-memory issue store driven by gh command args. No spawns.
// ---------------------------------------------------------------------------

interface FakeIssue {
  number: number;
  title: string;
  body: string;
  state: "open" | "closed";
  labels: Set<string>;
  assignees: Set<string>;
}

function makeFakeGh(opts: {
  issues: Map<number, FakeIssue>;
  claimant?: string;
  failEditsWithLabel?: string; // edits touching this label throw
  failEditsAfterRelease?: boolean; // any edit after a successful release throws
}): GhTransport & { editCalls: string[][] } {
  const claimant = opts.claimant ?? "test-bot";
  const editCalls: string[][] = [];
  let released = false;
  const gh = {
    capabilityMode: "read-write" as const,
    isWriteForbidden: () => false,
    editCalls,
    async run(args: string[]): Promise<string> {
      if (args[0] === "api" && args[1] === "user") return claimant;
      if (args[0] === "issue" && args[1] === "view") {
        const issue = opts.issues.get(Number(args[2]));
        if (!issue) throw new Error(`not found #${args[2]}`);
        return JSON.stringify({
          number: issue.number,
          title: issue.title,
          body: issue.body,
          state: issue.state,
          labels: [...issue.labels].map((name) => ({ name })),
          assignees: [...issue.assignees].map((login) => ({ login })),
        });
      }
      if (args[0] === "issue" && args[1] === "edit") {
        editCalls.push([...args]);
        const id = Number(args[2]);
        const issue = opts.issues.get(id);
        if (!issue) throw new Error(`not found #${id}`);
        const flags = args.slice(3);
        // Simulate targeted failure
        if (opts.failEditsWithLabel && flags.some((f, i) => f === "--add-label" && flags[i + 1] === opts.failEditsWithLabel)) {
          throw new Error("simulated add-label failure");
        }
        if (opts.failEditsAfterRelease && released) {
          throw new Error("simulated post-release failure");
        }
        for (let i = 0; i < flags.length; i++) {
          if (flags[i] === "--add-label") issue.labels.add(flags[++i]);
          else if (flags[i] === "--remove-label") issue.labels.delete(flags[++i]);
          else if (flags[i] === "--add-assignee") issue.assignees.add(flags[++i] === "@me" ? claimant : flags[i]);
          else if (flags[i] === "--remove-assignee") issue.assignees.delete(flags[++i] === "@me" ? claimant : flags[i]);
        }
        if (flags.includes("--remove-label", 0) && flags.join(" ").includes("agent:in-progress")) released = true;
        return "";
      }
      if (args[0] === "issue" && args[1] === "close") {
        const issue = opts.issues.get(Number(args[2]));
        if (issue) issue.state = "closed";
        return "";
      }
      if (args[0] === "issue" && args[1] === "comment") return "";
      // blocked_by dependency lookup used by fetchFresh for eligibility
      if (args[0] === "api" && args[1].includes("issues/") && args.includes("--jq")) return "0";
      throw new Error(`unexpected gh args: ${args.join(" ")}`);
    },
    async tryRun(args: string[]): Promise<boolean> {
      try { await this.run(args); return true; } catch { return false; }
    },
    async resolveClaimantLogin(): Promise<string> { return claimant; },
    resolveOwnerRepo() { return { owner: "rhythmatician", repo: "voxygen-monorepo" }; },
  };
  return gh as unknown as GhTransport & { editCalls: string[][] };
}

// Tracer-contract-valid body (all 7 concepts) required by canonical eligibility.
const TRACER_BODY = "Scope bounded observable outcome\nno unresolved design decided\nacceptance criteria done when\nverification path verify\ndependencies blocked by none\nsmall enough for one session\nvertical tracer bullet slice end-to-end";
// Research body satisfying the ## Question contract.
const RESEARCH_BODY = "## Question\n\nCanary research question with substantive details for validation, part of #190 with evidence needed and mechanism to be investigated.";

function implIssue(n: number): { issue: FakeIssue; input: IssueInput } {
  const issue: FakeIssue = { number: n, title: "t", body: TRACER_BODY, state: "open", labels: new Set(["ready-for-agent", "agent:implement"]), assignees: new Set() };
  const input: IssueInput = { number: n, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY };
  return { issue, input };
}

describe("tracker-adapter — verified saga", () => {
  it("claimImplementation commits: consumes agent:implement, adds in-progress + claimant", async () => {
    const { issue, input } = implIssue(501);
    const issues = new Map([[501, issue]]);
    const gh = makeFakeGh({ issues });
    const sink = makeMemoryReceiptSink();
    const tracker: TrackerAdapter = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("committed");
    if (result.kind !== "committed") return;
    expect(result.before.labels).toContain("agent:implement");
    expect(result.after.labels).toContain("ready-for-agent");
    expect(result.after.labels).toContain("agent:in-progress");
    expect(result.after.labels).not.toContain("agent:implement");
    expect(result.after.assignees).toContain("test-bot");
    expect(sink.receipts.length).toBe(1);
    expect(sink.receipts[0].kind).toBe("committed");
    expect(sink.receipts[0].transition).toBe("claimImplementation");
  });

  it("claimResearch commits and RETAINS wayfinder:research (explicit profile distinction)", async () => {
    const issue: FakeIssue = { number: 502, title: "t", body: RESEARCH_BODY, state: "open", labels: new Set(["wayfinder:research"]), assignees: new Set() };
    const input: IssueInput = { number: 502, title: "t", state: "open", labels: ["wayfinder:research"], assignees: [], body: RESEARCH_BODY };
    const gh = makeFakeGh({ issues: new Map([[502, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimResearch(input);

    expect(result.kind).toBe("committed");
    if (result.kind !== "committed") return;
    expect(result.after.labels).toContain("wayfinder:research"); // retained
    expect(result.after.labels).toContain("agent:in-progress");
    expect(result.after.assignees).toContain("test-bot");
  });

  it("invalid before-state rejects without mutating (canonical eligibility)", async () => {
    const issue: FakeIssue = { number: 503, title: "t", body: TRACER_BODY, state: "open", labels: new Set(["ready-for-agent"]), assignees: new Set() }; // no agent:implement
    const input: IssueInput = { number: 503, title: "t", state: "open", labels: ["ready-for-agent"], assignees: [], body: TRACER_BODY };
    const gh = makeFakeGh({ issues: new Map([[503, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    // Canonical policy rejection before any mutation — kind:"rejected", not compensated.
    expect(result.kind).toBe("rejected");
    if (result.kind !== "rejected") return;
    expect(gh.editCalls.length).toBe(0); // never mutated
    expect(issue.labels.has("agent:in-progress")).toBe(false);
  });

  it("invalid tracer body rejects via canonical policy (body contract enforced)", async () => {
    const issue: FakeIssue = { number: 513, title: "t", body: "b", state: "open", labels: new Set(["ready-for-agent", "agent:implement"]), assignees: new Set() };
    const input: IssueInput = { number: 513, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: "b" };
    const gh = makeFakeGh({ issues: new Map([[513, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("rejected");
    if (result.kind !== "rejected") return;
    expect(gh.editCalls.length).toBe(0);
  });

  it("mutation failure with defined compensation rolls back to before-state (compensated)", async () => {
    const { issue, input } = implIssue(504);
    const issues = new Map([[504, issue]]);
    // Fail the claim edit itself
    const gh = makeFakeGh({ issues, failEditsWithLabel: "agent:in-progress" });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("compensated");
    if (result.kind !== "compensated") return;
    // Canonical ops report MUTATE_FAILED with compensated=true — mapped to compensated.
    expect(result.receipt.code).toBe("MUTATE_FAILED");
    // Rollback proved by fresh read: no claim residue
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.size).toBe(0);
  });

  it("postcondition mismatch triggers compensation and reports compensated", async () => {
    // Issue where the edit succeeds but verification would fail: pre-marked both implement+in-progress
    // is caught by validateBefore, so instead simulate verify failure via a store that ignores edits.
    const issue: FakeIssue = { number: 505, title: "t", body: TRACER_BODY, state: "open", labels: new Set(["ready-for-agent", "agent:implement"]), assignees: new Set() };
    const input: IssueInput = { number: 505, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY };
    const issues = new Map([[505, issue]]);
    const gh = makeFakeGh({ issues });
    // Sabotage: edits apply but drop the --remove-label of agent:implement (simulate partial GitHub effect)
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const patched = args.filter((a, i) => !(a === "--remove-label" && args[i + 1] === "agent:implement"));
        if (patched.length === args.length) return origRun(args);
        return origRun(patched);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("compensated");
    if (result.kind !== "compensated") return;
    // Compensation removed in-progress + assignee
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.size).toBe(0);
    // Canonical ops report BOTH_PRESENT with compensated=true — mapped to compensated.
    expect(result.receipt.code).toBe("BOTH_PRESENT");
  });

  it("UNSAFE TO RESTORE: release succeeds but adding agent:blocked fails → indeterminate FACTORY_ERROR, agent:implement NOT restored", async () => {
    const issue: FakeIssue = {
      number: 506,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[506, issue]]), failEditsWithLabel: "agent:blocked" });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(506);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("UNSAFE_TO_RESTORE");
    // Critical asymmetry: release stands, blocked absent, implement NOT restored
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(false);
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(result.lastObserved).not.toBeNull();
  });

  it("transitionToBlocked commits when both steps succeed", async () => {
    const issue: FakeIssue = {
      number: 507,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[507, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(507);

    expect(result.kind).toBe("committed");
    expect(issue.labels.has("agent:blocked")).toBe(true);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.size).toBe(0);
  });

  it("releaseAfterFactoryError commits without restoring agent:implement", async () => {
    const issue: FakeIssue = {
      number: 508,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[508, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseAfterFactoryError(508);

    expect(result.kind).toBe("committed");
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
  });

  it("finalizeIntegrated strips transient labels and closes with postcondition proof", async () => {
    const issue: FakeIssue = {
      number: 509,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[509, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.finalizeIntegrated(509, "sandcastle/issue-509");

    expect(result.kind).toBe("committed");
    if (result.kind !== "committed") return;
    expect(result.after.state).toBe("closed");
    expect(issue.state).toBe("closed");
    expect(issue.labels.has("agent:in-progress")).toBe(false);
  });

  it("every transition persists exactly one typed receipt", async () => {
    const { issue, input } = implIssue(510);
    const gh = makeFakeGh({ issues: new Map([[510, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    await tracker.claimImplementation(input);
    expect(sink.receipts.length).toBe(1);
    expect(sink.receipts[0].at).toBeDefined();
    expect(sink.receipts[0].issueNumber).toBe(510);
  });
});

// ---------------------------------------------------------------------------
// Production-shaped guardrails (PR #217 round 2)
// ---------------------------------------------------------------------------

describe("tracker-adapter — production guardrails", () => {
  it("GUARDRAIL: receipt sink throws after GitHub mutation → outer indeterminate, never committed", async () => {
    const { issue, input } = implIssue(601);
    const gh = makeFakeGh({ issues: new Map([[601, issue]]) });
    // Sink that always throws — simulates disk failure after the GitHub edit succeeded.
    const throwingSink: ReceiptSink = {
      persist() { throw new Error("disk full"); },
    };
    const tracker = createTrackerAdapter({ gh, receiptSink: throwingSink });

    const result = await tracker.claimImplementation(input);

    // The GitHub claim DID succeed (labels mutated), but without durable
    // evidence the outer result must be indeterminate FACTORY_ERROR — main
    // would not launch a worker.
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("RECEIPT_PERSIST_FAILED");
    // GitHub state was actually mutated (claim applied) — evidence of why this is indeterminate.
    expect(issue.labels.has("agent:in-progress")).toBe(true);
  });

  it("GUARDRAIL: compensation edit exits 0 but post-state remains claimed → indeterminate", async () => {
    // Scenario: the claim edit SUCCEEDS (claim applied), then a postcondition
    // mismatch triggers compensation — but the compensation edit silently
    // no-ops (exit 0, mutates nothing). The fresh-read proof must catch it.
    const issue: FakeIssue = {
      number: 602,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:implement"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[602, issue]]) });
    // Scenario: the claim edit PARTIALLY applies (implement not consumed →
    // postcondition mismatch), then compensation silently no-ops (exit 0,
    // mutates nothing). The fresh-read proof must catch the still-claimed state.
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const isClaimEdit = args.includes("--add-label");
        if (isClaimEdit) {
          // Partial effect: add assignee + in-progress but SKIP removing implement.
          const patched = args.filter((a, i) => !(a === "--remove-label" && args[i + 1] === "agent:implement"));
          if (patched.length === args.length) return origRun(args);
          return origRun(patched);
        }
        // Compensation edit: pretend success, mutate nothing.
        return "";
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const input602: IssueInput = { number: 602, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY };
    const result = await tracker.claimImplementation(input602);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("COMPENSATION_VERIFY_FAILED");
    // Post-state remains claimed — exactly the hazard this guardrail proves.
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.assignees.has("test-bot")).toBe(true);
  });

  it("GUARDRAIL: invalid Research input → rejected with zero mutation", async () => {
    const issue: FakeIssue = { number: 603, title: "t", body: "no question here", state: "open", labels: new Set(["wayfinder:research"]), assignees: new Set() };
    const input: IssueInput = { number: 603, title: "t", state: "open", labels: ["wayfinder:research"], assignees: [], body: "no question here" };
    const gh = makeFakeGh({ issues: new Map([[603, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimResearch(input);

    expect(result.kind).toBe("rejected");
    if (result.kind !== "rejected") return;
    expect(gh.editCalls.length).toBe(0); // zero mutation
    expect(sink.receipts[0].kind).toBe("rejected");
  });

  it("GUARDRAIL: successful claim receipt has truthful pre-claim assignees/labels", async () => {
    const { issue, input } = implIssue(604);
    const gh = makeFakeGh({ issues: new Map([[604, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("committed");
    if (result.kind !== "committed") return;
    // before.assignees must NOT contain the newly added claimant
    expect(result.before.assignees).not.toContain("test-bot");
    expect(result.before.labels).toContain("agent:implement");   // implement present pre-claim
    expect(result.before.labels).not.toContain("agent:in-progress");
    // after is the actual final observed state
    expect(result.after.assignees).toContain("test-bot");
    expect(result.after.labels).toContain("agent:in-progress");
    expect(result.after.labels).not.toContain("agent:implement");
  });

  it("GUARDRAIL: implement reintroduced before blocked transition → rejected, zero mutation", async () => {
    const issue: FakeIssue = {
      number: 605,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress", "agent:implement"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[605, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(605);

    expect(result.kind).toBe("rejected");
    if (result.kind !== "rejected") return;
    expect(gh.editCalls.length).toBe(0); // zero mutation
    // State untouched
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });

  it("GUARDRAIL: blocked transition missing claimant → rejected, zero mutation", async () => {
    const issue: FakeIssue = {
      number: 606,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(), // no claimant
    };
    const gh = makeFakeGh({ issues: new Map([[606, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(606);

    expect(result.kind).toBe("rejected");
    if (result.kind !== "rejected") return;
    expect(gh.editCalls.length).toBe(0);
  });

  it("GUARDRAIL: verify-fetch failure after claim → compensated only when proven on fresh read", async () => {
    const issue: FakeIssue = { number: 607, title: "t", body: TRACER_BODY, state: "open", labels: new Set(["ready-for-agent", "agent:implement"]), assignees: new Set() };
    const input: IssueInput = { number: 607, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY };
    const gh = makeFakeGh({ issues: new Map([[607, issue]]) });
    // Sabotage: edits apply but drop --remove-label agent:implement → postcondition mismatch → compensate
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const patched = args.filter((a, i) => !(a === "--remove-label" && args[i + 1] === "agent:implement"));
        if (patched.length === args.length) return origRun(args);
        return origRun(patched);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("compensated");
    if (result.kind !== "compensated") return;
    // Compensation proven by fresh read: claim fully released, readiness retained
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.size).toBe(0);
    expect(issue.labels.has("agent:implement")).toBe(true); // still there (never consumed)
    expect(issue.labels.has("ready-for-agent")).toBe(true);
    // Truthful snapshots in receipt
    expect(result.before.assignees).not.toContain("test-bot");
    expect(result.after.assignees).not.toContain("test-bot");
  });
});
