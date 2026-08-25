import type { IssueInput } from "./tracker-policy.mts";
import type { ProvenanceInspection } from "./branch-helpers.mts";
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
  applyClaim: (id: string) => Promise<void>;
  verifyClaim: (id: string) => Promise<IssueInput>;
  compensateClaim: (id: string) => Promise<boolean>;
  comment?: (id: string, body: string) => Promise<boolean>;
  claimantLogin?: string;
}

export interface ReconcileOps {
  fetchIssue: (id: string) => Promise<IssueInput>;
  releaseClaim: (id: string) => Promise<boolean>;
  comment: (id: string, body: string) => Promise<boolean>;
  isBranchPreserved?: (branch: string) => Promise<boolean>;
}

/**
 * Inspection + cleanup operations for stale-implementation reconciliation.
 * Contains NO GitHub state-transition authority — release/block/integrate
 * flow through the TrackerAdapter's verified saga via ReconcileGitHubTransitions.
 */
export interface ReconcileInspectionOps {
  claimantLogin: string;
  fetchIssue: (id: string) => Promise<IssueInput>;
  getBatchPrNumber: (issueNumber: string) => Promise<{ prNumber: string | null; state: "found" | "absent" | "unknown"; error?: string }>;
  getPrState: (prNumber: string) => Promise<{ state: string; mergedAt: string | null; found: boolean; unknown?: boolean }>;
  checkBranchExists: (branch: string) => Promise<"present" | "absent" | "unknown">;
  checkProvenanceValid: (branch: string) => Promise<ProvenanceInspection>;
  hasCommitsAhead: (branch: string) => Promise<"has-work" | "empty" | "unknown">;
  /** Proven worktree/local/remote branch deletion incl. orphaned provenance cleanup. */
  deleteBranch: (branch: string) => Promise<boolean>;
}

export type ClaimResult =
  | { success: true; issue: IssueInput }
  | {
      success: false;
      reason: string;
      code: string;
      compensated: boolean;
      factoryError?: boolean;
      issue?: IssueInput;
      /**
       * Explicit phase marker set by the canonical operation itself — the
       * adapter NEVER infers phase from string codes. Present exactly when
       * the failure occurred before applyClaim was invoked (eligibility,
       * contradiction, both-present-before-claim gates).
       */
      phase?: "rejected-before-mutation";
    };

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
// Implementation claim
// ---------------------------------------------------------------------------

