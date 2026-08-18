import { z } from "zod";

export const planSchema = z.object({
  issues: z.array(
    z.object({ id: z.string(), title: z.string(), branch: z.string() }),
  ),
});

export type PlannedIssue = z.infer<typeof planSchema>["issues"][number];

export interface IssueInput {
  number: number;
  title?: string;
  branch?: string;
  labels?: string[];
  body?: string;
}

/**
 * Testable helper: extract and validate planner <plan> output.
 * - Finds last <plan>...</plan> in stdout
 * - Unwraps optional markdown fences
 * - JSON.parse + Zod validation
 * - Filters to eligible IDs (drops hallucinated)
 * Returns selected subset (may be empty = defer).
 * Throws on missing tag / invalid JSON / schema failure with rawMatched for diagnostics.
 */
export function parsePlannerOutput(stdout: string, eligible: IssueInput[]): PlannedIssue[] {
  const raw = findLastTagContent(stdout, "plan");
  if (raw === undefined) {
    throw new Error('Structured output tag <plan> not found in planner output');
  }
  const unwrapped = unwrapFences(raw.trim());
  let parsed: unknown;
  try {
    parsed = JSON.parse(unwrapped);
  } catch (cause) {
    const err = new Error(`Structured output tag <plan> contains invalid JSON: ${(cause as Error).message}`);
    (err as unknown as { rawMatched?: string; cause?: unknown }).rawMatched = raw;
    (err as unknown as { cause?: unknown }).cause = cause;
    throw err;
  }
  const result = planSchema.safeParse(parsed);
  if (!result.success) {
    const err = new Error(`Structured output tag <plan> failed schema validation: ${result.error.message}`);
    (err as unknown as { rawMatched?: string; cause?: unknown }).rawMatched = raw;
    (err as unknown as { cause?: unknown }).cause = result.error;
    throw err;
  }
  const eligibleIds = new Set(eligible.map((e) => String(e.number)));
  return result.data.issues.filter((p) => eligibleIds.has(p.id));
}

function findLastTagContent(text: string, tag: string): string | undefined {
  const openTag = `<${tag}>`;
  const closeTag = `</${tag}>`;
  let lastContent: string | undefined;
  let searchFrom = 0;
  while (true) {
    const openIdx = text.indexOf(openTag, searchFrom);
    if (openIdx === -1) break;
    const contentStart = openIdx + openTag.length;
    const closeIdx = text.indexOf(closeTag, contentStart);
    if (closeIdx === -1) break;
    lastContent = text.slice(contentStart, closeIdx);
    searchFrom = closeIdx + closeTag.length;
  }
  return lastContent;
}

function unwrapFences(text: string): string {
  const fenceMatch = text.match(/^```(?:json)?\s*\n([\s\S]*?)\n\s*```\s*$/);
  if (fenceMatch) return fenceMatch[1]!.trim();
  return text;
}

/**
 * Attempt to recover planner output from a StructuredOutputError by re-extracting
 * from the independently captured valid planner text stream (full stdout), not by
 * re-parsing the already-failed rawMatched. If rawMatched failed JSON.parse, retrying
 * the same string cannot succeed — need the full stream where the valid <plan> lives.
 */
export function tryRecoverPlannerOutput(
  error: unknown,
  fullStdoutFallback: string,
  eligible: IssueInput[],
): PlannedIssue[] | null {
  const rawMatched = (error as unknown as { rawMatched?: string })?.rawMatched;
  // If error already has rawMatched and it was invalid JSON, don't re-parse it alone
  // Instead, try fullStdoutFallback which may contain a valid last <plan>
  if (fullStdoutFallback) {
    try {
      return parsePlannerOutput(fullStdoutFallback, eligible);
    } catch {}
  }
  if (rawMatched) {
    try {
      const parsed = JSON.parse(unwrapFences(rawMatched.trim()));
      const result = planSchema.safeParse(parsed);
      if (result.success) {
        const eligibleIds = new Set(eligible.map((e) => String(e.number)));
        return result.data.issues.filter((p) => eligibleIds.has(p.id));
      }
    } catch {}
  }
  return null;
}
