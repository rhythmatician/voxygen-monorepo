/**
 * Deterministic process-result seam for ruff-stage.
 * Classifies Ruff subprocess results into "allow" vs "block" for pre-commit.
 * Pure functions - no spawn, no filesystem.
 */

export type RuffResult = {
  args: string[]; // e.g. ["check","--fix-only","--quiet",...] or ["format","--quiet",...]
  status: number | null; // null means signal termination
  stdout: string;
  stderr: string;
};

export type Classification = {
  blocked: boolean;
  reason: string;
};

/**
 * Classify a Ruff check result for pre-commit.
 * - For `check --fix-only`: status 0 => allowed (even with residual lint, ruff exits 0)
 *                           status !=0 => blocked (tool/config/executable failure)
 * - For `check --fix` (old): status 0 => allowed, status 1 with lint findings => allowed if policy is formatter-only,
 *                            but we now use --fix-only, so this path should not be used. For safety, treat non-zero as blocked unless proven lint-only.
 * - For `format`: any non-zero => blocked
 */
export function classifyRuffResult(result: RuffResult): Classification {
  const isCheck = result.args.includes("check");
  const isFormat = result.args.includes("format");
  const isFixOnly = result.args.includes("--fix-only");

  // Null status (killed by signal) is always a failure
  if (result.status === null) {
    return { blocked: true, reason: "ruff terminated by signal" };
  }

  if (isFormat) {
    if (result.status !== 0)
      return {
        blocked: true,
        reason: `ruff format failed with exit ${result.status}`,
      };
    return { blocked: false, reason: "format ok" };
  }

  if (isCheck) {
    if (isFixOnly) {
      // --fix-only: ruff exits 0 even with residual lint; non-zero is real tool failure
      if (result.status !== 0)
        return {
          blocked: true,
          reason: `ruff check --fix-only failed with exit ${result.status}`,
        };
      return {
        blocked: false,
        reason: "check --fix-only ok (residual lint allowed)",
      };
    }
    // Legacy --fix without --fix-only: would exit 1 for residual lint, which we want to allow for formatter-only pre-commit
    // But old permissive code swallowed all non-zero unless E902, which hid tool failures.
    // For new code we use --fix-only, so this branch should not be hit; treat non-zero as blocked to be safe,
    // unless caller explicitly opts into allowing residual lint.
    if (result.status !== 0)
      return {
        blocked: true,
        reason: `ruff check failed with exit ${result.status}`,
      };
    return { blocked: false, reason: "check ok" };
  }

  // Unknown command - treat non-zero as blocked
  if (result.status !== 0)
    return {
      blocked: true,
      reason: `ruff ${result.args[0] ?? "unknown"} failed`,
    };
  return { blocked: false, reason: "ok" };
}

/**
 * Old permissive implementation would have been:
 * if (status !== 0) { if (stderr.includes("E902")) block else allow }
 * This is wrong because it allows config/tool/format failures that don't contain E902.
 */
export function oldPermissiveWouldBlock(result: RuffResult): boolean {
  if (result.status === 0) return false;
  const output = (result.stdout + result.stderr).toLowerCase();
  return output.includes("e902");
}

/**
 * WSL/uv result classification seam
 * Determines if an execution path is trustworthy for exit codes.
 * Direct ruff binary is trustworthy; Windows uv.exe via WSL pipe is not.
 */
export function isTrustworthyExecutionPath(
  cmd: string,
  workspace: string
): boolean {
  // Direct ruff is trustworthy
  if (cmd.endsWith("ruff") || cmd.endsWith("ruff.exe")) return true;
  // Linux uv in WSL is trustworthy if it's not Windows uv.exe
  if (cmd === "uv" && !workspace.startsWith("/mnt/")) return true;
  // Windows uv.exe via WSL is not trustworthy for exit codes due to pipe bug
  if (cmd === "uv" && workspace.startsWith("/mnt/")) return false;
  // WSL path with Windows uv is not trustworthy
  if (cmd.includes("uv.exe")) return false;
  return false;
}
