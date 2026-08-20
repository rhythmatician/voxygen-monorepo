import { describe, it, expect } from "vitest";
import {
  reviewVerdictSchema,
  parseReviewVerdict,
  parseVerdictFromText,
  extractVerdict,
  isVerdictApproved,
  gateBranchesByVerdict,
  verdictFixture,
} from "./review-verdict.mts";
import { partitionWorkers } from "./dispatch.mts";

describe("ReviewVerdict schema", () => {
  it("valid approved verdict parses", () => {
    const v = verdictFixture({
      approved: true,
      findings: [],
      acceptanceCriteriaMet: [
        { criterion: "criterion A", met: true, evidence: "src/a.ts:1" },
        { criterion: "criterion B", met: true },
      ],
    });
    expect(reviewVerdictSchema.safeParse(v).success).toBe(true);
    expect(parseReviewVerdict(v)).toEqual(v);
  });

  it("valid rejected verdict with blocking finding parses", () => {
    const v = verdictFixture({
      approved: false,
      findings: [{ message: "missing X for criterion A", severity: "blocking" }],
      acceptanceCriteriaMet: [{ criterion: "criterion A", met: false, evidence: "no code" }],
      summary: "diverges from contract",
    });
    expect(parseReviewVerdict(v)).toEqual(v);
  });

  it("rejects missing approved", () => {
    expect(parseReviewVerdict({ findings: [], acceptanceCriteriaMet: [] })).toBeNull();
  });
});

describe("parseVerdictFromText", () => {
  it("extracts verdict from <verdict> tags", () => {
    const v = verdictFixture({ approved: true });
    const text = `some review\n<verdict>${JSON.stringify(v)}</verdict>\nmore`;
    expect(parseVerdictFromText(text)).toEqual(v);
  });

  it("returns null when no tags", () => {
    expect(parseVerdictFromText("no verdict here")).toBeNull();
  });

  it("returns null on invalid JSON inside tags", () => {
    expect(parseVerdictFromText("<verdict>{not json}</verdict>")).toBeNull();
  });

  it("uses the last verdict when the reviewer self-corrects", () => {
    const approved = verdictFixture({ approved: true, summary: "initial review" });
    const rejected = verdictFixture({
      approved: false,
      findings: [{ message: "verification failed", severity: "blocking" }],
      acceptanceCriteriaMet: [{ criterion: "tests pass", met: false }],
      summary: "final review",
    });
    const text = `<verdict>${JSON.stringify(approved)}</verdict>\n<verdict>${JSON.stringify(rejected)}</verdict>`;

    expect(parseVerdictFromText(text)).toEqual(rejected);
  });
});

describe("extractVerdict", () => {
  it("extracts a verdict from the stdout returned by sandbox.run", () => {
    const verdict = verdictFixture({ approved: false, summary: "from sandbox stdout" });

    expect(extractVerdict({ stdout: `<verdict>${JSON.stringify(verdict)}</verdict>` })).toEqual(verdict);
  });

  it("prefers validated structured output when a future sandbox result provides it", () => {
    const structured = verdictFixture({ approved: false, summary: "structured" });
    const stdout = verdictFixture({ approved: true, summary: "stdout" });

    expect(
      extractVerdict({
        output: structured,
        stdout: `<verdict>${JSON.stringify(stdout)}</verdict>`,
      }),
    ).toEqual(structured);
  });

  it("extracts a verdict from the Muse JSONL returned by stock Sandcastle", () => {
    const verdict = verdictFixture({
      approved: false,
      findings: [{ message: "implementation does not match the synthetic contract", severity: "blocking" }],
      acceptanceCriteriaMet: [{ criterion: "focused test passes", met: false, evidence: "test file missing" }],
      summary: "captured reviewer rejection",
    });
    const taggedVerdict = `<verdict>${JSON.stringify(verdict)}</verdict>`;
    const stdout = [
      JSON.stringify({ payload_type: "run.output.delta", payload: { kind: "run_output_delta", text: taggedVerdict.slice(0, 17) } }),
      JSON.stringify({ payload_type: "run.output.delta", payload: { kind: "run_output_delta", delta: taggedVerdict.slice(17) } }),
      JSON.stringify({ payload_type: "task.lifecycle.completed", payload: { kind: "task_lifecycle_completed" } }),
    ].join("\n");

    expect(extractVerdict({ stdout })).toEqual(verdict);
  });
});

