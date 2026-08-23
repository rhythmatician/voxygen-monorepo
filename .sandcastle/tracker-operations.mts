import type { IssueInput } from "./tracker-policy.mts";
import {
  READY_FOR_AGENT,
  AGENT_IMPLEMENT,
  AGENT_IN_PROGRESS,
  AGENT_BLOCKED,
  isImplementationEligible,
  detectContradictions,
  isResearchEligible,
} from "./tracker-policy.mts";

/**
 * Tracker operations — low-level GitHub effects separate from pure policy.
 * Keeps claim and reconciliation testable via injected operations.
 * No generic GitHub SDK, no workflow engine, no DI framework.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface FetchOps {
  fetchIssue: (id: string) => Promise<IssueInput>;
}

export interface ClaimOps extends FetchOps {
  /** Apply claim mutation: add assignee, add agent:in-progress, remove agent:implement (one logical transaction) */
  applyClaim: (id: string) => Promise<void>;
  /** Read back after mutation */
  verifyClaim: (id: string) => Promise<IssueInput>;
  /** Compensate partial claim: remove assignee and agent:in-progress, never restore implement */
  compensateClaim: (id: string) => Promise<boolean>;
  /** Optional comment for receipt */
  comment?: (id: string, body: string) => Promise<boolean>;
}

export interface ReconcileOps {
  fetchIssue: (id: string) => Promise<IssueInput>;
  releaseClaim: (id: string) => Promise<boolean>; // remove assignee + in-progress
  comment: (id: string, body: string) => Promise<boolean>;
  isBranchPreserved?: (branch: string) => Promise<boolean>;
}

export type ClaimResult =
  | { success: true; issue: IssueInput }
  | { success: false; reason: string; code: string; compensated: boolean; factoryError?: boolean; issue?: IssueInput };

export const CLAIM_CODES = {
  FETCH_FAILED: "FETCH_FAILED",
  NOT_ELIGIBLE: "NOT_ELIGIBLE",
  MUTATE_FAILED: "MUTATE_FAILED",
  VERIFY_FAILED: "VERIFY_FAILED",
  POSTCONDITION_MISMATCH: "POSTCONDITION_MISMATCH",
  COMPENSATION_FAILED: "COMPENSATION_FAILED",
  BOTH_PRESENT: "BOTH_PRESENT",
} as const;

// ---------------------------------------------------------------------------
// Implementation claim — tested host-side transition
// ---------------------------------------------------------------------------

/**
 * Attempt to claim an implementation issue with transactional semantics.
 *
 * Logical transaction:
 *   before: ready-for-agent + agent:implement + unassigned + no in-progress
 *   after:  ready-for-agent + agent:in-progress + assignee + no implement
 *
 * Steps: revalidate → mutate → verify → compensate if needed → never restore implement
 */
