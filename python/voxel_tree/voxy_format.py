"""Voxy format reference decoder — source-grounded helpers for ``python/docs/VOXY-FORMAT.md``.

Grounding
---------
Audited against pinned Voxy source ``python/tools/fabric-server/runtime/mods/voxy-0.2.11-alpha.jar``
(version ``0.2.11-alpha``, sha256 ``63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c``)
and the public mirror ``MCRcortex/voxy`` (commit ``337b919`` on ``dev`` branch,
cloned to ``/tmp/voxy`` during task). Key methods/constants:

* ``WorldEngine.getWorldSectionId`` / ``getLevel`` / ``getX`` / ``getY`` / ``getZ``
  — ``me.cortex.voxy.common.world.WorldEngine.java`` lines ~55-70.
* ``WorldSection.getIndex`` / ``SECTION_VOLUME`` / ``getChildIndex``
  — ``me.cortex.voxy.common.world.WorldSection.java`` (YZX ``(y<<10)|(z<<5)|x``).
* ``Mapper`` bit layout / ``composeMappingId`` / ``getBlockId`` / ``getBiomeId`` / ``getLightId``
  — ``me.cortex.voxy.common.world.other.Mapper.java``.
* Light packing ``(sky | (block<<4))`` — ``VoxelIngestService.java:71`` / ``WorldImporter.java:525``.
* Light channel split in ``Mipper.java`` (``blockLight & 0xF0``, ``skyLight & 0x0F``).
* Morton helpers ``SaveLoadSystem3.lin2z`` / ``z2lin`` — ``me.cortex.voxy.common.world.SaveLoadSystem3.java``.
* ``RealVoxyVolumeWriter.yzxIndex`` / ``VoxyWorldBinding.l0Index`` in this repo
  (mirrors ``WorldSection.getIndex``).

All public helpers are pure and have no I/O or global state.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Section key constants (WorldEngine.getWorldSectionId)
# ---------------------------------------------------------------------------

# Bit widths & positions for 64-bit key:
#   bits 63-60 : lvl  (4 bits, unsigned 0..15, we validate 0..4 in practice)
#   bits 59-52 : y    (8 bits, signed -128..127)
#   bits 51-28 : z    (24 bits, signed -8388608..8388607)
#   bits 27- 4 : x    (24 bits, signed)
#   bits  3- 0 : spare
_KEY_LVL_SHIFT = 60
_KEY_Y_SHIFT = 52
_KEY_Z_SHIFT = 28
_KEY_X_SHIFT = 4

# ---------------------------------------------------------------------------
# Voxel long constants (Mapper.java)
# ---------------------------------------------------------------------------

BLOCK_ID_SHIFT = 27
BLOCK_ID_BITS = 20
BLOCK_ID_MASK = (1 << BLOCK_ID_BITS) - 1  # 0xFFFFF

BIOME_ID_SHIFT = 47
BIOME_ID_BITS = 9
BIOME_ID_MASK = (1 << 9) - 1  # 0x1FF

LIGHT_SHIFT = 56
LIGHT_MASK = 0xFF  # 8 bits, layout: block(high nibble) | sky(low nibble)

# Voxy int sizes
_X_Z_BITS = 24
_Y_BITS = 8
_LVL_BITS = 4

SECTION_EXTENT = 32
SECTION_VOLUME = SECTION_EXTENT ** 3  # 32768


# ---------------------------------------------------------------------------
# Sign extension helper
# ---------------------------------------------------------------------------


def _sign_extend(value: int, bits: int) -> int:
    """Sign-extend ``value`` from ``bits`` width to a signed Python int.

    Mirrors Java's arithmetic right-shift sign extension, e.g. ``(id<<36)>>40``.
    ``value`` must already be masked to ``bits`` width (unsigned).
    """
    if value >= (1 << (bits - 1)):
        value -= 1 << bits
    return value


# ---------------------------------------------------------------------------
# Section key encode/decode
# ---------------------------------------------------------------------------


def make_key(lvl: int, x: int, y: int, z: int) -> int:
    """Pack Voxy section coordinates into a 64-bit key.

    Inverse of :func:`decode_key`. Uses the same masking as
    ``WorldEngine.getWorldSectionId``: ``(lvl&0xF)<<60 | (y&0xFF)<<52 | (z&0xFFFFFF)<<28 | (x&0xFFFFFF)<<4``.

    Source: ``WorldEngine.java:getWorldSectionId``.
    ``bits 3-0 are spare/unused`` and are always zero.
    """
    return (
        ((lvl & 0xF) << _KEY_LVL_SHIFT)
        | ((y & 0xFF) << _KEY_Y_SHIFT)
        | ((z & 0xFFFFFF) << _KEY_Z_SHIFT)
        | ((x & 0xFFFFFF) << _KEY_X_SHIFT)
    )


def decode_key(key: int) -> tuple[int, int, int, int]:
    """Decode a 64-bit Voxy section key into ``(lvl, x, y, z)``.

    Uses explicit masking + :func:`_sign_extend` so behaviour is independent of
    Python's arbitrary-precision shifts. Equivalent to Voxy's
    ``getLevel``/``getX``/``getY``/``getZ`` which use ``(id<<N)>>M`` arithmetic shifts
    on a fixed-width ``long``.

    Source: ``WorldEngine.java:getLevel/getX/getY/getZ``.
    """
    lvl = (key >> _KEY_LVL_SHIFT) & 0xF
    y = _sign_extend((key >> _KEY_Y_SHIFT) & 0xFF, _Y_BITS)
    z = _sign_extend((key >> _KEY_Z_SHIFT) & 0xFFFFFF, _X_Z_BITS)
    x = _sign_extend((key >> _KEY_X_SHIFT) & 0xFFFFFF, _X_Z_BITS)
    return lvl, x, y, z


# ---------------------------------------------------------------------------
# Voxel long encode/decode
# ---------------------------------------------------------------------------


def encode_voxel(block_id: int, biome_id: int, sky_light: int, block_light: int) -> int:
    """Compose a packed Voxy voxel ``long`` from semantic fields.

    Light packing per Voxy source (``VoxelIngestService.java:71`` / ``WorldImporter.java:525``):

    ``light_byte = sky | (block << 4)`` — low nibble = sky (0-15), high nibble = block (0-15).

    Layout (``Mapper.java``):
        bits 63-56 : light (block<<4|sky)
        bits 55-47 : biomeId (9 bits)
        bits 46-27 : blockId (20 bits)
        bits 26-0  : unused (zero)

    ``AIR = 0`` — block bits zero means air regardless of biome/light.
    """
    light = ((block_light & 0xF) << 4) | (sky_light & 0xF)
    return (
        ((light & LIGHT_MASK) << LIGHT_SHIFT)
        | ((biome_id & BIOME_ID_MASK) << BIOME_ID_SHIFT)
        | ((block_id & BLOCK_ID_MASK) << BLOCK_ID_SHIFT)
    )


def decode_voxel(v: int) -> tuple[int, int, int, int]:
    """Decode a packed Voxy voxel ``long`` into ``(block_id, biome_id, sky_light, block_light)``.

    Nibble ownership per ``Mipper.java`` / ingestion paths:
        ``sky_light   = light & 0xF``         (low nibble)
        ``block_light = (light >> 4) & 0xF``   (high nibble)

    This matches the on-disk contract where ``light = sky | (block<<4)``.

    Source: ``Mapper.getBlockId/getBiomeId/getLightId`` + ``Mipper.java`` light split.
    """
    block_id = (v >> BLOCK_ID_SHIFT) & BLOCK_ID_MASK
    biome_id = (v >> BIOME_ID_SHIFT) & BIOME_ID_MASK
    light = (v >> LIGHT_SHIFT) & LIGHT_MASK
    sky_light = light & 0xF
    block_light = (light >> 4) & 0xF
    return block_id, biome_id, sky_light, block_light


# ---------------------------------------------------------------------------
# YZX indexing (WorldSection.getIndex)
# ---------------------------------------------------------------------------


def yzx_index(x: int, y: int, z: int) -> int:
    """Linear index for 32^3 Voxy WorldSection in YZX order.

    Source: ``WorldSection.java:getIndex`` → ``(y<<10)|(z<<5)|x``.
    Mirrors ``RealVoxyVolumeWriter.yzxIndex`` in this repo (single source of truth).
    """
    return (y << 10) | (z << 5) | x


def l0_index(x: int, y: int, z: int) -> int:
    """Linear index for 16^3 VoxelizedSection level-0 (``(y<<8)|(z<<4)|x``).

    Source: ``VoxyWorldBinding.l0Index`` / ``WorldConversionFactory``.
    """
    return (y << 8) | (z << 4) | x


# ---------------------------------------------------------------------------
# Morton helpers (SaveLoadSystem3.lin2z / z2lin)
# ---------------------------------------------------------------------------


def lin2z(idx: int) -> int:
    """Map a YZX linear index (0..32767) to a Morton/Z-curve code.

    Correctly extracts ``x = idx & 31``, ``z = (idx>>5)&31``, ``y = (idx>>10)&31``
    per ``WorldSection.getIndex`` (YZX). Previous doc incorrectly swapped y/z.

    Morton interleaving: bit positions
        ``x`` → 0,3,6,9,12
        ``y`` → 1,4,7,10,13
        ``z`` → 2,5,8,11,14

    Source: ``SaveLoadSystem3.java:lin2z`` using ``Integer.expand`` masks
        ``x mask 0b1001001001001``, ``y mask 0b10010010010010``, ``z mask 0b100100100100100``.

    This implementation uses an explicit per-bit loop (readable and bit-exact
    to the mask expansion) rather than magic-constant ``_split3``.
    """
    x = idx & 0x1F
    y = (idx >> 10) & 0x1F
    z = (idx >> 5) & 0x1F
    morton = 0
    for b in range(5):
        morton |= ((x >> b) & 1) << (3 * b)
        morton |= ((y >> b) & 1) << (3 * b + 1)
        morton |= ((z >> b) & 1) << (3 * b + 2)
    return morton


def z2lin(morton: int) -> int:
    """Inverse of :func:`lin2z` — Morton code → YZX linear index.

    De-interleaves the 15-bit Morton code (5 bits per axis × 3) back to
    ``(y<<10)|(z<<5)|x`` linear order.

    Source: ``SaveLoadSystem3.java:z2lin`` using ``Integer.compress``.
    """
    x = y = z = 0
    for b in range(5):
        x |= ((morton >> (3 * b)) & 1) << b
        y |= ((morton >> (3 * b + 1)) & 1) << b
        z |= ((morton >> (3 * b + 2)) & 1) << b
    return (y << 10) | (z << 5) | x


# ---------------------------------------------------------------------------
# Serialization helpers (SaveLoadSystem3 layout)
# ---------------------------------------------------------------------------
# The true on-disk format for SaveLoadSystem3 (little-endian, not big-endian)
# is documented in ``SaveLoadSystem3.java:serialize/deserialize`` and used by
# ``python/voxel_tree/tasks/voxy_reader.py``. This module provides helpers to
# round-trip through YZX linear vs Morton order so tests can prove correctness
# regardless of whether a given storage backend uses Morton ordering.


def xyz_to_yxz_index_placeholder():  # pragma: no cover
    """Intentionally not defined — YZX helper is :func:`yzx_index`.

    Placeholder to make grep for 'XYZ' find this module.
    """
    return None
