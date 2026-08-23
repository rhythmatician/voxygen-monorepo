import {
  isImplementationEligible,
  isResearchEligible,
  detectContradictions,
  WAYFINDER_RESEARCH,
  WAYFINDER_TASK,
  READY_FOR_AGENT,
  READY_FOR_HUMAN,
  AGENT_IMPLEMENT,
  AGENT_IN_PROGRESS,
  type IssueInput,
} from "./tracker-policy.mts";
import { claimImplementation, type ClaimOps } from "./tracker-operations.mts";

/**
 * Live tracker canary — explicitly invoked host-owned command.
 * Proves tracker state transitions via temporary fixture issues.
 * Never launches Muse, never creates commits, never enters review/merger.
 */

export interface CanaryResult {
  implementationDiscoverableOnlyWithReadyAndImplement: boolean;
  successfulClaimConsumesImplement: boolean;
  staleReconciliationReleasesWithoutRestoring: boolean;
  researchDiscoverableFromWayfinderAlone: boolean;
  contradictionsFailBeforeWorker: boolean;
  fixturesCleaned: boolean;
  cleanupFailures: string[];
  fixtureIds: number[];
}

export interface CanaryOps {
  createIssue: (title: string, body: string, labels: string[]) => Promise<number>;
  fetchIssue: (id: number) => Promise<IssueInput>;
  closeIssue: (id: number) => Promise<void>;
  updateIssueLabels: (id: number, add: string[], remove: string[]) => Promise<void>;
  claimImplementation: (issue: IssueInput) => Promise<{ success: boolean; reason?: string }>;
  reconcile: (issue: IssueInput) => Promise<boolean>;
  comment: (id: number, body: string) => Promise<void>;
}

