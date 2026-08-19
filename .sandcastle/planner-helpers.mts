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
    // Bounded recovery for LLM escaping variants — deterministic, not infinite retry.
    // Live failure 2026-08-19: planner emitted {\"issues\":...} (single-escaped quotes) inside <plan>.
    // Direct JSON.parse fails at position 1; unescaping once yields valid JSON.
    // Try in order: (1) single unescape \" -> ", (2) parse as JSON string wrapping JSON.
    const candidates: string[] = [];
    // Candidate 1: unescape \" -> " (covers double-escaped inside <plan>)
    if (unwrapped.includes('\\"')) {
      candidates.push(unwrapped.replace(/\\"/g, '"'));
    }
    // Candidate 2: if unwrapped is a JSON string literal like "{\"issues\":...}", parse string then inner
    // Heuristic: starts with " and ends with " and contains \"
    if (unwrapped.startsWith('"') && unwrapped.endsWith('"') && unwrapped.includes('\\"')) {
      try {
        const asString = JSON.parse(unwrapped);
        if (typeof asString === 'string') candidates.push(asString);
      } catch {}
    }
    // Candidate 3: strip backslash escapes more aggressively (for fenced + escaped combo)
    // Only if candidate1 still fails, try interpreting via JSON string decode
    let recovered: unknown | null = null;
    let lastCause: unknown = cause;
    for (const cand of candidates) {
      try {
        recovered = JSON.parse(cand);
        break;
      } catch (e) {
        lastCause = e;
      }
      // Also try unescaping then parsing again (double layer)
      try {
        const double = cand.replace(/\\"/g, '"');
        recovered = JSON.parse(double);
        break;
      } catch (e) {
        lastCause = e;
      }
    }
    if (recovered !== null) {
      parsed = recovered;
    } else {
      const err = new Error(`Structured output tag <plan> contains invalid JSON: ${(lastCause as Error).message}`);
      (err as unknown as { rawMatched?: string; cause?: unknown }).rawMatched = raw;
      (err as unknown as { cause?: unknown }).cause = lastCause;
      throw err;
    }
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
 * Deterministic fallback when planner is malformed/unavailable.
 * Never fallback to all — serial progress only. LLM may improve parallelism
 * but must not make basic serial progress impossible. Sorted by number for determinism.
 */
export function fallbackToSingle(eligible: IssueInput[]): PlannedIssue[] {
  if (eligible.length === 0) return [];
  const sorted = [...eligible].sort((a, b) => a.number - b.number);
  const first = sorted[0]!;
  return [{ id: String(first.number), title: first.title ?? "", branch: first.branch ?? `sandcastle/issue-${first.number}` }];
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
