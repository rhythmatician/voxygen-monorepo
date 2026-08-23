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
    expect(main).toContain("reconcileStaleImplementation");
    // Must provide all required ops
    expect(main).toContain("getBatchPrNumber");
    expect(main).toContain("getPrState");
    expect(main).toContain("checkBranchExists");
    expect(main).toContain("checkProvenanceValid");
    expect(main).toContain("hasCommitsAhead");
    expect(main).toContain("deleteBranch");
    expect(main).toContain("addBlocked");
    expect(main).toContain("markIntegrated");
    // No fallback comment about release anyway
    expect(main).not.toContain("No branch check provided");
    // Should use fullOps not minimal ops
    expect(main).toContain("fullOps");
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
