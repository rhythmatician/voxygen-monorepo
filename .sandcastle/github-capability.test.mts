import { describe, it, expect } from "vitest";
import {
  classifyGhOperation,
  makeGitHubCapability,
  GitHubWriteForbiddenError,
} from "./github-capability.mts";

describe("GitHub capability boundary", () => {
  it("classifies known write commands as writes", () => {
    expect(classifyGhOperation(["issue", "edit", "1", "--add-label", "x"])).toBe("write");
    expect(classifyGhOperation(["issue", "comment", "1", "--body", "x"])).toBe("write");
    expect(classifyGhOperation(["issue", "close", "1"])).toBe("write");
    expect(classifyGhOperation(["pr", "create", "-B", "base", "-H", "head"])).toBe("write");
    expect(classifyGhOperation(["pr", "merge", "123", "--auto"])).toBe("write");
    expect(classifyGhOperation(["api", "issues", "--method", "PATCH"])).toBe("write");
  });

  it("read-only mode blocks claim-like write attempts before any gh execution", async () => {
    let invoked = false;
    const capability = makeGitHubCapability({
      mode: "read-only",
      exec: async () => {
        invoked = true;
        return "should not run";
      },
    });

    await expect(
      capability.run(["issue", "edit", "151", "--add-assignee", "@me", "--add-label", "agent:in-progress"]),
    ).rejects.toBeInstanceOf(GitHubWriteForbiddenError);
    expect(invoked).toBe(false);
  });

  it("read-only mode still supports GitHub reads needed by qualification", async () => {
    let invoked = false;
    const capability = makeGitHubCapability({
      mode: "read-only",
      exec: async () => {
        invoked = true;
        return "read-result";
      },
    });

    await expect(capability.run(["issue", "list", "--state", "open"])).resolves.toBe("read-result");
    await expect(capability.run(["issue", "view", "151", "--json", "body", "--jq", ".body"])).resolves.toBe("read-result");
    expect(invoked).toBe(true);
  });

  it("read-only mode rejects mutating issue/PR write commands used by production", async () => {
    const blocked = [
      ["issue", "edit", "151", "--remove-label", "agent:in-progress"],
      ["issue", "comment", "151", "--body", "blocked"],
      ["issue", "close", "151"],
      ["pr", "create", "--title", "x", "--body", "y", "-B", "main", "-H", "feature"],
      ["pr", "merge", "123", "--squash"],
      ["api", "repos/octo/repo/issues", "--method", "POST", "--input", "{}"],
    ];

    const capability = makeGitHubCapability({
      mode: "read-only",
      exec: async () => "should-not-run",
    });

    for (const args of blocked) {
      await expect(capability.run(args)).rejects.toBeInstanceOf(GitHubWriteForbiddenError);
    }
  });

  it("read-write mode executes both reads and writes through the same seam", async () => {
    let executes = 0;
    const capability = makeGitHubCapability({
      mode: "read-write",
      exec: async () => {
        executes += 1;
        return "executed";
      },
    });

    await expect(capability.run(["issue", "list", "--state", "open"])).resolves.toBe("executed");
    await expect(capability.run(["issue", "edit", "151", "--add-label", "agent:in-progress"])).resolves.toBe("executed");
    expect(executes).toBe(2);
  });
});
