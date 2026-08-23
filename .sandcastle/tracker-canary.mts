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
    const researchBody = "## Question\n\nCanary research question with substantive details for validation, part of #190 with evidence needed and mechanism to be investigated.";
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
    // For every fixture and in finally: remove assignee, transient, close, read back, verify
    for (const id of fixtures) {
      let perFixtureCleaned = true;
      let perError: string | null = null;
      try {
        // Try to fetch current state first
        let issue: any = null;
        try { issue = await ops.fetchIssue(id); } catch {}
        // Attempt to remove assignee and transient labels if present (best-effort)
        // The closeIssue should handle this via gh issue close, but we also ensure via direct ops if available
        // For live ops, closeIssue will be gh issue close; for mock, it just closes
        await ops.closeIssue(id);
        // Read back and verify closed, unassigned, free of transient
        try {
          const after = await ops.fetchIssue(id);
          const isClosed = after.state === "closed";
          const isUnassigned = after.assignees.length === 0;
          const hasTransient = after.labels.includes("agent:in-progress") || after.labels.includes("agent:implement") || after.labels.includes("agent:blocked");
          if (!isClosed) { perFixtureCleaned = false; perError = `fixture #${id} not closed after cleanup: state=${after.state}`; }
          else if (!isUnassigned) { perFixtureCleaned = false; perError = `fixture #${id} still assigned after cleanup: ${after.assignees.join(",")}`; }
          else if (hasTransient) { perFixtureCleaned = false; perError = `fixture #${id} still has transient/command labels after cleanup: ${after.labels.join(",")}`; }
        } catch (e) {
          // If fetch after close fails, consider cleanup not verified
          perFixtureCleaned = false;
          perError = `fixture #${id} read-back after close failed: ${String(e)}`;
        }
      } catch (e) {
        perFixtureCleaned = false;
        perError = `close #${id} failed: ${String(e)}`;
      }
      if (!perFixtureCleaned) {
        result.cleanupFailures.push(perError || `fixture #${id} cleanup incomplete`);
      }
    }
    // fixturesCleaned must come from read-back verification, not merely absence of exception
    result.fixturesCleaned = result.cleanupFailures.length === 0 && fixtures.length > 0;
    // Preserve fixture IDs and per-fixture results even if primary canary failed
    // (already in result.fixtureIds and cleanupFailures)
  }
  return result;
}
async function main() {
  const args = process.argv.slice(2);
  const live = args.includes("--live") || args.includes("--canary");
  if (!live) { console.error("Canary requires explicit --live flag"); process.exit(1); }
  console.log("Live tracker canary — requires live GitHub access");
  const ownerRepo = parseOwnerRepo();
  if (!ownerRepo) throw new Error("cannot resolve owner/repo for canary");
  const ops: CanaryOps = {
    createIssue: async (title, body, labels) => {
      // Use GitHub REST API via gh api --method POST (gh issue create does not support --json/--jq)
      const args: string[] = ["api", "--method", "POST", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues`, "-f", `title=${title}`, "-f", `body=${body}`];
      for (const l of labels) args.push("-f", `labels[]=${l}`);
      args.push("--jq", ".number");
      const out = await runGh(args);
      const n = parseInt(out.trim(), 10);
      if (isNaN(n)) throw new Error("failed to create issue via gh api POST: "+out+" args="+args.join(" "));
      console.log("Created canary fixture #"+n+" via gh api POST");
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
  let receiptPath = ".sandcastle/logs/canary-receipt.json";
  try { fs2.mkdirSync(".sandcastle/logs", { recursive: true }); } catch {}
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