function uniqueTitle(prefix: string): string {
  return `${prefix} — canary ${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export async function runCanary(ops: CanaryOps, opts: { live: boolean }): Promise<CanaryResult> {
  if (!opts.live) {
    throw new Error("Canary requires explicit --live flag");
  }

  const result: CanaryResult = {
    implementationDiscoverableOnlyWithReadyAndImplement: false,
    successfulClaimConsumesImplement: false,
    staleReconciliationReleasesWithoutRestoring: false,
    researchDiscoverableFromWayfinderAlone: false,
    contradictionsFailBeforeWorker: false,
    fixturesCleaned: false,
    cleanupFailures: [],
    fixtureIds: [],
  };

  const fixtures: number[] = [];

  try {
    // 1. Create implementation fixtures
    const implTitle = uniqueTitle("Canary impl");
    const implBody = `Scope bounded observable outcome\nno unresolved design decided\nacceptance criteria done when\nverification path verify\ndependencies blocked by none\nsmall enough for one session\nvertical tracer bullet slice`;
    
    // Create with only ready-for-agent (no implement) — should NOT be discoverable
    const implOnlyReady = await ops.createIssue(implTitle + " ready-only", implBody, [READY_FOR_AGENT]);
    fixtures.push(implOnlyReady);
    result.fixtureIds.push(implOnlyReady);
    let issueReadyOnly = await ops.fetchIssue(implOnlyReady);
    const eligibleReadyOnly = isImplementationEligible(issueReadyOnly);
    if (eligibleReadyOnly.eligible) throw new Error("ready-only should not be implementation eligible");

    // Create with ready + implement — SHOULD be discoverable
    const implReadyImplement = await ops.createIssue(implTitle + " ready+implement", implBody, [READY_FOR_AGENT, AGENT_IMPLEMENT]);
    fixtures.push(implReadyImplement);
    result.fixtureIds.push(implReadyImplement);
    let issueReadyImplement = await ops.fetchIssue(implReadyImplement);
    const eligibleReadyImplement = isImplementationEligible(issueReadyImplement);
    if (!eligibleReadyImplement.eligible) throw new Error(`ready+implement should be eligible: ${(eligibleReadyImplement as any).reason}`);
    result.implementationDiscoverableOnlyWithReadyAndImplement = true;

    // 2. Claim through production boundary — prove consumes implement, retains ready, adds in-progress + assignee
    const claimResult = await ops.claimImplementation(issueReadyImplement);
    if (!claimResult.success) throw new Error(`claim should succeed: ${claimResult.reason}`);
    let afterClaim = await ops.fetchIssue(implReadyImplement);
    const hasReady = afterClaim.labels.includes(READY_FOR_AGENT);
    const hasImplement = afterClaim.labels.includes(AGENT_IMPLEMENT);
    const hasInProgress = afterClaim.labels.includes(AGENT_IN_PROGRESS);
    const hasAssignee = afterClaim.assignees.length > 0;
    if (!hasReady || hasImplement || !hasInProgress || !hasAssignee) {
      throw new Error(`claim postcondition failed: ready=${hasReady} implement=${hasImplement} inProgress=${hasInProgress} assignee=${hasAssignee}`);
    }
    result.successfulClaimConsumesImplement = true;

    // 3. Stale reconciliation — release without restoring implement
    const reconciled = await ops.reconcile(afterClaim);
    if (!reconciled) throw new Error("reconciliation should succeed");
    let afterReconcile = await ops.fetchIssue(implReadyImplement);
    if (afterReconcile.labels.includes(AGENT_IN_PROGRESS) || afterReconcile.assignees.length > 0 || afterReconcile.labels.includes(AGENT_IMPLEMENT)) {
      throw new Error(`reconcile should release in-progress+assignee without restoring implement: ${afterReconcile.labels.join(",")} assignees=${afterReconcile.assignees.join(",")}`);
    }
    if (!afterReconcile.labels.includes(READY_FOR_AGENT)) throw new Error("ready-for-agent should remain after reconcile");
    result.staleReconciliationReleasesWithoutRestoring = true;

    // 4. Research discoverable from wayfinder:research alone
    const researchTitle = uniqueTitle("Canary research");
    const researchBody = "Canary research question\nPart of #190";
    const researchId = await ops.createIssue(researchTitle, researchBody, [WAYFINDER_RESEARCH]);
    fixtures.push(researchId);
    result.fixtureIds.push(researchId);
    let researchIssue = await ops.fetchIssue(researchId);
    const researchEligible = isResearchEligible(researchIssue);
    if (!researchEligible.eligible) throw new Error(`research should be eligible from wayfinder:research alone: ${(researchEligible as any).reason}`);
    result.researchDiscoverableFromWayfinderAlone = true;

    // 5. Contradictions fail before worker boundary
    const contraTitle = uniqueTitle("Canary contra");
    const contraId = await ops.createIssue(contraTitle, implBody, [WAYFINDER_RESEARCH, AGENT_IMPLEMENT, READY_FOR_AGENT]);
    fixtures.push(contraId);
    result.fixtureIds.push(contraId);
    let contraIssue = await ops.fetchIssue(contraId);
    const contraValidation = detectContradictions(contraIssue);
    if (contraValidation.contradictions.length === 0) throw new Error("contradictory issue should have contradictions");
    const implEligibleContra = isImplementationEligible(contraIssue);
    const researchEligibleContra = isResearchEligible(contraIssue);
    if (implEligibleContra.eligible || researchEligibleContra.eligible) throw new Error("contradictory issue should not be eligible for either profile");
    // Try to claim contradictory — should fail before worker
    const contraClaim = await ops.claimImplementation(contraIssue);
    if (contraClaim.success) throw new Error("contradictory claim should fail");
    result.contradictionsFailBeforeWorker = true;

  } finally {
    // Clean all fixtures
    for (const id of fixtures) {
      try {
        await ops.closeIssue(id);
      } catch (e) {
        result.cleanupFailures.push(`close #${id} failed: ${e}`);
      }
    }
    result.fixturesCleaned = result.cleanupFailures.length === 0;
  }

  return result;
}

// CLI
async function main() {
  const args = process.argv.slice(2);
  const live = args.includes("--live") || args.includes("--canary");
  if (!live) {
    console.error("Canary requires explicit --live flag");
    process.exit(1);
  }
  console.log("Live tracker canary — requires live GitHub access");
  // Real ops would use gh CLI — not implemented for safety in this scaffold
  // For live run, user must run after migration
  console.log("Note: canary in this implementation uses mock ops for local verification; live run requires manual gh ops wiring");
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(e => {
    console.error(e);
    process.exit(1);
  });
}
