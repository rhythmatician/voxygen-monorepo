/**
 * Pure deterministic scheduler for watcher coalescing/debounce/serialization.
 * Separate from fs.watch and subprocess plumbing.
 * Tested via .sandcastle/watcher-scheduler.test.mts
 */

const DEBOUNCE_MS = 150;
const IGNORE_AFTER_FORMAT_MS = 500;

function isPythonFile(p: string): boolean {
  return (
    p.endsWith(".py") &&
    !p.includes(".venv") &&
    !p.includes(".ruff_cache") &&
    !p.includes("__pycache__")
  );
}

export class WatchScheduler {
  pending = new Set<string>();
  isRunning = false;
  needsRerun = false;
  lastFormatted = new Map<string, number>();
  debounceMs: number;
  ignoreMs: number;
  debounceTimer: ReturnType<typeof setTimeout> | null = null;
  runs: string[][] = [];

  constructor(opts: { debounceMs?: number; ignoreMs?: number } = {}) {
    this.debounceMs = opts.debounceMs ?? DEBOUNCE_MS;
    this.ignoreMs = opts.ignoreMs ?? IGNORE_AFTER_FORMAT_MS;
  }

  // Returns false if ignored (self-loop or non-Python), true if queued
  queue(file: string, now = Date.now()): boolean {
    if (!isPythonFile(file)) return false; // filter non-Python at seam
    const last = this.lastFormatted.get(file) || 0;
    if (now - last < this.ignoreMs) return false;
    this.pending.add(file);
    return true;
  }

  // For existence filtering, caller can provide exists check; here we simulate by allowing
  // caller to pass only existing files. But we also provide a method that filters via callback.
  filterExisting(files: string[], exists: (f: string) => boolean): string[] {
    return files.filter(exists);
  }

  schedule(
    now = Date.now(),
    exists?: (f: string) => boolean
  ): {
    scheduled: boolean;
    reason?: string;
    batch?: string[];
    rerun?: boolean;
  } {
    if (this.isRunning) {
      this.needsRerun = true;
      return { scheduled: false, reason: "running" };
    }
    let batch = [...this.pending];
    this.pending.clear();
    if (batch.length === 0) return { scheduled: false, reason: "empty" };
    // Filter by existence if provided; ignore deleted/nonexistent safely
    if (exists) batch = batch.filter(exists);
    if (batch.length === 0) return { scheduled: false, reason: "empty" };
    this.isRunning = true;
    this.runs.push(batch);
    // Simulate immediate completion for deterministic tests
    this.isRunning = false;
    for (const f of batch) this.lastFormatted.set(f, now);
    if (this.needsRerun || this.pending.size > 0) {
      const hadPending = this.pending.size > 0;
      this.needsRerun = false;
      return { scheduled: true, batch, rerun: hadPending };
    }
    return { scheduled: true, batch };
  }

  startRun(batch: string[]): boolean {
    if (this.isRunning) {
      this.needsRerun = true;
      for (const f of batch) this.pending.add(f);
      return false;
    }
    this.isRunning = true;
    this.runs.push(batch);
    return true;
  }

  finishRun(
    batch: string[],
    now = Date.now()
  ): { needsRerun: boolean; pending: string[] } {
    for (const f of batch) this.lastFormatted.set(f, now);
    this.isRunning = false;
    // Failure should not destroy pending - we preserve pending regardless of success/failure
    // So we check pending size as well as needsRerun
    if (this.needsRerun || this.pending.size > 0) {
      this.needsRerun = false;
      return { needsRerun: true, pending: [...this.pending] };
    }
    return { needsRerun: false, pending: [] };
  }

  // For testing: simulate queue while running and ensure coalescing
  reset() {
    this.pending.clear();
    this.isRunning = false;
    this.needsRerun = false;
    this.lastFormatted.clear();
    this.runs = [];
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.debounceTimer = null;
  }
}

export function isPythonFileExport(p: string): boolean {
  return isPythonFile(p);
}

export const constants = { DEBOUNCE_MS, IGNORE_AFTER_FORMAT_MS };
