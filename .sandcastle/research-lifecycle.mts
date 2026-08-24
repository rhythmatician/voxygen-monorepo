import type { ResearchResult } from "./research-result.mts";
import { researchResultSchema, formatResearchResultForComment } from "./research-result.mts";
import { canClaimNextOuterIteration } from "./factory-verdict-gate.mts";
import type { WorkerOutcome } from "./factory-verdict-gate.mts";

/**
 * Research lifecycle — bounded orchestration for AFK research profile.
 *
 * Invariants:
 * - Research may produce zero or more optional commits. Commits are preserved
 *   on the dedicated research branch and never enter implementation review,
 *   merger, batch integration, push, PR creation, or auto-merge paths.
 * - Successful completion: result published, parent pointer published if required,
 *   ticket closed, transient claim (agent:in-progress + assignee) removed,
 *   ticket not redispatched because it is closed. Wayfinder:research lifecycle only.
 * - Factory failure during close/cleanup or parent-pointer publication:
 *   ticket remains open, transient claim removed,
 *   no agent:blocked, outcome FACTORY_ERROR, outer progression stops,
 *   retryable on next run, sibling successes preserved. Wayfinder:research lifecycle only.
 * - Parent pointer (Part of #N) is required when present in body. Failure is
 *   FACTORY_ERROR, not non-blocking. Close is not attempted on parent failure.
 */

export const RESEARCH_OUTPUT_TAG = "research" as const;

