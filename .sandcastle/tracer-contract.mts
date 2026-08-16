/**
 * Tracer-bullet ticket contract — concept detection (alias-tolerant).
 *
 * Every agent:implement issue must convey 7 concepts. Validator is intentionally
 * permissive on headings/synonyms/schema (not literal `Goal`/`Done when`).
 * See docs/agents/tracer-contract.md for canonical names + aliases.
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
    patterns: [
      /bounded observable outcome/i,
      /observable outcome/i,
      /^##\s*(Scope|Goal|Objective|Problem)\b/im,
      /Scope/i,
      /Goal/i,
      /Objective/i,
      /Problem/i,
      /outcome/i,
      /boundedOutcome/i,
    ],
  },
  {
    id: "no-unresolved-design",
    name: "no unresolved design decision",
    patterns: [
      /no unresolved design decision/i,
      /no unresolved/i,
      /no open questions/i,
      /design is decided/i,
      /design.*decided/i,
      /decided/i,
      /^##\s*Decision\b/im,
      /^##\s*Design\b/im,
    ],
  },
  {
    id: "acceptance-criteria",
    name: "explicit acceptance criteria",
    patterns: [
      /acceptance criteria/i,
      /done when/i,
      /^##\s*Acceptance\b/im,
      /acceptance/i,
      /criteria/i,
      /acceptanceCriteria/i,
    ],
  },
  {
    id: "verification-path",
    name: "explicit verification path",
    patterns: [
      /verification path/i,
      /^##\s*Verification\b/im,
      /verification/i,
      /verify/i,
      /how to verify/i,
      /validation/i,
    ],
  },
  {
    id: "dependencies-blockers",
    name: "dependencies / blockers",
    patterns: [
      /dependencies/i,
      /dependency/i,
      /blocker/i,
      /blocked by/i,
      /^##\s*Dependencies\b/im,
    ],
  },
  {
    id: "small-for-one-session",
    name: "small enough for one session",
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
    patterns: [
      /tracer.?bullet/i,
      /vertical/i,
      /tracer/i,
      /end.?to.?end/i,
      /slice/i,
    ],
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
