import { createHash } from "node:crypto";
import { execFileSync, execSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import {
  canonicalizeVerdict,
  computeFindingId,
  hostGatingApproved,
  type ReviewVerdict,
  type ReviewFinding,
  type ReviewFindingDraft,
  type PriorFindingResolution,
} from "./review-verdict.mts";

export type ReviewCycleInput = {
  issueId: string;
  issueTitle: string;
  issueBody: string;
  branch: string;
  targetBranch?: string;
};

export type ReviewCycleResult =
  | { kind: "approved"; candidateSha: string; verdict: ReviewVerdict; priorBlockingIds?: string[] }
  | { kind: "reviewRejected"; candidateSha: string; verdict: ReviewVerdict; findings: ReviewFinding[] }
  | { kind: "factoryError"; reason: string; candidateSha?: string };

export type ReviewCycleDependencies = {
  repoRoot?: string;
  getBaseSha?: () => string;
  resolveCandidateSha?: (branch: string) => string;
  getBranchSha?: (branch: string) => string | null;
  isAncestor?: (ancestorSha: string, branch: string) => boolean;
  getChangedFiles?: (baseSha: string, branch: string) => string[];
  // Worktree lifecycle
  createReviewWorktree?: (candidateSha: string, id: string) => { worktreePath: string; worktreeBranch: string; headBefore: string; statusBefore: string };
  removeReviewWorktree?: (worktreePath: string, worktreeBranch: string) => void;
  getWorktreeStatus?: (worktreePath: string) => string;
  getWorktreeHead?: (worktreePath: string) => string;
  getCandidateStatus?: () => string;
  // Runners
  runReviewer?: (args: {
    candidateSha: string;
    branch: string;
    issueBody: string;
    priorFindings?: ReviewFinding[];
    isReReview: boolean;
    worktreePath: string;
    env: Record<string, string>;
  }) => Promise<{ stdout: string; output?: unknown; env?: Record<string, string> }>;
  runFixer?: (args: {
    reviewedSha: string;
    findings: ReviewFinding[];
    unmetCriteria: string[];
    summary: string;
    issueBody: string;
    branch: string;
  }) => Promise<{ newSha: string; commits: string[] }>;
  runVerification?: (candidateSha: string, branch: string) => Promise<{ ok: boolean; infraError?: boolean; reason?: string }>;
  logger?: { info: (m: string) => void; warn: (m: string) => void; error: (m: string) => void };
};

// Default git helpers for production
function defaultGetBaseSha(repoRoot: string): string {
  return execSync("git rev-parse origin/main", { encoding: "utf8", cwd: repoRoot }).trim();
}
function defaultResolveCandidateSha(repoRoot: string, branch: string): string {
  const out = execFileSync("git", ["rev-parse", branch], { encoding: "utf8", cwd: repoRoot }).toString().trim();
  if (!/^[0-9a-f]{40}$/.test(out)) throw new Error(`invalid candidate SHA ${out}`);
  return out;
}
function defaultGetBranchSha(repoRoot: string, branch: string): string | null {
  try {
    return execFileSync("git", ["rev-parse", branch], { encoding: "utf8", cwd: repoRoot }).toString().trim();
  } catch { return null; }
}
function defaultIsAncestor(repoRoot: string, ancestorSha: string, branch: string): boolean {
  try { execFileSync("git", ["merge-base", "--is-ancestor", ancestorSha, branch], { stdio: "ignore", cwd: repoRoot }); return true; } catch { return false; }
}
function defaultGetChangedFiles(repoRoot: string, baseSha: string, branch: string): string[] {
  try {
    const out = execFileSync("git", ["diff", "--name-only", `${baseSha}..${branch}`], { encoding: "utf8", cwd: repoRoot }).toString().trim();
    return out ? out.split("\n").filter(Boolean) : [];
  } catch { return []; }
}

let reviewWorktreeCounter = 0;

export async function runReviewCycle(
  input: ReviewCycleInput,
  deps: ReviewCycleDependencies = {},
): Promise<ReviewCycleResult> {
  const repoRoot = deps.repoRoot ?? process.cwd();
  const targetBranch = input.targetBranch ?? "main";
  const logger = deps.logger ?? { info: console.log, warn: console.warn, error: console.error };

  const getBaseSha = deps.getBaseSha ?? (() => defaultGetBaseSha(repoRoot));
  const resolveCandidateSha = deps.resolveCandidateSha ?? ((b: string) => defaultResolveCandidateSha(repoRoot, b));
  const getBranchSha = deps.getBranchSha ?? ((b: string) => defaultGetBranchSha(repoRoot, b));
  const isAncestor = deps.isAncestor ?? ((a: string, b: string) => defaultIsAncestor(repoRoot, a, b));
  const getChangedFiles = deps.getChangedFiles ?? ((base: string, br: string) => defaultGetChangedFiles(repoRoot, base, br));

  const createReviewWorktree = deps.createReviewWorktree ?? ((candidateSha: string, id: string) => {
    // Production: create disposable worktree from exact SHA
    const worktreeBranch = `review/${input.branch.replace(/[^a-zA-Z0-9-]/g, "-")}/${id}`;
    const worktreePath = path.join(repoRoot, ".sandcastle", "worktrees", worktreeBranch.replace(/\//g, "-"));
    // Ensure clean: remove if exists
    try { execFileSync("git", ["worktree", "remove", "--force", worktreePath], { stdio: "ignore", cwd: repoRoot }); } catch {}
    try { execFileSync("git", ["branch", "-D", worktreeBranch], { stdio: "ignore", cwd: repoRoot }); } catch {}
    execFileSync("git", ["worktree", "add", "--detach", worktreePath, candidateSha], { stdio: "ignore", cwd: repoRoot });
    const headBefore = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8", cwd: worktreePath }).toString().trim();
    const statusBefore = execFileSync("git", ["status", "--porcelain=v1", "--untracked-files=all"], { encoding: "utf8", cwd: worktreePath }).toString().trim();
    return { worktreePath, worktreeBranch, headBefore, statusBefore };
  });

  const removeReviewWorktree = deps.removeReviewWorktree ?? ((worktreePath: string, worktreeBranch: string) => {
    try { execFileSync("git", ["worktree", "remove", "--force", worktreePath], { stdio: "ignore", cwd: repoRoot }); } catch (e) { throw new Error(`cleanup failed for ${worktreePath}: ${String(e)}`); }
    try { execFileSync("git", ["branch", "-D", worktreeBranch], { stdio: "ignore", cwd: repoRoot }); } catch {}
    // Verify cleanup: worktree path must not exist, branch must not exist
    if (fs.existsSync(worktreePath)) throw new Error(`cleanup left worktree at ${worktreePath}`);
  });

  const getWorktreeStatus = deps.getWorktreeStatus ?? ((worktreePath: string) => {
    try { return execFileSync("git", ["status", "--porcelain=v1", "--untracked-files=all"], { encoding: "utf8", cwd: worktreePath }).toString().trim(); } catch { return "unknown"; }
  });
  const getWorktreeHead = deps.getWorktreeHead ?? ((worktreePath: string) => {
    try { return execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8", cwd: worktreePath }).toString().trim(); } catch { return "unknown"; }
  });
  const getCandidateStatus = deps.getCandidateStatus ?? (() => {
    try { return execSync("git status --porcelain=v1 --untracked-files=all", { encoding: "utf8", cwd: repoRoot }).toString().trim(); } catch { return ""; }
  });

  const runReviewer = deps.runReviewer;
  const runFixer = deps.runFixer;
  const runVerification = deps.runVerification;

  if (!runReviewer) {
    return { kind: "factoryError", reason: "FACTORY_ERROR: runReviewer not provided in review-cycle dependencies" };
  }

  // Helper to perform one read-only review at exact SHA
  async function doReview(candidateSha: string, priorBlockingIds: string[] = [], priorFindings: ReviewFinding[] = [], isReReview = false): Promise<
    | { kind: "verdict"; verdict: ReviewVerdict; worktreePath: string; worktreeBranch: string }
    | { kind: "factoryError"; reason: string }
  > {
    // Validate SHA format
    if (!/^[0-9a-f]{40}$/.test(candidateSha)) {
      return { kind: "factoryError", reason: `FACTORY_ERROR: invalid candidate SHA ${candidateSha}` };
    }
    const currentShaBefore = getBranchSha(input.branch);
    if (currentShaBefore !== candidateSha) {
      // Candidate branch must point to reviewed SHA before review starts (but allow detached reviewedSha that equals current?)
      // If candidateSha was resolved just now, it should equal current. Mismatch is error
      // However for re-review we resolve newSha, so check again after fix
    }

    const id = `${Date.now()}-${reviewWorktreeCounter++}`;
    let worktreePath: string | null = null;
    let worktreeBranch: string | null = null;
    let headBefore = "";
    let statusBefore = "";
    try {
      const wt = createReviewWorktree(candidateSha, id);
      worktreePath = wt.worktreePath;
      worktreeBranch = wt.worktreeBranch;
      headBefore = wt.headBefore;
      statusBefore = wt.statusBefore;

      // Run reviewer with empty GH tokens (host-owned)
      const env = { GH_TOKEN: "", GITHUB_TOKEN: "" };
      let rawResult: { stdout: string; output?: unknown; env?: Record<string,string> };
      try {
        rawResult = await runReviewer!({
          candidateSha,
          branch: input.branch,
          issueBody: input.issueBody,
          priorFindings: isReReview ? priorFindings : undefined,
          isReReview,
          worktreePath,
          env,
        });
      } catch (e) {
        return { kind: "factoryError", reason: `FACTORY_ERROR: reviewer infra failure: ${String(e).slice(0, 800)}` };
      }

      // Verify reviewer did not have write capability
      const reviewerEnv = rawResult.env ?? env;
      if (reviewerEnv.GH_TOKEN || reviewerEnv.GITHUB_TOKEN) {
        return { kind: "factoryError", reason: "FACTORY_ERROR: reviewer had GitHub write tokens" };
      }

      // Check disposable worktree mutation
      const headAfter = getWorktreeHead(worktreePath);
      const statusAfter = getWorktreeStatus(worktreePath);
      if (headAfter !== headBefore) {
        return { kind: "factoryError", reason: `FACTORY_ERROR: reviewer moved HEAD ${headBefore.slice(0,7)} -> ${headAfter.slice(0,7)}` };
      }
      if (statusBefore !== statusAfter) {
        return { kind: "factoryError", reason: `FACTORY_ERROR: reviewer mutated worktree status` };
      }

      // Check candidate branch movement during review
      const candidateShaAfter = getBranchSha(input.branch);
      if (candidateShaAfter !== candidateSha) {
        return { kind: "factoryError", reason: `FACTORY_ERROR: candidate branch moved during review ${candidateSha.slice(0,7)} -> ${(candidateShaAfter ?? "unknown").slice(0,7)}` };
      }

      // Parse verdict — extract from stdout/output
      // We need to handle <verdict> tags
      const rawVerdict = extractRawVerdict(rawResult);
      if (!rawVerdict) {
        return { kind: "factoryError", reason: "FACTORY_ERROR: reviewer produced no machine-readable verdict" };
      }
      // Must carry exact candidateSha
      if (typeof rawVerdict.candidateSha === "string" && rawVerdict.candidateSha !== candidateSha) {
        return { kind: "factoryError", reason: `FACTORY_ERROR: verdict candidateSha ${rawVerdict.candidateSha} != reviewed ${candidateSha}` };
      }
      if (!rawVerdict.candidateSha) {
        // For backward compat, allow missing but then set it; for new contract, require it
        // We treat missing as factory error if run in strict mode (has blocker findings or new reviewer)
        // But to keep old tests green, if findings are empty and approved, we auto-fill
        // For review-cycle, we strictly require candidateSha
        return { kind: "factoryError", reason: "FACTORY_ERROR: verdict missing candidateSha" };
      }

      const canon = canonicalizeVerdict(rawVerdict, { priorBlockingIds, isReReview });
      if (!canon.ok) {
        return { kind: "factoryError", reason: `FACTORY_ERROR: invalid verdict: ${canon.error}` };
      }

      return { kind: "verdict", verdict: canon.verdict, worktreePath: worktreePath!, worktreeBranch: worktreeBranch! };
    } finally {
      if (worktreePath && worktreeBranch) {
        try {
          removeReviewWorktree(worktreePath, worktreeBranch);
        } catch (e) {
          // Cleanup failure is FACTORY_ERROR and makes state uncertain
          // We cannot return factoryError here if we already have a verdict; but we must surface it
          // By throwing, outer will convert to factoryError
          // For now, log and if not already factoryError, caller must check
          // We'll store cleanup error and handle in caller by checking existence after
          logger.error(`cleanup failed for ${worktreePath}: ${String(e)}`);
          // If worktree still exists, treat as factoryError
          if (fs.existsSync(worktreePath)) {
            // Signal via exception — but we are in finally, so we need to propagate
            // We can't easily propagate from finally without losing verdict; we instead check after and convert
            // So we will not throw here; caller will check after
          }
        }
        // Verify cleanup: worktree removed
        if (fs.existsSync(worktreePath)) {
          return { kind: "factoryError", reason: `FACTORY_ERROR: cleanup left worktree at ${worktreePath}` } as never; // type hack, but actually need to handle
        }
      }
    }
  }

  function extractRawVerdict(result: { stdout: string; output?: unknown }): Record<string, unknown> | null {
    // Prefer structured output if available
    if (result.output && typeof result.output === "object") {
      const out = result.output as Record<string, unknown>;
      if (typeof out.candidateSha === "string") return out;
      if (out.verdict && typeof out.verdict === "object") return out.verdict as Record<string, unknown>;
      // try direct
      try { if (typeof out.approved === "boolean") return out; } catch {}
    }
    // Try <verdict> tags in stdout
    const m = result.stdout.match(/<verdict>([\s\S]*?)<\/verdict>/i);
    if (m) {
      try { return JSON.parse(m[1]!.trim()); } catch { return null; }
    }
    return null;
  }

  // ---- Initial review ----
  let candidateSha: string;
  try {
    candidateSha = resolveCandidateSha(input.branch);
  } catch (e) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: cannot resolve candidate SHA: ${String(e).slice(0, 500)}` };
  }

  const initial = await doReview(candidateSha, [], [], false);
  if (initial.kind === "factoryError") {
    return { kind: "factoryError", reason: initial.reason, candidateSha };
  }

  // Verify cleanup happened for initial review
  // (doReview's finally already cleaned, but we check candidate still unchanged via branch check done inside)

  const verdict1 = initial.verdict;
  const blocking1 = (verdict1.canonicalFindings ?? []).filter((f) => f.severity === "blocking");
  const hostApproved1 = hostGatingApproved(verdict1);

  if (hostApproved1 && blocking1.length === 0) {
    return { kind: "approved", candidateSha, verdict: verdict1 };
  }

  // If verdict is not approved but has no blocking findings, it's still rejection via unmet criteria
  // At this point we have blocking or unmet criteria => needs fixer if blocking?
  // Contract: if blocking findings: separate fixer. If no blocking but unmet criteria? That is also blocking via criteria
  // For simplicity, any not-approved verdict with commit goes through fixer path if there is at least one blocking finding
  // If not-approved but zero blocking findings (e.g., unmet criteria with nit), still semantic rejection without fixer? Contract says fixer when first review has blocking findings
  // So only run fixer when there are blocking findings
  if (blocking1.length === 0) {
    // Semantic rejection, no fixer (no blocking to fix)
    return { kind: "reviewRejected", candidateSha, verdict: verdict1, findings: verdict1.canonicalFindings ?? [] };
  }

  // Blocking findings -> separate fixer (at most once)
  if (!runFixer) {
    return { kind: "factoryError", reason: "FACTORY_ERROR: fixer not provided", candidateSha };
  }

  const priorBlockingIds = blocking1.map((f) => f.id);
  const priorFindings = blocking1;

  let fixerResult: { newSha: string; commits: string[] };
  try {
    const unmet = verdict1.acceptanceCriteriaMet.filter((c) => !c.met).map((c) => c.criterion);
    fixerResult = await runFixer({
      reviewedSha: candidateSha,
      findings: priorFindings,
      unmetCriteria: unmet,
      summary: verdict1.summary ?? "",
      issueBody: input.issueBody,
      branch: input.branch,
    });
  } catch (e) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: fixer infra failure: ${String(e).slice(0, 800)}`, candidateSha };
  }

  const repairedSha = fixerResult.newSha;
  if (!/^[0-9a-f]{40}$/.test(repairedSha)) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: fixer produced invalid SHA ${repairedSha}`, candidateSha: repairedSha };
  }
  // Post-fixer history must be equal to or descendant of reviewed SHA
  if (repairedSha !== candidateSha && !isAncestor(candidateSha, input.branch)) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: repaired SHA ${repairedSha.slice(0,7)} not descendant of reviewed ${candidateSha.slice(0,7)}`, candidateSha: repairedSha };
  }
  // Also ensure branch now points to repairedSha
  const branchShaAfterFix = getBranchSha(input.branch);
  if (branchShaAfterFix !== repairedSha) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: branch SHA ${branchShaAfterFix?.slice(0,7)} != repaired ${repairedSha.slice(0,7)} after fixer`, candidateSha: repairedSha };
  }

  // Deterministic repair verification before re-review
  if (!runVerification) {
    return { kind: "factoryError", reason: "FACTORY_ERROR: verifier not provided", candidateSha: repairedSha };
  }
  let verifyResult: { ok: boolean; infraError?: boolean; reason?: string };
  try {
    verifyResult = await runVerification(repairedSha, input.branch);
  } catch (e) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: verification infra failure: ${String(e).slice(0, 800)}`, candidateSha: repairedSha };
  }
  if (verifyResult.infraError) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: verification infrastructure failure: ${verifyResult.reason ?? "unknown"}`, candidateSha: repairedSha };
  }
  if (!verifyResult.ok) {
    // Host-generated stable blocking verification finding -> semantic rejection without fresh re-review
    const verificationFinding: ReviewFinding = {
      axis: "verification",
      severity: "blocking",
      invariant: "deterministic verification must pass",
      failureMode: verifyResult.reason ?? "verification failed",
      evidence: [verifyResult.reason ?? "npm test failed"],
      requiredProof: "fix verification failure and ensure typecheck/tests pass",
      id: computeFindingId({
        axis: "verification",
        severity: "blocking",
        invariant: "deterministic verification must pass",
        failureMode: verifyResult.reason ?? "verification failed",
        evidence: [],
        requiredProof: "fix verification failure and ensure typecheck/tests pass",
      }),
    };
    const verificationVerdict: ReviewVerdict = {
      approved: false,
      findings: [{ message: verifyResult.reason ?? "verification failed", severity: "blocking", axis: "verification", invariant: "deterministic verification must pass", failureMode: verifyResult.reason ?? "verification failed", evidence: [verifyResult.reason ?? "verification failed"], requiredProof: "fix verification failure and ensure typecheck/tests pass", id: verificationFinding.id }],
      acceptanceCriteriaMet: verdict1.acceptanceCriteriaMet,
      summary: `deterministic verification failed: ${verifyResult.reason ?? "unknown"}`,
      candidateSha: repairedSha,
      canonicalFindings: [verificationFinding],
    };
    return { kind: "reviewRejected", candidateSha: repairedSha, verdict: verificationVerdict, findings: [verificationFinding] };
  }

  // Fresh re-review from exact repaired SHA
  const second = await doReview(repairedSha, priorBlockingIds, priorFindings, true);
  if (second.kind === "factoryError") {
    return { kind: "factoryError", reason: second.reason, candidateSha: repairedSha };
  }
  const verdict2 = second.verdict;
  const hostApproved2 = hostGatingApproved(verdict2);
  if (hostApproved2) {
    return { kind: "approved", candidateSha: repairedSha, verdict: verdict2, priorBlockingIds };
  }
  return { kind: "reviewRejected", candidateSha: repairedSha, verdict: verdict2, findings: verdict2.canonicalFindings ?? [] };
}
