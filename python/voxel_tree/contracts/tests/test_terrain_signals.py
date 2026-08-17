from __future__ import annotations

import pytest

from voxel_tree.contracts.terrain_signals import (
    CONTRACT_REVISION,
    DIMENSIONS,
    Dimension,
    Signal,
    Spacing,
    cache_key,
    lattice_for,
    model_spacing,
    native_shape_for_world_section,
    nyquist_min_wavelength,
    preserves_native_samples,
    world_height_shape,
)


def test_density_lattice_is_dimension_specific() -> None:
    assert DIMENSIONS[Dimension.OVERWORLD].density_spacing == Spacing(4, 8, 4)
    assert DIMENSIONS[Dimension.NETHER].density_spacing == Spacing(4, 8, 4)
    assert DIMENSIONS[Dimension.END].density_spacing == Spacing(8, 4, 8)


@pytest.mark.parametrize(
    ("dimension", "expected"),
    [
        (Dimension.OVERWORLD, (8, 4, 8)),
        (Dimension.NETHER, (8, 4, 8)),
        (Dimension.END, (4, 8, 4)),
    ],
)
def test_l0_density_shapes_follow_noise_settings(
    dimension: Dimension, expected: tuple[int, int, int]
) -> None:
    assert native_shape_for_world_section(Signal.FINAL_DENSITY, dimension, 0) == expected


@pytest.mark.parametrize(
    ("dimension", "expected"),
    [
        (Dimension.OVERWORLD, 48),
        (Dimension.NETHER, 16),
        (Dimension.END, 32),
    ],
)
def test_density_cells_span_each_dimension_height(dimension: Dimension, expected: int) -> None:
    assert world_height_shape(Signal.FINAL_DENSITY, dimension) == expected


def test_level_shapes_are_derived_from_world_section_footprint() -> None:
    assert native_shape_for_world_section(Signal.BIOME, Dimension.OVERWORLD, 0) == (8, 8, 8)
    assert native_shape_for_world_section(Signal.BIOME, Dimension.OVERWORLD, 4) == (
        128,
        128,
        128,
    )


def test_existing_eight_sample_climate_grids_have_derived_spacings() -> None:
    assert [model_spacing(level, 8) for level in (2, 3, 4)] == [16, 32, 64]
    assert [nyquist_min_wavelength(model_spacing(level, 8)) for level in (2, 3, 4)] == [
        32,
        64,
        128,
    ]


def test_only_l0_and_l1_current_noise_grids_preserve_overworld_native_xz() -> None:
    # Current shapes: L0=8, L1=16 native samples; L2-L4=8 reduced samples.
    assert preserves_native_samples(0, 8, 4)
    assert preserves_native_samples(1, 16, 4)
    assert not preserves_native_samples(2, 8, 4)
    assert not preserves_native_samples(3, 8, 4)
    assert not preserves_native_samples(4, 8, 4)


def test_cache_key_separates_dimension_lattice_and_halo() -> None:
    overworld = cache_key(Dimension.OVERWORLD, Signal.FINAL_DENSITY, 0, (-1, 0, 2))
    end = cache_key(Dimension.END, Signal.FINAL_DENSITY, 0, (-1, 0, 2))
    assert overworld.revision == CONTRACT_REVISION
    assert overworld.native_spacing == Spacing(4, 8, 4)
    assert end.native_spacing == Spacing(8, 4, 8)
    assert overworld != end


def test_aquifer_contract_keeps_seeded_voronoi_context() -> None:
    aquifer = lattice_for(Signal.AQUIFER, Dimension.OVERWORLD)
    assert aquifer.spacing == Spacing(16, 12, 16)
    assert aquifer.halo_cells == (4, 2, 4)
    assert "not resample-safe" in aquifer.exact_resampling


@pytest.mark.parametrize("level", [-1, 5])
def test_invalid_level_rejected(level: int) -> None:
    with pytest.raises(ValueError, match="level"):
        model_spacing(level, 8)
