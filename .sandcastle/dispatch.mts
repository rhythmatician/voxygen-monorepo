import { missingTracerConcepts } from "./tracer-contract.mts";

/**
 * Factory v0 dispatch — deterministic eligibility for AFK implementation.
 *
 * Separates semantic readiness (`ready-for-agent`) from execution authorization
 * (`agent:implement`). The LLM planner is never the authority on eligibility.
 */

export interface IssueInput {
  number: number;
  title: string;
  state: "open" | "closed";
  labels: string[];
  assignees: string[];
  blockedByCount?: number; // from GitHub native issue_dependencies_summary.blocked_by
  body?: string;
}

export const REQUIRED_LABEL = "agent:implement";
export const IN_PROGRESS_LABEL = "agent:in-progress";
export const BLOCKED_LABEL = "agent:blocked";

// Wayfinder workflow types that the generic implementation pipeline must never
// execute. `wayfinder:task` is intentionally NOT in this list — it is the
// only Wayfinder type that may be AFK-executable via Sandcastle (AFK Task =
// wayfinder:task + agent:implement + map Notes signal + tracer contract).
// `wayfinder:research` is AFK but via Wayfinder research subagents during
// charting, not via Sandcastle — it has no agent:implement so it remains
// ineligible here without being forbidden. See ADR 0001 and CONTEXT.md.
// Leave a seam: if future
// task routing needs special handling, gate it here instead of expanding
// this block-list.
export const FORBIDDEN_WAYFINDER_LABELS = [
  "wayfinder:prototype",
  "wayfinder:grilling",
  "wayfinder:map",
  "wayfinder:preserve-futures",
] as const;

export const WAYFINDER_TASK_LABEL = "wayfinder:task" as const;
export const WAYFINDER_TASK_MAP_SIGNAL = "Execution is carried into this map" as const;
const WAYFINDER_TASK_REASON = "wayfinder:task: map Notes does not authorize AFK execution" as const;

export type EligibilityResult =
  | { eligible: true }
  | { eligible: false; reason: string };

export function branchForIssue(id: number | string): string {
  return `sandcastle/issue-${id}`;
}

/**
 * Deterministic eligibility check. Must satisfy ALL conditions.
 * Returns eligible:false with a human-readable reason when any gate fails.
 */
export function isEligible(issue: IssueInput): EligibilityResult {
  // 1. Issue must be open (GH returns "OPEN" uppercase; normalize)
  if (issue.state.toLowerCase() !== "open") {
    return { eligible: false, reason: `state is ${issue.state}, expected open` };
  }

  // 2. Must have explicit execution authorization
  if (!issue.labels.includes(REQUIRED_LABEL)) {
    return { eligible: false, reason: `missing required label ${REQUIRED_LABEL}` };
  }

  // 3. Must not already be claimed/in-progress
  if (issue.labels.includes(IN_PROGRESS_LABEL)) {
    return { eligible: false, reason: `already has ${IN_PROGRESS_LABEL}` };
  }
  if (issue.labels.includes(BLOCKED_LABEL)) {
    return { eligible: false, reason: `already has ${BLOCKED_LABEL}` };
  }
  if (issue.assignees.length > 0) {
    return { eligible: false, reason: `already assigned to ${issue.assignees.join(",")}` };
  }

  // 4. Native blockers must be resolved — fail-closed: unknown (undefined) is ineligible.
  // Unknown can never become zero; fetchOpenImplementIssues throws or marks unknown.
  if (issue.blockedByCount === undefined) {
    return { eligible: false, reason: "blocked state unknown — GitHub dependency API unavailable (fail-closed)" };
  }
  if (issue.blockedByCount > 0) {
    return { eligible: false, reason: `blocked by ${issue.blockedByCount} open blocker(s)` };
  }

  // 5. Wayfinder workflow boundary — generic worker must not execute HITL tickets
  // HITL-only: prototype, grilling, map, preserve-futures. Research is AFK via
  // Wayfinder subagents (no agent:implement) so not forbidden — see ADR 0001.
  for (const label of FORBIDDEN_WAYFINDER_LABELS) {
    if (issue.labels.includes(label)) {
      return {
        eligible: false,
        reason: `forbidden Wayfinder type ${label} — requires HITL/other workflow`,
      };
    }
  }

  // 6. AFK wayfinder:task triple-signal gate — labels + map Notes as durable signal.
  // Wayfinder Task executor is orthogonal (CONTEXT.md): HITL Task = wayfinder:task
  // without agent:implement (ineligible at gate 2); AFK Task = wayfinder:task +
  // agent:implement + signal + tracer contract.
  // Requires `wayfinder:task` + `agent:implement` + map Notes. Gate 2 already
  // ensures REQUIRED_LABEL, so only the Wayfinder label is checked here.
  // v0 proxies map Notes via ticket body; v1 can fetch map body via `gh api`.
  if (issue.labels.includes(WAYFINDER_TASK_LABEL)) {
    const notesAllowsExecution = issue.body?.includes(WAYFINDER_TASK_MAP_SIGNAL);
    if (!notesAllowsExecution) return { eligible: false, reason: WAYFINDER_TASK_REASON };
  }

  // 7. Tracer-bullet contract — fail-closed. By gate 2 REQUIRED_LABEL is
  //    guaranteed present, so no second includes() check is needed.
  //    ready-for-agent remains triage/readiness, not an execution gate.
  //    Alias-tolerant: see docs/agents/tracer-contract.md + tracer-contract.mts.
  {
    const missing = missingTracerConcepts(issue.body);
    if (missing.length > 0) {
      return {
        eligible: false,
        reason: `tracer contract missing: ${missing.join(", ")} — see docs/agents/tracer-contract.md`,
      };
    }
  }

  return { eligible: true };
}

export function filterEligible(issues: IssueInput[]): IssueInput[] {
  return issues.filter((i) => isEligible(i).eligible);
}

/** Pure helper for tests: count ineligible reasons distribution (not used in prod). */
export function classifyIssues(issues: IssueInput[]): {
  eligible: IssueInput[];
  ineligible: Array<{ issue: IssueInput; reason: string }>;
} {
  const eligible: IssueInput[] = [];
  const ineligible: Array<{ issue: IssueInput; reason: string }> = [];
  for (const issue of issues) {
    const res = isEligible(issue);
    if (res.eligible) eligible.push(issue);
    else ineligible.push({ issue, reason: res.reason });
  }
  return { eligible, ineligible };
}

/**
 * Partition worker outcomes into completed vs failed — pure, testable.
 * `settled` shape mirrors Promise.allSettled for worker pipelines.
 * A fulfilled worker with zero commits is treated as failed (no work).
 */
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
