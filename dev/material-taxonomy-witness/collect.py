import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(
    description="Capture local pinned vanilla sources; does not download."
)
parser.add_argument("--output-root", type=Path, default=Path(__file__).resolve().parent)
parser.add_argument("--minecraft-client", type=Path)
parser.add_argument("--named-jar", type=Path)
args = parser.parse_args()
ROOT = args.output_root
ROOT.mkdir(parents=True, exist_ok=True)
SOURCE_ROOT = ROOT / ".source-cache"
CLIENT = (
    args.minecraft_client
    or Path.home() / ".gradle/caches/fabric-loom/1.21.11/minecraft-client.jar"
)
NAMED = (
    args.named_jar
    or Path.home()
    / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.4-v2/minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.4-v2.jar"
)
COHORT = [
    "air",
    "end_stone",
    "obsidian",
    "bedrock",
    "chorus_plant",
    "chorus_flower",
    "end_gateway",
    "fire",
    "iron_bars",
    "wall_torch",
    "snow",
    "snow_block",
    "ice",
    "packed_ice",
    "blue_ice",
    "oak_leaves",
    "oak_log",
    "short_grass",
]
CLASSES = [
    "client.render.BlockRenderLayers",
    "client.color.block.BlockColors",
    "client.render.block.entity.EndGatewayBlockEntityRenderer",
    "client.render.block.entity.AbstractEndPortalBlockEntityRenderer",
    "block.Blocks",
    "block.ChorusPlantBlock",
    "block.ChorusFlowerBlock",
    "block.LeavesBlock",
    "block.SnowBlock",
    "block.PaneBlock",
    "block.EndGatewayBlock",
    "world.gen.feature.EndGatewayFeature",
    "world.gen.feature.EndPortalFeature",
    "world.gen.feature.EndSpikeFeature",
    "world.gen.feature.EndPlatformFeature",
    "world.gen.feature.ChorusPlantFeature",
    "world.gen.feature.EndIslandFeature",
    "entity.boss.dragon.EnderDragonFight",
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(name, value):
    (ROOT / name).write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


manifest = {
    "schema": "voxygen.material-taxonomy-source-capture/0",
    "minecraft_version": "1.21.11",
    "yarn_mappings": "1.21.11+build.4",
    "artifacts": {},
    "entries": {},
    "missing": [],
    "builtin_texture_references": [],
    "render_witnesses": {},
}
mojang_info = json.loads((CLIENT.parent / "mojang_minecraft_info.json").read_text())
assert mojang_info["id"] == "1.21.11", "wrong Minecraft metadata version"
client_pin = mojang_info["downloads"]["client"]
assert client_pin["sha1"] == "ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd", (
    "wrong Minecraft client identity"
)
client_sha1 = hashlib.sha1(CLIENT.read_bytes()).hexdigest()
assert client_sha1 == client_pin["sha1"], "client does not match Mojang distribution"
assert (
    sha(NAMED.read_bytes())
    == "9bd8f2708a9bf367eb67d67a6bba6baddd02d42d48dbdb4ceb09ffd2bf9339a4"
), "wrong pinned named jar identity"
for key, path in [("mojang_client", CLIENT), ("loom_named_merged", NAMED)]:
    manifest["artifacts"][key] = {
        "local_cache_locator": ".gradle/"
        + str(path).replace("\\", "/").split("/.gradle/", 1)[-1]
        if "/.gradle/" in str(path).replace("\\", "/")
        else path.name,
        "sha256": sha(path.read_bytes()),
        "identity_kind": "original_client_distribution"
        if key == "mojang_client"
        else "transformed_named_jar",
    }
manifest["artifacts"]["mojang_client"].update(
    source_url=client_pin["url"],
    mojang_distribution_sha1=client_pin["sha1"],
    distribution_sha1_verified=True,
)
with zipfile.ZipFile(CLIENT) as client, zipfile.ZipFile(NAMED) as named:

    def preserve(jar, name, artifact):
        key = artifact + ":" + name
        if key in manifest["entries"]:
            return
        if name not in jar.namelist():
            manifest["missing"].append(key)
            return
        payload = jar.read(name)
        target = (SOURCE_ROOT / "primary" / artifact / name).resolve()
        if not target.is_relative_to(SOURCE_ROOT.resolve()):
            raise ValueError("primary output escapes source cache")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
        manifest["entries"][key] = {
            "sha256": sha(payload),
            "size": len(payload),
            "local_reference": str(target.relative_to(SOURCE_ROOT.resolve())).replace(
                "\\", "/"
            ),
        }
        return payload

    def model(name):
        namespace, path = name.split(":") if ":" in name else ("minecraft", name)
        entry = "assets/" + namespace + "/models/" + path + ".json"
        key = "mojang_client:" + entry
        if key in manifest["entries"]:
            return
        raw = preserve(client, entry, "mojang_client")
        if raw is None:
            return
        data = json.loads(raw)
        if "parent" in data and data["parent"] != "builtin/generated":
            model(data["parent"])
        for texture in data.get("textures", {}).values():
            if texture.startswith("#"):
                continue
            ns, tex = texture.split(":") if ":" in texture else ("minecraft", texture)
            if ns == "minecraft" and tex == "missingno":
                manifest["builtin_texture_references"].append(
                    {
                        "texture": texture,
                        "model": name,
                        "reason": "Air particle placeholder is generated by the client, not a texture PNG entry.",
                    }
                )
                continue
            tex_entry = "assets/" + ns + "/textures/" + tex + ".png"
            preserve(client, tex_entry, "mojang_client")
            if tex_entry + ".mcmeta" in client.namelist():
                preserve(client, tex_entry + ".mcmeta", "mojang_client")

    def refs(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "model":
                    yield child
                else:
                    yield from refs(child)
        elif isinstance(value, list):
            for child in value:
                yield from refs(child)

    for block in COHORT:
        raw = preserve(
            client, "assets/minecraft/blockstates/" + block + ".json", "mojang_client"
        )
        if raw is None:
            continue
        data = json.loads(raw)
        models = sorted(set(refs(data)))
        for item in models:
            model(item)
        manifest["render_witnesses"]["minecraft:" + block] = {
            "blockstate_asset": "mojang_client:assets/minecraft/blockstates/"
            + block
            + ".json",
            "variant_selectors": sorted(data.get("variants", {})),
            "multipart_parts": len(data.get("multipart", [])),
            "models": models,
            "state_population_complete": False,
        }
    for resource in [
        "data/minecraft/worldgen/noise_settings/end.json",
        "data/minecraft/dimension_type/the_end.json",
    ]:
        preserve(named, resource, "loom_named_merged")
    for biome in [
        "the_end",
        "end_highlands",
        "end_midlands",
        "end_barrens",
        "small_end_islands",
    ]:
        resource = "data/minecraft/worldgen/biome/" + biome + ".json"
        raw_biome = preserve(named, resource, "loom_named_merged")
        assert raw_biome is not None, "missing required biome"
        data = json.loads(raw_biome)
        for stage in data["features"]:
            for feature in stage:
                placed_path = (
                    "data/minecraft/worldgen/placed_feature/"
                    + feature.split(":")[1]
                    + ".json"
                )
                raw_placed = preserve(named, placed_path, "loom_named_merged")
                assert raw_placed is not None, "missing required placed feature"
                placed = json.loads(raw_placed)
                if isinstance(placed["feature"], str):
                    preserve(
                        named,
                        "data/minecraft/worldgen/configured_feature/"
                        + placed["feature"].split(":")[1]
                        + ".json",
                        "loom_named_merged",
                    )
    for short_class in CLASSES:
        name = "net.minecraft." + short_class
        entry = name.replace(".", "/") + ".class"
        preserve(named, entry, "loom_named_merged")
        class_file = SOURCE_ROOT / "primary" / "loom_named_merged" / entry
        result = subprocess.run(
            ["javap", "-p", "-c", str(class_file)], capture_output=True, check=True
        )
        target = (SOURCE_ROOT / "javap" / (name + ".txt")).resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(result.stdout)
        manifest["entries"]["javap:" + name] = {
            "sha256": sha(result.stdout),
            "size": len(result.stdout),
            "local_reference": str(target.relative_to(SOURCE_ROOT.resolve())).replace(
                "\\", "/"
            ),
            "derived_from": "loom_named_merged:" + entry,
            "command": ["javap", "-p", "-c", "<extracted pinned class file>"],
        }
write_json("source-capture.json", manifest)
print(
    json.dumps(
        {
            "entries": len(manifest["entries"]),
            "witnesses": len(manifest["render_witnesses"]),
            "missing": manifest["missing"],
        }
    )
)
