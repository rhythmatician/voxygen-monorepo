# Sandcastle Reliability — Failure / Invariant Matrix

> Source: PRs #145–#156 (+ #130–#137), live logs `planner-fail-*.json` (1787099982551), current tests.
> Do NOT trust this summary over code; code is source of truth.

## 1. Failure classes observed in live runs
| # | Historical class | PRs / evidence | Invariant violated | Current status in `main.mts` / helpers | Test that locks it |
|---|---|---|---|---|
| 1 | GH token / host-root assumptions (ambient `cwd`, `process.cwd` leak into container) | #145 `GH_TOKEN`, `REPO_ROOT = process.cwd()` at import, host `gh` vs container `/usr/bin/gh` | Host control-plane identity must not depend on ambient cwd/HEAD | `ghToken()` reads `REPO_ROOT/.sandcastle/.env`, all `exec` use `cwd: REPO_ROOT`, `ghBinary()` probes linux paths | `prompt-args.test`, manual `GH_TOKEN` log redaction |
| 2 | Java/Voxy sandbox mismatch (CI vs factory gradle/java) | #130, #145 hooks `install-voxy.sh`, `java -version` | Worker boundary must mirror CI before claiming work | `hooks.onSandboxReady` installs voxy + `java --version`; Doctor proves same | `doctor.test` bootstrap checks |
| 3 | Stale claims & restart reconciliation (claimed but dead, 0 commits) | #137, #146, #137 `reconcileInProgressIssues` | Restart/reconciliation deterministic, fail-closed, never loses work or leaves wrong `agent:in-progress` | 3-state `batchPR: found/unknown/absent`, empty-branch `hasCommits` check → clean, not block | `factory.test: empty branch lifecycle`, `acceptance.test G3/G4` |
| 4 | Quiet workers mistaken for dead (idle 600s kill of #126) | #147, #148 `idleTimeoutSeconds: 1800` deadman vs liveness | `1800s` emergency deadman only; liveness is output, not silent think | `idleTimeoutSeconds: 1800` + "Emergency deadman" label; terminology `No observable output` | `factory.test: quiet implementation not terminated` |
| 5 | Stale Sandcastle `dist` vs source identity (`95f3a5c`) | #149 Doctor provenance | Runtime Sandcastle code has provable source identity | Doctor checks `dist/index.js` contains `Agent alive`, `No observable output`, and mtime/hash cached | `doctor.test: runtime artifact provenance` |
| 6 | Caller-branch contamination / branch provenance | #150 `factoryBaseSha = origin/main`, `prepareIssueBranch` write-once | Caller checkout outside data plane; issue/batch never descendant of caller-only commits | `getFactoryBaseSha`, `isBranchAncestor(callerSha, issueBranch)==false`, `recordProvenance` `wx`, `verifyProvenance` | `factory.test: branch isolation`, `dispatch` caller-unchanged |
| 7 | Local / remote / diverged issue branches | #150–#152 `prepareIssueBranch` remote discovery | Remote truth handled explicitly; diverged → blocked fail-closed, remote-ahead → fast-forward | `prepareIssueBranch` does `ls-remote`, ancestor checks, `blocked` on diverged, `error` on lookup fail | `factory.test: remote-only, diverged, lookup failure` |
| 8 | Batch PR correlation / integration | #137 batch `sandcastle/batch-*` from `factoryBaseSha`, `Closes #N` + `Batch PR #N` comment | Batch is single writer, provenance `factoryBaseSha...batchBranch`, never `caller` | `createBatchWorktree`, `buildPrCreateArgs --head batchBranch`, `buildProtectedRootDiffSpec(factoryBaseSha...batchBranch)` | `factory.test: batch isolation`, `ci-policy.test` |
| 9 | Planner false structured-output rejection (`Output.object` vs fences) | #154 `Output.string` + `parsePlannerOutput` recovery | Deterministic safety not on perfect LLM syntax | `Output.string({tag:"plan"})` + `parsePlannerOutput` finds last `<plan>` | `planner.test` |
| 10 | Malformed planner output (double-escaped `{\"issues\":...}`) | live `planner-fail-1787099982551.json` (2026-08-19) | Same | `parsePlannerOutput` now tries candidates: `replace(/\\"/g,'"')` + string-wrapped | `planner-escape.test` (new) |
| 11 | Fail-open planner fallback (`planner failure → all eligible`) | pre-#154 → #154 fail-closed abort → NOW fail-closed single | Never fallback-to-all; LLM may improve parallelism but not block serial | `fallbackToSingle(sorted eligible → lowest N)`; main `catch` → `fallbackToSingle` not `break` nor `all` | `planner-fallback.test` (new), `planner-151-success.json` policy |
| 12 | Doctor worktree/branch cleanup (orphan `doctor-*`) | #154, #1d6d99d `cleanupDoctorBranchAndWorktree` | Temporary resources owned, cleaned in `finally`, reconciled after crash | `reconcileStaleDoctorResources` at Doctor startup + `assertNoStaleDoctorResources` before/after; `finally { chdir(REPO_ROOT); close; cleanup }` | `doctor.test` 7 cases |
| 13 | Doctor cache-hit stale resources | #154 | Same | Cache-hit still checks `dist` liveness + `assertNoStale` before returning `PASS (cached)` | `doctor.test: stale cleanup also on cache-hit` |
| 14 | Deleted / ambient `cwd` failures (`process.chdir` into worktree then deleted) | #1d6d99d | Host cwd never deleted-under; `REPO_ROOT` stable | `process.chdir(REPO_ROOT)` before/after cleanup, fail-closed if restore fails | `doctor.test: disappearing cwd` |
| 15 | Hidden `git fetch` diagnostics (`stdio:'ignore'`) | #154 | Malformed/transient externals have bounded recovery with visible diagnostics | `git fetch origin main --verbose` with captured `stdout/stderr/status` | manual log `factory-base` |

## 2. Architectural seams (why many bugs share one root)

| Seam | What it owns | Bugs it explains (from above) |
|---|---|---|
| **A. Ambient identity** (`process.cwd`, `HEAD`, `branch`, Windows `gh.exe`) vs explicit `REPO_ROOT` + frozen `factoryBaseSha` | Host control-plane identity, token, provenance | 1, 6, 8, 14, 15 |
| **B. Ephemeral ownership** (`worktree`/`branch`/`cache`/`dist` not owned → leak) vs `try/finally` + `reconcile*` + `assertNoStale*` strictly scoped `doctor-*` / `batch-*` | Orphan resources, stale claims, cache-hit stale | 3, 5, 12, 13, 14 |
| **C. Trusted LLM output** vs tolerant parse + fail-closed single + hallucination filter | Planner brittleness, fail-open | 9, 10, 11 |
| **D. Single provenance state machine** (`prepareIssueBranch` write-once `wx` + `verifyProvenance`) vs ad-hoc branch creation | Local/remote/diverged, caller contamination, legacy branches | 6, 7, 3 |
| **E. Bounded external recovery** (`MECHANICAL_RETRY_BUDGET=2`, `REVIEW_RETRY=1`, fetch retry 3, captured Gh diagnostics, `recovery/*.json`) vs infinite/unbounded/retry | Transient GH, reviewer, sandbox | 1, 2, 15 |

## 3. Defect separation

| Layer | What lives there | Recent defects |
|---|---|---|
| **Voxygen `.sandcastle` integration** (`voxygen-monorepo/.sandcastle/*`) | `main.mts` orchestration, `branch-helpers.mts`, `planner-helpers.mts`, `doctor-helpers.mts`, `dispatch.mts`, prompts, fixtures | 1, 3, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 (majority) |
| **Sandcastle library** (`rhythmatician/sandcastle`, sibling `../../sandcastle`) | `src/run.ts` timeouts (`worktreeMs`, `idleTimeoutSeconds`), `createSandbox` worktree lifecycle, Docker sandbox, `Output.string/object` | 4 (`idleTimeoutSeconds` deadman), 2 (Docker image `Sandcastle` build), 5 (dist provenance contract), 12 (worktree prune) |

Evidence: `package.json` `file:../../sandcastle` resolves to `../../sandcastle` (`/mnt/c/Users/JeffHall/git/sandcastle`), Doctor checks `dist/index.js` strings `Agent alive` / `No observable output`.

## 4. Tests before prod (TDD)

All tests are run by `npx vitest run` (no extra flags). TDD order: red → fix → green.

| Test file | New / Existing | What it proves (red → green) |
|---|---|---|
| `planner-escape.test.mts` | **new** | Double-escaped `{\"issues\":...}` inside `<plan>` parses after fix; hallucination still filtered; truly invalid still throws |
| `planner-fallback.test.mts` | **new** | Fallback never `all`; deterministic single lowest-N; empty → empty; branch default |
| `planner.test.mts` | existing | Valid fixture `planner-151-success.json` → `[151]` serialization |
| `factory.test.mts` | existing (20) | Empty-branch clean vs block, branch isolation, remote-only/diverged, quiet-worker idle, batch never on caller |
| `doctor.test.mts` | existing (7) | Stale `doctor-*` reconcile, scope, cache-hit, inspection-error, disappearing cwd |
| `dispatch.test.mts` + `acceptance.test.mts` | existing | Eligibility gates, `blockedByCount` fail-closed, `partitionWorkers` allSettled, G1–G6 |
| Planned: `reliability-matrix.test.mts` (next step) | **new planned** | One-shot matrix that asserts every row above via helpers without live GH/LLM (pure + tmp git) |

## 5. Where changes belong

| Change | Repo | Why |
|---|---|---|
| `planner-helpers.mts`: tolerant `parsePlannerOutput` + `fallbackToSingle` | `voxygen-monorepo` | Integration seam C; pure helper, no library change needed |
| `main.mts`: import `fallbackToSingle`, planner `catch` → `fallbackToSingle` not `break` | `voxygen-monorepo` | Integration seam C; preserves serial progress |
| All historical Doctor/branch fixes | `voxygen-monorepo` (already done) | Integration seams A/B/D already landed #145–#1d6d99d |
| No `rhythmatician/sandcastle` library change this round | `sandcastle` fork | Deadman `1800s` already `95f3a5c`; worktree lifecycle already principled; next library change only if new worktree-timeout evidence appears — deliberate pause |

## 6. Concrete exit criterion (when we stop touching Sandcastle and resume #151)

We resume ` #151 → #152 → human-attended #127` **iff** all of these are green *and* no new `logs/planner-fail-*.json` with same class without fallback:

1. `npx vitest run` all **191+** tests green (currently 191) — including 2 new planner suites.
2. `npx tsc --noEmit` clean.
3. Live proof (one dry run, no claims): `npx tsx .sandcastle/main.mts` with `GH_TOKEN=...` reaches Doctor `PASS`, fetches eligibility (including #151/#152), planner step shows either `selected 1/2` **or** `fallback to deterministic single #151` — never `aborting whole Sandcastle invocation` with 0 dispatched. Branch `sandcastle/issue-151` either not created yet or created from `factoryBaseSha` with provenance `wx` (verify `cat .sandcastle/provenance/sandcastle-issue-151.json`).
4. Adversarial local acceptance (no GH/LLM, tmp git) passes: `npx vitest run .sandcastle/reliability-matrix.test.mts` covering: normal 1-issue, overlap 2→1, malformed planner → single, stale empty branch → clean, remote diverged → blocked, caller dirty branch → unchanged, `doctor-*` leftover → clean.
5. Universal postconditions hold for that dry run: `git status --porcelain` unchanged vs before, `git branch --list 'sandcastle/issue-*'` contains at most `151` (not caller), `git worktree list --porcelain` has no `doctor-*`, no `recovery/*.json` newly claiming success, PR not created with wrong head (no `--head` without batch prefix).
6. After that, `git diff --stat HEAD` shows only the two coherent changes (`planner-helpers.mts` tolerance + `main.mts` fallback) — no further reactive patches in same wave.

If live run still emits `planner-fail-*.json` but dispatches `#151` via fallback single, that **is** success per contract (LLM may be imperfect). Only a *new* class (not escaped JSON, not planner) would require another wave.
