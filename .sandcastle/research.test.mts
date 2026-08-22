import { describe, it, expect, vi } from "vitest";
import {
  isEligible,
  isResearchEligible,
  classifyTicket,
  CONFLICT_BOTH_LABELS_REASON,
  RESEARCH_REQUIRES_WAYFINDER_REASON,
  RESEARCH_LABEL,
  WAYFINDER_RESEARCH_LABEL,
  branchForIssue,
} from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";
import { TRACER_BODY } from "./fixtures.mts";
import {
  researchResultSchema,
  extractResearchResult,
  parseResearchResult,
  formatResearchResultForComment,
  type ResearchResult,
} from "./research-result.mts";
import {
  resolveResearchSandboxEnv,
  getResearchEnvironment,
} from "./sandbox-token-env.mts";

function issue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 1,
    title: "Test",
    state: "open",
    labels: ["agent:implement"],
    assignees: [],
    body: TRACER_BODY,
    blockedByCount: 0,
    ...overrides,
  };
}

function researchIssue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 10,
    title: "Research question",
    state: "open",
    labels: ["wayfinder:research", "agent:research"],
    assignees: [],
    body: "Part of #22\nResearch the terrain signal",
    blockedByCount: 0,
    ...overrides,
  };
}

describe("Research eligibility", () => {
  it("wayfinder:research + agent:research open unassigned unblocked is eligible", () => {
    const r = researchIssue();
    expect(isResearchEligible(r)).toEqual({ eligible: true });
    expect(classifyTicket(r).profile).toBe("research");
    expect(classifyTicket(r).eligible).toBe(true);
  });

  it("ready-for-agent + wayfinder:research without agent:research is not executed", () => {
    const r = researchIssue({ labels: ["wayfinder:research", "ready-for-agent"] });
    expect(isResearchEligible(r).eligible).toBe(false);
    expect(classifyTicket(r).eligible).toBe(false);
    // Not research eligible, and also not implement eligible (missing implement)
    expect(isEligible(r).eligible).toBe(false);
  });

  it("conflicting agent:implement + agent:research fails closed before claim", () => {
    const c = issue({ labels: ["agent:implement", "agent:research", "wayfinder:research"], body: TRACER_BODY });
    expect(isEligible(c).eligible).toBe(false);
    if (!isEligible(c).eligible) expect((isEligible(c) as { reason: string }).reason).toBe(CONFLICT_BOTH_LABELS_REASON);
    expect(isResearchEligible(c).eligible).toBe(false);
    if (!isResearchEligible(c).eligible) expect((isResearchEligible(c) as { reason: string }).reason).toBe(CONFLICT_BOTH_LABELS_REASON);
    expect(classifyTicket(c).profile).toBe("conflicting");
    expect(classifyTicket(c).eligible).toBe(false);
  });

  it("agent:research without wayfinder:research fails closed", () => {
    const r = issue({ labels: ["agent:research"], body: "something" });
    expect(isResearchEligible(r).eligible).toBe(false);
    expect((isResearchEligible(r) as { reason: string }).reason).toBe(RESEARCH_REQUIRES_WAYFINDER_REASON);
    expect(isEligible(r).eligible).toBe(false);
    expect((isEligible(r) as { reason: string }).reason).toBe(RESEARCH_REQUIRES_WAYFINDER_REASON);
    expect(classifyTicket(r).profile).toBe("conflicting");
  });

  it("deterministic classification distinguishes implementation, research, ineligible", () => {
    const impl = issue({ number: 1, labels: ["agent:implement"], body: TRACER_BODY, blockedByCount: 0 });
    const res = researchIssue({ number: 2 });
    const inelig = issue({ number: 3, labels: ["ready-for-agent"], body: "hi" });
    const conflict = issue({ number: 4, labels: ["agent:implement", "agent:research"], body: "hi" });
    expect(classifyTicket(impl).profile).toBe("implementation");
    expect(classifyTicket(impl).eligible).toBe(true);
    expect(classifyTicket(res).profile).toBe("research");
    expect(classifyTicket(res).eligible).toBe(true);
    expect(classifyTicket(inelig).profile).toBe("ineligible");
    expect(classifyTicket(inelig).eligible).toBe(false);
    expect(classifyTicket(conflict).profile).toBe("conflicting");
  });

  it("existing agent:implement decisions unchanged (no regression)", () => {
    // Valid implement with tracer still eligible
    const impl = issue({ labels: ["agent:implement"], body: TRACER_BODY });
    expect(isEligible(impl).eligible).toBe(true);
    // Missing tracer still ineligible with tracer reason, not conflicting
    const missingTracer = issue({ labels: ["agent:implement"], body: "no tracer" });
    const res = isEligible(missingTracer);
    expect(res.eligible).toBe(false);
    if (!res.eligible) expect(res.reason).toContain("tracer contract");
    // Forbidden wayfinder still blocked
    const forbidden = issue({ labels: ["agent:implement", "wayfinder:prototype"], body: TRACER_BODY });
    expect(isEligible(forbidden).eligible).toBe(false);
  });

  it("blocked, assigned, in-progress, unknown blockedBy are ineligible for research", () => {
    expect(isResearchEligible(researchIssue({ blockedByCount: 1 })).eligible).toBe(false);
    expect(isResearchEligible(researchIssue({ blockedByCount: undefined })).eligible).toBe(false);
    expect(isResearchEligible(researchIssue({ assignees: ["someone"] })).eligible).toBe(false);
    expect(isResearchEligible(researchIssue({ labels: ["wayfinder:research", "agent:research", "agent:in-progress"] })).eligible).toBe(false);
    expect(isResearchEligible(researchIssue({ labels: ["wayfinder:research", "agent:research", "agent:blocked"] })).eligible).toBe(false);
    expect(isResearchEligible(researchIssue({ state: "closed" })).eligible).toBe(false);
  });

  it("research does not require tracer contract", () => {
    const r = researchIssue({ body: "Part of #22\nsimple research without tracer keywords" });
    expect(isResearchEligible(r).eligible).toBe(true);
  });
});

