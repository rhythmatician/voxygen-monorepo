/**
 * R-02 Documentation policy — deterministic gate.
 * Rejects newly created general-purpose Markdown unless admitted.
 * Admitted classes:
 * - CONTEXT.md, CONTEXT-MAP.md, AGENTS.md, docs/adr/*.md, docs/agents/*.md, README.md (any depth), .muse/skills/** /*.md, .sandcastle/*.md, docs/external/*.md (with provenance), python/docs/VOXY-FORMAT.md (grandfathered)
 * Fail patterns: *IMPLEMENTATION*, *SUMMARY*, *STATUS*, *TODO*, *PLAN*, *HANDOFF*, *DELIVERABLE*, *CHECKLIST*, *ROADMAP*, *PROGRESS*
 * Incremental debt rule for non-admitted existing files: D→PASS, M→PASS only if candidate < base, A→enforce, R→ destination as new
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
  /^docs\/external\/.+\.md$/,
  // grandfathered version-pinned external-reference — prefer docs/external/ for new
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
  // docs/external/* must have provenance; any external-reference claim must have provenance
  if (path.startsWith("docs/external/")) {
    if (!content.includes("doc-type: external-reference")) errors.push("docs/external/* must have doc-type: external-reference");
    if (!/source-revision:/i.test(content)) errors.push("external-reference must have source-revision");
  } else if (content.includes("doc-type: external-reference")) {
    if (!/source-revision:/i.test(content)) errors.push("external-reference must have source-revision");
  }
  // Grandfathered VOXY-FORMAT is explicitly admitted only with version-pinned provenance
  if (path === "python/docs/VOXY-FORMAT.md") {
    if (!content.includes("doc-type: external-reference")) errors.push("VOXY-FORMAT must have doc-type: external-reference");
    if (!/source-revision:/i.test(content)) errors.push("VOXY-FORMAT must have source-revision");
  }
  return errors;
}

export type FileStatus = "A" | "M" | "D" | "R";
export type FileEntry = { path: string; status: FileStatus; oldPath?: string };

export function checkFilesWithStatus(
  entries: FileEntry[],
  getBaseContent: (path: string) => string | null,
  getCandidateContent: (path: string) => string | null
): { path: string; error: string }[] {
  const violations: { path: string; error: string }[] = [];
  for (const entry of entries) {
    const f = entry.path;
    if (!f.endsWith(".md")) continue;

    // D → PASS (deleting debt is always allowed)
    if (entry.status === "D") continue;

    // For R (rename), treat destination as new doc (like A) — oldPath is deleted, not checked
    const isNew = entry.status === "A" || entry.status === "R";
    const isModify = entry.status === "M";

    const candidate = getCandidateContent(f);
    if (candidate === null) {
      violations.push({
        path: f,
        error: `Documentation policy violation: ${f} — candidate content missing for ${entry.status} (expected in candidate revision)`,
      });
      continue;
    }

    // Incremental debt rule for non-admitted existing files on M
    if (isModify && !isAdmitted(f)) {
      const base = getBaseContent(f);
      if (base !== null) {
        // Existing file: allow only if candidate strictly smaller than base
        if (candidate.length < base.length) continue;
        // Not smaller → enforce normal rules (will fail as non-admitted below)
      } else {
        // M but no base (file is actually new) → treat as A
      }
    }

    // For isNew or non-admitted M that wasn't smaller, enforce suspicious and admission
    // Suspicious names fail even if admitted (README with IMPLEMENTATION)
    if (isSuspiciousName(f) && !f.startsWith(".sandcastle/")) {
      // But for M on existing non-admitted that is smaller, we already continued
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
      violations.push({
        path: f,
        error:
          `Documentation policy violation: ${f} is not an admitted documentation class.\n` +
          `Permitted: CONTEXT.md, docs/adr/*.md, docs/agents/*.md, **/README.md, .muse/skills/**/*.md, .sandcastle/*.md, docs/external/*.md (with doc-type: external-reference + source-revision), version-pinned python/docs/VOXY-FORMAT.md.\n` +
          `If this is an ADR, use docs/adr/NNNN-*.md with proper structure.`,
      });
      continue;
    }
    // admitted → validate structure
    for (const e of validateAdmitted(f, candidate)) {
      violations.push({ path: f, error: `${f}: ${e}` });
    }
  }
  return violations;
}

/**
 * @test-only Legacy wrapper — use checkFilesWithStatus with --base/--candidate for production.
 * Kept for unit tests that exercise simple A/M paths without Git status.
 * Production CLI uses checkFilesWithStatus via --base/--candidate and reads from exact Git revisions.
 */
export function checkFiles(files: string[], read: (p: string) => string): { path: string; error: string }[] {
  // Fallback: assume all files are A (new) if not known, but for incremental test we need base check
  // We simulate base existence by trying to read base via git show if available, else treat as new
  // For unit tests that pass read() that throws for deleted, we handle that
  const entries: FileEntry[] = files.map((f) => ({ path: f, status: "A" as FileStatus }));
  // Try to infer base content for incremental rule: if file exists in base (git show), treat as M
  // But for simplicity in unit tests, we will treat all as A unless test explicitly uses checkFilesWithStatus
  return checkFilesWithStatus(
    entries,
    () => null, // no base in legacy wrapper
    (p) => {
      try {
        return read(p);
      } catch {
        return null;
      }
    }
  );
}

