/**
 * Deterministic process-result seam for ruff-stage - JS runtime
 * Keep in sync with scripts/ruff-process.mts
 */

export function classifyRuffResult(result) {
  const isCheck = result.args.includes("check");
  const isFormat = result.args.includes("format");
  const isFixOnly = result.args.includes("--fix-only");

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
    if (result.status !== 0)
      return {
        blocked: true,
        reason: `ruff check failed with exit ${result.status}`,
      };
    return { blocked: false, reason: "check ok" };
  }

  if (result.status !== 0)
    return {
      blocked: true,
      reason: `ruff ${result.args[0] ?? "unknown"} failed`,
    };
  return { blocked: false, reason: "ok" };
}

export function oldPermissiveWouldBlock(result) {
  if (result.status === 0) return false;
  const output = (result.stdout + result.stderr).toLowerCase();
  return output.includes("e902");
}

export function isTrustworthyExecutionPath(cmd, workspace) {
  if (cmd.endsWith("ruff") || cmd.endsWith("ruff.exe")) return true;
  if (cmd === "uv" && !workspace.startsWith("/mnt/")) return true;
  if (cmd === "uv" && workspace.startsWith("/mnt/")) return false;
  if (cmd.includes("uv.exe")) return false;
  return false;
}
