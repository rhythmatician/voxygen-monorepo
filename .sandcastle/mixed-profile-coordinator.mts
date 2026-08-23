import { orchestrateResearchBatch, type ResearchBatchIssue, type ResearchWorkerResult, type RunResearchWorker, type BatchOrchestrationOps } from "./research-lifecycle.mts";
import type { ReviewVerdict } from "./review-verdict.mts";

export type ImplWorkerResult = { commits: string[]; verdict: ReviewVerdict | null; reviewText?: string };

export interface MixedProfileBatchParams {
  researchIssues: ResearchBatchIssue[];
  implIssues: { id: string; branch: string; title: string }[];
  runResearchWorker: RunResearchWorker;
  runImplWorker: (issue: { id: string; branch: string; title: string }) => Promise<ImplWorkerResult>;
  ops: BatchOrchestrationOps;
  shouldMutateOutcomeState: boolean;
}

/**
 * Production helper that genuinely starts research and impl together.
 * Returns promises so caller can await impl first (to proceed to review/merger)
 * and settle research only at every exit boundary. This is the seam that
 * main.mts must use — tests exercising this helper exercise production.
 */
export function startMixedProfileBatch(params: MixedProfileBatchParams): {
  implSettled: Promise<PromiseSettledResult<ImplWorkerResult>[]>;
  settleResearch: () => Promise<{ researchBatch: Awaited<ReturnType<typeof orchestrateResearchBatch>> | null; researchHadFactoryError: boolean }>;
  researchBatchPromise: Promise<Awaited<ReturnType<typeof orchestrateResearchBatch>>> | null;
} {
  const { researchIssues, implIssues, runResearchWorker, runImplWorker, ops, shouldMutateOutcomeState } = params;

  let researchBatchPromise: Promise<Awaited<ReturnType<typeof orchestrateResearchBatch>>> | null = null;
  if (researchIssues.length > 0) {
    researchBatchPromise = orchestrateResearchBatch({
      issues: researchIssues,
      runWorker: runResearchWorker,
      ops,
      shouldMutateOutcomeState,
    });
  }

  let implSettledPromise: Promise<PromiseSettledResult<ImplWorkerResult>[]> | null = null;
  if (implIssues.length > 0) {
    implSettledPromise = Promise.allSettled(implIssues.map(issue => runImplWorker(issue)));
  }

  const implSettled: Promise<PromiseSettledResult<ImplWorkerResult>[]> = implSettledPromise ?? Promise.resolve([] as PromiseSettledResult<ImplWorkerResult>[]);

  // Memoized settlement promise — concurrent callers await same result, fail-closed on unexpected rejection
  let settlementPromise: Promise<{ researchBatch: Awaited<ReturnType<typeof orchestrateResearchBatch>> | null; researchHadFactoryError: boolean }> | null = null;

  const settleResearch = (): Promise<{ researchBatch: Awaited<ReturnType<typeof orchestrateResearchBatch>> | null; researchHadFactoryError: boolean }> => {
    if (settlementPromise) return settlementPromise;
    settlementPromise = (async () => {
      if (!researchBatchPromise) {
        return { researchBatch: null, researchHadFactoryError: false };
      }
      try {
        const batch = await researchBatchPromise;
        return { researchBatch: batch, researchHadFactoryError: batch.hadFactoryError };
      } catch {
        // Unexpected rejection escaping the whole research batch is a FACTORY_ERROR — fail closed
        return { researchBatch: null, researchHadFactoryError: true };
      }
    })();
    return settlementPromise;
  };

  return { implSettled, settleResearch, researchBatchPromise };
}

/**
 * Production mixed-profile coordinator: starts research and impl concurrently,
 * lets impl proceed through review/merger without waiting for slow research,
 * ensures research per-ticket publishing happens independently,
 * and guarantees research is settled before leaving the iteration for ANY reason.
 * This is the extracted callable version of main.mts's post-claim lifecycle,
 * so tests can invoke the actual production code via injected runners.
 * Implemented via startMixedProfileBatch so the seam is identical to production.
 */
export async function coordinateMixedProfileBatch(params: MixedProfileBatchParams): Promise<{
  researchBatch: Awaited<ReturnType<typeof orchestrateResearchBatch>> | null;
  implSettled: PromiseSettledResult<ImplWorkerResult>[];
  researchHadFactoryError: boolean;
  maxActiveDuringRun?: number;
}> {
  const mixed = startMixedProfileBatch(params);
  const implSettled = await mixed.implSettled;
  const { researchBatch, researchHadFactoryError } = await mixed.settleResearch();
  return { researchBatch, implSettled, researchHadFactoryError };
}
