import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import {
  EXPECTED_SANDCASTLE_SOURCE_SHA,
  EXPECTED_SANDCASTLE_SOURCE_PREFIX,
  isExpectedSandcastleSourceHead,
  verifySandcastleRuntimeExports,
  resolveSandcastleRuntimeDistPath,
} from "./sandcastle-runtime-provenance.mts";

export const SANDCASTLE_REPO_URL = "https://github.com/rhythmatician/sandcastle";

// ---------------------------------------------------------------------------
// Path helpers (deterministic, no network)
// ---------------------------------------------------------------------------

export function resolveSiblingSandcastlePath(repoRoot: string = process.cwd()): string {
  return path.resolve(repoRoot, "../../sandcastle");
}

export function resolvePackageLinkPath(repoRoot: string = process.cwd()): string {
  return path.join(repoRoot, "node_modules", "@ai-hero", "sandcastle");
}

export function verifyPackageIdentity(packageRoot: string): { ok: boolean; reason?: string; name?: string } {
  const pkgPath = path.join(packageRoot, "package.json");
  if (!fs.existsSync(pkgPath)) return { ok: false, reason: "package.json missing at " + pkgPath };
  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
    if (pkg.name !== "@ai-hero/sandcastle") return { ok: false, reason: `unexpected package name ${String(pkg.name)} expected @ai-hero/sandcastle`, name: String(pkg.name) };
    return { ok: true, name: String(pkg.name) };
  } catch (e) {
    return { ok: false, reason: String(e) };
  }
}

