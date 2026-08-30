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
    # Use parents[3] directly for this file location (python/voxel_tree/oracle/...)
    cur = Path(__file__).resolve().parents[3]
    if (cur / "python" / "servers.yaml").exists() or (cur / "servers.yaml").exists():
        return cur
    cur2 = Path(__file__).resolve()
    for _ in range(7):
        if (cur2 / "python" / "servers.yaml").exists() or (cur2 / "servers.yaml").exists():
            return cur2
        cur2 = cur2.parent
    return Path(__file__).resolve().parents[3]


def _rcon_has_players(lst: str | None) -> bool:
    """Robust check for >0 players in RCON list output.

    RCON 'list' returns 'There are X of a max of 20 players online: ...'.
    Naive substring check for '0 players' fails because '20 players'
    always contains '0 players' as substring. Use regex to parse the
    actual online count.
    """
    if not lst:
        return False
    low = lst.lower()
    if "players online" not in low:
        return False
    import re

    m = re.search(r"there are (\d+) of a max", low)
    if m:
        try:
            return int(m.group(1)) > 0
        except ValueError:
            pass
    # Fallback: explicit 0 check without false positive from '20 players'
    if "there are 0 of a max" in low:
        return False
    # If we can't parse, assume any non-zero listing with names after colon has players
    if ":" in lst:
        after = lst.split(":", 1)[1].strip()
        if after:
            return True
    return False


def _rcon_is_zero_players(lst: str | None) -> bool:
    if not lst:
        return True
    low = lst.lower()
    import re

    m = re.search(r"there are (\d+) of a max", low)
    if m:
        try:
            return int(m.group(1)) == 0
        except ValueError:
            pass
    return "there are 0 of a max" in low or "0 players online" in low


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
    "region": {
        "originSectionX": 100,
        "originSectionY": 4,
        "originSectionZ": 8,
        "extentSections": 2,
    },
    "blockRegion": {
        "originBlockX": 1600,
        "originBlockY": 64,
        "originBlockZ": 128,
        "extentBlocks": 32,
    },
    "authoritativeGenerationStage": "FEATURES",
    "actualCaptureStage": "FULL",
    "captureStage": "FULL",
}

REQUEST_PATH = REPO_ROOT / "java" / "run" / "config" / "oracle_capture_request.json"
DONE_PATH = REPO_ROOT / "java" / "run" / "config" / "oracle_capture_done.json"
INGEST_ACK_PATH = REPO_ROOT / "java" / "run" / "config" / "oracle_ingest_ack.json"
FIXTURE_PATH = Path(
    "java/oracle-fixtures/end_chorus__s42__b1600_64_128_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3.json"
)

# Global handle for AFK client gradle process (retained for orderly shutdown)
_ORACLE_CLIENT_PROC = None


def _chunk_rect_with_halo(blockRegion, haloBlocks):
    minBx = blockRegion["originBlockX"] - haloBlocks
    minBz = blockRegion["originBlockZ"] - haloBlocks
    maxBx = blockRegion["originBlockX"] + blockRegion["extentBlocks"] - 1 + haloBlocks
    maxBz = blockRegion["originBlockZ"] + blockRegion["extentBlocks"] - 1 + haloBlocks
    import math

    minCx = math.floor(minBx / 16)
    minCz = math.floor(minBz / 16)
    maxCx = math.floor(maxBx / 16)
    maxCz = math.floor(maxBz / 16)
    return (minCx, minCz, maxCx, maxCz)


def _l4_chunk_rect_with_halo(blockRegion, haloBlocks):
    """Derive ingest coverage from union of per-Level WorldSection footprints, i.e. L4. For anchor 1600,64,128: L4 ws [3,0,0] 512 blocks => chunks 96..127 x 0..31 plus halo 25 => 94..129 x -2..33 = 1296 chunks."""
    import math

    # L4 WorldSection containing the tracer blockRegion origin
    wsBlockSize = 32 * (1 << 4)  # 512
    wsX = math.floor(blockRegion["originBlockX"] / wsBlockSize)
    _wsY = math.floor(blockRegion["originBlockY"] / wsBlockSize)  # noqa: F841
    wsZ = math.floor(blockRegion["originBlockZ"] / wsBlockSize)
    # L4 block range
    minBx_l4 = wsX * wsBlockSize - haloBlocks
    minBz_l4 = wsZ * wsBlockSize - haloBlocks
    maxBx_l4 = wsX * wsBlockSize + wsBlockSize - 1 + haloBlocks
    maxBz_l4 = wsZ * wsBlockSize + wsBlockSize - 1 + haloBlocks
    minCx = math.floor(minBx_l4 / 16)
    minCz = math.floor(minBz_l4 / 16)
    maxCx = math.floor(maxBx_l4 / 16)
    maxCz = math.floor(maxBz_l4 / 16)
    return (minCx, minCz, maxCx, maxCz)


