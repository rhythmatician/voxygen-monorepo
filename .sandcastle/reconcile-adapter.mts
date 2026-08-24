import * as fs from "node:fs";
import * as path from "node:path";
import { getErrorMessage } from "./gh-errors.mts";
import * as branchHelpers from "./branch-helpers.mts";
import type { GitResult, GitRunner } from "./branch-helpers.mts";
import type { IssueInput } from "./tracker-policy.mts";
import type { FullReconcileOps } from "./tracker-operations.mts";

/**
 * Production reconciliation adapter — single callable owned by production.
 * Used by main.mts and by behavioral tests (same adapter, not duplicate).
 * Provides fresh GitHub reads and genuinely tri-state inspections.
 * All local Git operations use the injected GitRunner with argument arrays.
 */

export interface ReconcileAdapterDeps {
  runGh: (args: string[]) => Promise<string>;
  runGit: GitRunner;
  repoRoot: string;
  claimantLogin: string;
}

function parseOwnerRepo(runGit: GitRunner): { owner:string, repo:string } | null {
  const res = runGit(["remote", "get-url", "origin"]);
  if (res.exitCode !== 0) return null;
  const out = res.stdout.trim();
  const m = out.match(/github\.com[:\/]([^\/]+)\/([^\/\.]+)/);
  if (m) return { owner: m[1], repo: m[2].replace(/\.git$/, "") };
  return null;
}

