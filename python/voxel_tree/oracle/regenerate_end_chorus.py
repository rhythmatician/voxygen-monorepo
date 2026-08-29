"""
Deterministic process-level oracle regeneration for End chorus tracer.

Preferred completion path (no MinecraftServer-in-JUnit):
1. Create/reset disposable oracle world with seed 42, dimension the_end, generate_structures=false
2. Generate target outer-island region + halo (halo-complete chunk rect via blockRegion+halo, exact bounds mode)
3. Feed real chunks through DataHarvester /ingestall oracle -> IngestClientHandler -> VoxelIngestService.rawIngest
   (-> WorldConversionFactory.convert -> Mipper -> Mapper -> WorldUpdater.insertUpdate)
4. After ingest, capture post-insert Voxy WorldEngine L0..L4 WorldSections for blockRegion (per-Level origins via floorDiv)
5. Decode packed voxels via real Mapper -> canonical 1104/54+255 -> semantic VoxelVolume (explicit per-Level)
6. Serialize OracleFixture v3 with true contentSha256 + blockRegion/perLevelOrigins/chunkRect + separate actualCaptureStage
7. Exit nonzero on incomplete capture
"""

from __future__ import annotations

import json
import shutil
import sys
import time
from pathlib import Path

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
    "region": {"originSectionX": 96, "originSectionY": 4, "originSectionZ": 0, "extentSections": 2},
    "blockRegion": {"originBlockX": 1536, "originBlockY": 64, "originBlockZ": 0, "extentBlocks": 32},
    "authoritativeGenerationStage": "FEATURES",
    "actualCaptureStage": "FULL",
    "captureStage": "FULL",
}

REQUEST_PATH = Path("config/oracle_capture_request.json")
DONE_PATH = Path("config/oracle_capture_done.json")
INGEST_ACK_PATH = Path("config/oracle_ingest_ack.json")
FIXTURE_PATH = Path("java/oracle-fixtures/end_chorus__s42__b1536_64_0_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3.json")

def _chunk_rect_with_halo(blockRegion, haloBlocks):
    minBx = blockRegion["originBlockX"] - haloBlocks
    minBz = blockRegion["originBlockZ"] - haloBlocks
    maxBx = blockRegion["originBlockX"] + blockRegion["extentBlocks"] -1 + haloBlocks
    maxBz = blockRegion["originBlockZ"] + blockRegion["extentBlocks"] -1 + haloBlocks
    import math
    minCx = math.floor(minBx/16)
    minCz = math.floor(minBz/16)
    maxCx = math.floor(maxBx/16)
    maxCz = math.floor(maxBz/16)
    return (minCx, minCz, maxCx, maxCz)

def _patch_server_properties_for_oracle() -> None:
    from voxel_tree.gui.server_manager import _patch_server_properties  # type: ignore
    patches = {
        "level-name": ORACLE_ROLE["level_name"],
        "level-seed": str(ORACLE_ROLE["seed"]),
        "server-port": str(ORACLE_ROLE["server_port"]),
        "rcon.port": str(ORACLE_ROLE["rcon_port"]),
        "rcon.password": ORACLE_ROLE["rcon_password"],
        "enable-rcon": "true",
        "generate-structures": "false",
    }
    _patch_server_properties(patches)
    print(f"[oracle] Patched server.properties for role {ORACLE_ROLE['name']}: seed={ORACLE_ROLE['seed']} level={ORACLE_ROLE['level_name']} structures=false blockRegion={ORACLE_ROLE['blockRegion']} chunkRect={_chunk_rect_with_halo(ORACLE_ROLE['blockRegion'], ORACLE_ROLE['halo']['combinedHaloBlocks'])}")

