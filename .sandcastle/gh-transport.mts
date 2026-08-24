import { execFile, execFileSync } from "node:child_process";
import { promisify } from "node:util";
import * as fs from "node:fs";
import * as path from "node:path";
import { classifyGhOperation, GitHubWriteForbiddenError } from "./github-capability.mts";

/**
 * gh-transport — the single raw authenticated GitHub transport factory.
 *
 * Sole owner of: gh executable resolution (explicit override > PATH; no
 * personal home-directory knowledge), token resolution (env > .sandcastle/.env),
 * stable repository CWD, max output size, spawn behavior, structured errors,
 * read/write capability enforcement (pre-spawn), claimant identity, and
 * owner/repo resolution.
 *
 * Consumers never know probe paths, token fallback rules, or spawn options.
 */

const execFileAsync = promisify(execFile);

export type GhCapabilityMode = "read-only" | "read-write" | "lifecycle-dependent";

export interface GhTransportOptions {
  /** Stable repository root used as explicit cwd for every spawn. Never ambient CWD. */
  repoRoot: string;
  /**
   * read-only: writes fail before spawning. read-write: everything allowed.
   * lifecycle-dependent: caller asserts capability is decided by factory lifecycle;
   * treated as read-write at this layer (main.mts computes its mode from lifecycle).
   */
  capabilityMode: GhCapabilityMode;
  /** Environment override for token lookup; defaults to process.env. */
  environment?: NodeJS.ProcessEnv;
  /** Explicit gh executable path preferred over PATH resolution. */
  executableOverride?: string;
  /** Maximum stdout buffer in bytes. Default 10 MiB. */
  maxOutputBytes?: number;
}

export interface GhRunResult {
  stdout: string;
}

/**
 * Single capability error type. Extends GitHubWriteForbiddenError so existing
 * instanceof checks in consumers keep working — one taxonomy, not two.
 */
export class GhCapabilityError extends GitHubWriteForbiddenError {
  constructor(command: string[]) {
    super(command);
    this.name = "GhCapabilityError";
  }
}

export class GhExecutableNotFoundError extends Error {
  constructor() {
    super("gh executable not found on PATH and no explicit override provided");
    this.name = "GhExecutableNotFoundError";
  }
}

export class GhTokenMissingError extends Error {
  constructor(envPath: string) {
    super(`gh token not found in GH_TOKEN or ${envPath}`);
    this.name = "GhTokenMissingError";
  }
}

export interface GhStructuredError extends Error {
  command: string[];
  exitCode?: number;
  signal?: string;
  stderr?: string;
  stdout?: string;
}

function toStructuredError(command: string[], error: unknown): GhStructuredError {
  const e = error as { code?: unknown; signal?: unknown; stderr?: unknown; stdout?: unknown; message?: unknown };
  const err = new Error(`gh ${command.join(" ")} failed: ${
    typeof e.stderr === "string" && e.stderr.trim().length > 0
      ? e.stderr.trim()
      : e instanceof Error ? e.message : String(error)
  }`) as GhStructuredError;
  err.name = "GhStructuredError";
  err.command = [...command];
  if (typeof e.code === "number") err.exitCode = e.code;
  if (typeof e.signal === "string") err.signal = e.signal;
  if (e.stderr !== undefined && e.stderr !== null) err.stderr = String(e.stderr);
  if (e.stdout !== undefined && e.stdout !== null) err.stdout = String(e.stdout);
  return err;
}

/** Resolve gh executable: explicit override wins; otherwise PATH lookup only. No personal home paths. */
export function resolveGhExecutable(override?: string): string {
  if (override && override.trim().length > 0) return override.trim();
  return "gh"; // PATH resolution — spawn resolves it; no personal home knowledge here
}

