"""Tests for the model contract system."""

from __future__ import annotations

import json
import hashlib
from pathlib import Path

import pytest
import torch

from voxel_tree.contracts.registry import (
    CONTRACTS,
    get_contract,
    latest_revision,
    list_models,
    validate_checkpoint_contract,
)
from voxel_tree.contracts.spec import ContractViolation, ModelContract, TensorSpec

# ═══════════════════════════════════════════════════════════════════════════
#  TensorSpec
# ═══════════════════════════════════════════════════════════════════════════


class TestTensorSpec:
    def test_matches_static_shape(self):
        spec = TensorSpec(name="x", shape=(1, 6), dtype="float32")
        assert spec.matches_tensor(torch.randn(1, 6))
        assert not spec.matches_tensor(torch.randn(1, 7))
        assert not spec.matches_tensor(torch.randn(2, 6))

    def test_matches_dynamic_batch(self):
        spec = TensorSpec(name="x", shape=("batch", 6), dtype="float32")
        assert spec.matches_tensor(torch.randn(1, 6))
        assert spec.matches_tensor(torch.randn(32, 6))
        assert not spec.matches_tensor(torch.randn(32, 7))

    def test_matches_wrong_ndim(self):
        spec = TensorSpec(name="x", shape=("batch", 6), dtype="float32")
        assert not spec.matches_tensor(torch.randn(6))
        assert not spec.matches_tensor(torch.randn(1, 6, 4))

    def test_shape_mismatch_msg(self):
        spec = TensorSpec(name="x", shape=("batch", 6))
        assert spec.shape_mismatch_msg(torch.randn(4, 6)) is None
        msg = spec.shape_mismatch_msg(torch.randn(4, 7))
        assert msg is not None
        assert "x" in msg

    def test_roundtrip_dict(self):
        spec = TensorSpec(
            name="climate_input",
            shape=("batch", 6),
            dtype="float32",
            channels=("a", "b", "c", "d", "e", "f"),
            channel_indices=(0, 1, 2, 3, 4, 5),
            description="test",
        )
        d = spec.to_dict()
        reconstructed = TensorSpec.from_dict(d)
        assert reconstructed == spec

    def test_to_dict_minimal(self):
        spec = TensorSpec(name="x", shape=(1, 3))
        d = spec.to_dict()
        assert d == {"name": "x", "shape": [1, 3], "dtype": "float32"}


# ═══════════════════════════════════════════════════════════════════════════
#  ModelContract
# ═══════════════════════════════════════════════════════════════════════════


class TestModelContract:
    @pytest.fixture
    def simple_contract(self):
        return ModelContract(
            model_name="test_model",
            revision=1,
            inputs=(TensorSpec(name="x", shape=("batch", 6)),),
            outputs=(TensorSpec(name="y", shape=("batch", 2)),),
            description="test contract",
        )

    def test_auto_contract_id(self, simple_contract):
        assert simple_contract.contract_id == "lodiffusion.r1.test_model"

    def test_explicit_contract_id(self):
        c = ModelContract(
            model_name="foo",
            revision=3,
            contract_id="custom.id.foo",
            inputs=(),
            outputs=(),
        )
        assert c.contract_id == "custom.id.foo"

    def test_fingerprint_stability(self, simple_contract):
        # Same contract → same fingerprint
        c2 = ModelContract(
            model_name="test_model",
            revision=1,
            inputs=(TensorSpec(name="x", shape=("batch", 6)),),
            outputs=(TensorSpec(name="y", shape=("batch", 2)),),
            description="different description — should not affect fingerprint",
        )
        assert simple_contract.fingerprint == c2.fingerprint

    def test_fingerprint_changes_on_shape(self, simple_contract):
        c2 = ModelContract(
            model_name="test_model",
            revision=1,
            inputs=(TensorSpec(name="x", shape=("batch", 7)),),
            outputs=(TensorSpec(name="y", shape=("batch", 2)),),
        )
        assert simple_contract.fingerprint != c2.fingerprint

    def test_validate_forward_ok(self, simple_contract):
        x = torch.randn(4, 6)
        y = torch.randn(4, 2)
        simple_contract.validate_forward([x], [y])

    def test_validate_forward_wrong_input_shape(self, simple_contract):
        x = torch.randn(4, 7)  # wrong dimension
        y = torch.randn(4, 2)
        with pytest.raises(ContractViolation, match="Shape mismatches"):
            simple_contract.validate_forward([x], [y])

    def test_validate_forward_wrong_count(self, simple_contract):
        x = torch.randn(4, 6)
        with pytest.raises(ContractViolation, match="Expected 1 outputs"):
            simple_contract.validate_forward([x], [])

    def test_to_sidecar(self, simple_contract):
        sidecar = simple_contract.to_sidecar(epoch=10, val_mse=0.001)
        assert sidecar["contract"] == "lodiffusion.r1.test_model"
        assert sidecar["revision"] == 1
        assert sidecar["training"]["epoch"] == 10
        assert "fingerprint" in sidecar
        # Should be JSON-serializable
        json.dumps(sidecar)

    def test_to_checkpoint_meta(self, simple_contract):
        meta = simple_contract.to_checkpoint_meta()
        assert meta["model_name"] == "test_model"
        assert meta["revision"] == 1
        assert "fingerprint" in meta

    def test_from_sidecar_roundtrip(self, simple_contract):
        sidecar = simple_contract.to_sidecar()
        reconstructed = ModelContract.from_sidecar(sidecar)
        assert reconstructed.model_name == simple_contract.model_name
        assert reconstructed.revision == simple_contract.revision
        assert len(reconstructed.inputs) == len(simple_contract.inputs)
        assert len(reconstructed.outputs) == len(simple_contract.outputs)


