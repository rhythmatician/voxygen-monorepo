import {
  isImplementationEligible,
  isResearchEligible,
  detectContradictions,
  WAYFINDER_RESEARCH,
  READY_FOR_AGENT,
  AGENT_IMPLEMENT,
  AGENT_IN_PROGRESS,
  type IssueInput,
} from "./tracker-policy.mts";
import { claimImplementation, reconcileStaleImplementation, type ClaimOps } from "./tracker-operations.mts";
import * as fs2 from "node:fs";
import { execSync, execFile } from "node:child_process";
import { promisify } from "node:util";

export interface CanaryResult {
  implementationDiscoverableOnlyWithReadyAndImplement: boolean;
  successfulClaimConsumesImplement: boolean;
  staleReconciliationReleasesWithoutRestoring: boolean;
  researchDiscoverableFromWayfinderAlone: boolean;
  contradictionsFailBeforeWorker: boolean;
  fixturesCleaned: boolean;
  cleanupFailures: string[];
  fixtureIds: number[];
  receiptPath?: string;
}

export interface CanaryOps {
  createIssue: (title: string, body: string, labels: string[]) => Promise<number>;
  fetchIssue: (id: number) => Promise<IssueInput>;
  closeIssue: (id: number) => Promise<void>;
  claimImplementation: (issue: IssueInput) => Promise<{ success: boolean; reason?: string }>;
  reconcile: (issue: IssueInput) => Promise<boolean>;
}