// CLI: supports --base/--candidate (authoritative), --name-status, --files (legacy)
if (import.meta.url.endsWith("docs-policy.mts") && process.argv.some((a) => a.endsWith("docs-policy.mts"))) {
  const args = process.argv.slice(2);
  const fs = await import("node:fs");
  const { execFileSync } = await import("node:child_process");

  let entries: FileEntry[] = [];
  const baseIdx = args.indexOf("--base");
  const candIdx = args.indexOf("--candidate");
  const nameStatusIdx = args.indexOf("--name-status");
  const filesIdx = args.indexOf("--files");

  const parseNameStatusZ = (buf: Buffer): FileEntry[] => {
    const out: FileEntry[] = [];
    // -z output is NUL-separated: status\0path\0 or Rxx\0old\0new\0
    let i = 0;
    while (i < buf.length) {
      let j = buf.indexOf(0, i);
      if (j === -1) break;
      const statusRaw = buf.subarray(i, j).toString("utf-8");
      const status = statusRaw[0] as FileStatus;
      i = j + 1;
      if (status === "R") {
        // Rxxx: old and new
        let k = buf.indexOf(0, i);
        if (k === -1) break;
        const oldPath = buf.subarray(i, k).toString("utf-8");
        i = k + 1;
        let l = buf.indexOf(0, i);
        if (l === -1) break;
        const newPath = buf.subarray(i, l).toString("utf-8");
        i = l + 1;
        out.push({ path: newPath, status: "R", oldPath });
      } else {
        let k = buf.indexOf(0, i);
        if (k === -1) break;
        const path = buf.subarray(i, k).toString("utf-8");
        i = k + 1;
        if (path) out.push({ path, status });
      }
    }
    return out;
  };

  if (baseIdx >= 0 && candIdx >= 0) {
    const base = args[baseIdx + 1];
    const candidate = args[candIdx + 1];
    if (!base || !candidate) {
      console.error("R-02 --base and --candidate require values");
      process.exit(1);
    }
    let buf: Buffer;
    try {
      buf = execFileSync("git", ["diff", "--name-status", "-z", "--diff-filter=ADMR", base, candidate], { encoding: "buffer" }) as Buffer;
    } catch (e) {
      console.error(`R-02 failed to compute diff between ${base} and ${candidate}: ${(e as Error).message}`);
      process.exit(1);
    }
    // Empty diff is simply empty — do not inspect HEAD/index in authoritative mode
    entries = parseNameStatusZ(buf);
  } else if (nameStatusIdx >= 0) {
    const fileArg = args[nameStatusIdx + 1];
    let raw: string;
    if (fileArg) raw = fs.readFileSync(fileArg, "utf-8");
    else raw = execFileSync("git", ["diff", "--name-status", "--diff-filter=ADMR", "HEAD"], { encoding: "utf-8" });
    for (const line of raw.split("\n")) {
      const t = line.trim();
      if (!t) continue;
      const parts = t.split("\t");
      const statusRaw = parts[0];
      const status = statusRaw[0] as FileStatus;
      if (status === "R" && parts.length >= 3) {
        entries.push({ path: parts[2], status: "R", oldPath: parts[1] });
      } else {
        const path = parts[1];
        if (path) entries.push({ path, status });
      }
    }
  } else if (filesIdx >= 0) {
    const filesArg = args[filesIdx + 1];
    let files: string[] = [];
    if (filesArg) {
      const raw = fs.readFileSync(filesArg, "utf-8");
      for (const line of raw.split("\n")) if (line.trim()) files.push(line.trim());
    } else {
      const out = execFileSync("git", ["diff", "--name-only", "--diff-filter=AM", "HEAD"], { encoding: "utf-8" });
      for (const line of out.split("\n")) if (line.trim()) files.push(line.trim());
    }
    entries = files.map((p) => ({ path: p, status: "A" as FileStatus }));
  } else {
    try {
      const out = execFileSync("git", ["diff", "--name-status", "HEAD"], { encoding: "utf-8" });
      for (const line of out.split("\n")) {
        const t = line.trim();
        if (!t) continue;
        const parts = t.split("\t");
        const status = parts[0][0] as FileStatus;
        if (status === "R" && parts.length >= 3) entries.push({ path: parts[2], status: "R", oldPath: parts[1] });
        else if (parts[1]) entries.push({ path: parts[1], status });
      }
    } catch {
      const out = execFileSync("git", ["ls-files", "*.md"], { encoding: "utf-8" });
      for (const line of out.split("\n")) if (line.trim()) entries.push({ path: line.trim(), status: "A" });
    }
  }

  let baseSha: string | null = null;
  let candidateSha: string | null = null;
  if (baseIdx >= 0 && candIdx >= 0) {
    baseSha = args[baseIdx + 1];
    candidateSha = args[candIdx + 1];
  }
  const isAuthoritative = baseSha !== null && candidateSha !== null;
  const getBaseContent = (p: string): string | null => {
    try {
      if (isAuthoritative) {
        return execFileSync("git", ["show", `${baseSha}:${p}`], { encoding: "utf-8" });
      }
      return execFileSync("git", ["show", `HEAD:${p}`], { encoding: "utf-8" });
    } catch {
      return null;
    }
  };
  const getCandidateContent = (p: string): string | null => {
    try {
      if (isAuthoritative) {
        return execFileSync("git", ["show", `${candidateSha}:${p}`], { encoding: "utf-8" });
      }
      return fs.readFileSync(p, "utf-8");
    } catch {
      return null;
    }
  };

  const violations = checkFilesWithStatus(entries, getBaseContent, getCandidateContent);
  if (violations.length) {
    for (const v of violations) console.error(v.error + "\n");
    process.exit(1);
  } else {
    console.log("R-02 Documentation policy: ok");
  }
}
