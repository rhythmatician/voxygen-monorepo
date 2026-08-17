"""Executable spatial-lattice contract for Minecraft terrain conditioning.

The constants are version-bound to Minecraft 1.21.11 / 26.1-snapshot-11.
Their source evidence and the resampling proofs live in
``docs/reference/upstream/minecraft-1.21.11-terrain-signal-lattices.md``.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

CONTRACT_REVISION = "minecraft-1.21.11-terrain-signals-v1"


class Dimension(StrEnum):
    OVERWORLD = "minecraft:overworld"
    NETHER = "minecraft:the_nether"
    END = "minecraft:the_end"


class Signal(StrEnum):
    TEMPERATURE = "temperature"
    VEGETATION = "vegetation"
    CONTINENTS = "continents"
    EROSION = "erosion"
    RIDGES = "ridges"
    DEPTH = "depth"
    PRELIMINARY_SURFACE = "preliminary_surface"
    FINAL_DENSITY = "final_density"
    BIOME = "biome"
    HEIGHTMAP = "heightmap"
    AQUIFER = "aquifer"
    VEIN = "vein"
    SURFACE_RULE = "surface_rule"
    CARVER = "carver"
    PLACED_FEATURE = "placed_feature"


class LatticePhase(StrEnum):
    BLOCK = "block"
    QUART = "quart"
    DENSITY_CELL = "density_cell"
    AQUIFER_ANCHOR = "aquifer_anchor"


class InterpolationPolicy(StrEnum):
    POINT_EVALUATION = "point_evaluation"
    CATEGORICAL_NEAREST = "categorical_nearest"
    THRESHOLD_CROSSING = "threshold_crossing"
    SEEDED_VORONOI = "seeded_voronoi"
    THRESHOLDED_CLASSIFICATION = "thresholded_classification"
    TRILINEAR = "trilinear"
    SEEDED_MASK = "seeded_mask"
    SEEDED_PLACEMENT = "seeded_placement"


class CacheBoundary(StrEnum):
    NONE = "none"
    NOISE_CHUNK = "noise_chunk"
    CHUNK_HEIGHTMAP = "chunk_heightmap"
    CHUNK_SECTION_BIOME = "chunk_section_biome"


class FrequencyContent(StrEnum):
    OCTAVE_NOISE_NOT_BAND_LIMITED = "octave_noise_not_band_limited"
    CONSTANT = "constant"
    END_ISLANDS = "end_islands"
    CATEGORICAL_DISCONTINUOUS = "categorical_discontinuous"
    DERIVED_DISCONTINUOUS = "derived_discontinuous"
    SEEDED_TOPOLOGY = "seeded_topology"
    BLOCK_DECISION = "block_decision"


class ResamplingPolicy(StrEnum):
    EVALUATE_OR_LOWPASS = "evaluate_or_lowpass"
    CATEGORICAL_REDUCER = "categorical_reducer"
    HEIGHT_REDUCER = "height_reducer"
    RECOMPUTE = "recompute"
    CLASSIFY_THEN_AGGREGATE = "classify_then_aggregate"
    TRILINEAR_WITH_CORNER_HALO = "trilinear_with_corner_halo"
    GENERATE_FROM_SOURCE_NEIGHBORHOOD = "generate_from_source_neighborhood"


class ReconstructionStatus(StrEnum):
    DENSITY_LATTICE_ONLY = "density_lattice_only"
    MEASURED_LOSSY = "measured_lossy"


class CoordinateRule(StrEnum):
    FLOOR_DIVISION = "floor_division"
    TRUNCATE_TOWARD_ZERO = "truncate_toward_zero"


@dataclass(frozen=True)
class Spacing:
    x: int
    y: int | None
    z: int


@dataclass(frozen=True)
class DimensionLattice:
    min_y: int
    height: int
    density_spacing: Spacing
    aquifers_enabled: bool
    veins_enabled: bool


@dataclass(frozen=True)
class SignalLattice:
    spacing: Spacing
    ownership_offset: tuple[int, int | None, int]
    phase: LatticePhase
    interpolation: InterpolationPolicy
    cache_boundary: CacheBoundary
    frequency_content: FrequencyContent
    halo_cells: tuple[int, int, int]
    resampling: ResamplingPolicy
    coordinate_rule: CoordinateRule = CoordinateRule.FLOOR_DIVISION


@dataclass(frozen=True)
class LevelSamplingAssessment:
    target_voxel_blocks: int
    conditioning_shape: tuple[int, ...]
    xz_spacing_blocks: int
    nyquist_wavelength_blocks: int
    status: ReconstructionStatus


DIMENSIONS = {
    Dimension.OVERWORLD: DimensionLattice(-64, 384, Spacing(4, 8, 4), True, True),
    Dimension.NETHER: DimensionLattice(0, 128, Spacing(4, 8, 4), False, False),
    Dimension.END: DimensionLattice(0, 128, Spacing(8, 4, 8), False, False),
}


CLIMATE_SIGNALS = frozenset(
    {
        Signal.TEMPERATURE,
        Signal.VEGETATION,
        Signal.CONTINENTS,
        Signal.EROSION,
        Signal.RIDGES,
    }
)

CLIMATE_LATTICE = SignalLattice(
    spacing=Spacing(4, None, 4),
    ownership_offset=(0, None, 0),
    phase=LatticePhase.QUART,
    interpolation=InterpolationPolicy.POINT_EVALUATION,
    cache_boundary=CacheBoundary.NOISE_CHUNK,
    frequency_content=FrequencyContent.OCTAVE_NOISE_NOT_BAND_LIMITED,
    halo_cells=(0, 0, 0),
    resampling=ResamplingPolicy.EVALUATE_OR_LOWPASS,
)

CONSTANT_LATTICE = SignalLattice(
    spacing=Spacing(1, None, 1),
    ownership_offset=(0, None, 0),
    phase=LatticePhase.BLOCK,
    interpolation=InterpolationPolicy.POINT_EVALUATION,
    cache_boundary=CacheBoundary.NONE,
    frequency_content=FrequencyContent.CONSTANT,
    halo_cells=(0, 0, 0),
    resampling=ResamplingPolicy.EVALUATE_OR_LOWPASS,
)

END_ISLAND_LATTICE = SignalLattice(
    spacing=Spacing(8, None, 8),
    ownership_offset=(0, None, 0),
    phase=LatticePhase.DENSITY_CELL,
    interpolation=InterpolationPolicy.POINT_EVALUATION,
    cache_boundary=CacheBoundary.NOISE_CHUNK,
    frequency_content=FrequencyContent.END_ISLANDS,
    halo_cells=(12, 0, 12),
    resampling=ResamplingPolicy.EVALUATE_OR_LOWPASS,
    coordinate_rule=CoordinateRule.TRUNCATE_TOWARD_ZERO,
)

DIMENSION_INDEPENDENT_SIGNALS = {
    Signal.BIOME: SignalLattice(
        Spacing(4, 4, 4),
        (0, 0, 0),
        LatticePhase.QUART,
        InterpolationPolicy.CATEGORICAL_NEAREST,
        CacheBoundary.CHUNK_SECTION_BIOME,
        FrequencyContent.CATEGORICAL_DISCONTINUOUS,
        (0, 0, 0),
        ResamplingPolicy.CATEGORICAL_REDUCER,
    ),
    Signal.HEIGHTMAP: SignalLattice(
        Spacing(1, None, 1),
        (0, None, 0),
        LatticePhase.BLOCK,
        InterpolationPolicy.THRESHOLD_CROSSING,
        CacheBoundary.CHUNK_HEIGHTMAP,
        FrequencyContent.DERIVED_DISCONTINUOUS,
        (0, 0, 0),
        ResamplingPolicy.HEIGHT_REDUCER,
    ),
    Signal.SURFACE_RULE: SignalLattice(
        Spacing(1, 1, 1),
        (0, 0, 0),
        LatticePhase.BLOCK,
        InterpolationPolicy.THRESHOLDED_CLASSIFICATION,
        CacheBoundary.NONE,
        FrequencyContent.BLOCK_DECISION,
        (0, 0, 0),
        ResamplingPolicy.CLASSIFY_THEN_AGGREGATE,
    ),
    Signal.CARVER: SignalLattice(
        Spacing(1, 1, 1),
        (0, 0, 0),
        LatticePhase.BLOCK,
        InterpolationPolicy.SEEDED_MASK,
        CacheBoundary.NONE,
        FrequencyContent.SEEDED_TOPOLOGY,
        (128, 0, 128),
        ResamplingPolicy.GENERATE_FROM_SOURCE_NEIGHBORHOOD,
    ),
    Signal.PLACED_FEATURE: SignalLattice(
        Spacing(1, 1, 1),
        (0, 0, 0),
        LatticePhase.BLOCK,
        InterpolationPolicy.SEEDED_PLACEMENT,
        CacheBoundary.NONE,
        FrequencyContent.SEEDED_TOPOLOGY,
        (0, 0, 0),
        ResamplingPolicy.GENERATE_FROM_SOURCE_NEIGHBORHOOD,
    ),
}

AQUIFER_LATTICE = SignalLattice(
    Spacing(16, 12, 16),
    (5, -1, 5),
    LatticePhase.AQUIFER_ANCHOR,
    InterpolationPolicy.SEEDED_VORONOI,
    CacheBoundary.NOISE_CHUNK,
    FrequencyContent.SEEDED_TOPOLOGY,
    (4, 2, 4),
    ResamplingPolicy.RECOMPUTE,
)

VEIN_LATTICE = SignalLattice(
    Spacing(1, 1, 1),
    (0, 0, 0),
    LatticePhase.BLOCK,
    InterpolationPolicy.THRESHOLDED_CLASSIFICATION,
    CacheBoundary.NOISE_CHUNK,
    FrequencyContent.BLOCK_DECISION,
    (0, 0, 0),
    ResamplingPolicy.CLASSIFY_THEN_AGGREGATE,
)


LEVEL_SAMPLING = {
    0: LevelSamplingAssessment(1, (15, 8, 4, 8), 4, 8, ReconstructionStatus.DENSITY_LATTICE_ONLY),
    1: LevelSamplingAssessment(2, (15, 16, 8, 16), 4, 8, ReconstructionStatus.DENSITY_LATTICE_ONLY),
    2: LevelSamplingAssessment(4, (7, 8, 8), 16, 32, ReconstructionStatus.MEASURED_LOSSY),
    3: LevelSamplingAssessment(8, (6, 8, 8), 32, 64, ReconstructionStatus.MEASURED_LOSSY),
    4: LevelSamplingAssessment(16, (6, 8, 8), 64, 128, ReconstructionStatus.MEASURED_LOSSY),
}


def world_section_width(level: int) -> int:
    """Return the block width of Voxy's 32-voxel WorldSection at ``level``."""
    if level not in range(5):
        raise ValueError(f"level must be in [0, 4], got {level}")
    return 32 << level


