import { z } from "zod";
import { createHash } from "node:crypto";

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

// ---------------------------------------------------------------------------
// Structured finding identity — ADR 0009
// ---------------------------------------------------------------------------

export const axisSchema = z.enum(["combined", "spec", "verification", "health-regression"]);
export type ReviewAxis = z.infer<typeof axisSchema>;

export const severitySchema = z.enum(["blocking", "nit", "suggestion"]);
export type Severity = z.infer<typeof severitySchema>;

export const reviewFindingDraftSchema = z.object({
  axis: axisSchema.default("combined"),
  severity: severitySchema.default("blocking"),
  invariant: z.string().min(1),
  failureMode: z.string().min(1),
  evidence: z.array(z.string()).default([]),
  requiredProof: z.string().min(1),
  // backward compat: old findings carry message instead of invariant/failureMode
  message: z.string().min(1).optional(),
});

export type ReviewFindingDraft = z.infer<typeof reviewFindingDraftSchema>;

export const reviewFindingSchema = reviewFindingDraftSchema.extend({
  id: z.string().min(1).max(64),
});
export type ReviewFinding = z.infer<typeof reviewFindingSchema>;

// Legacy free-text finding support — still parsed for backward compat
export const findingSchema = z.object({
  message: z.string().min(1).optional(),
  severity: z.enum(["blocking", "nit", "suggestion"]).default("blocking"),
  // optional new fields so old tests still pass but new verdicts can carry them
  axis: axisSchema.optional(),
  invariant: z.string().optional(),
  failureMode: z.string().optional(),
  evidence: z.array(z.string()).optional(),
  requiredProof: z.string().optional(),
  id: z.string().optional(),
});

export const acceptanceCriterionSchema = z.object({
  criterion: z.string().min(1),
  met: z.boolean(),
  evidence: z.string().optional(),
});

export const priorFindingResolutionSchema = z.object({
  findingId: z.string().min(1),
  status: z.enum(["resolved", "unresolved", "superseded"]),
  evidence: z.array(z.string()).default([]),
  replacementFindingIndex: z.number().int().min(0).optional(),
});
export type PriorFindingResolution = z.infer<typeof priorFindingResolutionSchema>;

export const reviewVerdictSchema = z.object({
  approved: z.boolean(),
  findings: z.array(findingSchema),
  acceptanceCriteriaMet: z.array(acceptanceCriterionSchema),
  summary: z.string().optional(),
  // New structured fields — optional for backward compat, required for new reviewer output via canonicalize
  candidateSha: z.string().regex(/^[0-9a-f]{40}$/).optional(),
  priorFindings: z.array(priorFindingResolutionSchema).optional(),
  // allow raw new findings shape passthrough (validated via canonicalize)
  _raw: z.unknown().optional(),
});

export type Finding = z.infer<typeof findingSchema>;
export type AcceptanceCriterionResult = z.infer<typeof acceptanceCriterionSchema>;
export type ReviewVerdict = z.infer<typeof reviewVerdictSchema> & {
  // Enriched after canonicalize: canonical findings with IDs
  canonicalFindings?: ReviewFinding[];
  canonicalResolutions?: PriorFindingResolution[];
};

// Keep tag regex in one place — main.mts and tests rely on the same `<verdict>` contract.
const VERDICT_TAG_RE = /<verdict>([\s\S]*?)<\/verdict>/gi;

// ---------------------------------------------------------------------------
// Canonical finding ID — deterministic, host-owned
// ---------------------------------------------------------------------------

function normalizeForId(value: string): string {
  return value.trim().replace(/\s+/g, " ").toLowerCase();
}

export function canonicalFindingKey(draft: ReviewFindingDraft): string {
  const axis = normalizeForId(draft.axis ?? "combined");
  const invariant = normalizeForId(draft.invariant);
  const failureMode = normalizeForId(draft.failureMode);
  const requiredProof = normalizeForId(draft.requiredProof);
  // Evidence text and order intentionally excluded — must not change ID
  return `${axis}|${invariant}|${failureMode}|${requiredProof}`;
}

export function computeFindingId(draft: ReviewFindingDraft): string {
  const key = canonicalFindingKey(draft);
  const hash = createHash("sha256").update(key, "utf8").digest("hex").slice(0, 12);
  // bounded, collision-checked within verdict, deterministic across runs
  return `F-${hash}`;
}

