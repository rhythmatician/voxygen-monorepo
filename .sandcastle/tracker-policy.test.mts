import { describe, it, expect } from "vitest";
import {
  detectContradictions,
  getRemovableResidueLabels,
  isResearchEligible,
  isImplementationEligible,
  classifyTicket,
  getWayfinderLabels,
  WAYFINDER_RESEARCH,
  WAYFINDER_PROTOTYPE,
  WAYFINDER_GRILLING,
  WAYFINDER_MAP,
  WAYFINDER_TASK,
  AGENT_IMPLEMENT,
  AGENT_RESEARCH_RETIRED,
  WAYFINDER_PRESERVE_FUTURES_RETIRED,
  READY_FOR_AGENT,
  READY_FOR_HUMAN,
  AGENT_IN_PROGRESS,
  AGENT_BLOCKED,
  CONTRADICTION_CODES,
  type IssueInput,
} from "./tracker-policy.mts";
import { isEligible, isResearchEligible as dispatchIsResearchEligible } from "./dispatch.mts";
import { TRACER_BODY } from "./fixtures.mts";

function issue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 1,
    title: "Test",
    state: "open",
    labels: ["ready-for-agent", "agent:implement"],
    assignees: [],
    body: TRACER_BODY,
    blockedByCount: 0,
    ...overrides,
  };
}