export function getSiblingHead(siblingPath: string): string | null {
  try {
    return execFileSync("git", ["-C", siblingPath, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  } catch {
    return null;
  }
}

export function ensurePackageLink(repoRoot: string, siblingPath: string): void {
  const linkPath = resolvePackageLinkPath(repoRoot);
  const linkDir = path.dirname(linkPath);
  fs.mkdirSync(linkDir, { recursive: true });
  const expectedRelative = path.relative(linkDir, siblingPath);

  let currentTarget: string | null = null;
  let isSymlink = false;
  let exists = false;
  try {
    const lstat = fs.lstatSync(linkPath);
    exists = true;
    isSymlink = lstat.isSymbolicLink();
    if (isSymlink) currentTarget = fs.readlinkSync(linkPath);
  } catch {
    exists = false;
  }

  if (exists) {
    // If link already points to sibling (resolved), keep it even if it was previously broken
    try {
      const resolved = fs.realpathSync(linkPath);
      const expectedResolved = fs.realpathSync(siblingPath);
      if (resolved === expectedResolved) {
        return;
      }
    } catch {}
    // Otherwise remove existing (symlink or directory)
    try {
      fs.rmSync(linkPath, { recursive: true, force: true });
    } catch {}
  }

  // Create symlink with absolute target (more robust) but log relative for clarity
  try {
    fs.symlinkSync(siblingPath, linkPath, "dir");
  } catch {
    // Fallback to relative
    try { fs.rmSync(linkPath, { recursive: true, force: true }); } catch {}
    fs.symlinkSync(expectedRelative, linkPath, "dir");
  }
  console.log(`[sandcastle-bootstrap] Created symlink ${linkPath} -> ${siblingPath} (${expectedRelative})`);
}

// ---------------------------------------------------------------------------
// Bootstrap (may use network when sibling absent)
// ---------------------------------------------------------------------------

export function assertSiblingProvenance(siblingPath: string): string {
  const head = getSiblingHead(siblingPath);
  if (!head) throw new Error(`Sibling sandcastle at ${siblingPath} has no git HEAD (not a git repo or git failed)`);
  if (!isExpectedSandcastleSourceHead(head)) {
    throw new Error(`Sibling sandcastle HEAD ${head} does not match pinned ${EXPECTED_SANDCASTLE_SOURCE_SHA} (${EXPECTED_SANDCASTLE_SOURCE_PREFIX}). Fail closed.`);
  }
  return head;
}

export async function bootstrapSandcastleRuntime(repoRoot: string = process.cwd()): Promise<void> {
  const siblingPath = resolveSiblingSandcastlePath(repoRoot);
  const linkPath = resolvePackageLinkPath(repoRoot);

  const siblingExists = fs.existsSync(siblingPath) && fs.existsSync(path.join(siblingPath, ".git"));
  let siblingHead: string | null = null;

  if (siblingExists) {
    siblingHead = assertSiblingProvenance(siblingPath);
    const identity = verifyPackageIdentity(siblingPath);
    if (!identity.ok) throw new Error(`Sibling package identity failed: ${identity.reason}`);
    // Check dist in sibling
    let distPath = resolveSandcastleRuntimeDistPath(siblingPath);
    if (!distPath || !fs.existsSync(distPath)) {
      console.log(`[sandcastle-bootstrap] Sibling at ${siblingPath} HEAD ${siblingHead.slice(0,7)} exists but dist missing (${distPath || "unresolved"}), building...`);
      execFileSync("npm", ["ci"], { cwd: siblingPath, stdio: "inherit" });
      execFileSync("npm", ["run", "build"], { cwd: siblingPath, stdio: "inherit" });
      distPath = resolveSandcastleRuntimeDistPath(siblingPath);
      if (!distPath || !fs.existsSync(distPath)) throw new Error(`Build failed: dist still missing at ${distPath || "unresolved"} after building sibling`);
    }
    // Ensure link
    ensurePackageLink(repoRoot, siblingPath);
    // Verify link dist
    const linkDistPath = resolveSandcastleRuntimeDistPath(linkPath);
    if (!linkDistPath || !fs.existsSync(linkDistPath)) {
      throw new Error(`Package link dist missing at ${linkDistPath || "unresolved"} (link ${linkPath} -> ${siblingPath})`);
    }
    const linkIdentity = verifyPackageIdentity(linkPath);
    if (!linkIdentity.ok) throw new Error(`Package link identity failed: ${linkIdentity.reason}`);
    // Verify exports by importing built runtime
    const runtime = await import(linkDistPath);
    const exp = verifySandcastleRuntimeExports(runtime);
    if (!exp.ok) throw new Error(`Package link exports mismatch missing ${exp.missing.join(", ")} expected from ${EXPECTED_SANDCASTLE_SOURCE_PREFIX}`);
    console.log(`[sandcastle-bootstrap] Sibling present ${siblingPath} HEAD ${siblingHead.slice(0,7)} dist ${distPath} exports ✓ link verified`);
    return;
  }

  // Sibling absent -> deterministic bootstrap
  console.log(`[sandcastle-bootstrap] Sibling not found at ${siblingPath}, cloning pinned ${EXPECTED_SANDCASTLE_SOURCE_PREFIX} from ${SANDCASTLE_REPO_URL}...`);
  const parentDir = path.dirname(siblingPath);
  fs.mkdirSync(parentDir, { recursive: true });

  // Clone
  try {
    execFileSync("git", ["clone", SANDCASTLE_REPO_URL, siblingPath], { stdio: "inherit" });
  } catch (e) {
    throw new Error(`git clone failed for ${SANDCASTLE_REPO_URL} to ${siblingPath}: ${String(e)}`);
  }

  // Checkout pinned SHA - fail closed
  try {
    // Fetch the exact SHA (GitHub supports fetching by SHA)
    try {
      execFileSync("git", ["-C", siblingPath, "fetch", "--depth", "1", "origin", EXPECTED_SANDCASTLE_SOURCE_SHA], { stdio: "inherit" });
    } catch {
      // Fallback: fetch origin and checkout
      execFileSync("git", ["-C", siblingPath, "fetch", "origin"], { stdio: "inherit" });
    }
    execFileSync("git", ["-C", siblingPath, "checkout", EXPECTED_SANDCASTLE_SOURCE_SHA], { stdio: "inherit" });
  } catch (e) {
    throw new Error(`git checkout failed for ${EXPECTED_SANDCASTLE_SOURCE_SHA} in ${siblingPath}: ${String(e)}`);
  }

  const newHead = assertSiblingProvenance(siblingPath);
  console.log(`[sandcastle-bootstrap] Checked out ${newHead.slice(0,7)} at ${siblingPath}`);

  const identity = verifyPackageIdentity(siblingPath);
  if (!identity.ok) throw new Error(`Cloned package identity failed: ${identity.reason} expected @ai-hero/sandcastle`);

  console.log(`[sandcastle-bootstrap] Installing locked dependencies in ${siblingPath}...`);
  try {
    execFileSync("npm", ["ci"], { cwd: siblingPath, stdio: "inherit" });
  } catch (e) {
    throw new Error(`npm ci failed in ${siblingPath}: ${String(e)}`);
  }

  console.log(`[sandcastle-bootstrap] Building dist in ${siblingPath}...`);
  try {
    execFileSync("npm", ["run", "build"], { cwd: siblingPath, stdio: "inherit" });
  } catch (e) {
    throw new Error(`npm run build failed in ${siblingPath}: ${String(e)}`);
  }

  const distPath = resolveSandcastleRuntimeDistPath(siblingPath);
  if (!distPath || !fs.existsSync(distPath)) throw new Error(`Build failed: dist not found at ${distPath || "unresolved"} after clone+build`);

  const runtimeSibling = await import(distPath);
  const expSibling = verifySandcastleRuntimeExports(runtimeSibling);
  if (!expSibling.ok) throw new Error(`Built sibling exports mismatch missing ${expSibling.missing.join(", ")}`);

  // Ensure link
  ensurePackageLink(repoRoot, siblingPath);

  const linkDistPath = resolveSandcastleRuntimeDistPath(linkPath);
  if (!linkDistPath || !fs.existsSync(linkDistPath)) throw new Error(`Package link dist missing after bootstrap at ${linkDistPath || "unresolved"}`);
  const linkIdentity = verifyPackageIdentity(linkPath);
  if (!linkIdentity.ok) throw new Error(`Package link identity failed after bootstrap: ${linkIdentity.reason}`);

  const linkRuntime = await import(linkDistPath);
  const linkExp = verifySandcastleRuntimeExports(linkRuntime);
  if (!linkExp.ok) throw new Error(`Package link exports mismatch after bootstrap missing ${linkExp.missing.join(", ")}`);

  console.log(`[sandcastle-bootstrap] Bootstrap complete ${siblingPath} HEAD ${newHead.slice(0,7)} dist ${distPath} exports ${linkExp.ok ? "✓" : "✗"} link ${linkPath}`);
}

// CLI entry when run directly via tsx
const isDirectRun = process.argv[1] && (process.argv[1].endsWith("sandcastle-bootstrap.mts") || process.argv[1].endsWith("sandcastle-bootstrap.mjs") || import.meta.url.endsWith(path.basename(process.argv[1])));
if (isDirectRun) {
  bootstrapSandcastleRuntime().catch((e) => {
    console.error(`[sandcastle-bootstrap] FAIL: ${e instanceof Error ? e.message : String(e)}`);
    if (e instanceof Error && e.stack) console.error(e.stack);
    process.exit(1);
  });
}
