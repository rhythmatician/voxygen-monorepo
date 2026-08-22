import { orchestrateResearchBatch, type ResearchBatchIssue, type RunResearchWorker, type BatchOrchestrationOps } from "./research-lifecycle.mts";
import { partitionWorkerOutcomes, partitionToMutationPlan, type WorkerOutcome } from "./factory-verdict-gate.mts";
import type { IssueInput } from "./dispatch.mts";

export type ImplWorkerResult = { commits: string[]; verdict: any };

export interface MixedProfileBatchParams {
  researchIssues: ResearchBatchIssue[];
  implIssues: { id: string; branch: string; title: string }[];
  eligible: IssueInput[]; // for impl body
  runResearchWorker: RunResearchWorker;
  runImplWorker: (issue: { id: string; branch: string; title: string }) => Promise<ImplWorkerResult>;
  ops: BatchOrchestrationOps;
  shouldMutateOutcomeState: boolean;
}

/**
 * Production mixed-profile coordinator: starts research and impl concurrently,
 * lets impl proceed through review/merger without waiting for slow research,
 * ensures research per-ticket publishing happens independently,
 * and guarantees research is settled before leaving the iteration for ANY reason.
 * This is the extracted callable version of main.mts's post-claim lifecycle,
 * so tests can invoke the actual production code via injected runners.
 */
export async function coordinateMixedProfileBatch(params: MixedProfileBatchParams): Promise<{
  researchBatch: Awaited<ReturnType<typeof orchestrateResearchBatch>> | null;
  implSettled: PromiseSettledResult<ImplWorkerResult>[];
  researchHadFactoryError: boolean;
  maxActiveDuringRun?: number;
}> {
  const { researchIssues, implIssues, runResearchWorker, runImplWorker, ops, shouldMutateOutcomeState } = params;

  let researchBatch: Awaited<ReturnType<typeof orchestrateResearchBatch>> | null = null;
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

  // Track max concurrency for testing - not needed in prod, but we expose via side effect if needed
  // For now, just await impl first
  const implSettled = (await (implSettledPromise ?? Promise.resolve([] as PromiseSettledResult<ImplWorkerResult>[]))) as PromiseSettledResult<ImplWorkerResult>[];

  // Single epilogue: before leaving iteration for ANY reason, settle research
  let researchHadFactoryError = false;
  if (researchBatchPromise) {
    const batch = await researchBatchPromise;
    researchBatch = batch;
    researchHadFactoryError = batch.hadFactoryError;
  }

  return { researchBatch, implSettled, researchHadFactoryError };
}
