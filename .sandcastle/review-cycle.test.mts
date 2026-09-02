import { describe, it, expect } from "vitest";
import { execSync, execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import { runReviewCycle, type ReviewerWorkspace, type VerifierWorkspace } from "./review-cycle.mts";
import { computeFindingId, structuredFindingFixture } from "./review-verdict.mts";

function verdictJson(candidateSha: string, overrides: Record<string, unknown> = {}) {
  return JSON.stringify({
    candidateSha,
    approved: true,
    findings: [],
    acceptanceCriteriaMet: [{ criterion: "criterion A", met: true, evidence: "src/a.ts:1" }],
    summary: "all good",
    ...overrides,
  });
}

// Helper to create a temp git repo for ancestry tests
function createTmpRepo(): { repoRoot: string; cleanup: () => void; rev: (ref: string) => string } {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "review-cycle-"));
  execSync("git init", { cwd: dir, stdio: "ignore" });
  execSync('git config user.email "t@t.com"', { cwd: dir });
  execSync('git config user.name "t"', { cwd: dir });
  execSync("git commit --allow-empty -m init", { cwd: dir, stdio: "ignore" });
  const base = execSync("git rev-parse HEAD", { cwd: dir, encoding: "utf8" }).trim();
  execSync("git checkout -b sandcastle/issue-999", { cwd: dir, stdio: "ignore" });
  execSync("git commit --allow-empty -m feat", { cwd: dir, stdio: "ignore" });
  const feat = execSync("git rev-parse HEAD", { cwd: dir, encoding: "utf8" }).trim();
  return {
    repoRoot: dir,
    cleanup: () => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch {} },
    rev: (ref: string) => execSync(`git rev-parse ${ref}`, { cwd: dir, encoding: "utf8" }).trim(),
  };
}

/**
 * Build a mock reviewer workspace. The mutation guard operates on
 * `snapshot()` of THIS handle — exactly the Sandcastle-owned resource the
 * reviewer acted on. `close()` may throw to simulate `preservedWorktreePath`.
 */
function mockReviewerWorkspace(opts: {
  candidateSha: string;
  snapshotOverride?: () => { head: string; status: string };
  closeShouldThrow?: string;
}): ReviewerWorkspace {
  return {
    worktreePath: `/tmp/ws-${opts.candidateSha.slice(0,7)}`,
    branch: `review/mock-${opts.candidateSha.slice(0,7)}`,
    head: opts.candidateSha,
    status: "",
    snapshot() {
      if (opts.snapshotOverride) return opts.snapshotOverride();
      return { head: opts.candidateSha, status: "" };
    },
    async runAgent() {
      // Tests inject verdicts via the runReviewer dependency, which decides
      // whether to call runAgent. The mock's runAgent is a passthrough.
      return { stdout: "", output: undefined };
    },
    async close() {
      if (opts.closeShouldThrow) throw new Error(opts.closeShouldThrow);
    },
  };
}

