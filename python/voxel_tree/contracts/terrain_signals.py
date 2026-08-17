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
    CLIMATE_2D = "climate_2d"
    DEPTH = "depth"
    PRELIMINARY_SURFACE = "preliminary_surface"
    FINAL_DENSITY = "final_density"
    BIOME = "biome"
    HEIGHTMAP = "heightmap"
    AQUIFER = "aquifer"
    VEIN = "vein"


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
    phase: str
    interpolation: str
    cache_boundary: str
    halo_cells: tuple[int, int, int]
    exact_resampling: str


DIMENSIONS = {
    Dimension.OVERWORLD: DimensionLattice(-64, 384, Spacing(4, 8, 4), True, True),
    Dimension.NETHER: DimensionLattice(0, 128, Spacing(4, 8, 4), False, False),
    Dimension.END: DimensionLattice(0, 128, Spacing(8, 4, 8), False, False),
}


DIMENSION_INDEPENDENT_SIGNALS = {
    Signal.CLIMATE_2D: SignalLattice(
        Spacing(4, None, 4),
        "quart cells are floor-divided from block coordinates; Y is ignored",
        "continuous DensityFunction evaluation; no band-limit guarantee",
        "FlatCache/Cache2D lifetime of the owning NoiseChunk",
        (0, 0, 0),
        "evaluate at destination coordinates; filter before any decimation",
    ),
    Signal.BIOME: SignalLattice(
        Spacing(4, 4, 4),
        "quart cell q owns block coordinates [4q, 4q+3], including negative q",
        "categorical nearest-cell lookup; never linear interpolation",
        "LevelChunkSection 4x4x4 biome palette and BiomeSource search result",
        (0, 0, 0),
        "nearest/majority with an explicit categorical policy",
    ),
    Signal.HEIGHTMAP: SignalLattice(
        Spacing(1, None, 1),
        "one integer column at each block XZ coordinate",
        "derived threshold crossing; discontinuous and not band-limited",
        "16x16 chunk heightmap; WORLDGEN maps become valid at NOISE",
        (0, 0, 0),
        "max/min/mean must be chosen by consumer semantics; linear is not exact",
    ),
    Signal.AQUIFER: SignalLattice(
        Spacing(16, 12, 16),
        "anchor cells use floor((x-5)/16), floor((y+1)/12), floor((z-5)/16)",
        "seeded jittered Voronoi; four nearest anchors and pressure tests",
        "per-NoiseChunk FluidStatus and location arrays",
        (4, 2, 4),
        "not resample-safe; recompute from seed, density, and neighboring anchors",
    ),
    Signal.VEIN: SignalLattice(
        Spacing(1, 1, 1),
        "block coordinates",
        "thresholded ridged/noise decision at block resolution",
        "NoiseChunk/DensityFunction cache lifetime",
        (0, 0, 0),
        "not resample-safe; aggregate only after material classification",
    ),
}


def world_section_width(level: int) -> int:
    """Return the block width of Voxy's 32-voxel WorldSection at ``level``."""
    if level not in range(5):
        raise ValueError(f"level must be in [0, 4], got {level}")
    return 32 << level


def lattice_for(signal: Signal, dimension: Dimension) -> SignalLattice:
    """Resolve the native lattice, including the dimension-specific density cell."""
    if signal in DIMENSION_INDEPENDENT_SIGNALS:
        return DIMENSION_INDEPENDENT_SIGNALS[signal]

    spacing = DIMENSIONS[dimension].density_spacing
    if signal is Signal.DEPTH:
        return SignalLattice(
            spacing,
            "density-cell coordinates; vertical gradient is anchored to dimension Y",
            "trilinear where wrapped by NoiseChunk; contains an explicit Y gradient",
            "NoiseInterpolator/CacheOnce lifetime of the owning NoiseChunk",
            (1, 1, 1),
            "trilinear reconstruction only within the sampled cell representation",
        )
    if signal is Signal.PRELIMINARY_SURFACE:
        return SignalLattice(
            Spacing(spacing.x, None, spacing.z),
            "quart-aligned XZ query positions derived with floor division",
            "column search over density; discontinuous integer-like height",
            "NoiseChunk preliminarySurfaceLevelCache, keyed by quart-aligned XZ",
            (1, 0, 1),
            "not linearly resample-safe; recompute or use a declared height reducer",
        )
    if signal is Signal.FINAL_DENSITY:
        return SignalLattice(
            spacing,
            "cell corner lattice at multiples of the dimension NoiseSettings spacing",
            "NoiseChunk trilinear interpolation inside each density cell",
            "NoiseInterpolator/CacheAllInCell lifetime of the owning NoiseChunk",
            (1, 1, 1),
            "exact only for the piecewise-trilinear representation, with its corner halo",
        )
    raise KeyError(signal)


def native_shape_for_world_section(
    signal: Signal, dimension: Dimension, level: int
) -> tuple[int, int | None, int]:
    """Derive native sample cells in one full WorldSection footprint.

    This is the cell count, not the extra upper corner needed to reconstruct a
    closed interval. Add ``halo_cells`` from :func:`lattice_for` when caching.
    """
    width = world_section_width(level)
    spacing = lattice_for(signal, dimension).spacing
    if width % spacing.x or width % spacing.z:
        raise ValueError("WorldSection footprint does not align to signal lattice")
    y = None if spacing.y is None else width // spacing.y
    return width // spacing.x, y, width // spacing.z


def world_height_shape(signal: Signal, dimension: Dimension) -> int | None:
    """Return native Y cells across the dimension's generated height."""
    spacing_y = lattice_for(signal, dimension).spacing.y
    if spacing_y is None:
        return None
    height = DIMENSIONS[dimension].height
    if height % spacing_y:
        raise ValueError("Dimension height does not align to signal lattice")
    return height // spacing_y


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
    signal: Signal
    level: int
    world_section: tuple[int, int, int]
    native_spacing: Spacing
    halo_cells: tuple[int, int, int]


def cache_key(
    dimension: Dimension,
    signal: Signal,
    level: int,
    world_section: tuple[int, int, int],
) -> ConditioningCacheKey:
    """Build a key that cannot alias dimensions, lattices, revisions, or halos."""
    lattice = lattice_for(signal, dimension)
    world_section_width(level)
    return ConditioningCacheKey(
        CONTRACT_REVISION,
        dimension,
        signal,
        level,
        world_section,
        lattice.spacing,
        lattice.halo_cells,
    )


__all__ = [
    "CONTRACT_REVISION",
    "DIMENSIONS",
    "ConditioningCacheKey",
    "Dimension",
    "DimensionLattice",
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
