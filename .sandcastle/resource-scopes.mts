import * as fs from "node:fs";
import * as path from "node:path";
import { getErrorMessage } from "./gh-errors.mts";

/**
 * Narrow resource scopes — only for operations that own real lifetimes.
 * NOT a generic resource-management library. Per #210:
 *
 * - withTemporaryIssueFixtures: record-at-acquisition, finally-cleanup,
 *   primary vs cleanup failures separated, postcondition verification,
 *   fail closed (FACTORY_ERROR) on uncertainty.
 * - withAtomicJsonReceipt: migration/canary/recovery evidence written atomically
 *   (temp file + rename in same directory).
 */

// ---------------------------------------------------------------------------
// withTemporaryIssueFixtures
// ---------------------------------------------------------------------------

export interface FixtureCleanupOps {
  /** Cleanup a single fixture (remove transient labels/assignee, close, etc.). */
  cleanup: (id: number) => Promise<void>;
  /**
   * REQUIRED: verify cleanup postcondition for a fixture on a fresh read;
   * return error string when unclean. Verification failure or unreadable
   * state is a cleanup failure — fail closed on uncertainty.
   */
  verify: (id: number) => Promise<string | null>;
}

export interface FixturesResult<T> {
  value?: T;
  primaryError?: string;
  cleanupFailures: string[];
  fixtureIds: number[];
  /**
   * True when the primary body succeeded AND every fixture is proven clean
   * by fresh-read verification. False when primary failed OR any cleanup
   * state remains uncertain — callers must treat false as fail-closed
   * FACTORY_ERROR evidence.
   */
  ok: boolean;
}

/**
 * Acquire fixtures through the registrar: each created id is RECORDED at the
 * moment of acquisition, so partial acquisition (fixture #1 created, #2
 * throws) still leaves #1 on the cleanup list. Body runs with all recorded
 * ids. Cleanup always runs in `finally`, per-fixture isolated: one fixture's
 * cleanup failure never skips another's. Primary failure and cleanup failures
 * are reported separately. Postcondition verification is required after each
 * successful cleanup; an unverifiable postcondition counts as a cleanup
 * failure.
 */
export async function withTemporaryIssueFixtures<T>(
  acquire: (registrar: { record: (id: number) => void }) => Promise<T>,
  ops: FixtureCleanupOps,
): Promise<FixturesResult<T>> {
  const fixtureIds: number[] = [];
  const cleanupFailures: string[] = [];
  let primaryError: string | undefined;
  let value: T | undefined;

  try {
    value = await acquire({ record: (id) => { fixtureIds.push(id); } });
  } catch (e) {
    primaryError = getErrorMessage(e);
  } finally {
    for (const id of fixtureIds) {
      try {
        await ops.cleanup(id);
      } catch (e) {
        // Cleanup threw — postcondition cannot be trusted for this fixture.
        cleanupFailures.push(`cleanup #${id} failed: ${getErrorMessage(e)}`);
        continue;
      }
      try {
        const problem = await ops.verify(id);
        if (problem) cleanupFailures.push(`fixture #${id} postcondition failed: ${problem}`);
      } catch (e) {
        // Verification itself failing means state is UNKNOWN — fail closed.
        cleanupFailures.push(`fixture #${id} postcondition verification failed: ${getErrorMessage(e)}`);
      }
    }
  }

  const ok = primaryError === undefined && cleanupFailures.length === 0 && fixtureIds.length > 0;
  return { value, primaryError, cleanupFailures, fixtureIds, ok };
}

// ---------------------------------------------------------------------------
// withAtomicJsonReceipt
// ---------------------------------------------------------------------------

export interface AtomicWriteDeps {
  writeFileSync?: typeof fs.writeFileSync;
  mkdirSync?: typeof fs.mkdirSync;
  renameSync?: typeof fs.renameSync;
  unlinkSync?: typeof fs.unlinkSync;
}

/**
 * Write JSON evidence atomically: serialize once, write to `<target>.tmp`,
 * fsync-free rename into place within the same directory. A crash leaves
 * either the old file or the new file — never a torn half-written receipt.
 */
export function withAtomicJsonReceipt<T>(
  targetPath: string,
  produce: () => T,
  deps: AtomicWriteDeps = {},
): { path: string; data: T } {
  const writeFile = deps.writeFileSync ?? fs.writeFileSync;
  const mkdir = deps.mkdirSync ?? fs.mkdirSync;
  const rename = deps.renameSync ?? fs.renameSync;
  const unlink = deps.unlinkSync ?? fs.unlinkSync;

  const dir = path.dirname(targetPath);
  mkdir(dir, { recursive: true });
  const tmpPath = `${targetPath}.tmp-${process.pid}-${Date.now()}`;
  let data: T;
  try {
    data = produce();
    writeFile(tmpPath, JSON.stringify(data, null, 2), "utf8");
    rename(tmpPath, targetPath);
  } catch (e) {
    try { unlink(tmpPath); } catch {}
    throw e;
  }
  return { path: targetPath, data };
}
