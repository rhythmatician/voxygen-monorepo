import { z } from "zod";

/**
 * Machine-readable review verdict — reviewer reviews against original issue
 * contract (fresh context). The factory gates merge on `approved`.
 *
 * - approved=true  → eligible for merger (all acceptance criteria met, no blocking findings)
 * - approved=false → do NOT merge; preserve branch; mark agent:blocked with findings
 *
 * Single source of truth for verdict shape, parsing, and merge-gating.
 * Factory host (main.mts) and tests import from here; reviewer prompt
 * describes the same schema for the LLM.
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

// Keep tag regex in one place — main.mts and tests rely on the same `<verdict>` contract.
const VERDICT_TAG_RE = /<verdict>([\s\S]*?)<\/verdict>/i;

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
  const match = text.match(VERDICT_TAG_RE);
  if (!match) return null;
  const jsonText = match[1]!.trim();
  try {
    const raw = JSON.parse(jsonText);
    return parseReviewVerdict(raw);
  } catch {
    return null;
  }
}

/**
 * Unified extraction from a reviewer run — mirrors the factory fallback chain:
 * 1) structured `output` (already parsed object)
 * 2) nested `output.verdict`
 * 3) `<verdict>` in `text`
 * 4) `<verdict>` in stringified `output`
 *
 * Pure and testable; main.mts delegates to this instead of reimplementing
 * the chain inline.
 */
export function extractVerdict(review: { output?: unknown; text?: string }): ReviewVerdict | null {
  const { output, text } = review;

  if (output && typeof output === "object") {
    const direct = parseReviewVerdict(output);
    if (direct) return direct;
    const nested = (output as Record<string, unknown>).verdict;
    if (nested) {
      const parsedNested = parseReviewVerdict(nested);
      if (parsedNested) return parsedNested;
    }
  }

  if (typeof text === "string") {
    const fromText = parseVerdictFromText(text);
    if (fromText) return fromText;
  }

  if (typeof output === "string") {
    const fromStringOutput = parseVerdictFromText(output);
    if (fromStringOutput) return fromStringOutput;
  }

  return null;
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
 * Human-readable reason for a blocked branch — single place that formats
 * the message posted to GitHub and logged by the factory. Keeps main.mts
 * and gateBranchesByVerdict consistent.
 */
export function blockedReasonForVerdict(verdict: ReviewVerdict | null): string {
  if (!verdict) return "reviewer produced no verdict (treated as rejected - branch preserved, not merged)";
  if (!verdict.approved) {
    const detail = verdict.findings.map((f) => f.message).join("; ") || verdict.summary || "unmet criteria";
    return `reviewer rejected (approved=false): ${detail}`;
  }
  const blocking = verdict.findings.filter((f) => f.severity === "blocking");
  if (blocking.length > 0) {
    return `blocking findings despite approved=true: ${blocking.map((f) => f.message).join("; ")}`;
  }
  const unmet = verdict.acceptanceCriteriaMet.filter((c) => !c.met);
  if (unmet.length > 0) {
    return `unmet acceptance criteria despite approved=true: ${unmet.map((c) => c.criterion).join("; ")}`;
  }
  return "rejected by verdict gate";
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
      blocked.push({ ...issue, verdict, reason: blockedReasonForVerdict(verdict) });
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
