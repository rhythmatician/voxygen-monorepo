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

const execFileAsync = promisify(execFile);

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const MAX_ITERATIONS = 10;
const REASON_TRUNCATE = 800;
const MERGER_REASON_TRUNCATE = 1000;
const WORKER_REASON_TRUNCATE = 2000;

const hooks = {
  // Install vendored skills into the sandbox before any other setup so
  // Wayfinder/implement/research agents can discover them via `muse skills`.
  // Each entry in .muse/skills/* is installed to the user scope; output is
  // truncated to keep sandbox logs concise. This is best-effort -- the
  // forbidden-label guard in implement-prompt.md remains the safety net.
  //
  // Provision Python quality tooling before Muse starts work so formatting and
  // diagnostics are immediately available without agent remediation. Use the
  // lightweight dev-only sync (no heavy ML deps) aligned with the locked
  // versions: `uv sync --project python --only-group dev --locked`.
  sandbox: {
    onSandboxReady: [
      {
        command:
          'for s in .muse/skills/*; do muse skills install "$s" --scope user 2>&1 | head -5; done',
      },
      { command: "npm install" },
      {
        command:
          "uv sync --project python --only-group dev --locked 2>&1 | tail -20",
      },
      {
        command:
          'if pgrep -f "qgate-watcher.mjs" > /dev/null; then echo "watcher already running"; else setsid -f node scripts/qgate-watcher.mjs >> /tmp/qgate-watcher.log 2>&1; echo "watcher started pid $!"; sleep 1; cat /tmp/qgate-watcher.log 2>&1 | head -20; ps aux | grep -v grep | grep qgate-watcher | head -5 || echo "watcher check done"; fi',
      },
    ],
  },
} as const;
const copyToWorktree: string[] = [];

const planSchema = z.object({
  issues: z.array(
    z.object({ id: z.string(), title: z.string(), branch: z.string() })
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
  // Probe /usr/bin/gh first so a container or Linux host run never returns
  // a Windows path.
  if (fs.existsSync("/usr/bin/gh")) return "/usr/bin/gh";
  const winPath = "C:\\Program Files\\GitHub CLI\\gh.exe";
  if (fs.existsSync(winPath)) return winPath;
  const wslPath = "/mnt/c/Program Files/GitHub CLI/gh.exe";
  if (fs.existsSync(wslPath)) return wslPath;
  return "gh";
}

// muse binary: intentionally not hardcoded -- host and sandbox both expose
// `muse` on PATH (host via ~/.local/bin, sandbox via Dockerfile
// ENV PATH="/home/agent/.local/bin:$PATH" after install.sh).
// Do not introduce a host absolute path (same host-only principle as ghBinary).

function getErrorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

function getGhErrorDetails(error: unknown): string {
  if (error !== null && typeof error === "object" && "stderr" in error) {
    const stderr = (error as { stderr?: unknown }).stderr;
    if (stderr !== undefined && stderr !== null) return String(stderr);
  }
  return getErrorMessage(error);
}

async function safeRunGh(args: string[]): Promise<void> {
  try {
    await runGh(args);
  } catch {}
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
    const { stdout } = await execFileAsync(bin, args, {
      env,
      maxBuffer: 10 * 1024 * 1024,
    });
    return stdout.trim();
  } catch (error: unknown) {
    throw new Error(`gh ${args.join(" ")} failed: ${getGhErrorDetails(error)}`);
  }
}

function parseOwnerRepo(): { owner: string; repo: string } | null {
  try {
    const out = execSync("git remote get-url origin", {
      encoding: "utf8",
    }).trim();
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
        try {
          const summary = await runGh([
            "api",
            `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${r.number}`,
            "--jq",
            ".issue_dependencies_summary.blocked_by",
          ]);
          const n = parseInt(summary.trim(), 10);
          if (!isNaN(n)) blockedByCount = n;
        } catch {
          // ignore -- fallback to undefined (treated as unblocked)
        }
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
    })
  );
  return issues;
}

