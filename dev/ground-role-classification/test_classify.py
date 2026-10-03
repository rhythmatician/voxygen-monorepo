import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import classify


class FrozenGroundRoleTest(unittest.TestCase):
    def setUp(self):
        self.populations, self.rules, self.evidence = classify.load_inputs()

    def build(self):
        return classify.classify(self.populations, self.rules, self.evidence)

    def test_frozen_counts_and_known_population_totality(self):
        result = self.build()
        self.assertEqual(
            189, len(result["dimensions"]["minecraft:overworld"]["entries"])
        )
        self.assertEqual(10, len(result["dimensions"]["minecraft:the_end"]["entries"]))
        for dimension, value in result["dimensions"].items():
            self.assertEqual(
                set(self.populations["dimensions"][dimension]["identities"]),
                set(value["entries"]),
            )

    def test_same_block_class_cannot_make_logs_and_deepslate_share_role(self):
        entries = self.build()["dimensions"]["minecraft:overworld"]["entries"]
        self.assertEqual(
            "RotatedPillarBlock", entries["minecraft:oak_log"]["source"]["class"]
        )
        self.assertEqual(
            "RotatedPillarBlock", entries["minecraft:deepslate"]["source"]["class"]
        )
        self.assertIs(False, entries["minecraft:oak_log"]["ground_eligible"])
        self.assertIs(True, entries["minecraft:deepslate"]["ground_eligible"])
        self.assertIsNone(entries["minecraft:muddy_mangrove_roots"]["ground_eligible"])

    def test_ground_substrates_vegetation_and_fluids(self):
        entries = self.build()["dimensions"]["minecraft:overworld"]["entries"]
        for identity in [
            "stone",
            "rooted_dirt",
            "moss_block",
            "sculk",
            "ice",
            "deepslate_diamond_ore",
        ]:
            self.assertIs(True, entries["minecraft:" + identity]["ground_eligible"])
        for identity in [
            "oak_leaves",
            "brown_mushroom_block",
            "hanging_roots",
            "water",
            "air",
        ]:
            self.assertIs(False, entries["minecraft:" + identity]["ground_eligible"])

    def test_ambiguities_are_explicit_and_never_default_to_ground(self):
        result = self.build()
        for group, cases in result["ambiguities"].items():
            self.assertTrue(
                result["ambiguity_definitions"][group]["requires_human_decision"]
            )
            for case in cases:
                self.assertIsNone(
                    result["dimensions"][case["dimension"]]["entries"][
                        case["identity"]
                    ]["ground_eligible"]
                )
        self.assertEqual(
            38,
            sum(
                e["ground_eligible"] is None
                for e in result["dimensions"]["minecraft:overworld"]["entries"].values()
            ),
        )

    def test_missing_nether_population_blocks_acceptance_binding(self):
        result = self.build()
        self.assertFalse(result["acceptance_bindable"])
        self.assertEqual(
            37,
            result["unresolved_populations"]["minecraft:the_nether"]["reported_count"],
        )
        self.assertNotIn("minecraft:the_nether", result["dimensions"])

    def test_duplicate_ids_rejected(self):
        self.populations["dimensions"]["minecraft:overworld"]["identities"][
            "minecraft:dirt"
        ] = 0
        with self.assertRaisesRegex(ValueError, "unique contiguous"):
            self.build()

    def test_missing_identity_source_rejected(self):
        self.evidence["registrations"].pop("minecraft:pale_moss_block")
        with self.assertRaisesRegex(ValueError, "exactly cover"):
            self.build()

    def test_conflicting_rules_rejected(self):
        bad = copy.deepcopy(self.rules["rules"][1])
        bad["classes"] = []
        bad["identities"] = ["minecraft:stone"]
        self.rules["rules"].append(bad)
        with self.assertRaisesRegex(ValueError, "Conflicting role authority"):
            self.build()

    def test_unknown_identity_and_version_drift_rejected(self):
        self.rules["rules"][0]["identities"].append("minecraft:diamond_block")
        with self.assertRaisesRegex(ValueError, "outside the frozen"):
            self.build()
        self.rules["rules"][0]["identities"].pop()
        self.evidence["minecraft_version"] = "other"
        with self.assertRaisesRegex(ValueError, "version mismatch"):
            self.build()

    def test_missing_primary_source_rejected(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            self.assertRaisesRegex(ValueError, "Primary source missing or changed"),
        ):
            classify.verify_sources(self.evidence, Path(directory))

    def test_checkout_line_endings_do_not_change_artifact_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in classify.INPUTS:
                (root / name).write_bytes((classify.ROOT / name).read_bytes())
            with (
                patch.object(classify, "ROOT", root),
                patch("sys.argv", ["classify.py"]),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                classify.main()
            for path in root.iterdir():
                path.write_bytes(
                    path.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
                )
            with (
                patch.object(classify, "ROOT", root),
                patch("sys.argv", ["classify.py", "--check"]),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                classify.main()

    def test_consumer_lookup_rejects_local_ids_wrong_population_and_ambiguity(self):
        result = self.build()
        population_hash = result["dimensions"]["minecraft:overworld"][
            "population_sha256"
        ]
        self.assertIs(
            True,
            classify.ground_role(
                result, "minecraft:overworld", "minecraft:sand", population_hash
            ),
        )
        for identity in [0, "voxy:0", "minecraft:diamond_block", "minecraft:snow"]:
            with self.assertRaises(ValueError):
                classify.ground_role(
                    result, "minecraft:overworld", identity, population_hash
                )
        with self.assertRaisesRegex(ValueError, "registry hash mismatch"):
            classify.ground_role(
                result, "minecraft:overworld", "minecraft:sand", "0" * 64
            )
        with self.assertRaisesRegex(ValueError, "unresolved dimension"):
            classify.ground_role(
                result, "minecraft:the_nether", "minecraft:sand", population_hash
            )

    def test_unknown_schema_and_duplicate_json_keys_rejected(self):
        self.rules["schema"] = "voxygen.ground-role-rules/999"
        with self.assertRaisesRegex(ValueError, "Unsupported input schema"):
            self.build()
        with self.assertRaisesRegex(ValueError, "Duplicate JSON member"):
            json.loads(
                '{"minecraft:air":0,"minecraft:air":1}',
                object_pairs_hook=classify.unique_members,
            )

    def test_dimension_specific_substrate_and_gravity_ground(self):
        result = self.build()
        end = result["dimensions"]["minecraft:the_end"]["entries"]
        self.assertIs(True, end["minecraft:end_stone"]["ground_eligible"])
        self.assertIs(False, end["minecraft:chorus_plant"]["ground_eligible"])
        overworld = result["dimensions"]["minecraft:overworld"]["entries"]
        self.assertIs(True, overworld["minecraft:gravel"]["ground_eligible"])
        self.assertIs(True, overworld["minecraft:snow_block"]["ground_eligible"])


if __name__ == "__main__":
    unittest.main()