export function createProductionReconcileOps(deps: ReconcileAdapterDeps): FullReconcileOps {
  const { runGh, repoRoot, claimantLogin, runGit } = deps;
  if (!runGit) throw new Error("runGit is required for production adapter");
  const ownerRepo = parseOwnerRepo(runGit);

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

  async function safeGh(args:string[], context?: string): Promise<boolean> {
    try {
      await runGh(args);
      return true;
    } catch (e) {
      if (context) console.warn(`${context}: ${getErrorMessage(e)}`);
      return false;
    }
  }

  return {
    fetchIssue: async (id:string) => fetchIssueFresh(id),

    releaseClaim: async (issueId:string) => {
      return safeGh(["issue","edit",issueId,"--remove-label","agent:in-progress","--remove-assignee",claimantLogin], `Failed to release claim for #${issueId}`);
    },

    comment: async (issueId:string, body:string) => {
      return safeGh(["issue","comment",issueId,"--body",body]);
    },

    getBatchPrNumber: async (issueNumber:string) => {
      let batchPrNumber: string | null = null;
      let commentsUnknown=false;
      try {
        const commentsJson = await runGh(["issue","view",issueNumber,"--json","comments","--jq",".comments[].body"]);
        const match = commentsJson.match(/Batch PR #(\d+)/);
        if (match) batchPrNumber=match[1];
      } catch (e) {
        const msg=getErrorMessage(e).toLowerCase();
        const isNotFound=msg.includes("not found")||msg.includes("no pull")||msg.includes("404");
        if (!isNotFound) commentsUnknown=true;
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
      try {
        const prJson=await runGh(["pr","view",prNumber,"--json","state,mergedAt,number"]);
        const pr=JSON.parse(prJson);
        return { state: pr.state, mergedAt: pr.mergedAt ?? null, found:true };
      } catch (e) {
        const msg=getErrorMessage(e).toLowerCase();
        const isNotFound=msg.includes("not found")||msg.includes("could not find")||msg.includes("404");
        if (isNotFound) return { state:"CLOSED", mergedAt:null, found:false };
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
        if (ref && ref.includes(branchName)) return "present";
        return "absent";
      } catch (e) {
        const msg=String(e).toLowerCase();
        if (msg.includes("404")||msg.includes("not found")) return "absent";
        return "unknown";
      }
    },

    checkProvenanceValid: async (branchName:string) => {
      // Delegates to single canonical implementation in branch-helpers
      return branchHelpers.inspectProvenance(repoRoot, branchName, runGit);
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
      return branchHelpers.inspectCommitsAhead(repoRoot, base, branchName, runGit);
    },

    deleteBranch: async (branchName:string): Promise<boolean> => {
      // If owner/repo cannot be resolved, remote state is unknown — do not delete anything
      if (!ownerRepo) return false;
      // Ordered: worktree -> local -> remote -> verify -> provenance
      // worktree inspection/removal via GitRunner
      const wtRes = runGit(["worktree", "list", "--porcelain"]);
      if (wtRes.exitCode === 0 && wtRes.stdout.includes(branchName)) {
        const wtPath = path.join(repoRoot, ".sandcastle","worktrees",branchName.replace(/\//g,"-"));
        // Try removal by path
        let r = runGit(["worktree", "remove", "--force", wtPath]);
        // Fallback removal by branch name if path removal failed
        if (r.exitCode !== 0) {
          const r2 = runGit(["worktree", "remove", "--force", branchName]);
          // ignore failure, best effort
          void r2;
        }
      }
      // local
      const localList = runGit(["branch", "--list", branchName]);
      if (localList.exitCode !== 0) return false;
      const localPresent = !!localList.stdout.trim();
      if (localPresent) {
        const del = runGit(["branch", "-D", branchName]);
        if (del.exitCode !== 0) return false;
      }
      // remote — ownerRepo already verified, now check remote presence
      let remotePresent: "present"|"absent"|"unknown" = "unknown";
      try {
        const ref = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq", ".ref"]);
        remotePresent = (ref && ref.includes(branchName)) ? "present" : "absent";
      } catch (e) {
        const msg=String(e).toLowerCase();
        if (msg.includes("404")||msg.includes("not found")) remotePresent="absent";
        else remotePresent="unknown";
      }
      if (remotePresent==="unknown") return false;
      if (remotePresent==="present") {
        try { await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--method","DELETE"]); } catch (e) {
          const msg=String(e).toLowerCase();
          if (!(msg.includes("404")||msg.includes("not found"))) return false;
        }
        try {
          await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq",".ref"]);
          return false;
        } catch (e) {
          const msg=String(e).toLowerCase();
          if (!(msg.includes("404")||msg.includes("not found"))) return false;
        }
      }
      // Verify local and remote both absent before deleting provenance
      const verifyLocal = runGit(["branch", "--list", branchName]);
      if (verifyLocal.exitCode !== 0) return false;
      if (verifyLocal.stdout.trim()) return false;
      try {
        const ref = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq",".ref"]);
        if (ref && ref.includes(branchName)) return false;
      } catch (e) {
        const msg=String(e).toLowerCase();
        if (!(msg.includes("404")||msg.includes("not found"))) return false;
      }
      // provenance — only after branch absence proved
      try {
        const provPath = path.join(repoRoot, ".sandcastle","provenance", `${branchName.replace(/[^a-zA-Z0-9-]/g,"-")}.json`);
        if (fs.existsSync(provPath)) fs.unlinkSync(provPath);
      } catch { return false; }
      return true;
    },

    addBlocked: async (issueId:string) => {
      return safeGh(["issue","edit",issueId,"--add-label","agent:blocked"], `Failed to add agent:blocked to #${issueId}`);
    },

    markIntegrated: async (issueId:string, branchName:string): Promise<boolean> => {
      const steps: Array<{args:string[], context:string}> = [
        { args:["issue","edit",issueId,"--remove-label","agent:in-progress"], context:`Failed to remove agent:in-progress from #${issueId}` },
        { args:["issue","edit",issueId,"--remove-label","agent:implement"], context:`Failed to remove agent:implement from #${issueId}` },
        { args:["issue","edit",issueId,"--remove-label","agent:blocked"], context:`Failed to remove agent:blocked from #${issueId}` },
        { args:["issue","edit",issueId,"--remove-assignee",claimantLogin], context:`Failed to remove assignee from #${issueId}` },
      ];
      for (const s of steps) {
        const ok = await safeGh(s.args, s.context);
        if (!ok) return false;
      }
      let closed=false;
      try {
        await runGh(["issue","close",issueId,"--comment",`Completed by Sandcastle -- branch \`${branchName}\` merged and integrated. Auto-merged to main after verification.`]);
        closed=true;
      } catch {
        try {
          await runGh(["issue","comment",issueId,"--body",`Completed by Sandcastle -- branch \`${branchName}\` integrated.`]);
          await runGh(["issue","close",issueId]);
          closed=true;
        } catch { return false; }
      }
      if (!closed) return false;
      let after: IssueInput;
      try { after = await fetchIssueFresh(issueId); } catch { return false; }
      if (after.state!=="closed") return false;
      if (after.assignees.includes(claimantLogin)) return false;
      if (after.labels.includes("agent:in-progress")) return false;
      if (after.labels.includes("agent:implement")) return false;
      if (after.labels.includes("agent:blocked")) return false;
      return true;
    },
  };
}
