import { execFileSync } from "node:child_process";

type PublishBatchBranchInput<T> = {
  repoRoot: string;
  batchWorktreePath: string;
  batchBranch: string;
  createPullRequest?: () => Promise<T>;
};

export async function publishBatchBranch<T = never>({
  repoRoot,
  batchWorktreePath,
  batchBranch,
  createPullRequest,
}: PublishBatchBranchInput<T>): Promise<{
  localSha: string;
  remoteSha: string;
  pullRequest?: T;
}> {
  try {
    execFileSync("git", ["push", "origin", batchBranch], {
      cwd: batchWorktreePath,
      encoding: "utf8",
      stdio: "pipe",
    });
  } catch (error: unknown) {
    const failure = error as Error & { stderr?: string | Buffer };
    const stderr = failure.stderr?.toString().trim();
    throw new Error(
      `git push origin ${batchBranch} failed: ${stderr || failure.message}`,
      { cause: error },
    );
  }

  const localSha = execFileSync("git", ["rev-parse", batchBranch], {
    cwd: repoRoot,
    encoding: "utf8",
  }).trim();
  const remoteOutput = execFileSync(
    "git",
    ["ls-remote", "--heads", "origin", batchBranch],
    { cwd: repoRoot, encoding: "utf8" },
  ).trim();
  const remoteSha = remoteOutput.split(/\s+/)[0] ?? "";
  if (remoteSha !== localSha) {
    throw new Error(
      `remote batch ref ${batchBranch} did not resolve to local SHA ${localSha} (resolved ${remoteSha || "nothing"})`,
    );
  }

  const pullRequest = createPullRequest
    ? await createPullRequest()
    : undefined;
  return { localSha, remoteSha, pullRequest };
}
