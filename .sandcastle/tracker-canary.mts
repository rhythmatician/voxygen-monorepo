import {
  isImplementationEligible,
  isResearchEligible,
  detectContradictions,
  WAYFINDER_RESEARCH,
  READY_FOR_AGENT,
  AGENT_IMPLEMENT,
  AGENT_IN_PROGRESS,
  AGENT_BLOCKED,
  type IssueInput,
} from "./tracker-policy.mts";
import { claimImplementation, reconcileStaleImplementation, type ClaimOps } from "./tracker-operations.mts";
import * as fs2 from "node:fs";
import * as path2 from "node:path";
import { pathToFileURL } from "node:url";
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
  primaryError?: string;
}

export interface CanaryOps {
  createIssue: (title: string, body: string, labels: string[]) => Promise<number>;
  fetchIssue: (id: number) => Promise<IssueInput>;
  closeIssue: (id: number) => Promise<void>;
  claimImplementation: (issue: IssueInput) => Promise<{ success: boolean; reason?: string }>;
  reconcile: (issue: IssueInput) => Promise<boolean>;
  cleanupIssue?: (id: number) => Promise<void>;
  removeAssignee?: (id: number) => Promise<void>;
  removeLabel?: (id: number, label: string) => Promise<void>;
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
  try { const out = execSync("git remote get-url origin", { encoding: "utf8" }).trim(); const m = out.match(/github\.com[:\/]([^\/]+)\/([^\/\.]+)/); if (m) return { owner: m[1], repo: m[2] }; } catch {}
  return null;
}
export async function resolveClaimantLogin(runGhFn: (args: string[]) => Promise<string> = runGh): Promise<string> {
  const candidates = [
    ["api", "user", "--jq", ".login"],
    ["api", "/user", "--jq", ".login"],
  ];
  for (const args of candidates) {
    try {
      const out = await runGhFn(args);
      const login = out.trim().replace(/"/g, "");
      if (login && login !== "null" && !login.includes(" ")) return login;
    } catch {}
  }
  throw new Error("failed to resolve claimant login via gh api user");
}
async function fetchIssueReal(id: number, runGhFn: (args:string[])=>Promise<string> = runGh): Promise<IssueInput> {
  const rawJson = await runGhFn(["issue", "view", String(id), "--json", "number,title,body,labels,assignees,state"]);
  const raw = JSON.parse(rawJson);
  const ownerRepo = parseOwnerRepo();
  let blockedByCount: number | undefined = undefined;
  if (ownerRepo) {
    try { const summary = await runGhFn(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${id}`, "--jq", ".issue_dependencies_summary.blocked_by"]); const n=parseInt(summary.trim(),10); if(!isNaN(n)) blockedByCount=n; } catch {}
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

export function createLiveCanaryOps(opts: {
  owner: string;
  repo: string;
  runGh: (args: string[]) => Promise<string>;
  claimantLogin: string;
  fetchIssueRealFn?: (id:number)=>Promise<IssueInput>;
}): CanaryOps {
  const { owner, repo, runGh: runGhFn, claimantLogin } = opts;
  const fetchReal = opts.fetchIssueRealFn ?? ((id:number)=>fetchIssueReal(id, runGhFn));
  return {
    createIssue: async (title, body, labels) => {
      const args: string[] = ["api", "--method", "POST", `repos/${owner}/${repo}/issues`, "-f", `title=${title}`, "-f", `body=${body}`];
      for (const l of labels) args.push("-f", `labels[]=${l}`);
      args.push("--jq", ".number");
      const out = await runGhFn(args);
      const n = parseInt(out.trim(), 10);
      if (isNaN(n)) throw new Error("failed to create issue via gh api POST: "+out+" args="+args.join(" "));
      return n;
    },
    fetchIssue: fetchReal,
    closeIssue: async (id) => { await runGhFn(["issue", "close", String(id), "--comment", "Canary fixture — cleaning up"]); },
    cleanupIssue: async (id) => {
      // Remove claimant assignee and transient labels
      try { await runGhFn(["issue", "edit", String(id), "--remove-assignee", claimantLogin]); } catch {}
      for (const label of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) {
        try { await runGhFn(["issue", "edit", String(id), "--remove-label", label]); } catch {}
      }
    },
    claimImplementation: async (issue) => {
      const id = String(issue.number);
      const claimOps: ClaimOps & { claimantLogin: string } = {
        claimantLogin,
        fetchIssue: async (fid) => fetchReal(parseInt(fid,10)),
        applyClaim: async (fid) => { await runGhFn(["issue", "edit", fid, "--add-assignee", claimantLogin, "--add-label", AGENT_IN_PROGRESS, "--remove-label", AGENT_IMPLEMENT]); },
        verifyClaim: async (fid) => fetchReal(parseInt(fid,10)),
        compensateClaim: async (fid) => { try { await runGhFn(["issue", "edit", fid, "--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]); return true; } catch { return false; } },
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
        releaseClaim: async (rid: string) => { try { await runGhFn(["issue", "edit", rid, "--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]); return true; } catch { return false; } },
        comment: async (cid: string, body: string) => { try { await runGhFn(["issue", "comment", cid, "--body", body]); return true; } catch { return false; } },
        fetchIssue: async (fid: string) => fetchReal(parseInt(fid,10)),
        getBatchPrNumber: async () => ({ prNumber: null, state: "absent" as const }),
        getPrState: async () => ({ state: "CLOSED", mergedAt: null, found: false }),
        checkBranchExists: async () => "absent" as const,
        checkProvenanceValid: async () => ({ valid: true }),
        hasCommitsAhead: async () => "empty" as const,
        deleteBranch: async () => true,
        addBlocked: async (iid:string) => { try { await runGhFn(["issue", "edit", iid, "--add-label", AGENT_BLOCKED]); return true; } catch { return false; } },
        markIntegrated: async () => true,
      };
      const res = await reconcileStaleImplementation(issue, branch, ops2);
      // For canary, consider reconciled if postcondition holds regardless of blocked
      return res.reconciled || res.reason.includes("no branch");
    },
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
  let primaryError: Error | null = null;
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
  } catch (e) {
    primaryError = e instanceof Error ? e : new Error(String(e));
  } finally {
    for (const id of fixtures) {
      let perFixtureCleaned = true;
      let perError: string | null = null;
      try {
        // Real cleanup operation: remove assignee and transient labels, then close
        if (ops.cleanupIssue) {
          try { await ops.cleanupIssue(id); } catch (e) { perFixtureCleaned = false; perError = `cleanupIssue #${id} failed: ${String(e)}`; }
        } else {
          // Fallback: try to remove via individual ops if available
          if (ops.removeAssignee) { try { await ops.removeAssignee(id); } catch {} }
          if (ops.removeLabel) {
            for (const lbl of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) { try { await ops.removeLabel(id, lbl); } catch {} }
          }
        }
        await ops.closeIssue(id);
        try {
          const after = await ops.fetchIssue(id);
          const isClosed = after.state === "closed";
          const isUnassigned = after.assignees.length === 0;
          const hasTransient = after.labels.includes(AGENT_IN_PROGRESS) || after.labels.includes(AGENT_IMPLEMENT) || after.labels.includes(AGENT_BLOCKED);
          if (!isClosed) { perFixtureCleaned = false; perError = `fixture #${id} not closed after cleanup: state=${after.state}`; }
          else if (!isUnassigned) { perFixtureCleaned = false; perError = `fixture #${id} still assigned after cleanup: ${after.assignees.join(",")}`; }
          else if (hasTransient) { perFixtureCleaned = false; perError = `fixture #${id} still has transient/command labels after cleanup: ${after.labels.join(",")}`; }
        } catch (e) {
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
    result.fixturesCleaned = result.cleanupFailures.length === 0 && fixtures.length > 0;
  }
  if (primaryError) {
    result.primaryError = primaryError.message;
  }
  return result;
}

export interface CanaryCliDeps {
  runGh?: (args: string[]) => Promise<string>;
  resolveClaimantLoginFn?: () => Promise<string>;
  writeFileSync?: (path: string, data: string) => void;
  mkdirSync?: (path: string, opts?: any) => void;
}

export async function runCanaryCli(args: string[], deps: CanaryCliDeps = {}): Promise<{ exitCode: number; result?: CanaryResult }> {
  const live = args.includes("--live") || args.includes("--canary");
  if (!live) { console.error("Canary requires explicit --live flag"); return { exitCode: 1 }; }
  console.log("Live tracker canary — requires live GitHub access");
  const ownerRepo = parseOwnerRepo();
  if (!ownerRepo) throw new Error("cannot resolve owner/repo for canary");
  const runGhFn = deps.runGh ?? runGh;
  const claimantLogin = deps.resolveClaimantLoginFn ? await deps.resolveClaimantLoginFn() : await resolveClaimantLogin(runGhFn);
  const ops = createLiveCanaryOps({ owner: ownerRepo.owner, repo: ownerRepo.repo, runGh: runGhFn, claimantLogin });
  let result: CanaryResult | null = null;
  const receiptPath = ".sandcastle/logs/canary-receipt.json";
  try { fs2.mkdirSync(".sandcastle/logs", { recursive: true }); } catch {}
  // Use deps for file writes if provided (for testing)
  const writeFile = deps.writeFileSync ?? fs2.writeFileSync;
  const mkdir = deps.mkdirSync ?? fs2.mkdirSync;
  try { mkdir(".sandcastle/logs", { recursive: true }); } catch {}
  try {
    result = await runCanary(ops, { live: true });
    writeFile(receiptPath, JSON.stringify(result, null, 2) as any);
    console.log(JSON.stringify(result, null, 2));
    const allPassed = result.implementationDiscoverableOnlyWithReadyAndImplement && result.successfulClaimConsumesImplement && result.staleReconciliationReleasesWithoutRestoring && result.researchDiscoverableFromWayfinderAlone && result.contradictionsFailBeforeWorker && result.fixturesCleaned;
    if (!allPassed) { console.error("Canary FAILED"); return { exitCode: 1, result }; }
    if (result.cleanupFailures.length > 0) { console.error("cleanup failures", result.cleanupFailures); return { exitCode: 1, result }; }
    console.log("Canary PASSED");
    return { exitCode: 0, result };
  } catch (e) {
    const receipt = result ?? { error: String(e), fixturesCleaned: false, fixtureIds: [], cleanupFailures: [] } as any;
    try { writeFile(receiptPath, JSON.stringify(receipt, null, 2) as any); } catch {}
    console.error("Canary error", e);
    return { exitCode: 1, result: receipt };
  }
}

async function main() {
  const args = process.argv.slice(2);
  const result = await runCanaryCli(args, {});
  process.exit(result.exitCode);
}
const isMainModule = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(path2.resolve(process.argv[1])).href;
if (isMainModule) { main().catch(e => { console.error(e); process.exit(1); }); }
