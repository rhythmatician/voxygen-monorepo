import * as fs from "node:fs";
import * as path from "node:path";
import { execSync, execFileSync } from "node:child_process";
import { getErrorMessage } from "./gh-errors.mts";
import * as branchHelpers from "./branch-helpers.mts";
import type { IssueInput } from "./tracker-policy.mts";
import type { FullReconcileOps } from "./tracker-operations.mts";

/**
 * Production reconciliation adapter — single callable owned by production.
 * Used by main.mts and by behavioral tests (same adapter, not duplicate).
 * Provides fresh GitHub reads and genuinely tri-state inspections.
 */

export interface ReconcileAdapterDeps {
  runGh: (args: string[]) => Promise<string>;
  runGit?: (args: string[]) => string; // for local git, defaults to execSync
  repoRoot: string;
  claimantLogin: string;
}

function parseOwnerRepo(repoRoot: string, runGit: (args:string[])=>string): { owner:string, repo:string } | null {
  try {
    const out = runGit(["remote","get-url","origin"]);
    const m = out.match(/github\.com[:\/]([^\/]+)\/([^\/\.]+)/);
    if (m) return { owner: m[1], repo: m[2] };
  } catch {}
  try {
    const out = execSync("git remote get-url origin", { encoding:"utf8", cwd: repoRoot }).trim();
    const m = out.match(/github\.com[:\/]([^\/]+)\/([^\/\.]+)/);
    if (m) return { owner: m[1], repo: m[2] };
  } catch {}
  return null;
}

export function createProductionReconcileOps(deps: ReconcileAdapterDeps): FullReconcileOps {
  const { runGh, repoRoot, claimantLogin } = deps;
  const runGit = deps.runGit ?? ((args:string[]) => execSync(args.join(" "), { encoding:"utf8", cwd: repoRoot }).trim());

  const ownerRepo = parseOwnerRepo(repoRoot, (args:string[]) => {
    try { return execSync(args.join(" "), { encoding:"utf8", cwd: repoRoot }).trim(); } catch { return ""; }
  });

  async function fetchIssueFresh(issueId: string): Promise<IssueInput> {
    // Fresh GitHub read — never return captured inventory object
    const rawJson = await runGh(["issue","view",issueId,"--json","number,title,body,labels,assignees,state"]);
    const raw = JSON.parse(rawJson);
    let blockedByCount: number | undefined = undefined;
    if (ownerRepo) {
      try {
        const summary = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${issueId}`, "--jq", ".issue_dependencies_summary.blocked_by"]);
        const n = parseInt(summary.trim(), 10);
        if (!isNaN(n)) blockedByCount = n;
      } catch {
        // leave undefined — caller will treat as unknown (fail-closed)
        blockedByCount = undefined;
      }
    } else {
      // If we cannot resolve owner/repo, treat blocked_by as unknown to fail closed
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
      // Use genuinely tri-state inspections, preserve unknown on Git/API errors, do not wrap hasCommitsAhead
      // Local presence
      try {
        const out = execSync(`git branch --list "${branchName}"`, { encoding:"utf8", cwd: repoRoot }).trim();
        if (out) return "present";
      } catch { return "unknown"; }
      // Remote presence via gh api (authoritative 404 vs unknown)
      if (ownerRepo) {
        try {
          const ref = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq", ".ref"]);
          if (ref && ref.includes(branchName)) return "present";
          return "absent";
        } catch (e) {
          const msg=String(e).toLowerCase();
          if (msg.includes("404")||msg.includes("not found")) return "absent";
          // Check if origin exists — if so, unknown, else absent
          let originExists=false;
          try { execSync("git remote get-url origin", { stdio:"ignore", cwd: repoRoot }); originExists=true; } catch {}
          if (originExists) return "unknown";
          return "absent";
        }
      }
      return "absent";
    },

    checkProvenanceValid: async (branchName:string) => {
      // Read-only inspectProvenance — never call prepareIssueBranch
      try {
        const prov = branchHelpers.verifyProvenance(repoRoot, branchName);
        if (prov.ok) return { valid:true, reason: prov.reason };
        const isContaminated = prov.reason?.toLowerCase().includes("legacy") || prov.reason?.toLowerCase().includes("contaminated") || prov.reason?.toLowerCase().includes("fail closed");
        return { valid:false, reason: prov.reason, contaminated: !!isContaminated };
      } catch {
        return { valid:false, reason:"provenance inspection failed — unknown", contaminated:false };
      }
    },

    hasCommitsAhead: async (branchName:string): Promise<"has-work"|"empty"|"unknown"> => {
      // Use read-only inspectCommitsAhead that preserves unknown, do not wrap hasCommitsAhead
      try {
        const base = execSync("git rev-parse origin/main", { encoding:"utf8", cwd: repoRoot }).trim();
        const status = branchHelpers.inspectCommitsAhead(repoRoot, base, branchName);
        return status;
      } catch {
        return "unknown";
      }
    },

    deleteBranch: async (branchName:string): Promise<boolean> => {
      // Ordered and authoritative: worktree -> local -> remote -> provenance
      // worktree
      try {
        const wtList = execSync("git worktree list --porcelain", { encoding:"utf8", cwd: repoRoot });
        if (wtList.includes(branchName)) {
          try { execSync(`git worktree remove --force ${path.join(repoRoot, ".sandcastle","worktrees",branchName.replace(/\//g,"-"))}`, { encoding:"utf8", cwd: repoRoot }); } catch {}
          // Also try generic worktree remove by branch
          try { execSync(`git worktree remove --force "${branchName}"`, { encoding:"utf8", cwd: repoRoot }); } catch {}
        }
      } catch {}
      // local
      let localPresent=false;
      try {
        const out = execSync(`git branch --list "${branchName}"`, { encoding:"utf8", cwd: repoRoot }).trim();
        localPresent=!!out;
      } catch {}
      if (localPresent) {
        try { execSync(`git branch -D ${branchName}`, { encoding:"utf8", cwd: repoRoot }); } catch { return false; }
      }
      // remote
      if (ownerRepo) {
        let remotePresent: "present"|"absent"|"unknown"="unknown";
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
            if (msg.includes("404")||msg.includes("not found")) { /* already absent */ } else return false;
          }
          // Verify remote now absent
          try {
            await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq",".ref"]);
            return false; // still present
          } catch (e) {
            const msg=String(e).toLowerCase();
            if (!(msg.includes("404")||msg.includes("not found"))) return false;
          }
        }
      }
      // Verify local and remote both absent before deleting provenance
      try {
        const out = execSync(`git branch --list "${branchName}"`, { encoding:"utf8", cwd: repoRoot }).trim();
        if (out) return false;
      } catch {}
      if (ownerRepo) {
        try {
          const ref = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs/heads/${branchName}`, "--jq",".ref"]);
          if (ref && ref.includes(branchName)) return false;
        } catch (e) {
          const msg=String(e).toLowerCase();
          if (!(msg.includes("404")||msg.includes("not found"))) return false;
        }
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
      // Authoritative: remove labels, assignee, close, and verify each step
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
      // Close
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
      // Fresh read-back verification — every read failure is FACTORY_ERROR (return false)
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
