import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";

/**
 * Regression for sandcastle/issue-70: main.mts passed TARGET_BRANCH in promptArgs
 * which collides with Sandcastle's BUILT_IN_PROMPT_ARG_KEYS (SOURCE_BRANCH,
 * TARGET_BRANCH). Sandcastle auto-injects those and rejects overrides via
 * validateNoBuiltInArgOverride.
 *
 * This is a pure file-scan seam (like .ci/docs-policy.test.mts shell-metachar
 * guard) — no Docker, no GH, no mocks. It fails in the factory lane instead
 * of a live sandcastle run that leaves agent:blocked + preserved branch.
 *
 * See ADR 0001, .sandcastle/main.mts, PromptArgumentSubstitution.ts.
 */
const BUILT_IN_KEYS = ["SOURCE_BRANCH", "TARGET_BRANCH"] as const;

function findPromptArgsBlocks(source: string): string[] {
  // Naive but sufficient: capture promptArgs: { ... } blocks (non-greedy, may span lines).
  const re = /promptArgs\s*:\s*\{([\s\S]*?)\}/g;
  const blocks: string[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(source)) !== null) {
    blocks.push(m[1]);
  }
  return blocks;
}

describe("prompt-args built-in collision (regression for #70)", () => {
  it("main.mts promptArgs must not override Sandcastle built-ins", () => {
    const mainPath = path.resolve(process.cwd(), ".sandcastle/main.mts");
    const source = fs.readFileSync(mainPath, "utf8");
    const blocks = findPromptArgsBlocks(source);
    // At least the reviewer block exists — ensures scan isn't vacuous.
    expect(blocks.length).toBeGreaterThan(0);

    for (const block of blocks) {
      for (const key of BUILT_IN_KEYS) {
        // Match as object key: TARGET_BRANCH : or TARGET_BRANCH, or shorthand
        const keyRe = new RegExp(`\\b${key}\\b\\s*[,\\}:]`);
        expect(
          keyRe.test(block),
          `promptArgs must not contain built-in ${key} — found in block: ${block.slice(0, 200)}`
        ).toBe(false);
      }
    }
  });

  it("review-prompt.md may use {{TARGET_BRANCH}} via built-in injection, but main.mts must not pass it", () => {
    // This documents the intended split: prompt template uses built-in, host does not.
    const promptPath = path.resolve(process.cwd(), ".sandcastle/review-prompt.md");
    const promptSource = fs.readFileSync(promptPath, "utf8");
    // Prompt should be allowed to reference built-ins
    expect(promptSource).toContain("{{TARGET_BRANCH}}");
    // But the host file must not provide it
    const mainSource = fs.readFileSync(path.resolve(process.cwd(), ".sandcastle/main.mts"), "utf8");
    // Count occurrences of TARGET_BRANCH as a key in promptArgs (not as const declaration or comment)
    const promptArgsWithBuiltIn = findPromptArgsBlocks(mainSource).some((b) =>
      BUILT_IN_KEYS.some((k) => new RegExp(`\\b${k}\\b`).test(b))
    );
    expect(promptArgsWithBuiltIn).toBe(false);
  });
});

describe("implementer issue contract grounding", () => {
  it("embeds the host-selected issue body instead of requiring worker GitHub access", () => {
    const promptSource = fs.readFileSync(path.resolve(process.cwd(), ".sandcastle/implement-prompt.md"), "utf8");

    expect(promptSource).toContain("{{ISSUE_BODY}}");
    expect(promptSource).not.toContain("gh issue view");
  });

});
