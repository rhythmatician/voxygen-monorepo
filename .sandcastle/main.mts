// Factory v0 -- deterministic eligibility, claim-before-work, failure visibility,
// batch integration with audit trail. Preserves parallel isolated workers,
// review, and merger topology.

import * as sandcastle from "@ai-hero/sandcastle";
import { docker } from "@ai-hero/sandcastle/sandboxes/docker";
import { z } from "zod";
import { execFile, execSync, execFileSync } from "node:child_process";
import { promisify } from "node:util";
import * as fs from "node:fs";
import * as path from "node:path";

const REPO_ROOT = process.cwd();
import { isEligible, branchForIssue, type IssueInput } from "./dispatch.mts";
import {
  canClaimNextOuterIteration,
  partitionWorkerOutcomes,
  partitionToMutationPlan,
  type WorkerMutationKind,
  type WorkerOutcome,
} from "./factory-verdict-gate.mts";
import * as branchHelpers from "./branch-helpers.mts";
import { mayAutonomouslyMerge } from "./ci-policy.mts";
import { runReviewerPass } from "./review-pass.mts";
import {
  reviewVerdictSchema,
  isVerdictApproved,
  type ReviewVerdict,
} from "./review-verdict.mts";
import { formatGhFailure, getErrorMessage, getGhErrorDetails } from "./gh-errors.mts";
import { parsePlannerOutput, fallbackToSingle } from "./planner-helpers.mts";
import {
  makeIterationControl,
  planIssuesForIteration,
  type IterationControl,
  qualificationLifecyclePolicy,
} from "./factory-iteration-control.mts";
import {
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  isExpectedSandcastleSourceHead,
  resolveSandcastleRuntimeDistPath,
  verifySandcastleRuntimeDist,
  missingSandcastleRuntimeSymbols,
} from "./sandcastle-runtime-provenance.mts";
import {
  makeGitHubCapability,
  GitHubWriteForbiddenError,
} from "./github-capability.mts";
import { resolveWorkerSandboxEnv } from "./sandbox-token-env.mts";

const execFileAsync = promisify(execFile);

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const MAX_ITERATIONS = 10;
const REASON_TRUNCATE = 800;
const MERGER_REASON_TRUNCATE = 1000;
const WORKER_REASON_TRUNCATE = 2000;
const VERDICT_JSON_TRUNCATE = 2000;
const REVIEW_ERROR_TRUNCATE = 500;
const TARGET_BRANCH = "main";

// Retry budgets — small, bounded, behind a deep interface. Reviewer→implementer
// feedback (semantic/mechanical) gets one retry; transient sandbox/mechanical
// setup gets two with backoff. Callers see `reviewedImplement` as one call.
const REVIEW_RETRY_BUDGET = 1;
const MECHANICAL_RETRY_BUDGET = 2;
const MECHANICAL_RETRY_BASE_MS = 1000;
const ITERATION_CONTROL: IterationControl = makeIterationControl(MAX_ITERATIONS, process.argv);
const QUALIFICATION_LIFECYCLE = qualificationLifecyclePolicy(ITERATION_CONTROL.qualification);
if (ITERATION_CONTROL.qualification.kind === "invalid") {
  const reason = ITERATION_CONTROL.qualification.reason;
  console.error(`Invalid --issue argument (${reason})`);
  process.exit(1);
}

const GH_CAPABILITY_MODE: "read-only" | "read-write" = QUALIFICATION_LIFECYCLE.claimExternalState || QUALIFICATION_LIFECYCLE.mutateOutcomeState || QUALIFICATION_LIFECYCLE.integrate
  ? "read-write"
  : "read-only";
const WORKER_SANDBOX_ENV = resolveWorkerSandboxEnv(GH_CAPABILITY_MODE, ghToken());

const gitHubCapability = makeGitHubCapability({
  mode: GH_CAPABILITY_MODE,
  exec: async (args: string[]) => {
    const token = ghToken();
    const env = { ...process.env, GH_TOKEN: token };
    const bin = ghBinary();
    const { stdout } = await execFileAsync(bin, args, {
      env,
      cwd: REPO_ROOT,
      maxBuffer: 10 * 1024 * 1024,
    });
    return stdout.trim();
  },
});

// Worker result after implement + review -- commits plus machine-readable verdict.
type WorkerResult = { commits: string[]; verdict: ReviewVerdict | null; reviewText?: string };

const hooks = {
  // Install vendored skills into the sandbox before any other setup so
  // Wayfinder/implement/research agents can discover them via `muse skills`.
  // Each entry in .muse/skills/* is installed to the user scope; output is
  // truncated to keep sandbox logs concise. This is best-effort -- the
  // forbidden-label guard in implement-prompt.md remains the safety net.
  sandbox: {
    onSandboxReady: [
      { command: 'if [ ! -d /tmp/mattpocock-skills ]; then git clone --depth 1 https://github.com/rhythmatician/mattpocock-skills.git /tmp/mattpocock-skills 2>&1 | tail -5; fi; for s in /tmp/mattpocock-skills/skills/* /tmp/mattpocock-skills/skills/engineering/*; do [ -f "$s/SKILL.md" ] && muse skills install "$s" --scope user 2>&1 | head -5; done; echo "[skills] Docker user skills from rhythmatician/mattpocock-skills/main installed"' },
      { command: 'for s in .muse/skills/*; do muse skills install "$s" --scope user 2>&1 | head -5; done' },
      { command: 'npm install' },
      { command: 'bash .ci/install-voxy.sh install 2>&1 | tail -20; java -version 2>&1 | head -5; ./java/gradlew --version 2>&1 | tail -10' },
      // Ensure every worktree agent has a current GRAPH_REPORT.md for its HEAD.
      // graphify-out/ is gitignored so worktrees start without it; the hook's
      // worktree guard intentionally skips background rebuilds in worktrees.
      // Sandcastle worktrees intentionally do NOT rely on post-checkout hook -
      // graph is fresh per-session via this onSandboxReady `graphify update .`.
      // Single-session staleness is negligible (1-2 commits); no need for
      // per-commit hook in worktree. Host `post-checkout` (LF, WSL fallback)
      // keeps main's graphify-out fresh for /graphify queries. `graphify install`
      // for Muse skills is separate (vendored .muse/skills + `muse skills install`).
      // `graphify update .` is code-only (no LLM, no API key) and incremental
      // when a prior graph exists, so this is fast after the first build.
      { command: 'if command -v graphify >/dev/null 2>&1; then echo "[graphify] rebuilding for $(git rev-parse --short HEAD 2>/dev/null || echo HEAD)"; graphify update . 2>&1 | tail -30; echo "[graphify] report: $(grep \"Built from\" graphify-out/GRAPH_REPORT.md 2>/dev/null || echo \"missing\")"; elif python3 -m graphify --help >/dev/null 2>&1; then echo "[graphify] rebuilding via python3 -m graphify"; python3 -m graphify update . 2>&1 | tail -30; else echo "[graphify] not installed - skipping (rebuild Dockerfile)"; fi' },
    ],
  },
} as const;
const copyToWorktree: string[] = [];

const planSchema = z.object({
  issues: z.array(
    z.object({ id: z.string(), title: z.string(), branch: z.string() }),
  ),
});

type PlannedIssue = z.infer<typeof planSchema>["issues"][number];

// ---------------------------------------------------------------------------
// GH helpers -- host-side only
// ---------------------------------------------------------------------------
// runGh() executes on the host, so ghBinary() resolves the host `gh`.
// Inside the Docker sandbox (node:22-bookworm via .sandcastle/Dockerfile),
// `gh` is at /usr/bin/gh on PATH. Prompts and docker() commands must use
// bare `gh` and never call ghBinary() -- otherwise a host Windows path
// (C:\Program Files\...) would leak into the container where only
// /usr/bin/gh exists.

function ghBinary(): string {
  // Probe Linux gh first (host WSL: ~/.local/bin/gh, container: /usr/bin/gh)
  // so a Windows path never leaks into Linux/WSL where auth differs.
  const home = process.env.HOME || "";
  const linuxPaths = ["/usr/bin/gh", home ? `${home}/.local/bin/gh` : "", "/home/jeff/.local/bin/gh"];
  for(const p of linuxPaths){ if(p && fs.existsSync(p)) return p; }
  // Fallback to gh on PATH (resolves to Linux gh in WSL with correct auth)
  // Avoid Windows gh.exe which has different keyring/config in WSL.
  return "gh";
}

// muse binary: intentionally not hardcoded -- host and sandbox both expose
// `muse` on PATH (host via ~/.local/bin, sandbox via Dockerfile
// ENV PATH="/home/agent/.local/bin:$PATH" after install.sh).
// Do not introduce a host absolute path (same host-only principle as ghBinary).

async function safeRunGh(args: string[], failureContext?: string): Promise<boolean> {
  try {
    await runGh(args);
    return true;
  } catch (error: unknown) {
    if (error instanceof GitHubWriteForbiddenError) {
      if (failureContext) {
        console.warn(formatGhFailure(failureContext, error));
      }
      throw error;
    }
    if (failureContext) console.warn(formatGhFailure(failureContext, error));
    return false;
  }
}

function ghToken(): string {
  if (process.env.GH_TOKEN) return process.env.GH_TOKEN;
  // fallback: read .sandcastle/.env from stable REPO_ROOT, never ambient cwd
  try {
    const envPath = path.join(REPO_ROOT, ".sandcastle", ".env");
    const content = fs.readFileSync(envPath, "utf8");
    const m = content.match(/^GH_TOKEN=(.*)$/m);
    if (m) return m[1].trim();
  } catch {}
  return "";
}

async function runGh(args: string[]): Promise<string> {
  try {
    return await gitHubCapability.run(args);
  } catch (error: unknown) {
    const details = getGhErrorDetails(error);
    const msg = error instanceof Error ? error.message : String(error);
    const code = (error as unknown as { code?: unknown })?.code;
    const signal = (error as unknown as { signal?: unknown })?.signal;
    const stderr = (error as unknown as { stderr?: unknown })?.stderr;
    const stdout = (error as unknown as { stdout?: unknown })?.stdout;
    const stderrStr = stderr ? String(stderr).slice(0,1000) : "<no stderr>";
    const stdoutStr = stdout ? String(stdout).slice(0,500) : "<no stdout>";
    console.error(`[runGh] args=${args.join(" ")} code=${String(code)} signal=${String(signal)} msg=${msg} details=${details.slice(0,500)} stderr=${stderrStr} stdout=${stdoutStr}`);
    throw new Error(`gh ${args.join(" ")} failed: ${details}`);
  }
}

