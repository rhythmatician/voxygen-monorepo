import { describe, it, expect } from "vitest";
import { WatchScheduler } from "../scripts/watcher-scheduler.mts";

// Helper to simulate time
function nowPlus(base: number, ms: number) {
  return base + ms;
}

describe("WatchScheduler - deterministic seam", () => {
  it("repeated rapid writes to one file coalesce into one run", () => {
    const s = new WatchScheduler({ debounceMs: 150, ignoreMs: 500 });
    const t0 = 1000;
    s.queue("python/voxel_tree/a.py", t0);
    s.queue("python/voxel_tree/a.py", t0 + 10);
    s.queue("python/voxel_tree/a.py", t0 + 20);
    expect(s.pending.size).toBe(1);
    const res = s.schedule();
    expect(res.scheduled).toBe(true);
    expect(res.batch).toEqual(["python/voxel_tree/a.py"]);
    expect(s.pending.size).toBe(0);
    expect(s.runs.length).toBe(1);
  });

  it("multiple changed files coalesce correctly", () => {
    const s = new WatchScheduler({ debounceMs: 150, ignoreMs: 500 });
    const t0 = 2000;
    s.queue("python/voxel_tree/a.py", t0);
    s.queue("python/voxel_tree/b.py", t0 + 5);
    s.queue("python/voxel_tree/c.py", t0 + 10);
    expect(s.pending.size).toBe(3);
    const res = s.schedule();
    expect(res.scheduled).toBe(true);
    expect(new Set(res.batch)).toEqual(
      new Set([
        "python/voxel_tree/a.py",
        "python/voxel_tree/b.py",
        "python/voxel_tree/c.py",
      ])
    );
    // duplicate coalesced
    s.queue("python/voxel_tree/a.py", t0 + 100);
    s.queue("python/voxel_tree/a.py", t0 + 110);
    s.queue("python/voxel_tree/b.py", t0 + 115);
    const res2 = s.schedule();
    // Should have ignored a.py/b.py if within ignore window after lastFormatted? Use later time
    // Use time beyond ignore window
    const t1 = t0 + 1000;
    s.queue("python/voxel_tree/a.py", t1);
    s.queue("python/voxel_tree/a.py", t1 + 5);
    s.queue("python/voxel_tree/b.py", t1 + 10);
    // pending should be 2 (a.py deduped)
    // But we already scheduled res2, check that res2 had coalesced duplicates correctly if not ignored
    // For now verify coalescing: pending size after duplicate queues should be less than queue count
    // This test ensures Set deduplication
    s.pending.clear();
    s.lastFormatted.clear();
    s.queue("python/voxel_tree/x.py", t1);
    s.queue("python/voxel_tree/x.py", t1 + 5);
    s.queue("python/voxel_tree/x.py", t1 + 10);
    expect(s.pending.size).toBe(1);
  });

  it("maximum concurrent executions is exactly 1", () => {
    const s = new WatchScheduler();
    s.queue("python/voxel_tree/a.py");
    const started = s.startRun(["python/voxel_tree/a.py"]);
    expect(started).toBe(true);
    expect(s.isRunning).toBe(true);
    // Try to start second concurrent run - must be rejected
    const started2 = s.startRun(["python/voxel_tree/b.py"]);
    expect(started2).toBe(false);
    expect(s.isRunning).toBe(true);
    expect(s.needsRerun).toBe(true);
    // schedule() while running should not start new run
    s.queue("python/voxel_tree/c.py");
    const res = s.schedule();
    expect(res.scheduled).toBe(false);
    expect(res.reason).toBe("running");
    // Finish first run, then pending should be available
    s.finishRun(["python/voxel_tree/a.py"]);
    expect(s.isRunning).toBe(false);
    expect(s.pending.has("python/voxel_tree/b.py")).toBe(true);
    expect(s.pending.has("python/voxel_tree/c.py")).toBe(true);
  });

  it("edits arriving while a run is active cause one subsequent run containing latest pending", () => {
    const s = new WatchScheduler();
    // Simulate realistic flow: pending was cleared when run started, so we startRun directly
    s.startRun(["python/voxel_tree/a.py"]);
    // While running, new edits arrive
    s.queue("python/voxel_tree/b.py", 1010);
    s.queue("python/voxel_tree/c.py", 1020);
    s.queue("python/voxel_tree/b.py", 1030); // duplicate
    expect(s.pending.size).toBe(2); // b and c coalesced
    const fin = s.finishRun(["python/voxel_tree/a.py"], 1100);
    expect(s.pending.size).toBe(2);
    expect(fin.needsRerun).toBe(true);
    const next = s.schedule(1200);
    expect(next.scheduled).toBe(true);
    expect(new Set(next.batch!)).toEqual(
      new Set(["python/voxel_tree/b.py", "python/voxel_tree/c.py"])
    );
  });

  it("formatter-induced/self-write events do not create infinite loop", () => {
    const s = new WatchScheduler({ ignoreMs: 500 });
    const t0 = 5000;
    s.queue("python/voxel_tree/a.py", t0);
    const res = s.schedule(t0 + 50);
    expect(res.scheduled).toBe(true);
    // Immediately after formatting, file watcher sees own write - should be ignored within ignore window
    const ignored = s.queue("python/voxel_tree/a.py", t0 + 60);
    expect(ignored).toBe(false);
    expect(s.pending.size).toBe(0);
    // After ignore window, same file should be accepted again
    const accepted = s.queue("python/voxel_tree/a.py", t0 + 600);
    expect(accepted).toBe(true);
    expect(s.pending.size).toBe(1);
    s.pending.clear();
    s.lastFormatted.set("python/voxel_tree/b.py", t0 + 700);
    const selfTrigger = s.queue("python/voxel_tree/b.py", t0 + 705);
    expect(selfTrigger).toBe(false);
  });

  it("deleted/nonexistent/non-Python paths are ignored safely", () => {
    const s = new WatchScheduler();
    const nonPy = s.queue("python/voxel_tree/readme.md", 1000);
    expect(nonPy).toBe(false);
    expect(s.pending.has("python/voxel_tree/readme.md")).toBe(false);

    s.pending.clear();
    s.queue("python/voxel_tree/deleted.py", 2000);
    // Simulate existence check: deleted file does not exist
    const exists = (f: string) => f !== "python/voxel_tree/deleted.py";
    const res = s.schedule(2100, exists);
    expect(res.scheduled).toBe(false);
    expect(res.reason).toBe("empty");
    // Non-deleted file should still be scheduled
    s.queue("python/voxel_tree/existing.py", 2200);
    const res2 = s.schedule(2250, exists);
    expect(res2.scheduled).toBe(true);
    expect(res2.batch).toEqual(["python/voxel_tree/existing.py"]);
  });

  it("failures do not destroy pending work", () => {
    const s = new WatchScheduler();
    s.queue("python/voxel_tree/a.py", 1000);
    s.startRun(["python/voxel_tree/a.py"]);
    // While run fails, new work arrives
    s.queue("python/voxel_tree/b.py", 1010);
    // Simulate failure: finishRun is called but pending should remain
    // Current finishRun clears isRunning but does not distinguish success vs failure
    // If failure handling incorrectly clears pending, this fails
    const fin = s.finishRun(["python/voxel_tree/a.py"], 1020);
    // After failure, pending b.py must still be there
    expect(s.pending.has("python/voxel_tree/b.py")).toBe(true);
    // And scheduler should know to rerun
    // If pending exists, needsRerun should be true or schedule should succeed
    const res = s.schedule();
    expect(res.scheduled).toBe(true);
    expect(res.batch).toContain("python/voxel_tree/b.py");
  });
});