# ═══════════════════════════════════════════════════════════════════════════
#  Registry
# ═══════════════════════════════════════════════════════════════════════════


class TestRegistry:
    def test_catalog_loaded(self):
        """The catalog should be loaded and CONTRACTS should be non-empty."""
        assert len(CONTRACTS) > 0

    def test_list_models(self):
        models = list_models()
        assert "density" in models
        assert "biome" in models
        assert "heightmap" in models
        assert {f"voxy_l{level}" for level in range(5)} <= set(models)
        assert "voxy" not in models

    def test_latest_revision_density(self):
        rev = latest_revision("density")
        assert rev >= 1  # we registered rev 0 and rev 1

    def test_get_contract_latest(self):
        c = get_contract("density")
        assert c.revision == latest_revision("density")

    def test_get_contract_specific_revision(self):
        c1 = get_contract("density", revision=1)
        assert c1.revision == 1
        voxy_l4 = get_contract("voxy_l4", revision=1)
        assert voxy_l4.contract_id == "lodiffusion.v7.voxy_l4"

    def test_get_contract_missing(self):
        with pytest.raises(KeyError, match="No contract"):
            get_contract("nonexistent_model")

    def test_get_contract_missing_revision(self):
        with pytest.raises(KeyError, match="Available revisions"):
            get_contract("density", revision=999)


# ═══════════════════════════════════════════════════════════════════════════
#  Checkpoint validation
# ═══════════════════════════════════════════════════════════════════════════


class TestCheckpointValidation:
    def test_missing_meta_non_strict(self):
        """Non-strict should warn but not raise."""
        contract = get_contract("density", revision=1)
        ckpt = {"model_state_dict": {}}
        with pytest.warns(UserWarning, match="no contract_meta"):
            validate_checkpoint_contract(ckpt, contract, strict=False)

    def test_missing_meta_strict(self):
        contract = get_contract("density", revision=1)
        ckpt = {"model_state_dict": {}}
        with pytest.raises(ContractViolation, match="no contract_meta"):
            validate_checkpoint_contract(ckpt, contract, strict=True)

    def test_matching_meta(self):
        contract = get_contract("density", revision=1)
        ckpt = {"contract_meta": contract.to_checkpoint_meta()}
        validate_checkpoint_contract(ckpt, contract, strict=True)

    def test_wrong_model_name(self):
        contract = get_contract("density", revision=1)
        ckpt = {
            "contract_meta": {
                "model_name": "biome",
                "revision": 1,
                "fingerprint": "abc",
            }
        }
        with pytest.raises(ContractViolation, match="biome.*density"):
            validate_checkpoint_contract(ckpt, contract)

    def test_newer_revision_rejected(self):
        contract = get_contract("density", revision=1)
        newer = ModelContract(
            model_name="density",
            revision=2,
            inputs=contract.inputs,
            outputs=contract.outputs,
        )
        ckpt = {"contract_meta": newer.to_checkpoint_meta()}
        with pytest.raises(ContractViolation, match="revision 2"):
            validate_checkpoint_contract(ckpt, contract, strict=False)


