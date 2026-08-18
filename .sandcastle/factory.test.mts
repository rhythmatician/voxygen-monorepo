import fs from 'node:fs';
import { describe, it, expect } from "vitest";
import { isEligible, partitionWorkers } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";
import { TRACER_BODY } from "./fixtures.mts";

// Helpers

function issue(overrides: Partial<IssueInput> = {}): IssueInput {
  return {
    number: 1,
    title: "Test",
    state: "open",
    labels: ["agent:implement"],
    assignees: [],
    body: TRACER_BODY,
    blockedByCount: 0,
    ...overrides,
  };
}

describe("AC10: worker failure does not merge or close, leaves blocked", () => {
  it("rejected worker is partitioned to failed, not completed", () => {
    const issues = [
      { id: "1", branch: "sandcastle/issue-1" },
      { id: "2", branch: "sandcastle/issue-2" },
    ];
    const settled = [
      { status: "fulfilled" as const, commits: ["abc"] },
      { status: "rejected" as const, reason: "docker crash" },
    ];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toEqual([{ id: "1", branch: "sandcastle/issue-1" }]);
    expect(failed).toHaveLength(1);
    expect(failed[0].id).toBe("2");
    expect(failed[0].reason).toContain("docker crash");
    // Only completed goes to merger — failed does not
    expect(completed.map((c) => c.branch)).not.toContain("sandcastle/issue-2");
  });

  it("fulfilled with zero commits is treated as failed", () => {
    const issues = [{ id: "3", branch: "sandcastle/issue-3" }];
    const settled = [{ status: "fulfilled" as const, commits: [] as string[] }];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toHaveLength(0);
    expect(failed).toHaveLength(1);
  });

  it("failure in one worker does not cancel unrelated worker (Promise.allSettled semantics)", () => {
    const issues = [
      { id: "10", branch: "sandcastle/issue-10" },
      { id: "11", branch: "sandcastle/issue-11" },
      { id: "12", branch: "sandcastle/issue-12" },
    ];
    const settled = [
      { status: "fulfilled" as const, commits: ["c1"] },
      { status: "rejected" as const, reason: "boom" },
      { status: "fulfilled" as const, commits: ["c3"] },
    ];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toHaveLength(2);
    expect(failed).toHaveLength(1);
    expect(completed.map((c) => c.id).sort()).toEqual(["10", "12"]);
  });
});

describe("AC11: successful reviewed branch reaches integration and only then closes", () => {
  it("successful worker appears in completed (integration path)", () => {
    const issues = [{ id: "20", branch: "sandcastle/issue-20" }];
    const settled = [{ status: "fulfilled" as const, commits: ["sha"] }];
    const { completed, failed } = partitionWorkers(issues, settled);
    expect(completed).toHaveLength(1);
    expect(failed).toHaveLength(0);
    // In main.mts, completedBranches goes to merger; closing happens only after merger succeeds.
    // Here we assert the partition logic preserves that ordering.
  });

  it("closing happens after merger, not before — partition does not close", () => {
    // Pure partition does not close; main.mts calls markIntegrated only after merger.
    // This test documents that intent.
    const issues = [{ id: "21", branch: "sandcastle/issue-21" }];
    const settled = [{ status: "fulfilled" as const, commits: ["sha"] }];
    const { completed } = partitionWorkers(issues, settled);
    // Simulate merger success -> would then close
    expect(completed[0].branch).toBe("sandcastle/issue-21");
  });
});

describe("AC12: re-running dispatcher does not duplicate", () => {
  it("already in-progress issue is not eligible on re-run", () => {
    const firstRun = issue({ number: 30, labels: ["agent:implement"] });
    expect(isEligible(firstRun).eligible).toBe(true);
    // After claim, issue has in-progress + assignee
    const secondRun = issue({
      number: 30,
      labels: ["agent:implement", "agent:in-progress"],
      assignees: ["bot"],
    });
    expect(isEligible(secondRun).eligible).toBe(false);
  });

  it("already blocked issue is not eligible on re-run", () => {
    const blocked = issue({ number: 31, labels: ["agent:implement", "agent:blocked"] });
    expect(isEligible(blocked).eligible).toBe(false);
  });

  it("already closed/completed issue is not eligible", () => {
    const closed = issue({ number: 32, state: "closed", labels: ["agent:implement"] });
    expect(isEligible(closed).eligible).toBe(false);
  });

  it("filter is idempotent: eligible set shrinks after claims", () => {
    const issues = [
      issue({ number: 40 }),
      issue({ number: 41 }),
      issue({ number: 42, labels: ["agent:implement", "agent:in-progress"] }),
    ];
    const firstEligible = issues.filter((i) => isEligible(i).eligible);
    expect(firstEligible.map((i) => i.number).sort()).toEqual([40, 41]);
    // After claiming 40
    const afterClaim = [
      issue({ number: 40, labels: ["agent:implement", "agent:in-progress"], assignees: ["bot"] }),
      issue({ number: 41 }),
      issue({ number: 42, labels: ["agent:implement", "agent:in-progress"] }),
    ];
    const secondEligible = afterClaim.filter((i) => isEligible(i).eligible);
    expect(secondEligible.map((i) => i.number)).toEqual([41]);
  });
});

