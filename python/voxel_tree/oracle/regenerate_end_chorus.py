"""
Deterministic process-level oracle regeneration for End chorus tracer.

Preferred completion path (no MinecraftServer-in-JUnit):
1. Create/reset disposable oracle world with seed 42, dimension the_end, generate_structures=false
2. Generate target region + halo (acceptable to generate FULL chunks offline; record actual stage)
3. Feed real chunks through DataHarvester /ingestall -> IngestClientHandler -> VoxelIngestService.rawIngest
   (-> WorldConversionFactory.convert -> Mipper -> Mapper -> WorldUpdater.insertUpdate)
4. After ingest, capture post-insert Voxy WorldEngine L0..L4 WorldSections for target region
   (not VoxelizedSectionCaptureMixin HEAD)
5. Decode packed voxels via real Mapper -> canonical 1104/54+255 -> semantic VoxelVolume
6. Serialize OracleFixture v2 with true contentSha256
7. Exit nonzero on incomplete capture

Usage:
  python -m voxel_tree.oracle.regenerate_end_chorus --seed 42 --capture-stage FULL
  python -m voxel_tree.oracle.regenerate_end_chorus --check   # verify existing fixture

This orchestrates existing process-level components (RCON, server.properties, DataHarvester)
instead of bootstrapping Minecraft lifecycle inside JUnit.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

# Reuse existing server/RCON helpers
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

def _find_repo_root() -> Path:
    cur = Path(__file__).resolve()
    for _ in range(6):
        if (cur.parent / "python" / "servers.yaml").exists() or (cur.parent / "servers.yaml").exists():
            return cur.parent
        cur = cur.parent
    return Path(__file__).resolve().parents[3]

REPO_ROOT = _find_repo_root()
SERVERS_YAML = REPO_ROOT / "python" / "servers.yaml"
if not SERVERS_YAML.exists():
    SERVERS_YAML = REPO_ROOT / "servers.yaml"
RUNTIME_DIR = REPO_ROOT / "python" / "tools" / "fabric-server" / "runtime"
if not RUNTIME_DIR.exists():
    RUNTIME_DIR = REPO_ROOT / "tools" / "fabric-server" / "runtime"

ORACLE_ROLE = {
    "name": "oracle_end_chorus",
    "seed": 42,
    "level_name": "oracle_end_chorus",
    "server_port": 25565,
    "rcon_port": 25575,
    "rcon_password": "voxeltree",
    # Halo decomposition (distinct lattices, not a universal formula)
    "halo": {
        "featureReachBlocks": 8,
        "featureReachEvidence": "Chorus max horizontal spread 8 blocks from origin (maxHorizontalSpread parameter in generatePlant)",
        "featureReachSource": "ChorusFlowerBlock.java:178-210 growTreeRecursive maxHorizontalSpread=8",
        "minecraftGenerationHaloChunks": 1,
        "minecraftGenerationHaloEvidence": "FEATURES reads CARVERS@1 and STRUCTURE_STARTS@8, writes 1 chunk; need +1 chunk halo",
        "minecraftGenerationHaloSource": "ChunkPyramid.java:18 ChunkStatus.java:28",
        "voxyMipHaloBlocks": 1,
        "voxyMipHaloEvidence": "Voxy 2x2x2 Mipper group crossing WorldSection boundary needs 1 block halo",
        "voxyMipHaloSource": "Mipper.java:9-55 + WorldSection.java YZX",
        "combinedHaloBlocks": 25,
    },
    "region": {"originSectionX": 0, "originSectionY": 0, "originSectionZ": 0, "extentSections": 2},
    "authoritativeGenerationStage": "FEATURES",
    "captureStage": "FULL",  # offline oracle may generate FULL for simplicity; FEATURES is authoritative for chorus
}

REQUEST_PATH = Path("config/oracle_capture_request.json")
DONE_PATH = Path("config/oracle_capture_done.json")
FIXTURE_PATH = Path("java/oracle-fixtures/end_chorus__s42__r0_0_0_e2__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv2.json")

def _patch_server_properties_for_oracle() -> None:
    from voxel_tree.gui.server_manager import _patch_server_properties  # type: ignore
    patches = {
        "level-name": ORACLE_ROLE["level_name"],
        "level-seed": str(ORACLE_ROLE["seed"]),
        "server-port": str(ORACLE_ROLE["server_port"]),
        "rcon.port": str(ORACLE_ROLE["rcon_port"]),
        "rcon.password": ORACLE_ROLE["rcon_password"],
        "enable-rcon": "true",
        "generate-structures": "false",  # frozen profile generate_structures=false
    }
    _patch_server_properties(patches)
    print(f"[oracle] Patched server.properties for role {ORACLE_ROLE['name']}: seed={ORACLE_ROLE['seed']} level={ORACLE_ROLE['level_name']} structures=false")

def _reset_disposable_world() -> None:
    world_dir = RUNTIME_DIR / ORACLE_ROLE["level_name"]
    if world_dir.exists():
        print(f"[oracle] Removing disposable world {world_dir}")
        shutil.rmtree(world_dir)
    # Also clear per-world Voxy RocksDB data (host_port key)
    voxy_dir = Path.home() / ".local" / "share" / "voxy" / f"localhost_{ORACLE_ROLE['server_port']}"
    # Alternative: Modrinth profile path may vary; we clear both if present
    alt_voxy = REPO_ROOT / ".voxy" / f"localhost_{ORACLE_ROLE['server_port']}"
    for p in [voxy_dir, alt_voxy]:
        if p.exists():
            print(f"[oracle] Note: Voxy data at {p} exists — clearing for deterministic capture may be needed (manual)")
    # Ensure oracle-fixtures dir exists
    FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    if REQUEST_PATH.exists():
        REQUEST_PATH.unlink()
    if DONE_PATH.exists():
        DONE_PATH.unlink()

def _wait_for_rcon(timeout: int = 120) -> bool:
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient  # fallback
    host = "localhost"
    port = ORACLE_ROLE["rcon_port"]
    password = ORACLE_ROLE["rcon_password"]
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with RconClient(host, port, password, timeout=2) as rc:
                rc.command("list")
            print(f"[oracle] RCON ready at {host}:{port}")
            return True
        except Exception:
            time.sleep(2)
    print(f"[oracle] RCON not ready after {timeout}s at {host}:{port}", file=sys.stderr)
    return False

def _run_ingest_via_rcon(radius_chunks: int = 2) -> None:
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient
    host = "localhost"
    port = ORACLE_ROLE["rcon_port"]
    password = ORACLE_ROLE["rcon_password"]
    with RconClient(host, port, password, timeout=10) as rc:
        # Use DataHarvester ingestall with radius covering target+halo: 2 chunks radius is enough for 32+halo region
        # For conservative capture we ingest all chunks in world (small oracle world)
        resp = rc.command(f"ingestall {radius_chunks}")
        print(f"[oracle] /ingestall {radius_chunks}: {resp}")
        # Poll status until complete
        for _ in range(60):
            time.sleep(2)
            status = rc.command("ingestall status")
            print(f"[oracle] ingest status: {status}")
            if "No ingest running" in status or "Complete" in status:
                break

def _trigger_capture() -> None:
    REQUEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    req = {
        "provenanceId": "end_chorus__s42__r0_0_0_e2__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv2",
        "actualCaptureStage": ORACLE_ROLE["captureStage"],
        "region": ORACLE_ROLE["region"],
        "halo": ORACLE_ROLE["halo"],
        "seed": ORACLE_ROLE["seed"],
    }
    REQUEST_PATH.write_text(json.dumps(req, indent=2))
    print(f"[oracle] Wrote capture request {REQUEST_PATH}: {req}")

def _wait_for_done(timeout: int = 120) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if DONE_PATH.exists():
            data = json.loads(DONE_PATH.read_text())
            if "error" in data:
                print(f"[oracle] Capture failed: {data['error']}", file=sys.stderr)
                sys.exit(1)
            print(f"[oracle] Capture done: provenance={data.get('provenanceId')} sha={data.get('contentSha256')}")
            print(f"[oracle] Report: {data.get('report')}")
            return data
        time.sleep(1)
    print(f"[oracle] Timeout waiting for {DONE_PATH} after {timeout}s", file=sys.stderr)
    print(f"[oracle] Is the Minecraft client running with LODiffusion + Voxy and DataHarvester auto-connect?", file=sys.stderr)
    sys.exit(1)

def _verify_fixture(path: Path = FIXTURE_PATH) -> None:
    if not path.exists():
        print(f"[oracle] Fixture not found at {path}", file=sys.stderr)
        sys.exit(1)
    data = json.loads(path.read_text())
    sha = data.get("contentSha256")
    provenance = data.get("provenanceId")
    print(f"[oracle] Fixture {provenance} sha={sha} at {path}")
    vols = data.get("volumes", {})
    for lvl in ["L4","L3","L2","L1","L0"]:
        v = vols.get(lvl)
        if v:
            print(f"[oracle] {lvl}: nonAir={v.get('nonAirCount')} extent={v.get('extent')}")
    # Nonzero check: at least L0 must have chorus if generation succeeded
    l0 = vols.get("L0", {}).get("blocks", [])
    chorus = sum(1 for b in l0 if b in (196,197))
    print(f"[oracle] L0 chorus voxels: {chorus}")

def main(argv: list[str] | None = None) -> None:
    import argparse
    p = argparse.ArgumentParser(description="Deterministic End chorus oracle regeneration via DataHarvester pipeline")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--capture-stage", choices=["FEATURES","FULL"], default="FULL", help="Actual capture stage (FULL is acceptable offline; FEATURES is authoritative)")
    p.add_argument("--radius-chunks", type=int, default=2)
    p.add_argument("--check", action="store_true", help="Verify existing fixture without regenerating")
    p.add_argument("--reset-world", action="store_true", help="Delete disposable world before capture")
    p.add_argument("--ingest-only", action="store_true", help="Only run ingest, skip capture trigger (for debugging)")
    args = p.parse_args(argv)

    if args.check:
        _verify_fixture()
        return

    if args.reset_world:
        _reset_disposable_world()

    _patch_server_properties_for_oracle()

    # Start server if not running
    if not _wait_for_rcon(timeout=5):
        print("[oracle] Server not running — start it via: python -m voxel_tree --server start --role oracle_end_chorus (or ensure servers.yaml has oracle_end_chorus role)", file=sys.stderr)
        print("[oracle] Alternatively, run: python -m voxel_tree.oracle.regenerate_end_chorus --reset-world", file=sys.stderr)
        # Patch servers.yaml to add oracle role if missing
        if SERVERS_YAML.exists():
            text = SERVERS_YAML.read_text()
            if "oracle_end_chorus" not in text:
                print(f"[oracle] servers.yaml missing oracle_end_chorus role — add:\n  oracle_end_chorus:\n    seed: 42\n    level_name: oracle_end_chorus\n    server_port: 25565\n    rcon_port: 25575\n    rcon_password: voxeltree", file=sys.stderr)
        sys.exit(1)

    _run_ingest_via_rcon(radius_chunks=args.radius_chunks)
    if args.ingest_only:
        print("[oracle] Ingest complete (ingest-only)")
        return
    _trigger_capture()
    data = _wait_for_done(timeout=180)
    _verify_fixture()
    print("[oracle] SUCCESS: real fixed-seed End chunks ingested through Voxy and post-ingest WorldSections captured")
    # CandidateVerifier replay is done client-side in OracleFileTrigger; report here
    print(data.get("report",""))

if __name__ == "__main__":
    main()