# ═══════════════════════════════════════════════════════════════════════════
#  Catalog-specific assertions
# ═══════════════════════════════════════════════════════════════════════════


class TestCatalogContracts:
    """Verify that catalog-registered contracts have sane shapes."""

    def test_density_rev1_shapes(self):
        c = get_contract("density", revision=1)
        assert c.inputs[0].shape == ("batch", 6)
        assert c.outputs[0].shape == ("batch", 2)
        assert c.inputs[0].channels is not None
        assert len(c.inputs[0].channels) == 6

    def test_biome_rev1_shapes(self):
        c = get_contract("biome", revision=1)
        assert c.inputs[0].shape == ("batch", 6)
        assert c.outputs[0].shape == ("batch", 54)

    def test_heightmap_rev1_shapes(self):
        c = get_contract("heightmap", revision=1)
        assert c.inputs[0].shape == ("batch", 96)
        assert c.outputs[0].shape == ("batch", 32)

    @pytest.mark.parametrize(
        ("level", "input_names", "head_width", "output_shape", "byte_size"),
        [
            (4, ["climate_2d", "biome_2d", "y_position"], 24, ("batch", 24, 32, 32), 196_608),
            (
                3,
                ["climate_2d", "biome_2d", "y_position", "parent_blocks"],
                24,
                ("batch", 32, 32, 32),
                262_144,
            ),
            (
                2,
                ["climate_2d", "biome_2d", "y_position", "parent_blocks"],
                32,
                ("batch", 32, 32, 32),
                262_144,
            ),
            (
                1,
                ["noise_3d", "biome_3d", "y_position", "parent_blocks"],
                48,
                ("batch", 32, 32, 32),
                262_144,
            ),
            (
                0,
                ["noise_3d", "biome_3d", "y_position", "parent_blocks"],
                48,
                ("batch", 32, 32, 32),
                262_144,
            ),
        ],
    )
    def test_live_voxy_level_contracts(
        self, level, input_names, head_width, output_shape, byte_size
    ):
        c = get_contract(f"voxy_l{level}", revision=1)
        assert [spec.name for spec in c.inputs] == input_names
        spatial_shape = output_shape[1:]
        assert c.outputs == (
            TensorSpec(
                name="block_logits",
                shape=("batch", 513, *spatial_shape),
                dtype="float32",
                description="Debug logits in CYZX order",
            ),
        )
        assert c.extra["architecture"]["block_head"] == {
            "input_channels": head_width,
            "classes": 513,
        }
        assert c.extra["deployment_output"] == {
            "kind": "canonical_block_ids",
            "shape": ["batch", *spatial_shape],
            "dtype": "int64",
            "layout": "YZX",
            "byte_size_per_item": byte_size,
            "graph": "logits -> ArgMax -> local ID -> Gather(local_to_canonical) -> canonical ID",
            "debug_output": {
                "name": "block_logits",
                "dtype": "float32",
                "layout": "CYZX",
                "shape": ["batch", 513, *spatial_shape],
            },
        }
        assert c.extra["canonical_block_registry"] == {
            "version": "voxygen.blocks.v1",
            "sha256": "0c6a4c223cf4c7debea631a14a85741f8d09f684352a9d84cad072eceb087483",
            "size": 513,
        }

    def test_canonical_registry_hash_matches_checked_in_artifact(self):
        vocab_path = Path(__file__).parents[2] / "config" / "voxy_vocab.json"
        expected = get_contract("voxy_l4").extra["canonical_block_registry"]
        assert hashlib.sha256(vocab_path.read_bytes()).hexdigest() == expected["sha256"]

    def test_all_contracts_have_fingerprints(self):
        for key, contract in CONTRACTS.items():
            assert len(contract.fingerprint) == 16, f"{key} fingerprint wrong length"

    def test_no_duplicate_fingerprints_within_model(self):
        """Different revisions of the same model must have different fingerprints."""
        from collections import defaultdict

        by_model: dict[str, list[str]] = defaultdict(list)
        for (name, _rev), contract in CONTRACTS.items():
            by_model[name].append(contract.fingerprint)
        for name, fps in by_model.items():
            if len(fps) > 1:
                assert len(set(fps)) == len(fps), f"{name} has duplicate fingerprints"