function uniqueTitle(prefix: string): string {
  return `${prefix} — canary ${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function ghBinary(): string {
  const home = process.env.HOME || "";
  const candidates = ["/usr/bin/gh", home ? `${home}/.local/bin/gh` : "", "/home/jeff/.local/bin/gh"];
  for (const p of candidates) if (p && fs2.existsSync(p)) return p;
  return "gh";
}
function ghToken(): string {
  if (process.env.GH_TOKEN) return process.env.GH_TOKEN;
  try { const c=fs2.readFileSync(".sandcastle/.env","utf8"); const m=c.match(/^GH_TOKEN=(.*)$/m); if(m) return m[1].trim(); } catch {}
  return "";
}
async function runGh(args: string[]): Promise<string> {
  const execFileAsync = promisify(execFile);
  const bin = ghBinary();
  const token = ghToken();
  const env = { ...process.env, GH_TOKEN: token };
  const { stdout } = await execFileAsync(bin, args, { env, cwd: process.cwd(), maxBuffer: 10*1024*1024 }) as any;
  return (stdout as string).trim();
}
function parseOwnerRepo(): { owner: string; repo: string } | null {
  try { const out = execSync("git remote get-url origin", { encoding: "utf8" }).trim(); const m = out.match(/github\.com[:\/]([^\/]+)\/([^\/.]+)/); if (m) return { owner: m[1], repo: m[2] }; } catch {}
  return null;
}
async function fetchIssueReal(id: number): Promise<IssueInput> {
  const rawJson = await runGh(["issue", "view", String(id), "--json", "number,title,body,labels,assignees,state"]);
  const raw = JSON.parse(rawJson);
  const ownerRepo = parseOwnerRepo();
  let blockedByCount: number | undefined = undefined;
  if (ownerRepo) {
    try { const summary = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${id}`, "--jq", ".issue_dependencies_summary.blocked_by"]); const n=parseInt(summary.trim(),10); if(!isNaN(n)) blockedByCount=n; } catch {}
  }
  return {
    number: raw.number,
    title: raw.title,
    state: (raw.state?.toLowerCase() ?? "open") as "open" | "closed",
    labels: (raw.labels ?? []).map((l: any) => l.name),
    assignees: (raw.assignees ?? []).map((a: any) => a.login),
    blockedByCount,
    body: raw.body,
  };
}
export async function runCanary(ops: CanaryOps, opts: { live: boolean }): Promise<CanaryResult> {
  if (!opts.live) throw new Error("Canary requires explicit --live flag");
  const result: CanaryResult = {
    implementationDiscoverableOnlyWithReadyAndImplement: false,
    successfulClaimConsumesImplement: false,
    staleReconciliationReleasesWithoutRestoring: false,
    researchDiscoverableFromWayfinderAlone: false,
    contradictionsFailBeforeWorker: false,
    fixturesCleaned: false,
    cleanupFailures: [],
    fixtureIds: [],
  };
  const fixtures: number[] = [];
  try {
    const implTitle = uniqueTitle("Canary impl");
    const implBody = "Scope bounded observable outcome\nno unresolved design decided\nacceptance criteria done when\nverification path verify\ndependencies blocked by none\nsmall enough for one session\nvertical tracer bullet slice";
    const implOnlyReady = await ops.createIssue(implTitle + " ready-only", implBody, [READY_FOR_AGENT]);
    fixtures.push(implOnlyReady); result.fixtureIds.push(implOnlyReady);
    let issueReadyOnly = await ops.fetchIssue(implOnlyReady);
    const eligibleReadyOnly = isImplementationEligible(issueReadyOnly);
    if (eligibleReadyOnly.eligible) throw new Error("ready-only should not be implementation eligible");
    const implReadyImplement = await ops.createIssue(implTitle + " ready+implement", implBody, [READY_FOR_AGENT, AGENT_IMPLEMENT]);
    fixtures.push(implReadyImplement); result.fixtureIds.push(implReadyImplement);
    let issueReadyImplement = await ops.fetchIssue(implReadyImplement);
    const eligibleReadyImplement = isImplementationEligible(issueReadyImplement);
    if (!eligibleReadyImplement.eligible) throw new Error("ready+implement should be eligible");
    result.implementationDiscoverableOnlyWithReadyAndImplement = true;
    const claimResult = await ops.claimImplementation(issueReadyImplement);
    if (!claimResult.success) throw new Error("claim should succeed");
    let afterClaim = await ops.fetchIssue(implReadyImplement);
    const hasReady = afterClaim.labels.includes(READY_FOR_AGENT);
    const hasImplement = afterClaim.labels.includes(AGENT_IMPLEMENT);
    const hasInProgress = afterClaim.labels.includes(AGENT_IN_PROGRESS);
    const hasAssignee = afterClaim.assignees.length > 0;
    if (!hasReady || hasImplement || !hasInProgress || !hasAssignee) throw new Error("claim postcondition failed");
    result.successfulClaimConsumesImplement = true;
    const reconciled = await ops.reconcile(afterClaim);
    if (!reconciled) throw new Error("reconciliation should succeed");
    let afterReconcile = await ops.fetchIssue(implReadyImplement);
    if (afterReconcile.labels.includes(AGENT_IN_PROGRESS) || afterReconcile.assignees.length > 0 || afterReconcile.labels.includes(AGENT_IMPLEMENT)) throw new Error("reconcile should release");
    if (!afterReconcile.labels.includes(READY_FOR_AGENT)) throw new Error("ready-for-agent should remain after reconcile");
    result.staleReconciliationReleasesWithoutRestoring = true;
    const researchTitle = uniqueTitle("Canary research");
    const researchBody = "Canary research question\nPart of #190";
    const researchId = await ops.createIssue(researchTitle, researchBody, [WAYFINDER_RESEARCH]);
    fixtures.push(researchId); result.fixtureIds.push(researchId);
    let researchIssue = await ops.fetchIssue(researchId);
    const researchEligible = isResearchEligible(researchIssue);
    if (!researchEligible.eligible) throw new Error("research should be eligible from wayfinder alone");
    result.researchDiscoverableFromWayfinderAlone = true;
    const contraTitle = uniqueTitle("Canary contra");
    const contraId = await ops.createIssue(contraTitle, implBody, [WAYFINDER_RESEARCH, AGENT_IMPLEMENT, READY_FOR_AGENT]);
    fixtures.push(contraId); result.fixtureIds.push(contraId);
    let contraIssue = await ops.fetchIssue(contraId);
    const contraValidation = detectContradictions(contraIssue);
    if (contraValidation.contradictions.length === 0) throw new Error("contradictory should have contradictions");
    const implEligibleContra = isImplementationEligible(contraIssue);
    const researchEligibleContra = isResearchEligible(contraIssue);
    if (implEligibleContra.eligible || researchEligibleContra.eligible) throw new Error("contradictory should not be eligible");
    const contraClaim = await ops.claimImplementation(contraIssue);
    if (contraClaim.success) throw new Error("contradictory claim should fail");
    result.contradictionsFailBeforeWorker = true;
  } finally {
    for (const id of fixtures) {
      try { await ops.closeIssue(id); } catch (e) { result.cleanupFailures.push("close #"+id+" failed: "+String(e)); }
    }
    result.fixturesCleaned = result.cleanupFailures.length === 0;
  }
  return result;
}
async function main() {
  const args = process.argv.slice(2);
  const live = args.includes("--live") || args.includes("--canary");
  if (!live) { console.error("Canary requires explicit --live flag"); process.exit(1); }
  console.log("Live tracker canary — requires live GitHub access");
  const ops: CanaryOps = {
    createIssue: async (title, body, labels) => {
      const labelArgs: string[] = [];
      for (const l of labels) labelArgs.push("--label", l);
      const out = await runGh(["issue", "create", "--title", title, "--body", body, ...labelArgs, "--json", "number", "--jq", ".number"]);
      const n = parseInt(out.trim(), 10);
      if (isNaN(n)) throw new Error("failed to create issue: "+out);
      console.log("Created canary fixture #"+n);
      return n;
    },
    fetchIssue: fetchIssueReal,
    closeIssue: async (id) => { await runGh(["issue", "close", String(id), "--comment", "Canary fixture — cleaning up"]); },
    claimImplementation: async (issue) => {
      const id = String(issue.number);
      const claimOps: ClaimOps = {
        fetchIssue: async (fid) => fetchIssueReal(parseInt(fid,10)),
        applyClaim: async (fid) => { await runGh(["issue", "edit", fid, "--add-assignee", "@me", "--add-label", "agent:in-progress", "--remove-label", "agent:implement"]); },
        verifyClaim: async (fid) => fetchIssueReal(parseInt(fid,10)),
        compensateClaim: async (fid) => { try { await runGh(["issue", "edit", fid, "--remove-label", "agent:in-progress", "--remove-assignee", "@me"]); return true; } catch { return false; } },
      };
      const { claimImplementation: prodClaim } = await import("./tracker-operations.mts");
      const res = await prodClaim(id, issue, claimOps);
      return { success: res.success, reason: (res as any).reason };
    },
    reconcile: async (issue) => {
      const id = String(issue.number);
      const branch = "sandcastle/issue-"+id;
      const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
      const ops2: any = {
        releaseClaim: async (rid: string) => { try { await runGh(["issue", "edit", rid, "--remove-label", "agent:in-progress", "--remove-assignee", "@me"]); return true; } catch { return false; } },
        comment: async (cid: string, body: string) => { try { await runGh(["issue", "comment", cid, "--body", body]); return true; } catch { return false; } },
        fetchIssue: async (fid: string) => fetchIssueReal(parseInt(fid,10)),
      };
      const res = await reconcileStaleImplementation(issue, branch, ops2);
      return res.reconciled;
    },
  };
  let result: CanaryResult | null = null;
  let receiptPath = ".sandcastle/canary-receipt.json";
  try {
    result = await runCanary(ops, { live: true });
    fs2.writeFileSync(receiptPath, JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
    const allPassed = result.implementationDiscoverableOnlyWithReadyAndImplement && result.successfulClaimConsumesImplement && result.staleReconciliationReleasesWithoutRestoring && result.researchDiscoverableFromWayfinderAlone && result.contradictionsFailBeforeWorker && result.fixturesCleaned;
    if (!allPassed) { console.error("Canary FAILED"); process.exit(1); }
    if (result.cleanupFailures.length > 0) { console.error("cleanup failures", result.cleanupFailures); process.exit(1); }
    console.log("Canary PASSED");
    process.exit(0);
  } catch (e) {
    const receipt = result ?? { error: String(e), fixturesCleaned: false };
    try { fs2.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2)); } catch {}
    console.error("Canary error", e);
    process.exit(1);
  }
}
if (import.meta.url === "file://"+process.argv[1]) { main().catch(e => { console.error(e); process.exit(1); }); }