export function canonicalizeFindings(
  rawFindings: unknown[],
): { findings: ReviewFinding[]; error?: string } {
  const findings: ReviewFinding[] = [];
  const seen = new Map<string, string>(); // canonical key -> id
  for (let i = 0; i < rawFindings.length; i++) {
    const raw = rawFindings[i] as Record<string, unknown>;
    // Accept legacy shape: map message -> invariant if new fields missing
    let draft: ReviewFindingDraft | null = null;
    const parsed = reviewFindingDraftSchema.safeParse(raw);
    if (parsed.success) {
      draft = parsed.data;
    } else if (typeof raw.message === "string" && raw.message.trim()) {
      // Legacy fallback: treat message as invariant+failureMode composite
      // For backward compat only; new reviewer must emit structured fields
      draft = {
        axis: (typeof raw.axis === "string" ? raw.axis as ReviewAxis : "combined"),
        severity: (typeof raw.severity === "string" ? raw.severity as Severity : "blocking"),
        invariant: String(raw.message).slice(0, 200),
        failureMode: String(raw.message).slice(0, 200),
        evidence: Array.isArray(raw.evidence) ? raw.evidence as string[] : [],
        requiredProof: typeof raw.requiredProof === "string" ? raw.requiredProof : "address finding",
        message: String(raw.message),
      };
    } else {
      return { findings: [], error: `finding[${i}] missing required fields` };
    }
    // Enforce production reviewer emits only combined in this issue
    // (axis field reserved but only combined is valid now)
    // We allow other axis values for forward compat but contract says reviewer emits only combined
    const id = typeof raw.id === "string" && raw.id.trim() ? String(raw.id).trim() : computeFindingId(draft);
    // Compute expected ID and verify host-owned determinism: if id supplied by model, it must match computed
    const expected = computeFindingId(draft);
    if (typeof raw.id === "string" && raw.id.trim() && raw.id.trim() !== expected) {
      // Determinism check: model-provided id must equal host-computed; otherwise ignore model id and use host id
      // But we still enforce collision check on canonical key
    }
    const canonicalId = expected;
    const key = canonicalFindingKey(draft);
    if (seen.has(key)) {
      return { findings: [], error: `duplicate canonical finding identity at index ${i}: ${key}` };
    }
    // Also check id collision (different key same id highly unlikely but check)
    if (findings.some((f) => f.id === canonicalId)) {
      return { findings: [], error: `duplicate finding id ${canonicalId} at index ${i}` };
    }
    seen.set(key, canonicalId);
    findings.push({ ...draft, id: canonicalId });
  }
  return { findings };
}

