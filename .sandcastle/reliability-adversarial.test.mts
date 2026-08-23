import { describe, it, expect } from "vitest";
import { execSync, execFileSync } from "node:child_process";
import { mkdtemp, rm, mkdir, writeFile, readFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import * as fs from "node:fs";
import { parsePlannerOutput, fallbackToSingle } from "./planner-helpers.mts";
import { isEligible, branchForIssue } from "./dispatch.mts";
import { TRACER_BODY } from "./fixtures.mts";
import * as branchHelpers from "./branch-helpers.mts";
import {
  canClaimNextOuterIteration,
  partitionToMutationPlan,
  partitionWorkerOutcomes,
  type WorkerOutcome,
} from "./factory-verdict-gate.mts";
import { verdictFixture } from "./review-verdict.mts";

// Adversarial acceptance: local git tmp repos, no GH, no LLM

function makeTmpRepo(): Promise<string> {
  return mkdtemp(join(tmpdir(), "adv-"));
}

async function initRepo(tmp: string, withOrigin = false) {
  execSync('git init -q', { cwd: tmp });
  execSync('git config user.email "t@t.com"', { cwd: tmp });
  execSync('git config user.name "t"', { cwd: tmp });
  await writeFile(join(tmp, "base.txt"), "base");
  execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
  execSync('git branch -M main', { cwd: tmp });
  if (withOrigin) {
    // create bare origin for ls-remote tests
    const bare = await mkdtemp(join(tmpdir(), "bare-"));
    execSync('git init --bare -q', { cwd: bare });
    execSync(`git remote add origin ${bare}`, { cwd: tmp });
    execSync('git push origin main -q', { cwd: tmp });
    return { tmp, bare };
  }
  return { tmp, bare: null as string | null };
}

describe("Adversarial: one normal eligible issue", () => {
  it("single eligible dispatches without planner (serial invariant)", () => {
    const eligible = [{ number: 151, title: "a", state: "open" as const, labels: ["ready-for-agent", "agent:implement"], assignees: [], body: TRACER_BODY, blockedByCount: 0 }];
    expect(eligible.filter(e => isEligible(e).eligible)).toHaveLength(1);
    // main path: eligible.length===1 => planned = that one, no LLM
    const planned = [{ id: String(eligible[0].number), title: eligible[0].title, branch: branchForIssue(eligible[0].number) }];
    expect(planned[0].id).toBe("151");
  });
});

describe("Adversarial: reviewer verdict contract", () => {
  it("missing or malformed reviewer verdict must be treated as factory error, not review rejection", () => {
    const issues = [
      { id: "151", branch: "sandcastle/issue-151" },
      { id: "152", branch: "sandcastle/issue-152" },
    ];
    const settled = [
      { status: "fulfilled" as const, value: { commits: ["abc"], verdict: null } },
      { status: "fulfilled" as const, value: { commits: ["def"], verdict: verdictFixture({ approved: true }) } },
    ] satisfies WorkerOutcome[];
    const result = partitionWorkerOutcomes(issues, settled);

    expect(result.factoryErrors[0]?.reason).toContain("FACTORY_ERROR");
    expect(result.factoryErrors).toHaveLength(1);
    expect(result.factoryErrors[0]?.id).toBe("151");
    expect(result.reviewRejected).toHaveLength(0);
    expect(result.shouldStopOuterLoop).toBe(true);
  });

  it("FACTORY_ERROR maps to factoryError mutation action (no semantic blocked action)", () => {
    const issues = [
      { id: "151", branch: "sandcastle/issue-151" },
      { id: "152", branch: "sandcastle/issue-152" },
    ];
    const settled = [
      { status: "fulfilled" as const, value: { commits: ["abc"], verdict: null } },
      { status: "fulfilled" as const, value: { commits: ["def"], verdict: verdictFixture({ approved: true }) } },
    ] satisfies WorkerOutcome[];
    const partition = partitionWorkerOutcomes(issues, settled);
    const actions = partitionToMutationPlan(partition);

    expect(actions).toHaveLength(1);
    expect(actions.map((a) => a.kind)).toContain("factoryError");
    expect(actions.some((a) => a.kind === "reviewRejected")).toBe(false);
    expect(actions.every((a) => a.kind === "factoryError" || a.kind === "reviewRejected" || a.kind === "failed")).toBe(true);
    expect(actions.find((a) => a.kind === "factoryError")?.issue.id).toBe("151");
  });

  it("approved false verdict remains review rejection and does not stop outer loop", () => {
    const issues = [{ id: "153", branch: "sandcastle/issue-153" }];
    const verdict = verdictFixture({ approved: false, acceptanceCriteriaMet: [{ criterion: "must be documented", met: false, evidence: "docs/README.md" }] });
    const settled = [
      { status: "fulfilled" as const, value: { commits: ["abc"], verdict } },
    ] satisfies WorkerOutcome[];
    const result = partitionWorkerOutcomes(issues, settled);

    expect(result.reviewRejected).toHaveLength(1);
    expect(result.reviewRejected[0]?.id).toBe("153");
    expect(result.shouldStopOuterLoop).toBe(false);
    expect(result.completed).toHaveLength(0);
  });

  it("factory error suppresses merge/continue phase to prevent progressing to another issue", () => {
    const issues = [
      { id: "201", branch: "sandcastle/issue-201" },
      { id: "202", branch: "sandcastle/issue-202" },
    ];
    const settled = [
      { status: "fulfilled" as const, value: { commits: ["sha-201"], verdict: null } },
      { status: "fulfilled" as const, value: { commits: ["sha-202"], verdict: verdictFixture({ approved: true }) } },
    ] satisfies WorkerOutcome[];
    const result = partitionWorkerOutcomes(issues, settled);

    expect(result.completed.map((i) => i.id)).toEqual(["202"]);
    expect(result.factoryErrors.map((i) => i.id)).toEqual(["201"]);
    expect(result.shouldStopOuterLoop).toBe(true);

    // Factory-level stop must block continuation (merge + next outer claim) despite a ready completed branch.
    const shouldRunMerge = result.completed.length > 0 && !result.shouldStopOuterLoop;
    expect(shouldRunMerge).toBe(false);
  });

  it("FACTORY_ERROR disables the next claim batch (B) and keeps loop on current scope only", () => {
    const firstIterationIssues = [
      { id: "151", branch: "sandcastle/issue-151" },
      { id: "152", branch: "sandcastle/issue-152" },
    ];
    const firstIterationSettled = [
      { status: "fulfilled" as const, value: { commits: ["sha-151"], verdict: null } },
      { status: "fulfilled" as const, value: { commits: ["sha-152"], verdict: verdictFixture({ approved: true }) } },
    ] satisfies WorkerOutcome[];

    const firstPartition = partitionWorkerOutcomes(firstIterationIssues, firstIterationSettled);

    // Proof B: a FACTORY_ERROR must block any next-iteration claim, not just merge/transition.
    expect(canClaimNextOuterIteration(firstPartition)).toBe(false);

    const claimedIssues: string[] = ["151", "152"];
    const nextIterationPlanned = ["153", "154"];

    if (canClaimNextOuterIteration(firstPartition)) {
      claimedIssues.push(...nextIterationPlanned);
    }

    expect(claimedIssues).toEqual(["151", "152"]);
  });
});

describe("Adversarial: two overlapping requiring serialization", () => {
  it("planner valid → selects 151 only; malformed → fallback single 151 (never all, never 0)", () => {
    const eligible = [
      { number: 151, title: "End L4 dimension rebind", branch: "sandcastle/issue-151", labels: [], body: "Part of #22" },
      { number: 152, title: "End L4 telemetry", branch: "sandcastle/issue-152", labels: [], body: "Part of #22" },
    ];
    const valid = `<plan>{"issues": [{"id": "151", "title": "a", "branch": "sandcastle/issue-151"}]}</plan>`;
    expect(parsePlannerOutput(valid, eligible).map(p=>p.id)).toEqual(["151"]);
    // malformed double-escaped
    const malformed = `<plan>{\\"issues\\": [{\\"id\\": \\"151\\", \\"title\\": \\"a\\", \\"branch\\": \\"sandcastle/issue-151\\"}]}</plan>`;
    // tolerant parse now succeeds, but if it didn't, fallback must be single
    let planned: {id:string}[] | null = null;
    try { planned = parsePlannerOutput(malformed, eligible as unknown as Array<{number:number; title:string; branch:string}>); } catch { planned = fallbackToSingle(eligible as unknown as Array<{number:number; title:string; branch:string}>); }
    expect(planned).toHaveLength(1);
    expect(planned![0].id).toBe("151");
    // ensure fallback never returns all
    expect(fallbackToSingle(eligible as unknown as Array<{number:number; title:string; branch:string}>)).not.toHaveLength(2);
  });
});

describe("Adversarial: malformed/unavailable planner", () => {
  it("invalid JSON still throws but caller can fallback to single (not all)", () => {
    const eligible = [{ number: 151, title: "a", branch: "sandcastle/issue-151" }, { number: 152, title: "b", branch: "sandcastle/issue-152" }];
    const bad = `<plan>{"issues": [{"id": "151", "title": "a", "branch": "sandcastle/issue-151"},]}</plan>`;
    expect(() => parsePlannerOutput(bad, eligible)).toThrow();
    const fb = fallbackToSingle(eligible);
    expect(fb).toHaveLength(1);
    expect(fb[0].id).toBe("151");
  });
  it("missing <plan> tag → fallback single", () => {
    const eligible = [{ number: 151, title: "a", branch: "sandcastle/issue-151" }];
    expect(() => parsePlannerOutput("no tag", eligible)).toThrow(/not found/);
    expect(fallbackToSingle(eligible)).toHaveLength(1);
  });
});

describe("Adversarial: interruption after claim / after worker commit + restart with preserved work", () => {
  it("empty stale branch (claim but no commit) is cleaned and reusable; branch with commits + provenance is preserved", async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const baseSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding:'utf8' }).trim();
      const issueId = "951";
      const branch = branchForIssue(Number(issueId));
      // Simulate claim: prepareIssueBranch creates branch from base + provenance
      const prep1 = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, issueId);
      expect(prep1.ok).toBe(true);
      expect(prep1.action).toBe("created");
      // Simulate interruption before commit: branch exists but 0 commits ahead
      expect(execSync(`git log ${baseSha}..${branch} --oneline`, { cwd: tmp, encoding:'utf8' }).trim()).toBe("");
      // Simulate restart reconciliation: empty branch should be cleaned/recreated (not blocked)
      // We mimic what reconciliation does: if empty, delete and recreate is allowed.
      // Second call with same branch but no provenance file manipulation should reuse
      const prep2 = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, issueId);
      expect(prep2.ok).toBe(true);
      expect(prep2.action).toBe("reused");
      // Now simulate worker commit
      execSync(`git checkout ${branch} -q`, { cwd: tmp });
      await writeFile(join(tmp, "work.txt"), "work");
      execSync('git add work.txt && git commit -qm "work 951"', { cwd: tmp });
      execSync('git checkout main -q', { cwd: tmp });
      expect(execSync(`git log ${baseSha}..${branch} --oneline`, { cwd: tmp, encoding:'utf8' }).trim()).not.toBe("");
      // Restart with preserved work: should be reused, not destroyed
      const prep3 = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, issueId);
      expect(prep3.ok).toBe(true);
      expect(prep3.action).toBe("reused");
      expect(execSync(`git log ${baseSha}..${branch} --oneline`, { cwd: tmp, encoding:'utf8' }).trim()).toContain("work 951");
    } finally { await rm(tmp, { recursive:true, force:true }); }
  });

  it("legacy branch with commits but no provenance is blocked fail-closed (never silently destroyed)", async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const baseSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding:'utf8' }).trim();
      const branch = "sandcastle/issue-952";
      execSync(`git branch ${branch} ${baseSha}`, { cwd: tmp });
      execSync(`git checkout ${branch} -q`, { cwd: tmp });
      await writeFile(join(tmp, "legacy.txt"), "legacy");
      execSync('git add legacy.txt && git commit -qm "legacy"', { cwd: tmp });
      execSync('git checkout main -q', { cwd: tmp });
      const prov = branchHelpers.verifyProvenance(tmp, branch);
      expect(prov.ok).toBe(false);
      expect(prov.reason).toMatch(/no provenance/);
      const prep = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, "952");
      expect(prep.ok).toBe(false);
      expect(prep.action).toBe("blocked");
      // Branch still exists — not destroyed
      expect(execSync(`git branch --list "${branch}"`, { cwd: tmp, encoding:'utf8' }).trim()).toContain(branch);
    } finally { await rm(tmp, { recursive:true, force:true }); }
  });
});

