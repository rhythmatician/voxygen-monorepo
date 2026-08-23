import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readFileSync } from "node:fs";

const git = (cwd: string, ...args: string[]) =>
  execFileSync("git", args, { cwd, encoding: "utf8" }).trim();

describe("planner caller isolation", () => {
  it("production planner uses an explicit managed branch strategy", () => {
    const source = readFileSync(".sandcastle/main.mts", "utf8");
    const plannerCall = source.slice(source.indexOf("const planRun = await runStructuredOnce(sandcastle.run,"), source.indexOf("const rawPlanString"));
    expect(plannerCall).toContain('branchStrategy: { type: "branch"');
    expect(plannerCall).toContain('baseBranch: "origin/main"');
  });

  it("a planner setup hook may mutate its worktree without changing caller bytes, ref, or preexisting status", { timeout: 15_000 }, async () => {
    const repo = await mkdtemp(join(tmpdir(), "planner-isolation-"));
    try {
      git(repo, "init", "-q");
      git(repo, "config", "user.email", "test@example.com");
      git(repo, "config", "user.name", "Test");
      await writeFile(join(repo, "package-lock.json"), "caller-lock\n");
      await writeFile(join(repo, "tracked.txt"), "clean\n");
      git(repo, "add", "package-lock.json", "tracked.txt");
      git(repo, "commit", "-qm", "base");
      git(repo, "branch", "-M", "main");
      await writeFile(join(repo, "tracked.txt"), "preexisting dirty state\n");
      await writeFile(join(repo, "caller-only.tmp"), "untracked\n");

      const before = {
        branch: git(repo, "branch", "--show-current"),
        head: git(repo, "rev-parse", "HEAD"),
        ref: git(repo, "rev-parse", "refs/heads/main"),
        status: git(repo, "status", "--porcelain=v2"),
        lock: await readFile(join(repo, "package-lock.json"), "utf8"),
        tracked: await readFile(join(repo, "tracked.txt"), "utf8"),
      };

      const plannerWorktree = join(repo, ".sandcastle", "worktrees", "sandcastle-planner-test");
      git(repo, "worktree", "add", "-b", "sandcastle/planner-test", plannerWorktree, before.head);
      await writeFile(join(plannerWorktree, "package-lock.json"), "worktree mutation");
      git(repo, "worktree", "remove", "--force", plannerWorktree);

      expect(git(repo, "branch", "--show-current")).toBe(before.branch);
      expect(git(repo, "rev-parse", "HEAD")).toBe(before.head);
      expect(git(repo, "rev-parse", "refs/heads/main")).toBe(before.ref);
      expect(git(repo, "status", "--porcelain=v2")).toBe(before.status);
      expect(await readFile(join(repo, "package-lock.json"), "utf8")).toBe(before.lock);
      expect(await readFile(join(repo, "tracked.txt"), "utf8")).toBe(before.tracked);
    } finally {
      await rm(repo, { recursive: true, force: true });
    }
  });
});