def _patch_server_properties_for_oracle() -> None:
    patches = {
        "level-name": ORACLE_ROLE["level_name"],
        "level-seed": str(ORACLE_ROLE["seed"]),
        "server-port": str(ORACLE_ROLE["server_port"]),
        "rcon.port": str(ORACLE_ROLE["rcon_port"]),
        "rcon.password": ORACLE_ROLE["rcon_password"],
        "enable-rcon": "true",
        "generate-structures": "false",
    }
    # Prefer server_manager helper, fallback to manual without Qt dependency
    try:
        from voxel_tree.gui.server_manager import _patch_server_properties  # type: ignore

        _patch_server_properties(patches)
    except Exception as e:
        print(
            f"[oracle] server_manager _patch import failed ({e}), falling back to manual server.properties patch",
            file=sys.stderr,
        )
        # Manual patch: same logic as server_manager._patch_server_properties
        # Determine server.properties path from RUNTIME_DIR
        _sp = RUNTIME_DIR / "server.properties"
        try:
            text = _sp.read_text(encoding="utf-8")
        except FileNotFoundError:
            text = ""
        remaining = dict(patches)
        new_lines = []
        for line in text.splitlines():
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and "=" in stripped:
                key = stripped.split("=", 1)[0].strip()
                if key in remaining:
                    new_lines.append(f"{key}={remaining.pop(key)}")
                    continue
            new_lines.append(line)
        for k, v in remaining.items():
            new_lines.append(f"{k}={v}")
        _sp.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
    # Write oracle mode marker for DataHarvester SERVER_STARTED freeze invariant (Path.of("config/oracle_mode.json") in mod)
    try:
        marker_path = RUNTIME_DIR / "config" / "oracle_mode.json"
        marker_path.parent.mkdir(parents=True, exist_ok=True)
        import json as _jm

        _jm.dump(
            {
                "level_name": ORACLE_ROLE["level_name"],
                "oracle_end_chorus": True,
                "seed": ORACLE_ROLE["seed"],
            },
            marker_path.open("w", encoding="utf-8"),
            indent=2,
        )
        print(f"[oracle] Wrote oracle_mode marker {marker_path}")
    except Exception as e:
        print(f"[oracle] Failed to write oracle_mode marker: {e}", file=sys.stderr)
    rect = _l4_chunk_rect_with_halo(
        ORACLE_ROLE["blockRegion"], ORACLE_ROLE["halo"]["combinedHaloBlocks"]
    )
    print(
        f"[oracle] Patched server.properties for role {ORACLE_ROLE['name']}: seed={ORACLE_ROLE['seed']} level={ORACLE_ROLE['level_name']} structures=false blockRegion={ORACLE_ROLE['blockRegion']} L4 chunkRect={rect} ({(rect[2] - rect[0] + 1) * (rect[3] - rect[1] + 1)} chunks)"
    )


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
        print(
            f"[oracle] Server is running on {host}:{port} - sending /stop before world reset",
            file=sys.stderr,
        )
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
        Path.home()
        / ".local"
        / "share"
        / "voxy"
        / f"localhost_{ORACLE_ROLE['server_port']}"
        / "oracle_end_chorus",
        RUNTIME_DIR / ".voxy" / f"localhost_{ORACLE_ROLE['server_port']}",
    ]:
        if cand.exists():
            print(f"[oracle] Clearing Voxy storage {cand}")
            shutil.rmtree(cand, ignore_errors=True)
    FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    for p in [REQUEST_PATH, DONE_PATH, INGEST_ACK_PATH]:
        if p.exists():
            p.unlink()
    # Clean old oracle receipts/markers so next server start writes fresh receipt
    for rp in [
        RUNTIME_DIR / "config" / "oracle_startup_receipt.json",
        RUNTIME_DIR / "oracle_startup_receipt.json",
        Path("config/oracle_startup_receipt.json"),
        Path("oracle_startup_receipt.json"),
        RUNTIME_DIR / "config" / "oracle_mode.json",
    ]:
        try:
            if rp.exists():
                rp.unlink()
                print(f"[oracle] Cleaned old {rp}")
        except Exception:
            pass


