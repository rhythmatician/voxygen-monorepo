import { describe, it, expect } from "vitest";
import * as fs from "node:fs";

function readFile(path: string): string {
  return fs.readFileSync(path, "utf8");
}

describe("production-consumer guardrails", () => {
  it("tracker migration CLI is thin adapter around runTrackerMigration", () => {
    const migration = readFile(".sandcastle/tracker-migration.mts");
    // Must export runTrackerMigrationCli and main must call it
    expect(migration).toContain("export async function runTrackerMigrationCli");
    expect(migration).toContain("export async function runTrackerMigration");
    // main() should delegate to runTrackerMigrationCli, not duplicate mutation loops
    expect(migration).toMatch(/async function main\(\)[\s\S]*?runTrackerMigrationCli/);
    // No duplicate inline mutation loop outside runTrackerMigration — check that only one occurrence of updateIssueLabels loop exists and it's inside runTrackerMigration
    const loopMatches = [...migration.matchAll(/for\s*\(\s*const\s+m\s+of\s+plan\.plannedMutations/g)];
    // Should be exactly one in runTrackerMigration (the apply loop)
    expect(loopMatches.length).toBe(1);
    // Ensure CLI adapter does not contain direct gh issue edit for migration (it should delegate)
    const cliSection = migration.slice(migration.indexOf("export async function runTrackerMigrationCli"));
    expect(cliSection).toContain("runTrackerMigration({");
    expect(cliSection).not.toContain("for (const m of plan.plannedMutations)");
  });

  it("main reconciliation calls full reconciliation adapter with required ops", () => {
    const main = readFile(".sandcastle/main.mts");
    const adapter = readFile(".sandcastle/reconcile-adapter.mts");
    const trackerAdapter = readFile(".sandcastle/tracker-adapter.mts");
    // One-authority rule: main consumes the tracker adapter's reconcile port,
    // never createProductionReconcileOps directly.
    expect(main).toContain("reconcileStaleImplementation");
    expect(main).toContain("tracker.reconcileStaleImplementation");
    expect(main).not.toContain("createProductionReconcileOps");
    // The tracker adapter is the consumer-facing authority; reconcile-adapter
    // is its internal Git/worktree INSPECTION implementation — it must not
    // remain a second raw GitHub state-transition authority.
    expect(trackerAdapter).toContain("createProductionReconcileOps");
    expect(trackerAdapter).toContain("reconcileStaleImplementation");
    // One-authority rule: no issue/label mutation ops inside reconcile-adapter.
    expect(adapter).not.toContain("addBlocked");
    expect(adapter).not.toContain("markIntegrated");
    expect(adapter).not.toContain("releaseClaim:");
    expect(adapter).not.toMatch(/issue",\s*"edit/);
    expect(adapter).not.toMatch(/issue",\s*"close/);
    // The adapter wires its own saga transitions as reconciliation's GitHub port.
    expect(trackerAdapter).toContain("ReconcileGitHubTransitions");
    // Adapter must provide all required ops (authoritative safety)
    expect(adapter).toContain("getBatchPrNumber");
    expect(adapter).toContain("getPrState");
    expect(adapter).toContain("checkBranchExists");
    expect(adapter).toContain("checkProvenanceValid");
    expect(adapter).toContain("hasCommitsAhead");
    expect(adapter).toContain("deleteBranch");
    expect(adapter).toContain("fetchIssue");
    // No fallback comment about release anyway
    expect(main).not.toContain("No branch check provided");
  });

  it("canary CLI calls tested live adapter with injected runner", () => {
    const canary = readFile(".sandcastle/tracker-canary.mts");
    expect(canary).toContain("export function createLiveCanaryOps");
    expect(canary).toContain("export async function runCanaryCli");
    expect(canary).toContain("export async function resolveClaimantLogin");
    // Must use claimantLogin
    expect(canary).toContain("claimantLogin");
    // Must use gh api POST for creation
    expect(canary).toContain('api", "--method", "POST"');
    // Cleanup must remove assignee and transient labels
    expect(canary).toContain("cleanupIssue");
    expect(canary).toContain("AGENT_IN_PROGRESS");
    // Check that main canary uses createLiveCanaryOps
    expect(canary).toMatch(/createLiveCanaryOps[\s\S]*runCanary/);
    // Reconciliation must be executable: real or injected GitRunner, never an
    // always-failing stub.
    expect(canary).not.toContain("canary has no local git");
    expect(canary).toMatch(/runGit \?\?|runGit:/);
  });

  it("migration composes no direct issue mutations outside the tracker adapter", () => {
    const migration = readFile(".sandcastle/tracker-migration.mts");
    // Issue label mutations route through the adapter's verified saga port.
    expect(migration).toContain("makeIssueLabelMutationPort");
    // No raw `gh issue edit` composition for issue state in the CLI adapter.
    const cliSection = migration.slice(migration.indexOf("export async function runTrackerMigrationCli"));
    expect(cliSection).not.toMatch(/issue",\s*"edit/);
  });

  it("no optional production fallback for required safety operations", () => {
    const ops = readFile(".sandcastle/tracker-operations.mts");
    // Should have decideReconciliation and executeReconciliation
    expect(ops).toContain("export function decideReconciliation");
    expect(ops).toContain("export async function executeReconciliation");
    // Should fail closed if required op missing
    expect(ops).toContain("fail closed");
    // Should not contain legacy fallback phrase
    expect(ops).not.toContain("fallback to old simple behavior");
  });
});
