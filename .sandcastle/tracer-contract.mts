/**
 * Tracer-bullet ticket contract — concept detection (alias-tolerant).
 *
 * Every agent:implement issue must convey 7 concepts. Validator is intentionally
 * permissive on headings / synonyms / schema — not literal `Goal` / `Done when`.
 * See docs/agents/tracer-contract.md for canonical names and aliases.
 *
 * Patterns per concept are intentionally broad (single-word aliases like
 * "scope", "decided", "verify" etc. match loosely). That breadth is the
 * contract: we preserve exact OR semantics while removing strictly subsumed
 * alternates so each regex adds distinct coverage.
 */

export interface TracerConcept {
  id: string;
  name: string;
  patterns: RegExp[];
}

export const TRACER_CONCEPTS: readonly TracerConcept[] = [
  {
    id: "bounded-outcome",
    name: "bounded observable outcome",
    // Covers: bounded observable outcome, observable outcome, outcome, scope,
    // goal, objective, problem, headings, schema key boundedOutcome.
    // Narrower literals (bounded observable outcome, boundedOutcome, heading)
    // are subsumed by outcome / bare-word patterns and omitted.
    patterns: [/outcome/i, /scope/i, /goal/i, /objective/i, /problem/i],
  },
  {
    id: "no-unresolved-design",
    name: "no unresolved design decision",
    // Covers: no unresolved design decision, no unresolved, no open questions,
    // design is decided, decided, headings. Narrower design.*decided variants
    // are subsumed by decided / no unresolved.
    patterns: [
      /no unresolved/i,
      /no open questions/i,
      /decided/i,
      /^##\s*Decision\b/im,
      /^##\s*Design\b/im,
    ],
  },
  {
    id: "acceptance-criteria",
    name: "explicit acceptance criteria",
    // Covers: acceptance criteria, done when, acceptance, criteria, heading,
    // schema key acceptanceCriteria. Narrower acceptance criteria literal and
    // heading are subsumed by acceptance / criteria.
    patterns: [/acceptance/i, /criteria/i, /done when/i],
  },
  {
    id: "verification-path",
    name: "explicit verification path",
    // Covers: verification path, verification, verify, how to verify,
    // validation, heading. Narrower verification path and heading are
    // subsumed by verification.
    patterns: [/verification/i, /verify/i, /how to verify/i, /validation/i],
  },
  {
    id: "dependencies-blockers",
    name: "dependencies / blockers",
    // Covers: dependencies, dependency, blocker, blocked by, heading.
    // Heading is subsumed by dependencies.
    patterns: [/dependencies/i, /dependency/i, /blocker/i, /blocked by/i],
  },
  {
    id: "small-for-one-session",
    name: "small enough for one session",
    // Each phrasing is distinct — "one fresh implementation session" does not
    // contain the contiguous substring "one session", so all three length
    // variants are needed for exact OR preservation.
    patterns: [
      /small enough/i,
      /one fresh implementation session/i,
      /one implementation session/i,
      /one session/i,
      /single session/i,
      /sized for one/i,
      /small\b.*session\b/i,
    ],
  },
  {
    id: "vertical-tracer-bullet",
    name: "prefer vertical / tracer-bullet",
    // Covers: tracer bullet, tracer, vertical, end-to-end, slice.
    // tracer bullet is subsumed by tracer.
    patterns: [/vertical/i, /tracer/i, /end.?to.?end/i, /slice/i],
  },
] as const;

export function missingTracerConcepts(body: string | undefined): string[] {
  const text = body ?? "";
  const missing: string[] = [];
  for (const concept of TRACER_CONCEPTS) {
    const present = concept.patterns.some((p) => p.test(text));
    if (!present) missing.push(concept.id);
  }
  return missing;
}

export function validateTracerContract(body: string | undefined): {
  ok: boolean;
  missing: string[];
} {
  const missing = missingTracerConcepts(body);
  return { ok: missing.length === 0, missing };
}

export function hasTracerContract(body: string | undefined): boolean {
  return missingTracerConcepts(body).length === 0;
}