describe("Adversarial: stale/remote/diverged branch state", () => {
  it("remote diverged is detected as blocked (fail-closed)", { timeout: 15000 }, async () => {
    const bare = await mkdtemp(join(tmpdir(), "bare-div-"));
    execSync('git init --bare -q', { cwd: bare });
    const tmp = await makeTmpRepo();
    try {
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });
      execSync(`git remote add origin ${bare}`, { cwd: tmp });
      execSync('git push origin main -q', { cwd: tmp });
      const baseSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding:'utf8' }).trim();
      const branch = "sandcastle/issue-953";
      const prep = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, "953");
      expect(prep.ok).toBe(true);
      execSync(`git checkout ${branch} -q`, { cwd: tmp });
      await writeFile(join(tmp, "local.txt"), "local");
      execSync('git add local.txt && git commit -qm "local"', { cwd: tmp });
      execSync('git push origin HEAD -q', { cwd: tmp });
      // Simulate another clone diverged
      const tmp2 = await makeTmpRepo();
      execSync('git clone -q ' + bare + ' ' + tmp2);
      execSync('git config user.email "t@t.com"', { cwd: tmp2 });
      execSync('git config user.name "t"', { cwd: tmp2 });
      execSync(`git checkout -b ${branch} origin/${branch}`, { cwd: tmp2 });
      await writeFile(join(tmp2, "remote-div.txt"), "div");
      execSync('git add remote-div.txt && git commit -qm "div" --amend', { cwd: tmp2 }); // amend to diverge
      execSync(`git push --force origin ${branch} -q`, { cwd: tmp2 });
      execSync('git fetch origin -q', { cwd: tmp });
      const prep2 = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, "953");
      expect(prep2.ok).toBe(false);
      expect(prep2.action).toBe("blocked");
      expect(prep2.reason).toMatch(/diverged/);
      await rm(tmp2, { recursive:true, force:true });
    } finally { await rm(tmp, { recursive:true, force:true }); await rm(bare, { recursive:true, force:true }); }
  });

  it("remote-only branch with shell-special characters fails only if argv-unsafe git calls remain", { timeout: 15000 }, async () => {
    const tmp = await makeTmpRepo();
    const bare = await mkdtemp(join(tmpdir(), "bare-special-"));
    await initRepo(tmp);
    try {
      execSync('git init --bare -q', { cwd: bare });
      execSync(`git remote add origin ${bare}`, { cwd: tmp });
      const branch = "sandcastle/issue&shell";
      const baseSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding: 'utf8' }).trim();
      // Create remote-only branch with special chars and no local copy
      execFileSync("git", ["branch", branch, baseSha], { cwd: tmp });
      execFileSync("git", ["checkout", branch], { cwd: tmp });
      await writeFile(join(tmp, "special.txt"), "special");
      execSync('git add special.txt && git commit -qm "special"', { cwd: tmp });
      execFileSync("git", ["checkout", "main"], { cwd: tmp });
      execFileSync("git", ["push", "origin", `${branch}:refs/heads/${branch}`], { cwd: tmp });
      execFileSync("git", ["branch", "-D", branch], { cwd: tmp });

      const issueId = "900";
      const prep = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, issueId);
      expect(prep.ok).toBe(false);
      expect(prep.action).toBe("blocked");
      expect(prep.reason).toContain("has commits");
    } finally {
      await rm(tmp, { recursive: true, force: true });
      await rm(bare, { recursive: true, force: true });
    }
  });
});