export async function claimImplementation(
  issueId: string,
  initialIssue: IssueInput,
  ops: ClaimOps,
): Promise<ClaimResult> {
  // 1. Revalidate immediately before mutation
  let fresh: IssueInput;
  try {
    fresh = await ops.fetchIssue(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { success: false, reason: `revalidation fetch failed for #${issueId}: ${reason}`, code: CLAIM_CODES.FETCH_FAILED, compensated: true };
  }

  // Check eligibility via production policy (not just initialIssue)
  const eligibility = isImplementationEligible(fresh);
  if (!eligibility.eligible) {
    return { success: false, reason: (eligibility as { reason: string }).reason, code: (eligibility as { code?: string }).code ?? CLAIM_CODES.NOT_ELIGIBLE, compensated: true, issue: fresh };
  }

  // Additional safety: never intentionally run while both implement and in-progress present (even if revalidation missed)
  if (fresh.labels.includes(AGENT_IMPLEMENT) && fresh.labels.includes(AGENT_IN_PROGRESS)) {
    return { success: false, reason: `${AGENT_IMPLEMENT} with ${AGENT_IN_PROGRESS} present before claim — fail closed`, code: CLAIM_CODES.BOTH_PRESENT, compensated: true, issue: fresh };
  }

  // 2. Request assignee + in-progress + remove implement
  try {
    await ops.applyClaim(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    // Mutation partially may have succeeded — attempt compensation
    let compensated = true;
    let factoryError = false;
    try {
      compensated = await ops.compensateClaim(issueId);
      if (!compensated) factoryError = true;
    } catch (compErr) {
      compensated = false;
      factoryError = true;
    }
    return {
      success: false,
      reason: `claim mutation failed for #${issueId}: ${reason}`,
      code: CLAIM_CODES.MUTATE_FAILED,
      compensated,
      factoryError,
    };
  }

  // 3. Read back
  let after: IssueInput;
  try {
    after = await ops.verifyClaim(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    // Verify failed — compensate
    let compensated = true;
    let factoryError = false;
    try {
      compensated = await ops.compensateClaim(issueId);
      if (!compensated) factoryError = true;
    } catch {
      compensated = false;
      factoryError = true;
    }
    return { success: false, reason: `verify fetch failed for #${issueId}: ${reason}`, code: CLAIM_CODES.VERIFY_FAILED, compensated, factoryError };
  }

  // 4. Check postcondition
  const hasReady = after.labels.includes(READY_FOR_AGENT);
  const hasImplement = after.labels.includes(AGENT_IMPLEMENT);
  const hasInProgress = after.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = after.assignees.length > 0;
  const both = hasImplement && hasInProgress;

  if (both) {
    // Never run while both present — compensate
    let compensated = true;
    let factoryError = false;
    try {
      compensated = await ops.compensateClaim(issueId);
      if (!compensated) factoryError = true;
    } catch {
      compensated = false;
      factoryError = true;
    }
    return { success: false, reason: `both ${AGENT_IMPLEMENT} and ${AGENT_IN_PROGRESS} present after claim — compensated`, code: CLAIM_CODES.BOTH_PRESENT, compensated, factoryError, issue: after };
  }

  const successCondition = hasReady && !hasImplement && hasInProgress && hasAssignee;
  if (!successCondition) {
    let compensated = true;
    let factoryError = false;
    try {
      compensated = await ops.compensateClaim(issueId);
      if (!compensated) factoryError = true;
    } catch {
      compensated = false;
      factoryError = true;
    }
    const reason = `postcondition mismatch for #${issueId}: ready=${hasReady} implement=${hasImplement} inProgress=${hasInProgress} assignee=${hasAssignee}`;
    return { success: false, reason, code: CLAIM_CODES.POSTCONDITION_MISMATCH, compensated, factoryError, issue: after };
  }

  return { success: true, issue: after };
}

// ---------------------------------------------------------------------------
// Research claim — simpler, retains wayfinder:research type
// ---------------------------------------------------------------------------

export interface ResearchClaimOps extends FetchOps {
  applyClaim: (id: string) => Promise<void>; // add assignee + in-progress (no removal of type label)
  verifyClaim: (id: string) => Promise<IssueInput>;
  compensateClaim: (id: string) => Promise<boolean>;
}

export async function claimResearch(
  issueId: string,
  ops: ResearchClaimOps,
): Promise<ClaimResult> {
  let fresh: IssueInput;
  try {
    fresh = await ops.fetchIssue(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { success: false, reason: `revalidation fetch failed for research #${issueId}: ${reason}`, code: CLAIM_CODES.FETCH_FAILED, compensated: true };
  }

  // Revalidate full research eligibility (assignment, blocked, contradictions, body)
  const eligibility = isResearchEligible(fresh);
  if (!eligibility.eligible) {
    return { success: false, reason: eligibility.reason, code: eligibility.code ?? "ELIGIBILITY_FAILED", compensated: true };
  }
  const contradictions = detectContradictions(fresh);
  if (contradictions.contradictions.length > 0) {
    return { success: false, reason: contradictions.contradictions[0].reason, code: contradictions.contradictions[0].code, compensated: true, issue: fresh };
  }

  try {
    await ops.applyClaim(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    let compensated = true;
    let factoryError = false;
    try {
      compensated = await ops.compensateClaim(issueId);
      if (!compensated) factoryError = true;
    } catch {
      compensated = false;
      factoryError = true;
    }
    return { success: false, reason: `research claim mutation failed for #${issueId}: ${reason}`, code: CLAIM_CODES.MUTATE_FAILED, compensated, factoryError };
  }

  let after: IssueInput;
  try {
    after = await ops.verifyClaim(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    let compensated = true;
    let factoryError = false;
    try {
      compensated = await ops.compensateClaim(issueId);
      if (!compensated) factoryError = true;
    } catch {
      compensated = false;
      factoryError = true;
    }
    return { success: false, reason: `research verify failed for #${issueId}: ${reason}`, code: CLAIM_CODES.VERIFY_FAILED, compensated, factoryError };
  }

  const hasInProgress = after.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = after.assignees.length > 0;
  const hasResearch = after.labels.includes("wayfinder:research");
  if (!hasInProgress || !hasAssignee || !hasResearch) {
    let compensated = true;
    let factoryError = false;
    try {
      compensated = await ops.compensateClaim(issueId);
      if (!compensated) factoryError = true;
    } catch {
      compensated = false;
      factoryError = true;
    }
    return { success: false, reason: `research postcondition mismatch for #${issueId}`, code: CLAIM_CODES.POSTCONDITION_MISMATCH, compensated, factoryError, issue: after };
  }

  return { success: true, issue: after };
}

// ---------------------------------------------------------------------------
// Reconciliation — conservative ADR 0010 policy
// ---------------------------------------------------------------------------

export interface ReconcileResult {
  reconciled: boolean;
  reason: string;
  factoryError?: boolean;
}

/**
 * Reconcile a stale implementation claim after interruption.
 * Releases assignee + agent:in-progress, does NOT restore agent:implement,
 * preserves branch, leaves actionable comment.
 */
export async function reconcileStaleImplementation(
  issue: IssueInput,
  branch: string,
  ops: ReconcileOps,
): Promise<ReconcileResult> {
  const hasInProgress = issue.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = issue.assignees.length > 0;
  const hasImplement = issue.labels.includes(AGENT_IMPLEMENT);
  const hasReady = issue.labels.includes(READY_FOR_AGENT);

  // Only reconcile if it looks like a successfully claimed attempt (consumed implement)
  // i.e., has ready + in-progress + assignee, no implement
  if (!hasReady || !hasInProgress || !hasAssignee || hasImplement) {
    return { reconciled: false, reason: `not a stale claimed implementation: ready=${hasReady} inProgress=${hasInProgress} assignee=${hasAssignee} implement=${hasImplement}` };
  }

  // Release claim
  let released = false;
  try {
    released = await ops.releaseClaim(String(issue.number));
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { reconciled: false, reason: `failed to release stale claim for #${issue.number}: ${reason}`, factoryError: true };
  }
  if (!released) {
    return { reconciled: false, reason: `failed to release stale claim for #${issue.number}`, factoryError: true };
  }

  // Leave actionable receipt
  const commentBody = `Sandcastle reconciliation: stale implementation claim for \`${branch}\` was interrupted. Released assignee and \`${AGENT_IN_PROGRESS}\` without restoring \`${AGENT_IMPLEMENT}\`. Branch \`${branch}\` preserved. To retry, re-add \`${AGENT_IMPLEMENT}\` explicitly.`;
  try {
    await ops.comment(String(issue.number), commentBody);
  } catch {
    // comment is best-effort but note
  }

  return { reconciled: true, reason: `released stale claim for #${issue.number}, preserved ${branch}, requires explicit re-add of ${AGENT_IMPLEMENT}` };
}

export async function reconcileStaleResearch(
  issue: IssueInput,
  branch: string,
  ops: ReconcileOps,
): Promise<ReconcileResult> {
  const hasInProgress = issue.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = issue.assignees.length > 0;
  if (!hasInProgress && !hasAssignee) {
    return { reconciled: false, reason: `research #${issue.number} not stale` };
  }
  let released = false;
  try {
    released = await ops.releaseClaim(String(issue.number));
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { reconciled: false, reason: `failed to release stale research claim for #${issue.number}: ${reason}`, factoryError: true };
  }
  if (!released) {
    return { reconciled: false, reason: `failed to release stale research claim for #${issue.number}`, factoryError: true };
  }
  return { reconciled: true, reason: `released stale research claim for #${issue.number}, preserved ${branch}` };
}

/**
 * Closed-issue cleanup: remove stale transient/command labels from closed issues
 * as appropriate. Only removes agent:in-progress, agent:implement, agent:blocked if present.
 */
export async function cleanupClosedIssue(
  issue: IssueInput,
  ops: { removeLabel: (id: string, label: string) => Promise<boolean> },
): Promise<{ cleaned: boolean; removed: string[] }> {
  if (issue.state !== "closed") return { cleaned: false, removed: [] };
  const toRemove = [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED].filter((l) => issue.labels.includes(l));
  const removed: string[] = [];
  for (const label of toRemove) {
    try {
      const ok = await ops.removeLabel(String(issue.number), label);
      if (ok) removed.push(label);
    } catch {
      // best-effort
    }
  }
  return { cleaned: removed.length > 0, removed };
}
