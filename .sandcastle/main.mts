// Factory v0 -- deterministic eligibility, claim-before-work, failure visibility,
// batch integration with audit trail. Preserves parallel isolated workers,
// review, and merger topology.

import * as sandcastle from "@ai-hero/sandcastle";
import { docker } from "@ai-hero/sandcastle/sandboxes/docker";
import { z } from "zod";
import { execFile, execSync } from "node:child_process";
import { promisify } from "node:util";
import * as fs from "node:fs";
import * as path from "node:path";
import { isEligible, branchForIssue, type IssueInput } from "./dispatch.mts";
import { mayAutonomouslyMerge } from "./ci-policy.mts";
import {
  reviewVerdictSchema,
  isVerdictApproved,
  extractVerdict,
  blockedReasonForVerdict,
  type ReviewVerdict,
} from "./review-verdict.mts";
import { formatGhFailure, getErrorMessage, getGhErrorDetails } from "./gh-errors.mts";

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
    if (failureContext) console.warn(formatGhFailure(failureContext, error));
    return false;
  }
}

function ghToken(): string {
  if (process.env.GH_TOKEN) return process.env.GH_TOKEN;
  // fallback: read .sandcastle/.env
  try {
    const envPath = path.join(process.cwd(), ".sandcastle", ".env");
    const content = fs.readFileSync(envPath, "utf8");
    const m = content.match(/^GH_TOKEN=(.*)$/m);
    if (m) return m[1].trim();
  } catch {}
  return "";
}

async function runGh(args: string[]): Promise<string> {
  const token = ghToken();
  const env = { ...process.env, GH_TOKEN: token };
  const bin = ghBinary();
  try {
    const { stdout } = await execFileAsync(bin, args, { env, maxBuffer: 10 * 1024 * 1024 });
    return stdout.trim();
  } catch (error: unknown) {
    throw new Error(`gh ${args.join(" ")} failed: ${getGhErrorDetails(error)}`);
  }
}