export async function claimImplementation(
  issueId: string,
  initialIssue: IssueInput,
  ops: ClaimOps & { claimantLogin?: string },
): Promise<ClaimResult> {
  let fresh: IssueInput;
  try {
    fresh = await ops.fetchIssue(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { success: false, reason: `revalidation fetch failed for #${issueId}: ${reason}`, code: CLAIM_CODES.FETCH_FAILED, compensated: true };
  }
  const eligibility = isImplementationEligible(fresh);
  if (!eligibility.eligible) {
    return { success: false, reason: (eligibility as { reason: string }).reason, code: (eligibility as { code?: string }).code ?? CLAIM_CODES.NOT_ELIGIBLE, compensated: true, issue: fresh, phase: "rejected-before-mutation" };
  }
  if (fresh.labels.includes(AGENT_IMPLEMENT) && fresh.labels.includes(AGENT_IN_PROGRESS)) {
    return { success: false, reason: `${AGENT_IMPLEMENT} with ${AGENT_IN_PROGRESS} present before claim — fail closed`, code: CLAIM_CODES.BOTH_PRESENT, compensated: true, issue: fresh, phase: "rejected-before-mutation" };
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
    return { success: false, reason: `claim mutation failed for #${issueId}: ${reason}`, code: CLAIM_CODES.MUTATE_FAILED, compensated, factoryError };
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
    return { success: false, reason: `verify fetch failed for #${issueId}: ${reason}`, code: CLAIM_CODES.VERIFY_FAILED, compensated, factoryError };
  }
  const hasReady = after.labels.includes(READY_FOR_AGENT);
  const hasImplement = after.labels.includes(AGENT_IMPLEMENT);
  const hasInProgress = after.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = after.assignees.length > 0;
  const claimantLogin = ops.claimantLogin;
  if (!claimantLogin) {
    let compensated = true;
    let factoryError = true;
    try { compensated = await ops.compensateClaim(issueId); if (!compensated) factoryError = true; } catch { compensated = false; factoryError = true; }
    return { success: false, reason: `claimant login not resolved for #${issueId} — fail closed`, code: "CLAIMANT_UNRESOLVED", compensated, factoryError, issue: after };
  }
  const hasExpectedAssignee = after.assignees.includes(claimantLogin);
  const both = hasImplement && hasInProgress;
  if (both) {
    let compensated = true;
    let factoryError = false;
    try { compensated = await ops.compensateClaim(issueId); if (!compensated) factoryError = true; } catch { compensated = false; factoryError = true; }
    return { success: false, reason: `both ${AGENT_IMPLEMENT} and ${AGENT_IN_PROGRESS} present after claim — compensated`, code: CLAIM_CODES.BOTH_PRESENT, compensated, factoryError, issue: after };
  }
  const successCondition = hasReady && !hasImplement && hasInProgress && hasAssignee && hasExpectedAssignee;
  if (!successCondition) {
    let compensated = true;
    let factoryError = false;
    try { compensated = await ops.compensateClaim(issueId); if (!compensated) factoryError = true; } catch { compensated = false; factoryError = true; }
    const reason = `postcondition mismatch for #${issueId}: ready=${hasReady} implement=${hasImplement} inProgress=${hasInProgress} assignee=${hasAssignee} expected=${claimantLogin} hasExpected=${hasExpectedAssignee}`;
    return { success: false, reason, code: CLAIM_CODES.POSTCONDITION_MISMATCH, compensated, factoryError, issue: after };
  }
  return { success: true, issue: after };
}

// ---------------------------------------------------------------------------
// Research claim
// ---------------------------------------------------------------------------

export interface ResearchClaimOps extends FetchOps {
  applyClaim: (id: string) => Promise<void>;
  verifyClaim: (id: string) => Promise<IssueInput>;
  compensateClaim: (id: string) => Promise<boolean>;
}

export async function claimResearch(
  issueId: string,
  ops: ResearchClaimOps & { claimantLogin?: string },
): Promise<ClaimResult> {
  let fresh: IssueInput;
  try {
    fresh = await ops.fetchIssue(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { success: false, reason: `revalidation fetch failed for research #${issueId}: ${reason}`, code: CLAIM_CODES.FETCH_FAILED, compensated: true };
  }
  const eligibility = isResearchEligible(fresh);
  if (!eligibility.eligible) {
    return { success: false, reason: eligibility.reason, code: eligibility.code ?? "ELIGIBILITY_FAILED", compensated: true, phase: "rejected-before-mutation" };
  }
  const contradictions = detectContradictions(fresh);
  if (contradictions.contradictions.length > 0) {
    return { success: false, reason: contradictions.contradictions[0].reason, code: contradictions.contradictions[0].code, compensated: true, issue: fresh, phase: "rejected-before-mutation" };
  }
  try {
    await ops.applyClaim(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    let compensated = true;
    let factoryError = false;
    try { compensated = await ops.compensateClaim(issueId); if (!compensated) factoryError = true; } catch { compensated = false; factoryError = true; }
    return { success: false, reason: `research claim mutation failed for #${issueId}: ${reason}`, code: CLAIM_CODES.MUTATE_FAILED, compensated, factoryError };
  }
  let after: IssueInput;
  try {
    after = await ops.verifyClaim(issueId);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    let compensated = true;
    let factoryError = false;
    try { compensated = await ops.compensateClaim(issueId); if (!compensated) factoryError = true; } catch { compensated = false; factoryError = true; }
    return { success: false, reason: `research verify failed for #${issueId}: ${reason}`, code: CLAIM_CODES.VERIFY_FAILED, compensated, factoryError };
  }
  const hasInProgress = after.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = after.assignees.length > 0;
  const hasResearch = after.labels.includes("wayfinder:research");
  const claimantLogin = ops.claimantLogin;
  if (!claimantLogin) {
    let compensated = true;
    let factoryError = true;
    try { compensated = await ops.compensateClaim(issueId); if (!compensated) factoryError = true; } catch { compensated = false; factoryError = true; }
    return { success: false, reason: `claimant login not resolved for research #${issueId} — fail closed`, code: "CLAIMANT_UNRESOLVED", compensated, factoryError, issue: after };
  }
  const hasExpectedAssignee = after.assignees.includes(claimantLogin);
  if (!hasInProgress || !hasAssignee || !hasResearch || !hasExpectedAssignee) {
    let compensated = true;
    let factoryError = false;
    try { compensated = await ops.compensateClaim(issueId); if (!compensated) factoryError = true; } catch { compensated = false; factoryError = true; }
    return { success: false, reason: `research postcondition mismatch for #${issueId}`, code: CLAIM_CODES.POSTCONDITION_MISMATCH, compensated, factoryError, issue: after };
  }
  return { success: true, issue: after };
}

// ---------------------------------------------------------------------------
// Reconciliation — typed decision + executor, no fallback
// ---------------------------------------------------------------------------

export interface ReconcileResult {
  reconciled: boolean;
  reason: string;
  factoryError?: boolean;
  decision?: ReconcileDecision;
  /**
   * Typed receipts produced by the TrackerAdapter for the GitHub transitions
   * it executed to carry out this reconciliation (release/block/integrate).
   * Empty or absent when no GitHub transition was required.
   */
  receipts?: unknown[];
}

export type ReconcileDecision =
  | { type: "open_pr"; prNumber: string; reason: string }
  | { type: "merged_pr"; prNumber: string; reason: string }
  | { type: "unknown"; reason: string }
  | { type: "absent_empty_branch"; reason: string }
  | { type: "absent_with_work"; reason: string }
  | { type: "invalid_provenance"; reason: string; contaminated?: boolean }
  | { type: "no_branch"; reason: string }
  | { type: "not_stale"; reason: string }
  | { type: "pr_not_found"; prNumber: string; reason: string }
  | { type: "pr_closed_without_merge"; prNumber: string; state: string; reason: string };

/**
 * The GitHub state-transition port a reconciliation executor MUST use —
 * implemented ONLY by the TrackerAdapter's verified saga, so every mutation
 * is validated, proved on fresh reads, and receipted by the single authority.
 */
export interface ReconcileGitHubTransitions {
  /**
   * Release an owned implementation claim then add agent:blocked — ONE
   * two-step saga. The release step proves exact ownership; the add-blocked
   * step re-proves the released intermediate state on its own fresh read.
   * A drift between the two steps is indeterminate, never a clean rejection.
   */
  releaseAndBlockOwnedImplementation(issueNumber: number): Promise<TransitionOutcome>;
  /**
   * Release an owned implementation claim WITHOUT adding agent:blocked — used
   * by the empty-branch cleanup path where the branch was cleaned and the
   * claim is simply released. Proves exact ownership on its own fresh read.
   */
  releaseOwnedImplementationClaim(issueNumber: number): Promise<TransitionOutcome>;
  /** Strip transient labels, close, verify closed-and-clean. Returns committed result. */
  integrateAndClose(issueNumber: number, branch: string): Promise<TransitionOutcome>;
  comment(issueNumber: number, body: string): Promise<boolean>;
}

/** Minimal shape of a TrackerTransitionResult consumed here (avoids import cycle). */
export interface TransitionOutcome {
  kind: "committed" | "rejected" | "compensated" | "indeterminate";
  factoryError?: true;
  reason?: string;
  code?: string;
  receipt?: unknown;
}

export function decideReconciliation(
  issue: IssueInput,
  branch: string,
  batch: { prNumber: string | null; state: "found" | "absent" | "unknown"; error?: string } | null,
  prState: { state: string; mergedAt: string | null; found: boolean; unknown?: boolean } | null,
  branchExists: boolean | "present" | "absent" | "unknown" | null,
  provenanceValid: ProvenanceInspection | null,
  hasWork: boolean | "has-work" | "empty" | "unknown" | null,
): ReconcileDecision {
  const hasInProgress = issue.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = issue.assignees.length > 0;
  const hasImplement = issue.labels.includes(AGENT_IMPLEMENT);
  const hasReady = issue.labels.includes(READY_FOR_AGENT);
  if (!hasReady || !hasInProgress || !hasAssignee || hasImplement) {
    return { type: "not_stale", reason: `not a stale claimed implementation: ready=${hasReady} inProgress=${hasInProgress} assignee=${hasAssignee} implement=${hasImplement}` };
  }
  if (!batch) return { type: "unknown", reason: "batch PR lookup missing — fail closed" };
  if (batch.state === "unknown") return { type: "unknown", reason: `batch PR lookup unknown for #${issue.number} — no mutation` };
  if (batch.state === "found" && batch.prNumber) {
    if (!prState) return { type: "unknown", reason: `batch PR #${batch.prNumber} state unknown — no mutation` };
    if (prState.unknown) return { type: "unknown", reason: `batch PR #${batch.prNumber} state unknown for #${issue.number} — no mutation` };
    if (!prState.found) return { type: "pr_not_found", prNumber: batch.prNumber, reason: `batch PR #${batch.prNumber} not found — marking blocked` };
    if (prState.mergedAt) return { type: "merged_pr", prNumber: batch.prNumber, reason: `batch PR #${batch.prNumber} merged — finalized` };
    if (prState.state === "OPEN") return { type: "open_pr", prNumber: batch.prNumber, reason: `batch PR #${batch.prNumber} OPEN for #${issue.number} — recognizing, leaving claim intact` };
    return { type: "pr_closed_without_merge", prNumber: batch.prNumber, state: prState.state, reason: `batch PR #${batch.prNumber} state ${prState.state} — leaving in-progress` };
  }
  // No durable batch PR — check branch existence (tri-state)
  const branchState = branchExists === true ? "present" : branchExists === false ? "absent" : branchExists;
  if (branchState === null || branchState === "unknown") return { type: "unknown", reason: "branch existence unknown — fail closed" };
  if (branchState === "absent") return { type: "no_branch", reason: `no branch or PR for #${issue.number} — stale, marking blocked` };
  if (!provenanceValid) return { type: "unknown", reason: `provenance check missing — fail closed for ${branch}` };
  if (provenanceValid.state === "unknown") return { type: "unknown", reason: provenanceValid.reason || `provenance unknown for ${branch} — fail closed` };
  if (provenanceValid.state === "invalid") return { type: "invalid_provenance", reason: provenanceValid.reason || `invalid provenance for ${branch}`, contaminated: provenanceValid.contaminated };
  const workState = hasWork === true ? "has-work" : hasWork === false ? "empty" : hasWork;
  if (workState === null || workState === "unknown") return { type: "unknown", reason: "hasCommitsAhead unknown — fail closed" };
  if (workState === "empty") return { type: "absent_empty_branch", reason: `empty branch ${branch} — cleaned` };
  return { type: "absent_with_work", reason: `branch ${branch} has work but no PR — preserving and blocking` };
}

/**
 * Full reconciliation operations: tri-state inspections/cleanup plus the
 * GitHub state-transition port implemented by the TrackerAdapter saga.
 */
export interface FullReconcileOps extends ReconcileInspectionOps {
  github: ReconcileGitHubTransitions;
}

/**
 * Outcome of an adapter-saga transition used inside reconciliation. The
 * transition succeeded only when the verified saga PROVED its postcondition
 * and persisted its typed receipt; anything else stops the reconciliation.
 */
function collectTransitionReceipts(
  outcome: TransitionOutcome,
  receipts: unknown[],
): void {
  if (outcome.receipt) receipts.push(outcome.receipt);
}

function transitionFailureReason(outcome: TransitionOutcome, what: string, issueNumber: number | string): string {
  const detail = outcome.reason ?? outcome.code ?? "unknown";
  if (outcome.kind === "rejected") {
    return `${what} rejected for #${issueNumber}: ${detail}`;
  }
  return `FACTORY_ERROR ${what} for #${issueNumber}: ${detail}`;
}

/**
 * State-dependent recovery comments are published ONLY after the state
 * transition has committed and its receipt persisted. A comment failure
 * after a committed transition is separate recovery evidence — it never
 * rolls back or hides the committed, receipted transition.
 */
async function recoveryComment(
  gh: ReconcileGitHubTransitions,
  issueNumber: number,
  body: string,
): Promise<string | null> {
  try {
    const ok = await gh.comment(issueNumber, body);
    return ok ? null : "comment command reported failure";
  } catch (e) {
    return e instanceof Error ? e.message : String(e);
  }
}

export async function executeReconciliation(
  decision: ReconcileDecision,
  issue: IssueInput,
  branch: string,
  ops: FullReconcileOps,
): Promise<ReconcileResult> {
  const gh = ops.github;
  const receipts: unknown[] = [];

  /**
   * Exact-ownership pre-release validation on a FRESH read: a stale
   * Sandcastle claim must STILL belong to the authenticated claimant at
   * release time — merely being assigned to someone is not ownership.
   * Any drift (claimant removed, readiness gone, implement reintroduced)
   * receives ZERO mutation and never blocks on another actor's claim.
   */
  async function validateStillOwned(what: string): Promise<ReconcileResult | null> {
    let fresh: IssueInput;
    try {
      fresh = await ops.fetchIssue(String(issue.number));
    } catch (e) {
      return { reconciled: false, reason: `${what}: fresh ownership read failed for #${issue.number}: ${e}`, factoryError: true, decision, receipts };
    }
    const problems: string[] = [];
    // The claimant is the SINGLE concurrency owner — any additional assignee
    // is concurrent drift and the claim is not owned. This is the SAME
    // sole-claimant rule the adapter's release/block/finalize sagas enforce.
    if (fresh.assignees.length !== 1) problems.push(`expected exactly 1 assignee (sole claimant ${ops.claimantLogin}), found ${fresh.assignees.length}`);
    else if (fresh.assignees[0] !== ops.claimantLogin) problems.push(`sole assignee is ${fresh.assignees[0]}, not claimant ${ops.claimantLogin}`);
    if (!fresh.labels.includes(AGENT_IN_PROGRESS)) problems.push(`${AGENT_IN_PROGRESS} absent`);
    if (!fresh.labels.includes(READY_FOR_AGENT)) problems.push(`${READY_FOR_AGENT} absent`);
    if (fresh.labels.includes(AGENT_IMPLEMENT)) problems.push(`${AGENT_IMPLEMENT} present`);
    if (problems.length > 0) {
      return { reconciled: false, reason: `${what}: claim ownership drifted for #${issue.number} before release — zero mutation (${problems.join("; ")})`, decision, receipts };
    }
    return null;
  }

  /**
   * Release (on proven ownership) then add agent:blocked — ONE two-step saga
   * through the adapter's releaseAndBlockOwnedImplementation port. The port's
   * own validateBefore freshly proves exact ownership before the release
   * mutation; the add-blocked step re-proves the released intermediate state.
   */
  async function releaseThenBlock(what: string): Promise<ReconcileResult | null> {
    const owned = await validateStillOwned(what);
    if (owned) return owned;
    let outcome: TransitionOutcome;
    try {
      outcome = await gh.releaseAndBlockOwnedImplementation(issue.number);
    } catch (e) {
      return { reconciled: false, reason: `${what}: failed to release+block claim for #${issue.number}: ${e}`, factoryError: true, decision, receipts };
    }
    collectTransitionReceipts(outcome, receipts);
    if (outcome.kind !== "committed") {
      return { reconciled: false, reason: transitionFailureReason(outcome, `${what} release+block`, issue.number), factoryError: true, decision, receipts };
    }
    return null;
  }

  switch (decision.type) {
    case "not_stale":
      return { reconciled: false, reason: decision.reason, decision };
    case "open_pr":
      return { reconciled: false, reason: decision.reason, decision };
    case "unknown":
      return { reconciled: false, reason: decision.reason, decision };
    case "merged_pr": {
      let outcome: TransitionOutcome;
      try {
        outcome = await gh.integrateAndClose(issue.number, branch);
      } catch (e) { return { reconciled: false, reason: `merged_pr handling failed for #${issue.number}: ${e}`, factoryError: true, decision, receipts }; }
      collectTransitionReceipts(outcome, receipts);
      if (outcome.kind !== "committed") {
        return { reconciled: false, reason: transitionFailureReason(outcome, "integrate-and-close", issue.number), factoryError: true, decision, receipts };
      }
      return { reconciled: true, reason: decision.reason, decision, receipts };
    }
    case "pr_not_found":
    case "pr_closed_without_merge": {
      // State first: release the stale claim, then add agent:blocked on the
      // proven released state. The recovery comment is published ONLY after
      // both transitions have committed — never instructions to remove
      // agent:blocked that does not yet exist.
      const prFailed = await releaseThenBlock(decision.type);
      if (prFailed) return prFailed;
      const commentError = await recoveryComment(gh, issue.number, `Sandcastle reconciliation: batch PR #${decision.prNumber} for \`${branch}\` ${decision.type === "pr_not_found" ? "not found" : `state ${(decision as any).state}`} — preserving. To retry, remove \`agent:blocked\` and re-add \`agent:implement\`.`);
      if (commentError) {
        return { reconciled: false, reason: `state transition committed for #${issue.number}, but recovery comment failed (separate recovery evidence): ${commentError}`, factoryError: true, decision, receipts };
      }
      return { reconciled: false, reason: decision.reason, decision, receipts };
    }
    case "no_branch": {
      // Prove exact ownership BEFORE any worktree/branch/provenance deletion —
      // never clean up orphaned provenance for a claim that no longer belongs
      // to us. The later release+block saga revalidates ownership too.
      const ownedBeforeDelete = await validateStillOwned("no_branch");
      if (ownedBeforeDelete) return ownedBeforeDelete;
      // Recovery: authoritative absent-branch cleanup for orphaned provenance crash window.
      // deleteBranch proves worktree/local/remote absence before provenance deletion.
      try {
        const cleaned = await ops.deleteBranch(branch);
        if (!cleaned) {
          try {
            const state = await ops.checkBranchExists(branch);
            if (state === "present") return { reconciled: false, reason: `branch ${branch} still present after cleanup — fail closed`, factoryError: true, decision, receipts };
            if (state === "unknown") return { reconciled: false, reason: `branch ${branch} state unknown after cleanup — fail closed`, factoryError: true, decision, receipts };
          } catch {}
          try {
            const prov = await ops.checkProvenanceValid(branch);
            if (prov.state === "valid" || prov.state === "invalid") {
              return { reconciled: false, reason: `failed to clean up orphaned provenance for ${branch}: ${prov.reason} — fail closed`, factoryError: true, decision, receipts };
            }
            if (prov.state === "unknown" && prov.reason.includes("failed")) {
              return { reconciled: false, reason: `provenance cleanup verification failed for ${branch}: ${prov.reason}`, factoryError: true, decision, receipts };
            }
          } catch {}
          return { reconciled: false, reason: `failed to clean up orphaned provenance for ${branch} — fail closed`, factoryError: true, decision, receipts };
        }
      } catch (e) {
        return { reconciled: false, reason: `absent-branch cleanup failed for ${branch}: ${e}`, factoryError: true, decision, receipts };
      }
      // Branch cleanup proved — now release the stale claim and block.
      const noBranchFailed = await releaseThenBlock("no_branch");
      if (noBranchFailed) return noBranchFailed;
      const noBranchCommentError = await recoveryComment(gh, issue.number, `Sandcastle reconciliation: no branch \`${branch}\` or batch PR for #${issue.number} — stale claim after crash. To retry, remove \`agent:blocked\` and re-add \`agent:implement\`.`);
      if (noBranchCommentError) {
        return { reconciled: false, reason: `state transition committed for #${issue.number}, but recovery comment failed (separate recovery evidence): ${noBranchCommentError}`, factoryError: true, decision, receipts };
      }
      return { reconciled: false, reason: decision.reason, decision, receipts };
    }
    case "invalid_provenance": {
      const body = decision.contaminated
        ? `Sandcastle reconciliation: branch \`${branch}\` for #${issue.number} has contaminated/legacy provenance (${decision.reason}) — fail closed, preserving/blocking.`
        : `Sandcastle reconciliation: branch \`${branch}\` for #${issue.number} has invalid provenance (${decision.reason}) — blocking.`;
      const invalidFailed = await releaseThenBlock("invalid_provenance");
      if (invalidFailed) return invalidFailed;
      const invalidCommentError = await recoveryComment(gh, issue.number, body);
      if (invalidCommentError) {
        return { reconciled: false, reason: `state transition committed for #${issue.number}, but recovery comment failed (separate recovery evidence): ${invalidCommentError}`, factoryError: true, decision, receipts };
      }
      return { reconciled: false, reason: decision.reason, decision, receipts };
    }
    case "absent_empty_branch": {
      // Prove exact ownership BEFORE any worktree/branch/provenance deletion —
      // never clean up a branch for a claim that no longer belongs to us.
      const ownedBeforeDelete = await validateStillOwned("absent_empty_branch");
      if (ownedBeforeDelete) return ownedBeforeDelete;
      let deleted = false;
      try { deleted = await ops.deleteBranch(branch); } catch (e) { return { reconciled: false, reason: `failed to delete branch ${branch}: ${e}`, factoryError: true, decision, receipts }; }
      if (!deleted) {
        return { reconciled: false, reason: `failed to delete branch ${branch} — cleanup not proved`, factoryError: true, decision, receipts };
      }
      // Verify branch truly absent after delete (both local and remote)
      try {
        const afterState = await ops.checkBranchExists(branch);
        if (afterState === "present") return { reconciled: false, reason: `failed to verify branch deletion for ${branch} — still present`, factoryError: true, decision, receipts };
        if (afterState === "unknown") return { reconciled: false, reason: `branch ${branch} state unknown after delete — fail closed`, factoryError: true, decision, receipts };
      } catch (e) { return { reconciled: false, reason: `failed to verify branch deletion for ${branch}: ${e}`, factoryError: true, decision, receipts }; }
      // Release claim only after required cleanup succeeds — through the
      // adapter's owned-release saga (revalidates exact ownership on its own
      // fresh read before the release mutation).
      let releaseOutcome: TransitionOutcome;
      try {
        releaseOutcome = await gh.releaseOwnedImplementationClaim(issue.number);
      } catch (e) { return { reconciled: false, reason: `failed to release claim for #${issue.number}: ${e}`, factoryError: true, decision, receipts }; }
      collectTransitionReceipts(releaseOutcome, receipts);
      if (releaseOutcome.kind !== "committed") {
        return { reconciled: false, reason: transitionFailureReason(releaseOutcome, "release claim", issue.number), factoryError: true, decision, receipts };
      }
      // Comment AFTER the committed release — the message describes what was
      // released, so it must not precede the receipted transition.
      const emptyCommentError = await recoveryComment(gh, issue.number, `Sandcastle reconciliation: stale implementation claim for \`${branch}\` was interrupted (empty branch). Released assignee and \`${AGENT_IN_PROGRESS}\` without restoring \`${AGENT_IMPLEMENT}\`. Branch \`${branch}\` cleaned. To retry, re-add \`${AGENT_IMPLEMENT}\` explicitly.`);
      if (emptyCommentError) {
        return { reconciled: false, reason: `release committed for #${issue.number}, but recovery comment failed (separate recovery evidence): ${emptyCommentError}`, factoryError: true, decision, receipts };
      }
      return { reconciled: true, reason: decision.reason, decision, receipts };
    }
    case "absent_with_work": {
      const workFailed = await releaseThenBlock("absent_with_work");
      if (workFailed) return workFailed;
      const workCommentError = await recoveryComment(gh, issue.number, `Sandcastle reconciliation: branch \`${branch}\` for #${issue.number} exists with work but no batch PR — crash before PR creation. Preserving branch. To retry, remove \`agent:blocked\` and re-add \`agent:implement\`.`);
      if (workCommentError) {
        return { reconciled: false, reason: `state transition committed for #${issue.number}, but recovery comment failed (separate recovery evidence): ${workCommentError}`, factoryError: true, decision, receipts };
      }
      return { reconciled: false, reason: decision.reason, decision, receipts };
    }
  }
}

/**
 * Reconcile a stale implementation claim after interruption.
 * Now uses decide + execute, requires all ops for production paths, fails closed if missing.
 */
export async function reconcileStaleImplementation(
  issue: IssueInput,
  branch: string,
  ops: FullReconcileOps,
): Promise<ReconcileResult> {
  const hasInProgress = issue.labels.includes(AGENT_IN_PROGRESS);
  const hasAssignee = issue.assignees.length > 0;
  const hasImplement = issue.labels.includes(AGENT_IMPLEMENT);
  const hasReady = issue.labels.includes(READY_FOR_AGENT);
  if (!hasReady || !hasInProgress || !hasAssignee || hasImplement) {
    return { reconciled: false, reason: `not a stale claimed implementation: ready=${hasReady} inProgress=${hasInProgress} assignee=${hasAssignee} implement=${hasImplement}` };
  }

  // Production guardrail: if any required op is missing for the path we need, fail closed instead of fallback release
  // For simple stale test without branch/PR, we can still decide but need at least getBatchPrNumber to know absent vs unknown
  // If no batch op provided, treat as absent for test compatibility, but in production this would be fail-closed — we allow fallback for tests where ops are minimal
  let batch: { prNumber: string | null; state: "found" | "absent" | "unknown"; error?: string } | null = null;
  let prState: { state: string; mergedAt: string | null; found: boolean; unknown?: boolean } | null = null;
  let branchExists: "present" | "absent" | "unknown" | null = null;
  let provenanceValid: ProvenanceInspection | null = null;
  let hasWork: "has-work" | "empty" | "unknown" | null = null;

  // Batch lookup — required
  try {
    batch = await ops.getBatchPrNumber(String(issue.number));
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { reconciled: false, reason: `batch PR lookup failed (unknown) for #${issue.number}: ${reason}` };
  }
  if (batch.state === "unknown") {
    return { reconciled: false, reason: `batch PR lookup unknown for #${issue.number} — no mutation` };
  }
  if (batch.state === "found" && batch.prNumber) {
    try {
      prState = await ops.getPrState(batch.prNumber);
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e);
      return { reconciled: false, reason: `batch PR #${batch.prNumber} lookup unknown for #${issue.number}: ${reason} — no mutation` };
    }
    if (prState.unknown) {
      return { reconciled: false, reason: `batch PR #${batch.prNumber} state unknown for #${issue.number} — no mutation` };
    }
    const decision = decideReconciliation(issue, branch, batch, prState, null, null, null);
    return executeReconciliation(decision, issue, branch, ops);
  }
  // absent -> continue to branch checks

  // Need branch existence — tri-state, fail closed on unknown, no fallback (normalize boolean for test compat)
  try {
    const raw = await ops.checkBranchExists(branch) as any;
    const state = raw === true ? "present" : raw === false ? "absent" : raw;
    if (state === "unknown") return { reconciled: false, reason: `branch existence unknown for ${branch} — fail closed` };
    if (state !== "present" && state !== "absent") return { reconciled: false, reason: `branch existence unknown for ${branch} — fail closed` };
    branchExists = state as any;
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { reconciled: false, reason: `branch existence check failed for ${branch}: ${reason} — fail closed` };
  }

  if (branchExists === "absent") {
    const decision: ReconcileDecision = { type: "no_branch", reason: `no branch or PR for #${issue.number} — stale, marking blocked` };
    return executeReconciliation(decision, issue, branch, ops);
  }

  // Branch exists — check provenance (genuinely tri-state, unknown => no mutation)
  try { provenanceValid = await ops.checkProvenanceValid(branch); } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { reconciled: false, reason: `provenance check failed for ${branch}: ${reason} — fail closed` };
  }
  if (provenanceValid.state === "unknown") {
    return { reconciled: false, reason: provenanceValid.reason || `provenance unknown for ${branch} — fail closed` };
  }
  if (provenanceValid.state === "invalid") {
    const decision: ReconcileDecision = { type: "invalid_provenance", reason: provenanceValid.reason || `invalid provenance for ${branch}`, contaminated: provenanceValid.contaminated };
    return executeReconciliation(decision, issue, branch, ops);
  }

  try {
    const rawWork = await ops.hasCommitsAhead(branch) as any;
    const workState = rawWork === true ? "has-work" : rawWork === false ? "empty" : rawWork;
    if (workState === "unknown") return { reconciled: false, reason: `hasCommitsAhead unknown for ${branch} — fail closed` };
    if (workState !== "has-work" && workState !== "empty") return { reconciled: false, reason: `hasCommitsAhead unknown for ${branch} — fail closed` };
    hasWork = workState as any;
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    return { reconciled: false, reason: `hasCommitsAhead failed for ${branch}: ${reason} — fail closed` };
  }

  const decision = decideReconciliation(issue, branch, batch, prState, branchExists as any, provenanceValid, hasWork as any);
  return executeReconciliation(decision, issue, branch, ops);
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