describe("isVerdictApproved", () => {
  it("approved true with all criteria met and no blocking findings is approved", () => {
    const v = verdictFixture({
      approved: true,
      findings: [],
      acceptanceCriteriaMet: [{ criterion: "A", met: true }],
    });
    expect(isVerdictApproved(v)).toBe(true);
  });

  it("approved false is not approved", () => {
    const v = verdictFixture({ approved: false, findings: [{ message: "block", severity: "blocking" }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] });
    expect(isVerdictApproved(v)).toBe(false);
  });

  it("approved true but blocking finding makes it not approved (defensive)", () => {
    const v = verdictFixture({
      approved: true,
      findings: [{ message: "blocking", severity: "blocking" }],
      acceptanceCriteriaMet: [{ criterion: "A", met: true }],
    });
    expect(isVerdictApproved(v)).toBe(false);
  });

  it("approved true but unmet criterion makes it not approved", () => {
    const v = verdictFixture({
      approved: true,
      findings: [],
      acceptanceCriteriaMet: [{ criterion: "A", met: false }],
    });
    expect(isVerdictApproved(v)).toBe(false);
  });

  it("null verdict is not approved", () => {
    expect(isVerdictApproved(null)).toBe(false);
    expect(isVerdictApproved(undefined)).toBe(false);
  });

  it("nit findings do not block when approved true and all criteria met", () => {
    const v = verdictFixture({
      approved: true,
      findings: [{ message: "nit", severity: "nit" }],
      acceptanceCriteriaMet: [{ criterion: "A", met: true }],
    });
    expect(isVerdictApproved(v)).toBe(true);
  });
});

