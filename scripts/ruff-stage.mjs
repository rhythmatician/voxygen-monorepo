#!/usr/bin/env node
// lint-staged helper for Python: ruff check --fix-only (residual lint not blocking) + ruff format (must succeed)
// Uses direct ruff binary for reliable exit codes (avoids WSL uv pipe bug).

import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { existsSync } from "node:fs";
import {
  classifyRuffResult,
  isTrustworthyExecutionPath,
} from "./ruff-process.mjs";

const workspace = resolve(import.meta.dirname, "..");
const staged = process.argv.slice(2);
if (staged.length === 0) process.exit(0);
const pyFiles = staged.filter(
  (f) => f.endsWith(".py") && existsSync(resolve(workspace, f))
);
if (pyFiles.length === 0) process.exit(0);

function ruffBinary() {
  const candidates = [
    resolve(workspace, "python/.venv/Scripts/ruff.exe"),
    resolve(workspace, "python/.venv/bin/ruff"),
    resolve(workspace, "python/.venv/Scripts/ruff"),
  ];
  for (const p of candidates) if (existsSync(p)) return p;
  // fallback to uv
  return null;
}

function runRuff(args, files) {
  const ruff = ruffBinary();
  let cmd, cmdArgs;
  if (ruff) {
    // Handle WSL path translation for direct Windows ruff.exe
    const isWsl = workspace.startsWith("/mnt/");
    const needsWslPath = isWsl && ruff.endsWith(".exe");
    let fileArgs = files;
    if (needsWslPath) {
      fileArgs = files.map((f) => {
        const abs = resolve(workspace, f);
        const r = spawnSync("wslpath", ["-w", abs], { encoding: "utf8" });
        if (r.status === 0 && r.stdout) return r.stdout.trim();
        return f;
      });
    }
    cmd = ruff;
    cmdArgs = [...args, ...fileArgs];
    // Direct ruff is trustworthy for exit codes
    if (!isTrustworthyExecutionPath(cmd, workspace)) {
      console.error(
        `[ruff-stage] untrustworthy execution path ${cmd} for ${workspace}, preferring direct ruff`
      );
    }
  } else {
    // fallback: uv run - check trustworthiness
    const isWsl = workspace.startsWith("/mnt/");
    let fileArgs = files;
    if (isWsl) {
      const converted = [];
      for (const f of files) {
        const abs = resolve(workspace, f);
        const r = spawnSync("wslpath", ["-w", abs], { encoding: "utf8" });
        if (r.status === 0 && r.stdout) converted.push(r.stdout.trim());
        else converted.push(f);
      }
      fileArgs = converted;
    }
    cmd = "uv";
    cmdArgs = ["run", "--project", "python", "ruff", ...args, ...fileArgs];
    if (!isTrustworthyExecutionPath(cmd, workspace)) {
      console.error(
        `[ruff-stage] warning: uv execution from WSL may have unreliable exit codes, using direct ruff is preferred`
      );
    }
  }

  const result = spawnSync(cmd, cmdArgs, {
    cwd: workspace,
    encoding: "utf8",
    stdio: "pipe",
  });
  // Debug for lint-staged failures
  if (process.env.DEBUG_RUFF_STAGE) {
    console.error(
      `[ruff-stage] cmd: ${cmd} ${cmdArgs.join(" ")} status: ${result.status} stdout:${JSON.stringify(result.stdout?.slice(0, 200))} stderr:${JSON.stringify(result.stderr?.slice(0, 200))}`
    );
  }
  const classification = classifyRuffResult({
    args,
    status: result.status,
    stdout: result.stdout || "",
    stderr: result.stderr || "",
  });
  // Allowed: residual lint (check --fix-only exit 0) or format success
  if (classification.blocked) {
    if (result.stdout) process.stdout.write(result.stdout);
    if (result.stderr) process.stderr.write(result.stderr);
    console.error(
      `[ruff-stage] ${classification.reason} cmd: ${cmd} ${cmdArgs.join(" ")}`
    );
    process.exit(result.status ?? 1);
  }
}

// Safe lint fixes only, residual lint does NOT block (via --fix-only)
// Formatter must succeed, so we use plain format (no --exit-zero)
runRuff(["check", "--fix-only", "--quiet"], pyFiles);
runRuff(["format", "--quiet"], pyFiles);
