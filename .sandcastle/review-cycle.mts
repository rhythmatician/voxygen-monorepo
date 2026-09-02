import { execFileSync, execSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import {
  canonicalizeVerdict,
  computeFindingId,
  hostGatingApproved,
  type ReviewVerdict,
  type ReviewFinding,
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

/**
 * A single reviewer execution workspace, anchored at the exact candidate SHA.
 *
 * Per ADR 0017, the cycle delegates worktree creation, sandbox lifecycle, and
 * cleanup to a Sandcastle-owned resource. The same handle is what the
 * reviewer inspected, what the cycle checks for mutations, and what gets torn
 * down at the end of the review. There is no second, observable worktree.
 *
 * `close()` MUST throw if the cleanup is uncertain (e.g. the worktree was
 * dirty and Sandcastle preserved it on disk instead of deleting it). The
 * cycle converts every throw into `FACTORY_ERROR`; silent swallow of cleanup
 * failures is explicitly forbidden.
 */
export type ReviewerWorkspace = {
  /** Host path to the worktree bound to this review. */
  readonly worktreePath: string;
  /** Sandcastle branch used for the review (may be detached at candidateSha). */
  readonly branch: string;
  /** HEAD SHA at the moment the workspace was opened (== candidateSha). */
  readonly head: string;
  /** `git status --porcelain` snapshot at the moment the workspace was opened. */
  readonly status: string;
  /**
   * Re-snapshot the workspace's current HEAD and status. Used by the cycle
   * AFTER the reviewer has finished so the mutation check uses the very same
   * Sandcastle-managed resource the reviewer acted on.
   */
  snapshot(): { head: string; status: string };
  /**
   * Invoke an agent inside this workspace's sandbox. The workspace owner
   * binds the agent to the worktree; the reviewer callback forwards its
   * prepared run options (prompt, output schema, agent, etc.) through this
   * single seam. This keeps ONE sandbox per review — the same resource the
   * cycle snapshots for mutations and tears down via `close()`.
   */
  runAgent(opts: Record<string, unknown>): Promise<{ stdout?: string; output?: unknown }>;
  /**
   * Tear down the sandbox and worktree. Must throw when cleanup is
   * uncertain — the cycle converts every throw into `FACTORY_ERROR`.
   * The cycle MUST NOT swallow this error.
   */
  close(): Promise<void>;
};

/**
 * A single verifier execution workspace, anchored at the exact repaired SHA.
 *
 * Post-fixer verification must run against the *repaired* candidate, not the
 * host checkout. `runCommand` executes inside the workspace so the verifier
 * cannot accidentally drift back to the host branch and silently pass.
 */
export type VerifierWorkspace = {
  /** Host path to the worktree bound to this verification. */
  readonly worktreePath: string;
  /** Sandcastle branch used for the verification. */
  readonly branch: string;
  /**
   * Execute `cmd` with `args` inside the workspace, returning
   * `{ exitCode, stdout, stderr }`. Non-zero exit is NOT thrown — the cycle
   * treats non-zero as a verification failure.
   */
  runCommand(cmd: string, args: string[]): Promise<{ exitCode: number; stdout: string; stderr: string; error?: string }>;
  /**
   * Tear down the sandbox and worktree. Must throw on uncertain cleanup so
   * the cycle can convert into `FACTORY_ERROR`.
   */
  close(): Promise<void>;
};

export type ReviewCycleDependencies = {
  repoRoot?: string;
  getBaseSha?: () => string;
  resolveCandidateSha?: (branch: string) => string;
  getBranchSha?: (branch: string) => string | null;
  isAncestor?: (ancestorSha: string, branch: string) => boolean;
  getChangedFiles?: (baseSha: string, branch: string) => string[];
  // Single reviewer execution workspace — ADR 0017: Sandcastle owns the
  // resource; the cycle does not inspect a parallel worktree.
  createReviewerWorkspace?: (candidateSha: string) => Promise<ReviewerWorkspace>;
  // Verifier execution workspace anchored at the exact repaired SHA so
  // post-fixer verification actually verifies the repaired candidate.
  createVerifierWorkspace?: (candidateSha: string) => Promise<VerifierWorkspace>;
  // Runners
  runReviewer?: (args: {
    candidateSha: string;
    branch: string;
    issueBody: string;
    priorFindings?: ReviewFinding[];
    isReReview: boolean;
    workspace: ReviewerWorkspace;
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

let workspaceCounter = 0;

/**
 * Default fallback workspace used only when neither `createReviewerWorkspace`
 * nor `createVerifierWorkspace` is supplied. This implementation is for tests
 * and for emergency host-side use; production must always supply real
 * Sandcastle-backed factories so cleanup is reported through
 * `Worktree.close()`'s `preservedWorktreePath` (per ADR 0017).
 *
 * Cleanup here still throws on uncertain state (worktree path persists) — the
 * cycle's FACTORY_ERROR contract holds even in the fallback.
 */
function defaultCreateDisposableWorkspace(repoRoot: string, purpose: "reviewer" | "verifier", candidateSha: string): ReviewerWorkspace & VerifierWorkspace {
  const seq = workspaceCounter++;
  const worktreeBranch = `${purpose}/${candidateSha.slice(0, 7)}-${seq}`;
  const worktreePath = path.join(repoRoot, ".sandcastle", "worktrees", `${purpose}-${candidateSha.slice(0, 7)}-${seq}`);
  // Tear down any pre-existing resource at this path so a previous failed
  // cycle cannot leak a worktree into the next one.
  try { execFileSync("git", ["worktree", "remove", "--force", worktreePath], { stdio: "ignore", cwd: repoRoot }); } catch {}
  try { execFileSync("git", ["branch", "-D", worktreeBranch], { stdio: "ignore", cwd: repoRoot }); } catch {}
  execFileSync("git", ["worktree", "add", "--detach", worktreePath, candidateSha], { stdio: "ignore", cwd: repoRoot });
  const head = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8", cwd: worktreePath }).toString().trim();
  const status = execFileSync("git", ["status", "--porcelain=v1", "--untracked-files=all"], { encoding: "utf8", cwd: worktreePath }).toString().trim();
  return {
    worktreePath,
    branch: worktreeBranch,
    head,
    status,
    snapshot() {
      try {
        return {
          head: execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8", cwd: worktreePath }).toString().trim(),
          status: execFileSync("git", ["status", "--porcelain=v1", "--untracked-files=all"], { encoding: "utf8", cwd: worktreePath }).toString().trim(),
        };
      } catch {
        return { head: "unknown", status: "unknown" };
      }
    },
    async runCommand(cmd: string, args: string[]) {
      const { spawnSync } = await import("node:child_process");
      const res = spawnSync(cmd, args as string[], { cwd: worktreePath, encoding: "utf8", timeout: 300000 });
      return {
        exitCode: res.status ?? -1,
        stdout: res.stdout ?? "",
        stderr: res.stderr ?? "",
        ...(res.error ? { error: String(res.error) } : {}),
      };
    },
    async runAgent(opts: Record<string, unknown>) {
      // Fallback host-side agent invocation: run the muse CLI in the
      // worktree. Production always supplies a Sandcastle-backed workspace
      // factory; this path exists for tests and emergency host use.
      const { spawnSync } = await import("node:child_process");
      const res = spawnSync("muse", ["run", JSON.stringify(opts)], { cwd: worktreePath, encoding: "utf8", timeout: 600000 });
      return {
        stdout: res.stdout ?? "",
        output: undefined,
        ...(res.error ? { error: String(res.error) } : {}),
      };
    },
    async close() {
      try { execFileSync("git", ["worktree", "remove", "--force", worktreePath], { stdio: "ignore", cwd: repoRoot }); } catch (e) {
        throw new Error(`cleanup failed for ${worktreePath}: ${String(e)}`);
      }
      try { execFileSync("git", ["branch", "-D", worktreeBranch], { stdio: "ignore", cwd: repoRoot }); } catch {}
      if (fs.existsSync(worktreePath)) throw new Error(`cleanup left worktree at ${worktreePath}`);
    },
  };
}

export async function runReviewCycle(
  input: ReviewCycleInput,
  deps: ReviewCycleDependencies = {},
): Promise<ReviewCycleResult> {
  const repoRoot = deps.repoRoot ?? process.cwd();
  const targetBranch = input.targetBranch ?? "main";

  const getBaseSha = deps.getBaseSha ?? (() => defaultGetBaseSha(repoRoot));
  const resolveCandidateSha = deps.resolveCandidateSha ?? ((b: string) => defaultResolveCandidateSha(repoRoot, b));
  const getBranchSha = deps.getBranchSha ?? ((b: string) => defaultGetBranchSha(repoRoot, b));
  const isAncestor = deps.isAncestor ?? ((a: string, b: string) => defaultIsAncestor(repoRoot, a, b));
  const getChangedFiles = deps.getChangedFiles ?? ((base: string, br: string) => defaultGetChangedFiles(repoRoot, base, br));

  const createReviewerWorkspace = deps.createReviewerWorkspace ?? (async (candidateSha: string) => defaultCreateDisposableWorkspace(repoRoot, "reviewer", candidateSha));
  const createVerifierWorkspace = deps.createVerifierWorkspace ?? (async (candidateSha: string) => defaultCreateDisposableWorkspace(repoRoot, "verifier", candidateSha));

  const runReviewer = deps.runReviewer;
  const runFixer = deps.runFixer;

  if (!runReviewer) {
    return { kind: "factoryError", reason: "FACTORY_ERROR: runReviewer not provided in review-cycle dependencies" };
  }

  /**
   * One read-only review against an exact-SHA workspace.
   *
   * Lifecycle invariants (ADR 0017 + #198):
   * - the reviewer acts in `workspace`, the ONLY Sandcastle-owned resource
   *   for this review;
   * - mutation is checked by re-snapshotting `workspace`, not a separate path;
   * - `workspace.close()` MUST throw on uncertain cleanup — the cycle
   *   converts that throw into FACTORY_ERROR and does not swallow it.
   */
  async function doReview(candidateSha: string, priorBlockingIds: string[] = [], priorFindings: ReviewFinding[] = [], isReReview = false): Promise<
    | { kind: "verdict"; verdict: ReviewVerdict }
    | { kind: "factoryError"; reason: string }
  > {
    if (!/^[0-9a-f]{40}$/.test(candidateSha)) {
      return { kind: "factoryError", reason: `FACTORY_ERROR: invalid candidate SHA ${candidateSha}` };
    }

    // Invariant: the candidate branch must point at the reviewed SHA before
    // the review starts. A mismatch means the cycle would review one SHA
    // while the branch (what a fixer or submitter would consume) says
    // another — refuse rather than guess.
    const branchShaBefore = getBranchSha(input.branch);
    if (branchShaBefore !== candidateSha) {
      return { kind: "factoryError", reason: `FACTORY_ERROR: branch ${input.branch} at ${branchShaBefore?.slice(0,7) ?? "unknown"} does not point at candidate ${candidateSha.slice(0,7)} before review` };
    }

    let workspace: ReviewerWorkspace | null = null;
    let pending:
      | { kind: "verdict"; verdict: ReviewVerdict }
      | { kind: "factoryError"; reason: string }
      | null = null;
    // Using a labeled block so the `finally` can override the result. JS
    // evaluates the `return` expression before running `finally`, so to let
    // cleanup uncertainty win we must `break` out of the try instead of
    // returning from inside it.
    done: {
      try {
        try {
          workspace = await createReviewerWorkspace!(candidateSha);
        } catch (e) {
          pending = { kind: "factoryError", reason: `FACTORY_ERROR: reviewer workspace create failed: ${String(e).slice(0, 800)}` };
          break done;
        }
        const headBefore = workspace.head;
        const statusBefore = workspace.status;

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
            workspace,
            env,
          });
        } catch (e) {
          pending = { kind: "factoryError", reason: `FACTORY_ERROR: reviewer infra failure: ${String(e).slice(0, 800)}` };
          break done;
        }

        // Verify reviewer did not have write capability
        const reviewerEnv = rawResult.env ?? env;
        if (reviewerEnv.GH_TOKEN || reviewerEnv.GITHUB_TOKEN) {
          pending = { kind: "factoryError", reason: "FACTORY_ERROR: reviewer had GitHub write tokens" };
          break done;
        }

        // Check the SAME workspace for mutations (not a parallel worktree).
        const snap = workspace.snapshot();
        const headAfter = snap.head;
        const statusAfter = snap.status;
        if (headAfter !== headBefore) {
          pending = { kind: "factoryError", reason: `FACTORY_ERROR: reviewer moved HEAD ${headBefore.slice(0,7)} -> ${headAfter.slice(0,7)}` };
          break done;
        }
        if (statusBefore !== statusAfter) {
          pending = { kind: "factoryError", reason: `FACTORY_ERROR: reviewer mutated workspace status (was: '${statusBefore}', now: '${statusAfter}')` };
          break done;
        }

        // Check candidate branch movement during review
        const candidateShaAfter = getBranchSha(input.branch);
        if (candidateShaAfter !== candidateSha) {
          pending = { kind: "factoryError", reason: `FACTORY_ERROR: candidate branch moved during review ${candidateSha.slice(0,7)} -> ${(candidateShaAfter ?? "unknown").slice(0,7)}` };
          break done;
        }

        // Parse verdict — extract from stdout/output
        const rawVerdict = extractRawVerdict(rawResult);
        if (!rawVerdict) {
          pending = { kind: "factoryError", reason: "FACTORY_ERROR: reviewer produced no machine-readable verdict" };
          break done;
        }
        // Must carry exact candidateSha
        if (typeof rawVerdict.candidateSha === "string" && rawVerdict.candidateSha !== candidateSha) {
          pending = { kind: "factoryError", reason: `FACTORY_ERROR: verdict candidateSha ${rawVerdict.candidateSha} != reviewed ${candidateSha}` };
          break done;
        }
        if (!rawVerdict.candidateSha) {
          pending = { kind: "factoryError", reason: "FACTORY_ERROR: verdict missing candidateSha" };
          break done;
        }

        const canon = canonicalizeVerdict(rawVerdict, { priorBlockingIds, isReReview });
        if (!canon.ok) {
          pending = { kind: "factoryError", reason: `FACTORY_ERROR: invalid verdict: ${canon.error}` };
          break done;
        }

        pending = { kind: "verdict", verdict: canon.verdict };
        break done;
      } finally {
        // Per ADR 0017, the workspace owner (Sandcastle) reports uncertain
        // cleanup via close(). The cycle MUST NOT swallow that error; a
        // sandbox we cannot prove is gone leaves state indeterminate.
        if (workspace) {
          try {
            await workspace.close();
          } catch (e) {
            // Cleanup uncertain → override any prior outcome with FACTORY_ERROR.
            // This beats both verdict and any early FACTORY_ERROR, because a
            // workspace that survived its review still terminates indeterminate.
            pending = { kind: "factoryError", reason: `FACTORY_ERROR: reviewer workspace cleanup uncertain: ${String(e).slice(0, 800)}` };
          }
        }
      }
    }
    return pending ?? { kind: "factoryError", reason: "FACTORY_ERROR: reviewer workspace flow ended without verdict" };
  }

  function extractRawVerdict(result: { stdout: string; output?: unknown }): Record<string, unknown> | null {
    if (result.output && typeof result.output === "object") {
      const out = result.output as Record<string, unknown>;
      if (typeof out.candidateSha === "string") return out;
      if (out.verdict && typeof out.verdict === "object") return out.verdict as Record<string, unknown>;
      try { if (typeof out.approved === "boolean") return out; } catch {}
    }
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

  const verdict1 = initial.verdict;
  const blocking1 = (verdict1.canonicalFindings ?? []).filter((f) => f.severity === "blocking");
  const hostApproved1 = hostGatingApproved(verdict1);

  if (hostApproved1 && blocking1.length === 0) {
    return { kind: "approved", candidateSha, verdict: verdict1 };
  }

  if (blocking1.length === 0) {
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
  if (repairedSha !== candidateSha && !isAncestor(candidateSha, input.branch)) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: repaired SHA ${repairedSha.slice(0,7)} not descendant of reviewed ${candidateSha.slice(0,7)}`, candidateSha: repairedSha };
  }
  const branchShaAfterFix = getBranchSha(input.branch);
  if (branchShaAfterFix !== repairedSha) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: branch SHA ${branchShaAfterFix?.slice(0,7)} != repaired ${repairedSha.slice(0,7)} after fixer`, candidateSha: repairedSha };
  }

  // Deterministic verification against the exact repaired candidate. The
  // verifier workspace is anchored at `repairedSha` so verification
  // provably runs against the post-fixer history, not the host checkout.
  let verifyWorkspace: VerifierWorkspace | null = null;
  let verifyOk = false;
  let verifyReason: string | null = null;
  let verifyInfra = false;
  let verifyError:
    | { kind: "factoryError"; reason: string }
    | null = null;
  verify: {
    try {
      try {
        verifyWorkspace = await createVerifierWorkspace!(repairedSha);
      } catch (e) {
        verifyError = { kind: "factoryError", reason: `FACTORY_ERROR: verifier workspace create failed: ${String(e).slice(0, 800)}` };
        break verify;
      }
      let verifyResult: { ok: boolean; infraError?: boolean; reason?: string };
      try {
        verifyResult = await runDeterministicVerification(verifyWorkspace, repairedSha, getChangedFiles(getBaseSha(), input.branch));
      } catch (e) {
        verifyResult = { ok: false, infraError: true, reason: `verifier threw: ${String(e).slice(0, 800)}` };
      }
      if (verifyResult.infraError) {
        verifyInfra = true;
        verifyReason = verifyResult.reason ?? "unknown verifier infrastructure failure";
      } else if (!verifyResult.ok) {
        verifyReason = verifyResult.reason ?? "deterministic verification failed";
      } else {
        verifyOk = true;
      }
    } finally {
      if (verifyWorkspace) {
        // Cleanup uncertain → FACTORY_ERROR. Never swallow.
        try {
          await verifyWorkspace.close();
        } catch (e) {
          verifyError = { kind: "factoryError", reason: `FACTORY_ERROR: verifier workspace cleanup uncertain: ${String(e).slice(0, 800)}` };
        }
      }
    }
  }
  if (verifyError) {
    return { kind: "factoryError", reason: verifyError.reason, candidateSha: repairedSha };
  }
  if (verifyInfra) {
    return { kind: "factoryError", reason: `FACTORY_ERROR: verification infrastructure failure: ${verifyReason ?? "unknown"}`, candidateSha: repairedSha };
  }
  if (!verifyOk) {
    const verificationFinding: ReviewFinding = {
      axis: "verification",
      severity: "blocking",
      invariant: "deterministic verification must pass",
      failureMode: verifyReason ?? "verification failed",
      evidence: [verifyReason ?? "verification failed"],
      requiredProof: "fix verification failure and ensure typecheck/npm test (and Java lanes when mod/ is touched) pass against the repaired candidate",
      id: computeFindingId({
        axis: "verification",
        severity: "blocking",
        invariant: "deterministic verification must pass",
        failureMode: verifyReason ?? "verification failed",
        evidence: [],
        requiredProof: "fix verification failure and ensure typecheck/npm test (and Java lanes when mod/ is touched) pass against the repaired candidate",
      }),
    };
    const verificationVerdict: ReviewVerdict = {
      approved: false,
      findings: [{ message: verifyReason ?? "verification failed", severity: "blocking", axis: "verification", invariant: "deterministic verification must pass", failureMode: verifyReason ?? "verification failed", evidence: [verifyReason ?? "verification failed"], requiredProof: "fix verification failure and ensure typecheck/npm test (and Java lanes when mod/ is touched) pass against the repaired candidate", id: verificationFinding.id }],
      acceptanceCriteriaMet: verdict1.acceptanceCriteriaMet,
      summary: `deterministic verification failed: ${verifyReason ?? "unknown"}`,
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

/**
 * Run the canonical deterministic verification suite inside the verifier
 * workspace. The workspace is bound to the repaired candidate SHA, so
 * `runCommand` cannot drift back to the host checkout.
 *
 * The baseline is always typecheck + npm test (the Node lanes). When any
 * changed file begins with `mod/`, the Java/Voxy lanes are ADDED — never
 * in place of the Node baseline. This matches #198's contract that the
 * verification "always includes npm test, with Java checks added when
 * mod/ is touched".
 *
 * Returns `{ ok, infraError?, reason? }`. The cycle converts `infraError`
 * into `FACTORY_ERROR`; non-`ok` becomes a blocking verification finding.
 */
export async function runDeterministicVerification(
  workspace: VerifierWorkspace,
  _repairedSha: string,
  changedFiles: string[],
): Promise<{ ok: boolean; infraError?: boolean; reason?: string }> {
  const cmds: Array<[string, string[]]> = [
    ["npm", ["run", "typecheck"]],
    ["npm", ["test"]],
  ];
  if (changedFiles.some((f) => f.startsWith("mod/"))) {
    // Java/Voxy lanes ADDED, not replacing Node baseline.
    cmds.push(["bash", [".ci/install-voxy.sh", "install"]]);
    cmds.push(["./mod/gradlew", ["-p", "mod", "lint", "compileJava", "compileClientJava"]]);
    cmds.push(["./mod/gradlew", ["-p", "mod", "test", "-PexcludeVoxyTestRuntime"]]);
  }
  for (const [cmd, args] of cmds) {
    let res: { exitCode: number; stdout: string; stderr: string; error?: string };
    try {
      res = await workspace.runCommand(cmd, args);
    } catch (e) {
      return { ok: false, infraError: true, reason: `spawn ${cmd} failed: ${String(e).slice(0, 500)}` };
    }
    if (res.error) return { ok: false, infraError: true, reason: `spawn ${cmd} failed: ${res.error.slice(0, 500)}` };
    if (res.exitCode !== 0) {
      return { ok: false, reason: `${cmd} ${args.join(" ")} failed: ${(res.stdout + res.stderr).slice(0, 800)}` };
    }
  }
  return { ok: true };
}