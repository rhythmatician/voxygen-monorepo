import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import {
  createGhTransport,
  resolveGhExecutable,
  resolveGhToken,
  GhCapabilityError,
  GhTokenMissingError,
} from "./gh-transport.mts";

function makeTempRepoRoot(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gh-transport-test-"));
  return dir;
}

describe("resolveGhExecutable", () => {
  it("prefers explicit override over PATH resolution", () => {
    expect(resolveGhExecutable("/custom/path/gh")).toBe("/custom/path/gh");
    expect(resolveGhExecutable("  /spaced/gh  ")).toBe("/spaced/gh");
  });

  it("falls back to bare gh (PATH) with no personal home paths", () => {
    expect(resolveGhExecutable()).toBe("gh");
    expect(resolveGhExecutable("")).toBe("gh");
  });
});

describe("resolveGhToken", () => {
  it("reads GH_TOKEN from environment first", () => {
    const root = makeTempRepoRoot();
    expect(resolveGhToken(root, { GH_TOKEN: "env-token" })).toBe("env-token");
  });

  it("falls back to .sandcastle/.env under stable repoRoot, never ambient cwd", () => {
    const root = makeTempRepoRoot();
    const sandcastleDir = path.join(root, ".sandcastle");
    fs.mkdirSync(sandcastleDir, { recursive: true });
    fs.writeFileSync(path.join(sandcastleDir, ".env"), "GH_TOKEN=file-token\n");
    expect(resolveGhToken(root, {})).toBe("file-token");
  });

  it("returns empty when neither source has a token", () => {
    const root = makeTempRepoRoot();
    expect(resolveGhToken(root, {})).toBe("");
  });
});

describe("createGhTransport capability enforcement", () => {
  it("write through read-only transport fails BEFORE spawning any process", async () => {
    // executableOverride points at a nonexistent binary — if the transport
    // spawned anything, we'd get ENOENT rather than GhCapabilityError.
    const gh = createGhTransport({
      repoRoot: makeTempRepoRoot(),
      capabilityMode: "read-only",
      environment: { GH_TOKEN: "t" },
      executableOverride: "/nonexistent/gh-binary-for-test",
    });
    await expect(gh.run(["issue", "edit", "1", "--add-label", "x"])).rejects.toBeInstanceOf(GhCapabilityError);
    await expect(gh.run(["issue", "close", "1"])).rejects.toBeInstanceOf(GhCapabilityError);
    await expect(gh.run(["api", "--method", "POST", "repos/o/r/issues", "-f", "title=x"])).rejects.toBeInstanceOf(GhCapabilityError);
    // unknown commands are treated as potentially-writing and also rejected pre-spawn
    await expect(gh.run(["some", "unknown", "subcommand"])).rejects.toBeInstanceOf(GhCapabilityError);
  });

  it("read commands through read-only transport DO spawn (fail with spawn error, not capability error)", async () => {
    const gh = createGhTransport({
      repoRoot: makeTempRepoRoot(),
      capabilityMode: "read-only",
      environment: { GH_TOKEN: "t" },
      executableOverride: "/nonexistent/gh-binary-for-test",
    });
    // Reads are allowed through; failure is the missing binary, not capability.
    await expect(gh.run(["issue", "view", "1"])).rejects.not.toBeInstanceOf(GhCapabilityError);
  });

  it("read-write transport allows writes through to spawn", async () => {
    const gh = createGhTransport({
      repoRoot: makeTempRepoRoot(),
      capabilityMode: "read-write",
      environment: { GH_TOKEN: "t" },
      executableOverride: "/nonexistent/gh-binary-for-test",
    });
    // Write reaches spawn stage → fails on missing binary, NOT capability.
    await expect(gh.run(["issue", "edit", "1", "--add-label", "x"])).rejects.not.toBeInstanceOf(GhCapabilityError);
  });

  it("missing token fails before spawn", async () => {
    const root = makeTempRepoRoot();
    const gh = createGhTransport({
      repoRoot: root,
      capabilityMode: "read-write",
      environment: {},
      executableOverride: "/nonexistent/gh-binary-for-test",
    });
    await expect(gh.run(["issue", "view", "1"])).rejects.toBeInstanceOf(GhTokenMissingError);
  });

  it("isWriteForbidden reports pre-spawn rejection without throwing", () => {
    const gh = createGhTransport({
      repoRoot: makeTempRepoRoot(),
      capabilityMode: "read-only",
      environment: { GH_TOKEN: "t" },
    });
    expect(gh.isWriteForbidden(["issue", "edit", "1"])).toBe(true);
    expect(gh.isWriteForbidden(["issue", "view", "1"])).toBe(false);
  });
});

describe("createGhTransport structured errors and identity", () => {
  it("attaches structured details to spawn failures", async () => {
    const gh = createGhTransport({
      repoRoot: makeTempRepoRoot(),
      capabilityMode: "read-only",
      environment: { GH_TOKEN: "t" },
      executableOverride: "/nonexistent/gh-binary-for-test",
    });
    try {
      await gh.run(["issue", "view", "1"]);
      expect.unreachable("should have thrown");
    } catch (e: unknown) {
      const err = e as Error & { command?: string[] };
      expect(err.command).toEqual(["issue", "view", "1"]);
      expect(err.message).toContain("gh issue view 1 failed");
    }
  });

  it("resolves owner/repo from origin remote of the given repoRoot", () => {
    // This workspace IS a github.com repository — use it as ground truth.
    const repoRoot = path.resolve(import.meta.dirname ?? ".", "..");
    const gh = createGhTransport({
      repoRoot,
      capabilityMode: "read-only",
      environment: { GH_TOKEN: "t" },
    });
    const ownerRepo = gh.resolveOwnerRepo();
    expect(ownerRepo).not.toBeNull();
    expect(ownerRepo!.owner).toBe("rhythmatician");
    expect(ownerRepo!.repo).toBe("voxygen-monorepo");
  });
});