// Phase 0.5 claim — host-side, sequential, before createSandbox (single-host v0).
// Unified host claim: assignee + label + comment. Stale release is manual
// per #18 — do not auto-expire (gh issue edit --remove-label/--remove-assignee).
async function claimIssue(issue: IssueInput): Promise<boolean> {
  const id = String(issue.number);
  const branch = branchForIssue(issue.number);
  try {
    // Wayfinder-compatible claim: assignee + in-progress label, plus comment trace
    await runGh([
      "issue",
      "edit",
      id,
      "--add-assignee",
      "@me",
      "--add-label",
      "agent:in-progress",
    ]);
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

async function markBlocked(
  issueId: string,
  branch: string,
  reason: string
): Promise<void> {
  const shortReason = reason.slice(0, REASON_TRUNCATE);
  await safeRunGh([
    "issue",
    "edit",
    issueId,
    "--remove-label",
    "agent:in-progress",
  ]);
  await safeRunGh(["issue", "edit", issueId, "--add-label", "agent:blocked"]);
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
  for (const label of [
    "agent:in-progress",
    "agent:implement",
    "agent:blocked",
  ]) {
    await safeRunGh(["issue", "edit", issueId, "--remove-label", label]);
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
      await runGh([
        "issue",
        "comment",
        issueId,
        "--body",
        `Completed by Sandcastle -- branch \`${branch}\` integrated.`,
      ]);
      await runGh(["issue", "close", issueId]);
    } catch {}
  }
}

// ---------------------------------------------------------------------------
// Main loop
// ---------------------------------------------------------------------------
for (let iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
  console.log(`\n=== Iteration ${iteration}/${MAX_ITERATIONS} ===\n`);

  // ----- Phase 0: Deterministic eligibility (host-side, no LLM) -----
  let allCandidates: IssueInput[] = [];
  try {
    allCandidates = await fetchOpenImplementIssues();
  } catch (error: unknown) {
    console.error(`Failed to fetch issues: ${getErrorMessage(error)}`);
    break;
  }

  console.log(
    `Fetched ${allCandidates.length} open issue(s) with agent:implement`
  );
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
    2
  );

  let plannedIssues: PlannedIssue[] = [];
  if (eligible.length === 1) {
    // Single issue -- no need to invoke LLM
    plannedIssues = [
      {
        id: String(eligible[0].number),
        title: eligible[0].title,
        branch: branchForIssue(eligible[0].number),
      },
    ];
    console.log(
      `Single eligible issue -- skipping LLM planner, direct dispatch #${plannedIssues[0].id}`
    );
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
        console.warn(
          `Planner returned ${rawPlanned.length - plannedIssues.length} ineligible hallucinated issue(s) -- dropped`
        );
      }
      if (plannedIssues.length === 0) {
        console.log(
          "Planner advised to defer all eligible issues due to overlap risk. Will retry next iteration."
        );
        // Avoid busy loop: break to let human intervene next run
        break;
      }
      console.log(
        `Planner selected ${plannedIssues.length}/${eligible.length} issue(s) to run now:`
      );
      for (const p of plannedIssues)
        console.log(`  ${p.id}: ${p.title} → ${p.branch}`);
    } catch (error: unknown) {
      console.error(
        `Planner failed: ${getErrorMessage(error)} -- falling back to direct dispatch of all eligible`
      );
      plannedIssues = eligible.map((i) => ({
        id: String(i.number),
        title: i.title,
        branch: branchForIssue(i.number),
      }));
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

  console.log(
    `\nClaimed ${claimedIssues.length} issue(s), launching parallel workers...\n`
  );

  // ----- Phase 2: Execute + Review (parallel, isolated) -----
  const settled = await Promise.allSettled(
    claimedIssues.map(async (issue) => {
      const sandbox = await sandcastle.createSandbox({
        branch: issue.branch,
        sandbox: docker(),
        hooks,
        copyToWorktree,
        // Worktree checkout runs through WSL on an NTFS mount and can exceed
        // Sandcastle's 120-second default even for this modest repository.
        timeouts: { worktreeMs: 300_000 },
      });
      try {
        const implement = await sandbox.run({
          name: "implementer",
          maxIterations: 100,
          agent: sandcastle.muse("muse-spark-1.2-contributor"),
          promptFile: "./.sandcastle/implement-prompt.md",
          promptArgs: {
            TASK_ID: issue.id,
            ISSUE_TITLE: issue.title,
            BRANCH: issue.branch,
          },
        });
        if (implement.commits.length > 0) {
          const review = await sandbox.run({
            name: "reviewer",
            maxIterations: 1,
            agent: sandcastle.muse("muse-spark-1.2-contributor"),
            promptFile: "./.sandcastle/review-prompt.md",
            promptArgs: { BRANCH: issue.branch },
          });
          return {
            ...review,
            commits: [...implement.commits, ...review.commits],
          };
        }
        return implement;
      } finally {
        await sandbox.close();
      }
    })
  );

  // ----- Failure visibility per worker -----
  const failedIndices: number[] = [];
  for (const [i, outcome] of settled.entries()) {
    if (outcome.status === "rejected") {
      const reason = String(outcome.reason ?? "unknown error").slice(
        0,
        WORKER_REASON_TRUNCATE
      );
      console.error(
        `  ✗ ${claimedIssues[i]!.id} (${claimedIssues[i]!.branch}) failed: ${reason}`
      );
      failedIndices.push(i);
      await markBlocked(claimedIssues[i]!.id, claimedIssues[i]!.branch, reason);
    } else if (outcome.value.commits.length === 0) {
      // No commits -- treat as no-op, not failure, but remove in-progress so it can be retried?
      // Spec says failure must leave blocked; no-op we treat as blocked with diagnostics.
      console.warn(
        `  ⚠ ${claimedIssues[i]!.id} produced no commits -- marking blocked for inspection`
      );
      await markBlocked(
        claimedIssues[i]!.id,
        claimedIssues[i]!.branch,
        "Implementer produced no commits (no work or error without throw). Branch preserved."
      );
      failedIndices.push(i);
    }
  }

  const completedIssues = settled
    .map((outcome, i) => ({ outcome, issue: claimedIssues[i]! }))
    .filter(
      (entry) =>
        entry.outcome.status === "fulfilled" &&
        entry.outcome.value.commits.length > 0
    )
    .map((entry) => entry.issue);

  const completedBranches = completedIssues.map((i) => i.branch);

  console.log(
    `\nExecution complete. ${completedBranches.length} branch(es) with commits:`
  );
  for (const b of completedBranches) console.log(`  ${b}`);
  if (failedIndices.length > 0) {
    console.log(
      `  ${failedIndices.length} branch(es) failed and were marked agent:blocked`
    );
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
      await markBlocked(
        iss.id,
        iss.branch,
        `Merger failed: ${String(getErrorMessage(error)).slice(0, MERGER_REASON_TRUNCATE)} -- branch preserved`
      );
    }
    continue;
  }

  // Host-side: push + PR + auto-merge is handled by merger prompt's host?
  // For v0, attempt to push and create a batch PR. Failures are non-fatal -- work is already merged locally.
  // Attempt host-side audit-close only after local merge succeeded.
  // The PR creation is best-effort; closing issues indicates integration on current branch.
  try {
    // Try to push current branch if remote exists
    const currentBranch = execSync("git branch --show-current", {
      encoding: "utf8",
    }).trim();
    console.log(`Current branch after merge: ${currentBranch}`);

    // Attempt to create/update PR for batch -- best effort
    const ownerRepo = parseOwnerRepo();
    if (ownerRepo) {
      try {
        // Check if PR already exists for this branch
        let existingPr = "";
        try {
          existingPr = await runGh([
            "pr",
            "view",
            "--json",
            "number,state",
            "--jq",
            ".number",
          ]);
        } catch {}
        if (!existingPr) {
          try {
            // Push first
            execSync(`git push origin HEAD`, { stdio: "ignore" });
          } catch {}
          try {
            const prBody = `Sandcastle batch integration -- branches:\n${completedBranches.map((b) => `- \`${b}\``).join("\n")}\n\nIssues:\n${completedIssues.map((i) => `- #${i.id} ${i.title}`).join("\n")}`;
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
            // C5 changes may never grant themselves autonomous merge authority.
            try {
              const prNumber = prUrl.match(/\/pull\/(\d+)/)?.[1];
              if (prNumber) {
                const changed = execSync(
                  "git diff --name-only origin/main...HEAD",
                  { encoding: "utf8" }
                )
                  .split(/\r?\n/)
                  .filter(Boolean);
                if (!mayAutonomouslyMerge(changed)) {
                  console.log(
                    `PR #${prNumber} changes the control plane; independent human approval is required`
                  );
                } else {
                  await runGh(["pr", "merge", prNumber, "--auto", "--merge"]);
                  console.log(
                    `Auto-merge enabled for PR #${prNumber}; Factory / Merge Oracle remains authoritative`
                  );
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
    console.warn(
      `Post-merge PR handling failed (non-fatal): ${getErrorMessage(error)}`
    );
  }

  // A local merge is not integration into main. Leave tickets open until the
  // PR is actually merged under Factory / Merge Oracle authority.
  for (const iss of completedIssues) {
    await safeRunGh([
      "issue",
      "comment",
      iss.id,
      "--body",
      `Sandcastle produced and reviewed \`${iss.branch}\`. Awaiting exact-SHA Factory / Merge Oracle evidence and PR merge; this issue remains open.`,
    ]);
    console.log(`  #${iss.id} remains open pending authoritative PR merge`);
  }

  console.log(
    "\nBatch submitted -- authoritative CI and merge remain pending."
  );
}

console.log("\nAll done.");
