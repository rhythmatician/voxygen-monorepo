import * as fs from "node:fs";
import * as path from "node:path";
import { inspectProvenance, inspectCommitsAhead, type GitResult, type GitRunner } from "./branch-helpers.mts";
import type { IssueInput } from "./tracker-policy.mts";
import type { ReconcileInspectionOps, BranchCleanupResult } from "./tracker-operations.mts";
import { isHttp404 } from "./gh-errors.mts";

/**
 * Strict RFC3339 timestamp validation (e.g. `2024-01-15T10:30:00Z` or
 * `2024-01-15T10:30:00+00:00`). Used to validate a PR's `merged_at` before it
 * is trusted for a merged-PR decision. A malformed timestamp is UNKNOWN, never
 * treated as a merge.
 */
function isValidRfc3339(s: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/.test(s)) return false;
  return !isNaN(Date.parse(s));
}

/**
 * Production reconciliation INSPECTION adapter — internal to TrackerAdapter.
 *
 * Owns ONLY local Git/worktree/provenance inspection and cleanup:
 * branch presence, provenance validity, commits-ahead, and proven
 * worktree/local/remote branch deletion. It performs NO GitHub state
 * transitions — every issue/label/assignee mutation flows through the
 * TrackerAdapter's verified saga, which is the single GitHub state-transition
 * authority (one-authority rule).
 */

export interface ReconcileAdapterDeps {
  runGh: (args: string[]) => Promise<string>;
  runGit: GitRunner;
  repoRoot: string;
  claimantLogin: string;
  /**
   * Repository identity, owned by the single GhTransport. Required — this
   * adapter never parses git remotes locally (one-authority rule).
   */
  ownerRepo: { owner: string; repo: string } | null;
}

