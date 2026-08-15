/**
 * Pure deterministic scheduler for watcher coalescing/debounce/serialization.
 * JS runtime version for node scripts/qgate-watcher.mjs
 * Keep in sync with scripts/watcher-scheduler.mts
 */

const DEBOUNCE_MS = 150;
const IGNORE_AFTER_FORMAT_MS = 500;

function isPythonFile(p) {
  return (
    p.endsWith(".py") &&
    !p.includes(".venv") &&
    !p.includes(".ruff_cache") &&
    !p.includes("__pycache__")
  );
}

export class WatchScheduler {
  pending = new Set();
  isRunning = false;
  needsRerun = false;
  lastFormatted = new Map();
  debounceMs;
  ignoreMs;
  debounceTimer = null;
  runs = [];

  constructor(opts = {}) {
    this.debounceMs = opts.debounceMs ?? DEBOUNCE_MS;
    this.ignoreMs = opts.ignoreMs ?? IGNORE_AFTER_FORMAT_MS;
  }

  queue(file, now = Date.now()) {
    if (!isPythonFile(file)) return false;
    const last = this.lastFormatted.get(file) || 0;
    if (now - last < this.ignoreMs) return false;
    this.pending.add(file);
    return true;
  }

  filterExisting(files, exists) {
    return files.filter(exists);
  }

  schedule(now = Date.now(), exists) {
    if (this.isRunning) {
      this.needsRerun = true;
      return { scheduled: false, reason: "running" };
    }
    let batch = [...this.pending];
    this.pending.clear();
    if (batch.length === 0) return { scheduled: false, reason: "empty" };
    if (exists) batch = batch.filter(exists);
    if (batch.length === 0) return { scheduled: false, reason: "empty" };
    this.isRunning = true;
    this.runs.push(batch);
    this.isRunning = false;
    for (const f of batch) this.lastFormatted.set(f, now);
    if (this.needsRerun || this.pending.size > 0) {
      const hadPending = this.pending.size > 0;
      this.needsRerun = false;
      return { scheduled: true, batch, rerun: hadPending };
    }
    return { scheduled: true, batch };
  }

  startRun(batch) {
    if (this.isRunning) {
      this.needsRerun = true;
      for (const f of batch) this.pending.add(f);
      return false;
    }
    this.isRunning = true;
    this.runs.push(batch);
    return true;
  }

  finishRun(batch, now = Date.now()) {
    for (const f of batch) this.lastFormatted.set(f, now);
    this.isRunning = false;
    if (this.needsRerun || this.pending.size > 0) {
      this.needsRerun = false;
      return { needsRerun: true, pending: [...this.pending] };
    }
    return { needsRerun: false, pending: [] };
  }

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

export function isPythonFileExport(p) {
  return isPythonFile(p);
}

export const constants = { DEBOUNCE_MS, IGNORE_AFTER_FORMAT_MS };