export function extractParentMapId(body?: string): string | null {
  if (!body) return null;
  const m = body.match(/Part of #(\d+)/);
  return m ? m[1] : null;
}

export function researchResultMarker(issueId: string): string {
  return `<!-- research-result:${issueId} -->`;
}

export function researchParentMarker(researchId: string, parentId: string): string {
  return `<!-- research-parent-pointer:${researchId}->${parentId} -->`;
}

export type ResearchGhOps = {
  safeRunGh: (args: string[], context?: string) => Promise<boolean>;
  runGh: (args: string[]) => Promise<string>;
};

export async function publishResearchResult(
  issueId: string,
  branch: string,
  result: ResearchResult,
  rawText: string,
  ops: ResearchGhOps,
): Promise<boolean> {
  const formatted = formatResearchResultForComment(result);
  const marker = researchResultMarker(issueId);
  const body = `${marker}\nResearch completed on \`${branch}\`.\n\n${formatted}\n\n---\n*Raw output truncated:* \`\`\`\n${rawText.slice(0, 2000)}\n\`\`\``;
  return ops.safeRunGh(["issue", "comment", issueId, "--body", body], `Failed to publish research result to #${issueId}`);
}

export async function addParentMapPointer(
  parentId: string,
  researchId: string,
  researchTitle: string,
  result: ResearchResult,
  ops: ResearchGhOps,
): Promise<boolean> {
  const summaryOneLine = result.summary.replace(/\s+/g, " ").slice(0, 300);
  const marker = researchParentMarker(researchId, parentId);
  const body = `${marker}\nResearch #${researchId} — ${researchTitle}: ${summaryOneLine}\n\nRecommendation: ${result.recommendation.slice(0, 500)}\nSee #${researchId} for evidence-backed findings.`;
  return ops.safeRunGh(["issue", "comment", parentId, "--body", body], `Failed to add parent map pointer to #${parentId}`);
}

/**
 * Releases transient claim (agent:in-progress + assignee) while retaining
 * wayfinder:research and never adding agent:blocked. Used for FACTORY_ERROR
 * and as part of close.
 */
export async function releaseResearchTransientClaim(
  issueId: string,
  ops: ResearchGhOps,
): Promise<boolean> {
  return ops.safeRunGh(
    ["issue", "edit", issueId, "--remove-label", "agent:in-progress", "--remove-assignee", "@me"],
    `Failed to release research claim for #${issueId}`,
  );
}

/**
 * Closes research ticket: releases transient claim then closes issue.
 * Wayfinder:research lifecycle only — no agent:research retained.
 * On close failure, transient remains released, leaving ticket
 * open and retryable (caller maps to FACTORY_ERROR).
 *
 * Returns true only if the issue is closed and transient was released.
 * If transient release fails, close is not attempted and FACTORY_ERROR
 * is signaled. Close failure always returns false even if release
 * succeeded — caller will treat as FACTORY_ERROR and attempt release
 * again (idempotent).
 */
export async function closeResearchTicket(
  issueId: string,
  branch: string,
  ops: ResearchGhOps,
): Promise<boolean> {
  // Release transient first; if it fails, do not attempt close — report FACTORY_ERROR.
  const released = await releaseResearchTransientClaim(issueId, ops);
  if (!released) {
    return false;
  }
  try {
    await ops.runGh(["issue", "close", issueId, "--comment", `Research completed on \`${branch}\` — findings published.`]);
    return true;
  } catch {
    try {
      await ops.runGh(["issue", "comment", issueId, "--body", `Research completed on \`${branch}\` — findings published.`]);
      await ops.runGh(["issue", "close", issueId]);
      return true;
    } catch {
      return false;
    }
  }
}

export async function markResearchFactoryError(
  issueId: string,
  branch: string,
  reason: string,
  ops: ResearchGhOps,
): Promise<boolean> {
  const shortReason = reason.slice(0, 800);
  const released = await releaseResearchTransientClaim(issueId, ops);
  if (!released) {
    // Do not claim release if we couldn't confirm it; leave external state untouched for retry
    console.warn(`  [research] Failed to release transient claim for #${issueId} — will retry next startup, not claiming released`);
    return false;
  }
  const commentOk = await ops.safeRunGh(
    ["issue", "comment", issueId, "--body", `Sandcastle factory infrastructure failed on \`${branch}\` — preserved branch for inspection.\n\n**Reason:** ${shortReason}\n\nBranch: \`${branch}\`\n\nThe issue was released for retry without being marked semantically blocked.`],
  );
  return released && commentOk;
}

export type ResearchOutcome = "SUCCESS" | "FACTORY_ERROR";

export interface SingleResearchLifecycleParams {
  issue: { id: string; branch: string; title: string; body?: string };
  result: ResearchResult;
  rawText: string;
  ops: ResearchGhOps;
  commits?: string[];
}

export interface SingleResearchLifecycleResult {
  outcome: ResearchOutcome;
  closeAttempted: boolean;
  parentPointerAttempted: boolean;
  parentPointerSucceeded?: boolean;
}

/**
 * Completes lifecycle for a single research issue after worker has produced
 * a validated result. Orchestrates: publish result → parent pointer (if required)
 * → close. Each step's failure maps to FACTORY_ERROR with transient release,
 * retained research authorization, no blocked, retryable, and close not attempted
 * after parent failure.
 *
 * Commits, if present, are preserved on the branch and do not affect outcome.
 * They never invoke implementation paths (caller must not call review/merger).
 */
export async function completeResearchLifecycle(
  params: SingleResearchLifecycleParams,
): Promise<SingleResearchLifecycleResult> {
  const { issue, result, rawText, ops } = params;

  // Commits are optional and preserved — log but do not fail or integrate.
  if (params.commits && params.commits.length > 0) {
    // Log preservation; no integration.
    // In production main.mts this is also logged via console.warn/info, but here we keep lifecycle pure.
  }

  // 1. Publish research result
  const published = await publishResearchResult(issue.id, issue.branch, result, rawText, ops);
  if (!published) {
    await markResearchFactoryError(issue.id, issue.branch, `publication failed for research #${issue.id}`, ops);
    return { outcome: "FACTORY_ERROR", closeAttempted: false, parentPointerAttempted: false };
  }

  // 2. Parent map pointer if required (Part of #N)
  const parentId = extractParentMapId(issue.body);
  if (parentId) {
    const pointerOk = await addParentMapPointer(parentId, issue.id, issue.title, result, ops);
    if (!pointerOk) {
      // Required pointer failed: do not close, FACTORY_ERROR, release transient, retain research
      await markResearchFactoryError(issue.id, issue.branch, `parent map pointer to #${parentId} failed for research #${issue.id}`, ops);
      return { outcome: "FACTORY_ERROR", closeAttempted: false, parentPointerAttempted: true, parentPointerSucceeded: false };
    }
    // pointer succeeded, continue to close
    const closed = await closeResearchTicket(issue.id, issue.branch, ops);
    if (!closed) {
      await markResearchFactoryError(issue.id, issue.branch, `failed to close research ticket #${issue.id}`, ops);
      return { outcome: "FACTORY_ERROR", closeAttempted: true, parentPointerAttempted: true, parentPointerSucceeded: true };
    }
    return { outcome: "SUCCESS", closeAttempted: true, parentPointerAttempted: true, parentPointerSucceeded: true };
  } else {
    const closed = await closeResearchTicket(issue.id, issue.branch, ops);
    if (!closed) {
      await markResearchFactoryError(issue.id, issue.branch, `failed to close research ticket #${issue.id}`, ops);
      return { outcome: "FACTORY_ERROR", closeAttempted: true, parentPointerAttempted: false };
    }
    return { outcome: "SUCCESS", closeAttempted: true, parentPointerAttempted: false };
  }
}

// ---------------------------------------------------------------------------
// Batch orchestration — production fan-out with injected worker execution.
// ---------------------------------------------------------------------------

export interface ResearchBatchIssue {
  id: string;
  branch: string;
  title: string;
  body?: string;
}

export type ResearchWorkerResult = { result: ResearchResult; rawText: string; commits?: string[] };

export type RunResearchWorker = (issue: ResearchBatchIssue) => Promise<ResearchWorkerResult>;

export interface BatchOrchestrationOps extends ResearchGhOps {
  // Inherits runGh/safeRunGh for publication steps.
}

export interface OrchestrateResearchBatchParams {
  issues: ResearchBatchIssue[];
  runWorker: RunResearchWorker;
  ops: BatchOrchestrationOps;
  shouldMutateOutcomeState?: boolean; // mirrors QUALIFICATION_LIFECYCLE.mutateOutcomeState; defaults to true
}

export interface OrchestrateResearchBatchResult {
  settled: Array<{ status: "fulfilled"; value: ResearchWorkerResult } | { status: "rejected"; reason: unknown }>;
  outcomes: Map<string, ResearchOutcome>;
  hadFactoryError: boolean;
  publishedIds: string[];
  succeededIds: string[];
  failedIds: string[];
}

/**
 * Production orchestration: launches all injected workers concurrently via
 * Promise.allSettled before any is released, then publishes results host-side
 * sequentially. Sibling successes are preserved even if one worker or one
 * publication fails. Parent-pointer and close failures are required (not
 * non-blocking) and map to FACTORY_ERROR without closing.
 *
 * This is the callable unit for tests; main.mts delegates to it with real
 * sandbox workers and real GitHub ops. Tests inject fake workers with a
 * barrier to prove concurrency without reimplementing Promise.allSettled.
 */
/**
 * Production decision helpers for mixed-profile outer loop.
 * Extracted so tests can exercise the real gate logic instead of
 * reconstructing booleans. If main.mts regresses to the old
 * `|| researchHadFactoryError` early break, these helpers will catch it.
 */
export function shouldStopBeforeMergerForFactoryError(
  partition: { completed: unknown[]; factoryErrors: unknown[]; shouldStopOuterLoop: boolean },
): boolean {
  // Only implementation factory errors should stop before merger; research errors are deferred.
  return !canClaimNextOuterIteration(partition as unknown as Parameters<typeof canClaimNextOuterIteration>[0]);
}

export function shouldStopBeforeNextClaimForResearchError(researchHadFactoryError: boolean): boolean {
  return researchHadFactoryError;
}

export async function orchestrateResearchBatch(
  params: OrchestrateResearchBatchParams,
): Promise<OrchestrateResearchBatchResult> {
  const { issues, runWorker, ops, shouldMutateOutcomeState = true } = params;

  // Independent per-issue pipelines: worker → validation → publish → parent → close.
  // Start all pipelines before awaiting any, so all researchers become active before any is released,
  // but each fast successful ticket publishes immediately without waiting for stragglers.
  const pipelines = issues.map(async (issue): Promise<ResearchWorkerResult> => {
    let workerResult: ResearchWorkerResult;
    try {
      workerResult = await runWorker(issue);
    } catch (e) {
      if (shouldMutateOutcomeState) {
        const reason = String((e as any)?.message ?? String(e ?? "unknown"));
        await markResearchFactoryError(issue.id, issue.branch, reason, ops);
      }
      throw e;
    }

    const parsed = researchResultSchema.safeParse(workerResult.result);
    if (!parsed.success) {
      if (shouldMutateOutcomeState) {
        await markResearchFactoryError(issue.id, issue.branch, `research validation failed for #${issue.id}: ${parsed.error.message}`, ops);
      }
      throw new Error(`research validation failed for #${issue.id}: ${parsed.error.message}`);
    }

    if (!shouldMutateOutcomeState) {
      return workerResult;
    }

    const lifecycle = await completeResearchLifecycle({
      issue,
      result: workerResult.result,
      rawText: workerResult.rawText,
      ops,
      commits: workerResult.commits,
    });

    if (lifecycle.outcome === "FACTORY_ERROR") {
      throw new Error(`lifecycle FACTORY_ERROR for #${issue.id}`);
    }

    return workerResult;
  });

  const settled = await Promise.allSettled(pipelines);

  const outcomes = new Map<string, ResearchOutcome>();
  let hadFactoryError = false;
  const publishedIds: string[] = [];
  const succeededIds: string[] = [];
  const failedIds: string[] = [];

  for (let i = 0; i < issues.length; i++) {
    const issue = issues[i]!;
    const outcome = settled[i]!;
    if (outcome.status === "fulfilled") {
      outcomes.set(issue.id, "SUCCESS");
      if (shouldMutateOutcomeState) {
        publishedIds.push(issue.id);
      }
      succeededIds.push(issue.id);
    } else {
      hadFactoryError = true;
      outcomes.set(issue.id, "FACTORY_ERROR");
      failedIds.push(issue.id);
    }
  }

  return { settled: settled as OrchestrateResearchBatchResult["settled"], outcomes, hadFactoryError, publishedIds, succeededIds, failedIds };
}
