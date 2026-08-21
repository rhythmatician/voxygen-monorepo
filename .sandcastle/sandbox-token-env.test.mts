import { describe, it, expect } from "vitest";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  resolveFactoryMetaApiKey,
  resolveWorkerSandboxEnv,
} from "./sandbox-token-env.mts";

describe("worker sandbox env", () => {
  it("passes GH_TOKEN only for normal production writes", () => {
    expect(resolveWorkerSandboxEnv("read-write", "ghp_live_write", "meta_root")).toEqual({
      GH_TOKEN: "ghp_live_write",
      META_API_KEY: "meta_root",
    });
  });

  it("scrubs write-capable GitHub token for read-only qualification", () => {
    expect(resolveWorkerSandboxEnv("read-only", "ghp_live_write", "meta_root")).toEqual({
      GH_TOKEN: "",
      GITHUB_TOKEN: "",
      META_API_KEY: "meta_root",
    });
  });

  it("does not leak an empty read-write token as a capability path", () => {
    expect(resolveWorkerSandboxEnv("read-only", "", "meta_root")).toEqual({
      GH_TOKEN: "",
      GITHUB_TOKEN: "",
      META_API_KEY: "meta_root",
    });
  });

  it("resolves merger META_API_KEY from the stable factory root, not the batch worktree", async () => {
    const factoryRoot = await mkdtemp(join(tmpdir(), "factory-root-"));
    const batchWorktree = join(
      factoryRoot,
      ".sandcastle",
      "worktrees",
      "sandcastle-batch-151-test",
    );
    await mkdir(join(factoryRoot, ".sandcastle"), { recursive: true });
    await mkdir(batchWorktree, { recursive: true });
    await writeFile(
      join(factoryRoot, ".sandcastle", ".env"),
      "META_API_KEY=meta_from_factory_root\n",
    );

    expect(resolveFactoryMetaApiKey(factoryRoot, {})).toBe(
      "meta_from_factory_root",
    );
    expect(resolveFactoryMetaApiKey(batchWorktree, {})).toBe("");
  });
});