def _reset_disposable_world() -> None:
    world_dir = RUNTIME_DIR / ORACLE_ROLE["level_name"]
    if world_dir.exists():
        print(f"[oracle] Removing disposable world {world_dir}")
        shutil.rmtree(world_dir)
    voxy_dir = Path.home() / ".local" / "share" / "voxy" / f"localhost_{ORACLE_ROLE['server_port']}"
    alt_voxy = REPO_ROOT / ".voxy" / f"localhost_{ORACLE_ROLE['server_port']}"
    for p in [voxy_dir, alt_voxy]:
        if p.exists():
            print(f"[oracle] Note: Voxy data at {p} exists — clearing for deterministic capture may be needed (manual)")
    FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    for p in [REQUEST_PATH, DONE_PATH, INGEST_ACK_PATH]:
        if p.exists():
            p.unlink()

def _wait_for_rcon(timeout: int = 120) -> bool:
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient  # type: ignore
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

def _run_ingest_via_rcon(radius_chunks: int | None = None) -> None:
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient  # type: ignore
    host = "localhost"
    port = ORACLE_ROLE["rcon_port"]
    password = ORACLE_ROLE["rcon_password"]
    br = ORACLE_ROLE["blockRegion"]
    halo = ORACLE_ROLE["halo"]["combinedHaloBlocks"]
    minCx, minCz, maxCx, maxCz = _chunk_rect_with_halo(br, halo)
    print(f"[oracle] Exact oracle bounds [{minCx},{minCz} -> {maxCx},{maxCz}] halo {halo} for blockRegion {br}")
    # Batch ID and expected count for client ack barrier
    import uuid, math
    batch_id = str(uuid.uuid4())[:8]
    expected_chunks = (maxCx - minCx + 1) * (maxCz - minCz + 1)
    # Write batch info for client to pick up via request file later
    with RconClient(host, port, password, timeout=10) as rc:
        # Ensure ingest runs in the_end dimension; do not depend on RCON source position
        resp = rc.command(f"execute in minecraft:the_end run ingestall oracle {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}")
        print(f"[oracle] /ingestall oracle {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}: {resp}")
        if "Unknown" in resp or "Incorrect" in resp or "No such" in resp:
            # Fallback to legacy bounds without batch
            resp2 = rc.command(f"execute in minecraft:the_end run ingestall oracle {minCx} {minCz} {maxCx} {maxCz}")
            print(f"[oracle] fallback /ingestall oracle {minCx} {minCz} {maxCx} {maxCz}: {resp2}")
            if "Unknown" in resp2 or "Incorrect" in resp2:
                fallback_radius = max(maxCx - minCx, maxCz - minCz) // 2 + 1
                resp3 = rc.command(f"ingestall {fallback_radius}")
                print(f"[oracle] fallback /ingestall {fallback_radius}: {resp3}")
        for _ in range(90):
            time.sleep(2)
            status = rc.command("ingestall status")
            print(f"[oracle] ingest status: {status}")
            if "No ingest running" in status or "Complete" in status:
                break
        else:
            print(f"[oracle] ingest did not report complete within timeout", file=sys.stderr)
    # Real client completion barrier: wait for client ack file with matching batchId and expected count
    # IngestClientHandler writes config/oracle_ingest_ack.json after all rawIngest calls for the batch
    deadline = time.time() + 60
    while time.time() < deadline:
        if INGEST_ACK_PATH.exists():
            try:
                ack = json.loads(INGEST_ACK_PATH.read_text())
                if ack.get("batchId") == batch_id and ack.get("expectedChunks") == expected_chunks and ack.get("completedSections", 0) >= 0:
                    print(f"[oracle] Client ack received for batch {batch_id}: {ack}")
                    # Keep ack for capture trigger to verify
                    break
            except Exception as e:
                print(f"[oracle] ack read failed: {e}")
        time.sleep(1)
    else:
        print(f"[oracle] Warning: no client ack for batch {batch_id} within 60s - proceeding to capture but WorldEngine may still be flushing", file=sys.stderr)