function parseOwnerRepo(): { owner: string; repo: string } | null {
  try {
    const out = execSync("git remote get-url origin", { encoding: "utf8", cwd: REPO_ROOT }).trim();
    // https://github.com/rhythmatician/voxygen-monorepo.git
    const m = out.match(/github\.com[:/]([^/]+)\/([^/.]+)/);
    if (m) return { owner: m[1], repo: m[2] };
  } catch {}
  return null;
}

interface RawIssue {
  number: number;
  title: string;
  body: string;
  labels: { name: string }[];
  assignees: { login: string }[];
  state: string;
}

async function fetchOpenImplementIssues(): Promise<IssueInput[]> {
  const rawJson = await runGh([
    "issue",
    "list",
    "--state",
    "open",
    "--label",
    "agent:implement",
    "--limit",
    "100",
    "--json",
    "number,title,body,labels,assignees,state",
  ]);
  let raw: RawIssue[] = [];
  try {
    raw = JSON.parse(rawJson);
  } catch {
    raw = [];
  }
  const ownerRepo = parseOwnerRepo();
  // Fetch native blocker counts in parallel
  const issues: IssueInput[] = await Promise.all(
    raw.map(async (r) => {
      let blockedByCount: number | undefined = undefined;
      if (ownerRepo) {
        const summary = await runGh([
          "api",
          `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${r.number}`,
          "--jq",
          ".issue_dependencies_summary.blocked_by",
        ]);
        const n = parseInt(summary.trim(), 10);
        if (!isNaN(n)) blockedByCount = n;
        else throw new Error(`blocked_by parse failed for #${r.number}: ${JSON.stringify(summary)}`);
        // No catch: API failure propagates — caller retries or marks unknown as ineligible
      } else {
        throw new Error(`cannot determine ownerRepo for blocked_by lookup #${r.number}`);
      }
      return {
        number: r.number,
        title: r.title,
        state: r.state.toLowerCase() as "open" | "closed",
        labels: r.labels.map((l) => l.name),
        assignees: r.assignees.map((a) => a.login),
        blockedByCount,
        body: r.body,
      };
    }),
  );
  return issues;
}

// Phase 0.5 claim - host-side, sequential, before createSandbox (single-host v0).
// Unified host claim: assignee + label + comment. Stale release is manual
// per #18 - do not auto-expire (gh issue edit --remove-label/--remove-assignee).
async function claimIssue(issue: IssueInput): Promise<boolean> {
  const id = String(issue.number);
  const branch = branchForIssue(issue.number);
  try {
    // Wayfinder-compatible claim: assignee + in-progress label, plus comment trace
    await runGh(["issue", "edit", id, "--add-assignee", "@me", "--add-label", "agent:in-progress"]);
    try {
      await runGh([
        "issue",
        "comment",
        id,
        "--body",
        `Sandcastle claiming #${id} for AFK implementation on \`${branch}\` -- \`${issue.title}\``,
      ]);
    } catch {
      // comment is best-effort
    }
    console.log(`  Claimed #${id} → ${branch}`);
    return true;
  } catch (error: unknown) {
    console.warn(`  Claim failed for #${id}: ${getErrorMessage(error)}`);
    return false;
  }
}

async function transitionToBlocked(issueId: string): Promise<boolean> {
  const removed = await safeRunGh(
    ["issue", "edit", issueId, "--remove-label", "agent:in-progress"],
    `Failed to remove agent:in-progress from #${issueId}`,
  );
  const added = await safeRunGh(
    ["issue", "edit", issueId, "--add-label", "agent:blocked"],
    `Failed to add agent:blocked to #${issueId}`,
  );
  return removed && added;
}

async function markBlocked(issueId: string, branch: string, reason: string): Promise<boolean> {
  const shortReason = reason.slice(0, REASON_TRUNCATE);
  const transitionOk = await transitionToBlocked(issueId);
  const commentOk = await safeRunGh([
    "issue",
    "comment",
    issueId,
    "--body",
    `Sandcastle failed on \`${branch}\` -- not merged. Preserved branch for inspection.\n\n**Reason:** ${shortReason}\n\nBranch: \`${branch}\`\n\nTo retry: remove \`agent:blocked\`, ensure \`agent:implement\` is still present, and re-run factory.`,
  ]);
  const ok = transitionOk && commentOk;
  if (!ok) {
    // GitHub unavailable during failure cleanup — do not falsely report that the branch was marked.
    // Preserve recoverable local state so next reconciliation can truthfully report status after connectivity returns.
    try {
      const recoveryDir = path.join(REPO_ROOT, ".sandcastle", "recovery");
      fs.mkdirSync(recoveryDir, { recursive: true });
      const recoveryPath = path.join(recoveryDir, `${issueId}-${branch.replace(/[^a-zA-Z0-9-]/g, "-")}.json`);
      fs.writeFileSync(recoveryPath, JSON.stringify({ issueId, branch, reason: shortReason, at: new Date().toISOString(), transitionOk, commentOk, githubAvailable: false }, null, 2));
      console.warn(`  [recovery] Preserved local state at ${recoveryPath} — GitHub mutations failed (transitionOk=${transitionOk} commentOk=${commentOk}), will reconcile truthfully after connectivity returns. Did NOT report as "marked agent:blocked".`);
    } catch (e) {
      console.warn(`  [recovery] Failed to preserve local state for #${issueId}: ${getErrorMessage(e)}`);
    }
  }
  return ok;
}

async function markFactoryError(issueId: string, branch: string, reason: string): Promise<boolean> {
  const shortReason = reason.slice(0, REASON_TRUNCATE);
  const removed = await safeRunGh(
    ["issue", "edit", issueId, "--remove-label", "agent:in-progress"],
    `Failed to remove agent:in-progress from #${issueId}`,
  );
  const commentOk = await safeRunGh([
    "issue",
    "comment",
    issueId,
    "--body",
    `Sandcastle factory infrastructure produced an unrecoverable verdict contract failure on \`${branch}\` — preserved branch for inspection.\n\n**Reason:** ${shortReason}\n\nBranch: \`${branch}\`\n\nTo retry this issue, fix infra/agent contract wiring and re-run factory once valid review text can be emitted.`,
  ]);
  return removed && commentOk;
}

async function markIntegrated(issueId: string, branch: string): Promise<void> {
  // TODO(factory-v1): Wayfinder close ownership -- host closes ordinary impl
  // only; Wayfinder skill will own Wayfinder ticket close. See plan-prompt.
  for (const label of ["agent:in-progress", "agent:implement", "agent:blocked"]) {
    await safeRunGh(
      ["issue", "edit", issueId, "--remove-label", label],
      `Failed to remove ${label} from integrated issue #${issueId}`,
    );
  }
  // Close with audit comment
  try {
    await runGh([
      "issue",
      "close",
      issueId,
      "--comment",
      `Completed by Sandcastle -- branch \`${branch}\` merged and integrated. Auto-merged to main after verification.`,
    ]);
  } catch {
    // fallback: comment then close
    try {
      await runGh(["issue", "comment", issueId, "--body", `Completed by Sandcastle -- branch \`${branch}\` integrated.`]);
      await runGh(["issue", "close", issueId]);
    } catch {}
  }
}

function formatVerdictForRetry(verdict: ReviewVerdict | null, fallback: string): string {
  if (!verdict) return fallback.slice(0, REASON_TRUNCATE);
  const findings = verdict.findings.map(f => `[${f.severity}] ${f.message}`).join("\n");
  const criteria = verdict.acceptanceCriteriaMet.filter(c => !c.met).map(c => `- [ ] ${c.criterion}${c.evidence ? " - " + c.evidence : ""}`).join("\n");
  return `Review rejected: ${(verdict.summary ?? '').slice(0, REASON_TRUNCATE)}\n${findings ? "Findings:\n" + findings.slice(0, REASON_TRUNCATE) + "\n" : ""}${criteria ? "Unmet criteria:\n" + criteria.slice(0, REASON_TRUNCATE) : ""}`.trim();
}

async function fetchIssueBody(issueId: string): Promise<string> {
  // Fresh fetch every time - no caching. Host-side so it is authoritative. Fail-closed.
  // Throw on failure so caller can mark blocked — empty string would hide review contract.
  // Retry with backoff for transient GH rate limit / network hiccups (seen after docker GH calls).
  let lastError: unknown = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const body = await runGh(["issue", "view", issueId, "--json", "body", "--jq", ".body"]);
      return body;
    } catch (e) {
      lastError = e;
      if (attempt < 2) {
        const backoff = 1000 * Math.pow(2, attempt);
        console.warn(`  fetchIssueBody #${issueId} attempt ${attempt + 1}/3 failed: ${getErrorMessage(e)} — retrying in ${backoff}ms`);
        await new Promise((r) => setTimeout(r, backoff));
      }
    }
  }
  throw lastError;
}

async function markReviewRejected(
  issueId: string,
  branch: string,
  verdict: ReviewVerdict | null,
  fallbackReason: string,
): Promise<void> {
  const findings = verdict
    ? verdict.findings.map((f) => `- [${f.severity}] ${f.message}`).join("\n")
    : "";
  const criteria = verdict
    ? verdict.acceptanceCriteriaMet.map((c) => `- [${c.met ? "x" : " "}] ${c.criterion}${c.evidence ? ` - ${c.evidence}` : ""}`).join("\n")
    : "";
  const summary = verdict?.summary ?? fallbackReason;
  const body = `Sandcastle review rejected \`${branch}\` - not merged. Preserved branch for inspection.

**Verdict: approved=false**
**Summary:** ${summary.slice(0, REASON_TRUNCATE)}

${findings ? `**Findings:**\n${findings.slice(0, REASON_TRUNCATE)}\n` : ""}${criteria ? `**Acceptance criteria:**\n${criteria.slice(0, REASON_TRUNCATE)}\n` : ""}**Full verdict:** \`\`\`json
${JSON.stringify(verdict ?? { approved: false, reason: fallbackReason }, null, 2).slice(0, VERDICT_JSON_TRUNCATE)}
\`\`\`

Branch: \`${branch}\`

To retry: fix implementation to address findings, ensure \`agent:blocked\` is removed, \`agent:implement\` remains, and re-run factory.`;
  await transitionToBlocked(issueId);
  await safeRunGh(["issue", "comment", issueId, "--body", body]);
}

