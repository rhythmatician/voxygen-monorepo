#!/usr/bin/env node
/**
 * Voxygen-side Muse/Codex PostToolUse adapter for qgate (Node).
 * Reads JSON payload from stdin, identifies Python files, runs qgate --fix.
 * Silent on success, diagnostics on failure. Handles WSL path translation.
 */
import { readFileSync, existsSync, statSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve, relative, isAbsolute } from "node:path";

const workspace = resolve(import.meta.dirname, "..");

// Find git root (workspace may be . or parent)
let ws = workspace;
for (let i = 0; i < 3; i++) {
  if (existsSync(ws + "/.git")) break;
  const parent = ws.split("/").slice(0, -1).join("/") || "/";
  if (parent === ws) break;
  ws = parent;
}

function wslToWindows(p) {
  if (typeof p !== "string") return p;
  if (
    p.startsWith("/mnt/") &&
    p.length > 6 &&
    p[6] === "/" &&
    /^[a-zA-Z]$/.test(p[5])
  ) {
    const drive = p[5].toUpperCase();
    const rest = p.slice(7).replaceAll("/", "\\");
    return `${drive}:\\${rest}`;
  }
  return p;
}

const EXCLUDED = new Set([
  ".git",
  ".mypy_cache",
  ".pytest_cache",
  ".ruff_cache",
  ".venv",
  ".venv-pip-backup",
  "artifacts",
  "tmp",
  ".sandcastle",
  "node_modules",
  ".codex",
  "graphify-out",
]);

function isExcluded(absPath, ws) {
  try {
    const wsNorm = ws.replaceAll("\\", "/");
    const candNorm = absPath.replaceAll("\\", "/");
    if (!candNorm.startsWith(wsNorm)) return true;
    const rel = candNorm.slice(wsNorm.length).split("/").filter(Boolean);
    if (rel.some((part) => EXCLUDED.has(part))) return true;
  } catch {
    return true;
  }
  return false;
}

function isWithinWorkspace(absPath, ws) {
  const absNorm = absPath.replaceAll("\\", "/");
  const wsNorm = ws.replaceAll("\\", "/");
  return absNorm === wsNorm || absNorm.startsWith(wsNorm + "/");
}

function workingDir(reported, ws) {
  if (!reported) return ws;
  let r = wslToWindows(reported);
  let p = r;
  if (!isAbsolute(p) && !/^[A-Z]:[\\/]/.test(p)) {
    p = ws + "/" + p;
  }
  try {
    const resolved = resolve(p);
    if (!isWithinWorkspace(resolved, ws)) return ws;
    try {
      if (statSync(resolved).isDirectory()) return resolved;
      // if it's a file, return its dir? but we want dir
      return ws;
    } catch {
      return ws;
    }
  } catch {
    return ws;
  }
}

function stringValues(v) {
  if (typeof v === "string") return [v];
  if (Array.isArray(v)) return v.flatMap(stringValues);
  return [];
}

function payloadPaths(payload) {
  const paths = [];
  let toolInput =
    payload.tool_input ||
    payload.toolInput ||
    payload.toolArgs ||
    payload.tool_args ||
    payload.input ||
    payload.args ||
    payload.params;
  if (toolInput && typeof toolInput === "object" && !Array.isArray(toolInput)) {
    for (const k of [
      "path",
      "file_path",
      "target_file",
      "paths",
      "file",
      "filename",
    ]) {
      const val = toolInput[k];
      if (val != null) paths.push(...stringValues(val));
    }
    if (
      typeof toolInput.command === "string" &&
      String(payload.tool_name || payload.toolName || "").includes(
        "apply_patch"
      )
    ) {
      const re =
        /^\*\*\*\s+(?:(?:Add|Delete|Update)\s+File|Move\s+to):\s*(.+?)\s*$/gm;
      let m;
      while ((m = re.exec(toolInput.command)) !== null) paths.push(m[1].trim());
    }
  }
  for (const k of ["path", "file_path"]) {
    if (typeof payload[k] === "string") paths.push(payload[k]);
  }
  if (payload.tool && typeof payload.tool === "object") {
    for (const k of ["path", "file_path"]) {
      if (typeof payload.tool[k] === "string") paths.push(payload.tool[k]);
    }
  }
  return paths;
}

function main() {
  let raw = "";
  try {
    raw = readFileSync(0, "utf8");
  } catch {}
  if (!raw.trim()) process.exit(0);
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    process.exit(0);
  }
  if (typeof payload !== "object" || payload === null) process.exit(0);

  const candidatePaths = payloadPaths(payload);
  if (candidatePaths.length === 0) process.exit(0);

  const reportedCwd =
    payload.cwd ||
    payload.workdir ||
    payload.workspace ||
    payload.invocation?.cwd ||
    null;
  const base = workingDir(reportedCwd, ws);

  const targets = [];
  for (const rawPath of candidatePaths) {
    let txt = wslToWindows(rawPath.trim().replace(/^["']|["']$/g, ""));
    if (!txt) continue;
    let p = txt;
    if (!isAbsolute(p) && !/^[A-Z]:[\\/]/.test(p)) {
      p = base + "/" + p;
    }
    let abs;
    try {
      abs = resolve(p);
    } catch {
      continue;
    }
    if (!isWithinWorkspace(abs, ws)) continue;
    if (isExcluded(abs, ws)) continue;
    if (!abs.toLowerCase().endsWith(".py")) continue;
    if (!existsSync(abs)) continue;
    targets.push(abs);
  }
  if (targets.length === 0) process.exit(0);
  const unique = [...new Set(targets)];
  const relFiles = unique.map((abs) => {
    const absNorm = abs.replaceAll("\\", "/");
    const wsNorm = ws.replaceAll("\\", "/");
    return absNorm.startsWith(wsNorm + "/")
      ? absNorm.slice(wsNorm.length + 1)
      : absNorm;
  });

  const result = spawnSync(
    "uv",
    ["run", "--project", "python", "qgate", "--fix", ...relFiles],
    {
      cwd: ws,
      encoding: "utf8",
    }
  );

  const output = (result.stdout || "") + (result.stderr || "");
  if (result.status === 0 && !output.includes("[QUALITY GATE FAILED")) {
    process.exit(0);
  }
  // Failure: forward diagnostics, ensure non-zero exit for hook to surface
  if (output.trim()) process.stderr.write(output);
  else
    process.stderr.write(`[qgate] check failed for: ${relFiles.join(", ")}\n`);
  process.exit(result.status || 1);
}

main();