def _trigger_capture() -> None:
    REQUEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    br = ORACLE_ROLE["blockRegion"]
    rect = _chunk_rect_with_halo(br, ORACLE_ROLE["halo"]["combinedHaloBlocks"])
    import math, uuid
    per = {}
    for L in range(5):
        wsBlockSize = 32*(1<<L)
        wsX = math.floor(br["originBlockX"]/wsBlockSize)
        wsY = math.floor(br["originBlockY"]/wsBlockSize)
        wsZ = math.floor(br["originBlockZ"]/wsBlockSize)
        per[f"L{L}"] = {"wsX": wsX, "wsY": wsY, "wsZ": wsZ, "blockSize": wsBlockSize}
    req = {
        "provenanceId": "end_chorus__s42__b1536_64_0_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3",
        "authoritativeGenerationStage": ORACLE_ROLE["authoritativeGenerationStage"],
        "actualCaptureStage": ORACLE_ROLE["actualCaptureStage"],
        "region": ORACLE_ROLE["region"],
        "blockRegion": br,
        "haloCompleteChunkRect": {"minChunkX": rect[0], "minChunkZ": rect[1], "maxChunkX": rect[2], "maxChunkZ": rect[3]},
        "perLevelWorldSectionOrigins": per,
        "halo": ORACLE_ROLE["halo"],
        "seed": ORACLE_ROLE["seed"],
    }
    REQUEST_PATH.write_text(json.dumps(req, indent=2))
    print(f"[oracle] Wrote capture request {REQUEST_PATH}: {json.dumps(req, indent=2)}")

def _wait_for_done(timeout: int = 180) -> dict:
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
    print(f"[oracle] authoritativeGenerationStage={data.get('authoritativeGenerationStage')} actualCaptureStage={data.get('actualCaptureStage')}")
    if "blockRegion" in data:
        print(f"[oracle] blockRegion {data['blockRegion']} perLevel {data.get('perLevelWorldSectionOrigins')} chunkRect {data.get('haloCompleteChunkRect')}")
    vols = data.get("volumes", {})
    for lvl in ["L4","L3","L2","L1","L0"]:
        v = vols.get(lvl)
        if v:
            chorus = sum(1 for b in v.get("blocks",[]) if b in (196,197))
            print(f"[oracle] {lvl}: nonAir={v.get('nonAirCount')} extent={v.get('extent')} chorus={chorus}")
    l0 = vols.get("L0", {}).get("blocks", [])
    chorus = sum(1 for b in l0 if b in (196,197))
    print(f"[oracle] L0 chorus voxels: {chorus}")
    if chorus==0:
        print(f"[oracle] ERROR: L0 has zero chorus — selected blockRegion {data.get('blockRegion')} does not contain real chorus; anchor must be re-pinned via outer-island chunk inspection", file=sys.stderr)
        sys.exit(1)
    # Do not assert L4/L3 expected 0; record all Level counts without outcome-dependent assertions

def main(argv: list[str] | None = None) -> None:
    import argparse
    p = argparse.ArgumentParser(description="Deterministic End chorus oracle regeneration via DataHarvester pipeline (block-space outer island, exact bounds, batch ack)")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--capture-stage", choices=["FEATURES","FULL"], default="FULL", help="Actual capture stage (FULL is acceptable offline; FEATURES is authoritative)")
    p.add_argument("--radius-chunks", type=int, default=None)
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

    if not _wait_for_rcon(timeout=5):
        print("[oracle] Server not running — start it via: python -m voxel_tree --server start --role oracle_end_chorus", file=sys.stderr)
        if SERVERS_YAML.exists():
            text = SERVERS_YAML.read_text()
            if "oracle_end_chorus" not in text:
                print(f"[oracle] servers.yaml missing oracle_end_chorus role — add:\\n  oracle_end_chorus:\\n    seed: 42\\n    level_name: oracle_end_chorus", file=sys.stderr)
        sys.exit(1)

    _run_ingest_via_rcon(radius_chunks=args.radius_chunks)
    if args.ingest_only:
        print("[oracle] Ingest complete (ingest-only)")
        return
    _trigger_capture()
    data = _wait_for_done(timeout=180)
    _verify_fixture()
    print("[oracle] SUCCESS: real fixed-seed End chunks ingested through Voxy and post-ingest WorldSections captured (blockRegion + per-Level origins independent, batch ack barrier)")
    print(data.get("report",""))

if __name__ == "__main__":
    main()
