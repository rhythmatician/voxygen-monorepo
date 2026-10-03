"""Build the draft Ground Role Classification from frozen evidence inputs."""

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INPUTS = ("populations.json", "rules.json", "source-evidence.json")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def unique_members(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON member: {key}")
        result[key] = value
    return result


def load_inputs(root=ROOT):
    return [
        json.loads(
            (root / name).read_text(encoding="utf-8"), object_pairs_hook=unique_members
        )
        for name in INPUTS
    ]


def classify(populations, rules, evidence):
    for document, expected in [
        (populations, "voxygen.ground-role-populations/1"),
        (rules, "voxygen.ground-role-rules/1"),
        (evidence, "voxygen.ground-role-source-evidence/1"),
    ]:
        if document.get("schema") != expected:
            raise ValueError("Unsupported input schema")
    if set(populations["dimensions"]) != {
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end",
    }:
        raise ValueError("Unknown or missing dimension")
    if populations["minecraft_version"] != evidence["minecraft_version"]:
        raise ValueError("Population/source Minecraft version mismatch")
    if evidence["missing_source_files"]:
        raise ValueError("Referenced source files are missing")
    if populations["revision"] != rules["revision"]:
        raise ValueError("Population/rules revision mismatch")
    known = set()
    for dimension, population in populations["dimensions"].items():
        if population["coverage"] not in {"source-closed", "unresolved-population"}:
            raise ValueError("Unknown population coverage declaration")
        if population["coverage"] == "unresolved-population":
            if "identities" in population:
                raise ValueError("Unresolved population cannot claim identities")
            continue
        identities = population["identities"]
        ids = list(identities.values())
        if any(type(i) is not int for i in ids) or sorted(ids) != list(range(len(ids))):
            raise ValueError(f"{dimension}: IDs must be unique contiguous integers")
        if identities.get("minecraft:air") != 0:
            raise ValueError(f"{dimension}: frozen registry must reserve air=0")
        known.update(identities)
    if set(evidence["registrations"]) != known:
        raise ValueError("Source evidence must exactly cover the known population")
    for rule in rules["rules"]:
        if type(rule["ground_eligible"]) is not bool:
            raise ValueError("Resolved rule role must be boolean")
        if set(rule["identities"]) - known:
            raise ValueError("Rule names an identity outside the frozen populations")
        if not rule["basis"] or not rule["source_files"]:
            raise ValueError(
                "Every resolved rule requires rationale and primary sources"
            )
        if set(rule["source_files"]) - set(evidence["decompiled_sources"]):
            raise ValueError("Rule source hash is missing")
    if set(rules["ambiguous_identities"]) - known:
        raise ValueError("Ambiguity names an identity outside the frozen populations")

    result = {
        "schema": "voxygen.ground-role-classification/1",
        "revision": populations["revision"],
        "binding_status": populations["binding_status"],
        "minecraft_version": populations["minecraft_version"],
        "cms_identity": "cms1/rev1",
        "fp_identity": "voxygen-fidelity-v1",
        "identity_granularity": populations["identity_granularity"],
        "authority": populations["authority"],
        "dimensions": {},
        "ambiguities": {},
        "ambiguity_definitions": {},
        "unresolved_populations": {},
    }
    for dimension, population in populations["dimensions"].items():
        if population["coverage"] == "unresolved-population":
            result["unresolved_populations"][dimension] = population
            continue
        entries = {}
        for identity, legacy_id in population["identities"].items():
            registration = evidence["registrations"][identity]
            matches = [
                r
                for r in rules["rules"]
                if identity in r["identities"] or registration["class"] in r["classes"]
            ]
            explicit_group = rules["ambiguous_identities"].get(identity)
            class_groups = [
                group
                for group, classes in rules["ambiguous_classes"].items()
                if registration["class"] in classes
            ]
            if (
                len(matches) > 1
                or len(class_groups) > 1
                or (matches and (explicit_group or class_groups))
            ):
                raise ValueError(f"Conflicting role authority for {identity}")
            if matches:
                entry = {
                    "ground_eligible": matches[0]["ground_eligible"],
                    "status": "source-derived-draft",
                    "rule": matches[0]["id"],
                }
            else:
                group = explicit_group or (
                    class_groups[0] if class_groups else "unclassified-source-case"
                )
                entry = {
                    "ground_eligible": None,
                    "status": "unresolved",
                    "ambiguity": group,
                }
                result["ambiguities"].setdefault(group, []).append(
                    {"dimension": dimension, "identity": identity}
                )
                definition = rules["ambiguity_definitions"].get(group)
                if definition is None:
                    raise ValueError(
                        f"Missing explicit ambiguity definition for {identity}"
                    )
                result["ambiguity_definitions"][group] = dict(
                    definition,
                    competing_ground_eligible=[False, True],
                    effect_if_included="This geometry can supply the highest eligible surface in a column.",
                    effect_if_excluded="Remove this geometry before choosing the next eligible surface or explicit no-ground.",
                    semantic_adjudication_owner=109,
                )
            entries[identity] = dict(entry, legacy_id=legacy_id, source=registration)
        result["dimensions"][dimension] = {
            "authority": population.get("authority", populations["authority"]),
            "legacy_namespace": population["legacy_namespace"],
            "boundary": population["boundary"],
            "population_sha256": digest(encode(population["identities"])),
            "entries": entries,
        }
    result["complete_known_population_coverage"] = True
    result["all_roles_resolved"] = not result["ambiguities"]
    result["all_dimension_populations_resolved"] = not result["unresolved_populations"]
    result["acceptance_bindable"] = (
        result["all_roles_resolved"]
        and result["all_dimension_populations_resolved"]
        and result["binding_status"] == "bound"
    )
    return result


def verify_sources(evidence, minecraft_root):
    for relative, expected in evidence["decompiled_sources"].items():
        path = minecraft_root / relative
        if not path.is_file() or digest(path.read_bytes()) != expected:
            raise ValueError(f"Primary source missing or changed: {relative}")
    jar = minecraft_root / f"{evidence['minecraft_version']}.jar"
    if (
        not jar.is_file()
        or hashlib.sha1(jar.read_bytes()).hexdigest() != evidence["minecraft_jar_sha1"]
    ):
        raise ValueError("Minecraft JAR missing or changed")


def ground_role(classification, dimension, canonical_identity, population_sha256):
    if not isinstance(canonical_identity, str) or not canonical_identity.startswith(
        "minecraft:"
    ):
        raise ValueError(
            "Canonical registry name required; candidate/Voxy-local IDs are invalid"
        )
    population = classification["dimensions"].get(dimension)
    if population is None:
        raise ValueError("Unknown or unresolved dimension population")
    if population["population_sha256"] != population_sha256:
        raise ValueError("Population registry hash mismatch")
    entry = population["entries"].get(canonical_identity)
    if entry is None:
        raise ValueError("Canonical identity is outside the bound population")
    if entry["ground_eligible"] is None:
        raise ValueError("Ground role requires unresolved semantic adjudication")
    return entry["ground_eligible"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="verify generated output parity"
    )
    parser.add_argument(
        "--minecraft-root", type=Path, help="verify frozen primary source hashes"
    )
    args = parser.parse_args()
    populations, rules, evidence = load_inputs()
    if args.minecraft_root:
        verify_sources(evidence, args.minecraft_root)
    result = classify(populations, rules, evidence)
    result["input_sha256"] = {
        name: digest(encode(document))
        for name, document in zip(INPUTS, (populations, rules, evidence), strict=True)
    }
    output = ROOT / "classification.json"
    serialized = encode(result)
    hash_path = ROOT / "classification.sha256"
    hash_bytes = (digest(serialized) + "\n").encode("ascii")
    if args.check:
        if (
            not output.is_file()
            or output.read_text(encoding="utf-8").encode("utf-8") != serialized
        ):
            raise SystemExit("Generated classification is stale or missing")
        if (
            not hash_path.is_file()
            or hash_path.read_text(encoding="ascii").encode("ascii") != hash_bytes
        ):
            raise SystemExit("Classification artifact hash is stale or missing")
    else:
        output.write_bytes(serialized)
        hash_path.write_bytes(hash_bytes)
    for dimension, population in result["dimensions"].items():
        entries = population["entries"].values()
        unresolved = sum(e["ground_eligible"] is None for e in entries)
        print(
            f"{dimension}: {len(population['entries'])} entries, {unresolved} unresolved roles"
        )
    print(f"Unresolved populations: {list(result['unresolved_populations'])}")
    print(f"Acceptance bindable: {result['acceptance_bindable']}")


if __name__ == "__main__":
    main()
