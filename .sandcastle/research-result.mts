import { z } from "zod";

/**
 * Strict research result contract — evidence-backed findings.
 * Validated on host after researcher completes; invalid output is FACTORY_ERROR.
 */

export const researchFindingSchema = z.object({
  claim: z.string().min(1),
  evidence: z.string().min(1),
  source: z.string().min(1),
});

export const researchResultSchema = z.object({
  summary: z.string().min(1),
  findings: z.array(researchFindingSchema),
  recommendation: z.string().min(1),
  uncertainties: z.array(z.string()),
  followUps: z.array(z.string()),
});

export type ResearchFinding = z.infer<typeof researchFindingSchema>;
export type ResearchResult = z.infer<typeof researchResultSchema>;

const RESEARCH_TAG_RE = /<research>([\s\S]*?)<\/research>/gi;

export function parseResearchResult(raw: unknown): ResearchResult | null {
  const res = researchResultSchema.safeParse(raw);
  return res.success ? res.data : null;
}

export function parseResearchResultFromText(text: string): ResearchResult | null {
  const match = Array.from(text.matchAll(RESEARCH_TAG_RE)).at(-1);
  if (!match) return null;
  const jsonText = match[1]!.trim();
  try {
    const raw = JSON.parse(jsonText);
    return parseResearchResult(raw);
  } catch {
    return null;
  }
}

export function extractResearchResult(review: {
  output?: unknown;
  stdout?: string;
  text?: string;
}): ResearchResult | null {
  const { output, stdout, text } = review;
  if (output && typeof output === "object") {
    const direct = parseResearchResult(output);
    if (direct) return direct;
    const nested = (output as Record<string, unknown>).research;
    if (nested) {
      const parsed = parseResearchResult(nested);
      if (parsed) return parsed;
    }
    const nestedAlt = (output as Record<string, unknown>).result;
    if (nestedAlt) {
      const parsed2 = parseResearchResult(nestedAlt);
      if (parsed2) return parsed2;
    }
  }
  for (const candidate of [stdout, text]) {
    if (typeof candidate !== "string") continue;
    const parsed = parseResearchResultFromText(candidate);
    if (parsed) return parsed;
    // also try direct JSON parse of candidate if it is JSON without tags
    try {
      const raw = JSON.parse(candidate);
      const p = parseResearchResult(raw);
      if (p) return p;
    } catch {}
  }
  if (typeof output === "string") {
    const fromString = parseResearchResultFromText(output);
    if (fromString) return fromString;
    try {
      const raw = JSON.parse(output);
      const p = parseResearchResult(raw);
      if (p) return p;
    } catch {}
  }
  return null;
}

export function formatResearchResultForComment(result: ResearchResult): string {
  const findingsMd = result.findings
    .map((f, i) => `${i + 1}. **${f.claim}**\n   - Evidence: ${f.evidence}\n   - Source: \`${f.source}\``)
    .join("\n");
  const uncertaintiesMd = result.uncertainties.length > 0 ? result.uncertainties.map((u) => `- ${u}`).join("\n") : "- none";
  const followUpsMd = result.followUps.length > 0 ? result.followUps.map((f) => `- ${f}`).join("\n") : "- none";
  return `## Research findings\n\n**Summary:** ${result.summary}\n\n**Findings:**\n${findingsMd || "(no findings)"}\n\n**Recommendation:** ${result.recommendation}\n\n**Uncertainties:**\n${uncertaintiesMd}\n\n**Follow-ups:**\n${followUpsMd}\n\n<details><summary>Raw structured result</summary>\n\n\`\`\`json\n${JSON.stringify(result, null, 2)}\n\`\`\`\n</details>`;
}

export function researchValidationError(raw: unknown): string {
  const res = researchResultSchema.safeParse(raw);
  if (res.success) return "";
  return res.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ");
}
