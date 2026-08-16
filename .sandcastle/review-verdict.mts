import { z } from "zod";

/**
 * Machine-readable review verdict — reviewer reviews against original issue
 * contract (fresh context). The factory gates merge on `approved`.
 *
 * - approved=true  → eligible for merger (all acceptance criteria met, no blocking findings)
 * - approved=false → do NOT merge; preserve branch; mark agent:blocked with findings
 */

export const findingSchema = z.object({
  message: z.string().min(1),
  severity: z.enum(["blocking", "nit", "suggestion"]).default("blocking"),
});

export const acceptanceCriterionSchema = z.object({
  criterion: z.string().min(1),
  met: z.boolean(),
  evidence: z.string().optional(),
});

export const reviewVerdictSchema = z.object({
  approved: z.boolean(),
  findings: z.array(findingSchema),
  acceptanceCriteriaMet: z.array(acceptanceCriterionSchema),
  summary: z.string().optional(),
});

export type Finding = z.infer<typeof findingSchema>;
export type AcceptanceCriterionResult = z.infer<typeof acceptanceCriterionSchema>;
export type ReviewVerdict = z.infer<typeof reviewVerdictSchema>;

/**
 * Parse a verdict from raw JSON (e.g., extracted from <verdict> tags or LLM output).
 * Returns parsed verdict or null if invalid.
 */
export function parseReviewVerdict(raw: unknown): ReviewVerdict | null {
  const result = reviewVerdictSchema.safeParse(raw);
  return result.success ? result.data : null;
}

/**
 * Extract JSON inside <verdict>...</verdict> tags from reviewer text output.
 * Returns parsed verdict or null.
 */
export function parseVerdictFromText(text: string): ReviewVerdict | null {
  const match = text.match(/<verdict>([\s\S]*?)<\/verdict>/i);
  if (!match) return null;
  const jsonText = match[1].trim();
  try {
    const raw = JSON.parse(jsonText);
    return parseReviewVerdict(raw);
  } catch {
    return null;
  }
}

/**
 * Whether a verdict gates the branch to merger.
 * Only approved===true with all criteria met and no blocking findings is merge-eligible.
 * Pure helper for tests and for main.mts gating.
 */
export function isVerdictApproved(verdict: ReviewVerdict | null | undefined): boolean {
  if (!verdict) return false;
  if (!verdict.approved) return false;
  // Defensive: even if approved true, ensure no blocking findings and all criteria met
  const hasBlocking = verdict.findings.some((f) => f.severity === "blocking");
  if (hasBlocking) return false;
  const allMet = verdict.acceptanceCriteriaMet.every((c) => c.met);
  if (!allMet) return false;
  return true;
}

/**
 * Pure gate: filter completed branches by verdicts.
 * Mirrors main.mts logic but testable without GH/sandbox.
 *
 * Inputs parallel `issues` and `verdicts` (null means reviewer failed to produce verdict → blocked).
 * Returns { approved, blocked } partitioned identically to how main.mts will handle completedBranches.
 */
export function gateBranchesByVerdict(
  issues: Array<{ id: string; branch: string }>,
  verdicts: Array<ReviewVerdict | null>,
): {
  approved: Array<{ id: string; branch: string; verdict: ReviewVerdict }>;
  blocked: Array<{ id: string; branch: string; verdict: ReviewVerdict | null; reason: string }>;
} {
  const approved: Array<{ id: string; branch: string; verdict: ReviewVerdict }> = [];
  const blocked: Array<{ id: string; branch: string; verdict: ReviewVerdict | null; reason: string }> = [];

  for (let i = 0; i < issues.length; i++) {
    const issue = issues[i]!;
    const verdict = verdicts[i] ?? null;
    if (isVerdictApproved(verdict)) {
      approved.push({ ...issue, verdict: verdict! });
    } else {
      const reason = !verdict
        ? "reviewer produced no verdict (treated as rejected)"
        : !verdict.approved
          ? `reviewer rejected: ${verdict.findings.map((f) => f.message).join("; ") || verdict.summary || "unmet criteria"}`
          : verdict.findings.some((f) => f.severity === "blocking")
            ? `blocking findings despite approved=true: ${verdict.findings.filter((f) => f.severity === "blocking").map((f) => f.message).join("; ")}`
            : `unmet acceptance criteria despite approved=true: ${verdict.acceptanceCriteriaMet.filter((c) => !c.met).map((c) => c.criterion).join("; ")}`;
      blocked.push({ ...issue, verdict, reason });
    }
  }
  return { approved, blocked };
}

/**
 * Helper for tests: create a minimal verdict fixture.
 */
export function verdictFixture(overrides: Partial<ReviewVerdict> = {}): ReviewVerdict {
  return {
    approved: true,
    findings: [],
    acceptanceCriteriaMet: [{ criterion: "sample criterion", met: true, evidence: "src/foo.ts:1" }],
    summary: "all good",
    ...overrides,
  };
}
