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

export function resolveResearchSandboxEnv(metaApiKey: string): Record<string, string> {
  // Research workers: model/network + repo access, but NO GitHub write credential.
  // All tracker writes occur on host through GitHub capability boundary.
  // Keep behind profile seam for future image/resources override.
  return {
    GH_TOKEN: "",
    GITHUB_TOKEN: "",
    META_API_KEY: metaApiKey,
  };
}

export interface ResearchEnvironmentProfile {
  image?: string;
  env: Record<string, string>;
}

// Profile seam — future research tickets could request different image/resources
// without coupling purpose (wayfinder:research) to executor (Sandcastle).
export function getResearchEnvironment(
  metaApiKey: string,
  _issue?: { number: number; body?: string },
): ResearchEnvironmentProfile {
  return {
    image: "sandcastle:voxygen-monorepo",
    env: resolveResearchSandboxEnv(metaApiKey),
  };
}
