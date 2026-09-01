from __future__ import annotations

from voxel_tree.tasks.voxy.voxy_export import build_level_sidecar
from voxel_tree.tasks.voxy.voxy_models import VoxyModelConfig


def test_export_sidecar_is_catalog_driven_and_declares_deployment_output():
    sidecar = build_level_sidecar(
        level=4,
        cfg=VoxyModelConfig(),
        input_shapes={
            "climate_2d": [1, 6, 8, 8],
            "biome_2d": [1, 8, 8],
            "y_position": [1],
        },
        input_dtypes={
            "climate_2d": "float32",
            "biome_2d": "int64",
            "y_position": "int64",
        },
        output_shapes={"block_logits": [1, 513, 24, 32, 32]},
        provenance={"git_commit": "abc123"},
    )

    assert sidecar["contract"] == "lodiffusion.v7.voxy_l4"
    assert sidecar["fingerprint"]
    assert sidecar["contract_outputs"] == [
        {
            "name": "block_logits",
            "shape": ["batch", 513, 24, 32, 32],
            "dtype": "float32",
            "description": "Debug logits in CYZX order",
        }
    ]
    assert sidecar["outputs"] == {"block_logits": [1, 513, 24, 32, 32]}
    assert sidecar["deployment_output"]["byte_size_per_item"] == 196_608
    assert sidecar["canonical_block_registry"]["size"] == 513
    assert sidecar["provenance"] == {"git_commit": "abc123"}
