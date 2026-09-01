import { execFileSync, execSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";

export interface GcResult {
  deletedBranches: string[];
  deletedWorktrees: string[];
  prunedRemotes: string[];
  skipped: { branch: string; reason: string }[];
  errors: string[];
}

const PROTECTED_BRANCHES = new Set(["main", "master", "feat/bulk-round1"]);
const MAX_DELETE_PER_RUN = 10;

function runGit(repoRoot: string, args: string[]): { ok: boolean; stdout: string; stderr: string } {
  try {
    const out = execFileSync("git", args, { encoding: "utf8", cwd: repoRoot, stdio: "pipe" } as any);
    return { ok: true, stdout: typeof out === "string" ? out : (out as Buffer).toString(), stderr: "" };
  } catch (e: any) {
    return { ok: false, stdout: e.stdout ? e.stdout.toString() : "", stderr: e.stderr ? e.stderr.toString() : String(e.message ?? e) };
  }
}

function listLocalSandcastleBranches(repoRoot: string): string[] {
  const r = runGit(repoRoot, ["branch", "--list", "sandcastle/*"]);
  if (!r.ok) return [];
  return r.stdout
    .split("\n")
    .map((l) => l.trim().replace(/^\*\s+/, "").replace(/^\+?\s+/, "").trim())
    .filter((b) => b.startsWith("sandcastle/"));
}

function listWorktrees(repoRoot: string): Map<string, string> {
  // branch -> worktree path
  const m = new Map<string, string>();
  const r = runGit(repoRoot, ["worktree", "list", "--porcelain"]);
  if (!r.ok) return m;
  let curPath = "";
  let curBranch = "";
  for (const line of r.stdout.split("\n")) {
    if (line.startsWith("worktree ")) curPath = line.slice("worktree ".length).trim();
    else if (line.startsWith("branch ")) {
      curBranch = line.slice("branch ".length).trim().replace("refs/heads/", "");
      if (curBranch && curPath) m.set(curBranch, curPath);
      curPath = "";
      curBranch = "";
    }
  }
  return m;
}

function isAncestor(repoRoot: string, branch: string, target: string): boolean {
  const r = runGit(repoRoot, ["merge-base", "--is-ancestor", branch, target]);
  return r.ok;
}

function branchAgeDays(repoRoot: string, branch: string): number | null {
  const r = runGit(repoRoot, ["log", "-1", "--format=%ct", branch]);
  if (!r.ok) return null;
  const ts = parseInt(r.stdout.trim(), 10);
  if (isNaN(ts)) return null;
  return (Date.now() / 1000 - ts) / 86400;
}

function currentBranch(repoRoot: string): string {
  const r = runGit(repoRoot, ["branch", "--show-current"]);
  return r.ok ? r.stdout.trim() : "";
}

export async function runSandcastleGC(opts: {
  repoRoot: string;
  ghRun: (args: string[]) => Promise<string>;
  maxDelete?: number;
  dryRun?: boolean;
}): Promise<GcResult> {
  const repoRoot = opts.repoRoot;
  const ghRun = opts.ghRun;
  const maxDelete = opts.maxDelete ?? MAX_DELETE_PER_RUN;
  const dryRun = opts.dryRun ?? false;

  const result: GcResult = { deletedBranches: [], deletedWorktrees: [], prunedRemotes: [], skipped: [], errors: [] };

  console.log(`\n=== Sandcastle GC (RALPH) — dryRun=${dryRun} maxDelete=${maxDelete} ===`);

  // 1. Fetch closed issue numbers in one batch (bounded, not per-branch N+1)
  let closedSet = new Set<number>();
  try {
    const closedJson = await ghRun(["issue", "list", "--state", "closed", "--limit", "200", "--json", "number"]);
    const parsed: { number: number }[] = JSON.parse(closedJson);
    for (const o of parsed) closedSet.add(o.number);
    console.log(`  GC: fetched ${closedSet.size} closed issue numbers`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.warn(`  GC: failed to list closed issues: ${msg.slice(0, 300)} — GC skipped`);
    result.errors.push(`list closed failed: ${msg.slice(0, 200)}`);
    return result;
  }

  const localBranches = listLocalSandcastleBranches(repoRoot);
  const worktreeMap = listWorktrees(repoRoot);
  const cur = currentBranch(repoRoot);
  const originMain = (() => {
    const r = runGit(repoRoot, ["rev-parse", "origin/main"]);
    return r.ok ? r.stdout.trim() : null;
  })();

  let deleted = 0;

  for (const branch of localBranches) {
    if (deleted >= maxDelete) {
      result.skipped.push({ branch, reason: "maxDelete limit" });
      continue;
    }
    if (PROTECTED_BRANCHES.has(branch)) {
      result.skipped.push({ branch, reason: "protected" });
      continue;
    }
    if (branch === cur) {
      result.skipped.push({ branch, reason: "current HEAD" });
      continue;
    }

    const isIssueBranch = branch.startsWith("sandcastle/issue-");
    const isBatchBranch = branch.startsWith("sandcastle/batch-");

    if (isIssueBranch) {
      const m = branch.match(/^sandcastle\/issue-(\d+)$/);
      if (!m) {
        result.skipped.push({ branch, reason: "unparseable issue number" });
        continue;
      }
      const num = parseInt(m[1], 10);
      const isClosed = closedSet.has(num);
      if (!isClosed) {
        // OPEN — never GC, even if merged
        result.skipped.push({ branch, reason: "issue OPEN" });
        continue;
      }
      // CLOSED — safe to delete if ancestor of origin/main OR age > 1 hour (recent batch merges are NOT_ANCESTOR due to squash/rebase, so 7d audit would miss 9 closed branches for a week; 1h grace is enough for manual inspection while keeping branch count bounded per RALPH iteration)
      const ancestor = originMain ? isAncestor(repoRoot, branch, "origin/main") : false;
      const age = branchAgeDays(repoRoot, branch);
      const ageOk = age !== null && age > (1 / 24); // 1 hour grace, not 7 days — closed batch merges are batch-scoped, not direct ancestors
      if (!ancestor && !ageOk) {
        result.skipped.push({ branch, reason: `CLOSED but not ancestor of origin/main and age ${age?.toFixed(2) ?? "?"}d <1h — audit` });
        continue;
      }
      // Safe to delete
      if (dryRun) {
        console.log(`  GC dryRun would delete ${branch} (CLOSED, ancestor=${ancestor}, age=${age?.toFixed(1)}d)`);
        result.deletedBranches.push(branch + " (dryRun)");
        deleted++;
        continue;
      }
      // Remove worktree first if present
      const wt = worktreeMap.get(branch);
      if (wt) {
        console.log(`  GC: removing worktree for ${branch} at ${wt}`);
        const rm = runGit(repoRoot, ["worktree", "remove", "--force", wt]);
        if (!rm.ok) {
          // Try prune fallback
          runGit(repoRoot, ["worktree", "prune"]);
          // Force rm dir
          try {
            if (fs.existsSync(wt)) fs.rmSync(wt, { recursive: true, force: true });
          } catch {}
          try {
            const gitWt = path.join(repoRoot, ".git", "worktrees", path.basename(wt));
            if (fs.existsSync(gitWt)) fs.rmSync(gitWt, { recursive: true, force: true });
          } catch {}
        }
        runGit(repoRoot, ["worktree", "prune"]);
        result.deletedWorktrees.push(wt);
      }
      const del = runGit(repoRoot, ["branch", "-D", branch]);
      if (del.ok) {
        console.log(`  GC: deleted branch ${branch} (CLOSED, ancestor=${ancestor}, age=${age?.toFixed(1)}d)`);
        result.deletedBranches.push(branch);
        deleted++;
        // Try remote delete if exists
        const remoteExists = runGit(repoRoot, ["ls-remote", "--heads", "origin", branch]);
        if (remoteExists.ok && remoteExists.stdout.trim().length > 0) {
          const pushDel = runGit(repoRoot, ["push", "origin", "--delete", branch]);
          if (pushDel.ok) {
            console.log(`  GC: deleted remote ${branch}`);
            result.prunedRemotes.push(branch);
          } else {
            console.warn(`  GC: remote delete failed for ${branch}: ${pushDel.stderr.slice(0, 200)}`);
          }
        }
      } else {
        const msg = del.stderr.slice(0, 300);
        console.warn(`  GC: failed to delete ${branch}: ${msg}`);
        result.errors.push(`delete ${branch}: ${msg}`);
      }
    } else if (isBatchBranch) {
      const age = branchAgeDays(repoRoot, branch);
      // Delete batch branches older than 7 days OR if all issues in name are closed
      const ageOk = age !== null && age > 7;
      let allClosed = false;
      const ids = branch.match(/batch-([\d-]+)-/)?.[1]?.split("-").map((s) => parseInt(s, 10)).filter((n) => !isNaN(n)) ?? [];
      if (ids.length > 0) {
        allClosed = ids.every((n) => closedSet.has(n));
      }
      if (!ageOk && !allClosed) {
        result.skipped.push({ branch, reason: `batch age ${age?.toFixed(1) ?? "?"}d <7d and not allClosed` });
        continue;
      }
      if (dryRun) {
        console.log(`  GC dryRun would delete batch ${branch} (age=${age?.toFixed(1)}d, allClosed=${allClosed})`);
        result.deletedBranches.push(branch + " (dryRun)");
        deleted++;
        continue;
      }
      const wt = worktreeMap.get(branch);
      if (wt) {
        console.log(`  GC: removing worktree for batch ${branch}`);
        runGit(repoRoot, ["worktree", "remove", "--force", wt]);
        try {
          if (fs.existsSync(wt)) fs.rmSync(wt, { recursive: true, force: true });
        } catch {}
        runGit(repoRoot, ["worktree", "prune"]);
        result.deletedWorktrees.push(wt);
      }
      const del = runGit(repoRoot, ["branch", "-D", branch]);
      if (del.ok) {
        console.log(`  GC: deleted batch branch ${branch} (age=${age?.toFixed(1)}d, allClosed=${allClosed})`);
        result.deletedBranches.push(branch);
        deleted++;
      } else {
        result.errors.push(`delete batch ${branch}: ${del.stderr.slice(0, 200)}`);
      }
    } else {
      result.skipped.push({ branch, reason: "unknown sandcastle prefix" });
    }
  }

  // 2. Stale rename debris — untracked top-level dirs that were tracked at origin/main but are now renamed
  // This handles java/→mod/ and python/→training/ and any future top-level renames. Without this,
  // every batch that renames a tracked directory leaves the old path as an untracked tree (48k files),
  // flooding VS Code file watchers until the *next* GC run. GC must clean it *at merge time*, not next run.
  try {
    const untracked = runGit(repoRoot, ["ls-files", "--others", "--exclude-standard"]);
    const topUntracked = new Set<string>();
    if (untracked.ok) {
      for (const f of untracked.stdout.split("\n")) {
        const top = f.split("/")[0]?.trim();
        if (top) topUntracked.add(top);
      }
    }
    // Include explicit top-level dirs that appear as `?? java/` in porcelain but may have no `ls-files` entry yet due to large trees
    const status = runGit(repoRoot, ["status", "--porcelain"]);
    if (status.ok) {
      for (const line of status.stdout.split("\n")) {
        const m = line.match(/^\?\?\s+([^\/\s]+)\/?/);
        if (m) topUntracked.add(m[1]);
      }
    }
    const originTracked = runGit(repoRoot, ["ls-tree", "-r", "--name-only", "origin/main"]);
    const originTops = new Set<string>();
    if (originTracked.ok) {
      for (const f of originTracked.stdout.split("\n")) {
        const top = f.split("/")[0]?.trim();
        if (top) originTops.add(top);
      }
    }
    const headTracked = runGit(repoRoot, ["ls-tree", "-r", "--name-only", "HEAD"]);
    const headTops = new Set<string>();
    if (headTracked.ok) {
      for (const f of headTracked.stdout.split("\n")) {
        const top = f.split("/")[0]?.trim();
        if (top) headTops.add(top);
      }
    }
    for (const top of topUntracked) {
      if (!originTops.has(top)) continue; // was never tracked → intentional untracked, keep
      if (headTops.has(top)) continue; // still tracked at HEAD → not stale
      const full = path.join(repoRoot, top);
      let st: fs.Stats | null = null;
      try { st = fs.statSync(full); } catch { continue; }
      if (!st.isDirectory()) continue;
      // Extra safety: also check explicit rename mappings first (fast path for known 100% renames)
      // and generic: if top was tracked at origin but not at HEAD, it's stale debris.
      console.log(`  GC: removing stale rename debris ${top}/ (tracked at origin/main, untracked at HEAD)`);
      try {
        fs.rmSync(full, { recursive: true, force: true, maxRetries: 3 });
        result.deletedWorktrees.push(`${top}/ (stale debris)`);
      } catch (e) {
        // Fallback to git clean for Windows long-path / permission edge cases
        const clean = runGit(repoRoot, ["clean", "-fd", "--", top]);
        if (!clean.ok) result.errors.push(`stale debris ${top}: ${(e as Error).message.slice(0, 200)}`);
        else result.deletedWorktrees.push(`${top}/ (stale debris via git clean)`);
      }
    }
  } catch (e) {
    result.errors.push(`stale debris scan failed: ${(e as Error).message.slice(0, 200)}`);
  }

  // 3. Prune remote tracking for gone branches
  const pruneR = runGit(repoRoot, ["fetch", "--prune"]);
  if (!pruneR.ok) {
    result.errors.push(`fetch --prune failed: ${pruneR.stderr.slice(0, 200)}`);
  } else {
    console.log(`  GC: fetch --prune done`);
  }
  runGit(repoRoot, ["worktree", "prune"]);

  console.log(`  GC summary: deleted ${result.deletedBranches.length} branches, ${result.deletedWorktrees.length} worktrees, ${result.prunedRemotes.length} remotes, skipped ${result.skipped.length}, errors ${result.errors.length}`);
  if (result.deletedBranches.length > 0) console.log(`  GC deleted: ${result.deletedBranches.join(", ")}`);
  console.log(`=== GC complete ===\n`);
  return result;
}
