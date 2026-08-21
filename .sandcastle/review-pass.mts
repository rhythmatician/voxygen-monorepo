import { extractVerdict, isVerdictApproved, type ReviewVerdict } from "./review-verdict.mts";

export type ReviewerInput = {
  issueId: string;
  issueTitle: string;
  branch: string;
  attempt: number;
  issueBody: string;
  allCommits: string[];
  runReviewer: (issueBody: string, attempt: number, isRetry: boolean) => Promise<{
    stdout: string;
    output?: unknown;
  }>;
  onReviewerFailure: (issueId: string, attempt: number, error: unknown) => void;
  onInvalidVerdict: (issueId: string, attempt: number, reason: string, reviewText: string) => void;
};

export type ReviewedImplementationResult = {
  commits: string[];
  verdict: ReviewVerdict | null;
  reviewText: string;
};

export async function runReviewerPass(input: ReviewerInput): Promise<ReviewedImplementationResult> {
  const {
    issueId,
    allCommits,
    runReviewer,
    attempt,
    issueBody,
    onReviewerFailure,
    onInvalidVerdict,
  } = input;

  let reviewText = "";
  let verdict: ReviewVerdict | null = null;

  try {
    const review = await runReviewer(issueBody, attempt, attempt > 0);
    reviewText = review.stdout;
    verdict = extractVerdict(review);
  } catch (reviewError: unknown) {
    reviewText = String(reviewError);
    onReviewerFailure(issueId, attempt, reviewError);
    return { commits: allCommits, verdict: null, reviewText };
  }

  if (verdict === null) {
    onInvalidVerdict(issueId, attempt, `reviewer produced no machine-readable verdict (attempt ${attempt + 1})`, reviewText);
    return { commits: allCommits, verdict: null, reviewText };
  }

  if (isVerdictApproved(verdict)) {
    return { commits: allCommits, verdict, reviewText };
  }

  return { commits: allCommits, verdict, reviewText };
}
