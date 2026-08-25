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
import { createTrackerAdapter, type ReceiptSink } from "./tracker-adapter.mts";
import { withTemporaryIssueFixtures, withAtomicJsonReceipt } from "./resource-scopes.mts";
import { createGhTransport, type GhTransport } from "./gh-transport.mts";
import * as fs2 from "node:fs";
import * as path2 from "node:path";
import * as childProcess from "node:child_process";
import { pathToFileURL, fileURLToPath } from "node:url";

// REPO_ROOT for canary: stable repo root two levels above this module's directory.
const CANARY_REPO_ROOT = path2.resolve(path2.dirname(fileURLToPath(import.meta.url)), "..");

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

/**
 * Durable typed-transition-receipt sink for live canary runs — atomic writes
 * via withAtomicJsonReceipt under .sandcastle/logs/canary-transitions/.
 * Persistence failures THROW so the saga converts an unpersistable required
 * receipt into indeterminate FACTORY_ERROR.
 */
function makeFileReceiptSink(repoRoot: string): ReceiptSink {
  const dir = path2.join(repoRoot, ".sandcastle", "logs", "canary-transitions");
  // Per-run sequence — collision-resistant receipt filenames.
  let seq = 0;
  return {
    persist(receipt: unknown): void {
      const name = `${Date.now()}-${String(seq++).padStart(4, "0")}-${(receipt as { transition?: string }).transition ?? "transition"}-${(receipt as { issueNumber?: number }).issueNumber ?? "x"}.json`;
      withAtomicJsonReceipt(path2.join(dir, name), () => receipt);
      JSON.parse(fs2.readFileSync(path2.join(dir, name), "utf8"));
    },
  };
}