describe("gateBranchesByVerdict — tracer bullet fixtures", () => {
  // Sample review artifacts: both approved and rejected fixtures demonstrate behavior
  const issues = [
    { id: "57", branch: "sandcastle/issue-57" },
    { id: "58", branch: "sandcastle/issue-58" },
    { id: "59", branch: "sandcastle/issue-59" },
  ];

  it("approved=true branch is eligible for merger, approved=false is not", () => {
    const approvedVerdict = verdictFixture({
      approved: true,
      findings: [],
      acceptanceCriteriaMet: [{ criterion: "criterion 1", met: true, evidence: "src/foo.ts:10" }],
      summary: "all criteria met",
    });
    const rejectedVerdict = verdictFixture({
      approved: false,
      findings: [{ message: "criterion 1 not met: missing implementation", severity: "blocking" }],
      acceptanceCriteriaMet: [{ criterion: "criterion 1", met: false, evidence: "no code covers X" }],
      summary: "Implementation diverges from acceptance: X missing",
    });
    const pendingVerdict: null = null;

    const verdicts: Array<ReturnType<typeof verdictFixture> | null> = [approvedVerdict, rejectedVerdict, pendingVerdict];
    const { approved, blocked } = gateBranchesByVerdict(issues, verdicts);

    expect(approved.map((a) => a.id)).toEqual(["57"]);
    expect(blocked.map((b) => b.id).sort()).toEqual(["58", "59"]);
    // Approved branch goes to merger
    expect(approved[0].branch).toBe("sandcastle/issue-57");
    // Blocked branches are preserved, marked agent:blocked, not merged
    expect(blocked.find((b) => b.id === "58")!.reason).toContain("reviewer rejected");
    expect(blocked.find((b) => b.id === "59")!.reason).toContain("no machine-readable verdict");
  });

  it("partitionWorkers + verdict gating: rejected branch excluded from completedBranches → merger", () => {
    // Simulate factory main.mts logic: first partition by commits, then gate by verdict
    const workerIssues = [
      { id: "101", branch: "sandcastle/issue-101" },
      { id: "102", branch: "sandcastle/issue-102" },
    ];
    const settled = [
      { status: "fulfilled" as const, commits: ["c1"] },
      { status: "fulfilled" as const, commits: ["c2"] },
    ];
    const { completed } = partitionWorkers(workerIssues, settled);
    expect(completed).toHaveLength(2);

    // Now gate by verdict: 101 approved, 102 rejected
    const verdicts = [
      verdictFixture({ approved: true, acceptanceCriteriaMet: [{ criterion: "A", met: true }] }),
      verdictFixture({ approved: false, findings: [{ message: "mismatch", severity: "blocking" }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] }),
    ];
    const gated = gateBranchesByVerdict(completed, verdicts);
    expect(gated.approved.map((a) => a.id)).toEqual(["101"]);
    expect(gated.blocked.map((b) => b.id)).toEqual(["102"]);
    // Merger would receive only approved branches
    const completedBranches = gated.approved.map((a) => a.branch);
    expect(completedBranches).toEqual(["sandcastle/issue-101"]);
    expect(completedBranches).not.toContain("sandcastle/issue-102");
  });

  it("fixture: matching implementation returns approved=true is eligible for integration", () => {
    const issue = { id: "201", branch: "sandcastle/issue-201" };
    const verdict = verdictFixture({
      approved: true,
      findings: [],
      acceptanceCriteriaMet: [
        { criterion: "Reviewer prompt requires original issue contract in fresh context", met: true, evidence: ".sandcastle/review-prompt.md contains fresh context" },
        { criterion: "ReviewVerdict gates merger", met: true, evidence: "gateBranchesByVerdict filters correctly" },
      ],
      summary: "Implementation matches acceptance criteria",
    });
    const { approved, blocked } = gateBranchesByVerdict([issue], [verdict]);
    expect(approved).toHaveLength(1);
    expect(blocked).toHaveLength(0);
    expect(isVerdictApproved(verdict)).toBe(true);
  });

  it("fixture: diverging implementation returns approved=false is blocked, preserved, not merged", () => {
    const issue = { id: "202", branch: "sandcastle/issue-202" };
    const verdict = verdictFixture({
      approved: false,
      findings: [{ message: "Implementation defines intent from diff, not contract", severity: "blocking" }],
      acceptanceCriteriaMet: [
        { criterion: "Reviewer prompt requires original issue contract in fresh context", met: false, evidence: "prompt still says sole-diff intent (forbidden phrase)" },
        { criterion: "ReviewVerdict gates merger", met: false, evidence: "no gate" },
      ],
      summary: "Implementation diverges — reviewer would catch via contract vs diff compare",
    });
    const { approved, blocked } = gateBranchesByVerdict([issue], [verdict]);
    expect(approved).toHaveLength(0);
    expect(blocked).toHaveLength(1);
    expect(isVerdictApproved(verdict)).toBe(false);
    // Factory would: preserve branch, mark agent:blocked, post findings, not merge
    expect(blocked[0].reason).toContain("reviewer rejected");
    expect(blocked[0].verdict?.findings[0].message).toContain("defines intent from diff");
  });
});

describe("review prompt no longer contains sole intent phrase", () => {
  it("documents that grep for forbidden phrase must be zero (enforced via file content check)", async () => {
    const fs = await import("node:fs");
    const prompt = fs.readFileSync(new URL("./review-prompt.md", import.meta.url), "utf8");
    // Exact forbidden phrase from issue must not appear
    const forbidden = ["Understand", "intent", "from", "diff", "and", "commits"].join(" ");
    expect(prompt).not.toContain(forbidden);
    // Must explicitly require fresh context + original contract
    expect(prompt).toContain("original issue contract");
    expect(prompt).toContain("fresh context");
    expect(prompt).toContain("acceptance criteria");
    // Must define ReviewVerdict and gate
    expect(prompt).toContain("ReviewVerdict");
    expect(prompt).toContain("<verdict>");
    expect(prompt).toContain("approved");
  });
});