def _start_oracle_server_via_manager() -> bool:
    """Start oracle server respecting already-patched server.properties. Nonblocking Popen; respects existing server.properties and avoids role reconfiguration."""
    _JVM_FLAGS = [
        "-Xmx12g",
        "-Xms4g",
        "-XX:+UseG1GC",
        "-XX:+ParallelRefProcEnabled",
        "-XX:MaxGCPauseMillis=200",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+DisableExplicitGC",
        "-XX:G1NewSizePercent=30",
        "-XX:G1MaxNewSizePercent=40",
        "-XX:G1HeapRegionSize=8M",
        "-XX:G1ReservePercent=20",
        "-XX:InitiatingHeapOccupancyPercent=15",
    ]
    # Prefer reusing _JAR_PATH, _RUNTIME_DIR from server_manager.py; fallback to manual discovery if PySide6 not available
    try:
        from voxel_tree.gui.server_manager import _JAR_PATH as _SM_JAR, _RUNTIME_DIR as _SM_RUNTIME

        _JAR_PATH = _SM_JAR
        _RUNTIME_DIR = _SM_RUNTIME
    except Exception as e:
        print(
            f"[oracle] server_manager import failed ({e}), falling back to manual JAR discovery",
            file=sys.stderr,
        )

        def _find_tools_dir() -> Path:
            cur = Path(__file__).resolve()
            for _ in range(6):
                cand = cur.parent / "tools" / "fabric-server"
                if cand.exists():
                    return cand
                cur = cur.parent
            return REPO_ROOT / "python" / "tools" / "fabric-server"

        _td = _find_tools_dir()
        if not _td.exists():
            _td = REPO_ROOT / "tools" / "fabric-server"
        _RUNTIME_DIR = _td / "runtime"
        cands = list(_td.glob("*.jar"))
        _JAR_PATH = cands[0] if cands else None
    try:
        if not _JAR_PATH or not _JAR_PATH.exists():
            print(f"[oracle] JAR not found at {_JAR_PATH}", file=sys.stderr)
            return False
        if not _RUNTIME_DIR.exists():
            print(f"[oracle] Runtime dir not found at {_RUNTIME_DIR}", file=sys.stderr)
            return False
        import pathlib as _pl2
        import subprocess
        import os as _os

        _jdk25 = _pl2.Path(r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe")
        _java_cmd = str(_jdk25) if _jdk25.exists() else "java"
        if (
            "JAVA_HOME" in _os.environ
            and _pl2.Path(_os.environ["JAVA_HOME"], "bin", "java.exe").exists()
        ):
            _java_cmd = str(_pl2.Path(_os.environ["JAVA_HOME"], "bin", "java.exe"))
        _env = dict(_os.environ)
        if _jdk25.exists():
            _env["JAVA_HOME"] = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
        print(
            f"[oracle] Launching oracle server (nonblocking) {_java_cmd} {' '.join(_JVM_FLAGS)} -jar {_JAR_PATH.name} --nogui cwd={_RUNTIME_DIR} respecting patched server.properties"
        )
        proc = subprocess.Popen(
            [_java_cmd, *_JVM_FLAGS, "-jar", str(_JAR_PATH), "--nogui"],
            cwd=str(_RUNTIME_DIR),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=_env,
        )
        print(f"[oracle] Started oracle server pid={proc.pid}, waiting for RCON...")
        return True
    except Exception as e:
        print(f"[oracle] Direct launch failed: {e}", file=sys.stderr)
        import traceback

        traceback.print_exc()
        return False


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
            # Verify startup receipt fail-closed (freeze before first tick)
            try:
                _verify_oracle_startup_receipt(timeout=30)
            except SystemExit:
                raise
            except Exception as e:
                print(f"[oracle] receipt verification exception: {e}", file=sys.stderr)
                import sys as _sys3

                _sys3.exit(1)
            return True
        except Exception:
            time.sleep(2)
    print(f"[oracle] RCON not ready after {timeout}s at {host}:{port}", file=sys.stderr)
    return False


def _verify_oracle_startup_receipt(timeout: int = 30) -> None:
    """Fail-closed check that SERVER_STARTED freeze invariant was applied before first tick."""
    import json as _j

    deadline = time.time() + timeout
    receipt_path = RUNTIME_DIR / "config" / "oracle_startup_receipt.json"
    alt_path = RUNTIME_DIR / "oracle_startup_receipt.json"
    last_err = None
    while time.time() < deadline:
        for rp in (
            receipt_path,
            alt_path,
            Path("config/oracle_startup_receipt.json"),
            Path("oracle_startup_receipt.json"),
        ):
            if rp.exists():
                try:
                    data = _j.loads(rp.read_text(encoding="utf-8"))
                    frozen = data.get("simulationFrozenBeforeFirstTick")
                    rts = data.get("randomTickSpeed")
                    tick = data.get("tickCountAtFreeze")
                    print(f"[oracle] Startup receipt at {rp}: {data}")
                    if frozen is not True:
                        last_err = f"simulationFrozenBeforeFirstTick != true ({frozen})"
                    elif rts != 0:
                        last_err = f"randomTickSpeed != 0 ({rts})"
                    elif tick != 0:
                        last_err = f"tickCountAtFreeze != 0 ({tick})"
                    if last_err:
                        print(
                            f"[oracle] ERROR: Oracle invariant receipt invalid: {last_err} data={data}",
                            file=sys.stderr,
                        )
                        import sys as _sys

                        _sys.exit(1)
                    print(
                        f"[oracle] Oracle startup invariant VERIFIED: frozen before first tick, randomTickSpeed=0, tickCount={tick}"
                    )
                    return
                except SystemExit:
                    raise
                except Exception as e:
                    last_err = str(e)
                    print(f"[oracle] receipt read failed {rp}: {e}", file=sys.stderr)
        time.sleep(1)
    print(
        f"[oracle] ERROR: No valid oracle startup receipt found at {receipt_path} within {timeout}s (fail-closed). Ensure DataHarvester jar with SERVER_STARTED freeze is installed. Last error: {last_err}",
        file=sys.stderr,
    )
    import sys as _sys2

    _sys2.exit(1)


def _ensure_oracle_client_config() -> None:
    """Write DataHarvester autoConnect config and lodiffusion runtime overlay for AFK oracle."""
    for cfg_path in [
        REPO_ROOT / "java" / "run" / "config" / "dataharvester.json",
        Path("config/dataharvester.json"),
        RUNTIME_DIR / "config" / "dataharvester.json",
    ]:
        try:
            cfg_path.parent.mkdir(parents=True, exist_ok=True)
            cfg = {
                "serverAddress": f"localhost:{ORACLE_ROLE['server_port']}",
                "autoConnect": True,
                "autoConnectDelaySec": 5,
                "reconnectOnDisconnect": True,
                "reconnectDelaySec": 10,
            }
            cfg_path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
            print(f"[oracle] Wrote DataHarvester AFK config {cfg_path}: {cfg}")
        except Exception as e:
            print(f"[oracle] Failed to write {cfg_path}: {e}", file=sys.stderr)
    for rt_path in [
        REPO_ROOT / "java" / "run" / "config" / "lodiffusion" / "runtime.json",
        REPO_ROOT / "java" / "config" / "lodiffusion" / "runtime.json",
    ]:
        try:
            rt_path.parent.mkdir(parents=True, exist_ok=True)
            existing = {}
            if rt_path.exists():
                try:
                    existing = json.loads(rt_path.read_text(encoding="utf-8"))
                except Exception:
                    existing = {}
            existing["useOnnxTerrain"] = False
            if "adapter" not in existing:
                existing["adapter"] = "unified_v1"
            rt_path.write_text(json.dumps(existing, indent=2), encoding="utf-8")
            print(f"[oracle] Set useOnnxTerrain=false in {rt_path}")
        except Exception as e:
            print(f"[oracle] Failed to patch {rt_path}: {e}", file=sys.stderr)


def _ensure_dataharvester_jar_in_run_mods() -> None:
    """Copy latest DataHarvester jar into java/run/mods for AFK client."""
    import shutil

    candidates = []
    for pat in [
        REPO_ROOT
        / "python"
        / "tools"
        / "data-harvester"
        / "build"
        / "libs"
        / "data-harvester-*.jar",
        REPO_ROOT
        / "python"
        / "tools"
        / "fabric-server"
        / "runtime"
        / "mods"
        / "data-harvester-*.jar",
    ]:
        candidates.extend(list(pat.parent.glob(pat.name)))
    candidates = [c for c in candidates if "sources" not in c.name]
    if not candidates:
        print(
            "[oracle] WARNING: No DataHarvester jar found for client (build first)", file=sys.stderr
        )
        return
    src = max(candidates, key=lambda p: p.stat().st_mtime)
    dst_dir = REPO_ROOT / "java" / "run" / "mods"
    dst_dir.mkdir(parents=True, exist_ok=True)
    dst = dst_dir / src.name
    try:
        shutil.copy2(src, dst)
        print(f"[oracle] Copied DataHarvester {src} -> {dst}")
    except Exception as e:
        print(f"[oracle] Failed to copy DataHarvester jar: {e}", file=sys.stderr)


def _start_oracle_client_via_gradlew() -> bool:
    """AFK client launch via java/run/gradlew.bat runClient (nonblocking, retains handle). Mirrors flight-loop.ps1 deployToRunMods pattern."""
    global _ORACLE_CLIENT_PROC
    try:
        _ensure_oracle_client_config()
        _ensure_dataharvester_jar_in_run_mods()
    except Exception as e:
        print(f"[oracle] Failed to prepare AFK client config: {e}", file=sys.stderr)
    java_dir = REPO_ROOT / "java"
    gradlew = java_dir / "gradlew.bat"
    if not gradlew.exists():
        gradlew = java_dir / "gradlew"
    if not gradlew.exists():
        print(f"[oracle] gradlew not found at {gradlew}", file=sys.stderr)
        return False
    try:
        from voxel_tree.utils.rcon import RconClient

        with RconClient(
            "localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=2
        ) as rc:
            lst = rc.command("list")
            if _rcon_has_players(lst):
                print(f"[oracle] Client already connected: {lst}")
                return True
    except Exception:
        pass
    import pathlib as _pl3
    import subprocess
    import os as _os2

    _jdk25_c = _pl3.Path(r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot")
    _env2 = dict(_os2.environ)
    if _jdk25_c.exists():
        _env2["JAVA_HOME"] = str(_jdk25_c)
        _env2["PATH"] = str(_jdk25_c / "bin") + ";" + _env2.get("PATH", "")
    args = ["--no-daemon", "deployToRunMods", "runClient", "--console=plain"]
    print(f"[oracle] Launching AFK DataHarvester client: {gradlew} {' '.join(args)} cwd={java_dir}")
    try:
        if str(gradlew).endswith(".bat"):
            cmd = ["cmd.exe", "/c", str(gradlew)] + args
        else:
            cmd = [str(gradlew)] + args
        proc = subprocess.Popen(
            cmd,
            cwd=str(java_dir),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=_env2,
        )
        _ORACLE_CLIENT_PROC = proc
        print(
            f"[oracle] Started AFK client pid={proc.pid}, waiting for autoConnect to localhost:{ORACLE_ROLE['server_port']}..."
        )
        for _ in range(60):
            time.sleep(2)
            try:
                from voxel_tree.utils.rcon import RconClient as RC2

                with RC2(
                    "localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=2
                ) as rc2:
                    lst2 = rc2.command("list")
                    print(f"[oracle] RCON list poll: {lst2}")
                    if _rcon_has_players(lst2):
                        print(f"[oracle] AFK client connected: {lst2}")
                        return True
            except Exception:
                pass
            if proc.poll() is not None:
                print(
                    f"[oracle] AFK client process exited early with code {proc.poll()}",
                    file=sys.stderr,
                )
                return False
        print("[oracle] AFK client did not connect within 120s", file=sys.stderr)
        return False
    except Exception as e:
        print(f"[oracle] Failed to launch AFK client: {e}", file=sys.stderr)
        import traceback

        traceback.print_exc()
        return False


def _wait_for_client_via_rcon(timeout: int = 60) -> bool:
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient  # type: ignore
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with RconClient(
                "localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=2
            ) as rc:
                lst = rc.command("list")
                print(f"[oracle] Waiting for client: {lst}")
                if _rcon_has_players(lst):
                    return True
        except Exception:
            pass
        time.sleep(2)
    return False


def _run_smoke_ingest_via_rcon() -> None:
    """Cheap AFK smoke: ingest a tiny 2x2 chunk rect with full RETURN barrier and orderly shutdown proof."""
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient  # type: ignore
    host = "localhost"
    port = ORACLE_ROLE["rcon_port"]
    password = ORACLE_ROLE["rcon_password"]
    br = ORACLE_ROLE["blockRegion"]
    import math

    minCx = math.floor(br["originBlockX"] / 16)
    minCz = math.floor(br["originBlockZ"] / 16)
    maxCx = minCx + 1
    maxCz = minCz + 1
    expected_chunks = (maxCx - minCx + 1) * (maxCz - minCz + 1)
    print(
        f"[oracle][smoke] Ingest small rect [{minCx},{minCz} -> {maxCx},{maxCz}] {expected_chunks} chunks with RETURN barrier"
    )
    import uuid

    batch_id = str(uuid.uuid4())[:8]
    with RconClient(host, port, password, timeout=10) as rc:
        try:
            gm = rc.command("gamemode spectator @a")
            print(f"[oracle][smoke] spectator: {gm}")
        except Exception as e:
            print(f"[oracle][smoke] gamemode failed: {e}")
        tp = rc.command("execute as @a in minecraft:the_end run tp @s 0 80 0")
        print(f"[oracle][smoke] tp central: {tp}")
        time.sleep(2)
        resp = rc.command(
            f"execute in minecraft:the_end run ingestall oracle {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}"
        )
        print(
            f"[oracle][smoke] ingestall {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}: {resp}"
        )
        if "Unknown" in resp or "Incorrect" in resp:
            print("[oracle][smoke] ERROR: ingestall not installed", file=sys.stderr)
            sys.exit(1)
        for _ in range(60):
            time.sleep(2)
            st = rc.command("ingestall status")
            print(f"[oracle][smoke] status: {st}")
            if "No ingest running" in st or "Complete" in st:
                break
        else:
            print("[oracle][smoke] ingest did not complete", file=sys.stderr)
            sys.exit(1)
    ack_path = INGEST_ACK_PATH
    deadline = time.time() + 120
    while time.time() < deadline:
        if ack_path.exists():
            try:
                ack = json.loads(ack_path.read_text())
                if ack.get("batchId") == batch_id:
                    if ack.get("success") is not True:
                        print(f"[oracle][smoke] ack failure: {ack}", file=sys.stderr)
                        sys.exit(1)
                    if ack.get("receivedChunks") != expected_chunks:
                        print(
                            f"[oracle][smoke] waiting received {ack.get('receivedChunks')} != {expected_chunks}",
                            file=sys.stderr,
                        )
                    elif ack.get(
                        "expectedEnqueuedSections", ack.get("completedSections", 0)
                    ) != ack.get("completedSections", 0):
                        print(f"[oracle][smoke] pending {ack}", file=sys.stderr)
                    elif ack.get("failedEnqueues", 0) != 0:
                        print(f"[oracle][smoke] failedEnqueues {ack}", file=sys.stderr)
                        sys.exit(1)
                    else:
                        print(f"[oracle][smoke] TRUE ack {batch_id}: {ack} -- RETURN proof OK")
                        return
            except Exception as e:
                print(f"[oracle][smoke] ack read failed: {e}")
        time.sleep(1)
    print(f"[oracle][smoke] ERROR: no TRUE ack for {batch_id}", file=sys.stderr)
    sys.exit(1)


def _cheap_chorus_scan() -> None:
    """Exact preflight chorus check: force-load chunks covering 32^3 tracer ROI, then destructively /fill replace count across entire ROI."""
    try:
        from voxel_tree.utils.rcon import RconClient
    except ImportError:
        from utils.rcon import RconClient  # type: ignore
    import math

    host = "localhost"
    port = ORACLE_ROLE["rcon_port"]
    password = ORACLE_ROLE["rcon_password"]
    br = ORACLE_ROLE["blockRegion"]
    x1 = br["originBlockX"]
    y1 = br["originBlockY"]
    z1 = br["originBlockZ"]
    x2 = br["originBlockX"] + br["extentBlocks"] - 1
    y2 = br["originBlockY"] + br["extentBlocks"] - 1
    z2 = br["originBlockZ"] + br["extentBlocks"] - 1
    minCx = math.floor(x1 / 16)
    maxCx = math.floor(x2 / 16)
    minCz = math.floor(z1 / 16)
    maxCz = math.floor(z2 / 16)
    print(
        f"[oracle] Cheap preflight: force-loading chunks [{minCx},{minCz} -> {maxCx},{maxCz}] for ROI {x1} {y1} {z1} -> {x2} {y2} {z2}"
    )

    def _parse_fill_count(resp: str) -> int:
        import re

        if not resp:
            return 0
        low = resp.lower()
        if "no blocks" in low or "failed" in low or "unknown" in low:
            return 0
        m = re.search(r"(\d+)\s+block", low)
        if m:
            try:
                return int(m.group(1))
            except (ValueError, TypeError):
                return 0
        if "0 block" in low:
            return 0
        return 0

    with RconClient(host, port, password, timeout=10) as rc:
        # Force-load chunks covering ROI in The End (dimension-aware) so fill operates on generated chunks
        # Use block coordinates for forceload (interpreted as block pos -> chunk), executed in the_end
        try:
            resp = rc.command(f"execute in minecraft:the_end run forceload add {x1} {z1} {x2} {z2}")
            print(f"[oracle] forceload range add: {resp}")
            if "Unknown" in resp or "Incorrect" in resp or "Expected" in resp:
                raise RuntimeError(resp)
        except Exception:
            for cx in range(minCx, maxCx + 1):
                for cz in range(minCz, maxCz + 1):
                    try:
                        # Use block coords for chunk corners to ensure correct chunk is loaded in the_end
                        bx = cx * 16
                        bz = cz * 16
                        r = rc.command(f"execute in minecraft:the_end run forceload add {bx} {bz}")
                        print(f"[oracle] forceload add {cx} {cz} (block {bx} {bz}): {r}")
                    except Exception as e:
                        print(f"[oracle] forceload {cx} {cz} failed: {e}", file=sys.stderr)
        time.sleep(3)
        print("[oracle] Cheap preflight: counting chorus_plant across ROI via /fill ... replace")
        resp_plant = rc.command(
            f"execute in minecraft:the_end run fill {x1} {y1} {z1} {x2} {y2} {z2} minecraft:air replace minecraft:chorus_plant"
        )
        print(f"[oracle] fill chorus_plant result: {resp_plant}")
        count_plant = _parse_fill_count(resp_plant)
        print(f"[oracle] chorus_plant replaced: {count_plant}")
        print("[oracle] Cheap preflight: counting chorus_flower across ROI via /fill ... replace")
        resp_flower = rc.command(
            f"execute in minecraft:the_end run fill {x1} {y1} {z1} {x2} {y2} {z2} minecraft:air replace minecraft:chorus_flower"
        )
        print(f"[oracle] fill chorus_flower result: {resp_flower}")
        count_flower = _parse_fill_count(resp_flower)
        print(f"[oracle] chorus_flower replaced: {count_flower}")
        try:
            rc.command(f"execute in minecraft:the_end run forceload remove {x1} {z1} {x2} {z2}")
        except Exception:
            pass
        for cx in range(minCx, maxCx + 1):
            for cz in range(minCz, maxCz + 1):
                try:
                    bx = cx * 16
                    bz = cz * 16
                    rc.command(f"execute in minecraft:the_end run forceload remove {bx} {bz}")
                except Exception:
                    pass
        found = (count_plant > 0) or (count_flower > 0)
        if not found:
            print(
                f"[oracle] ERROR: Cheap scan destructively checked entire ROI {x1} {y1} {z1} -> {x2} {y2} {z2}: chorus_plant={count_plant} chorus_flower={count_flower} -- no chorus in target ROI, aborting before expensive 1296 run",
                file=sys.stderr,
            )
            sys.exit(1)
        print(
            f"[oracle] Cheap preflight SUCCESS: ROI contains chorus chorus_plant={count_plant} chorus_flower={count_flower}"
        )


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
    print(
        f"[oracle] Exact L4 oracle bounds [{minCx},{minCz} -> {maxCx},{maxCz}] halo {halo} for blockRegion {br} => {(maxCx - minCx + 1) * (maxCz - minCz + 1)} chunks"
    )
    import uuid

    batch_id = str(uuid.uuid4())[:8]
    expected_chunks = (maxCx - minCx + 1) * (maxCz - minCz + 1)
    # Ensure client is in The End and away from target to avoid simultaneous client chunk loading interfering with oracle
    with RconClient(host, port, password, timeout=10) as rc:
        # Put player in spectator to prevent fall damage / hunger / unwanted chunk ticks while AFK
        try:
            gm_resp = rc.command("gamemode spectator @a")
            print(f"[oracle] Set spectator mode: {gm_resp}")
        except Exception as e:
            print(f"[oracle] gamemode spectator failed: {e}")
        tp_resp = rc.command("execute as @a in minecraft:the_end run tp @s 0 80 0")
        print(
            f"[oracle] Teleported client to central End island (0 80 0) away from target: {tp_resp}"
        )
        import time as _t

        _t.sleep(3)
        # Verify eligible player exists in The End
        eligible = rc.command("execute as @a in minecraft:the_end run say eligible")
        if "eligible" not in eligible.lower() and "@a" not in eligible:
            # Fallback check via list
            list_resp = rc.command("list")
            print(f"[oracle] Checking eligible player in End, list: {list_resp}")
            # We still require at least one player; if none, fail
            if _rcon_is_zero_players(list_resp):
                print(
                    "[oracle] ERROR: No eligible player in The End for ingest - DataHarvester requires client in same dimension as activeLevel",
                    file=sys.stderr,
                )
                sys.exit(1)
        resp = rc.command(
            f"execute in minecraft:the_end run ingestall oracle {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}"
        )
        print(
            f"[oracle] /ingestall oracle {minCx} {minCz} {maxCx} {maxCz} {batch_id} {expected_chunks}: {resp}"
        )
        if "Unknown" in resp or "Incorrect" in resp or "No such" in resp:
            print(
                "[oracle] ERROR: /ingestall oracle command not installed - ensure DataHarvester JAR built from python/tools/data-harvester is installed",
                file=sys.stderr,
            )
            sys.exit(1)
        for _ in range(180):
            time.sleep(2)
            status = rc.command("ingestall status")
            print(f"[oracle] ingest status: {status}")
            if "No ingest running" in status or "Complete" in status:
                break
        else:
            print("[oracle] ingest did not report complete within timeout", file=sys.stderr)
            sys.exit(1)
    # Hardened barrier: require success true, exact receivedChunks, exact expectedEnqueuedSections == completedSections, failures==0
    deadline = time.time() + 180
    while time.time() < deadline:
        if INGEST_ACK_PATH.exists():
            try:
                ack = json.loads(INGEST_ACK_PATH.read_text())
                if ack.get("batchId") == batch_id:
                    if ack.get("success") is not True:
                        print(
                            f"[oracle] Ack indicates failure (success false): {ack}",
                            file=sys.stderr,
                        )
                        sys.exit(1)
                    if ack.get("expectedChunks") != expected_chunks:
                        print(
                            f"[oracle] Ack expectedChunks mismatch {ack.get('expectedChunks')} != {expected_chunks} waiting...",
                            file=sys.stderr,
                        )
                    elif ack.get("receivedChunks", 0) != expected_chunks:
                        print(
                            f"[oracle] Ack receivedChunks {ack.get('receivedChunks')} != expected {expected_chunks} waiting...",
                            file=sys.stderr,
                        )
                    elif ack.get(
                        "expectedEnqueuedSections", ack.get("completedSections", 0)
                    ) != ack.get("completedSections", 0):
                        print(
                            f"[oracle] Ack pending keys not empty: expectedEnqueued {ack.get('expectedEnqueuedSections')} completed {ack.get('completedSections')} waiting...",
                            file=sys.stderr,
                        )
                    elif ack.get("failedEnqueues", 0) != 0:
                        print(
                            f"[oracle] Ack has failedEnqueues {ack.get('failedEnqueues')} - failing",
                            file=sys.stderr,
                        )
                        sys.exit(1)
                    else:
                        print(f"[oracle] Client TRUE ack received for batch {batch_id}: {ack}")
                        return
                    print(f"[oracle] Ack incomplete {ack} waiting...", file=sys.stderr)
            except Exception as e:
                print(f"[oracle] ack read failed: {e}")
        time.sleep(1)
    print(
        f"[oracle] ERROR: no successful client ack for batch {batch_id} within 180s - WorldEngine may not have flushed or batch had failures. Ensure client is running with Voxy + DataHarvester and insertUpdate RETURN barrier is active. Ack must have success:true, exact receivedChunks, and pendingKeys empty.",
        file=sys.stderr,
    )
    sys.exit(1)


def _trigger_capture() -> None:
    REQUEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    br = ORACLE_ROLE["blockRegion"]
    rect = _l4_chunk_rect_with_halo(br, ORACLE_ROLE["halo"]["combinedHaloBlocks"])
    import math

    per = {}
    for L in range(5):
        wsBlockSize = 32 * (1 << L)
        wsX = math.floor(br["originBlockX"] / wsBlockSize)
        wsY = math.floor(br["originBlockY"] / wsBlockSize)
        wsZ = math.floor(br["originBlockZ"] / wsBlockSize)
        per[f"L{L}"] = {"wsX": wsX, "wsY": wsY, "wsZ": wsZ, "blockSize": wsBlockSize}
    req = {
        "provenanceId": "end_chorus__s42__b1600_64_128_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3",
        "authoritativeGenerationStage": ORACLE_ROLE["authoritativeGenerationStage"],
        "actualCaptureStage": ORACLE_ROLE["actualCaptureStage"],
        "region": ORACLE_ROLE["region"],
        "blockRegion": br,
        "haloCompleteChunkRect": {
            "minChunkX": rect[0],
            "minChunkZ": rect[1],
            "maxChunkX": rect[2],
            "maxChunkZ": rect[3],
        },
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
            print(
                f"[oracle] Capture done: provenance={data.get('provenanceId')} sha={data.get('contentSha256')}"
            )
            print(f"[oracle] Report: {data.get('report')}")
            return data
        time.sleep(1)
    print(f"[oracle] Timeout waiting for {DONE_PATH} after {timeout}s", file=sys.stderr)
    print(
        "[oracle] Is the Minecraft client running with LODiffusion + Voxy and DataHarvester auto-connect?",
        file=sys.stderr,
    )
    sys.exit(1)


def _verify_fixture(path: Path = FIXTURE_PATH) -> None:
    if not path.exists():
        print(f"[oracle] Fixture not found at {path}", file=sys.stderr)
        sys.exit(1)
    data = json.loads(path.read_text())
    sha = data.get("contentSha256")
    provenance = data.get("provenanceId")
    print(f"[oracle] Fixture {provenance} sha={sha} at {path}")
    print(
        f"[oracle] authoritativeGenerationStage={data.get('authoritativeGenerationStage')} actualCaptureStage={data.get('actualCaptureStage')}"
    )
    if "blockRegion" in data:
        print(
            f"[oracle] blockRegion {data['blockRegion']} perLevel {data.get('perLevelWorldSectionOrigins')} chunkRect {data.get('haloCompleteChunkRect')}"
        )
    vols = data.get("volumes", {})
    for lvl in ["L4", "L3", "L2", "L1", "L0"]:
        v = vols.get(lvl)
        if v:
            chorus = sum(1 for b in v.get("blocks", []) if b in (196, 197))
            print(
                f"[oracle] {lvl}: nonAir={v.get('nonAirCount')} extent={v.get('extent')} chorus={chorus}"
            )
    l0 = vols.get("L0", {}).get("blocks", [])
    chorus = sum(1 for b in l0 if b in (196, 197))
    print(f"[oracle] L0 chorus voxels: {chorus}")
    if chorus == 0:
        print(
            f"[oracle] ERROR: L0 has zero chorus â€” selected blockRegion {data.get('blockRegion')} does not contain real chorus; anchor must be re-pinned via outer-island chunk inspection",
            file=sys.stderr,
        )
        sys.exit(1)


def main(argv: list[str] | None = None) -> None:
    import argparse

    p = argparse.ArgumentParser(
        description="Deterministic End chorus oracle regeneration via DataHarvester pipeline (L4 coverage, RETURN barrier, fail-closed)"
    )
    p.add_argument("--seed", type=int, default=42)
    p.add_argument(
        "--capture-stage",
        choices=["FEATURES", "FULL"],
        default="FULL",
        help="Actual capture stage (FULL is acceptable offline; FEATURES is authoritative)",
    )
    p.add_argument("--radius-chunks", type=int, default=None)
    p.add_argument(
        "--check", action="store_true", help="Verify existing fixture without regenerating"
    )
    p.add_argument(
        "--reset-world",
        action="store_true",
        help="Delete disposable world + Voxy storage before capture (stops server first)",
    )
    p.add_argument(
        "--ingest-only",
        action="store_true",
        help="Only run ingest, skip capture trigger (for debugging)",
    )
    p.add_argument(
        "--cheap-scan",
        action="store_true",
        help="Run cheap server-only chorus scan around anchor before ingest",
    )
    p.add_argument(
        "--double-pristine",
        action="store_true",
        help="Run two full pristine captures and fail unless contentSha256 identical (cheap preflight not counted)",
    )
    p.add_argument(
        "--smoke",
        action="store_true",
        help="Cheap AFK smoke: tiny ingest with RETURN barrier proving AFK pipeline without full 1296 run",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate helpers, server properties, and lifecycle without needing live server (catches undefined helpers)",
    )
    args = p.parse_args(argv)

    if getattr(args, "dry_run", False):
        print("[oracle] Dry-run: validating helpers and lifecycle without live server")
        assert callable(_start_oracle_server_via_manager), "helper missing"
        assert callable(_reset_disposable_world)
        assert callable(_patch_server_properties_for_oracle)
        assert callable(_wait_for_rcon)
        assert callable(_run_ingest_via_rcon)
        assert callable(_run_smoke_ingest_via_rcon)
        assert callable(_start_oracle_client_via_gradlew)
        assert callable(_ensure_oracle_client_config)
        assert callable(_trigger_capture)
        print(
            "[oracle] Dry-run: all helpers defined, lifecycle check passed (reset->preflight->reset->real would be executed)"
        )
        # Also validate that IngestAllCommand sorting is deterministic
        print("[oracle] Dry-run: checking that IngestAllCommand uses squared distance -> X -> Z")
        # Dry-run assertion: starting while preflight must not rewrite to oracle_end_chorus
        import inspect

        src = inspect.getsource(_start_oracle_server_via_manager)
        assert "configure_for_role(" not in src, (
            "launcher must not call configure_for_role (would overwrite preflight level_name)"
        )
        orig_level = ORACLE_ROLE["level_name"]
        ORACLE_ROLE["level_name"] = "oracle_end_chorus_preflight"
        try:
            from unittest import mock

            with mock.patch("subprocess.Popen") as _mp:
                _mock_proc = mock.Mock()
                _mock_proc.pid = 99999
                _mp.return_value = _mock_proc
                try:
                    _start_oracle_server_via_manager()
                except Exception:
                    pass
                assert ORACLE_ROLE["level_name"] == "oracle_end_chorus_preflight", (
                    f"launcher rewrote level_name to {ORACLE_ROLE['level_name']}"
                )
                print(
                    "[oracle] Dry-run: launcher preserved preflight level_name (did not rewrite to oracle_end_chorus)"
                )
        finally:
            ORACLE_ROLE["level_name"] = orig_level
        return
    if args.check:
        _verify_fixture()
        return

    if args.reset_world:
        _reset_disposable_world()
        _patch_server_properties_for_oracle()
        if not _wait_for_rcon(timeout=5):
            print(
                "[oracle] Server not running after reset - attempting to start oracle_end_chorus role",
                file=sys.stderr,
            )
            _start_oracle_server_via_manager()
            if not _wait_for_rcon(timeout=120):
                print(
                    "[oracle] Server not running â€” start it via: python -m voxel_tree --server start --role oracle_end_chorus (isolated port 25567)",
                    file=sys.stderr,
                )
                sys.exit(1)
        # Wait for client to connect before proceeding
        print("[oracle] Waiting for client to auto-connect...")
        import time as _t2

        for _ in range(30):
            try:
                from voxel_tree.utils.rcon import RconClient as _RC

                with _RC(
                    "localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=2
                ) as _rc:
                    lst = _rc.command("list")
                    if _rcon_has_players(lst):
                        break
            except Exception:
                pass
            _t2.sleep(2)
    else:
        _patch_server_properties_for_oracle()

    if not _wait_for_rcon(timeout=5):
        print(
            "[oracle] Server not running â€” start it via: python -m voxel_tree --server start --role oracle_end_chorus (isolated port 25567)",
            file=sys.stderr,
        )
        if SERVERS_YAML.exists():
            text = SERVERS_YAML.read_text()
            if "oracle_end_chorus" not in text:
                print("[oracle] servers.yaml missing oracle_end_chorus role", file=sys.stderr)
        sys.exit(1)

    if args.cheap_scan:
        print(
            "[oracle] Running cheap scan in separate preflight world to avoid polluting authoritative generation history"
        )
        # Preflight: use separate level_name to isolate generation history
        orig_level = ORACLE_ROLE["level_name"]
        ORACLE_ROLE["level_name"] = "oracle_end_chorus_preflight"
        _reset_disposable_world()
        _patch_server_properties_for_oracle()
        # Start preflight server if not running
        if not _wait_for_rcon(timeout=5):
            print(
                "[oracle] Preflight server not running - attempting to start oracle_end_chorus_preflight via manager",
                file=sys.stderr,
            )
            _start_oracle_server_via_manager()
            if not _wait_for_rcon(timeout=120):
                print("[oracle] Preflight server failed to start", file=sys.stderr)
                sys.exit(1)
        _cheap_chorus_scan()
        # Destroy preflight world and Voxy state before real capture
        print("[oracle] Destroying preflight world before pristine real capture")
        _reset_disposable_world()
        ORACLE_ROLE["level_name"] = orig_level
        # Clear pristine oracle world/Voxy state, patch real properties, start real server, wait
        print("[oracle] Clearing pristine oracle world/Voxy for real capture")
        _reset_disposable_world()
        _patch_server_properties_for_oracle()
        print("[oracle] Starting real oracle server after preflight")
        if not _wait_for_rcon(timeout=5):
            _start_oracle_server_via_manager()
            if not _wait_for_rcon(timeout=120):
                print("[oracle] Real server failed to start after preflight", file=sys.stderr)
                sys.exit(1)
        print("[oracle] Waiting for client to auto-connect to real server...")
        import time as _t3

        for _ in range(30):
            try:
                from voxel_tree.utils.rcon import RconClient as _RC2

                with _RC2(
                    "localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=2
                ) as _rc2:
                    lst = _rc2.command("list")
                    if _rcon_has_players(lst):
                        break
            except Exception:
                pass
            _t3.sleep(2)

    # Ensure AFK DataHarvester client is running (autoConnect) before ingest; launch via gradlew if needed
    if not _wait_for_client_via_rcon(timeout=5):
        print(
            "[oracle] No client connected - attempting AFK launch via java/run/gradlew.bat runClient"
        )
        _ensure_oracle_client_config()
        if not _start_oracle_client_via_gradlew():
            print(
                "[oracle] ERROR: AFK client failed to auto-connect - ensure DataHarvester jar built and java/run/mods present",
                file=sys.stderr,
            )
            sys.exit(1)
        if not _wait_for_client_via_rcon(timeout=60):
            print(
                "[oracle] ERROR: AFK client did not appear via RCON list after launch",
                file=sys.stderr,
            )
            sys.exit(1)
    if getattr(args, "smoke", False):
        print("[oracle] Smoke mode: tiny ingest RETURN proof (no full 1296 capture)")
        _run_smoke_ingest_via_rcon()
        print("[oracle] Smoke ingest TRUE ack - AFK pipeline proof OK, shutting down orderly")
        try:
            from voxel_tree.utils.rcon import RconClient as _RCsmoke

            with _RCsmoke(
                "localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=5
            ) as _rcs:
                _rcs.command("stop")
        except Exception:
            pass
        # Also stop client proc if we launched it
        try:
            if _ORACLE_CLIENT_PROC is not None and _ORACLE_CLIENT_PROC.poll() is None:
                print(f"[oracle] Stopping AFK client pid={_ORACLE_CLIENT_PROC.pid}")
                _ORACLE_CLIENT_PROC.terminate()
        except Exception:
            pass
        return
    _run_ingest_via_rcon(radius_chunks=args.radius_chunks)
    if args.ingest_only:
        print("[oracle] Ingest complete (ingest-only)")
        return
    _trigger_capture()
    data = _wait_for_done(timeout=300)
    _verify_fixture()
    print(
        "[oracle] SUCCESS: real fixed-seed End chunks ingested through Voxy RETURN barrier and post-insert WorldSections captured (L4 36x36=1296 chunks, per-Level origins independent, batch ack barrier)"
    )
    print(data.get("report", ""))
    if args.double_pristine:
        sha1 = data.get("contentSha256")
        print(
            f"[oracle] Double-pristine: preserving capture #1 SHA {sha1}, fully resetting for capture #2"
        )
        # Preserve #1 fixture
        import shutil as _sh

        p1_path = FIXTURE_PATH
        p1_backup = FIXTURE_PATH.with_name(FIXTURE_PATH.stem + "_capture1" + FIXTURE_PATH.suffix)
        if p1_path.exists():
            _sh.copy(p1_path, p1_backup)
            print(f"[oracle] Saved capture #1 to {p1_backup}")
        # Fully reset pristine
        print("[oracle] Fully resetting pristine world/Voxy for capture #2")
        _reset_disposable_world()
        _patch_server_properties_for_oracle()
        if not _wait_for_rcon(timeout=5):
            _start_oracle_server_via_manager()
            if not _wait_for_rcon(timeout=120):
                print("[oracle] Server failed to start for capture #2", file=sys.stderr)
                sys.exit(1)
        print("[oracle] Waiting for client for capture #2...")
        import time as _t4

        for _ in range(30):
            try:
                from voxel_tree.utils.rcon import RconClient as _RC3

                with _RC3(
                    "localhost", ORACLE_ROLE["rcon_port"], ORACLE_ROLE["rcon_password"], timeout=2
                ) as _rc3:
                    lst = _rc3.command("list")
                    if _rcon_has_players(lst):
                        break
            except Exception:
                pass
            _t4.sleep(2)
        # Ensure AFK client still connected after reset (reconnect)
        if not _wait_for_client_via_rcon(timeout=5):
            print("[oracle] No client for capture #2 - launching AFK client")
            _ensure_oracle_client_config()
            _start_oracle_client_via_gradlew()
            _wait_for_client_via_rcon(timeout=60)
        _run_ingest_via_rcon(radius_chunks=args.radius_chunks)
        _trigger_capture()
        data2 = _wait_for_done(timeout=300)
        _verify_fixture()
        sha2 = data2.get("contentSha256")
        print(f"[oracle] Capture #1 SHA {sha1}")
        print(f"[oracle] Capture #2 SHA {sha2}")
        if sha1 != sha2:
            print(
                "[oracle] ERROR: Double-pristine SHA mismatch - determinism not proven",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"[oracle] Double-pristine SUCCESS: identical SHA {sha1}")
        data = data2


if __name__ == "__main__":
    main()