function mockVerifierWorkspace(opts: {
  candidateSha: string;
  results?: Map<string, { exitCode: number; stdout?: string; stderr?: string }>;
  closeShouldThrow?: string;
}): VerifierWorkspace {
  return {
    worktreePath: `/tmp/verify-${opts.candidateSha.slice(0,7)}`,
    branch: `verify/mock-${opts.candidateSha.slice(0,7)}`,
    async runCommand(cmd: string, args: string[]) {
      const key = `${cmd} ${args.join(" ")}`;
      const r = opts.results?.get(key);
      if (r) return { exitCode: r.exitCode, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
      return { exitCode: 0, stdout: "", stderr: "" };
    },
    async close() {
      if (opts.closeShouldThrow) throw new Error(opts.closeShouldThrow);
    },
  };
}

describe("review-cycle", () => {
  it("1. clean approval: exact SHA reviewed in fresh read-only workspace; no fixer; approved result names that SHA", async () => {
    const candidateSha = "a".repeat(40);
    let reviewerWorkspaceCreated = 0;
    let fixerCalled = false;
    let verifierCreated = false;
    const result = await runReviewCycle(
      { issueId: "101", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-101" },
      {
        resolveCandidateSha: () => candidateSha,
        getBranchSha: () => candidateSha,
        isAncestor: () => true,
        createReviewerWorkspace: async (sha) => {
          reviewerWorkspaceCreated++;
          return mockReviewerWorkspace({ candidateSha: sha });
        },
        createVerifierWorkspace: async (sha) => {
          verifierCreated = true;
          return mockVerifierWorkspace({ candidateSha: sha });
        },
        runReviewer: async ({ candidateSha: c, env }) => {
          expect(env.GH_TOKEN).toBe("");
          expect(env.GITHUB_TOKEN).toBe("");
          return { stdout: `<verdict>${verdictJson(c)}</verdict>`, env };
        },
        runFixer: async () => { fixerCalled = true; return { newSha: candidateSha, commits: [] }; },
      }
    );
    expect(result.kind).toBe("approved");
    if (result.kind === "approved") expect(result.candidateSha).toBe(candidateSha);
    expect(reviewerWorkspaceCreated).toBe(1);
    expect(fixerCalled).toBe(false);
    expect(verifierCreated).toBe(false); // no blocking findings → no verifier
  });

  it("2. blocking finding → repair → approval: verifier runs at repaired SHA; second fresh reviewer workspace resolves every prior finding", async () => {
    const sha1 = "b".repeat(40);
    const sha2 = "c".repeat(40);
    const draft = structuredFindingFixture({ invariant: "criterion X must be implemented", failureMode: "missing X" });
    const blockingId = computeFindingId(draft);
    let reviewerWorkspaceCount = 0;
    let verifierWorkspaceCount = 0;
    const verifierSeenShas: string[] = [];
    let fixerDone2 = false;
    const result = await runReviewCycle(
      { issueId: "102", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-102" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone2 ? sha2 : sha1,
        isAncestor: () => true,
        createReviewerWorkspace: async (sha) => {
          reviewerWorkspaceCount++;
          return mockReviewerWorkspace({ candidateSha: sha });
        },
        createVerifierWorkspace: async (sha) => {
          verifierWorkspaceCount++;
          verifierSeenShas.push(sha);
          return mockVerifierWorkspace({ candidateSha: sha });
        },
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          expect(env.GH_TOKEN).toBe("");
          if (!isReReview) {
            return {
              stdout: `<verdict>${verdictJson(candidateSha, {
                approved: false,
                findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: ["no file"], requiredProof: draft.requiredProof }],
                acceptanceCriteriaMet: [{ criterion: "criterion A", met: false, evidence: "missing" }],
              })}</verdict>`,
              env,
            };
          } else {
            return {
              stdout: `<verdict>${verdictJson(candidateSha, {
                approved: true,
                findings: [],
                acceptanceCriteriaMet: [{ criterion: "criterion A", met: true, evidence: "src/a.ts:1" }],
                priorFindings: [{ findingId: blockingId, status: "resolved", evidence: ["src/a.ts: fixed"] }],
              })}</verdict>`,
              env,
            };
          }
        },
        runFixer: async () => {
          fixerDone2 = true;
          return { newSha: sha2, commits: ["fix"] };
        },
      }
    );
    expect(result.kind).toBe("approved");
    if (result.kind === "approved") expect(result.candidateSha).toBe(sha2);
    expect(reviewerWorkspaceCount).toBe(2); // initial + re-review
    expect(verifierWorkspaceCount).toBe(1);
    expect(verifierSeenShas).toEqual([sha2]); // verifier anchored at repaired SHA, not original
  });

  it("3. unresolved after repair: fresh reviewer marks prior unresolved → no submission, one fixer", async () => {
    const sha1 = "d".repeat(40);
    const sha2 = "e".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv X", failureMode: "fail X" });
    const id = computeFindingId(draft);
    let fixerCalls = 0;
    let fixerDone3 = false;
    const result = await runReviewCycle(
      { issueId: "103", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-103" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone3 ? sha2 : sha1,
        isAncestor: () => true,
        createReviewerWorkspace: async (sha) => mockReviewerWorkspace({ candidateSha: sha }),
        createVerifierWorkspace: async (sha) => mockVerifierWorkspace({ candidateSha: sha }),
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          if (!isReReview) {
            return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env };
          } else {
            return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [], acceptanceCriteriaMet: [{ criterion: "A", met: false }], priorFindings: [{ findingId: id, status: "unresolved", evidence: [] }] })}</verdict>`, env };
          }
        },
        runFixer: async () => { fixerCalls++; fixerDone3 = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(result.kind).toBe("reviewRejected");
    expect(fixerCalls).toBe(1);
  });

  it("4. reviewer mutation (HEAD moved on workspace) → FACTORY_ERROR", async () => {
    const sha = "f".repeat(40);
    const result = await runReviewCycle(
      { issueId: "104", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-104" },
      {
        resolveCandidateSha: () => sha,
        getBranchSha: () => sha,
        isAncestor: () => true,
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s, snapshotOverride: () => ({ head: "0".repeat(40), status: "" }) }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s }),
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("moved HEAD");
    expect((result as { reason: string }).reason).toContain("FACTORY_ERROR");
  });

  it("4b. reviewer status mutation on workspace → FACTORY_ERROR (guard acts on workspace, not a parallel worktree)", async () => {
    const sha = "f".repeat(40);
    const result = await runReviewCycle(
      { issueId: "104b", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-104b" },
      {
        resolveCandidateSha: () => sha,
        getBranchSha: () => sha,
        isAncestor: () => true,
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s, snapshotOverride: () => ({ head: s, status: "?? new-file.txt" }) }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s }),
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("mutated workspace status");
  });

  it("5. candidate movement during review → FACTORY_ERROR", async () => {
    const sha = "1".repeat(40);
    const moved = "2".repeat(40);
    let getBranchCalls = 0;
    const result = await runReviewCycle(
      { issueId: "105", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-105" },
      {
        resolveCandidateSha: () => sha,
        getBranchSha: () => { getBranchCalls++; return getBranchCalls === 1 ? sha : moved; },
        isAncestor: () => true,
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s }),
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("candidate branch moved");
  });

  it("6. invalid finding resolution: missing prior ID → FACTORY_ERROR", async () => {
    const sha1 = "3".repeat(40);
    const sha2 = "4".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    let fixerDone6 = false;
    const result = await runReviewCycle(
      { issueId: "106", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-106" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone6 ? sha2 : sha1,
        isAncestor: () => true,
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s }),
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          if (!isReReview) return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env };
          return { stdout: `<verdict>${verdictJson(candidateSha, { approved: true, findings: [], acceptanceCriteriaMet: [{ criterion: "A", met: true }], priorFindings: [{ findingId: "F-unknown123", status: "resolved", evidence: ["x"] }] })}</verdict>`, env };
        },
        runFixer: async () => { fixerDone6 = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("FACTORY_ERROR");
  });

  it("7. verifier failure at repaired SHA → semantic rejection, no re-review", async () => {
    const sha1 = "5".repeat(40);
    const sha2 = "6".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    let reviewerCalls = 0;
    let fixerDone7 = false;
    const result = await runReviewCycle(
      { issueId: "107", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-107" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone7 ? sha2 : sha1,
        isAncestor: () => true,
        getChangedFiles: () => [],
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({
          candidateSha: s,
          results: new Map([
            ["npm run typecheck", { exitCode: 0 }],
            ["npm test", { exitCode: 1, stderr: "1 failing" }],
          ]),
        }),
        runReviewer: async ({ candidateSha, env }) => { reviewerCalls++; return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }; },
        runFixer: async () => { fixerDone7 = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(result.kind).toBe("reviewRejected");
    expect(reviewerCalls).toBe(1); // only initial, no re-review
    if (result.kind === "reviewRejected") {
      expect(result.findings[0].axis).toBe("verification");
      expect(result.findings[0].severity).toBe("blocking");
      expect(result.findings[0].failureMode).toContain("npm test");
    }
  });

  it("7b. verification baseline always includes npm test (and adds Java lanes only when mod/ is touched)", async () => {
    const sha1 = "5".repeat(40);
    const sha2 = "6".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    const observedCommands: string[] = [];
    const recorderWs: VerifierWorkspace = {
      worktreePath: "/tmp/recorder",
      branch: "verify/recorder",
      async runCommand(cmd, args) {
        observedCommands.push(`${cmd} ${args.join(" ")}`);
        return { exitCode: 0, stdout: "", stderr: "" };
      },
      async close() {},
    };

    // 7b-a: no mod/ touched → typecheck + npm test only
    observedCommands.length = 0;
    let fixerDone = false;
    await runReviewCycle(
      { issueId: "107b-a", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-107b-a" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone ? sha2 : sha1,
        isAncestor: () => true,
        getChangedFiles: () => ["src/foo.ts", "README.md"],
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async () => recorderWs,
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
        runFixer: async () => { fixerDone = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(observedCommands).toEqual(["npm run typecheck", "npm test"]);

    // 7b-b: mod/ touched → ADD Java lanes, npm test still present
    observedCommands.length = 0;
    fixerDone = false;
    await runReviewCycle(
      { issueId: "107b-b", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-107b-b" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone ? sha2 : sha1,
        isAncestor: () => true,
        getChangedFiles: () => ["mod/src/main/java/Foo.java"],
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async () => recorderWs,
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
        runFixer: async () => { fixerDone = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(observedCommands).toEqual([
      "npm run typecheck",
      "npm test",
      "bash .ci/install-voxy.sh install",
      "./mod/gradlew -p mod lint compileJava compileClientJava",
      "./mod/gradlew -p mod test -PexcludeVoxyTestRuntime",
    ]);
  });

  it("7c. verifier workspace anchored at repaired SHA, not the original candidate", async () => {
    const sha1 = "5".repeat(40);
    const sha2 = "6".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    const seenVerifierShas: string[] = [];
    let fixerDone = false;
    await runReviewCycle(
      { issueId: "107c", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-107c" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone ? sha2 : sha1,
        isAncestor: () => true,
        getChangedFiles: () => [],
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => {
          seenVerifierShas.push(s);
          return mockVerifierWorkspace({ candidateSha: s, results: new Map([["npm test", { exitCode: 1, stderr: "fail" }]]) });
        },
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
        runFixer: async () => { fixerDone = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(seenVerifierShas).toEqual([sha2]); // exact repaired SHA
  });

  it("8. verification infra failure (spawn throws) → FACTORY_ERROR", async () => {
    const sha1 = "7".repeat(40);
    const sha2 = "8".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    const result = await runReviewCycle(
      { issueId: "108", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-108" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: (() => { let f = true; return () => { if (f) { f = false; return sha1; } return sha2; }; })(),
        isAncestor: () => true,
        getChangedFiles: () => [],
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => ({
          worktreePath: `/tmp/v-${s.slice(0,7)}`,
          branch: `verify/x-${s.slice(0,7)}`,
          async runCommand() { throw new Error("spawn failed"); },
          async close() {},
        }),
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha2, commits: ["fix"] }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("FACTORY_ERROR");
  });

  it("9. non-descendant fixer history → FACTORY_ERROR before re-review", async () => {
    const sha1 = "9".repeat(40);
    const sha2 = "a".repeat(40); // unrelated
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    let reReviewCalled = false;
    let verifierCreated = 0;
    let fixerDone9 = false;
    const result = await runReviewCycle(
      { issueId: "109", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-109" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone9 ? sha2 : sha1,
        isAncestor: () => false, // not descendant
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => { verifierCreated++; return mockVerifierWorkspace({ candidateSha: s }); },
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          if (isReReview) reReviewCalled = true;
          return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env };
        },
        runFixer: async () => { fixerDone9 = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(result.kind).toBe("factoryError");
    expect(reReviewCalled).toBe(false);
    expect(verifierCreated).toBe(0); // verifier must NOT have been created yet
    expect((result as { reason: string }).reason).toContain("FACTORY_ERROR");
  });

  it("10. reviewer must have empty GH tokens; write tokens → FACTORY_ERROR", async () => {
    const sha = "b".repeat(40);
    const result = await runReviewCycle(
      { issueId: "110", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-110" },
      {
        resolveCandidateSha: () => sha,
        getBranchSha: () => sha,
        isAncestor: () => true,
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s }),
        runReviewer: async ({ candidateSha }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env: { GH_TOKEN: "secret", GITHUB_TOKEN: "" } }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("GitHub write");
  });

  it("11. reviewer workspace cleanup uncertain (close throws) → FACTORY_ERROR (no silent swallow)", async () => {
    const sha = "b".repeat(40);
    const result = await runReviewCycle(
      { issueId: "111", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-111" },
      {
        resolveCandidateSha: () => sha,
        getBranchSha: () => sha,
        isAncestor: () => true,
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s, closeShouldThrow: "worktree preserved (dirty)" }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s }),
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("cleanup uncertain");
  });

  it("12. verifier workspace cleanup uncertain (close throws) → FACTORY_ERROR (no silent swallow)", async () => {
    const sha1 = "c".repeat(40);
    const sha2 = "d".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    let fixerDone = false;
    const result = await runReviewCycle(
      { issueId: "112", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-112" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone ? sha2 : sha1,
        isAncestor: () => true,
        getChangedFiles: () => [],
        createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
        createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s, closeShouldThrow: "verifier worktree preserved" }),
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
        runFixer: async () => { fixerDone = true; return { newSha: sha2, commits: ["fix"] }; },
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("verifier workspace cleanup uncertain");
  });

  it("deterministic finding IDs: same invariant+failureMode+proof → same ID regardless of evidence/order", async () => {
    const d1 = structuredFindingFixture({ invariant: "Inv X", failureMode: "Fail Y", requiredProof: "Proof Z", evidence: ["a", "b"] });
    const d2 = structuredFindingFixture({ invariant: "  inv x  ", failureMode: "fail y", requiredProof: "proof z", evidence: ["different", "order"] });
    expect(computeFindingId(d1)).toBe(computeFindingId(d2));
    const d3 = structuredFindingFixture({ invariant: "Inv X", failureMode: "Fail Y", requiredProof: "Other proof" });
    expect(computeFindingId(d1)).not.toBe(computeFindingId(d3));
  });

  it("real git ancestry: review-cycle detects non-descendant via real git", async () => {
    const repo = createTmpRepo();
    try {
      const base = repo.rev("HEAD~1");
      const feat = repo.rev("sandcastle/issue-999");
      execSync("git checkout -b sandcastle/issue-div", { cwd: repo.repoRoot, stdio: "ignore" });
      execSync(`git reset --hard ${base}`, { cwd: repo.repoRoot, stdio: "ignore" });
      execSync("git commit --allow-empty -m divergent", { cwd: repo.repoRoot, stdio: "ignore" });
      const divSha = repo.rev("sandcastle/issue-div");
      const isAnc = (() => { try { execFileSync("git", ["merge-base", "--is-ancestor", feat, "sandcastle/issue-div"], { stdio: "ignore", cwd: repo.repoRoot }); return true; } catch { return false; } })();
      expect(isAnc).toBe(false);
      const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
      const result = await runReviewCycle(
        { issueId: "999", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-999" },
        {
          repoRoot: repo.repoRoot,
          resolveCandidateSha: () => feat,
          getBranchSha: (() => { let first = true; return () => { if (first) { first = false; return feat; } return divSha; }; })(),
          isAncestor: (anc, branch) => {
            try { execFileSync("git", ["merge-base", "--is-ancestor", anc, branch], { stdio: "ignore", cwd: repo.repoRoot }); return true; } catch { return false; }
          },
          createReviewerWorkspace: async (s) => mockReviewerWorkspace({ candidateSha: s }),
          createVerifierWorkspace: async (s) => mockVerifierWorkspace({ candidateSha: s }),
          runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
          runFixer: async () => ({ newSha: divSha, commits: ["fix"] }),
        }
      );
      expect(result.kind).toBe("factoryError");
    } finally { repo.cleanup(); }
  });
});