# ═══════════════════════════════════════════════════════════════════════════
#  Track↔Contract alignment checks
# ═══════════════════════════════════════════════════════════════════════════


class TestTrackAlignment:
    """Tests for ``check_track_alignment()`` staleness detection."""

    @pytest.fixture
    def _fake_track(self):
        """Factory for lightweight track-like objects."""
        from dataclasses import dataclass as _dc

        @_dc
        class FakeTrack:
            track_id: str
            contract_name: str | None = None
            contract_revision: int | None = None

        return FakeTrack

    def test_all_aligned_returns_empty(self, _fake_track):
        from voxel_tree.contracts.registry import check_track_alignment

        # density rev 1 IS the latest → aligned
        tracks = [_fake_track("density", "density", 1)]
        issues = check_track_alignment(tracks)
        assert issues == []

    def test_stale_track_detected(self, _fake_track, monkeypatch):
        from voxel_tree.contracts.registry import check_track_alignment
        from voxel_tree.contracts import registry

        revision_zero = ModelContract("fixture_model", 0, (), ())
        revision_one = ModelContract("fixture_model", 1, (), ())
        monkeypatch.setitem(registry.CONTRACTS, ("fixture_model", 0), revision_zero)
        monkeypatch.setitem(registry.CONTRACTS, ("fixture_model", 1), revision_one)
        monkeypatch.setitem(registry._LATEST, "fixture_model", 1)
        tracks = [_fake_track("old_octree", "fixture_model", 0)]
        issues = check_track_alignment(tracks)
        assert len(issues) >= 1
        stale_issues = [i for i in issues if i.severity == "stale"]
        assert len(stale_issues) == 1
        assert stale_issues[0].track_id == "old_octree"
        assert stale_issues[0].current_revision == 0
        assert stale_issues[0].latest_revision_ == 1
        assert "rev 0" in stale_issues[0].message
        assert "rev 1" in stale_issues[0].message

    def test_missing_contract_is_error(self, _fake_track):
        from voxel_tree.contracts.registry import check_track_alignment

        tracks = [_fake_track("ghost_model", "does_not_exist", 42)]
        issues = check_track_alignment(tracks)
        assert len(issues) == 1
        assert issues[0].severity == "error"
        assert "does not exist" in issues[0].message

    def test_none_revision_tracks_latest(self, _fake_track):
        from voxel_tree.contracts.registry import check_track_alignment

        # contract_revision=None means "always track latest" — no issue raised
        tracks = [_fake_track("auto_track", "density", None)]
        issues = check_track_alignment(tracks)
        assert issues == []

    def test_no_contract_binding_skipped(self, _fake_track):
        from voxel_tree.contracts.registry import check_track_alignment

        # Track with no contract_name → nothing to check → no issues
        tracks = [_fake_track("plain_track", None, None)]
        issues = check_track_alignment(tracks)
        assert issues == []

    def test_multiple_tracks_mixed(self, _fake_track):
        from voxel_tree.contracts.registry import check_track_alignment

        tracks = [
            _fake_track("ok_track", "density", 1),  # aligned
            _fake_track("stale_track", "density", 0),  # stale
            _fake_track("unbound", None, None),  # skipped
        ]
        issues = check_track_alignment(tracks)
        assert len(issues) == 1
        assert issues[0].track_id == "stale_track"

    def test_real_model_tracks_alignment(self):
        """Smoke test: the actual MODEL_TRACKS should have no errors and no stale tracks.
        All tracks are pinned to their current contract revision.
        """
        from voxel_tree.contracts.registry import check_track_alignment

        issues = check_track_alignment()  # uses real MODEL_TRACKS
        errors = [i for i in issues if i.severity == "error"]
        stale = [i for i in issues if i.severity == "stale"]
        # No errors should exist — all tracks reference valid contracts
        assert errors == [], f"Broken track bindings: {errors}"
        # No stale tracks — all tracks are pinned to current revisions
        assert stale == [], f"Stale tracks (update contract_revision): {stale}"
