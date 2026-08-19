import type { GitHubCapabilityMode } from "./github-capability.mts";

export function resolveWorkerSandboxEnv(
  mode: GitHubCapabilityMode,
  token: string,
): Record<string, string> {
  if (mode === "read-write") {
    return { GH_TOKEN: token };
  }

  return {
    GH_TOKEN: "",
    GITHUB_TOKEN: "",
  };
}