def lattice_for(signal: Signal, dimension: Dimension) -> SignalLattice:
    """Resolve the native lattice, including the dimension-specific density cell."""
    profile = DIMENSIONS[dimension]
    if signal in CLIMATE_SIGNALS:
        if dimension is Dimension.OVERWORLD:
            return CLIMATE_LATTICE
        if dimension is Dimension.NETHER:
            return (
                CLIMATE_LATTICE
                if signal in {Signal.TEMPERATURE, Signal.VEGETATION}
                else CONSTANT_LATTICE
            )
        return END_ISLAND_LATTICE if signal is Signal.EROSION else CONSTANT_LATTICE
    if signal in DIMENSION_INDEPENDENT_SIGNALS:
        return DIMENSION_INDEPENDENT_SIGNALS[signal]
    if signal is Signal.AQUIFER:
        if not profile.aquifers_enabled:
            raise ValueError(f"{signal.value} is disabled in {dimension.value}")
        return AQUIFER_LATTICE
    if signal is Signal.VEIN:
        if not profile.veins_enabled:
            raise ValueError(f"{signal.value} is disabled in {dimension.value}")
        return VEIN_LATTICE

    spacing = profile.density_spacing
    if signal is Signal.DEPTH:
        return SignalLattice(
            spacing,
            (0, profile.min_y, 0),
            LatticePhase.DENSITY_CELL,
            InterpolationPolicy.TRILINEAR,
            CacheBoundary.NOISE_CHUNK,
            FrequencyContent.OCTAVE_NOISE_NOT_BAND_LIMITED,
            (1, 1, 1),
            ResamplingPolicy.TRILINEAR_WITH_CORNER_HALO,
        )
    if signal is Signal.PRELIMINARY_SURFACE:
        return SignalLattice(
            Spacing(spacing.x, None, spacing.z),
            (0, None, 0),
            LatticePhase.DENSITY_CELL,
            InterpolationPolicy.THRESHOLD_CROSSING,
            CacheBoundary.NOISE_CHUNK,
            FrequencyContent.DERIVED_DISCONTINUOUS,
            (1, 0, 1),
            ResamplingPolicy.HEIGHT_REDUCER,
        )
    if signal is Signal.FINAL_DENSITY:
        return SignalLattice(
            spacing,
            (0, profile.min_y, 0),
            LatticePhase.DENSITY_CELL,
            InterpolationPolicy.TRILINEAR,
            CacheBoundary.NOISE_CHUNK,
            FrequencyContent.OCTAVE_NOISE_NOT_BAND_LIMITED,
            (1, 1, 1),
            ResamplingPolicy.TRILINEAR_WITH_CORNER_HALO,
        )
    raise KeyError(signal)


