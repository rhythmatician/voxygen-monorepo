import { describe, it, expect } from "vitest";
import { resolveWorkerSandboxEnv } from "./sandbox-token-env.mts";

describe("worker sandbox env", () => {
  it("passes GH_TOKEN only for normal production writes", () => {
    expect(resolveWorkerSandboxEnv("read-write", "ghp_live_write")).toEqual({
      GH_TOKEN: "ghp_live_write",
    });
  });

  it("scrubs write-capable GitHub token for read-only qualification", () => {
    expect(resolveWorkerSandboxEnv("read-only", "ghp_live_write")).toEqual({
      GH_TOKEN: "",
      GITHUB_TOKEN: "",
    });
  });

  it("does not leak an empty read-write token as a capability path", () => {
    expect(resolveWorkerSandboxEnv("read-only", "")).toEqual({
      GH_TOKEN: "",
      GITHUB_TOKEN: "",
    });
  });
});