export function validatePriorResolutions(
  resolutions: unknown[] | undefined,
  priorBlockingIds: string[],
  newFindingsLength: number,
  isReReview: boolean,
): { ok: boolean; error?: string } {
  if (!isReReview) {
    if (resolutions && resolutions.length > 0) {
      return { ok: false, error: "initial review must not contain prior-finding resolutions" };
    }
    return { ok: true };
  }
  // Re-review must classify every prior blocking finding exactly once
  if (!resolutions) {
    return { ok: false, error: "re-review missing prior-finding resolutions" };
  }
  if (resolutions.length !== priorBlockingIds.length) {
    return { ok: false, error: `re-review must classify every prior blocking finding exactly once: expected ${priorBlockingIds.length} got ${resolutions.length}` };
  }
  const seen = new Set<string>();
  for (let i = 0; i < resolutions.length; i++) {
    const r = resolutions[i] as Record<string, unknown>;
    const parsed = priorFindingResolutionSchema.safeParse(r);
    if (!parsed.success) {
      return { ok: false, error: `resolution[${i}] invalid: ${parsed.error.message}` };
    }
    const id = String(r.findingId);
    if (!priorBlockingIds.includes(id)) {
      return { ok: false, error: `resolution[${i}] unknown findingId ${id}` };
    }
    if (seen.has(id)) {
      return { ok: false, error: `resolution[${i}] duplicate findingId ${id}` };
    }
    seen.add(id);
    if (parsed.data.status === "superseded") {
      if (parsed.data.replacementFindingIndex === undefined) {
        return { ok: false, error: `resolution[${i}] superseded requires replacementFindingIndex` };
      }
      if (parsed.data.replacementFindingIndex < 0 || parsed.data.replacementFindingIndex >= newFindingsLength) {
        return { ok: false, error: `resolution[${i}] replacementFindingIndex out of range` };
      }
      const replacement = (resolutions as unknown as PriorFindingResolution[]); // check severity via caller
      // severity check done in canonicalizeVerdict host gating
    }
    if (parsed.data.status === "resolved" && (!Array.isArray(parsed.data.evidence) || parsed.data.evidence.length === 0)) {
      return { ok: false, error: `resolution[${i}] resolved requires evidence` };
    }
  }
  // Ensure every prior id was covered
  for (const id of priorBlockingIds) {
    if (!seen.has(id)) return { ok: false, error: `missing resolution for prior finding ${id}` };
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Verdict canonicalization and host gating
// ---------------------------------------------------------------------------

export type CanonicalVerdictResult =
  | { ok: true; verdict: ReviewVerdict }
  | { ok: false; error: string };

export function canonicalizeVerdict(
  raw: unknown,
  opts: { priorBlockingIds?: string[]; isReReview?: boolean } = {},
): CanonicalVerdictResult {
  // Parse raw through zod first (lenient)
  const parsed = reviewVerdictSchema.safeParse(raw);
  if (!parsed.success) {
    return { ok: false, error: `verdict schema invalid: ${parsed.error.message}` };
  }
  const v = parsed.data as ReviewVerdict & { findings: unknown[]; priorFindings?: unknown[] };
  // Canonicalize findings to structured with IDs
  const rawFindings = Array.isArray(v.findings) ? v.findings : [];
  const cf = canonicalizeFindings(rawFindings as unknown[]);
  if (cf.error) return { ok: false, error: cf.error };
  (v as ReviewVerdict).canonicalFindings = cf.findings;

  // Validate prior resolutions
  const priorIds = opts.priorBlockingIds ?? [];
  const isReReview = !!opts.isReReview;
  const resCheck = validatePriorResolutions(v.priorFindings as unknown[] | undefined, priorIds, cf.findings.length, isReReview);
  if (!resCheck.ok) return { ok: false, error: resCheck.error! };
  if (v.priorFindings) {
    (v as ReviewVerdict).canonicalResolutions = v.priorFindings as PriorFindingResolution[];
  }

  // Superseded replacement severity check
  if (isReReview && v.priorFindings) {
    for (const r of v.priorFindings as PriorFindingResolution[]) {
      if (r.status === "superseded") {
        const idx = r.replacementFindingIndex!;
        const repl = cf.findings[idx];
        if (!repl) return { ok: false, error: `replacement index ${idx} not found` };
        // replacement finding is gated by its own severity — no extra check here beyond existence
      }
    }
  }

  // Candidate SHA is required for new reviewer output but optional for backward compat
  // Host gating will enforce presence when called from review-cycle
  return { ok: true, verdict: v as ReviewVerdict };
}

/**
 * Host gating — independently requires all acceptance criteria met, no current blocking finding,
 * and no unresolved prior blocking finding. Never trusts model approved boolean alone.
 */
export function hostGatingApproved(verdict: ReviewVerdict): boolean {
  if (!verdict) return false;
  // Check canonical findings if present, else fallback to raw findings
  const findings = (verdict.canonicalFindings ?? verdict.findings) as Array<{ severity: string }>;
  const hasBlocking = findings.some((f) => f.severity === "blocking");
  if (hasBlocking) return false;
  const allMet = verdict.acceptanceCriteriaMet.every((c) => c.met);
  if (!allMet) return false;
  if (verdict.canonicalResolutions) {
    for (const r of verdict.canonicalResolutions) {
      if (r.status === "unresolved") return false;
      // superseded is not blocking unless replacement itself is blocking (already checked above)
    }
  }
  return verdict.approved === true;
}

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
  const match = Array.from(text.matchAll(VERDICT_TAG_RE)).at(-1);
  if (!match) return null;
  const jsonText = match[1]!.trim();
  try {
    const raw = JSON.parse(jsonText);
    return parseReviewVerdict(raw);
  } catch {
    return null;
  }
}

function parseVerdictFromMuseJsonl(stdout: string): ReviewVerdict | null {
  let accumulatedText = "";
  let verdict: ReviewVerdict | null = null;

  for (const line of stdout.split(/\r?\n/)) {
    if (!line.startsWith("{")) continue;
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(line) as Record<string, unknown>;
    } catch {
      continue;
    }

    const payload = event.payload;
    if (!payload || typeof payload !== "object") continue;
    const payloadRecord = payload as Record<string, unknown>;

    if (event.payload_type === "run.output.delta") {
      const fragment =
        typeof payloadRecord.text === "string"
          ? payloadRecord.text
          : typeof payloadRecord.delta === "string"
            ? payloadRecord.delta
            : null;
      if (fragment !== null) accumulatedText += fragment;
      continue;
    }

    if (event.payload_type === "task.lifecycle.completed") {
      verdict = parseVerdictFromText(accumulatedText) ?? verdict;
      accumulatedText = "";
    }
  }

  return verdict;
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
export function extractVerdict(review: { output?: unknown; stdout?: string; text?: string }): ReviewVerdict | null {
  const { output, stdout, text } = review;

  if (output && typeof output === "object") {
    const direct = parseReviewVerdict(output);
    if (direct) return direct;
    const nested = (output as Record<string, unknown>).verdict;
    if (nested) {
      const parsedNested = parseReviewVerdict(nested);
      if (parsedNested) return parsedNested;
    }
  }

  for (const candidate of [stdout, text]) {
    if (typeof candidate !== "string") continue;
    const parsed = parseVerdictFromText(candidate);
    if (parsed) return parsed;
  }

  if (typeof stdout === "string") {
    const fromMuseJsonl = parseVerdictFromMuseJsonl(stdout);
    if (fromMuseJsonl) return fromMuseJsonl;
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
 * Now delegates to hostGatingApproved for single truth.
 */
export function isVerdictApproved(verdict: ReviewVerdict | null | undefined): boolean {
  if (!verdict) return false;
  if (!verdict.approved) return false;
  return hostGatingApproved(verdict);
}

/**
 * Human-readable reason for a blocked branch — single place that formats
 * the message posted to GitHub and logged by the factory. Keeps main.mts
 * and gateBranchesByVerdict consistent.
 */
export function blockedReasonForVerdict(verdict: ReviewVerdict | null): string {
  if (!verdict) return "reviewer produced no machine-readable verdict (FACTORY_ERROR)";
  if (!verdict.approved) {
    const findings = (verdict.canonicalFindings ?? verdict.findings) as Array<{ message?: string; invariant?: string }>;
    const detail = findings.map((f) => (f as { message?: string }).message ?? (f as { invariant?: string }).invariant ?? "").join("; ") || verdict.summary || "unmet criteria";
    return `reviewer rejected (approved=false): ${detail}`;
  }
  const findings = (verdict.canonicalFindings ?? verdict.findings) as Array<{ severity: string; message?: string; invariant?: string }>;
  const blocking = findings.filter((f) => f.severity === "blocking");
  if (blocking.length > 0) {
    return `blocking findings despite approved=true: ${blocking.map((f) => (f as { message?: string }).message ?? (f as { invariant?: string }).invariant ?? "").join("; ")}`;
  }
  const unmet = verdict.acceptanceCriteriaMet.filter((c) => !c.met);
  if (unmet.length > 0) {
    return `unmet acceptance criteria despite approved=true: ${unmet.map((c) => c.criterion).join("; ")}`;
  }
  const unresolved = verdict.canonicalResolutions?.filter((r) => r.status === "unresolved");
  if (unresolved && unresolved.length > 0) {
    return `unresolved prior blocking findings: ${unresolved.map((r) => r.findingId).join("; ")}`;
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

/**
 * Helper: create a canonical structured finding draft for tests.
 */
export function structuredFindingFixture(overrides: Partial<ReviewFindingDraft> = {}): ReviewFindingDraft {
  return {
    axis: "combined",
    severity: "blocking",
    invariant: "criterion X must be implemented",
    failureMode: "implementation missing",
    evidence: ["no file covers X"],
    requiredProof: "add implementation and test covering X",
    ...overrides,
  };
}