def native_shape_for_world_section(
    signal: Signal,
    dimension: Dimension,
    level: int,
    world_section: tuple[int, int, int] = (0, 0, 0),
) -> tuple[int, int | None, int]:
    """Derive lattice cells intersecting a WorldSection's half-open footprint.

    Counts use the signal's ownership phase, so non-divisor lattices such as
    aquifer Y (12 blocks) correctly vary with the WorldSection coordinate.
    Add ``halo_cells`` from :func:`lattice_for` when caching source context.
    """
    width = world_section_width(level)
    lattice = lattice_for(signal, dimension)

    def intersecting_cells(axis: int, spacing: int, offset: int) -> int:
        if lattice.frequency_content is FrequencyContent.CONSTANT:
            return 1

        def coordinate_index(coordinate: int) -> int:
            numerator = coordinate - offset
            if lattice.coordinate_rule is CoordinateRule.FLOOR_DIVISION:
                return numerator // spacing
            return (1 if numerator >= 0 else -1) * (abs(numerator) // spacing)

        start = world_section[axis] * width
        end_inclusive = start + width - 1
        first = coordinate_index(start)
        last = coordinate_index(end_inclusive)
        return last - first + 1

    x = intersecting_cells(0, lattice.spacing.x, lattice.ownership_offset[0])
    z = intersecting_cells(2, lattice.spacing.z, lattice.ownership_offset[2])
    if lattice.spacing.y is None:
        y = None
    else:
        offset_y = lattice.ownership_offset[1]
        assert offset_y is not None
        y = intersecting_cells(1, lattice.spacing.y, offset_y)
    return x, y, z


def world_height_shape(signal: Signal, dimension: Dimension) -> int | None:
    """Return lattice cells intersecting the dimension's generated Y interval."""
    lattice = lattice_for(signal, dimension)
    spacing_y = lattice.spacing.y
    if spacing_y is None:
        return None
    offset_y = lattice.ownership_offset[1]
    assert offset_y is not None
    profile = DIMENSIONS[dimension]
    first = (profile.min_y - offset_y) // spacing_y
    last = (profile.min_y + profile.height - 1 - offset_y) // spacing_y
    return last - first + 1


def model_spacing(level: int, samples_xz: int) -> int:
    """Derive block spacing of a uniform model input across one WorldSection."""
    width = world_section_width(level)
    if samples_xz <= 0 or width % samples_xz:
        raise ValueError("sample count must divide the WorldSection width")
    return width // samples_xz


def nyquist_min_wavelength(sample_spacing: int) -> int:
    """Smallest wavelength representable without aliasing under uniform sampling."""
    if sample_spacing <= 0:
        raise ValueError("sample spacing must be positive")
    return sample_spacing * 2


def preserves_native_samples(level: int, samples_xz: int, native_spacing: int) -> bool:
    """Whether a model grid is at least as dense as the upstream native grid."""
    return model_spacing(level, samples_xz) <= native_spacing


@dataclass(frozen=True)
class ConditioningCacheKey:
    revision: str
    dimension: Dimension
    router_identity: str
    signal_mask: frozenset[Signal]
    signal: Signal
    level: int
    world_section: tuple[int, int, int]
    native_spacing: Spacing
    ownership_offset: tuple[int, int | None, int]
    interpolation: InterpolationPolicy
    halo_cells: tuple[int, int, int]


def cache_key(
    dimension: Dimension,
    signal: Signal,
    level: int,
    world_section: tuple[int, int, int],
    router_identity: str,
    signal_mask: frozenset[Signal],
) -> ConditioningCacheKey:
    """Build a key that cannot alias dimensions, lattices, revisions, or halos."""
    if not router_identity:
        raise ValueError("router_identity must be non-empty")
    if not signal_mask or signal not in signal_mask:
        raise ValueError("signal_mask must be non-empty and contain signal")
    lattice = lattice_for(signal, dimension)
    world_section_width(level)
    return ConditioningCacheKey(
        CONTRACT_REVISION,
        dimension,
        router_identity,
        signal_mask,
        signal,
        level,
        world_section,
        lattice.spacing,
        lattice.ownership_offset,
        lattice.interpolation,
        lattice.halo_cells,
    )


__all__ = [
    "CONTRACT_REVISION",
    "DIMENSIONS",
    "AQUIFER_LATTICE",
    "CLIMATE_SIGNALS",
    "CacheBoundary",
    "ConditioningCacheKey",
    "Dimension",
    "DimensionLattice",
    "CoordinateRule",
    "FrequencyContent",
    "InterpolationPolicy",
    "LatticePhase",
    "LEVEL_SAMPLING",
    "LevelSamplingAssessment",
    "ReconstructionStatus",
    "ResamplingPolicy",
    "Signal",
    "SignalLattice",
    "Spacing",
    "cache_key",
    "lattice_for",
    "model_spacing",
    "native_shape_for_world_section",
    "nyquist_min_wavelength",
    "preserves_native_samples",
    "world_height_shape",
    "world_section_width",
]
