#!/usr/bin/env python3
"""
⚠️  SCAFFOLD — NOT AUTHORITATIVE EVIDENCE  ⚠️

Thin oracle harness — Oracle 1-7 + FINAL_DENSITY viability gate.

THIS FILE IS SCAFFOLDING ONLY. It does NOT produce oracle evidence and
MUST NOT be used to satisfy any Factory, performance, or representation
gate. Specifically:
  - bench_final_density() emits SYNTHETIC RANDOM timings (random.uniform)
    chosen to have the expected ordering — it does not benchmark
    FINAL_DENSITY, ChunkNoiseSampler, or GpuHeightmapProvider.
  - oracle_stub() does NOT evaluate Oracles 1–4; it only checks whether
    a GT path exists on disk.
  - The report field `viable` is therefore MEANINGLESS and is forced to
    null/false by this scaffold. Any consumer that treats this output as
    a passing viability signal is misconfigured.

When a real oracle is needed, replace this file with an implementation
that calls VanillaNoiseRouterSampler.sampleSection via JPype/Py4J or via
pre-dumped SectionNoiseData npz, measures wall-clock time against a
ServerWorld/NoiseConfig, and computes real voxel accuracy / surface MAE /
silhouette IoU against GT.

Single file, no training. Reads fixed voxy_sections GT (build_voxy_pairs.py:504, vocab_remap 1104→513)
and would benchmark FINAL_DENSITY quart sampling vs ChunkNoiseSampler heightmap if implemented.

Usage:
  python python/harness/thin_oracle.py --gt /path/to/voxy_sections --columns 100 --seed 42 --report json
If --gt missing, probes repo for python/runs/*/voxy_sections or python/data.

Outputs: voxel accuracy / surface MAE / silhouette IoU / rendered-pixel stub + P50/P95 ms/column for
  * FINAL_DENSITY-only sampleSection (32 floats/section, 768/col)
  * sampleBothHeightmaps (ChunkNoiseSampler 5 evals/chunk + early-out loop)
  * GpuHeightmapProvider zero-crossing (if available, else fallback sea-level)

This is Oracle 6 gate for user question: is FINAL_DENSITY fast enough vs heightmap via ChunkNoiseSampler?
No chunk getHeight() is used — see WorldNoiseAccess.java:204 no side effects.
"""

import argparse
import json
import time
import statistics
import sys
import warnings
from pathlib import Path


def find_gt(args_gt):
    if args_gt and Path(args_gt).exists():
        return Path(args_gt)
    for cand in [Path("python/runs"), Path("python/data"), Path("data")]:
        if cand.exists():
            for p in cand.rglob("voxy_sections*"):
                return p
    return None


def bench_final_density(columns, seed):
    # SCAFFOLD: emits synthetic random timings — NOT a measurement.
    # Real impl would call VanillaNoiseRouterSampler.sampleSection via
    # JPype/Py4J or via pre-dumped SectionNoiseData npz.
    warnings.warn(
        "bench_final_density is SCAFFOLD — synthetic timings, not a measurement. "
        "Do not use `viable` as evidence.",
        UserWarning,
        stacklevel=2,
    )
    import random

    random.seed(seed)
    final = [0.25 + random.uniform(-0.05, 0.15) for _ in range(columns)]
    height = [1.2 + random.uniform(-0.3, 0.8) for _ in range(columns)]
    gpu = [0.4 + random.uniform(-0.1, 0.3) for _ in range(columns)]
    return final, height, gpu


def oracle_stub(gt_path, columns):
    # SCAFFOLD: does NOT evaluate Oracles 1–4; only reports GT presence.
    if gt_path is None:
        return {
            "status": "SCAFFOLD_GT_NOT_FOUND",
            "scaffold": True,
            "authoritative": False,
            "hint": "provide --gt /path/to/voxy_sections (see build_voxy_pairs.py:108,504)",
        }
    return {
        "status": "SCAFFOLD_GT_FOUND",
        "scaffold": True,
        "authoritative": False,
        "gt": str(gt_path),
        "columns": columns,
        "oracles": "SCAFFOLD — not evaluated. Real Oracles 1:height 2:surface 3:features 4:residual — see 34-min-sufficient-representation-decision.md:§3",
        "warning": "This stub does not compute accuracy/MAE/IoU. Presence of a GT path is not evidence.",
    }


def main():
    print(
        "WARNING: python/harness/thin_oracle.py is SCAFFOLD — NOT authoritative evidence. "
        "Timings are synthetic random numbers; Oracles 1–4 are not evaluated. "
        "See file header.",
        file=sys.stderr,
    )
    ap = argparse.ArgumentParser(description="SCAFFOLD — not authoritative. See file header.")
    ap.add_argument("--gt", default=None)
    ap.add_argument("--columns", type=int, default=100)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--report", choices=["json", "text"], default="json")
    args = ap.parse_args()

    gt = find_gt(args.gt)
    t0 = time.time()
    final, height, gpu = bench_final_density(args.columns, args.seed)
    oracle = oracle_stub(gt, args.columns)

    def p50(a):
        return statistics.median(a) if a else None

    def p95(a):
        return sorted(a)[int(len(a) * 0.95)] if a else None

    p95_final = p95(final)
    report = {
        "scaffold": True,
        "authoritative": False,
        "warning": "SCAFFOLD — synthetic timings and stub oracle. MUST NOT satisfy any Factory/performance/representation gate. Replace with real measurement before use as evidence.",
        "seed": args.seed,
        "columns": args.columns,
        "gt": str(gt) if gt else None,
        "final_density_ms_per_col": {
            "p50": p50(final),
            "p95": p95_final,
            # Forced non-viable: scaffold must not masquerade as passing.
            "viable": None,
            "viable_scaffold_synthetic": (p95_final < 5) if p95_final is not None else None,
            "synthetic": True,
        },
        "chunk_sampler_heightmap_ms_per_col": {
            "p50": p50(height),
            "p95": p95(height),
            "synthetic": True,
        },
        "gpu_zero_crossing_ms_per_col": {"p50": p50(gpu), "p95": p95(gpu), "synthetic": True},
        "oracle": oracle,
        "note": "SCAFFOLD. No chunk getHeight() used. 5 evals/chunk vs 512 per GLOSSARY/WorldNoiseAccess.java:192. Real timings require NoiseConfig + ServerWorld; this scaffold runs offline and emits random numbers.",
        "elapsed_s": time.time() - t0,
    }
    if args.report == "json":
        json.dump(report, sys.stdout, indent=2)
    else:
        print(report)

    print(report)


if __name__ == "__main__":
    main()
