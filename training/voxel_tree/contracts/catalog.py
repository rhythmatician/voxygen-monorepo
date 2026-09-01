"""Contract catalog — all model I/O contract revisions in one place.

Each model family has a section below.  When you change the I/O of a model:

  1. Copy the latest revision block.
  2. Bump ``revision`` by 1.
  3. Update the ``TensorSpec`` entries.
  4. Write a ``changelog`` string explaining what changed.
  5. Update ``build_pairs_fn`` / ``train_fn`` / ``export_fn`` if the entry
     points moved.

The training, export, and build-pairs scripts import ``get_contract(...)``
and use it to:
  - build their sidecar JSON     (``contract.to_sidecar(...)``)
  - embed metadata in checkpoints (``contract.to_checkpoint_meta()``)
  - validate tensor shapes        (``contract.validate_forward(...)``)

Revision numbering
------------------
Revisions are **per model family**, starting at 1.  There is no requirement
that revisions across different models stay in sync.  A "v7 pipeline" model
that already shipped with a sidecar marked ``version: "7.0.0"`` is mapped
to revision 1 here (the first contract-tracked revision).

Historical density/biome/heightmap contracts were removed during training
purification (#262 / #254). Recover via Git history if needed.
"""

from __future__ import annotations

# We import register locally to avoid circular import issues.
# It's fine — this module is loaded by registry._ensure_catalog_loaded().
from voxel_tree.contracts.registry import register as _register
from voxel_tree.contracts.spec import ModelContract, TensorSpec

# ===========================================================================
#  VOXY PER-LEVEL MODELS (ACTIVE)
# ===========================================================================

_CANONICAL_BLOCK_REGISTRY = {
    "version": "voxygen.blocks.v1",
    "sha256": "0c6a4c223cf4c7debea631a14a85741f8d09f684352a9d84cad072eceb087483",
    "size": 513,
}
_ROUTER_FIELDS = (
    "temperature",
    "vegetation",
    "continents",
    "erosion",
    "depth",
    "ridges",
    "preliminary_surface_level",
    "final_density",
    "barrier",
    "fluid_level_floodedness",
    "fluid_level_spread",
    "lava",
    "vein_toggle",
    "vein_ridged",
    "vein_gap",
)
_LEVEL_INPUTS = {
    0: (
        TensorSpec("noise_3d", ("batch", 15, 8, 4, 8), channels=_ROUTER_FIELDS),
        TensorSpec("biome_3d", ("batch", 8, 4, 8), dtype="int64"),
        TensorSpec("y_position", ("batch",), dtype="int64"),
        TensorSpec("parent_blocks", ("batch", 32, 32, 32), dtype="int64"),
    ),
    1: (
        TensorSpec("noise_3d", ("batch", 15, 16, 8, 16), channels=_ROUTER_FIELDS),
        TensorSpec("biome_3d", ("batch", 16, 8, 16), dtype="int64"),
        TensorSpec("y_position", ("batch",), dtype="int64"),
        TensorSpec("parent_blocks", ("batch", 32, 32, 32), dtype="int64"),
    ),
    2: (
        TensorSpec(
            "climate_2d", ("batch", 7, 8, 8), channels=_ROUTER_FIELDS[:6] + ("final_density",)
        ),
        TensorSpec("biome_2d", ("batch", 8, 8), dtype="int64"),
        TensorSpec("y_position", ("batch",), dtype="int64"),
        TensorSpec("parent_blocks", ("batch", 32, 32, 32), dtype="int64"),
    ),
    3: (
        TensorSpec("climate_2d", ("batch", 6, 8, 8), channels=_ROUTER_FIELDS[:6]),
        TensorSpec("biome_2d", ("batch", 8, 8), dtype="int64"),
        TensorSpec("y_position", ("batch",), dtype="int64"),
        TensorSpec("parent_blocks", ("batch", 32, 32, 32), dtype="int64"),
    ),
    4: (
        TensorSpec("climate_2d", ("batch", 6, 8, 8), channels=_ROUTER_FIELDS[:6]),
        TensorSpec("biome_2d", ("batch", 8, 8), dtype="int64"),
        TensorSpec("y_position", ("batch",), dtype="int64"),
    ),
}
_HEAD_WIDTHS = {0: 48, 1: 48, 2: 32, 3: 24, 4: 24}

for _level in range(5):
    _spatial_shape = (24, 32, 32) if _level == 4 else (32, 32, 32)
    _byte_size = 196_608 if _level == 4 else 262_144
    _register(
        ModelContract(
            model_name=f"voxy_l{_level}",
            revision=1,
            contract_id=f"lodiffusion.v7.voxy_l{_level}",
            inputs=_LEVEL_INPUTS[_level],
            outputs=(
                TensorSpec(
                    "block_logits",
                    ("batch", 513, *_spatial_shape),
                    dtype="float32",
                    description="Debug logits in CYZX order",
                ),
            ),
            onnx_opset=17,
            description=f"Live Voxy L{_level} per-Level deployment model",
            changelog="Replaces the retired monolithic sparse-octree contract.",
            build_pairs_fn="voxel_tree.tasks.voxy.build_voxy_pairs:main",
            train_fn="voxel_tree.tasks.voxy.voxy_train:train_voxy_level",
            export_fn="voxel_tree.tasks.voxy.voxy_export:export_level",
            extra={
                "level": _level,
                "architecture": {
                    "channels": [
                        _HEAD_WIDTHS[_level],
                        _HEAD_WIDTHS[_level] * 2,
                        _HEAD_WIDTHS[_level] * 4,
                    ],
                    "block_head": {"input_channels": _HEAD_WIDTHS[_level], "classes": 513},
                },
                "deployment_output": {
                    "kind": "canonical_block_ids",
                    "shape": ["batch", *_spatial_shape],
                    "dtype": "int64",
                    "layout": "YZX",
                    "byte_size_per_item": _byte_size,
                    "graph": "logits -> ArgMax -> local ID -> Gather(local_to_canonical) -> canonical ID",
                    "debug_output": {
                        "name": "block_logits",
                        "dtype": "float32",
                        "layout": "CYZX",
                        "shape": ["batch", 513, *_spatial_shape],
                    },
                },
                "canonical_block_registry": _CANONICAL_BLOCK_REGISTRY,
            },
        )
    )

del _byte_size, _level, _spatial_shape
