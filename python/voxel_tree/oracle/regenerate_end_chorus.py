"""
Deterministic process-level oracle regeneration for End chorus tracer.

Hardened pre-capture: L4 footprint ingest (36x36=1296 chunks), true Voxy RETURN barrier, oracle sends all sections, missing->fatal, v3 self-describing, isolated port 25567, fail-closed.
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
    "server_port": 25567,
    "rcon_port": 25577,
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

def _l4_chunk_rect_with_halo(blockRegion, haloBlocks):
    """Derive ingest coverage from union of per-Level WorldSection footprints, i.e. L4. For anchor 1536,64,0: L4 ws [3,0,0] 512 blocks => chunks 96..127 x 0..31 plus halo 25 => 94..129 x -2..33 = 1296 chunks."""
    import math
    # L4 WorldSection containing the tracer blockRegion origin
    wsBlockSize = 32 * (1 << 4)  # 512
    wsX = math.floor(blockRegion["originBlockX"] / wsBlockSize)
    wsY = math.floor(blockRegion["originBlockY"] / wsBlockSize)
    wsZ = math.floor(blockRegion["originBlockZ"] / wsBlockSize)
    # L4 block range
    minBx_l4 = wsX * wsBlockSize - haloBlocks
    minBz_l4 = wsZ * wsBlockSize - haloBlocks
    maxBx_l4 = wsX * wsBlockSize + wsBlockSize -1 + haloBlocks
    maxBz_l4 = wsZ * wsBlockSize + wsBlockSize -1 + haloBlocks
    minCx = math.floor(minBx_l4/16)
    minCz = math.floor(minBz_l4/16)
    maxCx = math.floor(maxBx_l4/16)
    maxCz = math.floor(maxBz_l4/16)
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
    rect = _l4_chunk_rect_with_halo(ORACLE_ROLE["blockRegion"], ORACLE_ROLE["halo"]["combinedHaloBlocks"])
    print(f"[oracle] Patched server.properties for role {ORACLE_ROLE['name']}: seed={ORACLE_ROLE['seed']} level={ORACLE_ROLE['level_name']} structures=false blockRegion={ORACLE_ROLE['blockRegion']} L4 chunkRect={rect} ({(rect[2]-rect[0]+1)*(rect[3]-rect[1]+1)} chunks)")

def _reset_disposable_world() -> None:
    # Check if server is running first - if so, require stop before delete
    # If server is running, the world files are locked and properties aren't what created active world
    try:
        from voxel_tree.utils.rcon import RconClient  # type: ignore
        host = "localhost"
        port = ORACLE_ROLE["rcon_port"]
        password = ORACLE_ROLE["rcon_password"]
        with RconClient(host, port, password, timeout=1) as rc:
            rc.command("list")
        print(f"[oracle] Server is running on {host}:{port} - sending /stop before world reset", file=sys.stderr)
        try:
            with RconClient(host, port, password, timeout=3) as rc:
                rc.command("stop")
        except Exception:
            pass
        # Wait for RCON to go down
        for _ in range(15):
            time.sleep(2)
            try:
                with RconClient(host, port, password, timeout=1) as rc2:
                    rc2.command("list")
            except Exception:
                break
        print("[oracle] Server stopped")
    except Exception:
        pass  # server not running, safe to delete
    world_dir = RUNTIME_DIR / ORACLE_ROLE["level_name"]
    if world_dir.exists():
        print(f"[oracle] Removing disposable world {world_dir}")
        shutil.rmtree(world_dir)
    # Isolated Voxy storage for oracle port must be cleared - Voxy world identity is dimension+biome/world seed, so repeat seed-42 runs share same storage
    for cand in [
        Path.home() / ".local" / "share" / "voxy" / f"localhost_{ORACLE_ROLE['server_port']}",
        REPO_ROOT / ".voxy" / f"localhost_{ORACLE_ROLE['server_port']}",
        Path.home() / ".local" / "share" / "voxy" / f"localhost_{ORACLE_ROLE['server_port']}" / "oracle_end_chorus",
        RUNTIME_DIR / ".voxy" / f"localhost_{ORACLE_ROLE['server_port']}",
    ]:
        if cand.exists():
            print(f"[oracle] Clearing Voxy storage {cand}")
            shutil.rmtree(cand, ignore_errors=True)
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

def _cheap_chorus_scan() -> None:
    """Cheap server-only scan around proposed anchor before paying for 1296-chunk Voxy run. Verifies outer-island contains real chorus via server block checks."""
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient  # type: ignore
    host = "localhost"
    port = ORACLE_ROLE["rcon_port"]
    password = ORACLE_ROLE["rcon_password"]
    br = ORACLE_ROLE["blockRegion"]
    # Scan a few highland columns for chorus_plant/flower using /execute if block - deterministic cheap check
    found = False
    with RconClient(host, port, password, timeout=10) as rc:
        for dx in [0, 8, 16, 24]:
            for dz in [0, 8, 16, 24]:
                for dy in range(64, 100):
                    x = br["originBlockX"] + dx
                    y = dy
                    z = br["originBlockZ"] + dz
                    resp = rc.command(f"execute in minecraft:the_end positioned {x} {y} {z} if block ~ ~ ~ minecraft:chorus_plant run say found")
                    if "found" in resp.lower():
                        print(f"[oracle] Cheap scan: found chorus_plant at {x} {y} {z}")
                        found = True
                        break
                    resp2 = rc.command(f"execute in minecraft:the_end positioned {x} {y} {z} if block ~ ~ ~ minecraft:chorus_flower run say found")
                    if "found" in resp2.lower():
                        print(f"[oracle] Cheap scan: found chorus_flower at {x} {y} {z}")
                        found = True
                        break
                if found:
                    break
            if found:
                break
    if not found:
        print(f"[oracle] Cheap scan: no chorus_plant/flower found in 4x4x36 scan around {br} - anchor may not contain chorus, proceed but capture will enforce L0 chorus>0", file=sys.stderr)

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
    # Derive from L4 footprint, not tracer ROI - ensures L2-L4 WorldSections fully populated
    minCx, minCz, maxCx, maxCz = _l4_chunk_rect_with_halo(br, halo)
    print(f"[oracle] Exact L4 oracle bounds [{minCx},{minCz} -> {maxCx},{maxCz}] halo {halo} for blockRegion {br} => {(maxCx-minCx+1)*(maxCz-minCz+1)} chunks")
    import uuid
    batch_id = str(uuid.uuid4())[:8]
    expected_chunks = (maxCx - minCx + 1) * (maxCz - minCz + 1)
    # Ensure client is in The End and away from target to avoid simultaneous client chunk loading interfering with oracle
    with RconClient(host, port, password, timeout=10) as rc:
        tp_resp = rc.command("execute as @a in minecraft:the_end run tp @s 0 80 0")
        print(f"[oracle] Teleported client to central End island (0 80 0) away from target: {tp_resp}")
        import time as _t
        _t.sleep(3)
        # Verify eligible player exists in The End
        eligible = rc.command("execute as @a in minecraft:the_end run say eligible")
        if "eligible" not in eligible.lower() and "@a" not in eligible:
            # Fallback check via list
            list_resp = rc.command("list")
            print(f"[oracle] Checking eligible player in End, list: {list_resp}")
            # We still require at least one player; if none, fail
            if "0 players" in list_resp or "players online: 0" in list_resp.lower():
                print(f"[oracle] ERROR: No eligible player in The End for ingest - DataHarvester requires client in same dimension as activeLevel", file=sys.stderr)
                sys.exit(1)
        resp = rc.command(f"execute in minecraft:the_end run ingestall oracle {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}")
        print(f"[oracle] /ingestall oracle {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}: {resp}")
        if "Unknown" in resp or "Incorrect" in resp or "No such" in resp:
            print(f"[oracle] ERROR: /ingestall oracle command not installed - ensure DataHarvester JAR built from python/tools/data-harvester is installed", file=sys.stderr)
            sys.exit(1)
        for _ in range(180):
            time.sleep(2)
            status = rc.command("ingestall status")
            print(f"[oracle] ingest status: {status}")
            if "No ingest running" in status or "Complete" in status:
                break
        else:
            print(f"[oracle] ingest did not report complete within timeout", file=sys.stderr)
            sys.exit(1)
    # Hardened barrier: require success true, exact receivedChunks, exact expectedEnqueuedSections == completedSections, failures==0
    deadline = time.time() + 180
    while time.time() < deadline:
        if INGEST_ACK_PATH.exists():
            try:
                ack = json.loads(INGEST_ACK_PATH.read_text())
                if ack.get("batchId") == batch_id:
                    if ack.get("success") is not True:
                        print(f"[oracle] Ack indicates failure (success false): {ack}", file=sys.stderr)
                        sys.exit(1)
                    if ack.get("expectedChunks") != expected_chunks:
                        print(f"[oracle] Ack expectedChunks mismatch {ack.get('expectedChunks')} != {expected_chunks} waiting...", file=sys.stderr)
                    elif ack.get("receivedChunks", 0) != expected_chunks:
                        print(f"[oracle] Ack receivedChunks {ack.get('receivedChunks')} != expected {expected_chunks} waiting...", file=sys.stderr)
                    elif ack.get("expectedEnqueuedSections", ack.get("completedSections",0)) != ack.get("completedSections",0):
                        print(f"[oracle] Ack pending keys not empty: expectedEnqueued {ack.get('expectedEnqueuedSections')} completed {ack.get('completedSections')} waiting...", file=sys.stderr)
                    elif ack.get("failedEnqueues",0) != 0:
                        print(f"[oracle] Ack has failedEnqueues {ack.get('failedEnqueues')} - failing", file=sys.stderr)
                        sys.exit(1)
                    else:
                        print(f"[oracle] Client TRUE ack received for batch {batch_id}: {ack}")
                        return
                    print(f"[oracle] Ack incomplete {ack} waiting...", file=sys.stderr)
            except Exception as e:
                print(f"[oracle] ack read failed: {e}")
        time.sleep(1)
    print(f"[oracle] ERROR: no successful client ack for batch {batch_id} within 180s - WorldEngine may not have flushed or batch had failures. Ensure client is running with Voxy + DataHarvester and insertUpdate RETURN barrier is active. Ack must have success:true, exact receivedChunks, and pendingKeys empty.", file=sys.stderr)
    sys.exit(1)

def _trigger_capture() -> None:
    REQUEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    br = ORACLE_ROLE["blockRegion"]
    rect = _l4_chunk_rect_with_halo(br, ORACLE_ROLE["halo"]["combinedHaloBlocks"])
    import math
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
        "generationOrder": "squared distance to center of L4 rect (dx*dx+dz*dz) -> X -> Z, then server tick order",
    }
    REQUEST_PATH.write_text(json.dumps(req, indent=2))
    print(f"[oracle] Wrote capture request {REQUEST_PATH}: {json.dumps(req, indent=2)}")

def _wait_for_done(timeout: int = 300) -> dict:
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

def main(argv: list[str] | None = None) -> None:
    import argparse
    p = argparse.ArgumentParser(description="Deterministic End chorus oracle regeneration via DataHarvester pipeline (L4 coverage, RETURN barrier, fail-closed)")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--capture-stage", choices=["FEATURES","FULL"], default="FULL", help="Actual capture stage (FULL is acceptable offline; FEATURES is authoritative)")
    p.add_argument("--radius-chunks", type=int, default=None)
    p.add_argument("--check", action="store_true", help="Verify existing fixture without regenerating")
    p.add_argument("--reset-world", action="store_true", help="Delete disposable world + Voxy storage before capture (stops server first)")
    p.add_argument("--ingest-only", action="store_true", help="Only run ingest, skip capture trigger (for debugging)")
    p.add_argument("--cheap-scan", action="store_true", help="Run cheap server-only chorus scan around anchor before ingest")
    args = p.parse_args(argv)

    if args.check:
        _verify_fixture()
        return

    if args.reset_world:
        _reset_disposable_world()
        _patch_server_properties_for_oracle()
        if not _wait_for_rcon(timeout=5):
            print("[oracle] Server not running after reset - attempting to start oracle_end_chorus role", file=sys.stderr)
            _start_oracle_server_via_manager()
            if not _wait_for_rcon(timeout=120):
                print("[oracle] Server not running — start it via: python -m voxel_tree --server start --role oracle_end_chorus (isolated port 25567)", file=sys.stderr)
                sys.exit(1)
        # Wait for client to connect before proceeding
        print("[oracle] Waiting for client to auto-connect...")
        import time as _t2
        for _ in range(30):
            try:
                from voxel_tree.utils.rcon import RconClient as _RC
                with _RC("localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=2) as _rc:
                    lst = _rc.command("list")
                    if "players online" in lst.lower():
                        # At least one player online
                        break
            except Exception:
                pass
            _t2.sleep(2)
    else:
        _patch_server_properties_for_oracle()

    if not _wait_for_rcon(timeout=5):
        print("[oracle] Server not running — start it via: python -m voxel_tree --server start --role oracle_end_chorus (isolated port 25567)", file=sys.stderr)
        if SERVERS_YAML.exists():
            text = SERVERS_YAML.read_text()
            if "oracle_end_chorus" not in text:
                print(f"[oracle] servers.yaml missing oracle_end_chorus role", file=sys.stderr)
        sys.exit(1)

    if args.cheap_scan:
        print("[oracle] Running cheap scan in separate preflight world to avoid polluting authoritative generation history")
        # Preflight: use separate level_name to isolate generation history
        orig_level = ORACLE_ROLE["level_name"]
        ORACLE_ROLE["level_name"] = "oracle_end_chorus_preflight"
        _reset_disposable_world()
        _patch_server_properties_for_oracle()
        # Start preflight server if not running
        if not _wait_for_rcon(timeout=5):
            print("[oracle] Preflight server not running - attempting to start oracle_end_chorus_preflight via manager", file=sys.stderr)
            _start_oracle_server_via_manager()
            if not _wait_for_rcon(timeout=120):
                print("[oracle] Preflight server failed to start", file=sys.stderr)
                sys.exit(1)
        _cheap_chorus_scan()
        # Destroy preflight world and Voxy state before real capture
        print("[oracle] Destroying preflight world before pristine real capture")
        _reset_disposable_world()
        ORACLE_ROLE["level_name"] = orig_level
        _patch_server_properties_for_oracle()

    _run_ingest_via_rcon(radius_chunks=args.radius_chunks)
    if args.ingest_only:
        print("[oracle] Ingest complete (ingest-only)")
        return
    _trigger_capture()
    data = _wait_for_done(timeout=300)
    _verify_fixture()
    print("[oracle] SUCCESS: real fixed-seed End chunks ingested through Voxy RETURN barrier and post-insert WorldSections captured (L4 36x36=1296 chunks, per-Level origins independent, batch ack barrier)")
    print(data.get("report",""))

if __name__ == "__main__":
    main()
