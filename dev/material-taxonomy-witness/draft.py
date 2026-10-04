import argparse
import hashlib
import json
from pathlib import Path
from typing import cast

from PIL import Image

parser = argparse.ArgumentParser(
    description="Generate an unadjudicated source/render witness."
)
parser.add_argument("--output-root", type=Path, default=Path(__file__).resolve().parent)
parser.add_argument("--source-root", type=Path)
parser.add_argument(
    "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
)
args = parser.parse_args()
ROOT = args.output_root
SOURCE_ROOT = args.source_root or ROOT / ".source-cache"
capture = json.loads((ROOT / "source-capture.json").read_text())


def encode(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def save(name, value):
    (ROOT / name).write_bytes(encode(value))


def asset(relative):
    return "mojang_client:assets/minecraft/" + relative


def bytecode(name):
    return "javap:net.minecraft." + name


models = {}
textures = {}
for key, evidence in capture["entries"].items():
    primary_path = SOURCE_ROOT / evidence["local_reference"]
    if "/models/" in key:
        data = json.loads(primary_path.read_text())
        elements = data.get("elements", [])
        models[key] = {
            "parent": data.get("parent"),
            "local_element_bounds": [
                {"from": e["from"], "to": e["to"]} for e in elements
            ],
            "local_tint_indices": sorted(
                {
                    f["tintindex"]
                    for e in elements
                    for f in e.get("faces", {}).values()
                    if "tintindex" in f
                }
            ),
            "textures": data.get("textures", {}),
            "source_sha256": evidence["sha256"],
            "inherited_elements_not_flattened": True,
        }
    if key.endswith(".png"):
        with Image.open(primary_path) as im:
            pixels = cast(
                list[tuple[int, int, int, int]],
                list(im.convert("RGBA").get_flattened_data()),
            )
            textures[key] = {
                "size": list(im.size),
                "alpha_min": min(p[3] for p in pixels),
                "alpha_max": max(p[3] for p in pixels),
                "mean_rgba_encoded_8bit": [
                    round(sum(p[i] for p in pixels) / len(pixels), 4) for i in range(4)
                ],
                "source_sha256": evidence["sha256"],
                "is_perceptual_equivalence_measure": False,
                "has_animation_metadata": key + ".mcmeta" in capture["entries"],
            }
save("model-evidence.json", models)
save("texture-evidence.json", textures)

families = []
memberships = []


def path(coarse, surface, fine, identity, selector, reasons, references, profile_scope):
    for depth, family, parent in [
        ("coarse-family", "coarse." + coarse, None),
        ("surface-family", "surface." + surface, "coarse." + coarse),
        ("fine-family", "fine." + fine, "surface." + surface),
    ]:
        prior = next((f for f in families if f["id"] == family), None)
        value = {
            "id": family,
            "depth": depth,
            "parent": parent,
            "status": "candidate-unadjudicated",
        }
        if prior:
            assert prior == value
        else:
            families.append(value)
    memberships.append(
        {
            "canonical_block_name": "minecraft:" + identity,
            "state_selector": selector,
            "canonical_state_population_complete": False,
            "candidate_membership": {
                "coarse-family": "coarse." + coarse,
                "surface-family": "surface." + surface,
                "fine-family": "fine." + fine,
            },
            "rule_identity": "witness-" + fine,
            "derivation": "source-supported candidate; not an accepted perceptual equivalence class",
            "rationale": reasons,
            "source_references": references,
            "profile_reachability": profile_scope,
        }
    )


render = bytecode("client.render.BlockRenderLayers")
colors = bytecode("client.color.block.BlockColors")
model = lambda name: asset("models/block/" + name + ".json")
state = lambda name: asset("blockstates/" + name + ".json")
texture = lambda name: asset("textures/block/" + name + ".png")
gen = lambda name: bytecode("world.gen.feature." + name)

path(
    "absence",
    "absence",
    "air",
    "air",
    {},
    [
        "No model elements; explicit absence family is not an occupied material assertion."
    ],
    [model("air"), "loom_named_merged:data/minecraft/worldgen/noise_settings/end.json"],
    "active-End base-noise default fluid witness; complete scope not frozen",
)
path(
    "end_mineral",
    "end_stone",
    "end_stone",
    "end_stone",
    {},
    [
        "Required End-defining mineral distinction; cube geometry and pale yellow patterned texture inspected."
    ],
    [
        model("end_stone"),
        texture("end_stone"),
        gen("EndIslandFeature"),
        "loom_named_merged:data/minecraft/worldgen/noise_settings/end.json",
    ],
    "active-End base-noise and small-island write witness",
)
path(
    "rock",
    "dark_violet_rock",
    "dark_violet_rock",
    "obsidian",
    {},
    [
        "Cube model and dark violet texture inspected; no luminosity or translucency grouping inferred from names."
    ],
    [
        model("obsidian"),
        texture("obsidian"),
        gen("EndSpikeFeature"),
        gen("EndPlatformFeature"),
    ],
    "active-End spike and platform write witness",
)
path(
    "rock",
    "gray_mottled_rock",
    "gray_mottled_rock",
    "bedrock",
    {},
    [
        "Cube and mirrored model variants; gray high-contrast texture. Merging with other rocks remains open."
    ],
    [
        state("bedrock"),
        texture("bedrock"),
        gen("EndSpikeFeature"),
        gen("EndGatewayFeature"),
    ],
    "active-End spike/gateway write witness",
)
path(
    "end_vegetation",
    "purple_branched_vegetation",
    "purple_branched_vegetation",
    "chorus_plant",
    {"connections": "not enumerated; multipart booleans"},
    [
        "Partial multipart branch geometry; purple texture; CUTOUT layer, so not a solid-cube material shortcut."
    ],
    [
        state("chorus_plant"),
        model("chorus_plant_side"),
        texture("chorus_plant"),
        render,
        bytecode("block.ChorusFlowerBlock"),
    ],
    "active-End chorus generation writes connection-dependent plant states",
)
path(
    "end_vegetation",
    "purple_branched_vegetation",
    "purple_terminal_flower",
    "chorus_flower",
    {"age": 5},
    [
        "Age 5 selects dead flower texture over sculpted flower model. Generation explicitly writes AGE=5."
    ],
    [
        state("chorus_flower"),
        model("chorus_flower_dead"),
        texture("chorus_flower_dead"),
        render,
        bytecode("block.ChorusFlowerBlock"),
    ],
    "active-End chorus generation AGE=5 write witness",
)
path(
    "end_vegetation",
    "purple_branched_vegetation",
    "pale_live_flower",
    "chorus_flower",
    {"age": [0, 1, 2, 3, 4]},
    [
        "Same flower geometry with a different living texture; cannot infer pre-tick profile membership from registry existence."
    ],
    [state("chorus_flower"), model("chorus_flower"), texture("chorus_flower"), render],
    "state diagnostic control; frozen pre-tick reachability unproven",
)
path(
    "portal_effect",
    "gateway_effect",
    "gateway_effect",
    "end_gateway",
    {},
    [
        "Model has only an obsidian particle texture and no elements; actual portal and temporary beam come from a block entity renderer. Particle texture does not establish rock membership."
    ],
    [
        model("end_gateway"),
        bytecode("client.render.block.entity.EndGatewayBlockEntityRenderer"),
        bytecode("client.render.block.entity.AbstractEndPortalBlockEntityRenderer"),
        gen("EndGatewayFeature"),
    ],
    "active-End gateway feature write witness; render context not fully bound",
)
path(
    "emissive_effect",
    "flame_effect",
    "animated_flame",
    "fire",
    {"directional_properties": "not enumerated", "age": "not enumerated"},
    ["CUTOUT multipart flame geometry and animated fire textures."],
    [
        state("fire"),
        texture("fire_0"),
        asset("textures/block/fire_0.png.mcmeta"),
        render,
        gen("EndSpikeFeature"),
    ],
    "active-End spike fire callback witness; exact state closure unproven",
)
path(
    "metal",
    "thin_metal",
    "thin_metal",
    "iron_bars",
    {"connections": "not enumerated", "waterlogged": "not enumerated"},
    [
        "Connection-sensitive thin geometry, gray texture and CUTOUT layer. Waterlogged behavior is separate from this witness classification."
    ],
    [
        state("iron_bars"),
        model("iron_bars_side"),
        texture("iron_bars"),
        render,
        gen("EndSpikeFeature"),
        bytecode("block.PaneBlock"),
    ],
    "active-End spike connection-state write witness; waterlogged feasibility unproven",
)
path(
    "emissive_effect",
    "torch_fixture",
    "torch_fixture",
    "wall_torch",
    {"facing": ["east", "north", "south", "west"]},
    [
        "Wall-oriented thin torch model and CUTOUT layer. Its small fixture is distinct from animated flame geometry."
    ],
    [
        state("wall_torch"),
        model("wall_torch"),
        texture("torch"),
        render,
        gen("EndPortalFeature"),
    ],
    "End podium source write witness; active first-player event boundary unresolved",
)
path(
    "snow",
    "snow",
    "snow",
    "snow",
    {"layers": [1, 2, 3, 4, 5, 6, 7, 8]},
    [
        "White snow texture; variants select model heights 2 through 16. Geometry remains observable even if material family is shared."
    ],
    [
        state("snow"),
        texture("snow"),
        model("snow_height2"),
        model("snow_block"),
        bytecode("block.SnowBlock"),
    ],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)
path(
    "snow",
    "snow",
    "snow",
    "snow_block",
    {},
    [
        "Shares snow texture; full cube geometry. Required snow-vs-ice coarse separation preserved."
    ],
    [model("snow_block"), texture("snow")],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)
path(
    "ice",
    "translucent_ice",
    "translucent_ice",
    "ice",
    {},
    [
        "Blue-white texture and explicit TRANSLUCENT layer; visually inspected against snow."
    ],
    [model("ice"), texture("ice"), render],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)
path(
    "ice",
    "solid_ice",
    "pale_solid_ice",
    "packed_ice",
    {},
    [
        "Ice-colored cube texture; no explicit layer override in pinned BlockRenderLayers, which defaults to SOLID."
    ],
    [model("packed_ice"), texture("packed_ice"), render],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)
path(
    "ice",
    "solid_ice",
    "blue_solid_ice",
    "blue_ice",
    {},
    [
        "Blue cube texture; no explicit layer override in pinned BlockRenderLayers, which defaults to SOLID. Hue threshold for fine-family separation remains unmeasured."
    ],
    [model("blue_ice"), texture("blue_ice"), render],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)
path(
    "biome_vegetation",
    "canopy",
    "biome_tinted_canopy",
    "oak_leaves",
    {
        "distance": "not enumerated",
        "persistent": "not enumerated",
        "waterlogged": "not enumerated",
    },
    [
        "Tinted cube faces with alpha-cutout leaf texture; foliage color provider uses biome context. SOLID/CUTOUT selection depends on leaf render configuration. Ground exclusion does not remove material-family membership."
    ],
    [
        model("oak_leaves"),
        model("leaves"),
        texture("oak_leaves"),
        render,
        colors,
        bytecode("block.LeavesBlock"),
    ],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)
path(
    "wood",
    "bark_wood",
    "bark_wood",
    "oak_log",
    {"axis": ["x", "y", "z"]},
    [
        "Separate bark/end-grain texture slots and axis-rotated cube models. No perceptual wood-species equivalence claim made."
    ],
    [state("oak_log"), model("oak_log"), texture("oak_log"), texture("oak_log_top")],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)
path(
    "biome_vegetation",
    "low_vegetation",
    "biome_tinted_low_vegetation",
    "short_grass",
    {},
    [
        "Cross geometry with tintindex; CUTOUT layer and grass biome color provider. Shares vegetation ancestry with canopy without equating geometry."
    ],
    [
        model("short_grass"),
        model("tinted_cross"),
        texture("short_grass"),
        render,
        colors,
    ],
    "Overworld control; legacy #26 membership exists, active frozen closure unproven",
)

ambiguities = [
    {
        "id": "population-and-state-closure",
        "kind": "missing-input",
        "identities": ["all"],
        "affected_depths": ["all"],
        "competing_outcomes": [
            "active-profile feasible state",
            "diagnostic-only state",
            "profile-inactive state",
        ],
        "evidence": [
            "Active 1.21.11 resources and callbacks are captured.",
            "Legacy accepted #26 populations describe 26.1-snapshot-11 block types; Nether list is missing.",
            "Chorus generation writes AGE=5 although assets also define ages 0-4.",
        ],
        "missing": "Authoritative active-version profile boundary and complete canonical state/source closure.",
        "consequence": "No acceptance-bound totality or retained/exact representative decisions can be made.",
    },
    {
        "id": "rock-merging-at-depth",
        "kind": "missing-perceptual-evidence",
        "identities": ["minecraft:bedrock", "minecraft:obsidian"],
        "affected_depths": ["coarse-family", "surface-family"],
        "competing_outcomes": [
            "shared coarse rock with distinct surface families",
            "separate coarse perceptual families",
        ],
        "evidence": [texture("obsidian"), texture("bedrock")],
        "missing": "Level-appropriate rendered comparison with fixed views; texture alone is insufficient.",
        "consequence": "Candidate ancestry may merge strongly different tones at coarse depth.",
    },
    {
        "id": "ice-transmission-depth",
        "kind": "missing-perceptual-evidence",
        "identities": ["minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice"],
        "affected_depths": ["coarse-family", "fine-family"],
        "competing_outcomes": [
            "shared coarse ice with transmission distinction at surface",
            "coarse transmission split",
            "merge solid-ice hue variants at fine depth",
        ],
        "evidence": [
            render,
            texture("ice"),
            texture("packed_ice"),
            texture("blue_ice"),
        ],
        "missing": "Bound render settings and Level-appropriate contrast/transmission witness.",
        "consequence": "Sharing ice family may hide visible background transmission; hue alone does not force a fine split.",
    },
    {
        "id": "chorus-live-dead-family",
        "kind": "missing-perceptual-evidence",
        "identities": ["minecraft:chorus_flower"],
        "affected_depths": ["surface-family", "fine-family"],
        "competing_outcomes": [
            "separate living/dead fine material family",
            "one flower family with appearance diagnostics",
        ],
        "evidence": [
            state("chorus_flower"),
            texture("chorus_flower"),
            texture("chorus_flower_dead"),
            bytecode("block.ChorusFlowerBlock"),
        ],
        "missing": "Profile-feasible state closure and Level-appropriate appearance comparison.",
        "consequence": "Block-name-only family lookup cannot express the visible age=5 texture difference.",
    },
    {
        "id": "special-render-context",
        "kind": "missing-render-input",
        "identities": [
            "minecraft:end_gateway",
            "minecraft:oak_leaves",
            "minecraft:short_grass",
        ],
        "affected_depths": ["all"],
        "competing_outcomes": [
            "material class stable with context-dependent visual diagnostics",
            "context-specific refinement classes",
        ],
        "evidence": [
            bytecode("client.render.block.entity.EndGatewayBlockEntityRenderer"),
            render,
            colors,
        ],
        "missing": "Authoritative treatment of block-entity visuals, biome tint and leaf configuration in pinned reference appearance.",
        "consequence": "Model geometry alone mislabels the gateway as empty and leaf texture alone cannot produce final color.",
    },
]

repo = args.repo_root
code_witnesses = {}
for relative, line_range in [
    (
        "mod/src/main/java/com/rhythmatician/voxygen/backend/voxy/CanonicalVoxyMaps.java",
        [38, 49],
    ),
    (
        "mod/src/main/java/com/rhythmatician/voxygen/backend/voxy/RealVoxyVolumeWriter.java",
        [369, 376],
    ),
    (
        "mod/src/main/java/com/rhythmatician/voxygen/generation/session/GenerationSession.java",
        [987, 989],
    ),
]:
    text = (repo / relative).read_text(encoding="utf-8")
    lines = text.splitlines()
    code_witnesses[relative] = {
        "source_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "hash_kind": "text-lf-utf8",
        "line_range": line_range,
        "excerpt": "\n".join(lines[line_range[0] - 1 : line_range[1]]),
    }
draft = {
    "schema": "voxygen.material-taxonomy-witness-draft/0",
    "revision": "mt38-active-end-witness-1",
    "semantic_authorities": {
        "fp": "voxygen-fidelity-v1",
        "cms": "cms1/rev1",
        "lis": "LIS1/rev1",
        "taxonomy_ticket": 38,
    },
    "acceptance_bindable": False,
    "total_population_classification": False,
    "canonical_registry_identity": None,
    "frozen_profile_identity": None,
    "source_population_identity": None,
    "source_capture_sha256": hashlib.sha256(encode(capture)).hexdigest(),
    "derived_evidence_sha256": {
        "model-evidence.json": hashlib.sha256(encode(models)).hexdigest(),
        "texture-evidence.json": hashlib.sha256(encode(textures)).hexdigest(),
    },
    "scope": {
        "minecraft_version": "1.21.11",
        "kind": "End source/render witnesses plus Overworld controls",
        "cohort_block_names": sorted(capture["render_witnesses"]),
        "runtime_catalog_is_profile_feasibility": False,
        "full_canonical_states_enumerated": False,
        "profile_generate_structures": False,
        "profile_boundary_frozen": False,
        "perceptual_family_assignments_accepted": False,
    },
    "families": sorted(families, key=lambda f: f["id"]),
    "membership_candidates": memberships,
    "ambiguity_ledger": ambiguities,
    "other_accounting": {"explicit_other_assignments": 0, "implicit_defaults": 0},
    "compatibility_witness": {
        "finding": "Canonical block-name mapping resolves each block default state; production writer uses that map. Pinned vanilla chorus default AGE=0 differs from generation AGE=5 and its texture. This is a source-level incompatibility with exact full-state claims, not a measured runtime comparison.",
        "repo_source_witnesses": code_witnesses,
        "minecraft_source_references": [
            bytecode("block.ChorusFlowerBlock"),
            state("chorus_flower"),
        ],
        "no_production_change": True,
    },
    "evidence_limits": [
        "Source and vanilla assets inspected; no Minecraft/Voxy rendering run, no same-view family error measurement.",
        "Texture statistics are encoded pixel diagnostics, not perceptual equivalence rules.",
        "Reference assets do not authorize backend representative choices.",
        "State selectors are witness scopes, not a validated canonical state registry or total mapping.",
        "Ground Role, consequentiality, retained-exact sets and representative permission are outside this artifact.",
    ],
}
save("taxonomy-draft.json", draft)
(ROOT / "taxonomy-draft.sha256").write_text(
    hashlib.sha256(encode(draft)).hexdigest() + "\n", encoding="utf-8", newline="\n"
)
print(
    json.dumps(
        {
            "membership_candidates": len(memberships),
            "families": len(families),
            "ambiguity_groups": len(ambiguities),
            "acceptance_bindable": False,
        }
    )
)
