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

  it("mutation failure with defined compensation → indeterminate when post-mutation state unavailable (command-aware)", async () => {
    const { issue, input } = implIssue(504);
    const issues = new Map([[504, issue]]);
    // Fail the claim edit itself
    const gh = makeFakeGh({ issues, failEditsWithLabel: "agent:in-progress" });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    // The claim edit threw, so verifyClaim never ran → the ACTUAL post-mutation
    // state is unavailable. Command-aware compensation cannot prove whether
    // agent:implement was consumed → indeterminate, never "compensated".
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("COMPENSATION_STATE_UNAVAILABLE");
    // Compensation still attempted on the fresh read: no claim residue.
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

  it("releaseOwnedImplementationClaim commits without restoring agent:implement", async () => {
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

    const result = await tracker.releaseOwnedImplementationClaim(508);

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

  it("GUARDRAIL: consumed-then-reintroduced command → compensation indeterminate (command-aware)", async () => {
    // Scenario: the claim edit CONSUMES agent:implement (removes it) and adds
    // in-progress, but the assignee add silently fails → postcondition
    // mismatch → compensation runs. The post-mutation state shows implement
    // ABSENT (consumed). Then, before compensation, an external actor
    // REINTRODUCES agent:implement. The command-aware compensation must detect
    // the reintroduction as "unexpectedly restored" and report indeterminate —
    // NOT "compensated".
    const issue: FakeIssue = {
      number: 608,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:implement"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[608, issue]]) });
    const origRun = gh.run.bind(gh);
    let claimEditApplied = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const isClaimEdit = args.includes("--add-label");
        if (isClaimEdit) {
          // Claim edit: consume implement + add in-progress, but DROP the
          // --add-assignee so the postcondition fails (assignee absent).
          const patched = args.filter((a, i) => !(a === "--add-assignee" && args[i + 1] === "test-bot"));
          const out = await origRun(patched);
          claimEditApplied = true;
          return out;
        }
        // Compensation edit: BEFORE it runs, an external actor reintroduces
        // agent:implement (the command was consumed, then restored).
        if (claimEditApplied) {
          issue.labels.add("agent:implement");
        }
        return origRun(args);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const input608: IssueInput = { number: 608, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY };
    const result = await tracker.claimImplementation(input608);

    // The claim edit consumed implement (post-mutation state had it absent),
    // then compensation observed implement restored → indeterminate.
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("COMPENSATION_VERIFY_FAILED");
    // The reintroduced implement is the reason — never reported as compensated.
    expect(result.receipt.reason).toContain("unexpectedly restored");
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

// ---------------------------------------------------------------------------
// PR #217 round-3 regressions
// ---------------------------------------------------------------------------

describe("tracker-adapter — canonical claim reads authoritative", () => {
  it("REGRESSION: canonical claim fetch failure => indeterminate FACTORY_ERROR, zero mutation", async () => {
    const issue: FakeIssue = { number: 701, title: "t", body: TRACER_BODY, state: "open", labels: new Set(["ready-for-agent", "agent:implement"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[701, issue]]) });
    // Sabotage: the FIRST issue view (the canonical pre-mutation fetch) fails.
    let viewCalls = 0;
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCalls++;
        if (viewCalls === 1) throw new Error("network partition");
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const input: IssueInput = { number: 701, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY };
    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("FETCH_FAILED");
    expect(result.receipt.kind).toBe("indeterminate");
    // Zero mutation — the claim edit never ran because eligibility could not be revalidated.
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
  });

  it("REGRESSION: exact canonical pre-mutation snapshot is receipted", async () => {
    const issue: FakeIssue = { number: 702, title: "t", body: TRACER_BODY, state: "open", labels: new Set(["ready-for-agent", "agent:implement"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[702, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const input: IssueInput = { number: 702, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY };
    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("committed");
    if (result.kind !== "committed") return;
    // The receipted before-state is EXACTLY what the canonical operation's own
    // pre-mutation fetch observed — implement present, no in-progress, unassigned.
    expect(result.before.labels).toEqual(["ready-for-agent", "agent:implement"]);
    expect(result.before.assignees).toEqual([]);
    expect(sink.receipts[0].before).toEqual(result.before);
  });
});

describe("tracker-adapter — transition invariants across saga steps", () => {
  it("REGRESSION: reclaim between release and add-blocked => indeterminate, blocked absent", async () => {
    const issue: FakeIssue = {
      number: 703,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[703, issue]]) });
    // Concurrent drift: after the release edit lands, another actor re-adds
    // agent:in-progress before the intermediate fresh read proves release.
    const origRun = gh.run.bind(gh);
    let released = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit" && args.includes("--remove-label") && args.includes("agent:in-progress")) {
        released = true;
        return origRun(args);
      }
      if (released && args[0] === "issue" && args[1] === "view") {
        // Drift: reclaim happened concurrently — inject in-progress back into reads.
        const raw = await origRun(args);
        const parsed = JSON.parse(raw);
        parsed.labels.push({ name: "agent:in-progress" });
        return JSON.stringify(parsed);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(703);

    // The released intermediate state did NOT hold — must be indeterminate,
    // and compensation restored the claimed state; agent:blocked never added.
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });

  it("REGRESSION: final committed blocked state proves full invariant", async () => {
    const issue: FakeIssue = {
      number: 704,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[704, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(704);

    expect(result.kind).toBe("committed");
    if (result.kind !== "committed") return;
    // open + ready-for-agent + agent:blocked − in-progress − implement − claimant
    expect(result.after.state).toBe("open");
    expect(result.after.labels).toContain("ready-for-agent");
    expect(result.after.labels).toContain("agent:blocked");
    expect(result.after.labels).not.toContain("agent:in-progress");
    expect(result.after.labels).not.toContain("agent:implement");
    expect(result.after.assignees).not.toContain("test-bot");
  });
});

describe("tracker-adapter — one-authority migration and cleanup receipts", () => {
  it("REGRESSION: migration label mutation port commits with typed receipt and proves final label set", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 705, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[705, issue]]) });
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(705, ["ready-for-human"], []);

    expect(result.committed).toBe(true);
    expect(issue.labels.has("ready-for-human")).toBe(true);
    // Typed receipt persisted by the tracker authority.
    expect(sink.receipts.length).toBe(1);
    expect(sink.receipts[0].transition).toBe("migrationLabelMutation");
    expect(sink.receipts[0].kind).toBe("committed");
  });

  it("REGRESSION: migration label mutation postcondition mismatch => not committed", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 706, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[706, issue]]) });
    // Sabotage: edits silently drop --add-label ready-for-human.
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const patched = args.filter((a, i) => !(a === "--add-label" && args[i + 1] === "ready-for-human"));
        if (patched.length === args.length) return origRun(args);
        return origRun(patched);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(706, ["ready-for-human"], []);

    expect(result.committed).toBe(false);
    expect(issue.labels.has("ready-for-human")).toBe(false);
    expect(sink.receipts[sink.receipts.length - 1].kind).not.toBe("committed");
  });

  it("REGRESSION: closed cleanup produces the tracker authority's typed receipt per label", async () => {
    const issue: FakeIssue = {
      number: 707,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress", "agent:implement"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[707, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(707);

    expect(outcome.status).toBe("committed");
    // ONE verified closed-state transition for the whole cleanup, one typed receipt.
    expect(sink.receipts.length).toBe(1);
    const receipt = sink.receipts[0];
    expect(receipt.transition).toBe("cleanupClosedStaleLabels");
    expect(receipt.kind).toBe("committed");
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.labels.has("agent:implement")).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-4 regressions — phase-aware preconditions, exact ownership,
// verified closed cleanup.
// ---------------------------------------------------------------------------

describe("tracker-adapter — phase-aware second-step preconditions (round 4)", () => {
  it("REGRESSION: reclaim between release and add-blocked => indeterminate, ZERO blocked-label mutation", async () => {
    const issue: FakeIssue = {
      number: 801,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[801, issue]]) });
    // Concurrent drift: the release postcondition PASSES, then another actor
    // re-adds agent:in-progress + claimant BEFORE the add-blocked step's own
    // fresh read. The phase-aware validateBefore must catch it on that read
    // and NO --add-label agent:blocked command may occur.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // Read sequence: 1 = release validateBefore, 2 = release verifyAfter,
        // 3 = add-blocked validateBefore. Drift (concurrent reclaim) becomes
        // visible exactly at read 3 — AFTER the release postcondition passed.
        if (viewCount <= 2) return raw;
        const parsed = JSON.parse(raw);
        parsed.labels.push({ name: "agent:in-progress" });
        parsed.assignees.push({ login: "test-bot" });
        return JSON.stringify(parsed);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(801);

    // Second-step precondition failure AFTER a prior mutation is evidence
    // loss — indeterminate FACTORY_ERROR, never rejected, never committed.
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("PRECONDITION_DRIFT");
    // The critical assertion: no blocked-label mutation occurred.
    const blockedEdit = gh.editCalls.find((c) => c.includes("--add-label") && c.includes("agent:blocked"));
    expect(blockedEdit).toBeUndefined();
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });

  it("REGRESSION: reconciliation addBlockedAfterRelease observes drift => no blocked label added", async () => {
    const issue: FakeIssue = {
      number: 802,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[802, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    // Release first through the adapter (committed), then inject drift before
    // the add-blocked saga reads.
    const released = await tracker.releaseOwnedImplementationClaim(802);
    expect(released.kind).toBe("committed");
    // Concurrent reclaim by another actor.
    issue.labels.add("agent:in-progress");

    // Drive the reconciliation transition port directly — the fresh ownership
    // read observes the reintroduced claim and must refuse to mutate.
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const staleInput: IssueInput = { number: 802, title: "t", state: "open", labels: ["ready-for-agent", "agent:in-progress"], assignees: ["test-bot"], body: TRACER_BODY, blockedByCount: 0 };
    const res = await reconcileStaleImplementation(staleInput, "sandcastle/issue-802", {
      claimantLogin: "test-bot",
      // Fresh read reflects the CURRENT (drifted) store state above.
      fetchIssue: async () => ({ number: 802, title: "t", state: "open" as const, labels: [...issue.labels], assignees: [...issue.assignees], body: TRACER_BODY, blockedByCount: 0 }),
      getBatchPrNumber: async () => ({ prNumber: null, state: "absent" as const }),
      getPrState: async () => ({ state: "CLOSED", mergedAt: null, found: false }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }) as any,
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      github: {
        releaseAndBlockOwnedImplementation: async () => {
          throw new Error("release+block must not run after observed drift");
        },
        releaseOwnedImplementationClaim: async () => {
          throw new Error("owned release must not run after observed drift");
        },
        integrateAndClose: async () => ({ kind: "indeterminate" as const }),
        comment: async () => true,
      },
    });

    // Ownership gate rejects with zero mutation; no agent:blocked anywhere.
    expect(res.factoryError ?? false).toBe(false);
    expect(res.reason).toMatch(/ownership drifted|zero mutation/);
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });
});

describe("tracker-adapter — exact ownership for stale reconciliation (round 4)", () => {
  function ownedIssue(n: number): FakeIssue {
    return {
      number: n,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
  }

  it("REGRESSION: unrelated assignee + agent:in-progress => zero mutation", async () => {
    const issue: FakeIssue = ownedIssue(811);
    issue.assignees.clear();
    issue.assignees.add("someone-else");
    const gh = makeFakeGh({ issues: new Map([[811, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(811);

    // Not our claim — rejected before any mutation.
    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.assignees.has("someone-else")).toBe(true);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
  });

  it("REGRESSION: multiple assignees including claimant => rejected, zero mutation (sole concurrency owner)", async () => {
    // The claimant is the SINGLE concurrency owner. An additional assignee
    // (intruder) is concurrent drift — the claim is not owned and must NOT be
    // released/blocked. Zero mutation.
    const issue: FakeIssue = ownedIssue(812);
    issue.assignees.add("human-reviewer");
    const gh = makeFakeGh({ issues: new Map([[812, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(812);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.assignees.has("human-reviewer")).toBe(true);
    expect(issue.assignees.has("test-bot")).toBe(true);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });

  it("REGRESSION: claimant removed between inspection and release => zero mutation", async () => {
    const issue: FakeIssue = ownedIssue(813);
    const gh = makeFakeGh({ issues: new Map([[813, issue]]) });
    // Sabotage: the release edit silently drops --remove-assignee so the
    // claimant remains assigned while in-progress is removed — the released
    // intermediate proof must fail and report indeterminate.
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const patched = args.filter((a, i) => !(a === "--remove-assignee" && args[i + 1] === "test-bot"));
        if (patched.length === args.length) return origRun(args);
        return origRun(patched);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(813);

    // Released intermediate state did not hold (claimant still assigned) —
    // indeterminate, and NO blocked label was added.
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    const blockedEdit = gh.editCalls.find((c) => c.includes("--add-label") && c.includes("agent:blocked"));
    expect(blockedEdit).toBeUndefined();
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });

  it("REGRESSION: unrelated assignee appears after ownership proof => indeterminate, no block progression (zero assignees)", async () => {
    const issue: FakeIssue = ownedIssue(814);
    const gh = makeFakeGh({ issues: new Map([[814, issue]]) });
    // Sabotage: after the release edit removes the claimant, an unrelated
    // assignee (human-reviewer) is added concurrently. The released
    // intermediate state must have ZERO assignees — this drift must make the
    // transition indeterminate and prevent block progression.
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      const r = await origRun(args);
      if (args[0] === "issue" && args[1] === "edit" && args.includes("--remove-assignee")) {
        // Concurrent unrelated assignee appears after the release.
        issue.assignees.add("human-reviewer");
      }
      return r;
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(814);

    // Released intermediate state has an unrelated assignee — indeterminate,
    // and NO blocked label was added.
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    const blockedEdit = gh.editCalls.find((c) => c.includes("--add-label") && c.includes("agent:blocked"));
    expect(blockedEdit).toBeUndefined();
    expect(issue.labels.has("agent:blocked")).toBe(false);
    expect(issue.assignees.has("human-reviewer")).toBe(true);
  });

  it("REGRESSION: unrelated assignee appears during finalizeIntegrated close => indeterminate, not closed", async () => {
    const issue: FakeIssue = ownedIssue(815);
    const gh = makeFakeGh({ issues: new Map([[815, issue]]) });
    // Sabotage: after the strip-transient step, an unrelated assignee appears
    // before the close step. The close step's verifyAfter must reject (zero
    // assignees in the closed integrated state) — indeterminate, not closed.
    const origRun = gh.run.bind(gh);
    let stripped = false;
    (gh as any).run = async (args: string[]) => {
      const r = await origRun(args);
      if (args[0] === "issue" && args[1] === "edit" && args.includes("--remove-label") && args.includes("agent:in-progress")) {
        stripped = true;
      }
      if (stripped && args[0] === "issue" && args[1] === "close") {
        // Concurrent unrelated assignee appears before close.
        issue.assignees.add("human-reviewer");
      }
      return r;
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.finalizeIntegrated(815, "sandcastle/issue-815");

    // The close step's verifyAfter rejects (unrelated assignee present) —
    // indeterminate, NOT committed. The close mutation may have landed (state
    // closed) but the transition is not committed because the final state has
    // an unrelated assignee.
    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(issue.assignees.has("human-reviewer")).toBe(true);
  });
});

describe("tracker-adapter — one verified closed-state cleanup (round 4)", () => {
  it("REGRESSION: reopened closed issue => rejected with zero cleanup mutation", async () => {
    const issue: FakeIssue = {
      number: 821,
      title: "t",
      body: TRACER_BODY,
      state: "open", // reopened between listing and cleanup
      labels: new Set(["ready-for-agent", "agent:in-progress", "agent:implement"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[821, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(821);

    // Fresh pre-mutation read sees open — rejected, zero writes.
    expect(outcome.status).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(sink.receipts[sink.receipts.length - 1].kind).toBe("rejected");
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.labels.has("agent:implement")).toBe(true);
  });

  it("REGRESSION: reopen during cleanup transition => indeterminate, factory stops", async () => {
    const issue: FakeIssue = {
      number: 822,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[822, issue]]) });
    // Sabotage: the label removal lands but concurrently reopens the issue.
    const origRun = gh.run.bind(gh);
    let mutated = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const r = await origRun(args);
        mutated = true;
        issue.state = "open"; // concurrent reopen mid-transition
        return r;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(822);

    // Postcondition proves closed-state violation — not cleaned, factory stops.
    expect(outcome.status).toBe("indeterminate");
    expect(mutated).toBe(true);
    expect(sink.receipts[sink.receipts.length - 1].kind).not.toBe("committed");
  });

  it("REGRESSION: cleanup with zero listed residue proves absence on fresh read", async () => {
    const issue: FakeIssue = {
      number: 823,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[823, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(823);

    expect(outcome.status).toBe("unchanged");
    expect(gh.editCalls.length).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-5 — terminal committed snapshot revalidation. The final fetch
// in runSaga is a NEW read that must be re-verified against the last step's
// postcondition before it may be persisted as the committed `after`. A drift
// between the in-loop verified read and the terminal read is indeterminate.
// ---------------------------------------------------------------------------

describe("tracker-adapter — terminal committed snapshot revalidation (round 5)", () => {
  it("REGRESSION: transitionToBlocked terminal drift => indeterminate, not committed", async () => {
    const issue: FakeIssue = {
      number: 901,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[901, issue]]) });
    // Drift on the terminal fetch: agent:in-progress reappears after the
    // in-loop verifyAfter passed. The committed snapshot must NOT be persisted.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // transitionToBlocked views: 1=release validate, 2=release verify,
        // 3=add-blocked validate, 4=add-blocked verify, 5=terminal.
        if (viewCount === 5) {
          const parsed = JSON.parse(raw);
          parsed.labels.push({ name: "agent:in-progress" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.transitionToBlocked(901);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("TERMINAL_DRIFT");
    // No committed receipt was persisted.
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("REGRESSION: cleanupClosedStaleLabels terminal drift => indeterminate, not cleaned", async () => {
    const issue: FakeIssue = {
      number: 902,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[902, issue]]) });
    // Drift on the terminal fetch: agent:in-progress reappears on the closed
    // issue after the in-loop verifyAfter passed.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // cleanupClosedStaleLabels views: 1=validateBefore, 2=verifyAfter, 3=terminal.
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.labels.push({ name: "agent:in-progress" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(902);

    expect(outcome.status).toBe("indeterminate");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("REGRESSION: finalizeIntegrated terminal drift => indeterminate, not committed", async () => {
    const issue: FakeIssue = {
      number: 903,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[903, issue]]) });
    // Drift on the terminal fetch: the issue is reopened after the in-loop
    // verifyAfter proved it closed.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // finalizeIntegrated views: 1=strip validate, 2=strip verify,
        // 3=close validate, 4=close verify, 5=terminal.
        if (viewCount === 5) {
          const parsed = JSON.parse(raw);
          parsed.state = "open";
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.finalizeIntegrated(903, "sandcastle/issue-903");

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("TERMINAL_DRIFT");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("REGRESSION: migrationLabelMutation terminal drift => not committed", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 904, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[904, issue]]) });
    // Drift on the terminal fetch: ready-for-human disappears after the
    // in-loop verifyAfter proved it present.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // migrationLabelMutation views: 1=validateBefore, 2=verifyAfter, 3=terminal.
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.labels = parsed.labels.filter((l: any) => l.name !== "ready-for-human");
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(904, ["ready-for-human"], []);

    expect(result.committed).toBe(false);
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-5 — exact ownership inside the release mutation. The release
// mutation's own validateBefore freshly proves the exact claimant, profile,
// readiness, and command state before mutation. Reconciliation release+block
// is ONE two-step saga.
// ---------------------------------------------------------------------------

describe("tracker-adapter — exact ownership inside release mutation (round 5)", () => {
  function ownedImpl(n: number): FakeIssue {
    return {
      number: n,
      title: "t",
      body: TRACER_BODY,
      state: "open",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
  }

  it("releaseOwnedImplementationClaim commits on exact ownership", async () => {
    const issue = ownedImpl(911);
    const gh = makeFakeGh({ issues: new Map([[911, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseOwnedImplementationClaim(911);

    expect(result.kind).toBe("committed");
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.labels.has("ready-for-agent")).toBe(true);
    expect(issue.labels.has("agent:implement")).toBe(false);
  });

  it("releaseOwnedImplementationClaim rejects unrelated assignee with zero mutation", async () => {
    const issue = ownedImpl(912);
    issue.assignees.clear();
    issue.assignees.add("someone-else");
    const gh = makeFakeGh({ issues: new Map([[912, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseOwnedImplementationClaim(912);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.assignees.has("someone-else")).toBe(true);
  });

  it("releaseOwnedImplementationClaim rejects when implement not consumed (both present)", async () => {
    const issue = ownedImpl(913);
    issue.labels.add("agent:implement");
    const gh = makeFakeGh({ issues: new Map([[913, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseOwnedImplementationClaim(913);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:implement")).toBe(true);
  });

  it("releaseOwnedResearchClaim commits on exact research ownership", async () => {
    const issue: FakeIssue = {
      number: 914,
      title: "t",
      body: RESEARCH_BODY,
      state: "open",
      labels: new Set(["wayfinder:research", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[914, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseOwnedResearchClaim(914);

    expect(result.kind).toBe("committed");
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.labels.has("wayfinder:research")).toBe(true);
  });

  it("releaseOwnedResearchClaim rejects unrelated assignee with zero mutation", async () => {
    const issue: FakeIssue = {
      number: 915,
      title: "t",
      body: RESEARCH_BODY,
      state: "open",
      labels: new Set(["wayfinder:research", "agent:in-progress"]),
      assignees: new Set(["someone-else"]),
    };
    const gh = makeFakeGh({ issues: new Map([[915, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseOwnedResearchClaim(915);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
  });

  it("compensateBothPresentClaim removes in-progress + claimant but RETAINS implement", async () => {
    const issue = ownedImpl(916);
    issue.labels.add("agent:implement");
    const gh = makeFakeGh({ issues: new Map([[916, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.compensateBothPresentClaim(916);

    expect(result.kind).toBe("committed");
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.has("test-bot")).toBe(false);
    // The command was NOT consumed — agent:implement must be retained.
    expect(issue.labels.has("agent:implement")).toBe(true);
  });

  it("compensateBothPresentClaim rejects when implement absent (not a contradiction)", async () => {
    const issue = ownedImpl(917);
    const gh = makeFakeGh({ issues: new Map([[917, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.compensateBothPresentClaim(917);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
  });

  it("releaseOwnedImplementationClaim rejects claimant+intruder (sole concurrency owner) with zero mutation", async () => {
    // The claimant is the SINGLE concurrency owner. An additional assignee
    // (intruder) is concurrent drift — the claim is not owned and must NOT be
    // released. Zero mutation.
    const issue = ownedImpl(918);
    issue.assignees.add("human-reviewer");
    const gh = makeFakeGh({ issues: new Map([[918, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseOwnedImplementationClaim(918);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.assignees.has("human-reviewer")).toBe(true);
    expect(issue.assignees.has("test-bot")).toBe(true);
  });

  it("releaseOwnedResearchClaim rejects claimant+intruder (sole concurrency owner) with zero mutation", async () => {
    const issue: FakeIssue = {
      number: 919,
      title: "t",
      body: RESEARCH_BODY,
      state: "open",
      labels: new Set(["wayfinder:research", "agent:in-progress"]),
      assignees: new Set(["test-bot", "human-reviewer"]),
    };
    const gh = makeFakeGh({ issues: new Map([[919, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseOwnedResearchClaim(919);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.assignees.has("human-reviewer")).toBe(true);
    expect(issue.assignees.has("test-bot")).toBe(true);
  });

  it("compensateBothPresentClaim rejects claimant+intruder (sole concurrency owner) with zero mutation", async () => {
    const issue = ownedImpl(920);
    issue.labels.add("agent:implement");
    issue.assignees.add("human-reviewer");
    const gh = makeFakeGh({ issues: new Map([[920, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.compensateBothPresentClaim(920);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.labels.has("agent:implement")).toBe(true);
    expect(issue.assignees.has("human-reviewer")).toBe(true);
    expect(issue.assignees.has("test-bot")).toBe(true);
  });

  it("finalizeIntegrated rejects claimant+intruder (sole concurrency owner) with zero mutation", async () => {
    const issue = ownedImpl(921);
    issue.assignees.add("human-reviewer");
    const gh = makeFakeGh({ issues: new Map([[921, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.finalizeIntegrated(921, "sandcastle/issue-921");

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.state).toBe("open");
    expect(issue.assignees.has("human-reviewer")).toBe(true);
    expect(issue.assignees.has("test-bot")).toBe(true);
  });

  it("releaseAndBlockOwnedImplementation is ONE two-step saga: release then block", async () => {
    const issue = ownedImpl(918);
    const gh = makeFakeGh({ issues: new Map([[918, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseAndBlockOwnedImplementation(918);

    expect(result.kind).toBe("committed");
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(true);
    expect(issue.labels.has("ready-for-agent")).toBe(true);
    // ONE typed receipt for the whole two-step saga.
    expect(sink.receipts.length).toBe(1);
    expect(sink.receipts[0].transition).toBe("releaseAndBlockOwnedImplementation");
    expect(sink.receipts[0].kind).toBe("committed");
  });

  it("releaseAndBlockOwnedImplementation rejects unrelated assignee with zero mutation", async () => {
    const issue = ownedImpl(919);
    issue.assignees.clear();
    issue.assignees.add("someone-else");
    const gh = makeFakeGh({ issues: new Map([[919, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseAndBlockOwnedImplementation(919);

    expect(result.kind).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
    expect(issue.labels.has("agent:blocked")).toBe(false);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
  });

  it("releaseAndBlockOwnedImplementation drift between steps => indeterminate, no blocked", async () => {
    const issue = ownedImpl(920);
    const gh = makeFakeGh({ issues: new Map([[920, issue]]) });
    // Drift: after the release step commits, another actor reclaims before the
    // add-blocked step's own fresh read.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // releaseAndBlockOwnedImplementation views: 1=release validate,
        // 2=release verify, 3=add-blocked validate, 4=add-blocked verify, 5=terminal.
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.labels.push({ name: "agent:in-progress" });
          parsed.assignees.push({ login: "test-bot" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.releaseAndBlockOwnedImplementation(920);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("PRECONDITION_DRIFT");
    const blockedEdit = gh.editCalls.find((c) => c.includes("--add-label") && c.includes("agent:blocked"));
    expect(blockedEdit).toBeUndefined();
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });

  it("REGRESSION: drift between executor read and adapter saga read => zero mutation", async () => {
    // The executor's validateStillOwned read passes (claimant present), but
    // the adapter saga's OWN fresh read observes the drift (claimant removed).
    // The saga's validateBefore must reject with zero mutation.
    const issue = ownedImpl(921);
    const gh = makeFakeGh({ issues: new Map([[921, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    // Drive the reconciliation executor with the REAL adapter saga port. The
    // executor's validateStillOwned reads ops.fetchIssue (claimant present),
    // then calls the adapter's releaseAndBlockOwnedImplementation which does
    // its OWN fresh read. Inject drift so the saga's read sees no claimant.
    const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
    const staleInput: IssueInput = { number: 921, title: "t", state: "open", labels: ["ready-for-agent", "agent:in-progress"], assignees: ["test-bot"], body: TRACER_BODY, blockedByCount: 0 };
    let executorReads = 0;
    const res = await reconcileStaleImplementation(staleInput, "sandcastle/issue-921", {
      claimantLogin: "test-bot",
      // Executor ownership read (first) sees the claimant; the saga's own
      // fresh read (subsequent) sees the claimant removed.
      fetchIssue: async () => {
        executorReads++;
        if (executorReads === 1) return { ...staleInput };
        return { ...staleInput, assignees: [] };
      },
      getBatchPrNumber: async () => ({ prNumber: null, state: "absent" as const }),
      getPrState: async () => ({ state: "CLOSED", mergedAt: null, found: false }),
      checkBranchExists: async () => "absent" as const,
      checkProvenanceValid: async () => ({ state: "valid" as const, reason: "valid" }) as any,
      hasCommitsAhead: async () => "empty" as const,
      deleteBranch: async () => true,
      github: {
        releaseAndBlockOwnedImplementation: (n) => tracker.releaseAndBlockOwnedImplementation(n),
        releaseOwnedImplementationClaim: (n) => tracker.releaseOwnedImplementationClaim(n),
        integrateAndClose: (n, b) => tracker.finalizeIntegrated(n, b),
        comment: (n, body) => tracker.comment(n, body),
      },
    });

    // The executor's validateStillOwned passes, but the saga's own read
    // rejects — the release+block saga's validateBefore fires with zero
    // mutation. The saga's validateBefore rejection is a clean rejection
    // (first step), so no factoryError.
    expect(res.factoryError ?? false).toBe(false);
    expect(issue.labels.has("agent:in-progress")).toBe(true);
    expect(issue.assignees.has("test-bot")).toBe(true);
    expect(issue.labels.has("agent:blocked")).toBe(false);
    expect(gh.editCalls.length).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-5 — complete closed-claim cleanup. The cleanup fetches the
// closed issue's assignees, removes and verifies the AUTHENTICATED claimant
// while preserving unrelated assignees, and proves the final invariant:
// closed + claimant absent + all three machine labels absent. Applied in the
// no-label fast path too.
// ---------------------------------------------------------------------------

describe("tracker-adapter — complete closed-claim cleanup (round 5)", () => {
  it("removes claimant assignee while preserving unrelated assignees on a closed issue", async () => {
    const issue: FakeIssue = {
      number: 1001,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress", "agent:implement"]),
      assignees: new Set(["test-bot", "human-reviewer"]),
    };
    const gh = makeFakeGh({ issues: new Map([[1001, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1001);

    expect(outcome.status).toBe("committed");
    // Final invariant: closed + claimant absent + all three machine labels absent.
    expect(issue.state).toBe("closed");
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.assignees.has("human-reviewer")).toBe(true); // unrelated preserved
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(false);
    // ONE verified two-step saga, one typed receipt.
    expect(sink.receipts.length).toBe(1);
    expect(sink.receipts[0].transition).toBe("cleanupClosedStaleLabels");
    expect(sink.receipts[0].kind).toBe("committed");
  });

  it("no-label fast path also removes the claimant assignee", async () => {
    const issue: FakeIssue = {
      number: 1002,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[1002, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1002);

    expect(outcome.status).toBe("committed");
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(false);
    expect(sink.receipts[0].kind).toBe("committed");
  });

  it("claimant removal silently dropped => indeterminate, not cleaned", async () => {
    const issue: FakeIssue = {
      number: 1003,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[1003, issue]]) });
    // Sabotage: the assignee-removal edit silently drops --remove-assignee.
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit") {
        const patched = args.filter((a, i) => !(a === "--remove-assignee" && args[i + 1] === "test-bot"));
        if (patched.length === args.length) return origRun(args);
        return origRun(patched);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1003);

    // The label step committed, but the assignee step's verifyAfter catches
    // the still-assigned claimant → indeterminate, not cleaned.
    expect(outcome.status).toBe("indeterminate");
    expect(issue.assignees.has("test-bot")).toBe(true);
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("claimant not assigned on closed issue => no assignee mutation, labels still cleaned", async () => {
    const issue: FakeIssue = {
      number: 1004,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["human-reviewer"]), // claimant NOT assigned
    };
    const gh = makeFakeGh({ issues: new Map([[1004, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1004);

    expect(outcome.status).toBe("committed");
    // Unrelated assignee preserved; no assignee edit attempted.
    expect(issue.assignees.has("human-reviewer")).toBe(true);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    const assigneeEdits = gh.editCalls.filter((c) => c.includes("--remove-assignee"));
    expect(assigneeEdits.length).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-5 — reviewed-state-bound migration mutations. The label
// mutation port accepts the expected reviewed snapshot and validates
// state/labels/assignees/blocked_by/body hash on its own fresh pre-mutation
// read before any write. Drift between review and apply is evidence loss.
// ---------------------------------------------------------------------------

describe("tracker-adapter — reviewed-state-bound migration mutation (round 5)", () => {
  function reviewedSnapshot(n: number, overrides: Partial<{ state: string; labels: string[]; assignees: string[]; blocked_by: number | undefined; bodySha256: string }> = {}) {
    return {
      number: n,
      state: "open",
      labels: ["wayfinder:task"],
      assignees: [],
      blocked_by: 0,
      bodySha256: "abc123",
      ...overrides,
    };
  }

  it("commits when the fresh pre-mutation read matches the reviewed snapshot", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1101, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1101, issue]]) });
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    // Body hash must match the live body "body"., { bodySha256: "230d8358dc8e8890b4c58deeb62912ee2f20357ae92a5cc861b98e68fe31acb5" }
    const result = await mutate(1101, ["ready-for-human"], [], reviewedSnapshot(1101, { bodySha256: "230d8358dc8e8890b4c58deeb62912ee2f20357ae92a5cc861b98e68fe31acb5" }));

    expect(result.committed).toBe(true);
    expect(issue.labels.has("ready-for-human")).toBe(true);
    expect(sink.receipts[0].kind).toBe("committed");
  });

  it("rejects with zero mutation when labels drifted from the reviewed snapshot", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1102, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task", "ready-for-human"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1102, issue]]) });
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    // Reviewed snapshot says only wayfinder:task; live has ready-for-human too.
    const result = await mutate(1102, ["ready-for-human"], [], reviewedSnapshot(1102));

    expect(result.committed).toBe(false);
    expect(result.reason).toContain("labels drifted");
    expect(gh.editCalls.length).toBe(0); // zero mutation
  });

  it("rejects with zero mutation when assignees drifted from the reviewed snapshot", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1103, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set(["someone-else"]) };
    const gh = makeFakeGh({ issues: new Map([[1103, issue]]) });
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(1103, ["ready-for-human"], [], reviewedSnapshot(1103));

    expect(result.committed).toBe(false);
    expect(result.reason).toContain("assignees drifted");
    expect(gh.editCalls.length).toBe(0);
  });

  it("rejects with zero mutation when blocked_by drifted from the reviewed snapshot", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1104, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1104, issue]]) });
    // Override the blocked_by lookup to report 2 (live) while the reviewed
    // snapshot says 0.
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1].includes("issues/") && args.includes("--jq")) return "2";
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    // Reviewed blocked_by=0; live blocked_by=2. Body hash matches so the
    // blocked_by check is the one that fires.
    const result = await mutate(1104, ["ready-for-human"], [], reviewedSnapshot(1104, { blocked_by: 0, bodySha256: "230d8358dc8e8890b4c58deeb62912ee2f20357ae92a5cc861b98e68fe31acb5" }));

    expect(result.committed).toBe(false);
    expect(result.reason).toContain("blocked_by drifted");
    expect(gh.editCalls.length).toBe(0);
  });

  it("rejects with zero mutation when body hash drifted from the reviewed snapshot", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1105, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1105, issue]]) });
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    // Reviewed bodySha256=abc123; live body hashes to something else.
    const result = await mutate(1105, ["ready-for-human"], [], reviewedSnapshot(1105, { bodySha256: "abc123" }));

    expect(result.committed).toBe(false);
    expect(result.reason).toContain("body hash drifted");
    expect(gh.editCalls.length).toBe(0);
  });

  it("rejects with zero mutation when state drifted from the reviewed snapshot", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1106, title: "task", body: "body", state: "closed", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1106, issue]]) });
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    // Reviewed state=open; live state=closed.
    const result = await mutate(1106, ["ready-for-human"], [], reviewedSnapshot(1106));

    expect(result.committed).toBe(false);
    expect(result.reason).toContain("state drifted");
    expect(gh.editCalls.length).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-6 — terminal claim proof (item 1), evidence-authoritative
// repository/creation ports (item 4), reviewed-state drift after precondition
// (item 5), canary cleanup one operation (item 6).
// ---------------------------------------------------------------------------

describe("tracker-adapter — terminal claim proof (round 6)", () => {
  it("implementation claim closes before final read => not committed, no worker", async () => {
    const { issue, input } = implIssue(1201);
    const gh = makeFakeGh({ issues: new Map([[1201, issue]]) });
    // Sabotage: after the canonical claim succeeds, the terminal fresh read
    // observes the issue closed.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // claimImplementation views: 1=canonical fetch, 2=canonical verify,
        // 3=terminal proof.
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.state = "closed";
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("TERMINAL_CLAIM_DRIFT");
    // No committed receipt — worker must not launch.
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("implementation claim gains agent:blocked/open blocker => not committed", async () => {
    const { issue, input } = implIssue(1202);
    const gh = makeFakeGh({ issues: new Map([[1202, issue]]) });
    // Sabotage: the terminal fresh read observes agent:blocked present.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.labels.push({ name: "agent:blocked" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("TERMINAL_CLAIM_DRIFT");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("Research claim gains blocker or closes => not committed", async () => {
    const issue: FakeIssue = { number: 1203, title: "t", body: RESEARCH_BODY, state: "open", labels: new Set(["wayfinder:research"]), assignees: new Set() };
    const input: IssueInput = { number: 1203, title: "t", state: "open", labels: ["wayfinder:research"], assignees: [], body: RESEARCH_BODY };
    const gh = makeFakeGh({ issues: new Map([[1203, issue]]) });
    // Sabotage: the terminal fresh read observes the issue closed.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // claimResearch views: 1=canonical fetch, 2=canonical verify, 3=terminal.
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.state = "closed";
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimResearch(input);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("TERMINAL_CLAIM_DRIFT");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("implementation claim terminal body missing tracer concepts => not committed (shared body contract)", async () => {
    const { issue, input } = implIssue(1204);
    const gh = makeFakeGh({ issues: new Map([[1204, issue]]) });
    // Sabotage: the terminal fresh read observes a NONEMPTY body that is
    // missing tracer concepts. The canonical body contract (the SAME validator
    // isImplementationEligible uses) must reject it — TERMINAL_CLAIM_DRIFT.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.body = "This is a nonempty body but it has no tracer concepts at all.";
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("TERMINAL_CLAIM_DRIFT");
    expect(result.receipt.reason).toContain("implementation body invalid");
    // No committed receipt — worker must not launch.
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("implementation claim terminal unexpected assignee => not committed (single concurrency owner)", async () => {
    const { issue, input } = implIssue(1205);
    const gh = makeFakeGh({ issues: new Map([[1205, issue]]) });
    // Sabotage: the terminal fresh read observes an unexpected assignee in
    // addition to the claimant. The claimant is the single concurrency owner —
    // any additional assignee is drift.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.assignees.push({ login: "intruder" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.claimImplementation(input);

    expect(result.kind).toBe("indeterminate");
    if (result.kind !== "indeterminate") return;
    expect(result.factoryError).toBe(true);
    expect(result.receipt.code).toBe("TERMINAL_CLAIM_DRIFT");
    expect(result.receipt.reason).toMatch(/sole claimant|exactly 1 assignee/);
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });
});

describe("tracker-adapter — evidence-authoritative repository/creation ports (round 6)", () => {
  it("updateCanonicalLabelDescription binds to reviewed old description", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    // Mock the label API: PATCH + read-back.
    let liveDesc = "old desc";
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "PATCH") {
        liveDesc = args[args.indexOf("-f") + 1].replace("description=", "");
        return "";
      }
      if (args[0] === "api" && args[1].includes("/labels/") && args.includes("--jq")) {
        return liveDesc;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    // Reviewed old description matches live — mutation proceeds.
    const ok = await tracker.updateCanonicalLabelDescription("wayfinder:task", "new desc", "old desc");
    expect(ok.status).toBe("committed");
    expect(liveDesc).toBe("new desc");

    // Reviewed old description drifted — zero mutation.
    const drifted = await tracker.updateCanonicalLabelDescription("wayfinder:task", "new desc 2", "stale reviewed desc");
    expect(drifted.status).toBe("rejected");
    expect(drifted.reason).toContain("drifted");
    expect(liveDesc).toBe("new desc");
  });

  it("updateCanonicalLabelDescription receipt persistence failure => not committed", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    let liveDesc = "old desc";
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "PATCH") {
        liveDesc = args[args.indexOf("-f") + 1].replace("description=", "");
        return "";
      }
      if (args[0] === "api" && args[1].includes("/labels/") && args.includes("--jq")) {
        return liveDesc;
      }
      return origRun(args);
    };
    // Receipt sink throws after the PATCH succeeds.
    const sink = makeMemoryReceiptSink();
    const origPersist = sink.persist.bind(sink);
    sink.persist = (r) => { if (r.transition === "updateCanonicalLabelDescription") throw new Error("sink full"); origPersist(r); };
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.updateCanonicalLabelDescription("wayfinder:task", "new desc", "old desc");
    expect(result.status).toBe("indeterminate");
    expect(result.reason).toContain("receipt persistence failed");
  });

  it("deleteRetiredLabel proves zero open users before deletion", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let deleted = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--label")) {
        // One open user still uses the label — refuse deletion.
        return JSON.stringify([{ number: 1 }]);
      }
      if (args[0] === "api" && args[1] === "--method" && args[2] === "DELETE") { deleted = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        // Pre-mutation existence read succeeds (label exists).
        return JSON.stringify({ name: "agent:research", description: "retired" });
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.deleteRetiredLabel("agent:research", true);
    expect(result.status).toBe("rejected");
    expect(result.reason).toContain("open users");
    expect(deleted).toBe(false);
  });

  it("deleteRetiredLabel verification returns 500/network failure => not committed", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let deleted = false;
    let existenceReads = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--label")) {
        return JSON.stringify([]);
      }
      if (args[0] === "api" && args[1] === "--method" && args[2] === "DELETE") { deleted = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        existenceReads++;
        // First read = pre-mutation existence (succeeds); second = post-delete
        // read-back (500 => indeterminate).
        if (existenceReads === 1) return JSON.stringify({ name: "agent:research", description: "retired" });
        throw new Error("500 Internal Server Error");
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.deleteRetiredLabel("agent:research", true);
    expect(result.status).toBe("indeterminate");
    expect(result.reason).toContain("not authoritative 404");
    expect(deleted).toBe(true);
  });

  it("deleteRetiredLabel receipt persistence failure => not committed", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let existenceReads = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--label")) {
        return JSON.stringify([]);
      }
      if (args[0] === "api" && args[1] === "--method" && args[2] === "DELETE") { return ""; }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        existenceReads++;
        if (existenceReads === 1) return JSON.stringify({ name: "agent:research", description: "retired" });
        // Authoritative HTTP 404 — gh CLI reports the status in stderr/message.
        const err = new Error("gh api repos/rhythmatician/voxygen-monorepo/labels/agent:research failed: HTTP 404: Not Found") as any;
        err.stderr = "HTTP 404: Not Found";
        throw err;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const origPersist = sink.persist.bind(sink);
    sink.persist = (r) => { if (r.transition === "deleteRetiredLabel") throw new Error("sink full"); origPersist(r); };
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.deleteRetiredLabel("agent:research", true);
    expect(result.status).toBe("indeterminate");
    expect(result.reason).toContain("receipt persistence failed");
  });

  it("createCanaryFixture receipt persistence failure => not committed but id exposed for cleanup", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "POST") {
        return "1301";
      }
      if (args[0] === "issue" && args[1] === "view") {
        return JSON.stringify({ number: 1301, title: "t", body: "body", state: "open", labels: [{ name: "ready-for-agent" }], assignees: [] });
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const origPersist = sink.persist.bind(sink);
    sink.persist = (r) => { if (r.transition === "createCanaryFixture") throw new Error("sink full"); origPersist(r); };
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.createCanaryFixture("t", "body", ["ready-for-agent"]);
    expect(result.outcome.status).toBe("indeterminate");
    expect(result.outcome.reason).toContain("receipt persistence failed");
    // The fixture id is still exposed so cleanup can occur.
    expect(result.id).toBe(1301);
  });

  it("deleteRetiredLabel GhTokenMissingError after DELETE is NOT 404 => indeterminate and receipted", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let deleted = false;
    let existenceReads = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--label")) {
        return JSON.stringify([]);
      }
      if (args[0] === "api" && args[1] === "--method" && args[2] === "DELETE") { deleted = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        existenceReads++;
        if (existenceReads === 1) return JSON.stringify({ name: "agent:research", description: "retired" });
        // GhTokenMissingError — its message contains "not found" but it is NOT
        // an HTTP 404. Must be treated as indeterminate, never absence.
        const err = new Error("gh token not found in GH_TOKEN or .sandcastle/.env") as any;
        err.name = "GhTokenMissingError";
        throw err;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.deleteRetiredLabel("agent:research", true);
    expect(result.status).toBe("indeterminate");
    expect(result.reason).toContain("not authoritative 404");
    expect(deleted).toBe(true);
    // Durable indeterminate recovery evidence is receipted.
    const receipts = sink.receipts.filter((r) => r.transition === "deleteRetiredLabel");
    expect(receipts.some((r) => r.kind === "indeterminate")).toBe(true);
  });

  it("deleteRetiredLabel HTTP 500 after DELETE => indeterminate and receipted", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let deleted = false;
    let existenceReads = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--label")) {
        return JSON.stringify([]);
      }
      if (args[0] === "api" && args[1] === "--method" && args[2] === "DELETE") { deleted = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        existenceReads++;
        if (existenceReads === 1) return JSON.stringify({ name: "agent:research", description: "retired" });
        const err = new Error("gh api ... failed: HTTP 500: Internal Server Error") as any;
        err.stderr = "HTTP 500: Internal Server Error";
        throw err;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.deleteRetiredLabel("agent:research", true);
    expect(result.status).toBe("indeterminate");
    expect(result.reason).toContain("not authoritative 404");
    expect(deleted).toBe(true);
    const receipts = sink.receipts.filter((r) => r.transition === "deleteRetiredLabel");
    expect(receipts.some((r) => r.kind === "indeterminate")).toBe(true);
  });

  it("updateCanonicalLabelDescription PATCH success/read-back failure => indeterminate and receipted", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let patched = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "PATCH") { patched = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/") && args.includes("--jq")) {
        // Pre-mutation read succeeds; post-PATCH read-back fails (network).
        if (patched) throw new Error("network timeout");
        return "old desc";
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.updateCanonicalLabelDescription("wayfinder:task", "new desc", "old desc");
    expect(result.status).toBe("indeterminate");
    expect(result.reason).toContain("read-back failed");
    expect(patched).toBe(true);
    // Durable indeterminate recovery evidence is receipted.
    const receipts = sink.receipts.filter((r) => r.transition === "updateCanonicalLabelDescription");
    expect(receipts.some((r) => r.kind === "indeterminate")).toBe(true);
  });

  it("createCanaryFixture POST success/read-back failure => exposes id, indeterminate, and is cleaned", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "POST") {
        return "1302";
      }
      if (args[0] === "issue" && args[1] === "view") {
        // Read-back fails — the POST succeeded but the created state cannot be
        // proven. Indeterminate, but the id is exposed for cleanup.
        throw new Error("network timeout");
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.createCanaryFixture("t", "body", ["ready-for-agent"]);
    expect(result.outcome.status).toBe("indeterminate");
    expect(result.outcome.reason).toContain("state not proven");
    // The fixture id is exposed so cleanup can occur.
    expect(result.id).toBe(1302);
    // Durable indeterminate recovery evidence is receipted.
    const receipts = sink.receipts.filter((r) => r.transition === "createCanaryFixture");
    expect(receipts.some((r) => r.kind === "indeterminate")).toBe(true);
  });

  it("deleteRetiredLabel expectedExists=false + live label => drift and zero DELETE", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let deleted = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "DELETE") { deleted = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        // The label EXISTS on the pre-mutation read, but expectedExists=false.
        return JSON.stringify({ name: "agent:research", description: "retired" });
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.deleteRetiredLabel("agent:research", false);
    expect(result.status).toBe("rejected");
    expect(result.reason).toContain("drift");
    // Zero DELETE — the label exists but was reviewed as absent.
    expect(deleted).toBe(false);
    const receipts = sink.receipts.filter((r) => r.transition === "deleteRetiredLabel");
    expect(receipts.some((r) => r.kind === "rejected")).toBe(true);
  });
});

describe("tracker-adapter — reviewed-state drift after precondition (round 6)", () => {
  function reviewedSnapshot(n: number, overrides: Partial<{ state: string; labels: string[]; assignees: string[]; blocked_by: number | undefined; bodySha256: string }> = {}) {
    return {
      number: n,
      state: "open",
      labels: ["wayfinder:task"],
      assignees: [],
      blocked_by: 0,
      bodySha256: "230d8358dc8e8890b4c58deeb62912ee2f20357ae92a5cc861b98e68fe31acb5", // sha256("body")
      ...overrides,
    };
  }

  it("concurrent assignee drift after precondition => not committed", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1401, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1401, issue]]) });
    // Sabotage: after the precondition read passes, an unrelated assignee is
    // added before the terminal verification read.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // migrationLabelMutation views: 1=validateBefore, 2=verifyAfter, 3=terminal.
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.assignees.push({ login: "intruder" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(1401, ["ready-for-human"], [], reviewedSnapshot(1401));
    expect(result.committed).toBe(false);
    expect(result.reason).toContain("assignees changed");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("concurrent body drift after precondition => not committed", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1402, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1402, issue]]) });
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.body = "changed body";
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(1402, ["ready-for-human"], [], reviewedSnapshot(1402));
    expect(result.committed).toBe(false);
    expect(result.reason).toContain("body hash changed");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("concurrent dependency drift after precondition => not committed", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1403, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1403, issue]]) });
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.labels.push({ name: "agent:blocked" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(1403, ["ready-for-human"], [], reviewedSnapshot(1403));
    expect(result.committed).toBe(false);
    expect(result.reason).toContain("labels not exactly reviewed-transformed");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("concurrent state drift after precondition => not committed", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1404, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1404, issue]]) });
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.state = "closed";
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(1404, ["ready-for-human"], [], reviewedSnapshot(1404));
    expect(result.committed).toBe(false);
    expect(result.reason).toContain("state changed");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });

  it("concurrent unrelated label drift after precondition => not committed", async () => {
    const { makeIssueLabelMutationPort } = await import("./tracker-adapter.mts");
    const issue: FakeIssue = { number: 1405, title: "task", body: "body", state: "open", labels: new Set(["wayfinder:task"]), assignees: new Set() };
    const gh = makeFakeGh({ issues: new Map([[1405, issue]]) });
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.labels.push({ name: "needs-triage" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const mutate = makeIssueLabelMutationPort(gh, sink);

    const result = await mutate(1405, ["ready-for-human"], [], reviewedSnapshot(1405));
    expect(result.committed).toBe(false);
    expect(result.reason).toContain("labels not exactly reviewed-transformed");
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });
});

describe("tracker-adapter — canary fixture cleanup one operation (round 6)", () => {
  it("cleanupCanaryFixture executes once with one close command and one receipt", async () => {
    const issue: FakeIssue = { number: 1501, title: "t", body: TRACER_BODY, state: "open", labels: new Set(["ready-for-agent", "agent:in-progress"]), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1501, issue]]) });
    // Track close commands.
    const closeCommands: string[][] = [];
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "close") closeCommands.push([...args]);
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    await tracker.cleanupCanaryFixture(1501);

    // ONE logical cleanup call => one close command, one final receipt.
    expect(closeCommands.length).toBe(1);
    // ONE final cleanup receipt per fixture.
    const cleanupReceipts = sink.receipts.filter((r) => r.transition === "cleanupCanaryFixture");
    expect(cleanupReceipts.length).toBe(1);
    expect(cleanupReceipts[0].kind).toBe("committed");
    // Final invariant: closed + claimant absent + all three labels absent.
    expect(issue.state).toBe("closed");
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-6 — remaining item-7 regressions: closed agent:blocked-only
// residue, reopened no-label fast path, and the production closed-inventory
// wiring path (initial list omits assignee details; fresh read supplies them).
// ---------------------------------------------------------------------------

describe("tracker-adapter — closed cleanup residue regressions (round 6)", () => {
  it("closed agent:blocked-only residue is cleaned", async () => {
    const issue: FakeIssue = {
      number: 1601,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:blocked"]),
      assignees: new Set(["test-bot"]),
    };
    const gh = makeFakeGh({ issues: new Map([[1601, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1601);

    expect(outcome.status).toBe("committed");
    // Final invariant: closed + claimant absent + all three labels absent.
    expect(issue.state).toBe("closed");
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(false);
    expect(sink.receipts[0].kind).toBe("committed");
  });

  it("reopened no-label cleanup fast path => not clean", async () => {
    const issue: FakeIssue = {
      number: 1602,
      title: "t",
      body: TRACER_BODY,
      state: "open", // reopened between listing and cleanup
      labels: new Set(["ready-for-agent"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[1602, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1602);

    // Reopened issue is not clean even though there is no label residue.
    expect(outcome.status).toBe("rejected");
    expect(gh.editCalls.length).toBe(0);
  });

  it("reopened no-label fast path with concurrent reopen after first read => not clean", async () => {
    const issue: FakeIssue = {
      number: 1603,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[1603, issue]]) });
    // Sabotage: the fast-path fresh read observes the issue reopened.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        // cleanupClosedStaleLabels views: 1=initial fresh read, 2=fast-path fresh read.
        if (viewCount === 2) {
          const parsed = JSON.parse(raw);
          parsed.state = "open";
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1603);

    // The fast path's fresh read proves the issue is no longer closed => not clean.
    expect(outcome.status).toBe("indeterminate");
    expect(gh.editCalls.length).toBe(0);
  });

  it("production closed-inventory path: initial list omits assignee details, fresh read supplies them", async () => {
    // Simulate the production closed-inventory wiring in main.mts: the initial
    // `issue list --state closed --label <machine-label> --json number` returns
    // ONLY the issue number (no assignee details). The cleanup then reads the
    // issue FRESH by number, which supplies assignee details, and unassigns the
    // authenticated claimant + removes all three machine labels.
    const issue: FakeIssue = {
      number: 1604,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(["test-bot", "human-reviewer"]),
    };
    const gh = makeFakeGh({ issues: new Map([[1604, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    // Production inventory: list returns only { number } — no assignee details.
    const listed = [{ number: 1604 }];
    const numbers = new Set<number>();
    for (const r of listed) numbers.add(r.number);

    // The cleanup is driven by the production inventory number, exactly as
    // main.mts does: tracker.cleanupClosedIssueStaleLabels(number).
    for (const number of numbers) {
      const cleanup = await tracker.cleanupClosedIssueStaleLabels(number);
      expect(cleanup.status).toBe("committed");
    }

    // Final invariant: closed + claimant absent + unrelated assignee preserved
    // + all three machine labels absent.
    expect(issue.state).toBe("closed");
    expect(issue.assignees.has("test-bot")).toBe(false);
    expect(issue.assignees.has("human-reviewer")).toBe(true);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(false);
    expect(sink.receipts[0].kind).toBe("committed");
  });

  it("claimant appears after initial read (stale label, claimant absent) => not cleaned", async () => {
    // Initial fresh read: a stale label present but claimant ABSENT. The
    // cleanup removes the stale label; then the claimant appears concurrently
    // before the terminal read. The UNCONDITIONAL terminal invariant must
    // catch the claimant and NOT report cleaned.
    const issue: FakeIssue = {
      number: 1605,
      title: "t",
      body: TRACER_BODY,
      state: "closed",
      labels: new Set(["ready-for-agent", "agent:in-progress"]),
      assignees: new Set(),
    };
    const gh = makeFakeGh({ issues: new Map([[1605, issue]]) });
    // Sabotage: after the label-removal mutation, the terminal read observes
    // the claimant assigned. cleanupClosedStaleLabels views: 1=initial fresh
    // read, 2=post-mutation verifyAfter, 3=terminal revalidation.
    const origRun = gh.run.bind(gh);
    let viewCount = 0;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "view") {
        viewCount++;
        const raw = await origRun(args);
        if (viewCount === 3) {
          const parsed = JSON.parse(raw);
          parsed.assignees.push({ login: "test-bot" });
          return JSON.stringify(parsed);
        }
        return raw;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1605);

    // The claimant appeared after the initial read — cleanup must NOT report
    // cleaned (the terminal invariant is unconditional).
    expect(outcome.status).toBe("indeterminate");
    // No committed cleanup receipt.
    expect(sink.receipts.some((r) => r.kind === "committed")).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// PR #217 round-8 — recoverably ordered closed cleanup, claimant-only residue
// recovery, and typed repository/resource outcomes.
// ---------------------------------------------------------------------------

describe("tracker-adapter — recoverably ordered closed cleanup (round 8)", () => {
  it("removes agent:in-progress LAST, after claimant absence is proven", async () => {
    // Closed issue with agent:in-progress + claimant assigned. The cleanup
    // must remove the claimant BEFORE clearing agent:in-progress (the last
    // machine marker). Verify the edit ordering.
    const issue: FakeIssue = { number: 1701, title: "t", body: "body", state: "closed", labels: new Set(["agent:in-progress"]), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1701, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1701);

    expect(outcome.status).toBe("committed");
    // The claimant was removed BEFORE agent:in-progress (recoverable order).
    const removeAssigneeIdx = gh.editCalls.findIndex((c) => c.includes("--remove-assignee"));
    const removeInProgressIdx = gh.editCalls.findIndex((c) => c.includes("--remove-label") && c.includes("agent:in-progress"));
    expect(removeAssigneeIdx).toBeGreaterThanOrEqual(0);
    expect(removeInProgressIdx).toBeGreaterThan(removeAssigneeIdx);
    // Final invariant: closed + claimant absent + no machine labels.
    expect(issue.state).toBe("closed");
    expect(issue.assignees.size).toBe(0);
    expect(issue.labels.has("agent:in-progress")).toBe(false);
  });

  it("rejects in-progress removal if claimant still assigned (recoverable order)", async () => {
    // Sabotage: after the claimant-removal step, the claimant reappears before
    // the in-progress removal step. The in-progress step's validateBefore must
    // reject — the last machine marker is NOT cleared while the claimant is
    // still assigned.
    const issue: FakeIssue = { number: 1702, title: "t", body: "body", state: "closed", labels: new Set(["agent:in-progress"]), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1702, issue]]) });
    // Sabotage: after the claimant-removal edit, re-add the claimant.
    const origRun = gh.run.bind(gh);
    let removedClaimant = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "edit" && args.includes("--remove-assignee")) {
        removedClaimant = true;
        const r = await origRun(args);
        // Re-add the claimant to simulate concurrent reassignment.
        issue.assignees.add("test-bot");
        return r;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1702);

    // The in-progress removal step's validateBefore rejects (claimant still
    // assigned) — cleanup is NOT reported cleaned.
    expect(outcome.status).toBe("indeterminate");
    expect(issue.labels.has("agent:in-progress")).toBe(true);
  });

  it("removes the claimant FIRST, before implement/blocked markers", async () => {
    // Closed issue with agent:implement + agent:blocked + claimant assigned.
    // The cleanup must remove the claimant BEFORE the non-last machine markers
    // (claimant first, implement/blocked next, in-progress last).
    const issue: FakeIssue = { number: 1703, title: "t", body: "body", state: "closed", labels: new Set(["agent:implement", "agent:blocked"]), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1703, issue]]) });
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const outcome = await tracker.cleanupClosedIssueStaleLabels(1703);

    expect(outcome.status).toBe("committed");
    // The claimant was removed BEFORE the implement/blocked markers.
    const removeAssigneeIdx = gh.editCalls.findIndex((c) => c.includes("--remove-assignee"));
    const removeImplementIdx = gh.editCalls.findIndex((c) => c.includes("--remove-label") && c.includes("agent:implement"));
    const removeBlockedIdx = gh.editCalls.findIndex((c) => c.includes("--remove-label") && c.includes("agent:blocked"));
    expect(removeAssigneeIdx).toBeGreaterThanOrEqual(0);
    expect(removeImplementIdx).toBeGreaterThan(removeAssigneeIdx);
    expect(removeBlockedIdx).toBeGreaterThan(removeAssigneeIdx);
    // Final invariant: closed + claimant absent + no machine labels.
    expect(issue.state).toBe("closed");
    expect(issue.assignees.size).toBe(0);
    expect(issue.labels.has("agent:implement")).toBe(false);
    expect(issue.labels.has("agent:blocked")).toBe(false);
  });
});

describe("tracker-adapter — claimant-only residue recovery (round 8)", () => {
  it("recovers claimant-only closed residue with state-specific receipt evidence, skips without", async () => {
    // Two closed issues assigned to the claimant with NO machine labels.
    // #1801 has a CURRENT, RELEVANT indeterminate cleanup receipt whose
    // observed state matches the live claimant-only residue => recovered.
    // #1802 has no such evidence => skipped (ordinary closed issue).
    const issue1801: FakeIssue = { number: 1801, title: "t", body: "body", state: "closed", labels: new Set(), assignees: new Set(["test-bot"]) };
    const issue1802: FakeIssue = { number: 1802, title: "t", body: "body", state: "closed", labels: new Set(), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1801, issue1801], [1802, issue1802]]) });
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      // Listing closed issues assigned to the claimant.
      if (args[0] === "issue" && args[1] === "list" && args.includes("--assignee")) {
        return JSON.stringify([{ number: 1801 }, { number: 1802 }]);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    // #1801 has a current indeterminate cleanup receipt whose observed state
    // matches the live claimant-only residue (closed + claimant + no labels).
    sink.receipts.push({
      transition: "cleanupClosedStaleLabels", issueNumber: 1801, at: new Date().toISOString(),
      kind: "indeterminate", code: "UNSAFE_TO_RESTORE",
      lastObserved: { number: 1801, state: "closed", labels: [], assignees: ["test-bot"] },
    });
    const tracker = createTrackerAdapter({ gh, receiptSink: sink, readReceipts: () => sink.receipts });

    const result = await tracker.recoverClaimantOnlyClosedResidue(100);

    expect(result.recovered).toBe(1);
    expect(result.skipped).toBe(1);
    expect(result.errors).toEqual([]);
    // #1801 unassigned (state-specific evidence), #1802 left assigned (no evidence).
    expect(issue1801.assignees.has("test-bot")).toBe(false);
    expect(issue1802.assignees.has("test-bot")).toBe(true);
  });

  it("does not unassign an ordinary closed issue without state-specific evidence", async () => {
    // A closed issue assigned to the claimant with NO machine labels and NO
    // state-specific receipt evidence — must NOT be unassigned.
    const issue: FakeIssue = { number: 1803, title: "t", body: "body", state: "closed", labels: new Set(), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1803, issue]]) });
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--assignee")) {
        return JSON.stringify([{ number: 1803 }]);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink, readReceipts: () => sink.receipts });

    const result = await tracker.recoverClaimantOnlyClosedResidue(100);

    expect(result.recovered).toBe(0);
    expect(result.skipped).toBe(1);
    expect(issue.assignees.has("test-bot")).toBe(true);
    expect(gh.editCalls.length).toBe(0);
  });

  it("does not treat a historical committed receipt as ownership proof", async () => {
    // A closed issue assigned to the claimant with a COMMITTED cleanup receipt
    // (the transition settled) — the receipt is NOT indeterminate, so it is
    // NOT recovery evidence. The issue must NOT be unassigned.
    const issue: FakeIssue = { number: 1804, title: "t", body: "body", state: "closed", labels: new Set(), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1804, issue]]) });
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--assignee")) {
        return JSON.stringify([{ number: 1804 }]);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    // A committed cleanup receipt — the transition settled, so no residue
    // recovery is warranted. NOT evidence.
    sink.receipts.push({
      transition: "cleanupClosedStaleLabels", issueNumber: 1804, at: new Date().toISOString(),
      kind: "committed",
      before: { number: 1804, state: "closed", labels: ["agent:in-progress"], assignees: ["test-bot"] },
      after: { number: 1804, state: "closed", labels: [], assignees: [] },
    });
    const tracker = createTrackerAdapter({ gh, receiptSink: sink, readReceipts: () => sink.receipts });

    const result = await tracker.recoverClaimantOnlyClosedResidue(100);

    expect(result.recovered).toBe(0);
    expect(result.skipped).toBe(1);
    expect(issue.assignees.has("test-bot")).toBe(true);
    expect(gh.editCalls.length).toBe(0);
  });

  it("does not treat a mismatched-state indeterminate receipt as ownership proof", async () => {
    // A closed issue assigned to the claimant with an indeterminate cleanup
    // receipt whose observed state does NOT match the live residue (the
    // receipt observed the claimant ABSENT) — NOT recovery evidence.
    const issue: FakeIssue = { number: 1805, title: "t", body: "body", state: "closed", labels: new Set(), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1805, issue]]) });
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--assignee")) {
        return JSON.stringify([{ number: 1805 }]);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    // Indeterminate cleanup receipt, but the observed state has the claimant
    // ABSENT — does not match the live claimant-only residue.
    sink.receipts.push({
      transition: "cleanupClosedStaleLabels", issueNumber: 1805, at: new Date().toISOString(),
      kind: "indeterminate", code: "UNSAFE_TO_RESTORE",
      lastObserved: { number: 1805, state: "closed", labels: [], assignees: [] },
    });
    const tracker = createTrackerAdapter({ gh, receiptSink: sink, readReceipts: () => sink.receipts });

    const result = await tracker.recoverClaimantOnlyClosedResidue(100);

    expect(result.recovered).toBe(0);
    expect(result.skipped).toBe(1);
    expect(issue.assignees.has("test-bot")).toBe(true);
    expect(gh.editCalls.length).toBe(0);
  });

  it("does not treat a non-cleanup/integration indeterminate receipt as ownership proof", async () => {
    // A closed issue assigned to the claimant with an indeterminate CLAIM
    // receipt (not a cleanup/integration transition) — NOT recovery evidence.
    const issue: FakeIssue = { number: 1806, title: "t", body: "body", state: "closed", labels: new Set(), assignees: new Set(["test-bot"]) };
    const gh = makeFakeGh({ issues: new Map([[1806, issue]]) });
    const origRun = gh.run.bind(gh);
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "issue" && args[1] === "list" && args.includes("--assignee")) {
        return JSON.stringify([{ number: 1806 }]);
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    // Indeterminate claimImplementation receipt — not a cleanup/integration
    // transition, so it does not describe a closed residue.
    sink.receipts.push({
      transition: "claimImplementation", issueNumber: 1806, at: new Date().toISOString(),
      kind: "indeterminate", code: "UNSAFE_TO_RESTORE",
      lastObserved: { number: 1806, state: "closed", labels: [], assignees: ["test-bot"] },
    });
    const tracker = createTrackerAdapter({ gh, receiptSink: sink, readReceipts: () => sink.receipts });

    const result = await tracker.recoverClaimantOnlyClosedResidue(100);

    expect(result.recovered).toBe(0);
    expect(result.skipped).toBe(1);
    expect(issue.assignees.has("test-bot")).toBe(true);
    expect(gh.editCalls.length).toBe(0);
  });
});

describe("tracker-adapter — typed repository/resource outcomes (round 8)", () => {
  it("deleteRetiredLabel expected-absent + authoritative 404 => unchanged (not failure)", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let deleted = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "DELETE") { deleted = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/")) {
        // Authoritative HTTP 404 — the label is already absent.
        const err = new Error("gh api ... failed: HTTP 404: Not Found") as any;
        err.stderr = "HTTP 404: Not Found";
        throw err;
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.deleteRetiredLabel("agent:research", false);
    expect(result.status).toBe("unchanged");
    expect(deleted).toBe(false);
  });

  it("updateCanonicalLabelDescription already-matching description => unchanged", async () => {
    const gh = makeFakeGh({ issues: new Map() });
    const origRun = gh.run.bind(gh);
    let patched = false;
    (gh as any).run = async (args: string[]) => {
      if (args[0] === "api" && args[1] === "--method" && args[2] === "PATCH") { patched = true; return ""; }
      if (args[0] === "api" && args[1].includes("/labels/") && args.includes("--jq")) {
        return "new desc"; // already matches the target
      }
      return origRun(args);
    };
    const sink = makeMemoryReceiptSink();
    const tracker = createTrackerAdapter({ gh, receiptSink: sink });

    const result = await tracker.updateCanonicalLabelDescription("wayfinder:task", "new desc");
    expect(result.status).toBe("unchanged");
    expect(patched).toBe(false);
  });
});