/** Resolve GH_TOKEN from environment first, then .sandcastle/.env under stable repoRoot. */
export function resolveGhToken(repoRoot: string, environment: NodeJS.ProcessEnv = process.env): string {
  const fromEnv = environment.GH_TOKEN;
  if (fromEnv && fromEnv.length > 0) return fromEnv;
  try {
    const envPath = path.join(repoRoot, ".sandcastle", ".env");
    const content = fs.readFileSync(envPath, "utf8");
    const m = content.match(/^GH_TOKEN=(.*)$/m);
    if (m) return m[1].trim();
  } catch {}
  return "";
}

export interface GhTransport {
  /** Capability mode this transport enforces pre-spawn. */
  readonly capabilityMode: GhCapabilityMode;
  /** Resolved claimant login (cached after first successful resolution). */
  resolveClaimantLogin(): Promise<string>;
  /** Repository owner/name resolved from origin remote URL. */
  resolveOwnerRepo(): { owner: string; repo: string } | null;
  /** Run a gh command; enforces capability before any spawn. */
  run(args: string[]): Promise<string>;
  /** Run a gh command, returning success boolean instead of throwing (non-capability errors). */
  tryRun(args: string[]): Promise<boolean>;
  /** True when this command would be rejected as a write under current mode. */
  isWriteForbidden(args: string[]): boolean;
}

export function createGhTransport(options: GhTransportOptions): GhTransport {
  const {
    repoRoot,
    capabilityMode,
    environment = process.env,
    executableOverride,
    maxOutputBytes = 10 * 1024 * 1024,
  } = options;

  const bin = resolveGhExecutable(executableOverride);
  const writesAllowed = capabilityMode !== "read-only";

  let cachedClaimantLogin: string | null = null;

  function isWriteForbidden(args: string[]): boolean {
    if (writesAllowed) return false;
    const kind = classifyGhOperation(args);
    return kind !== "read";
  }

  async function run(args: string[]): Promise<string> {
    // Capability enforcement happens BEFORE any process spawn.
    if (!writesAllowed) {
      const kind = classifyGhOperation(args);
      if (kind === "write" || kind === "unknown") throw new GhCapabilityError(args);
    }
    const token = resolveGhToken(repoRoot, environment);
    if (!token) throw new GhTokenMissingError(path.join(repoRoot, ".sandcastle", ".env"));
    const env = { ...environment, GH_TOKEN: token };
    try {
      const { stdout } = await execFileAsync(bin, args, {
        env,
        cwd: repoRoot,
        maxBuffer: maxOutputBytes,
      });
      return stdout.trim();
    } catch (error: unknown) {
      throw toStructuredError(args, error);
    }
  }

  async function tryRun(args: string[]): Promise<boolean> {
    try {
      await run(args);
      return true;
    } catch (error: unknown) {
      if (error instanceof GhCapabilityError) throw error;
      return false;
    }
  }

  return {
    capabilityMode,
    isWriteForbidden,

    async resolveClaimantLogin(): Promise<string> {
      if (cachedClaimantLogin) return cachedClaimantLogin;
      const candidates = [
        ["api", "user", "--jq", ".login"],
        ["api", "/user", "--jq", ".login"],
      ];
      for (const args of candidates) {
        try {
          const out = await run(args);
          const login = out.trim().replace(/"/g, "");
          if (login && login !== "null" && !login.includes(" ")) {
            cachedClaimantLogin = login;
            return login;
          }
        } catch {}
      }
      throw new Error("failed to resolve claimant login via gh api user");
    },

    resolveOwnerRepo(): { owner: string; repo: string } | null {
      try {
        const out = execFileSyncGit(["remote", "get-url", "origin"], repoRoot).trim();
        const m = out.match(/github\.com[:/]([^/]+)\/([^/.]+?)(?:\.git)?$/);
        if (m) return { owner: m[1], repo: m[2] };
      } catch {}
      return null;
    },

    run,
    tryRun,
  };
}

// Local git runner for owner/repo resolution only — keeps transport free of
// branch-helpers dependency while still using explicit cwd.
function execFileSyncGit(args: string[], cwd?: string): string {
  const out = execFileSync("git", args, { encoding: "utf8", cwd });
  return typeof out === "string" ? out : (out as Buffer).toString();
}

export { GitHubWriteForbiddenError };
