#!/usr/bin/env node
/**
 * Hardened Python file watcher for immediate formatting.
 * - Debounces rapid edits (150ms)
 * - Coalesces multiple files into one batch
 * - Serializes ruff executions (at most one at a time, queue next)
 * - Avoids self-trigger loop (ignore events for 500ms after formatting)
 * - Uses ruff check --fix-only + ruff format (formatter-only, no pyright) to save CPU
 *   since Muse 0.1.0 cannot consume pyright diagnostics via watcher.
 * - Silent on success, diagnostics on failure.
 */

import { watch, existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { WatchScheduler } from "./watcher-scheduler.mjs";

const workspace = resolve(import.meta.dirname, "..");
const pythonDir = resolve(workspace, "python");

const scheduler = new WatchScheduler();
let debounceTimer = null;

function ruffBinary() {
  const cands = [
    resolve(workspace, "python/.venv/Scripts/ruff.exe"),
    resolve(workspace, "python/.venv/bin/ruff"),
    resolve(workspace, "python/.venv/Scripts/ruff"),
  ];
  for (const p of cands) if (existsSync(p)) return p;
  return null;
}

function runRuffBatch(files) {
  if (files.length === 0) return { status: 0, stdout: "", stderr: "" };
  const ruff = ruffBinary();
  let cmd, baseArgs;
  if (ruff) {
    cmd = ruff;
    baseArgs = [];
  } else {
    cmd = "uv";
    baseArgs = ["run", "--project", "python", "ruff"];
  }

  // Use ruff check --fix-only (residual lint not blocking) then ruff format (must succeed)
  const checkArgs = ruff
    ? ["check", "--fix-only", "--quiet", ...files]
    : [...baseArgs, "check", "--fix-only", "--quiet", ...files];
  const formatArgs = ruff
    ? ["format", "--quiet", ...files]
    : [...baseArgs, "format", "--quiet", ...files];

  // For direct ruff, cmd is ruff, args are check/format + files
  // For uv, cmd is uv, args are baseArgs + check/format + files
  const checkCmd = ruff ? ruff : "uv";
  const checkCmdArgs = ruff
    ? ["check", "--fix-only", "--quiet", ...files]
    : [
        "run",
        "--project",
        "python",
        "ruff",
        "check",
        "--fix-only",
        "--quiet",
        ...files,
      ];
  const formatCmd = ruff ? ruff : "uv";
  const formatCmdArgs = ruff
    ? ["format", "--quiet", ...files]
    : ["run", "--project", "python", "ruff", "format", "--quiet", ...files];

  // Run check --fix-only
  let result = spawnSync(checkCmd, checkCmdArgs, {
    cwd: workspace,
    encoding: "utf8",
  });
  // check --fix-only should exit 0 even with leftover lint; any non-zero is tool failure
  if (result.status !== 0) {
    return result;
  }
  // Run format
  result = spawnSync(formatCmd, formatCmdArgs, {
    cwd: workspace,
    encoding: "utf8",
  });
  return result;
}

function scheduleRun() {
  if (scheduler.isRunning) {
    scheduler.needsRerun = true;
    return;
  }
  let batch = [...scheduler.pending];
  scheduler.pending.clear();
  if (batch.length === 0) return;
  batch = batch.filter(existsSync);
  if (batch.length === 0) {
    if (scheduler.needsRerun || scheduler.pending.size > 0) {
      scheduler.needsRerun = false;
      setTimeout(scheduleRun, 10);
    }
    return;
  }
  if (!scheduler.startRun(batch)) return;
  const relFiles = batch.map((abs) => {
    const absNorm = abs.replaceAll("\\", "/");
    const wsNorm = workspace.replaceAll("\\", "/");
    return absNorm.startsWith(wsNorm + "/")
      ? absNorm.slice(wsNorm.length + 1)
      : absNorm;
  });
  const result = runRuffBatch(relFiles);
  if (result.status !== 0) {
    const out = (result.stdout || "") + (result.stderr || "");
    if (out.trim())
      console.error(
        `[qgate-watcher] ${relFiles.join(", ")} failed:\n${out.trim()}`
      );
    else
      console.error(
        `[qgate-watcher] failed for ${relFiles.join(", ")} (exit ${result.status})`
      );
  }
  const fin = scheduler.finishRun(batch, Date.now());
  if (fin.needsRerun) {
    debounceTimer = setTimeout(scheduleRun, 10);
  }
}

function queueFile(file) {
  if (!scheduler.queue(file, Date.now())) return;
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(scheduleRun, scheduler.debounceMs);
}

// Re-export for testing compatibility
export { WatchScheduler } from "./watcher-scheduler.mjs";

if (process.argv.includes("--once")) {
  const result = runRuffBatch(["python/"]);
  if (result.status !== 0) {
    console.error(result.stderr || result.stdout);
    process.exit(result.status || 1);
  }
  process.exit(0);
}

if (existsSync(pythonDir)) {
  try {
    const watcher = watch(pythonDir, { recursive: true }, (event, filename) => {
      if (!filename) return;
      // filename is relative to pythonDir, may be like "voxel_tree/foo.py" or "voxel_tree\\foo.py" on Windows
      const norm = filename.replaceAll("\\", "/");
      if (!isPythonFile(norm)) return;
      const full = resolve(pythonDir, norm);
      // Handle deleted files: if event is rename and file doesn't exist, still queue for check? No, skip if not exists
      // But we should still handle it safely: if file was deleted, pending should be cleared
      if (event === "rename" && !existsSync(full)) {
        pendingFiles.delete(full);
        return;
      }
      queueFile(full);
    });
    console.log(`[qgate-watcher] watching ${pythonDir} (recursive)`);
    const cleanup = () => {
      try {
        watcher.close();
      } catch {}
      if (debounceTimer) clearTimeout(debounceTimer);
      process.exit(0);
    };
    process.on("SIGINT", cleanup);
    process.on("SIGTERM", cleanup);
    process.on("exit", () => {
      try {
        watcher.close();
      } catch {}
    });
  } catch (e) {
    console.error("[qgate-watcher] failed to watch:", e.message);
    process.exit(1);
  }
} else {
  console.error("[qgate-watcher] python dir not found:", pythonDir);
  process.exit(1);
}