function uniqueTitle(prefix: string): string {
  return `${prefix} — canary ${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

// Single transport — no local ghBinary/ghToken/runGh/parseOwnerRepo duplicates.
const canaryTransport: GhTransport = createGhTransport({
  repoRoot: CANARY_REPO_ROOT,
  capabilityMode: "read-write", // canary --live is the only mode that reaches here
});

/**
 * Wrap an injected gh runner as a transport so adapter construction works for
 * both live runs (real transport) and tests (mock runner) through one path.
 */
function transportFromRunner(runGhFn: (args: string[]) => Promise<string>, claimantLogin: string): GhTransport {
  return {
    capabilityMode: "read-write",
    isWriteForbidden: () => false,
    run: runGhFn,
    tryRun: async (args) => { try { await runGhFn(args); return true; } catch { return false; } },
    resolveClaimantLogin: async () => claimantLogin,
    resolveOwnerRepo: () => canaryTransport.resolveOwnerRepo(),
  };
}

export async function resolveClaimantLogin(runGhFn: (args: string[]) => Promise<string> = (args) => canaryTransport.run(args)): Promise<string> {
  // Transport-owned claimant resolution — no local candidate loop.
  return canaryTransport.resolveClaimantLogin();
}
async function fetchIssueReal(id: number, runGhFn: (args:string[])=>Promise<string> = (args)=>canaryTransport.run(args)): Promise<IssueInput> {
  const rawJson = await runGhFn(["issue", "view", String(id), "--json", "number,title,body,labels,assignees,state"]);
  const raw = JSON.parse(rawJson);
  const ownerRepo = canaryTransport.resolveOwnerRepo();
  let blockedByCount: number | undefined = undefined;
  if (ownerRepo) {
    try { const summary = await runGhFn(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${id}`, "--jq", ".issue_dependencies_summary.blocked_by"]); const n=parseInt(summary.trim(),10); blockedByCount = isNaN(n) ? undefined : n; } catch { blockedByCount = undefined; }
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
  /**
   * Real or injected local Git runner — reconciliation's branch/worktree
   * inspections must be executable, never an always-failing stub.
   */
  runGit?: (args: string[]) => { exitCode: number; stdout: string; stderr: string };
  /** Stable repository root for the local git runner. Defaults to CANARY_REPO_ROOT. */
  repoRoot?: string;
  /** Receipt sink override for tests. Production defaults to a durable atomic file sink. */
  receiptSink?: ReceiptSink;
}): CanaryOps {
  const { runGh: runGhFn, claimantLogin } = opts;
  const fetchReal = opts.fetchIssueRealFn ?? ((id:number)=>fetchIssueReal(id, runGhFn));
  const canaryRepoRoot = opts.repoRoot ?? CANARY_REPO_ROOT;
  // Real git runner by default — the fresh fixture's local branch absence is
  // PROVED by inspection, not represented by a failing stub.
  const runGit = opts.runGit ?? ((args: string[]) => {
    try {
      const out = childProcess.execFileSync("git", args, { encoding: "utf8", cwd: canaryRepoRoot } as any);
      const stdout = typeof out === "string" ? out : Buffer.from(out).toString();
      return { exitCode: 0, stdout, stderr: "" };
    } catch (e: any) {
      return { exitCode: typeof e?.status === "number" ? e.status : 1, stdout: e?.stdout?.toString() ?? "", stderr: e?.stderr?.toString() ?? String(e?.message ?? e) };
    }
  });
  // ONE adapter per ops instance — every tracker mutation (claims,
  // reconciliation transitions, fixture cleanup) flows through it with typed
  // receipts persisted to the shared sink. PRODUCTION uses a durable atomic
  // file sink (typed transition receipts survive the process); tests may
  // inject an in-memory sink.
  const receiptSink: ReceiptSink = opts.receiptSink ?? makeFileReceiptSink(canaryRepoRoot);
  const canaryTransport = transportFromRunner(runGhFn, claimantLogin);
  const adapter = createTrackerAdapter({
    gh: canaryTransport,
    receiptSink,
    runGit,
    repoRoot: canaryRepoRoot,
  });
  return {
    createIssue: async (title, body, labels) => {
      // Adapter-owned canary fixture creation — never a raw gh POST. The
      // adapter freshly proves the created state and returns the fixture id
      // even when creation is indeterminate (receipt persistence failure), so
      // the fixture is still registered for cleanup.
      const created = await adapter.createCanaryFixture(title, body, labels);
      return created.id;
    },
    fetchIssue: fetchReal,
    closeIssue: async (id) => {
      // Adapter-owned canary fixture cleanup — saga-verified close with typed
      // receipt. Never a raw gh close.
      await adapter.cleanupCanaryFixture(id);
    },
    cleanupIssue: async (id) => {
      // Adapter-owned canary fixture cleanup — saga-verified label removal,
      // claimant assignee removal, and close. Never raw gh commands, never
      // swallowed errors.
      await adapter.cleanupCanaryFixture(id);
    },
    claimImplementation: async (issue) => {
      // Route through the ONE production tracker adapter — the canary must
      // exercise the exact production claim implementation, not a parallel one.
      const result = await adapter.claimImplementation(issue);
      return { success: result.kind === "committed", reason: result.receipt.reason ?? result.receipt.code };
    },
    reconcile: async (issue) => {
      const id = String(issue.number);
      const branch = "sandcastle/issue-"+id;
      // Same adapter authority used by main — no parallel reconciliation implementation.
      // The adapter's internal git inspections use the REAL (or injected) runner.
      const res = await adapter.reconcileStaleImplementation(issue, branch);
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

  // Fixture lifecycle owned by the narrow resource scope: record-at-acquisition
  // (partial acquisition cannot leak), finally-cleanup, required fresh-read
  // verification, fail closed on uncertainty.
  const fixturesResult = await withTemporaryIssueFixtures(
    async ({ record }) => {
      const implTitle = uniqueTitle("Canary impl");
      const implBody = "Scope bounded observable outcome\nno unresolved design decided\nacceptance criteria done when\nverification path verify\ndependencies blocked by none\nsmall enough for one session\nvertical tracer bullet slice";
      const implOnlyReady = await ops.createIssue(implTitle + " ready-only", implBody, [READY_FOR_AGENT]);
      record(implOnlyReady); result.fixtureIds.push(implOnlyReady);
      let issueReadyOnly = await ops.fetchIssue(implOnlyReady);
      const eligibleReadyOnly = isImplementationEligible(issueReadyOnly);
      if (eligibleReadyOnly.eligible) throw new Error("ready-only should not be implementation eligible");
      const implReadyImplement = await ops.createIssue(implTitle + " ready+implement", implBody, [READY_FOR_AGENT, AGENT_IMPLEMENT]);
      record(implReadyImplement); result.fixtureIds.push(implReadyImplement);
      let issueReadyImplement = await ops.fetchIssue(implReadyImplement);
      const eligibleReadyImplement = isImplementationEligible(issueReadyImplement);
      if (!eligibleReadyImplement.eligible) throw new Error("ready+implement should be eligible");
      result.implementationDiscoverableOnlyWithReadyAndImplement = true;
      const claimResult = await ops.claimImplementation(issueReadyImplement);
      if (!claimResult.success) throw new Error(`claim should succeed: ${claimResult.reason ?? "unknown"}`);
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
      record(researchId); result.fixtureIds.push(researchId);
      let researchIssue = await ops.fetchIssue(researchId);
      const researchEligible = isResearchEligible(researchIssue);
      if (!researchEligible.eligible) throw new Error("research should be eligible from wayfinder alone");
      result.researchDiscoverableFromWayfinderAlone = true;
      const contraTitle = uniqueTitle("Canary contra");
      const contraId = await ops.createIssue(contraTitle, implBody, [WAYFINDER_RESEARCH, AGENT_IMPLEMENT, READY_FOR_AGENT]);
      record(contraId); result.fixtureIds.push(contraId);
      let contraIssue = await ops.fetchIssue(contraId);
      const contraValidation = detectContradictions(contraIssue);
      if (contraValidation.contradictions.length === 0) throw new Error("contradictory should have contradictions");
      const implEligibleContra = isImplementationEligible(contraIssue);
      const researchEligibleContra = isResearchEligible(contraIssue);
      if (implEligibleContra.eligible || researchEligibleContra.eligible) throw new Error("contradictory should not be eligible");
      const contraClaim = await ops.claimImplementation(contraIssue);
      if (contraClaim.success) throw new Error("contradictory claim should fail");
      result.contradictionsFailBeforeWorker = true;
      return true;
    },
    {
      cleanup: async (id) => {
        // ONE adapter-owned cleanup operation, invoked exactly once per
        // fixture. The adapter's cleanupCanaryFixture removes stale machine
        // labels, removes the authenticated claimant assignee, and closes the
        // issue in a single verified saga with one final receipt. Never wire
        // cleanupIssue AND closeIssue to the same full cleanup operation.
        if (ops.cleanupIssue) {
          await ops.cleanupIssue(id);
        } else {
          if (ops.removeAssignee) { try { await ops.removeAssignee(id); } catch {} }
          if (ops.removeLabel) {
            for (const lbl of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) { try { await ops.removeLabel(id, lbl); } catch {} }
          }
          await ops.closeIssue(id);
        }
      },
      verify: async (id) => {
        const after = await ops.fetchIssue(id);
        const isClosed = after.state === "closed";
        const isUnassigned = after.assignees.length === 0;
        const hasTransient = after.labels.includes(AGENT_IN_PROGRESS) || after.labels.includes(AGENT_IMPLEMENT) || after.labels.includes(AGENT_BLOCKED);
        if (!isClosed) return `fixture #${id} not closed after cleanup: state=${after.state}`;
        if (!isUnassigned) return `fixture #${id} still assigned after cleanup: ${after.assignees.join(",")}`;
        if (hasTransient) return `fixture #${id} still has transient/command labels after cleanup: ${after.labels.join(",")}`;
        return null;
      },
    },
  );

  // fixturesCleaned means CLEANUP PROVEN (per-fixture fresh-read verification),
  // deliberately independent of primaryError — the canary reports primary
  // failures separately via result.primaryError.
  result.cleanupFailures.push(...fixturesResult.cleanupFailures);
  result.fixturesCleaned = fixturesResult.cleanupFailures.length === 0 && fixturesResult.fixtureIds.length > 0;
  if (fixturesResult.primaryError) {
    result.primaryError = fixturesResult.primaryError;
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
  const ownerRepo = canaryTransport.resolveOwnerRepo();
  if (!ownerRepo) throw new Error("cannot resolve owner/repo for canary");
  const runGhFn = deps.runGh ?? ((ghArgs: string[]) => canaryTransport.run(ghArgs));
  const claimantLogin = deps.resolveClaimantLoginFn ? await deps.resolveClaimantLoginFn() : await resolveClaimantLogin(runGhFn);
  const ops = createLiveCanaryOps({ owner: ownerRepo.owner, repo: ownerRepo.repo, runGh: runGhFn, claimantLogin });
  let result: CanaryResult | null = null;
  const receiptPath = ".sandcastle/logs/canary-receipt.json";
  try { fs2.mkdirSync(".sandcastle/logs", { recursive: true }); } catch {}
  // Use deps for file writes if provided (for testing)
  const writeFile = deps.writeFileSync ?? fs2.writeFileSync;
  const mkdir = deps.mkdirSync ?? fs2.mkdirSync;
  // Atomic receipt writer — evidence is temp-file + rename; injected
  // writeFileSync (tests) still flows through the same atomic path.
  const writeReceiptAtomic = (targetPath: string, data: unknown): void => {
    withAtomicJsonReceipt(targetPath, () => data, {
      writeFileSync: ((p: any, d: any) => writeFile(p, typeof d === "string" ? d : JSON.stringify(d, null, 2))) as any,
      mkdirSync: mkdir as any,
    });
  };
  try { mkdir(".sandcastle/logs", { recursive: true }); } catch {}
  try {
    result = await runCanary(ops, { live: true });
    writeReceiptAtomic(receiptPath, result);
    console.log(JSON.stringify(result, null, 2));
    const allPassed = result.implementationDiscoverableOnlyWithReadyAndImplement && result.successfulClaimConsumesImplement && result.staleReconciliationReleasesWithoutRestoring && result.researchDiscoverableFromWayfinderAlone && result.contradictionsFailBeforeWorker && result.fixturesCleaned;
    if (!allPassed) { console.error("Canary FAILED"); return { exitCode: 1, result }; }
    if (result.cleanupFailures.length > 0) { console.error("cleanup failures", result.cleanupFailures); return { exitCode: 1, result }; }
    console.log("Canary PASSED");
    return { exitCode: 0, result };
  } catch (e) {
    const receipt = result ?? { error: String(e), fixturesCleaned: false, fixtureIds: [], cleanupFailures: [] } as any;
    try { writeReceiptAtomic(receiptPath, receipt); } catch {}
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