describe("Structured research result contract", () => {
  it("valid evidence-backed conclusion may state unknown/insufficient evidence and still succeed", () => {
    const result: ResearchResult = {
      summary: "Insufficient evidence to answer — no ground-truth samples found",
      findings: [],
      recommendation: "Collect stratified samples",
      uncertainties: ["no L4 ground surface samples", "missing biome registry version"],
      followUps: ["open sampling ticket"],
    };
    expect(researchResultSchema.safeParse(result).success).toBe(true);
    const extracted = extractResearchResult({ stdout: `<research>${JSON.stringify(result)}</research>` });
    expect(extracted).toEqual(result);
  });

  it("rejects missing/invalid structured output", () => {
    expect(parseResearchResult({})).toBeNull();
    expect(parseResearchResult({ summary: "", findings: [], recommendation: "", uncertainties: [], followUps: [] })).toBeNull();
    expect(parseResearchResult({ summary: "s", findings: [{ claim: "", evidence: "e", source: "s" }], recommendation: "r", uncertainties: [], followUps: [] })).toBeNull();
    expect(extractResearchResult({ stdout: "no tag here" })).toBeNull();
    expect(extractResearchResult({ stdout: "<research>not json</research>" })).toBeNull();
    expect(extractResearchResult({ stdout: "<research>{\"summary\":\"s\"}</research>" })).toBeNull();
  });

  it("requires claim/evidence/source non-empty for each finding", () => {
    const valid: ResearchResult = {
      summary: "found",
      findings: [{ claim: "X is Y", evidence: "line 42", source: "src/foo.ts" }],
      recommendation: "do Z",
      uncertainties: [],
      followUps: [],
    };
    expect(researchResultSchema.safeParse(valid).success).toBe(true);
    const invalid = { ...valid, findings: [{ claim: "", evidence: "e", source: "s" }] };
    expect(researchResultSchema.safeParse(invalid).success).toBe(false);
  });

  it("extracts last <research> block", () => {
    const r1: ResearchResult = { summary: "first", findings: [], recommendation: "r1", uncertainties: [], followUps: [] };
    const r2: ResearchResult = { summary: "second", findings: [], recommendation: "r2", uncertainties: [], followUps: [] };
    const text = `<research>${JSON.stringify(r1)}</research> noise <research>${JSON.stringify(r2)}</research>`;
    expect(extractResearchResult({ stdout: text })?.summary).toBe("second");
  });
});

describe("Host-only GitHub writes and env isolation", () => {
  it("research sandbox env scrubs GH_TOKEN (no GitHub write credential)", () => {
    const env = resolveResearchSandboxEnv("meta123");
    expect(env.GH_TOKEN).toBe("");
    expect(env.GITHUB_TOKEN).toBe("");
    expect(env.META_API_KEY).toBe("meta123");
  });

  it("getResearchEnvironment profile seam uses sandcastle:voxygen-monorepo image and scrubbed env", () => {
    const prof = getResearchEnvironment("metaXYZ");
    expect(prof.imageName).toBe("sandcastle:voxygen-monorepo");
    expect(prof.env.GH_TOKEN).toBe("");
    expect(prof.env.META_API_KEY).toBe("metaXYZ");
  });

  it("formatResearchResultForComment produces host-side comment (no free-form partial)", () => {
    const result: ResearchResult = {
      summary: "summary text",
      findings: [{ claim: "claim", evidence: "evidence", source: "source.ts" }],
      recommendation: "rec",
      uncertainties: ["u1"],
      followUps: ["f1"],
    };
    const md = formatResearchResultForComment(result);
    expect(md).toContain("summary text");
    expect(md).toContain("claim");
    expect(md).toContain("source.ts");
    expect(md).toContain("rec");
  });
});

describe("Research lifecycle — pure helpers", () => {
  it("extractParentMapId is available via research-lifecycle (behavioral, not source text)", async () => {
    const { extractParentMapId } = await import("./research-lifecycle.mts");
    expect(extractParentMapId("Part of #22\nhello")).toBe("22");
    expect(extractParentMapId("no parent")).toBeNull();
    expect(extractParentMapId(undefined)).toBeNull();
  });
});
