import argparse
import copy
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(
    description="Validate the bounded witness; raw source verification is opt-in."
)
parser.add_argument("--data-root", type=Path, default=Path(__file__).resolve().parent)
parser.add_argument("--source-root", type=Path)
parser.add_argument(
    "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
)
parser.add_argument("--write-receipt", type=Path)
args = parser.parse_args()
ROOT = args.data_root


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def encode(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def load(name):
    return json.loads(
        (ROOT / name).read_text(encoding="utf-8"), object_pairs_hook=unique
    )


capture = load("source-capture.json")
draft = load("taxonomy-draft.json")


def validate(value):
    if (
        value["acceptance_bindable"] is not False
        or value["total_population_classification"] is not False
    ):
        raise ValueError("partial witness cannot claim acceptance or total population")
    if any(
        value["scope"][field] is not False
        for field in [
            "full_canonical_states_enumerated",
            "runtime_catalog_is_profile_feasibility",
            "profile_boundary_frozen",
            "perceptual_family_assignments_accepted",
        ]
    ):
        raise ValueError("unsupported state/population completeness")
    if value["source_capture_sha256"] != sha(encode(capture)):
        raise ValueError("source-capture hash drift")
    if value["other_accounting"] != {
        "explicit_other_assignments": 0,
        "implicit_defaults": 0,
    }:
        raise ValueError("no other or implicit fallback authorized")
    for field in [
        "canonical_registry_identity",
        "frozen_profile_identity",
        "source_population_identity",
    ]:
        if value[field] is not None:
            raise ValueError("partial witness cannot fabricate authority bindings")
    families = {f["id"]: f for f in value["families"]}
    if len(families) != len(value["families"]):
        raise ValueError("duplicate family ID")
    previous = {
        "coarse-family": None,
        "surface-family": "coarse-family",
        "fine-family": "surface-family",
    }
    for family in families.values():
        if family["status"] != "candidate-unadjudicated":
            raise ValueError("witness family cannot claim adjudication")
        if family["depth"] not in previous:
            raise ValueError("unknown depth")
        if family["depth"] == "coarse-family":
            if family["parent"] is not None:
                raise ValueError("coarse parent must be absent")
        elif (
            family["parent"] not in families
            or families[family["parent"]]["depth"] != previous[family["depth"]]
        ):
            raise ValueError("bad family ancestry")
    if {m["canonical_block_name"] for m in value["membership_candidates"]} != set(
        capture["render_witnesses"]
    ):
        raise ValueError("witness cohort coverage mismatch")
    scopes = set()
    for member in value["membership_candidates"]:
        scope = (
            member["canonical_block_name"],
            json.dumps(member["state_selector"], sort_keys=True),
        )
        if scope in scopes:
            raise ValueError("duplicate state selector")
        scopes.add(scope)
        if member["canonical_state_population_complete"] is not False:
            raise ValueError("unproven canonical state population")
        for depth, family in member["candidate_membership"].items():
            if family not in families or families[family]["depth"] != depth:
                raise ValueError("unknown/mismatched membership")
        links = member["candidate_membership"]
        if (
            families[links["fine-family"]]["parent"] != links["surface-family"]
            or families[links["surface-family"]]["parent"] != links["coarse-family"]
        ):
            raise ValueError("inconsistent membership path")
        if not member["source_references"] or any(
            ref not in capture["entries"] for ref in member["source_references"]
        ):
            raise ValueError("missing membership source")


validate(draft)
for name, expected in draft["derived_evidence_sha256"].items():
    if name not in {"model-evidence.json", "texture-evidence.json"}:
        raise ValueError("unknown derived evidence file")
    if sha(encode(load(name))) != expected:
        raise ValueError("derived evidence hash drift: " + name)
verified = 0
if args.source_root:
    source_root = args.source_root.resolve()
    for key, entry in capture["entries"].items():
        path = (source_root / entry["local_reference"]).resolve()
        if not path.is_relative_to(source_root):
            raise ValueError("primary path escapes source root")
        if sha(path.read_bytes()) != entry["sha256"]:
            raise ValueError("primary source drift: " + key)
        verified += 1
if capture["missing"]:
    raise ValueError("unresolved primary asset reference")
if sha(encode(draft)) != (ROOT / "taxonomy-draft.sha256").read_text().strip():
    raise ValueError("artifact hash mismatch")
for relative, witness in draft["compatibility_witness"][
    "repo_source_witnesses"
].items():
    if witness["hash_kind"] != "text-lf-utf8":
        raise ValueError("unsupported repository source hash kind")
    text = (args.repo_root / relative).read_text(encoding="utf-8")
    if sha(text.encode("utf-8")) != witness["source_sha256"]:
        raise ValueError("repository source witness drift")
    lines = text.splitlines()
    start, end = witness["line_range"]
    if (
        type(start) is not int
        or type(end) is not int
        or not 1 <= start <= end <= len(lines)
    ):
        raise ValueError("invalid repository excerpt range")
    if witness["excerpt"] != "\n".join(lines[start - 1 : end]):
        raise ValueError("repository excerpt drift")
negative = []
for name, change in [
    (
        "missing-source",
        lambda d: d["membership_candidates"][0]["source_references"].append(
            "nonexistent"
        ),
    ),
    ("broken-hierarchy", lambda d: d["families"][0].update(parent="nonexistent")),
    ("false-binding", lambda d: d.update(acceptance_bindable=True)),
    ("fabricated-registry", lambda d: d.update(canonical_registry_identity="invented")),
    ("fabricated-profile", lambda d: d.update(frozen_profile_identity="invented")),
    (
        "fabricated-population",
        lambda d: d.update(source_population_identity="invented"),
    ),
    ("adjudicated-family", lambda d: d["families"][0].update(status="accepted")),
    ("zero-binding", lambda d: d.update(acceptance_bindable=0)),
    (
        "null-completeness",
        lambda d: d["scope"].update(full_canonical_states_enumerated=None),
    ),
    ("capture-hash-drift", lambda d: d.update(source_capture_sha256="0" * 64)),
]:
    value = copy.deepcopy(draft)
    change(value)
    try:
        validate(value)
    except ValueError:
        negative.append(name)
    else:
        raise AssertionError("mutation not rejected: " + name)
for value in [capture, draft]:
    lf = encode(value)
    crlf = lf.replace(b"\n", b"\r\n")
    if sha(encode(json.loads(lf))) != sha(encode(json.loads(crlf))):
        raise AssertionError("LF/CRLF artifact hash differs")
for witness in draft["compatibility_witness"]["repo_source_witnesses"].values():
    lf = witness["excerpt"]
    crlf = lf.replace("\n", "\r\n")
    if sha(lf.encode()) != sha(crlf.replace("\r\n", "\n").encode()):
        raise AssertionError("LF/CRLF text digest differs")
receipt = {
    "schema": "voxygen.material-taxonomy-witness-receipt/0",
    "artifact_sha256": sha(encode(draft)),
    "source_capture_sha256": sha(encode(capture)),
    "json_hash_kind": "canonical-json-sorted-indent2-utf8-lf",
    "primary_and_disassembled_entries_verified": verified,
    "primary_verification_status": "verified"
    if args.source_root
    else "unchecked-data-only",
    "cohort_block_names": len(capture["render_witnesses"]),
    "membership_candidates": len(draft["membership_candidates"]),
    "family_candidates": len(draft["families"]),
    "ambiguity_groups": len(draft["ambiguity_ledger"]),
    "negative_mutations_rejected": negative,
    "lf_crlf_digest_regression": "passed",
    "canonical_state_population_complete": False,
    "perceptual_correctness_proven": False,
    "acceptance_bindable": False,
    "production_files_written": False,
}
if args.write_receipt:
    args.write_receipt.write_bytes(encode(receipt))
print(json.dumps(receipt))