describe("tracker-policy — canonical work-item table", () => {
  it("Wayfinder map — never executable", () => {
    const m: IssueInput = { number: 1, title: "map", state: "open", labels: ["wayfinder:map"], assignees: [], blockedByCount: 0, body: "map" };
    expect(isResearchEligible(m).eligible).toBe(false);
    expect(isImplementationEligible(m).eligible).toBe(false);
    expect(classifyTicket(m).profile).toBe("ineligible");
  });

  it("Wayfinder Research — AFK via wayfinder:research alone", () => {
    const r: IssueInput = { number: 2, title: "research", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 0, body: "question Part of #1" };
    expect(isResearchEligible(r).eligible).toBe(true);
    expect(isImplementationEligible(r).eligible).toBe(false);
    expect(classifyTicket(r).profile).toBe("research");
    expect(classifyTicket(r).eligible).toBe(true);
  });

  it("Wayfinder Prototype — HITL only", () => {
    const p: IssueInput = { number: 3, title: "proto", state: "open", labels: ["wayfinder:prototype"], assignees: [], blockedByCount: 0, body: "proto" };
    expect(isResearchEligible(p).eligible).toBe(false);
    expect(isImplementationEligible(p).eligible).toBe(false);
    expect(classifyTicket(p).profile).toBe("ineligible");
    // With ready-for-agent should be contradiction
    const p2 = { ...p, labels: ["wayfinder:prototype", "ready-for-agent", "agent:implement"], body: TRACER_BODY };
    expect(detectContradictions(p2).contradictions.some(c => c.code === CONTRADICTION_CODES.PROTOTYPE_WITH_READY_AGENT)).toBe(true);
  });

  it("Wayfinder Grilling — HITL only", () => {
    const g: IssueInput = { number: 4, title: "grill", state: "open", labels: ["wayfinder:grilling"], assignees: [], blockedByCount: 0, body: "grill" };
    expect(isImplementationEligible(g).eligible).toBe(false);
    const g2 = { ...g, labels: ["wayfinder:grilling", "ready-for-agent", "agent:implement"], body: TRACER_BODY };
    expect(detectContradictions(g2).contradictions.length).toBeGreaterThan(0);
  });

  it("AFK Wayfinder Task — wayfinder:task + ready-for-agent + agent:implement + tracer", () => {
    const t: IssueInput = { number: 5, title: "afk task", state: "open", labels: ["wayfinder:task", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: TRACER_BODY };
    expect(isImplementationEligible(t).eligible).toBe(true);
    expect(classifyTicket(t).profile).toBe("implementation");
  });

  it("HITL Wayfinder Task — wayfinder:task + ready-for-human, no implement", () => {
    const t: IssueInput = { number: 6, title: "hitl task", state: "open", labels: ["wayfinder:task", "ready-for-human"], assignees: [], blockedByCount: 0, body: "task" };
    expect(isImplementationEligible(t).eligible).toBe(false);
    expect(classifyTicket(t).profile).toBe("ineligible"); // not implementation eligible, not research
    // But task classification should be valid via getTaskReadiness
    expect(detectContradictions(t).contradictions.length).toBe(0);
  });

  it("Ordinary AFK implementation — ready-for-agent + agent:implement + tracer", () => {
    const i = issue({ labels: ["ready-for-agent", "agent:implement"], body: TRACER_BODY });
    expect(isImplementationEligible(i).eligible).toBe(true);
    expect(classifyTicket(i).profile).toBe("implementation");
  });

  it("Ordinary HITL issue — ready-for-human only, not AFK", () => {
    const h: IssueInput = { number: 7, title: "hitl", state: "open", labels: ["ready-for-human"], assignees: [], blockedByCount: 0, body: "human" };
    expect(isImplementationEligible(h).eligible).toBe(false);
    expect(isResearchEligible(h).eligible).toBe(false);
    expect(classifyTicket(h).profile).toBe("ineligible");
  });
});

describe("tracker-policy — every contradiction", () => {
  const tracer = TRACER_BODY;
  it("multiple wayfinder types", () => {
    const i: IssueInput = { number: 10, title: "x", state: "open", labels: ["wayfinder:research", "wayfinder:task", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    const v = detectContradictions(i);
    expect(v.contradictions.some(c => c.code === CONTRADICTION_CODES.MULTIPLE_WAYFINDER)).toBe(true);
    expect(isImplementationEligible(i).eligible).toBe(false);
  });
  it("wayfinder:research + agent:implement", () => {
    const i: IssueInput = { number: 11, title: "x", state: "open", labels: ["wayfinder:research", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.RESEARCH_WITH_IMPLEMENT)).toBe(true);
  });
  it("wayfinder:prototype + ready-for-agent", () => {
    const i: IssueInput = { number: 12, title: "x", state: "open", labels: ["wayfinder:prototype", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.PROTOTYPE_WITH_READY_AGENT)).toBe(true);
  });
  it("wayfinder:prototype + agent:implement", () => {
    const i: IssueInput = { number: 13, title: "x", state: "open", labels: ["wayfinder:prototype", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    // Will have both prototype with ready and implement — check implement contradiction
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.PROTOTYPE_WITH_IMPLEMENT)).toBe(true);
  });
  it("wayfinder:grilling + ready-for-agent", () => {
    const i: IssueInput = { number: 14, title: "x", state: "open", labels: ["wayfinder:grilling", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.GRILLING_WITH_READY_AGENT)).toBe(true);
  });
  it("wayfinder:grilling + agent:implement", () => {
    const i: IssueInput = { number: 15, title: "x", state: "open", labels: ["wayfinder:grilling", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.GRILLING_WITH_IMPLEMENT)).toBe(true);
  });
  it("wayfinder:task + ready-for-agent + ready-for-human", () => {
    const i: IssueInput = { number: 16, title: "x", state: "open", labels: ["wayfinder:task", "ready-for-agent", "ready-for-human", "agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.TASK_BOTH_READY)).toBe(true);
  });
  it("agent:implement without ready-for-agent", () => {
    const i: IssueInput = { number: 17, title: "x", state: "open", labels: ["agent:implement"], assignees: [], blockedByCount: 0, body: tracer };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.IMPLEMENT_WITHOUT_READY)).toBe(true);
    expect(isImplementationEligible(i).eligible).toBe(false);
  });
  it("agent:implement + agent:in-progress", () => {
    const i: IssueInput = { number: 18, title: "x", state: "open", labels: ["ready-for-agent", "agent:implement", "agent:in-progress"], assignees: [], blockedByCount: 0, body: tracer };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.IMPLEMENT_WITH_IN_PROGRESS)).toBe(true);
  });
  it("retired agent:research", () => {
    const i: IssueInput = { number: 19, title: "x", state: "open", labels: ["wayfinder:research", "agent:research"], assignees: [], blockedByCount: 0, body: "q" };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.RETIRED_AGENT_RESEARCH)).toBe(true);
  });
  it("retired wayfinder:preserve-futures", () => {
    const i: IssueInput = { number: 20, title: "x", state: "open", labels: ["wayfinder:preserve-futures"], assignees: [], blockedByCount: 0, body: "q" };
    expect(detectContradictions(i).contradictions.some(c => c.code === CONTRADICTION_CODES.RETIRED_PRESERVE_FUTURES)).toBe(true);
  });
});