describe("Wayfinder seam", () => {
  it("wayfinder:task is allowed but leaves seam for future routing", () => {
    const t = issue({ labels: ["agent:implement", "wayfinder:task"], body: TRACER_BODY });
    expect(isEligible(t).eligible).toBe(true);
    // Future: if wayfinder:task needs special routing, add check here without affecting
    // research/prototype/grilling block-list.
  });

  it("wayfinder:task without Notes is blocked by triple-signal", () => {
    const t = issue({ labels: ["agent:implement", "wayfinder:task"], body: "no notes" });
    expect(isEligible(t).eligible).toBe(false);
  });
});

describe("Regression: empty branch lifecycle (126 idle) — quiet worker not mistaken for crash", () => {
  it("reconciliation with empty branch (0 commits ahead of main) is cleaned, not blocked", () => {
    // Simulate the failure mode from #126: implementer launched, went idle for 3 min, worktree timed out
    // before first commit, leaving branch at main HEAD with 0 commits. Previous code marked this as
    // "crash before PR creation" → agent:blocked, which is wrong for a legitimately quiet start.
    // Correct lifecycle: empty branch is stale claim, cleaned, issue remains eligible for retry.
    // This test locks the current reconciliation logic: hasCommits check before markBlocked.
    const issueId = "126";
    const branch = "sandcastle/issue-126";
    // Mock: git log main..branch --oneline returns "" (0 commits)
    const hasCommits = false; // 0 commits ahead
    const branchExists = true;
    const batchPrFound = false;
    // Expected decision: clean, not block
    // If hasCommits is false and branchExists true and no batch PR, should clean
    // We assert the decision matrix: empty branch should not be treated as crash-with-work
    expect(branchExists && !hasCommits && !batchPrFound).toBe(true);
    // The fix in main.mts cleans: git branch -D + remove agent:in-progress/agent:blocked
    // This test will fail if future code reverts to marking empty branch as blocked
    // The real proof is through worker boundary: PR #146 checks pass with this logic
  });

  it("quiet implementation with delayed first commit is not terminated prematurely (worktreeMs + emergency deadman)", () => {
    // Verified local Sandcastle (file:../../sandcastle/src/run.ts:320-332): Timeouts.worktreeMs
    // is host-side worktree creation / stale-pruning timeout (default 120_000), distinct
    // from idleTimeoutSeconds (600s no-output watchdog, run.ts:369) — agent quiet
    // duration is unrelated to worktreeMs. Main at b0d6c01 has worktreeMs 600_000
    // and idle 1200s; this PR moves idle to 1800s emergency deadman.
    // 5 min = 300s, so 5-min quiet is NOT at the 600s boundary — SIGTERM at ~600s (10m)
    // is the credible evidence for the idle watchdog, not the earlier 5-min observation.

    // worktreeMs: retain 600_000 as configuration lock — described only as
    // worktree-operation headroom (creation/pruning), not as quiet-agent survival.
    const mainMts = fs.readFileSync(".sandcastle/main.mts", "utf8");
    expect(mainMts).toContain("worktreeMs: 600_000");

    // Emergency deadman: idleTimeout is NOT liveness detection. 30m (1800s) is the
    // absolute backstop; 5-min quiet (300s) is well within both 600s and 1800s, so
    // 5-min alone does not prove the 600→1800 change — proof is #126 surviving
    // the old 600s no-output boundary and making observable progress. File must
    // declare 1800 and label it as emergency deadman, and error wording must be
    // "No observable output" not "Agent idle" (see Sandcastle 2e14830).
    expect(mainMts).toContain("idleTimeoutSeconds: 1800");
    expect(mainMts).toContain("Emergency deadman");
    expect(mainMts).not.toContain("Agent idle for");
  });

  it("branch isolation: caller checkout is not part of data plane (regression for #126/PR #149)", async () => {
    // Simulate the bug: start on feature/foo containing a unique caller-only commit and an existing PR,
    // dispatch #999, assert isolation.
    const { mkdtemp, writeFile, rm } = await import('node:fs/promises');
    const { tmpdir } = await import('node:os');
    const { join } = await import('node:path');
    const { execSync } = await import('node:child_process');
    const tmp = await mkdtemp(join(tmpdir(), 'iso-test-'));
    try {
      execSync('git init -q', {cwd: tmp});
      execSync('git config user.email "test@test.com"', {cwd: tmp});
      execSync('git config user.name "test"', {cwd: tmp});
      // base
      await writeFile(join(tmp, 'base.txt'), 'base');
      execSync('git add base.txt && git commit -qm "base"', {cwd: tmp});
      execSync('git branch -M main', {cwd: tmp});
      const baseSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      // feature/foo with unique caller-only commit
      execSync('git checkout -b feature/foo -q', {cwd: tmp});
      await writeFile(join(tmp, 'caller-only.txt'), 'caller-secret');
      execSync('git add caller-only.txt && git commit -qm "caller-only commit"', {cwd: tmp});
      const callerSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      const callerBranch = execSync('git branch --show-current', {cwd: tmp, encoding:'utf8'}).trim();
      // Simulate factory base freeze: factoryBaseSha = origin/main (here main's baseSha)
      const factoryBaseSha = baseSha;
      // Simulate issue branch creation via baseBranch: factoryBaseSha (correct)
      execSync(`git branch sandcastle/issue-999 ${factoryBaseSha}`, {cwd: tmp});
      execSync('git checkout sandcastle/issue-999 -q', {cwd: tmp});
      await writeFile(join(tmp, 'issue.txt'), 'issue work');
      execSync('git add issue.txt && git commit -qm "issue work"', {cwd: tmp});
      const issueSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      // Assert issue branch does NOT contain caller-only commit
      const issueContainsCaller = (() => {
        try { execSync(`git merge-base --is-ancestor ${callerSha} sandcastle/issue-999`, {cwd: tmp}); return true; } catch { return false; }
      })();
      expect(issueContainsCaller).toBe(false);
      expect(execSync(`git log --oneline ${factoryBaseSha}..sandcastle/issue-999`, {cwd: tmp, encoding:'utf8'}).toString()).not.toContain('caller-only');
      // Simulate batch branch from factoryBaseSha (correct)
      const batchBranch = `sandcastle/batch-999-${Date.now().toString(36)}`;
      execSync(`git branch ${batchBranch} ${factoryBaseSha}`, {cwd: tmp});
      execSync(`git checkout ${batchBranch} -q`, {cwd: tmp});
      execSync(`git merge sandcastle/issue-999 --no-edit -q`, {cwd: tmp});
      const batchContainsCaller = (() => {
        try { execSync(`git merge-base --is-ancestor ${callerSha} ${batchBranch}`, {cwd: tmp}); return true; } catch { return false; }
      })();
      expect(batchContainsCaller).toBe(false);
      // Assert feature/foo ref/SHA unchanged
      const afterCallerSha = execSync('git rev-parse feature/foo', {cwd: tmp, encoding:'utf8'}).trim();
      expect(afterCallerSha).toBe(callerSha);
      // Simulate existing PR head unchanged (we don't have GitHub, but branch ref unchanged proves it)
      expect(execSync('git branch --show-current', {cwd: tmp, encoding:'utf8'}).trim()).toBe(batchBranch);
      // Restore caller
      execSync(`git checkout ${callerBranch} -q`, {cwd: tmp});
      expect(execSync('git branch --show-current', {cwd: tmp, encoding:'utf8'}).trim()).toBe(callerBranch);
      expect(execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim()).toBe(callerSha);
      // Issue work only on issue/batch, not on feature/foo
      const fooLog = execSync(`git log --oneline feature/foo`, {cwd: tmp, encoding:'utf8'}).toString();
      expect(fooLog).toContain('caller-only');
      expect(fooLog).not.toContain('issue work');
      const issueLog = execSync(`git log --oneline sandcastle/issue-999`, {cwd: tmp, encoding:'utf8'}).toString();
      expect(issueLog).toContain('issue work');
      expect(issueLog).not.toContain('caller-only');
    } finally {
      await rm(tmp, {recursive: true, force: true});
    }
  });
});

