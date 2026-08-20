import fs from "node:fs";
import path from "node:path";
import type { GitHubCapabilityMode } from "./github-capability.mts";

export function resolveFactoryMetaApiKey(
  factoryRoot: string,
  env: NodeJS.ProcessEnv = process.env,
): string {
  const inherited = env.META_API_KEY?.trim();
  if (inherited) return inherited;

  try {
    const content = fs.readFileSync(
      path.join(factoryRoot, ".sandcastle", ".env"),
      "utf8",
    );
    const match = content.match(/^META_API_KEY=(.*)$/m);
    return match?.[1]?.trim() ?? "";
  } catch {
    return "";
  }
}

export function resolveWorkerSandboxEnv(
  mode: GitHubCapabilityMode,
  token: string,
  metaApiKey: string,
): Record<string, string> {
  if (mode === "read-write") {
    return { GH_TOKEN: token, META_API_KEY: metaApiKey };
  }

  return {
    GH_TOKEN: "",
    GITHUB_TOKEN: "",
    META_API_KEY: metaApiKey,
  };
}
