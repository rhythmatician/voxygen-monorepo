export type GitHubCapabilityMode = "read-only" | "read-write";

export type GhCommandKind = "read" | "write" | "unknown";

const ISSUE_READ_COMMANDS = new Set([
  "list",
  "view",
  "reopen",
]);

const ISSUE_WRITE_COMMANDS = new Set([
  "edit",
  "comment",
  "close",
]);

const PR_READ_COMMANDS = new Set([
  "list",
  "view",
]);

const PR_WRITE_COMMANDS = new Set([
  "create",
  "merge",
]);

const API_WRITE_METHODS = new Set(["POST", "PATCH", "PUT", "DELETE"]);

export function classifyGhOperation(args: string[]): GhCommandKind {
  if (!args || args.length === 0) return "unknown";

  const command = args[0];
  const subcommand = args[1];

  if (command === "issue") {
    if (!subcommand) return "read";
    if (ISSUE_WRITE_COMMANDS.has(subcommand)) return "write";
    if (ISSUE_READ_COMMANDS.has(subcommand)) return "read";
    return "unknown";
  }

  if (command === "pr") {
    if (!subcommand) return "read";
    if (PR_WRITE_COMMANDS.has(subcommand)) return "write";
    if (PR_READ_COMMANDS.has(subcommand)) return "read";
    return "unknown";
  }

  if (command === "api") {
    const methodIndex = args.indexOf("--method");
    if (methodIndex !== -1 && methodIndex + 1 < args.length) {
      const method = args[methodIndex + 1].toUpperCase();
      return API_WRITE_METHODS.has(method) ? "write" : "read";
    }
    return "read";
  }

  return "unknown";
}

export class GitHubWriteForbiddenError extends Error {
  readonly command: string[];
  constructor(command: string[]) {
    const cmd = command.join(" ");
    super(`GitHub write operation unavailable in read-only mode: ${cmd}`);
    this.name = "GitHubWriteForbiddenError";
    this.command = [...command];
  }
}

export interface GitHubCapability {
  run: (args: string[]) => Promise<string>;
}

interface Options {
  mode: GitHubCapabilityMode;
  exec: (args: string[]) => Promise<string>;
}

export function makeGitHubCapability({ mode, exec }: Options): GitHubCapability {
  return {
    async run(args: string[]): Promise<string> {
      const kind = classifyGhOperation(args);
      if (mode === "read-only" && kind === "write") {
        throw new GitHubWriteForbiddenError(args);
      }
      if (mode === "read-only" && kind === "unknown") {
        throw new GitHubWriteForbiddenError(args);
      }
      return exec(args);
    },
  };
}