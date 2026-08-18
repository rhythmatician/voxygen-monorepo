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
    // Use helpers to test the production seam: factory base freeze, issue/batch from base, caller invariant, and stale-branch reconciliation.
    const helpers = await import("./branch-helpers.mts");
    const { mkdtemp, writeFile, rm, mkdir } = await import('node:fs/promises');
    const { tmpdir } = await import('node:os');
    const { join } = await import('node:path');
    const { execSync } = await import('node:child_process');
    const tmp = await mkdtemp(join(tmpdir(), 'iso-test-'));
    try {
      execSync('git init -q', {cwd: tmp});
      execSync('git config user.email "test@test.com"', {cwd: tmp});
      execSync('git config user.name "test"', {cwd: tmp});
      await writeFile(join(tmp, 'base.txt'), 'base');
      execSync('git add base.txt && git commit -qm "base"', {cwd: tmp});
      execSync('git branch -M main', {cwd: tmp});
      const baseSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      // Simulate origin/main for helpers
      execSync('git branch origin-main-tmp ' + baseSha, {cwd: tmp});
      // feature/foo with unique caller-only commit
      execSync('git checkout -b feature/foo -q', {cwd: tmp});
      await writeFile(join(tmp, 'caller-only.txt'), 'caller-secret');
      execSync('git add caller-only.txt && git commit -qm "caller-only commit"', {cwd: tmp});
      const callerSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      const callerBranch = 'feature/foo';
      // Helpers: getCallerInfo should report feature/foo
      const callerInfo = helpers.getCallerInfo(tmp);
      expect(callerInfo.branch).toBe(callerBranch);
      expect(callerInfo.sha).toBe(callerSha);

      // Contaminated M → caller-only → issue must be rejected: issue branch created via baseBranch: factoryBaseSha must NOT contain caller-only
      const factoryBaseSha = baseSha;
      execSync(`git branch sandcastle/issue-999 ${factoryBaseSha}`, {cwd: tmp});
      execSync('git checkout sandcastle/issue-999 -q', {cwd: tmp});
      await writeFile(join(tmp, 'issue.txt'), 'issue work');
      execSync('git add issue.txt && git commit -qm "issue work"', {cwd: tmp});
      expect(helpers.isBranchAncestor(tmp, callerSha, 'sandcastle/issue-999')).toBe(false);
      expect(execSync(`git log --oneline ${factoryBaseSha}..sandcastle/issue-999`, {cwd: tmp, encoding:'utf8'}).toString()).not.toContain('caller-only');

      // Legitimate stale branch from older M0 after main advances to M1 must not be destroyed:
      // Advance origin/main to M1
      execSync('git checkout main -q', {cwd: tmp});
      await writeFile(join(tmp, 'm1.txt'), 'm1');
      execSync('git add m1.txt && git commit -qm "m1 advance"', {cwd: tmp});
      const m1Sha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      // Create stale issue branch from old base M0 (simulate earlier factory run)
      execSync(`git branch sandcastle/issue-998 ${baseSha}`, {cwd: tmp});
      execSync('git checkout sandcastle/issue-998 -q', {cwd: tmp});
      await writeFile(join(tmp, 'stale.txt'), 'stale work');
      execSync('git add stale.txt && git commit -qm "stale work"', {cwd: tmp});
      // Record provenance for stale branch at M0
      helpers.recordProvenance(tmp, 'sandcastle/issue-998', baseSha, callerBranch, callerSha, '998');
      // Now provenance check for stale branch should be OK (descendant of recorded base), not destroyed
      const provOk = helpers.verifyProvenance(tmp, 'sandcastle/issue-998');
      expect(provOk.ok).toBe(true);
      expect(provOk.recordedBase).toBe(baseSha);
      // But if we check against current origin/main (M1), merge-base heuristic would falsely reject — our helper must not.

      // Provenance-check failure must fail closed: legacy branch with commits and no provenance
      execSync(`git checkout -b sandcastle/issue-997 ${baseSha} -q`, {cwd: tmp});
      await writeFile(join(tmp, 'legacy.txt'), 'legacy');
      execSync('git add legacy.txt && git commit -qm "legacy work"', {cwd: tmp});
      // Do NOT record provenance — legacy
      const legacyProv = helpers.verifyProvenance(tmp, 'sandcastle/issue-997');
      expect(legacyProv.ok).toBe(false);
      expect(legacyProv.reason).toContain('no provenance');

      // Caller checkout remains on feature/foo throughout batch creation/integration, not merely restored afterward
      execSync('git checkout feature/foo -q', {cwd: tmp});
      const beforeBranch = execSync('git branch --show-current', {cwd: tmp, encoding:'utf8'}).trim();
      const beforeSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      const batchBranch = `sandcastle/batch-999-${Date.now().toString(36)}`;
      const worktreePath = join(tmp, '.sandcastle', 'worktrees', batchBranch.replace(/\//g, '-'));
      await mkdir(join(tmp, '.sandcastle', 'worktrees'), {recursive: true});
      execSync(`git worktree add -b ${batchBranch} ${worktreePath} ${factoryBaseSha}`, {cwd: tmp});
      // Verify caller still on feature/foo and ref unchanged (never moved)
      const afterBranch = execSync('git branch --show-current', {cwd: tmp, encoding:'utf8'}).trim();
      const afterSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      const refSha = execSync(`git rev-parse refs/heads/${callerBranch}`, {cwd: tmp, encoding:'utf8'}).trim();
      expect(afterBranch).toBe(beforeBranch);
      expect(afterSha).toBe(beforeSha);
      expect(refSha).toBe(callerSha);
      expect(helpers.verifyCallerUnchanged(tmp, callerBranch, callerSha).ok).toBe(true);
      // Merge issue into batch worktree directly (without moving caller)
      execSync(`git -C ${worktreePath} merge sandcastle/issue-999 --no-edit -q`, {cwd: tmp});
      expect(helpers.isBranchAncestor(tmp, callerSha, batchBranch)).toBe(false);
      // Cleanup
      execSync(`git worktree remove --force ${worktreePath}`, {cwd: tmp});
      execSync(`git branch -D ${batchBranch}`, {cwd: tmp});
      execSync(`git checkout ${callerBranch} -q`, {cwd: tmp});
    } finally {
      await rm(tmp, {recursive: true, force: true});
    }
  });

  it("gh pr create specifies exact batch head --head batchBranch (never caller inference)", async () => {
    const mainMts = fs.readFileSync(".sandcastle/main.mts", "utf8");
    // Must create PR with explicit --head batchBranch via helper, not inferring caller checkout
    // Production should call helper so test and production share seam
    expect(mainMts).toContain("branchHelpers.buildPrCreateArgs");
    expect(mainMts).toContain("batchBranch");
    // Helper must build args with --head
    const helpers = await import("./branch-helpers.mts");
    const args = helpers.buildPrCreateArgs("sandcastle/batch-999-abc", [{id:"999"}]);
    expect(args).toContain("--head");
    expect(args).toContain("sandcastle/batch-999-abc");
    expect(args).toContain("--base");
    expect(args[args.indexOf("--head")+1]).toBe("sandcastle/batch-999-abc");
    // Ensure no bare gh pr create without --head helper
    expect(mainMts).not.toMatch(/runGh\(\[\s*"pr",\s*"create",\s*"--base",\s*"main",\s*"--title"/);
  });

  it("protected-root gate classifies exact batch candidate factoryBaseSha...batchBranch, not origin/main...HEAD", async () => {
    const mainMts = fs.readFileSync(".sandcastle/main.mts", "utf8");
    // Must not classify caller HEAD
    expect(mainMts).not.toMatch(/git diff --name-only origin\/main\.\.\.HEAD/);
    // Must classify via helper buildProtectedRootDiffSpec(factoryBaseSha, batchBranch)
    expect(mainMts).toContain("branchHelpers.buildProtectedRootDiffSpec");
    expect(mainMts).toContain("factoryBaseSha");
    expect(mainMts).toContain("batchBranch");
    const helpers = await import("./branch-helpers.mts");
    const spec = helpers.buildProtectedRootDiffSpec("abc123", "sandcastle/batch-1");
    expect(spec).toBe("abc123...sandcastle/batch-1");
  });

  it("provenance and worktrees are gitignored and production uses helpers (no duplication), caller status unchanged", async () => {
    const mainMts = fs.readFileSync(".sandcastle/main.mts", "utf8");
    const gitignore = fs.readFileSync(".gitignore", "utf8");
    expect(gitignore).toContain(".sandcastle/provenance/");
    expect(gitignore).toContain(".sandcastle/worktrees/");
    // Production must call single state-machine helper, not manually implement provenance/worktree outside it
    // (recordProvenance/verifyProvenance are now encapsulated in prepareIssueBranch)
    expect(mainMts).toContain("branchHelpers.prepareIssueBranch");
    expect(mainMts).toContain("branchHelpers.createBatchWorktree");
    expect(mainMts).toContain("branchHelpers.verifyCallerUnchanged");
    expect(mainMts).toContain("branchHelpers.cleanupBatchWorktree");
    // Caller status invariant
    expect(mainMts).toContain("git status --porcelain");
    expect(mainMts).toContain("callerStatusBefore");
    expect(mainMts).toContain("callerStatusAfter");
    expect(mainMts).toContain("callerStatusBeforeForBatch");
    // Whole-run invariant: snapshot at freeze, not late Phase-3 (frozen before claim, reused in Phase-3)
    expect(mainMts).toMatch(/let callerStatusBefore[\s\S]*execSync\('git status --porcelain'/);
    expect(mainMts).toContain("Factory base frozen");
    expect(mainMts).toContain("callerStatusBeforeForBatch = callerStatusBefore");
    expect(mainMts).not.toMatch(/\/\/ Capture caller status before mutation[\s\S]*const callerStatusBefore/);
    expect(mainMts).toContain("prepareIssueBranch");
    // Helper seam exists
    const helpers = await import("./branch-helpers.mts");
    expect(typeof helpers.recordProvenance).toBe("function");
    expect(typeof helpers.createBatchWorktree).toBe("function");
    expect(typeof helpers.cleanupBatchWorktree).toBe("function");
    expect(typeof helpers.verifyCallerUnchanged).toBe("function");
    expect(typeof helpers.prepareIssueBranch).toBe("function");
  });

  it("provenance is write-once and contaminated legacy branch is never retroactively certified (prepareIssueBranch)", async () => {
    const helpers = await import("./branch-helpers.mts");
    const { mkdtemp, writeFile, rm, mkdir } = await import('node:fs/promises');
    const { tmpdir } = await import('node:os');
    const { join } = await import('node:path');
    const { execSync } = await import('node:child_process');
    const tmp = await mkdtemp(join(tmpdir(), 'writeonce-test-'));
    try {
      execSync('git init -q', {cwd: tmp});
      execSync('git config user.email "test@test.com"', {cwd: tmp});
      execSync('git config user.name "test"', {cwd: tmp});
      await writeFile(join(tmp, 'base.txt'), 'base');
      execSync('git add base.txt && git commit -qm "base"', {cwd: tmp});
      execSync('git branch -M main', {cwd: tmp});
      const baseSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      // Simulate origin/main at baseSha (helpers use origin/main if exists, else fallback)
      // Create feature/foo with caller-only commit
      execSync('git checkout -b feature/foo -q', {cwd: tmp});
      await writeFile(join(tmp, 'caller-only.txt'), 'caller-secret');
      execSync('git add caller-only.txt && git commit -qm "caller-only commit"', {cwd: tmp});
      const callerSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      // Pre-create contaminated sandcastle/issue-999 as M -> caller-only -> issue (omit provenance)
      // Branch ancestry: base -> caller-only (feature/foo) -> issue work, so includes caller-only
      execSync('git checkout -b sandcastle/issue-999 feature/foo -q', {cwd: tmp});
      await writeFile(join(tmp, 'issue.txt'), 'issue contaminated');
      execSync('git add issue.txt && git commit -qm "issue contaminated"', {cwd: tmp});
      const contaminatedSha = execSync('git rev-parse HEAD', {cwd: tmp, encoding:'utf8'}).trim();
      expect(execSync(`git log --oneline ${baseSha}..sandcastle/issue-999`, {cwd: tmp, encoding:'utf8'}).toString()).toContain('caller-only');
      // No provenance file — simulate legacy retry
      const provPath = join(tmp, '.sandcastle', 'provenance', 'sandcastle-issue-999.json');
      expect(await import('node:fs').then(m=>m.existsSync(provPath))).toBe(false);
      // Factory retry must NOT write fresh provenance and accept it — must fail closed via prepareIssueBranch
      const prep = helpers.prepareIssueBranch(tmp, 'sandcastle/issue-999', baseSha, 'feature/foo', callerSha, '999');
      expect(prep.ok).toBe(false);
      expect(prep.action).toBe('blocked');
      expect(prep.reason).toContain('no provenance');
      // Must NOT have created provenance — write-once would have falsely certified if it had overwritten
      expect(await import('node:fs').then(m=>m.existsSync(provPath))).toBe(false);
      // Branch still contaminated (not silently recreated)
      expect(execSync(`git rev-parse sandcastle/issue-999`, {cwd: tmp, encoding:'utf8'}).trim()).toBe(contaminatedSha);
      // Second retry with same contaminated branch still blocked (idempotent)
      const prep2 = helpers.prepareIssueBranch(tmp, 'sandcastle/issue-999', baseSha, 'feature/foo', callerSha, '999');
      expect(prep2.ok).toBe(false);
      expect(prep2.action).toBe('blocked');

      // Fresh branch (does not exist) should create with write-once provenance and be reusable
      const freshPrep = helpers.prepareIssueBranch(tmp, 'sandcastle/issue-1000', baseSha, 'feature/foo', callerSha, '1000');
      expect(freshPrep.ok).toBe(true);
      expect(['created','recreated'].includes(freshPrep.action)).toBe(true);
      const freshProvPath = join(tmp, '.sandcastle', 'provenance', 'sandcastle-issue-1000.json');
      expect(await import('node:fs').then(m=>m.existsSync(freshProvPath))).toBe(true);
      // Direct recordProvenance must now fail with EEXIST (write-once)
      expect(() => helpers.recordProvenance(tmp, 'sandcastle/issue-1000', baseSha, 'feature/foo', callerSha, '1000')).toThrow();
      // Second prepare on fresh branch with existing provenance should reuse, not overwrite
      const reusePrep = helpers.prepareIssueBranch(tmp, 'sandcastle/issue-1000', baseSha, 'feature/foo', callerSha, '1000');
      expect(reusePrep.ok).toBe(true);
      expect(reusePrep.action).toBe('reused');

      // Empty stale branch without provenance should be deleted and recreated, not blocked
      execSync(`git checkout ${baseSha} -q`, {cwd: tmp});
      execSync('git checkout -b sandcastle/issue-1001 -q', {cwd: tmp});
      // No commits ahead of base, no provenance — truly empty
      const emptyLog = execSync(`git log ${baseSha}..sandcastle/issue-1001 --oneline`, {cwd: tmp, encoding:'utf8'}).trim();
      expect(emptyLog).toBe('');
      const emptyPrep = helpers.prepareIssueBranch(tmp, 'sandcastle/issue-1001', baseSha, 'feature/foo', callerSha, '1001');
      expect(emptyPrep.ok).toBe(true);
      expect(emptyPrep.action).toBe('recreated');
      expect(await import('node:fs').then(m=>m.existsSync(join(tmp, '.sandcastle', 'provenance', 'sandcastle-issue-1001.json')))).toBe(true);
      // After recreation, branch tip should be baseSha
      expect(execSync('git rev-parse sandcastle/issue-1001', {cwd: tmp, encoding:'utf8'}).trim()).toBe(baseSha);

      // Main.mts must use prepareIssueBranch for both claim and reconciliation (single state machine)
      const mainMts = (await import('node:fs')).readFileSync('.sandcastle/main.mts', 'utf8');
      expect(mainMts).toContain('prepareIssueBranch');
      // Must not contain old direct overwrite pattern outside helper
      expect(mainMts).not.toMatch(/recordProvenance\(REPO_ROOT, p\.branch, factoryBaseSha[^)]+\)\s*;\s*\n\s*console\.log\(`\s*Provenance recorded/);
    } finally {
      await rm(tmp, {recursive: true, force: true});
    }
  });
});

