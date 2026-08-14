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
// only Wayfinder type that may be AFK-executable. Leave a seam: if future
// task routing needs special handling, gate it here instead of expanding
// this block-list.
export const FORBIDDEN_WAYFINDER_LABELS = [
  "wayfinder:research",
  "wayfinder:prototype",
  "wayfinder:grilling",
  "wayfinder:map",
  "wayfinder:preserve-futures",
] as const;

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

  // 4. Native blockers must be resolved
  if (issue.blockedByCount !== undefined && issue.blockedByCount > 0) {
    return { eligible: false, reason: `blocked by ${issue.blockedByCount} open blocker(s)` };
  }

  // Fallback: `Blocked by: #N` line in body when native dependencies unavailable.
  // If the body still references an open blocker pattern, the host should have
  // already resolved blockedByCount; we treat a `Blocked by:` line as a hint
  // only when blockedByCount is undefined. Conservative: if present and no
  // native data, treat as blocked (caller can override by closing blockers).
  // For v0 we do NOT parse this line — the authoritative gate is native
  // dependencies. This keeps the seam explicit without inventing a second
  // dependency convention.

  // 5. Wayfinder workflow boundary — generic worker must not execute HITL tickets
  for (const label of FORBIDDEN_WAYFINDER_LABELS) {
    if (issue.labels.includes(label)) {
      return {
        eligible: false,
        reason: `forbidden Wayfinder type ${label} — requires HITL/other workflow`,
      };
    }
  }

  // 6. AFK wayfinder:task triple-signal gate — labels + map Notes as durable signal
  if (issue.labels.includes("wayfinder:task") && issue.labels.includes(REQUIRED_LABEL)) {
    // For v0: proxy via ticket body; v1 can fetch map body via gh api. Keep deterministic.
    const notesAllowsExecution = issue.body?.includes("Execution is carried into this map");
    if (!notesAllowsExecution) return { eligible: false, reason: "wayfinder:task: map Notes does not authorize AFK execution" };
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
