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
    return { success: false, reason: (eligibility as { reason: string }).reason, code: (eligibility as { code?: string }).code ?? CLAIM_CODES.NOT_ELIGIBLE, compensated: true, issue: fresh };
  }
  if (fresh.labels.includes(AGENT_IMPLEMENT) && fresh.labels.includes(AGENT_IN_PROGRESS)) {
    return { success: false, reason: `${AGENT_IMPLEMENT} with ${AGENT_IN_PROGRESS} present before claim — fail closed`, code: CLAIM_CODES.BOTH_PRESENT, compensated: true, issue: fresh };
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
  const claimantLogin = (ops as any).claimantLogin as string | undefined;
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
  const claimantLogin = (ops as any).claimantLogin as string | undefined;
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

export interface FullReconcileOps extends ReconcileOps {
  getBatchPrNumber: (issueNumber: string) => Promise<{ prNumber: string | null; state: "found" | "absent" | "unknown"; error?: string }>;
  getPrState: (prNumber: string) => Promise<{ state: string; mergedAt: string | null; found: boolean; unknown?: boolean }>;
  checkBranchExists: (branch: string) => Promise<"present" | "absent" | "unknown">;
  checkProvenanceValid: (branch: string) => Promise<ProvenanceInspection>;
  hasCommitsAhead: (branch: string) => Promise<"has-work" | "empty" | "unknown">;
  deleteBranch: (branch: string) => Promise<boolean>;
  addBlocked: (issueId: string) => Promise<boolean>;
  markIntegrated: (issueId: string, branch: string) => Promise<boolean>;
  claimantLogin?: string;
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

export async function executeReconciliation(
  decision: ReconcileDecision,
  issue: IssueInput,
  branch: string,
  ops: FullReconcileOps,
): Promise<ReconcileResult> {
  switch (decision.type) {
    case "not_stale":
      return { reconciled: false, reason: decision.reason, decision };
    case "open_pr":
      return { reconciled: false, reason: decision.reason, decision };
    case "unknown":
      return { reconciled: false, reason: decision.reason, decision };
    case "merged_pr": {
      try {
        const ok = await ops.markIntegrated(String(issue.number), branch);
        if (!ok) return { reconciled: false, reason: `markIntegrated failed for #${issue.number}`, factoryError: true, decision };
        // Verify final issue state after integration
        if (ops.fetchIssue) {
          try {
            const after = await ops.fetchIssue(String(issue.number));
            const hasInProgress = after.labels.includes(AGENT_IN_PROGRESS);
            const hasBlocked = after.labels.includes(AGENT_BLOCKED);
            const isClosed = after.state === "closed";
            if (hasInProgress || hasBlocked) {
              return { reconciled: false, reason: `failed to verify integrated state for #${issue.number} — still has transient labels`, factoryError: true, decision };
            }
            // Expect closed or at least not open with claim
            if (after.labels.includes(AGENT_IMPLEMENT)) {
              return { reconciled: false, reason: `failed to verify integrated state for #${issue.number} — still has implement`, factoryError: true, decision };
            }
          } catch (e) { return { reconciled: false, reason: `failed to fetch after markIntegrated for #${issue.number}: ${e}`, factoryError: true, decision }; }
        }
      } catch (e) { return { reconciled: false, reason: `merged_pr handling failed for #${issue.number}: ${e}`, factoryError: true, decision }; }
      return { reconciled: true, reason: decision.reason, decision };
    }
    case "pr_not_found":
    case "pr_closed_without_merge": {
      try {
        const ok = await ops.comment(String(issue.number), `Sandcastle reconciliation: batch PR #${decision.prNumber} for \`${branch}\` ${decision.type === "pr_not_found" ? "not found" : `state ${(decision as any).state}`} — preserving. To retry, remove \`agent:blocked\` and re-add \`agent:implement\`.`);
        if (!ok) return { reconciled: false, reason: `failed to comment for #${issue.number}`, factoryError: true, decision };
      } catch (e) { return { reconciled: false, reason: `failed to comment for #${issue.number}: ${e}`, factoryError: true, decision }; }
      let released = false;
      try { released = await ops.releaseClaim(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to release claim for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!released) return { reconciled: false, reason: `failed to release claim for #${issue.number}`, factoryError: true, decision };
      // Verify claim release with fresh read — failure is FACTORY_ERROR
      try {
        const afterRelease = await ops.fetchIssue(String(issue.number));
        if (afterRelease.labels.includes(AGENT_IN_PROGRESS) || afterRelease.assignees.length > 0) {
          return { reconciled: false, reason: `failed to verify claim release for #${issue.number} — still has in-progress or assignee`, factoryError: true, decision };
        }
      } catch (e) { return { reconciled: false, reason: `failed to verify claim release for #${issue.number}: ${e}`, factoryError: true, decision }; }
      let blocked = false;
      try { blocked = await ops.addBlocked(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to add blocked for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!blocked) return { reconciled: false, reason: `failed to add blocked for #${issue.number}`, factoryError: true, decision };
      // Verify blocked present with fresh read — failure is FACTORY_ERROR
      try {
        const afterBlocked = await ops.fetchIssue(String(issue.number));
        if (!afterBlocked.labels.includes(AGENT_BLOCKED)) {
          return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}`, factoryError: true, decision };
        }
      } catch (e) { return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}: ${e}`, factoryError: true, decision }; }
      return { reconciled: false, reason: decision.reason, decision };
    }
    case "no_branch": {
      // Recovery: authoritative absent-branch cleanup for orphaned provenance crash window
      // Prove no worktree, local, remote before deleting orphaned provenance
      try {
        const cleaned = await ops.deleteBranch(branch);
        // deleteBranch proves worktree/local/remote absence before provenance deletion.
        // If branch was truly absent and no orphan, cleaned should be true (provenance already absent or deleted).
        // If cleaned false, check why: if branch still present or unknown, fail closed.
        if (!cleaned) {
          // If deleteBranch failed, verify if it's because branch still exists or worktree issue
          if (ops.checkBranchExists) {
            try {
              const state = await ops.checkBranchExists(branch);
              if (state === "present") return { reconciled: false, reason: `branch ${branch} still present after cleanup — fail closed`, factoryError: true, decision };
              if (state === "unknown") return { reconciled: false, reason: `branch ${branch} state unknown after cleanup — fail closed`, factoryError: true, decision };
              // absent but delete failed due to worktree or provenance -> still factory error, but check provenance
            } catch {}
          }
          // For orphaned provenance case where branch absent but provenance existed, deleteBranch should have succeeded.
          // If it returned false, treat as factory error unless we can prove provenance absent.
          // We will still attempt to proceed only if we can prove worktree/local/remote absent and provenance absent.
          // For now, if deleteBranch false and branch is absent, we need to verify provenance deletion separately.
          // Try to check if provenance still exists via checkProvenanceValid: if it returns valid/invalid, it still exists; unknown may mean file missing or error.
          // If provenance still present, we cannot leave orphan.
          try {
            const prov = await ops.checkProvenanceValid(branch);
            // If provenance still reports valid/invalid (file exists), then cleanup failed
            if (prov.state === "valid" || prov.state === "invalid") {
              return { reconciled: false, reason: `failed to clean up orphaned provenance for ${branch}: ${prov.reason} — fail closed`, factoryError: true, decision };
            }
            // unknown could be malformed or missing; if missing, it would be valid with reason clean if branch empty -> but for orphan case branch absent and empty, valid means provenance already absent, so we can proceed
            // If unknown due to read failure, fail closed
            if (prov.state === "unknown" && prov.reason.includes("failed")) {
              return { reconciled: false, reason: `provenance cleanup verification failed for ${branch}: ${prov.reason}`, factoryError: true, decision };
            }
          } catch {}
          // If we cannot prove provenance absent, fail closed
          // But if branch is absent and provenance is valid with clean (empty branch no provenance), then cleaned should have been true; so false here is unexpected -> factory error
          return { reconciled: false, reason: `failed to clean up orphaned provenance for ${branch} — fail closed`, factoryError: true, decision };
        }
      } catch (e) { return { reconciled: false, reason: `absent-branch cleanup failed for ${branch}: ${e}`, factoryError: true, decision }; }
      let commented = false;
      try { commented = await ops.comment(String(issue.number), `Sandcastle reconciliation: no branch \`${branch}\` or batch PR for #${issue.number} — stale claim after crash. To retry, remove \`agent:blocked\` and re-add \`agent:implement\`.`); } catch (e) { return { reconciled: false, reason: `failed to comment for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!commented) return { reconciled: false, reason: `failed to comment for #${issue.number}`, factoryError: true, decision };
      let released = false;
      try { released = await ops.releaseClaim(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to release claim for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!released) return { reconciled: false, reason: `failed to release claim for #${issue.number}`, factoryError: true, decision };
      try {
        const afterRelease = await ops.fetchIssue(String(issue.number));
        const hasInProgress = afterRelease.labels.includes(AGENT_IN_PROGRESS);
        const claimant = (ops as any).claimantLogin as string | undefined;
        const stillAssigned = claimant ? afterRelease.assignees.includes(claimant) : afterRelease.assignees.length > 0;
        if (hasInProgress || stillAssigned) return { reconciled: false, reason: `failed to verify claim release for #${issue.number}`, factoryError: true, decision };
      } catch (e) { return { reconciled: false, reason: `failed to verify claim release for #${issue.number}: ${e}`, factoryError: true, decision }; }
      let blocked = false;
      try { blocked = await ops.addBlocked(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to add blocked for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!blocked) return { reconciled: false, reason: `failed to add blocked for #${issue.number}`, factoryError: true, decision };
      try {
        const afterBlocked = await ops.fetchIssue(String(issue.number));
        if (!afterBlocked.labels.includes(AGENT_BLOCKED)) return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}`, factoryError: true, decision };
      } catch (e) { return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}: ${e}`, factoryError: true, decision }; }
      return { reconciled: false, reason: decision.reason, decision };
    }
    case "invalid_provenance": {
      const body = decision.contaminated
        ? `Sandcastle reconciliation: branch \`${branch}\` for #${issue.number} has contaminated/legacy provenance (${decision.reason}) — fail closed, preserving/blocking.`
        : `Sandcastle reconciliation: branch \`${branch}\` for #${issue.number} has invalid provenance (${decision.reason}) — blocking.`;
      let commented = false;
      try { commented = await ops.comment(String(issue.number), body); } catch (e) { return { reconciled: false, reason: `failed to comment for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!commented) return { reconciled: false, reason: `failed to comment for #${issue.number}`, factoryError: true, decision };
      let released = false;
      try { released = await ops.releaseClaim(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to release claim for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!released) return { reconciled: false, reason: `failed to release claim for #${issue.number}`, factoryError: true, decision };
      try {
        const afterRelease = await ops.fetchIssue(String(issue.number));
        if (afterRelease.labels.includes(AGENT_IN_PROGRESS) || afterRelease.assignees.length > 0) return { reconciled: false, reason: `failed to verify claim release for #${issue.number}`, factoryError: true, decision };
      } catch (e) { return { reconciled: false, reason: `failed to verify claim release for #${issue.number}: ${e}`, factoryError: true, decision }; }
      let blocked = false;
      try { blocked = await ops.addBlocked(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to add blocked for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!blocked) return { reconciled: false, reason: `failed to add blocked for #${issue.number}`, factoryError: true, decision };
      try {
        const afterBlocked = await ops.fetchIssue(String(issue.number));
        if (!afterBlocked.labels.includes(AGENT_BLOCKED)) return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}`, factoryError: true, decision };
      } catch (e) { return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}: ${e}`, factoryError: true, decision }; }
      return { reconciled: false, reason: decision.reason, decision };
    }
    case "absent_empty_branch": {
      let deleted = false;
      try { deleted = await ops.deleteBranch(branch); } catch (e) { return { reconciled: false, reason: `failed to delete branch ${branch}: ${e}`, factoryError: true, decision }; }
      if (!deleted) {
        // deleteBranch should be idempotent: authoritative remote absence is success, unknown is FACTORY_ERROR
        // If deleteBranch returns false, treat as factory error unless we can prove absence via re-check
        if (ops.checkBranchExists) {
          try {
            const state = await ops.checkBranchExists(branch);
            if (state === "absent") {
              // Already absent, consider success but still need to prove cleanup
              deleted = true;
            } else if (state === "unknown") {
              return { reconciled: false, reason: `branch ${branch} state unknown — fail closed`, factoryError: true, decision };
            } else {
              return { reconciled: false, reason: `failed to delete branch ${branch} — still present`, factoryError: true, decision };
            }
          } catch {
            return { reconciled: false, reason: `failed to delete branch ${branch}`, factoryError: true, decision };
          }
        } else {
          return { reconciled: false, reason: `failed to delete branch ${branch}`, factoryError: true, decision };
        }
      }
      // Verify branch truly absent after delete (both local and remote)
      if (ops.checkBranchExists) {
        try {
          const afterState = await ops.checkBranchExists(branch);
          if (afterState === "present") return { reconciled: false, reason: `failed to verify branch deletion for ${branch} — still present`, factoryError: true, decision };
          if (afterState === "unknown") return { reconciled: false, reason: `branch ${branch} state unknown after delete — fail closed`, factoryError: true, decision };
        } catch (e) { return { reconciled: false, reason: `failed to verify branch deletion for ${branch}: ${e}`, factoryError: true, decision }; }
      }
      // Release claim only after required cleanup succeeds, then verify via fresh read
      let released = false;
      try { released = await ops.releaseClaim(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to release claim for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!released) return { reconciled: false, reason: `failed to release stale claim for #${issue.number}`, factoryError: true, decision };
      // Fresh verification of claim release before publishing success comment
      try {
        const afterRelease = await ops.fetchIssue(String(issue.number));
        const hasInProgress = afterRelease.labels.includes(AGENT_IN_PROGRESS);
        const claimant = (ops as any).claimantLogin as string | undefined;
        const stillAssigned = claimant ? afterRelease.assignees.includes(claimant) : afterRelease.assignees.length > 0;
        if (hasInProgress || stillAssigned) {
          return { reconciled: false, reason: `failed to verify claim release for #${issue.number} — still has in-progress or claimant assigned`, factoryError: true, decision };
        }
      } catch (e) { return { reconciled: false, reason: `failed to verify claim release for #${issue.number}: ${e}`, factoryError: true, decision }; }
      let commented = false;
      try { commented = await ops.comment(String(issue.number), `Sandcastle reconciliation: stale implementation claim for \`${branch}\` was interrupted (empty branch). Released assignee and \`${AGENT_IN_PROGRESS}\` without restoring \`${AGENT_IMPLEMENT}\`. Branch \`${branch}\` cleaned. To retry, re-add \`${AGENT_IMPLEMENT}\` explicitly.`); } catch (e) { return { reconciled: false, reason: `failed to comment for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!commented) return { reconciled: false, reason: `failed to comment for #${issue.number}`, factoryError: true, decision };
      return { reconciled: true, reason: decision.reason, decision };
    }
    case "absent_with_work": {
      let commented = false;
      try { commented = await ops.comment(String(issue.number), `Sandcastle reconciliation: branch \`${branch}\` for #${issue.number} exists with work but no batch PR — crash before PR creation. Preserving branch. To retry, remove \`agent:blocked\` and re-add \`agent:implement\`.`); } catch (e) { return { reconciled: false, reason: `failed to comment for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!commented) return { reconciled: false, reason: `failed to comment for #${issue.number}`, factoryError: true, decision };
      let released = false;
      try { released = await ops.releaseClaim(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to release claim for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!released) return { reconciled: false, reason: `failed to release claim for #${issue.number}`, factoryError: true, decision };
      try {
        const afterRelease = await ops.fetchIssue(String(issue.number));
        if (afterRelease.labels.includes(AGENT_IN_PROGRESS) || afterRelease.assignees.length > 0) return { reconciled: false, reason: `failed to verify claim release for #${issue.number}`, factoryError: true, decision };
      } catch (e) { return { reconciled: false, reason: `failed to verify claim release for #${issue.number}: ${e}`, factoryError: true, decision }; }
      let blocked = false;
      try { blocked = await ops.addBlocked(String(issue.number)); } catch (e) { return { reconciled: false, reason: `failed to add blocked for #${issue.number}: ${e}`, factoryError: true, decision }; }
      if (!blocked) return { reconciled: false, reason: `failed to add blocked for #${issue.number}`, factoryError: true, decision };
      try {
        const afterBlocked = await ops.fetchIssue(String(issue.number));
        if (!afterBlocked.labels.includes(AGENT_BLOCKED)) return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}`, factoryError: true, decision };
      } catch (e) { return { reconciled: false, reason: `failed to verify blocked label for #${issue.number}: ${e}`, factoryError: true, decision }; }
      return { reconciled: false, reason: decision.reason, decision };
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

/**
 * Closed-issue cleanup
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
    } catch {}
  }
  return { cleaned: removed.length > 0, removed };
}
