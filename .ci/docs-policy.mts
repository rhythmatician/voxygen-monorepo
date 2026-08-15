/**
 * R-02 Documentation policy — deterministic gate.
 * Rejects newly created general-purpose Markdown unless admitted.
 * Admitted classes:
 * - CONTEXT.md, docs/adr/*.md, docs/agents/*.md, ** /README.md, .muse/skills/** /*.md, .sandcastle/*.md, ** /VOXY-FORMAT.md (and other explicit external-reference)
 * Fail patterns: *IMPLEMENTATION*, *SUMMARY*, *STATUS*, *TODO*, *PLAN*, *HANDOFF*, *DELIVERABLE*, *CHECKLIST*, *ROADMAP*, *PROGRESS*
 * External-reference must have frontmatter doc-type: external-reference and source-revision
 * ADR must have numeric filename, title, status, context/problem, decision, alternatives
 */

const ADMITTED = [
  /^AGENTS\.md$/,
  /^CONTEXT\.md$/,
  /^CONTEXT-MAP\.md$/,
  /^docs\/adr\/.+\.md$/,
  /^docs\/agents\/.+\.md$/,
  /(^|\/)README\.md$/,
  /^\.muse\/skills\/.+\.md$/,
  /^\.sandcastle\/.+\.md$/,
  /^\.sandcastle\/CODING_STANDARDS\.md$/,
  // explicit external-reference allowlist — version-pinned, expensive to reconstruct
  /^python\/docs\/VOXY-FORMAT\.md$/,
];

const SUSPICIOUS = /IMPLEMENTATION|SUMMARY|STATUS|TODO|PLAN|HANDOFF|DELIVERABLE|CHECKLIST|ROADMAP|PROGRESS/i;

export function isAdmitted(path: string): boolean {
  return ADMITTED.some((re) => re.test(path));
}

export function isSuspiciousName(path: string): boolean {
  return SUSPICIOUS.test(path.toUpperCase());
}

export function validateAdmitted(path: string, content: string): string[] {
  const errors: string[] = [];
  if (/^docs\/adr\//.test(path)) {
    if (!/^\d{4}-/.test(path.split("/").pop()!)) errors.push("ADR filename must be numeric NNNN-*.md");
    if (!/^#\s+.+/m.test(content)) errors.push("ADR must have title");
    if (!/status:/i.test(content)) errors.push("ADR must have status");
    if (!/(context|problem)/i.test(content)) errors.push("ADR must have context/problem");
    if (!/decision/i.test(content)) errors.push("ADR must have decision");
    if (!/(alternative|trade-off|consequence)/i.test(content)) errors.push("ADR must have alternatives/trade-offs");
  }
  if (content.includes("doc-type: external-reference")) {
    if (!/source-revision:/i.test(content)) errors.push("external-reference must have source-revision");
  }
  return errors;
}

export function checkFiles(files: string[], read: (p: string) => string): { path: string; error: string }[] {
  const violations: { path: string; error: string }[] = [];
  for (const f of files) {
    if (!f.endsWith(".md")) continue;
    // Deleted files (read throws or file not found) are not new violations — allow incremental cleanup
    let content: string | null = null;
    let isDeleted = false;
    try {
      content = read(f);
    } catch {
      isDeleted = true;
    }
    if (isDeleted) continue;
    // Legacy sediment: java/docs/* and python/docs/MASTER_PLAN.md etc. are known violations
    // Reducing them (smaller, pointer-style content) must be allowed for incremental cleanup
    // For now, allow any modification to java/docs/*.md that results in small pointer content
    // This supports the migration test: reducing IMPLEMENTATION_SUMMARY.md to a pointer passes
    if ((f.startsWith("java/docs/") || f === "python/docs/MASTER_PLAN.md" || f === "python/docs/MODEL-CONTRACT.md") && content !== null && content.length < 200 && content.includes("See ")) {
      continue;
    }
    // Suspicious names fail even if structurally admitted (README with IMPLEMENTATION still violates)
    if (isSuspiciousName(f)) {
      violations.push({
        path: f,
        error:
          `Documentation policy violation: ${f}\nRepository prose may not duplicate implementation state.\n` +
          `Put current mechanics in code/tests/contracts and work state in the GitHub issue/PR. ` +
          `If this is domain language, update CONTEXT.md. If this records a durable architectural trade-off, create an ADR.`,
      });
      continue;
    }
    if (!isAdmitted(f)) {
      // general-purpose new markdown not in admitted class — fail closed (allowlist)
      violations.push({
        path: f,
        error:
          `Documentation policy violation: ${f} is not an admitted documentation class.\n` +
          `Permitted: CONTEXT.md, docs/adr/*.md, docs/agents/*.md, **/README.md, .muse/skills/**/*.md, .sandcastle/*.md, version-pinned external-reference.\n` +
          `If this is an ADR, use docs/adr/NNNN-*.md with proper structure.`,
      });
      continue;
    }
    // admitted — still validate structure if ADR or external-reference
    try {
      const c = content ?? read(f);
      for (const e of validateAdmitted(f, c)) {
        violations.push({ path: f, error: `${f}: ${e}` });
      }
    } catch {
      // file may not exist on disk during plan check — skip content validation
    }
  }
  return violations;
}

// CLI: node --loader tsx .ci/docs-policy.mts --files changed-files.txt
if (import.meta.url.endsWith("docs-policy.mts") && process.argv.some(a => a.endsWith("docs-policy.mts"))) {
  const args = process.argv.slice(2);
  const filesIdx = args.indexOf("--files");
  const filesArg = filesIdx >= 0 ? args[filesIdx + 1] : null;
  const fs = await import("node:fs");
  const files: string[] = [];
  if (filesArg) {
    const raw = fs.readFileSync(filesArg, "utf-8");
    for (const line of raw.split("\n")) {
      const t = line.trim();
      if (t) files.push(t);
    }
  } else {
    // check all tracked md files (fallback)
    const { execSync } = await import("node:child_process");
    const out = execSync("git ls-files '*.md'", { encoding: "utf-8" });
    for (const line of out.split("\n")) if (line.trim()) files.push(line.trim());
  }
  const violations = checkFiles(files, (p) => {
    try {
      return fs.readFileSync(p, "utf-8");
    } catch {
      return "";
    }
  });
  if (violations.length) {
    for (const v of violations) console.error(v.error + "\n");
    process.exit(1);
  } else {
    console.log("R-02 Documentation policy: ok");
  }
}
