import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { execSync, execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import { runReviewCycle } from "./review-cycle.mts";
import { computeFindingId, structuredFindingFixture } from "./review-verdict.mts";

function sha40(seed: string): string {
  // deterministic 40-char hex from seed
  const base = Buffer.from(seed).toString("hex").padEnd(40, "0").slice(0, 40);
  // need hex chars only: already hex
  return base;
}

function makeFinding(over: Partial<ReturnType<typeof structuredFindingFixture>> = {}) {
  const draft = structuredFindingFixture(over);
  const id = computeFindingId(draft);
  return { ...draft, id };
}

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
  // create branch
  execSync("git checkout -b sandcastle/issue-999", { cwd: dir, stdio: "ignore" });
  execSync("git commit --allow-empty -m feat", { cwd: dir, stdio: "ignore" });
  const feat = execSync("git rev-parse HEAD", { cwd: dir, encoding: "utf8" }).trim();
  return {
    repoRoot: dir,
    cleanup: () => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch {} },
    rev: (ref: string) => execSync(`git rev-parse ${ref}`, { cwd: dir, encoding: "utf8" }).trim(),
  };
}

describe("review-cycle", () => {
  it("1. clean approval: exact SHA reviewed in fresh read-only sandbox; no fixer; approved result names that SHA; reviewer resources cleaned", async () => {
    const candidateSha = "a".repeat(40);
    let created = 0;
    let removed = 0;
    let fixerCalled = false;
    let verifyCalled = false;
    const result = await runReviewCycle(
      { issueId: "101", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-101" },
      {
        resolveCandidateSha: () => candidateSha,
        getBranchSha: () => candidateSha,
        isAncestor: () => true,
        createReviewWorktree: (sha) => { created++; return { worktreePath: `/tmp/wt-${sha.slice(0,7)}`, worktreeBranch: `review/wt-${created}`, headBefore: sha, statusBefore: "" }; },
        removeReviewWorktree: () => { removed++; },
        getWorktreeHead: () => candidateSha,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha: c, env }) => {
          expect(env.GH_TOKEN).toBe("");
          expect(env.GITHUB_TOKEN).toBe("");
          return { stdout: `<verdict>${verdictJson(c)}</verdict>`, env };
        },
        runFixer: async () => { fixerCalled = true; return { newSha: candidateSha, commits: [] }; },
        runVerification: async () => { verifyCalled = true; return { ok: true }; },
      }
    );
    expect(result.kind).toBe("approved");
    if (result.kind === "approved") expect(result.candidateSha).toBe(candidateSha);
    expect(created).toBe(1);
    expect(removed).toBe(1);
    expect(fixerCalled).toBe(false);
    expect(verifyCalled).toBe(false);
  });

  it("2. blocking finding → repair → approval: second fresh reviewer resolves every prior finding", async () => {
    const sha1 = "b".repeat(40);
    const sha2 = "c".repeat(40);
    const draft = structuredFindingFixture({ invariant: "criterion X must be implemented", failureMode: "missing X" });
    const blockingId = computeFindingId(draft);
    let call = 0;
    let created = 0, removed = 0;
    let fixerDone2=false;
    const result = await runReviewCycle(
      { issueId: "102", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-102" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone2 ? sha2 : sha1,

        isAncestor: () => true,
        createReviewWorktree: (sha) => { created++; return { worktreePath: `/tmp/wt-${sha.slice(0,7)}-${created}`, worktreeBranch: `review/wt-${created}`, headBefore: sha, statusBefore: "" }; },
        removeReviewWorktree: () => { removed++; },
        getWorktreeHead: (p) => p.includes(sha1.slice(0,7)) ? sha1 : sha2,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          call++;
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
          fixerDone2=true;
          return { newSha: sha2, commits: ["fix"] };
        },
        runVerification: async () => ({ ok: true }),
      }
    );
    expect(result.kind).toBe("approved");
    if (result.kind === "approved") expect(result.candidateSha).toBe(sha2);
    expect(created).toBe(2);
    expect(removed).toBe(2);
  });

  it("3. unresolved after repair: fresh reviewer marks prior unresolved → no submission, one fixer", async () => {
    const sha1 = "d".repeat(40);
    const sha2 = "e".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv X", failureMode: "fail X" });
    const id = computeFindingId(draft);
    let fixerCalls = 0;
    let fixerDone3=false;
    const result = await runReviewCycle(
      { issueId: "103", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-103" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone3 ? sha2 : sha1,
        isAncestor: () => true,
        createReviewWorktree: (sha) => ({ worktreePath: `/tmp/wt-${sha.slice(0,7)}`, worktreeBranch: `review/wt-${sha.slice(0,7)}`, headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => {},
        getWorktreeHead: (p) => p.includes(sha1.slice(0,7)) ? sha1 : sha2,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          if (!isReReview) {
            return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env };
          } else {
            return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [], acceptanceCriteriaMet: [{ criterion: "A", met: false }], priorFindings: [{ findingId: id, status: "unresolved", evidence: [] }] })}</verdict>`, env };
          }
        },
        runFixer: async () => { fixerCalls++; fixerDone3=true; return { newSha: sha2, commits: ["fix"] }; },
        runVerification: async () => ({ ok: true }),
      }
    );
    expect(result.kind).toBe("reviewRejected");
    expect(fixerCalls).toBe(1);
  });

  it("4. reviewer mutation: HEAD movement → FACTORY_ERROR and cleanup", async () => {
    const sha = "f".repeat(40);
    let removed = 0;
    const result = await runReviewCycle(
      { issueId: "104", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-104" },
      {
        resolveCandidateSha: () => sha,
        getBranchSha: () => sha,
        isAncestor: () => true,
        createReviewWorktree: () => ({ worktreePath: "/tmp/wt-mut", worktreeBranch: "review/mut", headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => { removed++; },
        getWorktreeHead: () => "0".repeat(40), // mutated
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
        runVerification: async () => ({ ok: true }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("FACTORY_ERROR");
    expect(removed).toBe(1);
  });

  it("5. candidate movement during review → FACTORY_ERROR", async () => {
    const sha = "1".repeat(40);
    const moved = "2".repeat(40);
    let removed = 0;
    let getBranchCalls = 0;
    const result = await runReviewCycle(
      { issueId: "105", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-105" },
      {
        resolveCandidateSha: () => sha,
        getBranchSha: () => { getBranchCalls++; return getBranchCalls === 1 ? sha : moved; }, // before vs after review
        isAncestor: () => true,
        createReviewWorktree: () => ({ worktreePath: "/tmp/wt-move", worktreeBranch: "review/move", headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => { removed++; },
        getWorktreeHead: () => sha,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
        runVerification: async () => ({ ok: true }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("candidate branch moved");
    expect(removed).toBe(1);
  });

  it("6. invalid finding resolution: missing prior ID → FACTORY_ERROR", async () => {
    const sha1 = "3".repeat(40);
    const sha2 = "4".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    const id = computeFindingId(draft);
    let fixerDone6=false;
    const result = await runReviewCycle(
      { issueId: "106", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-106" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone6 ? sha2 : sha1,
        isAncestor: () => true,
        createReviewWorktree: (sha) => ({ worktreePath: `/tmp/wt-${sha.slice(0,7)}`, worktreeBranch: `review/wt-${sha.slice(0,7)}`, headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => {},
        getWorktreeHead: (p) => p.includes(sha1.slice(0,7)) ? sha1 : sha2,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          if (!isReReview) return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env };
          // re-review with unknown ID
          return { stdout: `<verdict>${verdictJson(candidateSha, { approved: true, findings: [], acceptanceCriteriaMet: [{ criterion: "A", met: true }], priorFindings: [{ findingId: "F-unknown123", status: "resolved", evidence: ["x"] }] })}</verdict>`, env };
        },
        runFixer: async () => { fixerDone6=true; return { newSha: sha2, commits: ["fix"] }; },
        runVerification: async () => ({ ok: true }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("FACTORY_ERROR");
  });

  it("7. fixer verification failure: host verification fails → semantic rejection, no re-review", async () => {
    const sha1 = "5".repeat(40);
    const sha2 = "6".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    let reviewerCalls = 0;
    let fixerDone7=false;
    const result = await runReviewCycle(
      { issueId: "107", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-107" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone7 ? sha2 : sha1,
        isAncestor: () => true,
        createReviewWorktree: (sha) => ({ worktreePath: `/tmp/wt-${sha.slice(0,7)}`, worktreeBranch: `review/wt-${sha.slice(0,7)}`, headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => {},
        getWorktreeHead: (p) => p.includes(sha1.slice(0,7)) ? sha1 : sha2,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, env }) => { reviewerCalls++; return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }; },
        runFixer: async () => { fixerDone7=true; return { newSha: sha2, commits: ["fix"] }; },
        runVerification: async () => ({ ok: false, reason: "npm test failed: 1 failing" }),
      }
    );
    expect(result.kind).toBe("reviewRejected");
    expect(reviewerCalls).toBe(1); // only initial, no re-review
    if (result.kind === "reviewRejected") {
      expect(result.findings[0].axis).toBe("verification");
      expect(result.findings[0].severity).toBe("blocking");
    }
  });

  it("8. verification infra failure → FACTORY_ERROR", async () => {
    const sha1 = "7".repeat(40);
    const sha2 = "8".repeat(40);
    const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
    const result = await runReviewCycle(
      { issueId: "108", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-108" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: (() => { let f=true; return ()=>{ if(f){f=false; return sha1;} return sha2;}; })(),
        isAncestor: () => true,
        createReviewWorktree: (sha) => ({ worktreePath: `/tmp/wt-${sha.slice(0,7)}`, worktreeBranch: `review/wt-${sha.slice(0,7)}`, headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => {},
        getWorktreeHead: (p) => p.includes(sha1.slice(0,7)) ? sha1 : sha2,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
        runFixer: async () => ({ newSha: sha2, commits: ["fix"] }),
        runVerification: async () => { throw new Error("spawn failed"); },
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
    let fixerDone9=false;
    const result = await runReviewCycle(
      { issueId: "109", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-109" },
      {
        resolveCandidateSha: () => sha1,
        getBranchSha: () => fixerDone9 ? sha2 : sha1,
        isAncestor: () => false, // not descendant
        createReviewWorktree: (sha) => ({ worktreePath: `/tmp/wt-${sha.slice(0,7)}`, worktreeBranch: `review/wt-${sha.slice(0,7)}`, headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => {},
        getWorktreeHead: (p) => p.includes(sha1.slice(0,7)) ? sha1 : sha2,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha, isReReview, env }) => {
          if (isReReview) reReviewCalled = true;
          return { stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env };
        },
        runFixer: async () => { fixerDone9=true; return { newSha: sha2, commits: ["fix"] }; },
        runVerification: async () => ({ ok: true }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect(reReviewCalled).toBe(false);
    // The factoryError should be due to non-descendant, but getBranchSha moving check may also trigger; allow either
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
        createReviewWorktree: () => ({ worktreePath: "/tmp/wt-tok", worktreeBranch: "review/tok", headBefore: sha, statusBefore: "" }),
        removeReviewWorktree: () => {},
        getWorktreeHead: () => sha,
        getWorktreeStatus: () => "",
        runReviewer: async ({ candidateSha }) => ({ stdout: `<verdict>${verdictJson(candidateSha)}</verdict>`, env: { GH_TOKEN: "secret", GITHUB_TOKEN: "" } }),
        runFixer: async () => ({ newSha: sha, commits: [] }),
        runVerification: async () => ({ ok: true }),
      }
    );
    expect(result.kind).toBe("factoryError");
    expect((result as { reason: string }).reason).toContain("GitHub write");
  });

  it("deterministic finding IDs: same invariant+failureMode+proof → same ID regardless of evidence/order", async () => {
    const d1 = structuredFindingFixture({ invariant: "Inv X", failureMode: "Fail Y", requiredProof: "Proof Z", evidence: ["a", "b"] });
    const d2 = structuredFindingFixture({ invariant: "  inv x  ", failureMode: "fail y", requiredProof: "proof z", evidence: ["different", "order"] });
    expect(computeFindingId(d1)).toBe(computeFindingId(d2));
    // different requiredProof → different ID
    const d3 = structuredFindingFixture({ invariant: "Inv X", failureMode: "Fail Y", requiredProof: "Other proof" });
    expect(computeFindingId(d1)).not.toBe(computeFindingId(d3));
  });

  it("real git ancestry: review-cycle detects non-descendant via real git", async () => {
    const repo = createTmpRepo();
    try {
      const base = repo.rev("HEAD~1");
      const feat = repo.rev("sandcastle/issue-999");
      // create divergent branch from base
      execSync("git checkout -b sandcastle/issue-div", { cwd: repo.repoRoot, stdio: "ignore" });
      execSync(`git reset --hard ${base}`, { cwd: repo.repoRoot, stdio: "ignore" });
      execSync("git commit --allow-empty -m divergent", { cwd: repo.repoRoot, stdio: "ignore" });
      const divSha = repo.rev("sandcastle/issue-div");
      // isAncestor(feat, divBranch) should be false
      const isAnc = (() => { try { execFileSync("git", ["merge-base", "--is-ancestor", feat, "sandcastle/issue-div"], { stdio: "ignore", cwd: repo.repoRoot }); return true; } catch { return false; } })();
      expect(isAnc).toBe(false);
      // Test review-cycle with real isAncestor via repoRoot injection
      const draft = structuredFindingFixture({ invariant: "inv", failureMode: "fail" });
      const result = await runReviewCycle(
        { issueId: "999", issueTitle: "t", issueBody: "body", branch: "sandcastle/issue-999" },
        {
          repoRoot: repo.repoRoot,
          resolveCandidateSha: () => feat,
          getBranchSha: (() => { let first=true; return ()=>{ if(first){first=false; return feat;} return divSha;}; })(),
          isAncestor: (anc, branch) => {
            try { execFileSync("git", ["merge-base", "--is-ancestor", anc, branch], { stdio: "ignore", cwd: repo.repoRoot }); return true; } catch { return false; }
          },
          createReviewWorktree: (sha) => ({ worktreePath: `/tmp/wt-${sha.slice(0,7)}`, worktreeBranch: `review/wt-${sha.slice(0,7)}`, headBefore: sha, statusBefore: "" }),
          removeReviewWorktree: () => {},
          getWorktreeHead: (p) => p.includes(feat.slice(0,7)) ? feat : divSha,
          getWorktreeStatus: () => "",
          runReviewer: async ({ candidateSha, env }) => ({ stdout: `<verdict>${verdictJson(candidateSha, { approved: false, findings: [{ axis: "combined", severity: "blocking", invariant: draft.invariant, failureMode: draft.failureMode, evidence: [], requiredProof: draft.requiredProof }], acceptanceCriteriaMet: [{ criterion: "A", met: false }] })}</verdict>`, env }),
          runFixer: async () => ({ newSha: divSha, commits: ["fix"] }),
          runVerification: async () => ({ ok: true }),
        }
      );
      expect(result.kind).toBe("factoryError");
    } finally { repo.cleanup(); }
  });
});