function parseOwnerRepo(): { owner: string; repo: string } | null {
  try {
    const out = execSync("git remote get-url origin", { encoding: "utf8" }).trim();
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

async function transitionToBlocked(issueId: string): Promise<void> {
  await safeRunGh(
    ["issue", "edit", issueId, "--remove-label", "agent:in-progress"],
    `Failed to remove agent:in-progress from #${issueId}`,
  );
  await safeRunGh(
    ["issue", "edit", issueId, "--add-label", "agent:blocked"],
    `Failed to add agent:blocked to #${issueId}`,
  );
}

async function markBlocked(issueId: string, branch: string, reason: string): Promise<void> {
  const shortReason = reason.slice(0, REASON_TRUNCATE);
  await transitionToBlocked(issueId);
  await safeRunGh([
    "issue",
    "comment",
    issueId,
    "--body",
    `Sandcastle failed on \`${branch}\` -- not merged. Preserved branch for inspection.\n\n**Reason:** ${shortReason}\n\nBranch: \`${branch}\`\n\nTo retry: remove \`agent:blocked\`, ensure \`agent:implement\` is still present, and re-run factory.`,
  ]);
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
  const body = await runGh(["issue", "view", issueId, "--json", "body", "--jq", ".body"]);
  return body;
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

// ---------------------------------------------------------------------------
// Factory doctor — fail-closed preflight before any claim
// ---------------------------------------------------------------------------
async function runDoctor(): Promise<boolean> {
  console.log("\n=== Factory Doctor (preflight — proves real worker boundary) ===\n");
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
      console.log(`  Doctor cache HIT — SHA ${sha.slice(0,7)} + image ${(imageId||imageDigest).slice(0,12)} already certified`);
      console.log('=== Doctor PASS (cached) ===\n');
      return true;
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
  // 3. Prove actual worker boundary: create real docker sandbox and run bootstrap hooks (no LLM)
  console.log('  Proving worker sandbox — creating docker sandbox with onSandboxReady hooks...');
  let sandbox: Awaited<ReturnType<typeof sandcastle.createSandbox>> | null = null;
  const doctorBranch = `doctor-${Date.now()}`;
  try {
    sandbox = await sandcastle.createSandbox({
      branch: doctorBranch,
      sandbox: docker(),
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
        try{ await sandbox.close(); }catch{}
        try{ execSync(`git branch -D ${doctorBranch} 2>/dev/null || true`, {stdio:'ignore'}); }catch{}
        return false;
      }
      if(c.mustContain && !out.includes(c.mustContain)){
        console.error(`  FAIL: ${c.label} output missing '${c.mustContain}' — got: ${out.slice(0,300)}`);
        try{ await sandbox.close(); }catch{}
        try{ execSync(`git branch -D ${doctorBranch} 2>/dev/null || true`, {stdio:'ignore'}); }catch{}
        return false;
      }
    }
    console.log('  worker sandbox bootstrap: all hooks passed');
    await sandbox.close();
    try{ execSync(`git branch -D ${doctorBranch} 2>/dev/null || true`, {stdio:'ignore'}); }catch{}
    execSync('git worktree prune 2>/dev/null || true', {stdio:'ignore'});
  } catch(e){
    console.error(`  FAIL: doctor sandbox creation or exec failed: ${getErrorMessage(e)}`);
    if(sandbox) try{ await sandbox.close(); }catch{}
    try{ execSync(`git branch -D ${doctorBranch} 2>/dev/null || true`, {stdio:'ignore'}); }catch{}
    return false;
  }
  // 4. Cache PASS against SHA + image identity
  try {
    fs.writeFileSync(cachePath, JSON.stringify({sha, imageId, imageDigest, passed:true, at: new Date().toISOString()}, null, 2));
    console.log(`  Doctor cache written: ${cachePath}`);
  } catch {}
  console.log('=== Doctor PASS (real sandbox) ===\n');
  return true;
}

// ---------------------------------------------------------------------------
// Main loop
// ---------------------------------------------------------------------------
for (let iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
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

  let plannedIssues: PlannedIssue[] = [];
  if (eligible.length === 1) {
    // Single issue -- no need to invoke LLM
    plannedIssues = [{ id: String(eligible[0].number), title: eligible[0].title, branch: branchForIssue(eligible[0].number) }];
    console.log(`Single eligible issue -- skipping LLM planner, direct dispatch #${plannedIssues[0].id}`);
  } else {
    try {
      const plan = await sandcastle.run({
        hooks,
        sandbox: docker(),
        name: "planner",
        maxIterations: 1,
        agent: sandcastle.muse("muse-spark-1.2-contributor"),
        promptFile: "./.sandcastle/plan-prompt.md",
        promptArgs: { ISSUES_JSON: issuesJson },
        output: sandcastle.Output.object({ tag: "plan", schema: planSchema }),
      });
      const rawPlanned = plan.output.issues as PlannedIssue[];
      // Enforce subset of eligible -- drop any hallucinated IDs
      const eligibleIds = new Set(eligible.map((e) => String(e.number)));
      plannedIssues = rawPlanned.filter((p) => eligibleIds.has(p.id));
      if (plannedIssues.length !== rawPlanned.length) {
        console.warn(`Planner returned ${rawPlanned.length - plannedIssues.length} ineligible hallucinated issue(s) -- dropped`);
      }
      if (plannedIssues.length === 0) {
        console.log("Planner advised to defer all eligible issues due to overlap risk. Will retry next iteration.");
        // Avoid busy loop: break to let human intervene next run
        break;
      }
      console.log(`Planner selected ${plannedIssues.length}/${eligible.length} issue(s) to run now:`);
      for (const p of plannedIssues) console.log(`  ${p.id}: ${p.title} → ${p.branch}`);
    } catch (error: unknown) {
      console.error(`Planner failed: ${getErrorMessage(error)} -- falling back to direct dispatch of all eligible`);
      plannedIssues = eligible.map((i) => ({ id: String(i.number), title: i.title, branch: branchForIssue(i.number) }));
    }
  }

  // ----- Phase 0.5: Claim before work (host-side, before expensive workers) -----
  const claimedIssues: typeof plannedIssues = [];
  for (const p of plannedIssues) {
    const src = eligible.find((e) => String(e.number) === p.id);
    if (!src) continue;
    const ok = await claimIssue(src);
    if (ok) claimedIssues.push(p);
    else console.warn(`  Skipping #${p.id} -- claim failed, likely raced`);
  }

  if (claimedIssues.length === 0) {
    console.log("No issues claimed -- nothing to execute this iteration.");
    continue;
  }

  console.log(`\nClaimed ${claimedIssues.length} issue(s), launching parallel workers...\n`);

  // ----- Phase 2: Execute + Review (parallel, isolated) -----
  const settled = await Promise.allSettled<WorkerResult>(
    claimedIssues.map(async (issue): Promise<WorkerResult> => {
      let sandbox: Awaited<ReturnType<typeof sandcastle.createSandbox>> | null = null;
      for (let attempt = 0; attempt <= MECHANICAL_RETRY_BUDGET; attempt++) {
        try {
          sandbox = await sandcastle.createSandbox({
            branch: issue.branch,
            sandbox: docker(),
            hooks,
            copyToWorktree,
            timeouts: { worktreeMs: 300_000 },
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
          let verdict: ReviewVerdict | null = null;
          let reviewText = "";
          try {
            const review = await sandbox!.run({
              name: reviewAttempt === 0 ? "reviewer" : "reviewer-retry",
              maxIterations: 1,
              agent: sandcastle.muse("muse-spark-1.2-contributor"),
              promptFile: "./.sandcastle/review-prompt.md",
              promptArgs: {
                BRANCH: issue.branch,
                ISSUE_NUMBER: issue.id,
                ISSUE_TITLE: issue.title,
                ISSUE_BODY: issueBody,
              },
              output: sandcastle.Output.object({ tag: "verdict", schema: reviewVerdictSchema }),
            });
            verdict = extractVerdict(review);
            reviewText = review.stdout;
          } catch (reviewError: unknown) {
            console.warn(`  Reviewer failed for #${issue.id} (attempt ${reviewAttempt+1}): ${String(reviewError).slice(0, REVIEW_ERROR_TRUNCATE)} - treating as rejected`);
            verdict = null;
            reviewText = String(reviewError);
          }
          reviewVerdict = verdict;
          reviewTextForRetry = reviewText;
          if (isVerdictApproved(verdict)) {
            return {
              commits: [...allCommits, ...((verdict ? [] : []))],
              verdict,
              reviewText,
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
            reviewText,
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
      }
    }),
  );

  // ----- Failure visibility per worker + review verdict gating -----
  const failedIndices: number[] = [];
  const reviewRejectedIndices: number[] = [];

  for (const [i, outcome] of settled.entries()) {
    if (outcome.status === "rejected") {
      const reason = String(outcome.reason ?? "unknown error").slice(0, WORKER_REASON_TRUNCATE);
      console.error(`  ✗ ${claimedIssues[i]!.id} (${claimedIssues[i]!.branch}) failed: ${reason}`);
      failedIndices.push(i);
      await markBlocked(claimedIssues[i]!.id, claimedIssues[i]!.branch, reason);
    } else if (outcome.value.commits.length === 0) {
      console.warn(`  ⚠ ${claimedIssues[i]!.id} produced no commits -- marking blocked for inspection`);
      await markBlocked(claimedIssues[i]!.id, claimedIssues[i]!.branch, "Implementer produced no commits (no work or error without throw). Branch preserved.");
      failedIndices.push(i);
    } else {
      const verdict = outcome.value.verdict;
      if (!isVerdictApproved(verdict)) {
        const reason = blockedReasonForVerdict(verdict);
        console.warn(`  ⚠ ${claimedIssues[i]!.id} review rejected - not eligible for merger: ${reason.slice(0, REVIEW_ERROR_TRUNCATE)}`);
        reviewRejectedIndices.push(i);
        await markReviewRejected(claimedIssues[i]!.id, claimedIssues[i]!.branch, verdict, reason);
      }
    }
  }

  // Completed = fulfilled + commits + review approved
  const completedEntries = settled
    .map((outcome, i) => ({ outcome, issue: claimedIssues[i]!, index: i }))
    .filter(
      (entry) =>
        entry.outcome.status === "fulfilled" &&
        entry.outcome.value.commits.length > 0 &&
        !failedIndices.includes(entry.index) &&
        !reviewRejectedIndices.includes(entry.index) &&
        isVerdictApproved(entry.outcome.value.verdict),
    );

  const completedIssues = completedEntries.map((entry) => entry.issue);
  const completedBranches = completedIssues.map((i) => i.branch);

  console.log(`\nExecution complete. ${completedBranches.length} branch(es) approved for merger:`);
  for (const b of completedBranches) console.log(`  ${b}`);
  if (failedIndices.length > 0) {
    console.log(`  ${failedIndices.length} branch(es) failed and were marked agent:blocked`);
  }
  if (reviewRejectedIndices.length > 0) {
    console.log(`  ${reviewRejectedIndices.length} branch(es) review-rejected (approved=false) - preserved, marked agent:blocked, not merged`);
  }

  if (completedBranches.length === 0) {
    console.log("No commits produced. Nothing to merge this iteration.");
    continue;
  }

  // ----- Phase 3: Merge (single agent merges all completed branches) -----
  try {
    await sandcastle.run({
      hooks,
      sandbox: docker(),
      name: "merger",
      maxIterations: 1,
      agent: sandcastle.muse("muse-spark-1.2-contributor"),
      promptFile: "./.sandcastle/merge-prompt.md",
      promptArgs: {
        BRANCHES: completedBranches.map((b) => `- ${b}`).join("\n"),
        ISSUES: completedIssues.map((i) => `- ${i.id}: ${i.title}`).join("\n"),
      },
    });
    console.log("\nBranches merged locally via merger agent.");
  } catch (error: unknown) {
    console.error(`Merger failed: ${getErrorMessage(error)}`);
    // Mark all completed as blocked since integration failed
    for (const iss of completedIssues) {
      await markBlocked(iss.id, iss.branch, `Merger failed: ${String(getErrorMessage(error)).slice(0, MERGER_REASON_TRUNCATE)} -- branch preserved`);
    }
    continue;
  }

  // Host-side: push + PR + auto-merge is handled by merger prompt's host?
  // For v0, attempt to push and create a batch PR. Failures are non-fatal -- work is already merged locally.
  // Attempt host-side audit-close only after local merge succeeded.
  // The PR creation is best-effort; closing issues indicates integration on current branch.
  try {
    // Try to push current branch if remote exists
    const currentBranch = execSync("git branch --show-current", { encoding: "utf8" }).trim();
    console.log(`Current branch after merge: ${currentBranch}`);

    // Attempt to create/update PR for batch -- best effort
    const ownerRepo = parseOwnerRepo();
    if (ownerRepo) {
      try {
        // Check if PR already exists for this branch
        let existingPr = "";
        try {
          existingPr = await runGh(["pr", "view", "--json", "number,state", "--jq", ".number"]);
        } catch {}
        if (!existingPr) {
          try {
            // Push first
            execSync(`git push origin HEAD`, { stdio: "ignore" });
          } catch {}
          try {
            const prBody = `Sandcastle batch integration -- branches:\n${completedBranches.map((b) => `- \`${b}\``).join("\n")}\n\n${completedIssues.map((i) => `Closes #${i.id} - ${i.title}`).join("\n")}\n\n<!-- batch-pr-map: ${completedIssues.map(i=>i.id).join(',')} -->`;
            const prUrl = await runGh([
              "pr",
              "create",
              "--base",
              "main",
              "--title",
              `Sandcastle batch: ${completedIssues.map((i) => `#${i.id}`).join(", ")}`,
              "--body",
              prBody,
            ]);
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
            try {
              const prNumber = prUrl.match(/\/pull\/(\d+)/)?.[1];
              if (prNumber) {
                const changed = execSync("git diff --name-only origin/main...HEAD", { encoding: "utf8" }).split(/\r?\n/).filter(Boolean);
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
          console.log(`PR #${existingPr} already exists for ${currentBranch}`);
          try {
            execSync(`git push origin HEAD`, { stdio: "ignore" });
          } catch {}
        }
      } catch (error: unknown) {
        console.warn(`PR handling failed: ${getErrorMessage(error)}`);
      }
    }
  } catch (error: unknown) {
    console.warn(`Post-merge PR handling failed (non-fatal): ${getErrorMessage(error)}`);
  }

  // A local merge is not integration into main. Leave tickets open until the
  // PR is actually merged under Factory / Merge Oracle authority.
  for (const iss of completedIssues) {
    await safeRunGh(["issue", "comment", iss.id, "--body", `Sandcastle produced and reviewed \`${iss.branch}\`. Awaiting exact-SHA Factory / Merge Oracle evidence and PR merge; this issue remains open.`]);
    console.log(`  #${iss.id} remains open pending authoritative PR merge`);
  }

  // Immediate GC for ephemeral batch artefacts -- same-process lifecycle (not weekly cron)
  try {
    execSync("git branch --list 'preserve-local-*' | xargs -r git branch -D", { stdio: "ignore" });
  } catch {}
  try {
    execSync("git worktree prune", { stdio: "ignore" });
  } catch {}

  console.log("\nBatch submitted -- authoritative CI and merge remain pending.");
}

console.log("\nAll done.");