// ---------------------------------------------------------------------------
// Lifecycle reconciliation — restart-safe, before looking for new work
// Durable batch-PR correlation: batch PR is from host branch (not worker branch).
// Records exact PR number in issue comments + PR body Closes #N.
// 3-state: found / definitively absent / unknown (unknown never mutates).
// ---------------------------------------------------------------------------
async function reconcileInProgressIssues(): Promise<void> {
  console.log("\n=== Reconciliation: checking Sandcastle-owned agent:in-progress issues ===\n");
  // Helper: classify gh error as definitively not-found vs transient unknown
  function isNotFoundError(msg: string): boolean {
    const m = msg.toLowerCase();
    return m.includes("no pull requests found") || m.includes("could not find") || m.includes("not found") || m.includes("404");
  }
  // 1. Open in-progress issues
  let inProgress: IssueInput[] = [];
  try {
    const rawJson = await runGh([
      "issue", "list",
      "--state", "open",
      "--label", "agent:in-progress",
      "--limit", "100",
      "--json", "number,title,body,labels,assignees,state",
    ]);
    const raw: any[] = JSON.parse(rawJson);
    inProgress = raw.map(r => ({
      number: r.number,
      title: r.title,
      state: r.state.toLowerCase() as "open" | "closed",
      labels: r.labels.map((l: any) => l.name),
      assignees: r.assignees.map((a: any) => a.login),
      blockedByCount: 0,
      body: r.body,
    }));
  } catch (e) {
    console.warn(`  Reconciliation: failed to list agent:in-progress issues: ${getErrorMessage(e)} — fail-closed, will retry next startup`);
    return;
  }
  if (inProgress.length === 0) {
    console.log("  No Sandcastle-owned open in-progress issues to reconcile.");
  } else {
  for (const issue of inProgress) {
    const id = String(issue.number);
    const branch = branchForIssue(issue.number);
    // Try durable correlation: batch PR number from issue comments
    let batchPrNumber: string | null = null;
    let commentsUnknown = false;
    try {
      const commentsJson = await runGh(["issue", "view", id, "--json", "comments", "--jq", ".comments[].body"]);
      const match = commentsJson.match(/Batch PR #(\d+)/);
      if(match) batchPrNumber = match[1];
    } catch (e) {
      const msg = getErrorMessage(e);
      if(isNotFoundError(msg)) {
        // No comments or issue not found — definitively no batch PR comment
      } else {
        console.warn(`  #${id} → failed to read comments: ${msg} — unknown, skipping mutate`);
        commentsUnknown = true;
      }
    }
    // Fallback: search open PRs whose body contains Closes #id (if no comment)
    let prListUnknown = false;
    if(!batchPrNumber && !commentsUnknown){
      try {
        const prListJson = await runGh(["pr", "list", "--state", "open", "--limit", "100", "--json", "number,body"]);
        const prs: any[] = JSON.parse(prListJson);
        for(const pr of prs){
          if(pr.body && pr.body.includes(`Closes #${id}`)){
            batchPrNumber = String(pr.number);
            break;
          }
        }
      } catch (e){
        console.warn(`  #${id} → failed to list PRs: ${getErrorMessage(e)} — unknown`);
        prListUnknown = true;
      }
    }
    // If we have a batch PR number, query it directly (3-state)
    if(batchPrNumber){
      let prState: string | null = null;
      let prMerged = false;
      let prFound = false;
      let prUnknown = false;
      try {
        const prJson = await runGh(["pr", "view", batchPrNumber, "--json", "state,mergedAt,number", "--jq", "{state: .state, mergedAt: .mergedAt}"]);
        const pr = JSON.parse(prJson);
        prState = pr.state;
        prMerged = !!pr.mergedAt;
        prFound = true;
      } catch (e){
        const msg = getErrorMessage(e);
        if(isNotFoundError(msg)){
          prFound = false;
        } else {
          console.warn(`  #${id} batch PR #${batchPrNumber} lookup failed: ${msg} — unknown, skipping mutate`);
          prUnknown = true;
        }
      }
      if(prUnknown) continue;
      if(prMerged){
        console.log(`  #${id} (${branch}) → batch PR #${batchPrNumber} merged, finalizing via markIntegrated`);
        await markIntegrated(id, branch);
        continue;
      }
      if(prFound && prState === "OPEN"){
        console.log(`  #${id} (${branch}) → batch PR #${batchPrNumber} OPEN, CI/Merge Oracle pending — recognizing`);
        continue;
      }
      if(!prFound){
        console.log(`  #${id} (${branch}) → batch PR #${batchPrNumber} not found (was closed without merge?) — marking blocked`);
        await markBlocked(id, branch, `Batch PR #${batchPrNumber} for ${branch} not found — may have been closed without merge. Branch preserved.`);
        continue;
      }
      console.log(`  #${id} (${branch}) → batch PR #${batchPrNumber} state ${prState} — leaving in-progress`);
      continue;
    }
    // No durable batch PR found — determine definitively absent vs unknown
    if(commentsUnknown || prListUnknown){
      console.log(`  #${id} (${branch}) → no batch PR correlation yet, but lookup was unknown — leaving in-progress`);
      continue;
    }
    // Definitively no batch PR recorded — check worker branch existence to decide pre-PR vs stale
    let branchExists = false;
    try {
      const out = execSync(`git branch --list "${branch}"`, { encoding: "utf8" }).trim();
      if (out) branchExists = true;
      else {
        const remote = await runGh(["api", `repos/${parseOwnerRepo()?.owner}/${parseOwnerRepo()?.repo}/git/refs/heads/${branch}`, "--jq", ".ref"]);
        if (remote && remote.includes(branch)) branchExists = true;
      }
    } catch { branchExists = false; }
    if(branchExists){
      // Share single write-once provenance state machine with claim path (prepareIssueBranch)
      // Never overwrite provenance — fail closed on legacy contaminated branches.
      let reconcileBase = "";
      let reconcileCallerBranch = "";
      let reconcileCallerSha = "";
      try {
        reconcileBase = execSync('git rev-parse origin/main', {encoding:'utf8', cwd: REPO_ROOT}).trim();
        reconcileCallerBranch = execSync('git branch --show-current', {encoding:'utf8', cwd: REPO_ROOT}).trim();
        reconcileCallerSha = execSync('git rev-parse HEAD', {encoding:'utf8', cwd: REPO_ROOT}).trim();
      } catch {
        try { reconcileBase = execSync('git rev-parse origin/main', {encoding:'utf8', cwd: REPO_ROOT}).trim(); } catch { reconcileBase = execSync('git rev-parse HEAD', {encoding:'utf8', cwd: REPO_ROOT}).trim(); }
        reconcileCallerBranch = "reconcile";
        reconcileCallerSha = reconcileBase;
      }
      const prep = branchHelpers.prepareIssueBranch(REPO_ROOT, branch, reconcileBase, reconcileCallerBranch, reconcileCallerSha, id);
      if (!prep.ok) {
        if (prep.action === 'blocked') {
          console.warn(`  #${id} (${branch}) → ${prep.reason} — legacy/contaminated branch, fail closed: preserving/blocking`);
          await markBlocked(id, branch, `${prep.reason} — preserved for inspection. Was not created from frozen factory base. To retry: delete branch ${branch} and remove agent:blocked, re-run factory from clean base.`);
          continue;
        }
        console.error(`  #${id} (${branch}) → prepare error (${prep.action}): ${prep.reason} — blocking`);
        await markBlocked(id, branch, prep.reason);
        continue;
      }
      if (prep.action === 'recreated') {
        console.log(`  #${id} (${branch}) → ${prep.reason} — cleaned empty stale branch, allowing retry`);
        await safeRunGh(["issue", "edit", id, "--remove-label", "agent:in-progress"], `Failed to cleanup stale in-progress for #${id}`);
        await safeRunGh(["issue", "edit", id, "--remove-label", "agent:blocked"], `Failed to cleanup stale blocked for #${id}`);
        continue;
      }
      // prep.ok with reused/created — provenance valid, now distinguish empty vs crash-with-work
      console.log(`  #${id} (${branch}) → ${prep.reason}`);
      const hasCommits = (() => {
        try {
          return branchHelpers.hasCommitsAhead(REPO_ROOT, "origin/main", branch);
        } catch { return false; }
      })();
      if (!hasCommits) {
        console.log(`  #${id} (${branch}) → branch exists but empty (no commits ahead of origin/main) — cleaning stale claim, will retry`);
        try { require('child_process').execSync(`git branch -D ${branch}`, { encoding: "utf8", cwd: REPO_ROOT }); } catch {}
        try { await runGh(["api", `repos/${parseOwnerRepo()?.owner}/${parseOwnerRepo()?.repo}/git/refs/heads/${branch}`, "--method", "DELETE"]); } catch {}
        try { const p = require('path').join(REPO_ROOT, ".sandcastle", "provenance", `${branch.replace(/[^a-zA-Z0-9-]/g, "-")}.json`); require('fs').unlinkSync(p); } catch {}
        await safeRunGh(["issue", "edit", id, "--remove-label", "agent:in-progress"], `Failed to cleanup stale in-progress for #${id}`);
        await safeRunGh(["issue", "edit", id, "--remove-label", "agent:blocked"], `Failed to cleanup stale blocked for #${id}`);
        continue;
      }
      console.log(`  #${id} (${branch}) → branch exists but no batch PR yet (crash before PR creation) — marking blocked`);
      await markBlocked(id, branch, "Sandcastle claimed but no batch PR found on restart — previous process may have crashed before PR creation. Branch preserved. To retry: remove agent:blocked, keep agent:implement, re-run.");
      continue;
    } else {
      console.log(`  #${id} (${branch}) → no branch or batch PR — stale claim after crash, marking blocked`);
      await markBlocked(id, branch, "Sandcastle claimed but no branch/PR found on restart — stale claim, likely crash after claim. To retry: remove agent:blocked, keep agent:implement, re-run.");
      continue;
    }
  }
  }
  // 2. Closed in-progress issues — cleanup after GitHub Closes #N auto-close
  try {
    const closedJson = await runGh([
      "issue", "list",
      "--state", "closed",
      "--label", "agent:in-progress",
      "--limit", "100",
      "--json", "number,title",
    ]);
    const closed: any[] = JSON.parse(closedJson);
    for(const r of closed){
      const id = String(r.number);
      console.log(`  closed #${id} still has agent:in-progress — cleaning up stale claim label`);
      await safeRunGh(["issue", "edit", id, "--remove-label", "agent:in-progress"], `Failed to cleanup closed #${id}`);
      // Also remove agent:implement/blocked if present — issue is closed via Closes #N, authoritative
      for(const label of ["agent:implement","agent:blocked"]){
        try{ await runGh(["issue", "edit", id, "--remove-label", label]); }catch{}
      }
    }
    if(closed.length===0) console.log("  No closed in-progress issues to clean.");
  } catch (e){
    console.warn(`  Reconciliation: failed to list closed in-progress issues: ${getErrorMessage(e)}`);
  }
  console.log("=== Reconciliation complete ===\n");
}

import { doctorWorktreePath, cleanupDoctorBranchAndWorktree, reconcileStaleDoctorResources } from "./doctor-helpers.mts";

// ---------------------------------------------------------------------------
// Factory doctor — fail-closed preflight before any claim
// ---------------------------------------------------------------------------
async function runDoctor(): Promise<boolean> {
  console.log("\n=== Factory Doctor (preflight — proves real worker boundary) ===\n");
  // 0. Reconcile stale doctor-* worktrees left by previous crash/kill before creating another Doctor sandbox
  // Strictly scoped to doctor-*; never sweep arbitrary human worktrees. Idempotent.
  // FAIL-CLOSED: if reconciliation throws, Doctor FAILs — never WARN-and-continue to a cached PASS.
  try {
    await reconcileStaleDoctorResources(REPO_ROOT);
  } catch (e) {
    console.error(`  FAIL: Doctor stale reconciliation failed: ${getErrorMessage(e)} — fail-closed`);
    return false;
  }
  // MANDATORY ASSERT CLEAN before any cache-hit return — exact class of bug we eliminate:
  // stale worktree/dir/branch must not survive to a cached PASS, and inspection failures are FAIL not "nothing found".
  try {
    const { assertNoStaleDoctorResources: assertStartup } = await import("./doctor-helpers.mts");
    const { ok, leftover } = assertStartup(REPO_ROOT);
    if (!ok) {
      console.error(`  FAIL: Doctor startup postcondition — stale doctor-* resources remain: ${leftover.join(", ")} — fail-closed`);
      return false;
    }
    console.log("  Doctor startup postcondition: no doctor-* leftover ✓");
  } catch (e) {
    console.error(`  FAIL: Doctor startup inspection failed: ${getErrorMessage(e)} — fail-closed`);
    return false;
  }
  // 1. Control-plane SHA + image identity (cache key)
  let sha = "";
  let imageId = "";
  let imageDigest = "";
  try {
    sha = execSync('git rev-parse HEAD', {encoding:'utf8'}).trim();
    console.log(`  control-plane HEAD: ${sha}`);
  } catch { console.error('  FAIL: cannot determine HEAD SHA'); return false; }
  try {
    imageId = execSync('docker images sandcastle --format "{{.ID}}" 2>/dev/null | head -1', {encoding:'utf8'}).trim();
    imageDigest = execSync('docker inspect --format "{{.Id}}" sandcastle 2>/dev/null | head -1', {encoding:'utf8'}).trim();
    if(imageId) console.log(`  docker sandcastle ID: ${imageId}`);
    if(imageDigest) console.log(`  docker sandcastle digest: ${imageDigest}`);
    if(!imageId && !imageDigest) console.warn('  WARN: docker sandcastle image not found locally — will build on demand');
  } catch {}
  const cachePath = '.sandcastle/.doctor-cache.json';
  try {
    const cached = JSON.parse(fs.readFileSync(cachePath,'utf8'));
    if(cached.sha === sha && (cached.imageId === imageId || cached.imageDigest === imageDigest) && cached.passed) {
      // Even on cache HIT, verify runtime artifact provenance — source SHA + image is not enough,
      // dist must expose required API symbols.
      try {
        const distPath = resolveSandcastleRuntimeDistPath();
        if (!distPath) {
          console.warn(`  Doctor cache HIT but runtime dist unavailable for package @ai-hero/sandcastle, re-proving.`);
          return false;
        }
        const runtimeVerification = verifySandcastleRuntimeDist(distPath);
        if (!runtimeVerification.ok) {
          console.warn(`  Doctor cache HIT but runtime dist at ${distPath} missing symbols (${runtimeVerification.missing.join(", ")}) — re-proving.`);
          return false;
        }
        // Also check cached dist mtime/hash if present — if dist changed, re-prove
        let distMtime = "";
        try { distMtime = fs.statSync(distPath).mtime.toISOString(); } catch {}
        if (cached.distMtime && cached.distMtime !== distMtime) {
          console.warn(`  Doctor cache HIT but dist mtime changed (${cached.distMtime} → ${distMtime}), re-proving.`);
        } else {
          console.log(`  Doctor cache HIT — SHA ${sha.slice(0,7)} + image ${(imageId||imageDigest).slice(0,12)} + dist ${distPath.slice(-30)} already certified`);
          console.log('=== Doctor PASS (cached) ===\n');
          return true;
        }
      } catch (e) {
        console.warn(`  Doctor cache HIT but dist verification failed: ${getErrorMessage(e)}, re-proving.`);
      }
    }
  } catch {}
  // 2. Static seams — fail fast without sandbox
  try {
    if(!fs.existsSync('.ci/voxy-artifact.json')) { console.error('  FAIL: .ci/voxy-artifact.json missing'); return false; }
    console.log('  voxy-artifact.json: present');
  } catch {}
  try {
    const dockerfile = fs.readFileSync('.sandcastle/Dockerfile','utf8');
    if(dockerfile.includes('graphifyy')) { console.error('  FAIL: Dockerfile still contains graphifyy typo'); return false; }
    console.log('  Dockerfile graphify check: OK');
  } catch {}
  // 2b. Runtime artifact provenance — source SHA is not enough, dist must expose required symbols.
  // Doctor must fail if imported dist does not correspond to intended Sandcastle revision.
  try {
    const distPath = resolveSandcastleRuntimeDistPath();
    if (!distPath || !fs.existsSync(distPath)) {
      console.error(`  FAIL: Sandcastle runtime dist not found (package main unresolved) — expected built artifact from ${EXPECTED_SANDCASTLE_SOURCE_PREFIX}`);
      return false;
    }
    const runtimeVerification = verifySandcastleRuntimeDist(distPath);
    if (!runtimeVerification.ok) {
      const missing = missingSandcastleRuntimeSymbols(distPath);
      console.error(`  FAIL: Sandcastle runtime dist at ${distPath} missing required symbols (${missing.join(", ")}) — expected runtime from ${EXPECTED_SANDCASTLE_SOURCE_PREFIX}.`);
      if (missing.length > 0) {
        console.error(`  Missing symbols: ${missing.join(", ")}`);
      }
      return false;
    }
    // Also verify source HEAD is the intended revision (not just branch checkout)
    let sandcastleHead = "";
    try { sandcastleHead = execSync('git -C ../../sandcastle rev-parse HEAD', {encoding:'utf8'}).trim(); } catch {}
    if (sandcastleHead && !isExpectedSandcastleSourceHead(sandcastleHead)) {
      console.error(`  FAIL: Sandcastle HEAD is ${sandcastleHead.slice(0,7)} not ${EXPECTED_SANDCASTLE_SOURCE_PREFIX} — refusing to run factory on unexpected Sandcastle revision.`);
      return false;
    }
    console.log(`  sandcastle runtime dist: ${distPath} — required API symbols ✓ (source HEAD ${sandcastleHead.slice(0,7) || "unknown"}, dist mtime ${fs.statSync(distPath).mtime.toISOString()})`);
  } catch (e) {
    console.error(`  FAIL: cannot verify Sandcastle runtime dist: ${getErrorMessage(e)}`);
    return false;
  }
  // 3. Prove actual worker boundary: create real docker sandbox and run bootstrap hooks (no LLM)
  console.log('  Proving worker sandbox — creating docker sandbox with onSandboxReady hooks...');
  let sandbox: Awaited<ReturnType<typeof sandcastle.createSandbox>> | null = null;
  const doctorBranch = `doctor-${Date.now()}`;
  let doctorSuccess = true;
  try {
    sandbox = await sandcastle.createSandbox({
      branch: doctorBranch,
      sandbox: docker({ env: WORKER_SANDBOX_ENV }),
      hooks,
      timeouts: { worktreeMs: 120_000 },
    });
    console.log(`  sandbox created on ${doctorBranch}, running bootstrap verification...`);
    // Run the same commands that the worker will rely on, inside the sandbox
    const checks: Array<{cmd: string, label: string, mustContain?: string}> = [
      {cmd: 'bash -lc "java -version 2>&1" | head -5', label: 'java 21', mustContain: '21'},
      {cmd: 'bash -lc "./java/gradlew --version 2>&1" | tail -10', label: 'gradle'},
      {cmd: 'bash -lc "bash .ci/install-voxy.sh install 2>&1" | tail -20', label: 'voxy install'},
      {cmd: 'bash -lc "npm install 2>&1" | tail -10', label: 'npm install'},
    ];
    // Debug PATH inside sandbox
    try {
      const dbg = await (sandbox as any).exec('bash -lc "echo PATH=\$PATH; echo JAVA_HOME=\$JAVA_HOME; ls -l \$JAVA_HOME/bin/java 2>&1 | head -3; which java 2>&1 | head -3"');
      console.log(`  [doctor debug] ${(dbg.stdout+dbg.stderr).slice(0,400)}`);
    } catch {}
    for(const c of checks){
      const res = await (sandbox as any).exec(c.cmd);
      const out = (res.stdout + res.stderr).trim();
      console.log(`  [doctor] ${c.label}: exit ${res.exitCode} — ${out.slice(0,200)}`);
      if(res.exitCode !== 0){
        console.error(`  FAIL: ${c.label} failed inside sandbox (exit ${res.exitCode})`);
        doctorSuccess = false;
        break;
      }
      if(c.mustContain && !out.includes(c.mustContain)){
        console.error(`  FAIL: ${c.label} output missing '${c.mustContain}' — got: ${out.slice(0,300)}`);
        doctorSuccess = false;
        break;
      }
    }
    if (doctorSuccess) {
      console.log('  worker sandbox bootstrap: all hooks passed');
    }
  } catch(e){
    console.error(`  FAIL: doctor sandbox creation or exec failed: ${getErrorMessage(e)}`);
    doctorSuccess = false;
  } finally {
    // Wrap Doctor sandbox/worktree creation in try/finally — idempotent cleanup of ephemeral control-plane state
    // Must never survive a successful Doctor run; startup reconciliation also cleans stale left by crash/kill.
    // Scope strictly to Sandcastle-owned doctor-*; never sweep arbitrary human worktrees.
    // Restore stable cwd BEFORE cleanup deletes the Doctor worktree — otherwise host cwd becomes
    // a deleted directory and all subsequent host git/fs inspection fails with
    // "Unable to read current working directory".
    try {
      process.chdir(REPO_ROOT);
    } catch (e) {
      console.error(`  FAIL: Doctor cwd restore to REPO_ROOT failed: ${getErrorMessage(e)} — fail-closed`);
      doctorSuccess = false;
    }
    if (sandbox) { try { await sandbox.close(); } catch {} }
    // Explicit git worktree remove --force before branch delete; prune alone is insufficient when directory still exists
    cleanupDoctorBranchAndWorktree(REPO_ROOT, doctorBranch);
    // Ensure we leave the process at REPO_ROOT even if close/cleanup chdir'd
    try { process.chdir(REPO_ROOT); } catch {}
  }
  // Strict postcondition: after cleanup, no doctor-* worktree / .sandcastle/worktrees/doctor-* dir / local branch may remain.
  // If any leftover exists, Doctor FAILs and must not write PASS cache — repo must be in same control-plane state as found.
  // Second mandatory ASSERT CLEAN after fresh sandbox path (mirrors startup fail-closed gate).
  try {
    const { assertNoStaleDoctorResources } = await import("./doctor-helpers.mts");
    const { ok, leftover } = assertNoStaleDoctorResources(REPO_ROOT);
    if (!ok) {
      console.error(`  FAIL: Doctor ephemeral cleanup incomplete — leftover: ${leftover.join(", ")} — fail-closed`);
      doctorSuccess = false;
    } else {
      console.log("  Doctor ephemeral cleanup postcondition: no doctor-* leftover ✓");
    }
  } catch (e) {
    console.error(`  FAIL: Doctor postcondition inspection failed: ${getErrorMessage(e)} — fail-closed`);
    doctorSuccess = false;
  }
  if (!doctorSuccess) return false;
  // 4. Cache PASS against SHA + image identity + runtime dist provenance (source SHA alone is not enough)
  try {
    let distMtime = "";
    let distPathForCache = "";
    try {
      const { createRequire } = await import('node:module');
      const require = createRequire(import.meta.url);
      distPathForCache = require.resolve('@ai-hero/sandcastle');
      distMtime = fs.statSync(distPathForCache).mtime.toISOString();
    } catch {}
    fs.writeFileSync(cachePath, JSON.stringify({sha, imageId, imageDigest, distMtime, distPath: distPathForCache, passed:true, at: new Date().toISOString()}, null, 2));
    console.log(`  Doctor cache written: ${cachePath} (dist ${distPathForCache.slice(-30)} mtime ${distMtime})`);
  } catch {}
  console.log('=== Doctor PASS (real sandbox) ===\n');
  return true;
}

// ---------------------------------------------------------------------------
// Main loop
// ---------------------------------------------------------------------------
for (let iteration = 1; iteration <= ITERATION_CONTROL.maxIterations; iteration++) {
  // Reconcile + doctor before looking for new work — guarantees liveness, no indefinite claim
  if (iteration === 1) {
    const doctorOk = await runDoctor();
    if (!doctorOk) {
      console.error("Doctor FAIL — factory unhealthy, not claiming new work. Fix Dockerfile/voxy-artifact/java before retry.");
      // Still reconcile stale claims so they don't stay indefinite
    }
    await reconcileInProgressIssues();
    if (!doctorOk) {
      console.log("Doctor failed — exiting before claiming new work (reconciliation already done).");
      break;
    }
  }
  console.log(`\n=== Iteration ${iteration}/${MAX_ITERATIONS} ===\n`);

  // ----- Phase 0: Deterministic eligibility (host-side, no LLM) -----
  let allCandidates: IssueInput[] = [];
  // Retry fetch with backoff — transient gh/API failures should not kill the whole batch
  let fetchAttempts = 0;
  const maxFetchAttempts = 3;
  while (true) {
    try {
      allCandidates = await fetchOpenImplementIssues();
      break;
    } catch (error: unknown) {
      fetchAttempts++;
      const msg = getErrorMessage(error);
      console.error(`Failed to fetch issues (attempt ${fetchAttempts}/${maxFetchAttempts}): ${msg}`);
      if (fetchAttempts >= maxFetchAttempts) {
        console.error('Max fetch retries reached — skipping iteration, will retry next iteration');
        await new Promise(r => setTimeout(r, 5000));
        break;
      }
      const backoff = Math.pow(2, fetchAttempts) * 1000;
      console.log(`Retrying in ${backoff}ms...`);
      await new Promise(r => setTimeout(r, backoff));
    }
  }
  if (allCandidates.length === 0 && fetchAttempts >= maxFetchAttempts) {
    console.log('No candidates fetched after retries — continuing to next iteration');
    continue;
  }

  console.log(`Fetched ${allCandidates.length} open issue(s) with agent:implement`);
  for (const c of allCandidates) {
    const r = isEligible(c);
    if (!r.eligible) {
      console.log(`  - #${c.number} "${c.title}" → SKIP (${r.reason})`);
    } else {
      console.log(`  - #${c.number} "${c.title}" → ELIGIBLE`);
    }
  }

  const eligible = allCandidates.filter((i) => isEligible(i).eligible);

  if (eligible.length === 0) {
    console.log("No eligible issues to work on. Exiting.");
    break;
  }

  const iterationPlan = planIssuesForIteration(eligible, {
    requestedIssueNumber: ITERATION_CONTROL.requestedIssueNumber,
  });

  let plannedIssues: PlannedIssue[] = [];
  if (iterationPlan.mode === "qualified") {
    plannedIssues = iterationPlan.plannedIssues;
    console.log(`Qualification mode: explicitly selected issue #${plannedIssues[0]!.id} only.`);
  } else if (iterationPlan.mode === "qualify-unsupported") {
    const requestedIssueNumber = ITERATION_CONTROL.requestedIssueNumber;
    console.log(`Qualification mode requested issue #${requestedIssueNumber}, but it is not eligible in this iteration.`);
    continue;
  } else if (iterationPlan.mode === "single-eligible") {
    plannedIssues = iterationPlan.plannedIssues;
    console.log(`Single eligible issue -- skipping LLM planner, direct dispatch #${plannedIssues[0]!.id}`);
  } else if (iterationPlan.mode === "planner-required") {
    // ----- Phase 1: Overlap-aware planning (LLM may serialize) -----
    // Planner receives only eligible issues; it must return a subset.
    const issuesJson = JSON.stringify(
      eligible.map((i) => ({
        number: i.number,
        title: i.title,
        labels: i.labels,
        branch: branchForIssue(i.number),
      })),
      null,
      2,
    );
    // Use Output.string (not Output.object) so we get the raw planner stream even if it contains
    // fences or surrounding reasoning — then parse via testable helper. This fixes the false
    // rejection where StructuredOutputError.rawMatched already failed JSON.parse and re-parsing it
    // cannot bypass; we need the independently captured valid planner text stream.
    try {
      const planRun = await sandcastle.run({
        hooks,
        sandbox: docker({ env: WORKER_SANDBOX_ENV }),
        name: "planner",
        maxIterations: 1,
        agent: sandcastle.muse("muse-spark-1.2-contributor"),
        promptFile: "./.sandcastle/plan-prompt.md",
        promptArgs: { ISSUES_JSON: issuesJson },
        output: sandcastle.Output.string({ tag: "plan" }),
      });
      const rawPlanString: string = (planRun.output as unknown as string) ?? "";
      // Wrap with tag so helper can extract last <plan> — if run already returned inner string, re-wrap
      const planStdout = rawPlanString.includes("<plan>") ? rawPlanString : `<plan>${rawPlanString}</plan>`;
      plannedIssues = parsePlannerOutput(planStdout, eligible);
      if (plannedIssues.length === 0) {
        console.log("Planner advised to defer all eligible issues due to overlap risk. Will retry next iteration.");
        break;
      }
      console.log(`Planner selected ${plannedIssues.length}/${eligible.length} issue(s) to run now:`);
      for (const p of plannedIssues) console.log(`  ${p.id}: ${p.title} → ${p.branch}`);
    } catch (error: unknown) {
      const errorMsg = getErrorMessage(error);
      // If Output.string threw (missing tag), try to recover from rawMatched + full stdout if available
      const rawMatched = (error as unknown as { rawMatched?: string })?.rawMatched;
      const candidateStdouts: string[] = [];
      if (typeof rawMatched === "string" && rawMatched) candidateStdouts.push(`<plan>${rawMatched}</plan>`);
      // StructuredOutputError may have sessionId/sessionFilePath pointing to full output — try to read if present
      const sessionFile = (error as unknown as { sessionFilePath?: string; sessionId?: string })?.sessionFilePath;
      if (typeof sessionFile === "string" && sessionFile) {
        try {
          const sessContent = fs.readFileSync(sessionFile, "utf8");
          if (sessContent.includes("<plan>")) candidateStdouts.push(sessContent);
        } catch {}
      }
      let bypassSucceeded = false;
      for (const cand of candidateStdouts) {
        try {
          const filtered = parsePlannerOutput(cand, eligible);
          if (filtered.length > 0) {
            plannedIssues = filtered;
            console.log(`Planner bypass succeeded via full stream recovery: selected ${plannedIssues.length}/${eligible.length} issue(s)`);
            for (const p of plannedIssues) console.log(`  ${p.id}: ${p.title} → ${p.branch}`);
            bypassSucceeded = true;
            break;
          }
        } catch {}
      }
      if (bypassSucceeded) {
        // recovered
      } else {
        const rawDump = (rawMatched ?? errorMsg).slice(0, 12000);
        try {
          fs.mkdirSync(path.join(REPO_ROOT, ".sandcastle", "logs"), { recursive: true });
          const logPath = path.join(REPO_ROOT, ".sandcastle", "logs", `planner-fail-${Date.now()}.json`);
          fs.writeFileSync(logPath, JSON.stringify({ at: new Date().toISOString(), error: errorMsg, raw: rawDump, rawMatched, eligible: eligible.map((e) => e.number) }, null, 2));
          console.error(`  Preserved failing raw planner output to ${logPath} (ignored, not fixtures)`);
        } catch (preserveErr) {
          console.warn(`  Failed to preserve planner failure log: ${getErrorMessage(preserveErr)}`);
        }
        // Deterministic safety does not depend on LLM perfect syntax.
        // Never fallback-to-all; fallback to single serial progress.
        // LLM may improve parallelism but must not block basic serial dispatch.
        const fallback = fallbackToSingle(eligible);
        if (fallback.length === 0) {
          console.error(`Planner failed: ${errorMsg} -- no fallback eligible, aborting iteration.`);
          break;
        }
        plannedIssues = fallback;
        console.warn(`Planner failed: ${errorMsg} -- fallback to deterministic single #${fallback[0].id} (fail-closed single, not all, not abort)`);
      }
    }
  }

  // ----- Freeze explicit factory base before claiming work (isolation invariant) -----
  // Caller checkout is not part of Sandcastle's data plane — irrelevant whether launching from main, feature/foo, or PR branch.
  let factoryBaseSha = "";
  let callerBranch = "";
  let callerSha = "";
  let callerStatusBefore: string | null = null;
  try {
    // Fetch with captured stderr/stdout — previous stdio:'ignore' hid the diagnostic (see #151/#152 preflight).
    // Surface exit code, stdout, stderr so host fetch failures are actionable. This is a safety boundary.
    let fetchStdout = "";
    let fetchStderr = "";
    try {
      fetchStdout = execSync('git fetch origin main --verbose', {encoding:'utf8', stdio:'pipe'});
      console.log(`[factory-base] git fetch origin main ok: ${fetchStdout.slice(0,500).replace(/\n/g, ' ')}`);
    } catch (fetchErr: unknown) {
      const fe = fetchErr as unknown as { stdout?: unknown; stderr?: unknown; status?: unknown; code?: unknown; message?: unknown };
      fetchStdout = fe.stdout ? String(fe.stdout).slice(0,2000) : "";
      fetchStderr = fe.stderr ? String(fe.stderr).slice(0,2000) : "";
      const code = (fe as unknown as { status?: unknown })?.status ?? (fe as unknown as { code?: unknown })?.code ?? "unknown";
      const msg = getErrorMessage(fetchErr);
      console.error(`Failed to freeze factory base: git fetch origin main failed code=${String(code)} msg=${msg} stdout=${fetchStdout.slice(0,1000)} stderr=${fetchStderr.slice(0,2000)} — aborting run (fail closed, not retrying iteration)`);
      break;
    }
    factoryBaseSha = execSync('git rev-parse origin/main', {encoding:'utf8'}).trim();
    callerBranch = execSync('git branch --show-current', {encoding:'utf8'}).trim();
    callerSha = execSync('git rev-parse HEAD', {encoding:'utf8'}).trim();
    callerStatusBefore = (() => { try { return execSync('git status --porcelain', {encoding:'utf8', cwd: REPO_ROOT}).trim(); } catch { return null; } })();
    console.log(`Factory base frozen: ${factoryBaseSha.slice(0,7)} (origin/main), caller ${callerBranch}@${callerSha.slice(0,7)} status "${(callerStatusBefore||'').slice(0,80)}" — will NOT be mutated`);
  } catch (e) {
    const fe = e as unknown as { stdout?: unknown; stderr?: unknown; status?: unknown; code?: unknown };
    const stdout = fe.stdout ? String(fe.stdout).slice(0,1000) : "";
    const stderr = fe.stderr ? String(fe.stderr).slice(0,2000) : "";
    const code = (fe as unknown as { status?: unknown })?.status ?? (fe as unknown as { code?: unknown })?.code ?? "unknown";
    console.error(`Failed to freeze factory base: ${getErrorMessage(e)} code=${String(code)} stdout=${stdout} stderr=${stderr} — aborting run`);
    break;
  }

  // ----- Phase 0.5: Claim before work (host-side, before expensive workers) -----
  let claimedIssues: typeof plannedIssues = [];
  if (QUALIFICATION_LIFECYCLE.claimExternalState) {
    for (const p of plannedIssues) {
      const src = eligible.find((e) => String(e.number) === p.id);
      if (!src) continue;
      const ok = await claimIssue(src);
      if (ok) claimedIssues.push(p);
      else console.warn(`  Skipping #${p.id} -- claim failed, likely raced`);
    }
  } else {
    console.log("Qualification mode: external claim suppressed (read-only and explicit target selection).");
    claimedIssues = [...plannedIssues];
  }

  if (claimedIssues.length === 0) {
    console.log("No issues prepared for execution -- nothing to execute this iteration.");
    continue;
  }

  // Prepare issue branches via single write-once provenance state machine (claim/retry and reconciliation share it).
  // Never overwrite provenance — fail closed on legacy contaminated branches, recreate only truly empty stale.
  const preparedIssues: typeof claimedIssues = [];
  for (const p of claimedIssues) {
    const prep = branchHelpers.prepareIssueBranch(REPO_ROOT, p.branch, factoryBaseSha, callerBranch, callerSha, p.id);
    if (prep.ok) {
      console.log(`  Prepared ${p.branch}: ${prep.action} — ${prep.reason} → ${prep.provPath}`);
      // Assert caller still unchanged after prepare (provenance is gitignored, branch creation is isolated)
      try {
        const afterPrepareStatus = execSync('git status --porcelain', {encoding:'utf8', cwd: REPO_ROOT}).trim();
        if (callerStatusBefore !== null && afterPrepareStatus !== callerStatusBefore) {
          console.warn(`  Prepare left caller status dirty: before "${callerStatusBefore.slice(0,100)}" after "${afterPrepareStatus.slice(0,100)}" — should be gitignored`);
        }
      } catch {}
      preparedIssues.push(p);
    } else {
      if (QUALIFICATION_LIFECYCLE.mutateOutcomeState) {
        if (prep.action === "blocked") {
          console.warn(`  #${p.id} (${p.branch}) → ${prep.reason} — fail closed, preserving/blocking`);
          await markBlocked(p.id, p.branch, prep.reason);
        } else {
          console.error(`  #${p.id} (${p.branch}) → prepare failed (${prep.action}): ${prep.reason}`);
          await markBlocked(p.id, p.branch, prep.reason);
        }
      } else {
        console.log(`  #${p.id} (${p.branch}) → prepare failed (${prep.action}) — preserving for qualification evidence`);
      }
      // Do not launch worker for blocked branches — already preserved, will not be retried until branch removed
    }
  }
  claimedIssues = preparedIssues;
  if (claimedIssues.length === 0) {
    console.log("No issues prepared for execution (all blocked/failed) — nothing to execute this iteration.");
    continue;
  }

  console.log(`\nClaimed and prepared ${claimedIssues.length} issue(s), launching parallel workers...\n`);

  // ----- Phase 2: Execute + Review (parallel, isolated) -----
  const settled = await Promise.allSettled<WorkerResult>(
    claimedIssues.map(async (issue): Promise<WorkerResult> => {
      let sandbox: Awaited<ReturnType<typeof sandcastle.createSandbox>> | null = null;
      for (let attempt = 0; attempt <= MECHANICAL_RETRY_BUDGET; attempt++) {
        try {
          sandbox = await sandcastle.createSandbox({
            branch: issue.branch,
            baseBranch: factoryBaseSha,
            sandbox: docker({ env: WORKER_SANDBOX_ENV }),
            hooks,
            copyToWorktree,
            timeouts: { worktreeMs: 600_000 },
          });
          break;
        } catch (e) {
          if (attempt === MECHANICAL_RETRY_BUDGET) throw e;
          const backoff = MECHANICAL_RETRY_BASE_MS * Math.pow(2, attempt);
          console.warn(`  sandbox create failed for #${issue.id} (attempt ${attempt+1}/${MECHANICAL_RETRY_BUDGET+1}): ${getErrorMessage(e)} — retrying in ${backoff}ms`);
          await new Promise(r => setTimeout(r, backoff));
        }
      }
      // sandbox is guaranteed non-null here (otherwise thrown)
      try {
        let implement = await sandbox!.run({
          name: "implementer",
          maxIterations: 100,
          // Emergency deadman only — not liveness detection. 30m matches
          // sandcastle's principled fix: live agent thinking quietly for
          // #126-class work must not be killed; only hard cap as safety.
          idleTimeoutSeconds: 1800,
          agent: sandcastle.muse("muse-spark-1.2-contributor"),
          promptFile: "./.sandcastle/implement-prompt.md",
          promptArgs: {
            TASK_ID: issue.id,
            ISSUE_TITLE: issue.title,
            BRANCH: issue.branch,
            REVIEW_FEEDBACK: "",
          },
        });
        // Reviewer→implementer feedback loop — one bounded retry for mechanical/semantic misses
        let reviewVerdict: ReviewVerdict | null = null;
        let reviewTextForRetry = "";
        let allCommits = [...implement.commits];
        // If no new commits but branch already has implementation (e.g., canary already at target SHA), use existing commits for review
        if (allCommits.length === 0) {
          try {
            const existing = execFileSync("git", ["log", `main..${issue.branch}`, "--oneline"], { encoding: "utf8", cwd: REPO_ROOT }).toString().trim();
            if (existing) {
              const shas = existing.split("\n").map((l: string) => l.split(" ")[0]).filter(Boolean);
              if (shas.length > 0) {
                console.log(`  ${issue.id} has existing commits on ${issue.branch} (${shas.join(",")}) - using for review`);
                allCommits = [...shas];
                (implement as unknown as { commits: string[] }).commits = [...shas];
              }
            }
          } catch {}
        }
        let shouldRetryReview = false;
        for (let reviewAttempt = 0; reviewAttempt <= REVIEW_RETRY_BUDGET; reviewAttempt++) {
          if (implement.commits.length === 0) break;
          // On retry, re-run implementer with reviewer feedback
          if (reviewAttempt > 0) {
            const feedback = formatVerdictForRetry(reviewVerdict, reviewTextForRetry);
            console.log(`  Reviewer requested changes for #${issue.id} (attempt ${reviewAttempt}/${REVIEW_RETRY_BUDGET}) — re-running implementer with feedback`);
            const retryImplement = await sandbox!.run({
              name: "implementer-retry",
              maxIterations: 50,
              // Emergency deadman — see implementer above.
              idleTimeoutSeconds: 1800,
              agent: sandcastle.muse("muse-spark-1.2-contributor"),
              promptFile: "./.sandcastle/implement-prompt.md",
              promptArgs: {
                TASK_ID: issue.id,
                ISSUE_TITLE: issue.title,
                BRANCH: issue.branch,
                REVIEW_FEEDBACK: feedback,
              },
            });
            allCommits = [...allCommits, ...(retryImplement.commits ?? [])];
            implement = retryImplement;
            if (retryImplement.commits.length === 0) break;
          }
          // Fresh fetch of original issue body - no stale caching.
          const issueBody = await fetchIssueBody(issue.id);
          const reviewerResult = await runReviewerPass({
            issueId: issue.id,
            issueTitle: issue.title,
            branch: issue.branch,
            attempt: reviewAttempt,
            issueBody,
            allCommits,
            runReviewer: async (_issueBody, _attempt, isRetry) => {
              const review = await sandbox!.run({
                name: isRetry ? "reviewer-retry" : "reviewer",
                maxIterations: 1,
                agent: sandcastle.muse("muse-spark-1.2-contributor"),
                promptFile: "./.sandcastle/review-prompt.md",
                promptArgs: {
                  BRANCH: issue.branch,
                  ISSUE_NUMBER: issue.id,
                  ISSUE_TITLE: issue.title,
                  ISSUE_BODY: _issueBody,
                },
                output: sandcastle.Output.object({ tag: "verdict", schema: reviewVerdictSchema }),
              });
              return review;
            },
            onReviewerFailure: (id, attemptIndex, reviewError) => {
              console.warn(`  Reviewer failed for #${id} (attempt ${attemptIndex + 1}): ${String(reviewError).slice(0, REVIEW_ERROR_TRUNCATE)} - treating as FACTORY_ERROR (missing/invalid machine-verifiable verdict)`);
            },
            onInvalidVerdict: (id, attemptIndex, reason, reviewText) => {
              console.warn(`  Reviewer produced invalid verdict for #${id} (attempt ${attemptIndex + 1}): ${reason} — ${reviewText.slice(0, REVIEW_ERROR_TRUNCATE)} — treating as FACTORY_ERROR`);
            },
          });
          const verdict = reviewerResult.verdict;
          reviewVerdict = verdict;
          reviewTextForRetry = reviewerResult.reviewText;
          if (verdict === null) {
            return reviewerResult;
          }
          allCommits = reviewerResult.commits;
          if (isVerdictApproved(verdict)) {
            return {
              commits: allCommits,
              verdict,
              reviewText: reviewerResult.reviewText,
            };
          }
          // Not approved — loop will retry if budget remains
          if (reviewAttempt < REVIEW_RETRY_BUDGET) {
            shouldRetryReview = true;
            continue;
          }
          // Budget exhausted — return last result for blocked handling
          return {
            commits: allCommits,
            verdict,
            reviewText: reviewerResult.reviewText,
          };
          }
          // No commits or loop exhausted without early return
          if (implement.commits.length > 0 && shouldRetryReview) {
            return {
              commits: allCommits,
              verdict: reviewVerdict,
              reviewText: reviewTextForRetry,
            };
          }
          if (implement.commits.length > 0) {
            // (review handled in loop above)
          }
          return { commits: allCommits.length > 0 ? allCommits : implement.commits, verdict: reviewVerdict, reviewText: reviewTextForRetry };
      } finally {
        await sandbox!.close();
        try { process.chdir(REPO_ROOT); } catch {}
      }
    }),
  );

  // ----- Failure visibility per worker + review verdict gating -----
  const partition = partitionWorkerOutcomes(
    claimedIssues,
    settled as unknown as WorkerOutcome[],
  );
  const {
    completed,
    failed,
    reviewRejected,
    factoryErrors,
    shouldStopOuterLoop,
  } = partition;
  const completedIssues = completed;
  const failedMarkResults: boolean[] = [];

  const failedIssueIds = failed.map((issue) => issue.id);
  const mutationPlan = partitionToMutationPlan(partition);
  for (const mutation of mutationPlan) {
    if (!QUALIFICATION_LIFECYCLE.mutateOutcomeState) {
      if (mutation.kind === "failed") {
        const reason = mutation.reason.slice(0, WORKER_REASON_TRUNCATE);
        console.error(`  ✗ ${mutation.issue.id} (${mutation.issue.branch}) failed: ${reason} [external mutation suppressed by qualification policy]`);
      } else if (mutation.kind === "reviewRejected") {
        const reason = mutation.reason;
        console.warn(`  ⚠ ${mutation.issue.id} review rejected (${reason.slice(0, REVIEW_ERROR_TRUNCATE)}) [external mutation suppressed by qualification policy]`);
      } else {
        const reason = mutation.reason;
        console.warn(`  ⚠ ${mutation.issue.id} review verdict contract failed (FACTORY_ERROR): ${reason.slice(0, REVIEW_ERROR_TRUNCATE)} [external mutation suppressed by qualification policy]`);
      }
      continue;
    }

    if (mutation.kind === "failed") {
      const reason = mutation.reason.slice(0, WORKER_REASON_TRUNCATE);
      console.error(`  ✗ ${mutation.issue.id} (${mutation.issue.branch}) failed: ${reason}`);
      const marked = await markBlocked(mutation.issue.id, mutation.issue.branch, mutation.reason);
      failedMarkResults.push(marked);
      continue;
    }

    if (mutation.kind === "reviewRejected") {
      const reason = mutation.reason;
      console.warn(`  ⚠ ${mutation.issue.id} review rejected - not eligible for merger: ${reason.slice(0, REVIEW_ERROR_TRUNCATE)}`);
      await markReviewRejected(
        mutation.issue.id,
        mutation.issue.branch,
        mutation.verdict!,
        reason,
      );
      continue;
    }

    const reason = mutation.reason;
    console.warn(`  ⚠ ${mutation.issue.id} review verdict contract failed (FACTORY_ERROR): ${reason.slice(0, REVIEW_ERROR_TRUNCATE)} — preserving branch and stopping outer run.`);
    await markFactoryError(mutation.issue.id, mutation.issue.branch, reason);
  }

  const completedBranches = completedIssues.map((i) => i.branch);

  const failedIndices = failedIssueIds;

  console.log(`\nExecution complete. ${completedBranches.length} branch(es) approved for merger:`);
  for (const b of completedBranches) console.log(`  ${b}`);
  if (failedIndices.length > 0) {
    const markedCount = failedMarkResults.filter(Boolean).length;
    const unmarkedCount = failedIndices.length - markedCount;
    if (unmarkedCount === 0) {
      console.log(`  ${failedIndices.length} branch(es) failed and were marked agent:blocked`);
    } else {
      console.log(`  ${failedIndices.length} branch(es) failed: ${markedCount} marked agent:blocked, ${unmarkedCount} NOT marked (GitHub unavailable — local recovery at .sandcastle/recovery/*.json, will reconcile truthfully after connectivity returns)`);
    }
  }
  if (reviewRejected.length > 0) {
    if (QUALIFICATION_LIFECYCLE.mutateOutcomeState) {
      console.log(`  ${reviewRejected.length} branch(es) review-rejected (approved=false) - preserved, marked agent:blocked, not merged`);
    } else {
      console.log(`  ${reviewRejected.length} branch(es) review-rejected (approved=false) - external mutation suppressed by qualification policy`);
    }
  }
  if (factoryErrors.length > 0) {
    if (QUALIFICATION_LIFECYCLE.mutateOutcomeState) {
      console.log(`  ${factoryErrors.length} branch(es) produced FACTORY_ERROR - preserved for inspection, stopping outer run`);
    } else {
      console.log(`  ${factoryErrors.length} branch(es) produced FACTORY_ERROR - external mutation suppressed by qualification policy, preserved for inspection`);
    }
  }

  if (!canClaimNextOuterIteration(partition)) {
    console.log("Factory error detected during review verdict handling. Stopping outer loop to prevent unsafe progression.");
    break;
  }

  if (completedBranches.length === 0) {
    console.log("No commits produced. Nothing to merge this iteration.");
    continue;
  }

  if (!QUALIFICATION_LIFECYCLE.integrate) {
    console.log("Qualification mode: integration suppressed by lifecycle policy; preserving local branches/logs for inspection.");
    continue;
  }

  // ----- Phase 3: Merge (single agent merges all completed branches) -----
  // INVARIANT: Sandcastle never integrates agent work into the caller's current branch.
  // Use helper so production and regression test the same batch-worktree seam.
  const callerBranchForBatch = callerBranch;
  const callerShaForBatch = callerSha;
  // Use frozen snapshot from factory-base freeze (whole-run invariant), not a late Phase-3 snapshot
  const callerStatusBeforeForBatch = callerStatusBefore;
  const callerHeadBefore = callerSha;
  const batchBranch = `sandcastle/batch-${completedIssues.map(i=>i.id).join('-')}-${Date.now().toString(36)}`;
  console.log(`Creating dedicated batch branch ${batchBranch} from factoryBaseSha ${factoryBaseSha.slice(0,7)} (caller ${callerBranchForBatch}@${callerShaForBatch.slice(0,7)} — will NOT be mutated)`);
  let batchWorktreePath: string | null = null;
  try {
    batchWorktreePath = branchHelpers.createBatchWorktree(REPO_ROOT, batchBranch, factoryBaseSha);
    console.log(`Created batch worktree ${batchWorktreePath} for ${batchBranch} from ${factoryBaseSha.slice(0,7)} — caller ${callerBranchForBatch} never moved`);
  } catch (e) {
    console.error(`Failed to create batch worktree for ${batchBranch} from ${factoryBaseSha.slice(0,7)}: ${getErrorMessage(e)}`);
    for (const iss of completedIssues) {
      await markBlocked(iss.id, iss.branch, `Batch worktree creation failed for ${batchBranch} from ${factoryBaseSha.slice(0,7)}: ${String(getErrorMessage(e)).slice(0, MERGER_REASON_TRUNCATE)} -- branch preserved`);
    }
    if (batchWorktreePath) { try { execSync(`git worktree remove --force ${batchWorktreePath}`, {stdio:'ignore'}); } catch {} }
    try { execSync(`git branch -D ${batchBranch}`, {stdio:'ignore'}); } catch {}
    continue;
  }

  try {
    await sandcastle.run({
      hooks,
      sandbox: docker({ env: WORKER_SANDBOX_ENV }),
      name: "merger",
      maxIterations: 1,
      cwd: batchWorktreePath,
      agent: sandcastle.muse("muse-spark-1.2-contributor"),
      promptFile: path.join(REPO_ROOT, ".sandcastle", "merge-prompt.md"),
      promptArgs: {
        BRANCHES: completedBranches.map((b) => `- ${b}`).join("\n"),
        ISSUES: completedIssues.map((i) => `- ${i.id}: ${i.title}`).join("\n"),
      },
    });
    console.log(`\nBranches merged locally into ${batchBranch} via merger agent in worktree ${batchWorktreePath}.`);
  } catch (error: unknown) {
    console.error(`Merger failed on ${batchBranch} in ${batchWorktreePath}: ${getErrorMessage(error)}`);
    for (const iss of completedIssues) {
      await markBlocked(iss.id, iss.branch, `Merger failed on ${batchBranch}: ${String(getErrorMessage(error)).slice(0, MERGER_REASON_TRUNCATE)} -- branch preserved`);
    }
    try { execSync(`git worktree remove --force ${batchWorktreePath}`, {stdio:'ignore'}); } catch {}
    try { execSync(`git branch -D ${batchBranch}`, {stdio:'ignore'}); } catch {}
    continue;
  }

  // Host-side: push batch branch + PR + auto-merge. Never push caller's branch.
  let currentBranch = batchBranch;
  try {
    console.log(`Batch branch after merge: ${currentBranch} (caller ${callerBranchForBatch} untouched, caller SHA ${callerShaForBatch.slice(0,7)})`);

    // Attempt to create/update PR for batch -- best effort. Identify existing PR by exact expected head branch, never by implicit current branch.
    const ownerRepo = parseOwnerRepo();
    if (ownerRepo) {
      try {
        // Check if PR already exists for the dedicated batch branch (exact head), not caller's branch
        let existingPr = "";
        try {
          existingPr = await runGh(["pr", "list", "--head", batchBranch, "--state", "open", "--json", "number", "--jq", ".[0].number"]);
        } catch {}
        if (!existingPr) {
          try {
            // Push batch branch explicitly (never caller's branch, never HEAD when HEAD could be caller-controlled)
            execSync(`git push origin ${batchBranch}`, { stdio: "ignore" });
          } catch {}
          try {
            const prBody = `Sandcastle batch integration -- branches:\n${completedBranches.map((b) => `- \`${b}\``).join("\n")}\n\n${completedIssues.map((i) => `Closes #${i.id} - ${i.title}`).join("\n")}\n\n<!-- batch-pr-map: ${completedIssues.map(i=>i.id).join(',')} -->`;
            const prCreateBase = branchHelpers.buildPrCreateArgs(batchBranch, completedIssues);
            const prUrl = await runGh([...prCreateBase, "--body", prBody]);
            console.log(`Created PR: ${prUrl}`);
            // Durable batch-PR correlation: record exact PR number against every issue (for reconciliation)
            try {
              const prNumberForMap = prUrl.match(/\/pull\/(\d+)/)?.[1];
              if(prNumberForMap){
                for(const iss of completedIssues){
                  await safeRunGh(["issue","comment", iss.id, "--body", `Sandcastle batch integration: branch \`${iss.branch}\` merged into \`${currentBranch}\`, batch PR #${prNumberForMap} (${prUrl}) awaiting Factory / Merge Oracle. Closes #${iss.id}`]);
                }
              }
            } catch {}
            // Privileged changes may never grant themselves autonomous merge authority.
            // Classify the exact batch candidate via helper, not caller HEAD.
            try {
              const prNumber = prUrl.match(/\/pull\/(\d+)/)?.[1];
              if (prNumber) {
                const diffSpec = branchHelpers.buildProtectedRootDiffSpec(factoryBaseSha, batchBranch);
                const changed = execSync(`git diff --name-only ${diffSpec}`, { encoding: "utf8" }).split(/\r?\n/).filter(Boolean);
                if (!mayAutonomouslyMerge(changed)) {
                  console.log(`PR #${prNumber} changes a protected root; independent human approval is required`);
                } else {
                  await runGh(["pr", "merge", prNumber, "--auto", "--squash"]);
                  console.log(`Auto-merge enabled for PR #${prNumber}; Factory / Merge Oracle remains authoritative`);
                }
              }
            } catch (error: unknown) {
              console.warn(`Auto-merge not enabled: ${getErrorMessage(error)}`);
            }
          } catch (error: unknown) {
            console.warn(`PR creation skipped: ${getErrorMessage(error)}`);
          }
        } else {
          console.log(`PR #${existingPr} already exists for ${batchBranch} (dedicated batch, not caller ${callerBranchForBatch})`);
          try {
            execSync(`git push origin ${batchBranch}`, { stdio: "ignore" });
          } catch {}
        }
      } catch (error: unknown) {
        console.warn(`PR handling failed: ${getErrorMessage(error)}`);
      }
    }
  } catch (error: unknown) {
    console.warn(`Post-merge PR handling failed (non-fatal): ${getErrorMessage(error)}`);
  } finally {
    // Enforce invariant via helper: caller checkout never moved, git status --porcelain unchanged
    try {
      const afterBranch = execSync('git branch --show-current', {encoding:'utf8'}).trim();
      const callerStatusAfter = (() => { try { return execSync('git status --porcelain', {encoding:'utf8', cwd: REPO_ROOT}).trim(); } catch { return null; } })();
      const callerHeadAfter = (() => { try { return execSync('git rev-parse HEAD', {encoding:'utf8', cwd: REPO_ROOT}).trim(); } catch { return null; } })();
      // Use helper for caller-unchanged check (same seam as tests) — compare against frozen snapshot from iteration start
      const callerCheck = branchHelpers.verifyCallerUnchanged(REPO_ROOT, callerBranchForBatch, callerShaForBatch);
      const refSha = callerCheck.refSha;
      if (!callerCheck.ok) {
        console.error(`INVARIANT VIOLATION: caller unchanged FAILED — ref ${callerCheck.refSha.slice(0,7)} vs ${callerShaForBatch.slice(0,7)} checkout ${callerCheck.checkoutBranch} vs ${callerBranchForBatch}`);
      } else {
        console.log(`Invariant OK: caller ref ${callerCheck.refSha.slice(0,7)} checkout ${callerCheck.checkoutBranch} (batch ${batchBranch} pushed)`);
      }
      if (refSha !== callerShaForBatch) {
        console.error(`INVARIANT VIOLATION: refs/heads/${callerBranchForBatch} changed! Before ${callerShaForBatch.slice(0,7)} after ${refSha.slice(0,7)} — caller branch was mutated`);
      }
      if (callerStatusBeforeForBatch !== null && callerStatusAfter !== null && callerStatusBeforeForBatch !== callerStatusAfter) {
        console.error(`INVARIANT VIOLATION: git status --porcelain changed from "${callerStatusBeforeForBatch.slice(0,200)}" to "${callerStatusAfter.slice(0,200)}" — caller working tree was mutated (whole-run invariant from freeze)`);
      } else {
        console.log(`Caller status unchanged (whole-run): "${(callerStatusAfter||'').slice(0,100)}" vs frozen "${(callerStatusBeforeForBatch||'').slice(0,50)}"`);
      }
      if (callerHeadBefore !== callerHeadAfter) {
        console.error(`INVARIANT VIOLATION: caller HEAD moved from ${callerHeadBefore?.slice(0,7)} to ${callerHeadAfter?.slice(0,7)} — should remain on ${callerBranchForBatch}`);
      }
      // Also verify checkout still on caller (should never have left)
      if (afterBranch !== callerBranchForBatch) {
        console.warn(`Caller checkout moved from ${callerBranchForBatch} to ${afterBranch} — restoring (should not have happened with dedicated worktree)`);
        try { execFileSync("git", ["checkout", callerBranchForBatch], { stdio: "ignore", cwd: REPO_ROOT }); } catch {}
      }
      // Clean up batch worktree (if used) — keep branch for PR, remove worktree via helper
      if (batchWorktreePath) {
        try { branchHelpers.cleanupBatchWorktree(REPO_ROOT, batchWorktreePath); } catch { try { execSync(`git worktree remove --force ${batchWorktreePath}`, {stdio:'ignore'}); } catch {} }
        console.log(`Batch worktree ${batchWorktreePath} removed (branch ${batchBranch} remains for PR)`);
      }
    } catch (e) {
      console.warn(`Failed to verify caller invariant: ${getErrorMessage(e)}`);
      if (batchWorktreePath) { try { branchHelpers.cleanupBatchWorktree(REPO_ROOT, batchWorktreePath); } catch { try { execSync(`git worktree remove --force ${batchWorktreePath}`, {stdio:'ignore'}); } catch {} } }
    }
  }

  // A local merge is not integration into main. Leave tickets open until the
  // PR is actually merged under Factory / Merge Oracle authority.
  for (const iss of completedIssues) {
    await safeRunGh(["issue", "comment", iss.id, "--body", `Sandcastle produced and reviewed \`${iss.branch}\`. Awaiting exact-SHA Factory / Merge Oracle evidence and PR merge; this issue remains open.`]);
    console.log(`  #${iss.id} remains open pending authoritative PR merge`);
  }

  // Immediate GC for ephemeral batch artefacts -- same-process lifecycle (not weekly cron)
    try { branchHelpers.cleanupPreserveLocalBranches(REPO_ROOT); } catch {}
  try {
    execSync("git worktree prune", { stdio: "ignore" });
  } catch {}

  console.log("\nBatch submitted -- authoritative CI and merge remain pending.");
}

console.log("\nAll done.");