describe("tracker-policy — irrelevant Wayfinder metadata", () => {
  it("ordinary implementation with irrelevant noncontradictory metadata stays eligible", () => {
    // Ordinary implementation should not be affected by having extra non-wayfinder labels like enhancement, or Sandcastle
    const i = issue({ labels: ["ready-for-agent", "agent:implement", "enhancement", "Sandcastle"], body: TRACER_BODY });
    expect(isImplementationEligible(i).eligible).toBe(true);
    // Also dispatch should agree
    expect(isEligible(i).eligible).toBe(true);
  });
});

describe("tracker-policy — Research from wayfinder:research alone", () => {
  it("research eligible from wayfinder:research alone, no agent:research needed", () => {
    const r: IssueInput = { number: 30, title: "r", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 0, body: "question" };
    expect(isResearchEligible(r).eligible).toBe(true);
    expect(dispatchIsResearchEligible(r).eligible).toBe(true);
  });
  it("research with ready-for-agent residue still eligible but residue removable", () => {
    const r: IssueInput = { number: 31, title: "r", state: "open", labels: ["wayfinder:research", "ready-for-agent"], assignees: [], blockedByCount: 0, body: "q" };
    expect(isResearchEligible(r).eligible).toBe(true);
    expect(getRemovableResidueLabels(r)).toContain("ready-for-agent");
  });
  it("research must be open, unassigned, no in-progress/blocked, blocked_by 0", () => {
    const base: IssueInput = { number: 32, title: "r", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 0, body: "q" };
    expect(isResearchEligible({ ...base, state: "closed" }).eligible).toBe(false);
    expect(isResearchEligible({ ...base, assignees: ["someone"] }).eligible).toBe(false);
    expect(isResearchEligible({ ...base, labels: ["wayfinder:research", "agent:in-progress"] }).eligible).toBe(false);
    expect(isResearchEligible({ ...base, labels: ["wayfinder:research", "agent:blocked"] }).eligible).toBe(false);
    expect(isResearchEligible({ ...base, blockedByCount: 1 }).eligible).toBe(false);
    expect(isResearchEligible({ ...base, blockedByCount: undefined }).eligible).toBe(false);
  });
});

describe("tracker-policy — production dispatch uses same classifier", () => {
  it("dispatch delegates to tracker-policy", () => {
    const impl = issue({ labels: ["ready-for-agent", "agent:implement"], body: TRACER_BODY });
    expect(isEligible(impl).eligible).toBe(true);
    expect(isImplementationEligible(impl).eligible).toBe(true);
    const research: IssueInput = { number: 40, title: "r", state: "open", labels: ["wayfinder:research"], assignees: [], blockedByCount: 0, body: "q" };
    expect(dispatchIsResearchEligible(research).eligible).toBe(true);
    expect(isResearchEligible(research).eligible).toBe(true);
    // Contradiction case
    const contra: IssueInput = { number: 41, title: "c", state: "open", labels: ["wayfinder:research", "ready-for-agent", "agent:implement"], assignees: [], blockedByCount: 0, body: TRACER_BODY };
    expect(classifyTicket(contra).profile).toBe("conflicting");
    expect(detectContradictions(contra).contradictions.length).toBeGreaterThan(0);
  });
});
