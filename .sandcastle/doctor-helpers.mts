import { execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";

export function doctorWorktreePath(repoRoot: string, branch: string): string {
  return path.join(repoRoot, ".sandcastle", "worktrees", branch);
}

function withRepoRootCwd<T>(repoRoot: string, action: () => T): T {
  const originalCwd = process.cwd();
  let didRestore = false;
  try {
    if (originalCwd !== repoRoot) {
      process.chdir(repoRoot);
      didRestore = true;
    }
    return action();
  } finally {
    if (didRestore) {
      try { process.chdir(originalCwd); } catch {}
    }
  }
}

export function cleanupDoctorBranchAndWorktree(repoRoot: string, branch: string): void {
  if (!branch.startsWith("doctor-")) return;
  const worktreePath = doctorWorktreePath(repoRoot, branch);
  // Prefer argument-based invocation (execFileSync) — no shell interpolation for paths/refnames
  withRepoRootCwd(repoRoot, () => {
    try {
      const list = execFileSync("git", ["worktree", "list", "--porcelain"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
      if (list.includes(worktreePath) || list.includes(branch)) {
        try { execFileSync("git", ["worktree", "remove", "--force", worktreePath], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
      }
    } catch {}
    try { execFileSync("git", ["worktree", "remove", "--force", worktreePath], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
    try { if (fs.existsSync(worktreePath)) fs.rmSync(worktreePath, { recursive: true, force: true }); } catch {}
    try { execFileSync("git", ["worktree", "prune"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
    try { execFileSync("git", ["branch", "-D", branch], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
    try { execFileSync("git", ["worktree", "prune"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
  });
}

export function assertNoStaleDoctorResources(repoRoot: string): { ok: boolean; leftover: string[] } {
  const leftover: string[] = [];
  const getErrorMessage = (e: unknown) => e instanceof Error ? e.message : String(e);
  let worktreeOk = false;
  let branchOk = false;

  let worktreeList = "";
  try {
    worktreeList = execFileSync("git", ["worktree", "list", "--porcelain"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
    worktreeOk = true;
  } catch (e) {
    leftover.push(`inspection-error: git worktree list failed: ${getErrorMessage(e).slice(0, 500)}`);
  }
  if (worktreeOk) {
    for (const line of worktreeList.split("\n")) {
      if (line.startsWith("worktree ")) {
        const p = line.slice("worktree ".length).trim();
        if (p.includes(".sandcastle/worktrees/doctor-")) leftover.push(`worktree:${p}`);
      }
    }
  }

  // Filesystem inspection — readdir must succeed if base exists
  try {
    const base = path.join(repoRoot, ".sandcastle", "worktrees");
    if (fs.existsSync(base)) {
      let entries: string[] = [];
      try {
        entries = fs.readdirSync(base);
      } catch (e) {
        leftover.push(`inspection-error: filesystem readdir failed: ${getErrorMessage(e).slice(0, 500)}`);
        entries = [];
      }
      for (const entry of entries) {
        if (entry.startsWith("doctor-")) {
          const full = path.join(base, entry);
          try {
            if (fs.existsSync(full)) leftover.push(`dir:${full}`);
          } catch (e) {
            leftover.push(`inspection-error: filesystem existsSync failed for ${full}: ${getErrorMessage(e).slice(0, 500)}`);
          }
        }
      }
    }
  } catch (e) {
    leftover.push(`inspection-error: filesystem inspection failed: ${getErrorMessage(e).slice(0, 500)}`);
  }

  let branches = "";
  try {
    branches = execFileSync("git", ["branch", "--list", "doctor-*"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot });
    branchOk = true;
  } catch (e) {
    leftover.push(`inspection-error: git branch --list failed: ${getErrorMessage(e).slice(0, 500)}`);
  }
  if (branchOk) {
    const branchNames = branches.split("\n").map((s) => s.trim().replace(/^\*\s+/, "").trim()).filter((s) => s && s.startsWith("doctor-"));
    for (const b of branchNames) leftover.push(`branch:${b}`);
  }

  return { ok: leftover.length === 0, leftover };
}

export async function reconcileStaleDoctorResources(repoRoot: string): Promise<void> {
  const getErrorMessage = (e: unknown) => e instanceof Error ? e.message : String(e);
  console.log("  [doctor] reconciling stale doctor-* worktrees/branches (startup)...");
  let worktreeList = "";
  try { worktreeList = execFileSync("git", ["worktree", "list", "--porcelain"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
  const registeredDoctorPaths: string[] = [];
  for (const line of worktreeList.split("\n")) {
    if (line.startsWith("worktree ")) {
      const p = line.slice("worktree ".length).trim();
      if (p.includes(".sandcastle/worktrees/doctor-")) registeredDoctorPaths.push(p);
    }
  }
  for (const wp of registeredDoctorPaths) {
    console.log(`  [doctor] removing stale registered worktree ${wp}`);
    try { execFileSync("git", ["worktree", "remove", "--force", wp], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); console.log(`  [doctor] removed worktree ${wp}`); } catch (e) { console.warn(`  [doctor] worktree remove failed for ${wp}: ${getErrorMessage(e)}`); }
    try { if (fs.existsSync(wp)) fs.rmSync(wp, { recursive: true, force: true }); } catch {}
  }
  try {
    const base = path.join(repoRoot, ".sandcastle", "worktrees");
    if (fs.existsSync(base)) {
      for (const entry of fs.readdirSync(base)) {
        if (entry.startsWith("doctor-")) {
          const full = path.join(base, entry);
          if (!registeredDoctorPaths.includes(full) && fs.existsSync(full)) {
            try { if (fs.statSync(full).isDirectory()) { console.log(`  [doctor] removing stale doctor directory (not in worktree list) ${full}`); fs.rmSync(full, { recursive: true, force: true }); } } catch (e) { console.warn(`  [doctor] rm failed for ${full}: ${getErrorMessage(e)}`); }
          }
        }
      }
    }
  } catch {}
  try { execFileSync("git", ["worktree", "prune"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
  let branches = "";
  try { branches = execFileSync("git", ["branch", "--list", "doctor-*"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
  const branchNames = branches.split("\n").map((s) => s.trim().replace(/^\*\s+/, "").trim()).filter((s) => s && s.startsWith("doctor-"));
  for (const b of branchNames) {
    console.log(`  [doctor] deleting stale branch ${b}`);
    cleanupDoctorBranchAndWorktree(repoRoot, b);
  }
  try { execFileSync("git", ["worktree", "prune"], { encoding: "utf8", stdio: "pipe", cwd: repoRoot }); } catch {}
  if (registeredDoctorPaths.length === 0 && branchNames.length === 0) console.log("  [doctor] no stale doctor-* resources found");
}
