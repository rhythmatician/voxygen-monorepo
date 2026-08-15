import { describe, it, expect } from "vitest";
import {
  classifyRuffResult,
  oldPermissiveWouldBlock,
  isTrustworthyExecutionPath,
  type RuffResult,
} from "../scripts/ruff-process.mts";

describe("ruff-process - deterministic failure semantics", () => {
  it("residual Ruff lint findings are allowed for formatter-only pre-commit (check --fix-only exits 0)", () => {
    // ruff check --fix-only exits 0 even with leftover lint (e.g., BLE001 that cannot be auto-fixed)
    const result: RuffResult = {
      args: ["check", "--fix-only", "--quiet", "python/voxel_tree/bad.py"],
      status: 0,
      stdout: "python/voxel_tree/bad.py:10:5: BLE001 ...",
      stderr: "",
    };
    const c = classifyRuffResult(result);
    expect(c.blocked).toBe(false);
    expect(c.reason).toContain("residual lint allowed");
  });

  it("Ruff invocation/config/tool failure is blocked (check --fix-only non-zero)", () => {
    const result: RuffResult = {
      args: ["check", "--fix-only", "--quiet", "python/voxel_tree/a.py"],
      status: 2,
      stdout: "",
      stderr: "error: Failed to parse config at python/pyproject.toml",
    };
    const c = classifyRuffResult(result);
    expect(c.blocked).toBe(true);
    expect(c.reason).toContain("failed");

    // Old permissive would have allowed this because stderr doesn't contain E902 - demonstrating bug
    expect(oldPermissiveWouldBlock(result)).toBe(false); // old would incorrectly allow
    expect(c.blocked).toBe(true); // new correctly blocks
  });

  it("ruff format nonzero is blocked", () => {
    const result: RuffResult = {
      args: ["format", "--quiet", "python/voxel_tree/a.py"],
      status: 1,
      stdout: "",
      stderr: "error: Failed to format",
    };
    const c = classifyRuffResult(result);
    expect(c.blocked).toBe(true);
    expect(c.reason).toContain("format failed");

    // Old permissive checking only E902 would miss format failures
    expect(oldPermissiveWouldBlock(result)).toBe(false);
  });

  it("ruff format success is allowed", () => {
    const result: RuffResult = {
      args: ["format", "--quiet", "python/voxel_tree/a.py"],
      status: 0,
      stdout: "",
      stderr: "",
    };
    expect(classifyRuffResult(result).blocked).toBe(false);
  });

  it("signal termination (null status) is blocked", () => {
    const result: RuffResult = {
      args: ["check", "--fix-only", "--quiet", "python/voxel_tree/a.py"],
      status: null,
      stdout: "",
      stderr: "",
    };
    expect(classifyRuffResult(result).blocked).toBe(true);
  });

  it("old permissive implementation would hide tool failures - regression", () => {
    // Config error without E902
    const configFail: RuffResult = {
      args: ["check", "--fix", "--quiet", "a.py"],
      status: 2,
      stdout: "",
      stderr: "configuration error",
    };
    expect(oldPermissiveWouldBlock(configFail)).toBe(false); // old allows
    expect(
      classifyRuffResult({
        ...configFail,
        args: ["check", "--fix-only", "--quiet", "a.py"],
      }).blocked
    ).toBe(true); // new blocks

    // Binary not found
    const notFound: RuffResult = {
      args: ["format", "--quiet", "a.py"],
      status: 127,
      stdout: "",
      stderr: "command not found",
    };
    expect(oldPermissiveWouldBlock(notFound)).toBe(false);
    expect(classifyRuffResult(notFound).blocked).toBe(true);
  });

  it("WSL/uv execution path trustworthiness is correctly classified", () => {
    expect(
      isTrustworthyExecutionPath("python/.venv/Scripts/ruff.exe", "/mnt/c/repo")
    ).toBe(true);
    expect(
      isTrustworthyExecutionPath("python/.venv/bin/ruff", "/home/user/repo")
    ).toBe(true);
    expect(isTrustworthyExecutionPath("ruff", "/home/user/repo")).toBe(true);
    // Windows uv via WSL is untrustworthy
    expect(
      isTrustworthyExecutionPath(
        "uv",
        "/mnt/c/Users/JeffHall/git/MC/voxygen-monorepo"
      )
    ).toBe(false);
    expect(isTrustworthyExecutionPath("uv.exe", "/mnt/c/repo")).toBe(false);
    // Linux uv on native Linux is trustworthy
    expect(isTrustworthyExecutionPath("uv", "/home/jeff/repo")).toBe(true);
  });
});
