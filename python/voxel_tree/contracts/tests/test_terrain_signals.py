from __future__ import annotations

import pytest

from voxel_tree.contracts.terrain_signals import (
    CONTRACT_REVISION,
    DIMENSIONS,
    Dimension,
    FrequencyContent,
    LEVEL_SAMPLING,
    ReconstructionStatus,
    ResamplingPolicy,
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


def test_per_level_reconstruction_status_is_explicit() -> None:
    assert LEVEL_SAMPLING[0].status is ReconstructionStatus.DENSITY_LATTICE_ONLY
    assert LEVEL_SAMPLING[1].status is ReconstructionStatus.DENSITY_LATTICE_ONLY
    for level in (2, 3, 4):
        assessment = LEVEL_SAMPLING[level]
        assert assessment.status is ReconstructionStatus.MEASURED_LOSSY
        assert assessment.nyquist_wavelength_blocks == assessment.xz_spacing_blocks * 2


def test_only_l0_and_l1_current_noise_grids_preserve_overworld_native_xz() -> None:
    # Current shapes: L0=8, L1=16 native samples; L2-L4=8 reduced samples.
    assert preserves_native_samples(0, 8, 4)
    assert preserves_native_samples(1, 16, 4)
    assert not preserves_native_samples(2, 8, 4)
    assert not preserves_native_samples(3, 8, 4)
    assert not preserves_native_samples(4, 8, 4)


def test_cache_key_separates_dimension_lattice_and_halo() -> None:
    mask = frozenset({Signal.FINAL_DENSITY})
    overworld = cache_key(
        Dimension.OVERWORLD, Signal.FINAL_DENSITY, 0, (-1, 0, 2), "router-a", mask
    )
    end = cache_key(Dimension.END, Signal.FINAL_DENSITY, 0, (-1, 0, 2), "router-a", mask)
    assert overworld.revision == CONTRACT_REVISION
    assert overworld.native_spacing == Spacing(4, 8, 4)
    assert end.native_spacing == Spacing(8, 4, 8)
    assert overworld != end


def test_cache_key_separates_router_and_field_mask() -> None:
    density_only = frozenset({Signal.FINAL_DENSITY})
    with_depth = frozenset({Signal.FINAL_DENSITY, Signal.DEPTH})
    first = cache_key(
        Dimension.OVERWORLD, Signal.FINAL_DENSITY, 0, (0, 0, 0), "seed:1", density_only
    )
    other_seed = cache_key(
        Dimension.OVERWORLD, Signal.FINAL_DENSITY, 0, (0, 0, 0), "seed:2", density_only
    )
    other_mask = cache_key(
        Dimension.OVERWORLD, Signal.FINAL_DENSITY, 0, (0, 0, 0), "seed:1", with_depth
    )
    assert len({first, other_seed, other_mask}) == 3


def test_aquifer_contract_keeps_seeded_voronoi_context() -> None:
    aquifer = lattice_for(Signal.AQUIFER, Dimension.OVERWORLD)
    assert aquifer.spacing == Spacing(16, 12, 16)
    assert aquifer.halo_cells == (4, 2, 4)
    assert aquifer.resampling is ResamplingPolicy.RECOMPUTE


def test_aquifer_shape_accounts_for_phase_and_world_section_origin() -> None:
    assert native_shape_for_world_section(Signal.AQUIFER, Dimension.OVERWORLD, 0, (0, 0, 0)) == (
        3,
        3,
        3,
    )
    assert native_shape_for_world_section(Signal.AQUIFER, Dimension.OVERWORLD, 0, (0, 1, 0)) == (
        3,
        4,
        3,
    )


@pytest.mark.parametrize(
    ("level", "expected_y_counts"),
    [(0, {3, 4}), (1, {6, 7}), (2, {11, 12}), (3, {22, 23}), (4, {43, 44})],
)
def test_aquifer_y_tile_ranges_match_phase_proof(level: int, expected_y_counts: set[int]) -> None:
    actual = {
        native_shape_for_world_section(Signal.AQUIFER, Dimension.OVERWORLD, level, (0, ws_y, 0))[1]
        for ws_y in range(3)
    }
    assert actual == expected_y_counts


@pytest.mark.parametrize("dimension", [Dimension.NETHER, Dimension.END])
@pytest.mark.parametrize("signal", [Signal.AQUIFER, Signal.VEIN])
def test_disabled_dimension_signals_fail_closed(dimension: Dimension, signal: Signal) -> None:
    with pytest.raises(ValueError, match="disabled"):
        lattice_for(signal, dimension)


@pytest.mark.parametrize(
    "signal",
    [
        Signal.TEMPERATURE,
        Signal.VEGETATION,
        Signal.CONTINENTS,
        Signal.EROSION,
        Signal.RIDGES,
    ],
)
def test_each_climate_quantity_has_an_explicit_frequency_contract(signal: Signal) -> None:
    lattice = lattice_for(signal, Dimension.OVERWORLD)
    assert lattice.frequency_content is FrequencyContent.OCTAVE_NOISE_NOT_BAND_LIMITED


def test_climate_frequency_is_dimension_specific() -> None:
    assert (
        lattice_for(Signal.TEMPERATURE, Dimension.NETHER).frequency_content
        is FrequencyContent.OCTAVE_NOISE_NOT_BAND_LIMITED
    )
    assert (
        lattice_for(Signal.CONTINENTS, Dimension.NETHER).frequency_content
        is FrequencyContent.CONSTANT
    )
    assert (
        lattice_for(Signal.TEMPERATURE, Dimension.END).frequency_content
        is FrequencyContent.CONSTANT
    )
    assert (
        lattice_for(Signal.EROSION, Dimension.END).frequency_content is FrequencyContent.END_ISLANDS
    )


@pytest.mark.parametrize(
    "signal",
    [Signal.SURFACE_RULE, Signal.CARVER, Signal.PLACED_FEATURE],
)
def test_post_density_quantities_have_executable_lattices(signal: Signal) -> None:
    assert lattice_for(signal, Dimension.OVERWORLD).spacing == Spacing(1, 1, 1)


@pytest.mark.parametrize("level", [-1, 5])
def test_invalid_level_rejected(level: int) -> None:
    with pytest.raises(ValueError, match="level"):
        model_spacing(level, 8)
