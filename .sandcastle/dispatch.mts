import {
  isImplementationEligible,
  isResearchEligible as policyIsResearchEligible,
  classifyTicket as policyClassifyTicket,
  branchForIssue as policyBranchForIssue,
  detectContradictions,
  WAYFINDER_RESEARCH as POLICY_WAYFINDER_RESEARCH,
  WAYFINDER_TASK as POLICY_WAYFINDER_TASK,
  WAYFINDER_MAP as POLICY_WAYFINDER_MAP,
  AGENT_IMPLEMENT as POLICY_AGENT_IMPLEMENT,
  AGENT_RESEARCH_RETIRED as POLICY_AGENT_RESEARCH,
  READY_FOR_AGENT as POLICY_READY_FOR_AGENT,
  AGENT_IN_PROGRESS as POLICY_AGENT_IN_PROGRESS,
  AGENT_BLOCKED as POLICY_AGENT_BLOCKED,
  CONTRADICTION_CODES,
  type IssueInput as PolicyIssueInput,
  type TicketClassification as PolicyTicketClassification,
} from "./tracker-policy.mts";

/**
 * Factory dispatch — deterministic eligibility for AFK implementation and research.
 * Delegates to tracker-policy (single production seam per ADR 0010).
 * This module is a thin adapter preserving the historic dispatch import surface.
 */

export type IssueInput = PolicyIssueInput;

export const REQUIRED_LABEL = POLICY_AGENT_IMPLEMENT;
export const RESEARCH_LABEL = POLICY_AGENT_RESEARCH;
export const WAYFINDER_RESEARCH_LABEL = POLICY_WAYFINDER_RESEARCH;
export const IN_PROGRESS_LABEL = POLICY_AGENT_IN_PROGRESS;
export const BLOCKED_LABEL = POLICY_AGENT_BLOCKED;

export const CONFLICT_BOTH_LABELS_REASON =
  "conflicting authorization labels agent:implement and agent:research — fail closed" as const;
export const RESEARCH_REQUIRES_WAYFINDER_REASON =
  "agent:research requires wayfinder:research — fail closed" as const;

export const FORBIDDEN_WAYFINDER_LABELS = [
  "wayfinder:prototype",
  "wayfinder:grilling",
  "wayfinder:map",
  "wayfinder:preserve-futures",
] as const;

export const WAYFINDER_TASK_LABEL = POLICY_WAYFINDER_TASK;
// Retained for historical reference only — machine authorization no longer parses this sentence
export const WAYFINDER_TASK_MAP_SIGNAL = "Execution is carried into this map" as const;

export type EligibilityResult =
  | { eligible: true }
  | { eligible: false; reason: string; code?: string };

export function branchForIssue(id: number | string): string {
  return policyBranchForIssue(id);
}

export function isEligible(issue: IssueInput): EligibilityResult {
  const result = isImplementationEligible(issue);
  if (result.eligible) return { eligible: true };
  const r = result as { reason: string; code?: string };
  if (r.code === CONTRADICTION_CODES.RETIRED_AGENT_RESEARCH && issue.labels.includes(REQUIRED_LABEL) && issue.labels.includes(RESEARCH_LABEL)) {
    return { eligible: false, reason: CONFLICT_BOTH_LABELS_REASON, code: r.code };
  }
  if (r.code === CONTRADICTION_CODES.RETIRED_AGENT_RESEARCH && !issue.labels.includes(POLICY_WAYFINDER_RESEARCH)) {
    return { eligible: false, reason: RESEARCH_REQUIRES_WAYFINDER_REASON, code: r.code };
  }
  return { eligible: false, reason: r.reason, code: r.code };
}

export function isResearchEligible(issue: IssueInput): EligibilityResult {
  const result = policyIsResearchEligible(issue);
  if (result.eligible) return { eligible: true };
  const r = result as { reason: string; code?: string };
  if (r.code === CONTRADICTION_CODES.RETIRED_AGENT_RESEARCH && issue.labels.includes(RESEARCH_LABEL) && !issue.labels.includes(WAYFINDER_RESEARCH_LABEL)) {
    return { eligible: false, reason: RESEARCH_REQUIRES_WAYFINDER_REASON, code: r.code };
  }
  if (issue.labels.includes(REQUIRED_LABEL) && issue.labels.includes(RESEARCH_LABEL)) {
    return { eligible: false, reason: CONFLICT_BOTH_LABELS_REASON, code: r.code };
  }
  return { eligible: false, reason: r.reason, code: r.code };
}

export function filterEligible(issues: IssueInput[]): IssueInput[] {
  return issues.filter((i) => isEligible(i).eligible);
}

export function filterResearchEligible(issues: IssueInput[]): IssueInput[] {
  return issues.filter((i) => isResearchEligible(i).eligible);
}

export type TicketProfile = "implementation" | "research" | "conflicting" | "ineligible";

export interface TicketClassification {
  profile: TicketProfile;
  eligible: boolean;
  reason?: string;
  code?: string;
}

export function classifyTicket(issue: IssueInput): TicketClassification {
  const c = policyClassifyTicket(issue);
  return {
    profile: c.profile,
    eligible: c.eligible,
    reason: c.reason,
    code: (c as { code?: string }).code,
  };
}

export function classifyIssues(issues: IssueInput[]): {
  eligible: IssueInput[];
  ineligible: Array<{ issue: IssueInput; reason: string }>;
} {
  const eligible: IssueInput[] = [];
  const ineligible: Array<{ issue: IssueInput; reason: string }> = [];
  for (const issue of issues) {
    const res = isEligible(issue);
    if (res.eligible) eligible.push(issue);
    else ineligible.push({ issue, reason: (res as { reason: string }).reason });
  }
  return { eligible, ineligible };
}

export type SettledWorker =
  | { status: "fulfilled"; commits: string[] }
  | { status: "rejected"; reason: string };

export function partitionWorkers(
  issues: Array<{ id: string; branch: string }>,
  settled: SettledWorker[],
): { completed: Array<{ id: string; branch: string }>; failed: Array<{ id: string; branch: string; reason: string }> } {
  const completed: Array<{ id: string; branch: string }> = [];
  const failed: Array<{ id: string; branch: string; reason: string }> = [];
  for (let i = 0; i < issues.length; i++) {
    const issue = issues[i]!;
    const s = settled[i]!;
    if (s.status === "rejected") {
      failed.push({ ...issue, reason: s.reason });
    } else if (s.commits.length === 0) {
      failed.push({ ...issue, reason: "no commits produced" });
    } else {
      completed.push(issue);
    }
  }
  return { completed, failed };
}

export { detectContradictions } from "./tracker-policy.mts";