export function createProductionReconcileOps(deps: ReconcileAdapterDeps): ReconcileInspectionOps {
  const { runGh, repoRoot, claimantLogin, runGit } = deps;
  if (!runGit) throw new Error("runGit is required for production adapter");
  // Transport-owned identity only — no local remote parsing.
  const ownerRepo = deps.ownerRepo;

  /**
   * Classify a successful REST git-ref response. A ref is PRESENT only when it
   * EXACTLY equals the expected ref (`refs/heads/<branch>`). An empty,
   * mismatched, or malformed successful response is UNKNOWN — it does not
   * prove the branch is absent (the response could be a different ref, a
   * truncated payload, or a shape we did not expect). Only an authoritative
   * HTTP 404 (handled by the caller) proves absence.
   */
  function classifyRefResponse(ref: unknown, branchName: string): "present" | "unknown" {
    if (typeof ref === "string" && ref === `refs/heads/${branchName}`) return "present";
    return "unknown";
  }

  async function fetchIssueFresh(issueId: string): Promise<IssueInput> {
    const rawJson = await runGh(["issue","view",issueId,"--json","number,title,body,labels,assignees,state"]);
    const raw = JSON.parse(rawJson);
    let blockedByCount: number | undefined = undefined;
    if (ownerRepo) {
      try {
        const summary = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${issueId}`, "--jq", ".issue_dependencies_summary.blocked_by"]);
        const n = parseInt(summary.trim(), 10);
        if (!isNaN(n)) blockedByCount = n;
      } catch {
        blockedByCount = undefined;
      }
    } else {
      blockedByCount = undefined;
    }
    return {
      number: raw.number,
      title: raw.title,
      state: (raw.state?.toLowerCase() ?? "open") as "open" | "closed",
      labels: (raw.labels ?? []).map((l:any)=>l.name),
      assignees: (raw.assignees ?? []).map((a:any)=>a.login),
      blockedByCount,
      body: raw.body,
    };
  }

  return {
    claimantLogin,
    fetchIssue: async (id:string) => fetchIssueFresh(id),

    getBatchPrNumber: async (issueNumber:string) => {
      let batchPrNumber: string | null = null;
      let commentsUnknown=false;
      try {
        const commentsJson = await runGh(["issue","view",issueNumber,"--json","comments","--jq",".comments[].body"]);
        const match = commentsJson.match(/Batch PR #(\d+)/);
        if (match) batchPrNumber=match[1];
      } catch (e) {
        // The issue is KNOWN to exist (we are reconciling it). A comments
        // fetch failure for a known-existing issue is NEVER an authoritative
        // absence — it is unknown. Only an actual HTTP 404 on the issue itself
        // would prove absence, but `gh issue view` on a known issue cannot
        // legitimately 404. Auth/network/timeout/403/429/5xx => unknown.
        commentsUnknown=true;
      }
      if (commentsUnknown) return { prNumber:null, state:"unknown" as const };
      if (batchPrNumber) return { prNumber:batchPrNumber, state:"found" as const };
      let prListUnknown=false;
      try {
        const prListJson=await runGh(["pr","list","--state","open","--limit","100","--json","number,body"]);
        const prs:any[]=JSON.parse(prListJson);
        for (const pr of prs) {
          if (pr.body && pr.body.includes(`Closes #${issueNumber}`)) return { prNumber:String(pr.number), state:"found" as const };
        }
      } catch { prListUnknown=true; }
      if (prListUnknown) return { prNumber:null, state:"unknown" as const };
      return { prNumber:null, state:"absent" as const };
    },

    getPrState: async (prNumber:string) => {
      // Use the REST pull-request endpoint — it exposes an AUTHORITATIVE HTTP
      // 404 when the PR does not exist. `gh pr view` does not reliably expose
      // the HTTP status, so absence is proven only by the REST endpoint's
      // structured 404. Auth/network/timeout/403/429/5xx => unknown.
      if (!ownerRepo) return { state:"UNKNOWN", mergedAt:null, found:false, unknown:true };
      try {
        const prJson=await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/pulls/${prNumber}`, "--jq", "{state,merged_at,number}"]);
        let pr: unknown;
        try {
          pr = JSON.parse(prJson);
        } catch {
          // Malformed successful response — the PR state cannot be trusted.
          return { state:"UNKNOWN", mergedAt:null, found:false, unknown:true };
        }
        // Validate the response before returning found: the PR number must
        // match the requested number, and the state must be a known PR state.
        // A malformed or mismatched successful response is UNKNOWN, never
        // found.
        const obj = pr as { number?: unknown; state?: unknown; merged_at?: unknown };
        if (Number(obj.number) !== Number(prNumber)) {
          return { state:"UNKNOWN", mergedAt:null, found:false, unknown:true };
        }
        const rawState = String(obj.state ?? "").toLowerCase();
        if (rawState !== "open" && rawState !== "closed") {
          return { state:"UNKNOWN", mergedAt:null, found:false, unknown:true };
        }
        // Normalize to the uppercase state contract the decision logic expects
        // ("OPEN"/"CLOSED"), matching the prior `gh pr view` output.
        const state = rawState.toUpperCase();
        // Validate merged_at SEMANTICALLY before trusting it for a merged-PR
        // decision. OPEN + merged_at null is valid; CLOSED + merged_at null is
        // valid; CLOSED + a valid RFC3339 timestamp is valid. A non-string,
        // non-null merged_at, a malformed timestamp, or an OPEN PR carrying a
        // merged_at is INCONSISTENT => UNKNOWN and zero tracker mutation.
        const rawMergedAt = obj.merged_at;
        if (rawMergedAt === null || rawMergedAt === undefined) {
          // merged_at null/absent — valid for both OPEN and CLOSED.
          return { state, mergedAt: null, found: true };
        }
        if (typeof rawMergedAt !== "string") {
          // Non-string, non-null merged_at — malformed.
          return { state: "UNKNOWN", mergedAt: null, found: false, unknown: true };
        }
        if (!isValidRfc3339(rawMergedAt)) {
          // String but not a valid RFC3339 timestamp — malformed.
          return { state: "UNKNOWN", mergedAt: null, found: false, unknown: true };
        }
        if (state === "OPEN") {
          // OPEN PR carrying a merged_at — inconsistent.
          return { state: "UNKNOWN", mergedAt: null, found: false, unknown: true };
        }
        // CLOSED + valid RFC3339 merged_at.
        return { state, mergedAt: rawMergedAt, found: true };
      } catch (e) {
        if (isHttp404(e)) return { state:"CLOSED", mergedAt:null, found:false };
        return { state:"UNKNOWN", mergedAt:null, found:false, unknown:true };
      }
    },

    checkBranchExists: async (branchName:string): Promise<"present"|"absent"|"unknown"> => {
      // Local presence via GitRunner
      const localRes = runGit(["branch", "--list", branchName]);
      if (localRes.exitCode !== 0) return "unknown";
      if (localRes.stdout.trim()) return "present";
      // Local absent — check remote via gh api if ownerRepo known
      if (!ownerRepo) return "unknown";
      try {
        const ref = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq", ".ref"]);
        // Present ONLY when the response exactly equals the expected ref.
        // Empty/mismatched/malformed success is UNKNOWN, never absent.
        return classifyRefResponse(ref, branchName);
      } catch (e) {
        // ONLY an authoritative HTTP 404 proves remote absence. Auth/network/
        // timeout/403/429/5xx => unknown — never inferred from a message
        // substring like "not found".
        if (isHttp404(e)) return "absent";
        return "unknown";
      }
    },

    checkProvenanceValid: async (branchName:string) => {
      // Delegates to single canonical implementation in branch-helpers
      return inspectProvenance(repoRoot, branchName, runGit);
    },

    hasCommitsAhead: async (branchName:string): Promise<"has-work"|"empty"|"unknown"> => {
      // Resolve base via GitRunner, then delegate to canonical helper
      let base: string | null = null;
      for (const candidate of ["origin/main", "main", "master"]) {
        const res = runGit(["rev-parse", "--verify", candidate]);
        if (res.exitCode === 0) { base = candidate; break; }
        // Also try rev-parse without --verify? but we use same
      }
      if (!base) {
        // Try to get origin/main via rev-parse origin/main direct
        const res = runGit(["rev-parse", "origin/main"]);
        if (res.exitCode === 0) base = "origin/main";
        else return "unknown";
      }
      // Use canonical helper for commits-ahead
      return inspectCommitsAhead(repoRoot, base, branchName, runGit);
    },

    deleteBranch: async (branchName:string): Promise<BranchCleanupResult> => {
      // Effect evidence accumulator — every mutation is recorded so the caller
      // can distinguish a full cleanup from a partial/indeterminate one.
      const effects = { worktreeRemoved: false, localBranchRemoved: false, remoteBranchRemoved: false, provenanceRemoved: false };
      const untouched = (reason: string): BranchCleanupResult => ({ cleaned: false, untouched: true, effects, reason });
      const partial = (reason: string): BranchCleanupResult => ({ cleaned: false, untouched: false, effects, reason });

      // If owner/repo cannot be resolved, remote state is unknown — do not
      // delete anything (zero mutation).
      if (!ownerRepo) return untouched("repository identity unavailable — remote state unknown, zero mutation");

      // PREFLIGHT: authoritatively inspect REMOTE state BEFORE any destructive
      // local cleanup (worktree/local/provenance). Initial unknown remote state
      // => zero worktree/local/provenance/tracker mutation. Only an
      // authoritative HTTP 404 proves remote absence; auth/network/timeout/5xx
      // (including GhTokenMissingError) => unknown => untouched.
      let remotePresent: "present"|"absent"|"unknown" = "unknown";
      try {
        const ref = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq", ".ref"]);
        // Present ONLY when the response exactly equals the expected ref.
        // Empty/mismatched/malformed success is UNKNOWN, never absent.
        remotePresent = classifyRefResponse(ref, branchName);
      } catch (e) {
        // ONLY an authoritative HTTP 404 proves remote absence.
        if (isHttp404(e)) remotePresent = "absent";
        else remotePresent = "unknown";
      }
      if (remotePresent === "unknown") {
        // Initial remote state unknown — zero worktree/local/provenance/tracker
        // mutation. Preserve provenance and the claim.
        return untouched(`remote state unknown for ${branchName} — zero mutation`);
      }

      // Worktree cleanup fail closed: nonzero worktree inventory before any mutation
      const wtListRes = runGit(["worktree", "list", "--porcelain"]);
      if (wtListRes.exitCode !== 0) return partial(`worktree inventory failed for ${branchName}`);
      // Parse porcelain blocks to locate exact worktree path for this branch
      const wtBlocks = wtListRes.stdout.split("\n\n");
      let exactWtPath: string | null = null;
      for (const block of wtBlocks) {
        const lines = block.split("\n");
        let branchLine: string | null = null;
        let worktreeLine: string | null = null;
        for (const line of lines) {
          if (line.startsWith("branch ")) branchLine = line.slice("branch ".length).trim();
          if (line.startsWith("worktree ")) worktreeLine = line.slice("worktree ".length).trim();
        }
        if (branchLine === `refs/heads/${branchName}` && worktreeLine) {
          exactWtPath = worktreeLine;
          break;
        }
      }
      if (exactWtPath) {
        // Defensive recheck: dirty worktree must never be force-removed (close inspection/removal race)
        const dirtyRes = runGit(["-C", exactWtPath, "status", "--porcelain=v1", "--untracked-files=all"]);
        if (dirtyRes.exitCode !== 0) return partial(`worktree status failed for ${branchName}`);
        if (dirtyRes.stdout.trim()) return partial(`worktree ${exactWtPath} is dirty — refusing removal`);
        const rmRes = runGit(["worktree", "remove", "--force", exactWtPath]);
        if (rmRes.exitCode !== 0) return partial(`worktree removal failed for ${branchName}`);
        effects.worktreeRemoved = true;
        // Re-run inventory and prove no worktree still references the branch
        const wtVerify = runGit(["worktree", "list", "--porcelain"]);
        if (wtVerify.exitCode !== 0) return partial(`worktree verification failed for ${branchName}`);
        const verifyBlocks = wtVerify.stdout.split("\n\n");
        for (const block of verifyBlocks) {
          for (const line of block.split("\n")) {
            if (line.trim() === `branch refs/heads/${branchName}`) return partial(`worktree still references ${branchName} after removal`);
          }
        }
      }
      // local
      const localList = runGit(["branch", "--list", branchName]);
      if (localList.exitCode !== 0) return partial(`local branch inventory failed for ${branchName}`);
      const localPresent = !!localList.stdout.trim();
      if (localPresent) {
        const del = runGit(["branch", "-D", branchName]);
        if (del.exitCode !== 0) return partial(`local branch deletion failed for ${branchName}`);
        effects.localBranchRemoved = true;
      }
      // remote — preflighted above; now delete if present
      if (remotePresent === "present") {
        try { await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--method","DELETE"]); } catch (e) {
          // A non-404 error after DELETE is NOT an authoritative absence — the
          // remote may still exist. Fail closed (no provenance deletion).
          if (!isHttp404(e)) return partial(`remote branch DELETE failed for ${branchName}`);
        }
        effects.remoteBranchRemoved = true;
        try {
          await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq",".ref"]);
          return partial(`remote branch ${branchName} still exists after DELETE`);
        } catch (e) {
          // ONLY an authoritative HTTP 404 proves the remote ref is gone.
          if (!isHttp404(e)) return partial(`remote branch ${branchName} deletion read-back failed`);
        }
      }
      // Verify local and remote both absent before deleting provenance
      const verifyLocal = runGit(["branch", "--list", branchName]);
      if (verifyLocal.exitCode !== 0) return partial(`local branch verification failed for ${branchName}`);
      if (verifyLocal.stdout.trim()) return partial(`local branch ${branchName} still present after deletion`);
      try {
        const ref = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq",".ref"]);
        // Present (exact ref match) => not absent, fail closed. An empty,
        // mismatched, or malformed successful response is UNKNOWN — fail
        // closed (do not delete provenance on ambiguous remote state).
        if (classifyRefResponse(ref, branchName) === "present") return partial(`remote branch ${branchName} still present after deletion`);
        if (classifyRefResponse(ref, branchName) === "unknown") return partial(`remote branch ${branchName} state unknown after deletion`);
      } catch (e) {
        // ONLY an authoritative HTTP 404 proves remote absence.
        if (!isHttp404(e)) return partial(`remote branch ${branchName} verification failed after deletion`);
      }
      // provenance — only after branch absence proved
      try {
        const provPath = path.join(repoRoot, ".sandcastle","provenance", `${branchName.replace(/[^a-zA-Z0-9-]/g,"-")}.json`);
        if (fs.existsSync(provPath)) { fs.unlinkSync(provPath); effects.provenanceRemoved = true; }
      } catch { return partial(`provenance deletion failed for ${branchName}`); }
      return { cleaned: true, untouched: false, effects };
    },
  };
}