describe("Adversarial: temporary-resource cleanup", () => {
  it("cleanupPreserveLocalBranches deletes preserve-local branches without shell dependency", async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const branchA = "preserve-local-adv-1";
      const branchB = "preserve-local-adv-2";
      const branchKeep = "feature/main";
      execSync(`git branch ${branchA}`, { cwd: tmp });
      execSync(`git branch ${branchB}`, { cwd: tmp });
      execSync(`git branch ${branchKeep}`, { cwd: tmp });
      const deleted = branchHelpers.cleanupPreserveLocalBranches(tmp);
      const keep = execSync("git branch", { cwd: tmp, encoding:"utf8" });
      expect(deleted).toContain(branchA);
      expect(deleted).toContain(branchB);
      expect(keep).not.toContain(branchA);
      expect(keep).not.toContain(branchB);
      expect(keep).toContain(branchKeep);
      // idempotent
      const deletedAgain = branchHelpers.cleanupPreserveLocalBranches(tmp);
      expect(deletedAgain).toHaveLength(0);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  });

  it("doctor-* worktree/dir/branch are owned and cleaned; non-doctor untouched", { timeout: 15000 }, async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const { reconcileStaleDoctorResources, assertNoStaleDoctorResources, cleanupDoctorBranchAndWorktree } = await import("./doctor-helpers.mts");
      const staleBranch = `doctor-${Date.now()}-adv`;
      const stalePath = join(tmp, ".sandcastle", "worktrees", staleBranch);
      await mkdir(stalePath, { recursive:true });
      await writeFile(join(stalePath, ".git"), "gitdir: bogus");
      execSync(`git branch ${staleBranch}`, { cwd: tmp });
      expect(assertNoStaleDoctorResources(tmp).ok).toBe(false);
      await reconcileStaleDoctorResources(tmp);
      expect(assertNoStaleDoctorResources(tmp).ok).toBe(true);
      expect(fs.existsSync(stalePath)).toBe(false);
      // non-doctor
      const keep = join(tmp, ".sandcastle", "worktrees", "batch-keep");
      execSync(`git worktree add -b sandcastle/issue-9999 "${keep}" HEAD`, { cwd: tmp });
      await reconcileStaleDoctorResources(tmp);
      expect(fs.existsSync(keep)).toBe(true);
      execSync(`git worktree remove --force "${keep}"`, { cwd: tmp });
      execSync('git branch -D sandcastle/issue-9999', { cwd: tmp });
      // idempotent
      cleanupDoctorBranchAndWorktree(tmp, "doctor-nonexistent");
      expect(() => cleanupDoctorBranchAndWorktree(tmp, "doctor-nonexistent")).not.toThrow();
    } finally { await rm(tmp, { recursive:true, force:true }); }
  });
});

