import { execSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";

export function doctorWorktreePath(branch: string): string {
  return path.join(process.cwd(), ".sandcastle", "worktrees", branch);
}

export function cleanupDoctorBranchAndWorktree(branch: string): void {
  if (!branch.startsWith("doctor-")) return;
  const worktreePath = doctorWorktreePath(branch);
  try {
    const list = execSync('git worktree list --porcelain', { encoding: 'utf8', stdio: 'pipe' });
    if (list.includes(worktreePath) || list.includes(branch)) {
      try { execSync(`git worktree remove --force "${worktreePath}"`, { encoding: 'utf8', stdio: 'pipe' }); } catch {}
    }
  } catch {}
  try { execSync(`git worktree remove --force "${worktreePath}"`, { encoding: 'utf8', stdio: 'pipe' }); } catch {}
  try { if (fs.existsSync(worktreePath)) fs.rmSync(worktreePath, { recursive: true, force: true }); } catch {}
  try { execSync('git worktree prune', { encoding: 'utf8', stdio: 'pipe' }); } catch {}
  try { execSync(`git branch -D ${branch}`, { encoding: 'utf8', stdio: 'pipe' }); } catch {}
  try { execSync('git worktree prune', { encoding: 'utf8', stdio: 'pipe' }); } catch {}
}

export async function reconcileStaleDoctorResources(): Promise<void> {
  const getErrorMessage = (e: unknown) => e instanceof Error ? e.message : String(e);
  console.log("  [doctor] reconciling stale doctor-* worktrees/branches (startup)...");
  let worktreeList = "";
  try { worktreeList = execSync('git worktree list --porcelain', { encoding: 'utf8', stdio: 'pipe' }); } catch {}
  const registeredDoctorPaths: string[] = [];
  for (const line of worktreeList.split("\n")) {
    if (line.startsWith("worktree ")) {
      const p = line.slice("worktree ".length).trim();
      if (p.includes(".sandcastle/worktrees/doctor-")) registeredDoctorPaths.push(p);
    }
  }
  for (const wp of registeredDoctorPaths) {
    console.log(`  [doctor] removing stale registered worktree ${wp}`);
    try { execSync(`git worktree remove --force "${wp}"`, { encoding: 'utf8', stdio: 'pipe' }); console.log(`  [doctor] removed worktree ${wp}`); } catch (e) { console.warn(`  [doctor] worktree remove failed for ${wp}: ${getErrorMessage(e)}`); }
    try { if (fs.existsSync(wp)) fs.rmSync(wp, { recursive: true, force: true }); } catch {}
  }
  try {
    const base = path.join(process.cwd(), ".sandcastle", "worktrees");
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
  try { execSync('git worktree prune', { encoding: 'utf8', stdio: 'pipe' }); } catch {}
  let branches = "";
  try { branches = execSync("git branch --list 'doctor-*'", { encoding: 'utf8', stdio: 'pipe' }); } catch {}
  const branchNames = branches.split("\n").map((s) => s.trim().replace(/^\*\s+/, "").trim()).filter((s) => s && s.startsWith("doctor-"));
  for (const b of branchNames) {
    console.log(`  [doctor] deleting stale branch ${b}`);
    cleanupDoctorBranchAndWorktree(b);
  }
  try { execSync('git worktree prune', { encoding: 'utf8', stdio: 'pipe' }); } catch {}
  if (registeredDoctorPaths.length === 0 && branchNames.length === 0) console.log("  [doctor] no stale doctor-* resources found");
}
