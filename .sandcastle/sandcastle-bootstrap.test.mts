import { describe, it, expect, beforeEach, afterEach } from "vitest";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import {
  SANDCASTLE_REPO_URL,
  resolveSiblingSandcastlePath,
  resolvePackageLinkPath,
  verifyPackageIdentity,
  ensurePackageLink,
  assertSiblingProvenance,
  getSiblingHead,
} from "./sandcastle-bootstrap.mts";
import {
  EXPECTED_SANDCASTLE_SOURCE_SHA,
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  isExpectedSandcastleSourceHead,
} from "./sandcastle-runtime-provenance.mts";

describe("sandcastle-bootstrap deterministic helpers", () => {
  it("reuses single SHA authority from provenance, no second copy", () => {
    const bootstrapContent = fs.readFileSync(".sandcastle/sandcastle-bootstrap.mts", "utf8");
    const provenanceContent = fs.readFileSync(".sandcastle/sandcastle-runtime-provenance.mts", "utf8");
    // Bootstrap must import SHA, not define its own
    expect(bootstrapContent).toMatch(/from\s+["']\.\/sandcastle-runtime-provenance\.mts["']/);
    expect(bootstrapContent).toContain("EXPECTED_SANDCASTLE_SOURCE_SHA");
    // Should not contain a hardcoded second SHA definition
    const shaMatches = [...bootstrapContent.matchAll(/4692bc0681d2e9228bdaaaecd6f16c8058c4bb0f/g)];
    // The bootstrap may mention SHA in logs/errors but should not define const EXPECTED_... =
    expect(bootstrapContent).not.toMatch(/const\s+EXPECTED_SANDCASTLE_SOURCE_SHA\s*=\s*["']4692bc0/);
    expect(bootstrapContent).not.toMatch(/const\s+EXPECTED_SANDCASTLE_SOURCE_PREFIX\s*=/);
    // Repo URL is correct
    expect(SANDCASTLE_REPO_URL).toBe("https://github.com/rhythmatician/sandcastle");
    expect(EXPECTED_SANDCASTLE_SOURCE_SHA).toBe("4692bc0681d2e9228bdaaaecd6f16c8058c4bb0f");
    expect(EXPECTED_SANDCASTLE_SOURCE_PREFIX).toBe("4692bc0");
  });

  it("resolves sibling and link paths deterministically from repo root", () => {
    const repoRoot = "/tmp/fake-repo/voxygen-monorepo";
    expect(resolveSiblingSandcastlePath(repoRoot)).toBe(path.resolve(repoRoot, "../../sandcastle"));
    expect(resolvePackageLinkPath(repoRoot)).toBe(path.join(repoRoot, "node_modules", "@ai-hero", "sandcastle"));
    // Default (process.cwd) should also resolve without throwing
    expect(() => resolveSiblingSandcastlePath()).not.toThrow();
    expect(() => resolvePackageLinkPath()).not.toThrow();
    // Sibling path should be outside repoRoot, one level up from parent
    const sibling = resolveSiblingSandcastlePath("/a/b/c/repo");
    expect(sibling).toBe(path.resolve("/a/b/c/repo", "../../sandcastle"));
    expect(sibling).toBe("/a/b/sandcastle");
  });

  it("verifies package identity without network", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "bootstrap-id-"));
    try {
      // Missing package.json
      expect(verifyPackageIdentity(tmp).ok).toBe(false);
      expect(verifyPackageIdentity(tmp).reason).toMatch(/package.json missing/);

      // Wrong name
      fs.writeFileSync(path.join(tmp, "package.json"), JSON.stringify({ name: "other" }));
      expect(verifyPackageIdentity(tmp).ok).toBe(false);
      expect(verifyPackageIdentity(tmp).reason).toMatch(/unexpected package name/);

      // Correct name
      fs.writeFileSync(path.join(tmp, "package.json"), JSON.stringify({ name: "@ai-hero/sandcastle", version: "0.12.0" }));
      expect(verifyPackageIdentity(tmp).ok).toBe(true);
      expect(verifyPackageIdentity(tmp).name).toBe("@ai-hero/sandcastle");

      // Invalid JSON
      fs.writeFileSync(path.join(tmp, "package.json"), "{ not json");
      expect(verifyPackageIdentity(tmp).ok).toBe(false);
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  });

  it("ensurePackageLink creates and repairs symlink deterministically", async () => {
    const repoRoot = fs.mkdtempSync(path.join(os.tmpdir(), "bootstrap-link-repo-"));
    const siblingRoot = fs.mkdtempSync(path.join(os.tmpdir(), "bootstrap-link-sib-"));
    // Create a fake sibling package
    fs.writeFileSync(path.join(siblingRoot, "package.json"), JSON.stringify({ name: "@ai-hero/sandcastle" }));
    fs.mkdirSync(path.join(siblingRoot, "dist"), { recursive: true });
    fs.writeFileSync(path.join(siblingRoot, "dist", "index.js"), "export const run = () => {};");

    try {
      const linkPath = resolvePackageLinkPath(repoRoot);
      // Initially no link
      expect(fs.existsSync(linkPath)).toBe(false);
      ensurePackageLink(repoRoot, siblingRoot);
      expect(fs.lstatSync(linkPath).isSymbolicLink()).toBe(true);
      expect(fs.realpathSync(linkPath)).toBe(fs.realpathSync(siblingRoot));

      // Idempotent second call should not change
      const beforeTarget = fs.readlinkSync(linkPath);
      ensurePackageLink(repoRoot, siblingRoot);
      expect(fs.readlinkSync(linkPath)).toBe(beforeTarget);

      // Repair wrong target
      fs.rmSync(linkPath, { force: true });
      const wrong = fs.mkdtempSync(path.join(os.tmpdir(), "bootstrap-wrong-"));
      fs.symlinkSync(wrong, linkPath, "dir");
      expect(fs.realpathSync(linkPath)).not.toBe(fs.realpathSync(siblingRoot));
      ensurePackageLink(repoRoot, siblingRoot);
      expect(fs.realpathSync(linkPath)).toBe(fs.realpathSync(siblingRoot));
      fs.rmSync(wrong, { recursive: true, force: true });

      // Repair when link is a directory (npm copy scenario)
      fs.rmSync(linkPath, { recursive: true, force: true });
      fs.mkdirSync(linkPath, { recursive: true });
      fs.writeFileSync(path.join(linkPath, "junk.txt"), "old");
      ensurePackageLink(repoRoot, siblingRoot);
      expect(fs.lstatSync(linkPath).isSymbolicLink()).toBe(true);
      expect(fs.realpathSync(linkPath)).toBe(fs.realpathSync(siblingRoot));
    } finally {
      fs.rmSync(repoRoot, { recursive: true, force: true });
      fs.rmSync(siblingRoot, { recursive: true, force: true });
    }
  });

  it("assertSiblingProvenance fails closed on wrong HEAD", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "bootstrap-head-"));
    try {
      // Not a git repo
      expect(() => assertSiblingProvenance(tmp)).toThrow(/no git HEAD/);

      // Create git repo with wrong HEAD
      execFileSync("git", ["init", "-q"], { cwd: tmp });
      execFileSync("git", ["config", "user.email", "test@example.com"], { cwd: tmp });
      execFileSync("git", ["config", "user.name", "Test"], { cwd: tmp });
      fs.writeFileSync(path.join(tmp, "README.md"), "test");
      execFileSync("git", ["add", "."], { cwd: tmp });
      execFileSync("git", ["commit", "-qm", "init"], { cwd: tmp });
      const wrongHead = execFileSync("git", ["-C", tmp, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
      expect(wrongHead).not.toBe(EXPECTED_SANDCASTLE_SOURCE_SHA);
      expect(isExpectedSandcastleSourceHead(wrongHead)).toBe(false);
      expect(() => assertSiblingProvenance(tmp)).toThrow(/does not match pinned/);
      expect(() => assertSiblingProvenance(tmp)).toThrow(EXPECTED_SANDCASTLE_SOURCE_SHA);
      expect(getSiblingHead(tmp)).toBe(wrongHead);
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  });

  it("bootstrap does not use allowEmptyMessages and does not launch Muse", () => {
    const content = fs.readFileSync(".sandcastle/sandcastle-bootstrap.mts", "utf8");
    expect(content).not.toMatch(/allowEmptyMessages/);
    expect(content).not.toMatch(/muse/);
    expect(content).not.toMatch(/createSandbox/);
    expect(content).not.toMatch(/Muse/);
  });
});