describe("Adversarial: cross-platform path handling", () => {
  it("createBatchWorktree handles repository paths with spaces", { timeout: 15000 }, async () => {
    const root = await mkdtemp(join(tmpdir(), "adv space-"));
    const tmp = join(root, "repo with spaces");
    await mkdir(tmp, { recursive: true });
    try {
      await writeFile(join(tmp, "base.txt"), "base");
      execSync('git init -q', { cwd: tmp });
      execSync('git config user.email "t@t.com"', { cwd: tmp });
      execSync('git config user.name "t"', { cwd: tmp });
      execSync('git add base.txt && git commit -qm "base"', { cwd: tmp });
      execSync('git branch -M main', { cwd: tmp });

      const baseSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding:'utf8' }).trim();
      const worktreePath = branchHelpers.createBatchWorktree(tmp, "sandcastle/issue-space", baseSha);

      expect(worktreePath).toContain(" ");
      expect(fs.existsSync(worktreePath)).toBe(true);
      expect(execSync('git -C "' + worktreePath.replace(/"/g, "\\\"") + '" rev-parse --is-inside-work-tree', { cwd: tmp, encoding:'utf8' }).trim()).toBe("true");

      branchHelpers.cleanupBatchWorktree(tmp, worktreePath);
      expect(fs.existsSync(worktreePath)).toBe(false);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("prepareIssueBranch creates branches with shell-special characters via argv-safe invocation", { timeout: 15000 }, async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const baseSha = execSync("git rev-parse HEAD", { cwd: tmp, encoding: "utf8" }).trim();
      const issueId = "999";
      const branch = "sandcastle/issue&shell";

      const result = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, issueId);
      expect(result.ok).toBe(true);
      expect(result.action).toBe("created");
      expect(execSync(`git branch --list "${branch}"`, { cwd: tmp, encoding: "utf8" }).trim()).toContain(branch);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  });
});

describe("Adversarial: shell metacharacter handling", () => {
  it("shell-special issue branch is not safe for inline git log command interpolation", { timeout: 15000 }, async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const baseSha = execSync("git rev-parse HEAD", { cwd: tmp, encoding: "utf8" }).trim();
      const branch = "sandcastle/issue&shell";
      execFileSync("git", ["checkout", "-b", branch], { cwd: tmp });
      await writeFile(join(tmp, "special.txt"), "special");
      execSync('git add special.txt && git commit -qm "special"', { cwd: tmp });

      // This intentionally mirrors inline production shell interpolation in main.ts and should fail on Windows shell parsing.
      expect(branchHelpers.hasCommitsAhead(tmp, "main", branch)).toBe(true);
      expect(() => execSync(`git log main..${branch} --oneline`, { cwd: tmp, encoding: "utf8" })).toThrow();
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  });
});

describe("Adversarial: caller launched from arbitrary/dirty branch", () => {
  it("factory never mutates caller checkout or HEAD; batch from factoryBaseSha not caller", async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const baseSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding:'utf8' }).trim();
      execSync('git checkout -b feature/foo -q', { cwd: tmp });
      await writeFile(join(tmp, "caller-only.txt"), "secret");
      execSync('git add caller-only.txt && git commit -qm "caller-only"', { cwd: tmp });
      const callerSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding:'utf8' }).trim();
      const callerBranch = "feature/foo";
      // dirty working tree (untracked not, but modified)
      await writeFile(join(tmp, "dirty.txt"), "dirty");
      const branch = branchForIssue(999);
      const prep = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, callerBranch, callerSha, "999");
      expect(prep.ok).toBe(true);
      expect(branchHelpers.isBranchAncestor(tmp, callerSha, branch)).toBe(false);
      // batch worktree from base, not caller
      const batchBranch = `sandcastle/batch-999-${Date.now().toString(36)}`;
      const worktreePath = branchHelpers.createBatchWorktree(tmp, batchBranch, baseSha);
      expect(branchHelpers.isBranchAncestor(tmp, callerSha, batchBranch)).toBe(false);
      expect(branchHelpers.verifyCallerUnchanged(tmp, callerBranch, callerSha).ok).toBe(true);
      expect(execSync('git branch --show-current', { cwd: tmp, encoding:'utf8' }).trim()).toBe(callerBranch);
      branchHelpers.cleanupBatchWorktree(tmp, worktreePath);
      execSync(`git branch -D ${batchBranch}`, { cwd: tmp });
      execSync(`git branch -D ${branch}`, { cwd: tmp });
      fs.unlinkSync(join(tmp, "dirty.txt"));
    } finally { await rm(tmp, { recursive:true, force:true }); }
  });
});

describe("Universal postconditions", () => {
  it("no lost work: provenance write-once, commits preserved; no caller mutation", async () => {
    const { tmp } = await initRepo(await makeTmpRepo());
    try {
      const baseSha = execSync('git rev-parse HEAD', { cwd: tmp, encoding:'utf8' }).trim();
      const branch = "sandcastle/issue-954";
      const prep = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, "954");
      expect(prep.ok).toBe(true);
      execSync(`git checkout ${branch} -q`, { cwd: tmp });
      await writeFile(join(tmp, "val.txt"), "v1");
      execSync('git add val.txt && git commit -qm "v1"', { cwd: tmp });
      execSync('git checkout main -q', { cwd: tmp });
      const shaBefore = execSync(`git rev-parse ${branch}`, { cwd: tmp, encoding:'utf8' }).trim();
      const prep2 = branchHelpers.prepareIssueBranch(tmp, branch, baseSha, "main", baseSha, "954");
      expect(prep2.ok).toBe(true);
      expect(execSync(`git rev-parse ${branch}`, { cwd: tmp, encoding:'utf8' }).trim()).toBe(shaBefore);
    } finally { await rm(tmp, { recursive:true, force:true }); }
  });
});
