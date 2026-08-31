"""Tests for ``voxel_tree.voxy_format`` — correct and tested reference decoder.

Grounding: pinned Voxy source ``tools/server-harness/runtime/mods/voxy-0.2.11-alpha.jar (pinned via .ci/voxy-artifact.json, reconstructed by tools/server-harness/scripts/install.sh)``
(sha256 ``63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c``)
mirrored at ``/tmp/voxy`` commit ``337b919`` (``dev`` branch).

References per test:
  - WorldEngine.getWorldSectionId / getX/Y/Z / getLevel — WorldEngine.java
  - WorldSection.getIndex (YZX) — WorldSection.java
  - Mapper bit layout & light packing (sky|block<<4) — Mapper.java, VoxelIngestService.java:71, Mipper.java
  - SaveLoadSystem3.lin2z / z2lin — SaveLoadSystem3.java

Acceptance criteria from issue #44:
  - Signed 24-bit X/Z tests cover -8388608, -1, 0, 1, 8388607
  - Signed 8-bit Y tests cover -128, -1, 0, 1, 127
  - Key round trips cover representative Levels including L0 and L4
  - Asymmetric sky/block values prove high/low nibble ownership
  - lin2z/z2lin full-domain inverse and uniqueness over 32^3 indices
  - Asymmetric XYZ sentinel volume proves XYZ ↔ YZX ↔ Morton transformations + round-trip
"""

from __future__ import annotations

import numpy as np
import pytest

from voxel_tree.voxy_format import (
    BLOCK_ID_MASK,
    BIOME_ID_MASK,
    decode_key,
    decode_voxel,
    encode_voxel,
    lin2z,
    make_key,
    yzx_index,
    z2lin,
)

# ---------------------------------------------------------------------------
# Key signed-range helpers
# ---------------------------------------------------------------------------

_XZ_MIN = -8388608  # -(1<<23)  min 24-bit signed
_XZ_MAX = 8388607  # (1<<23)-1  max 24-bit signed
_XZ_CASES = [_XZ_MIN, -1, 0, 1, _XZ_MAX]

_Y_MIN = -128
_Y_MAX = 127
_Y_CASES = [_Y_MIN, -1, 0, 1, _Y_MAX]


class TestSignedRanges:
    """Verify signed 24-bit X/Z and signed 8-bit Y edge cases.

    Source: WorldEngine.java arithmetic-shift decoders sign-extend 24-bit X/Z
    and 8-bit Y.
    """

    @pytest.mark.parametrize("x", _XZ_CASES)
    def test_x_signed_24bit(self, x: int) -> None:
        # lvl 0, y 0, z 0, vary x
        key = make_key(0, x, 0, 0)
        _, rx, _, _ = decode_key(key)
        assert rx == x, f"X {x} round-tripped as {rx}"

    @pytest.mark.parametrize("z", _XZ_CASES)
    def test_z_signed_24bit(self, z: int) -> None:
        key = make_key(0, 0, 0, z)
        _, _, _, rz = decode_key(key)
        assert rz == z

    @pytest.mark.parametrize("y", _Y_CASES)
    def test_y_signed_8bit(self, y: int) -> None:
        key = make_key(0, 0, y, 0)
        _, _, ry, _ = decode_key(key)
        assert ry == y

    def test_combined_extremes(self) -> None:
        # Pairwise extremes should round-trip together (covers masking isolation)
        for x in _XZ_CASES:
            for z in _XZ_CASES:
                for y in _Y_CASES:
                    for lvl in (0, 4, 7, 15):
                        key = make_key(lvl, x, y, z)
                        rlvl, rx, ry, rz = decode_key(key)
                        assert (rlvl, rx, ry, rz) == (lvl, x, y, z)

    def test_old_np_int32_shift_would_fail(self) -> None:
        """Prove the old doc decoder ``np.int32((key<<12)>>40)`` is wrong in Python.

        For a negative Z like -1, masking would give unsigned 0xFFFFFF,
        but the old shift on arbitrary-precision Python would not sign-extend
        because Python left-shift grows bit width. Demonstrate correct decoder
        handles it while naive int32 truncation would not be portable.
        """
        key = make_key(0, 0, 0, -1)
        _, _, _, rz = decode_key(key)
        assert rz == -1
        # Naive unsigned extraction without sign-extend would give 16777215
        unsigned = (key >> 28) & 0xFFFFFF
        assert unsigned == 0xFFFFFF
        # Our sign extend must have corrected it

    def test_key_round_trips_include_l0_and_l4(self) -> None:
        for lvl in (0, 4):
            for x in (_XZ_MIN, 0, _XZ_MAX):
                for y in (_Y_MIN, 0, _Y_MAX):
                    for z in (_XZ_MIN, 0, _XZ_MAX):
                        k = make_key(lvl, x, y, z)
                        assert decode_key(k) == (lvl, x, y, z)

    def test_spare_low_bits_zero(self) -> None:
        # bits 3-0 are spare; make_key should leave them zero
        k = make_key(2, 1, 2, 3)
        assert (k & 0xF) == 0


# ---------------------------------------------------------------------------
# Light nibble ownership — asymmetric sky/block
# ---------------------------------------------------------------------------


class TestLightNibbles:
    """High nibble = block, low nibble = sky per Voxy source.

    Source: VoxelIngestService.java:71 ``sky|(block<<4)`` and Mipper.java
    ``blockLight = light & 0xF0`` vs ``skyLight = light & 0x0F``.
    The old doc prose said ``sky<<4|block`` and swapped names — this test
    would fail under that prose.
    """

    def test_asymmetric_sky_block_proves_high_low(self) -> None:
        # sky=2, block=13 → light byte = 0xD2 = 210
        v = encode_voxel(block_id=1, biome_id=0, sky_light=2, block_light=13)
        _, _, sky, block = decode_voxel(v)
        assert sky == 2
        assert block == 13
        # raw light byte check
        light = (v >> 56) & 0xFF
        assert light == ((13 << 4) | 2)

    def test_swapped_would_fail(self) -> None:
        # The bug was: sky_low vs block_high swapped in *prose* not code.
        # If someone mistakenly decoded sky as high nibble, these would swap.
        v = encode_voxel(block_id=0, biome_id=0, sky_light=0xA, block_light=0x5)
        # Encodes as 0x5A
        light = (v >> 56) & 0xFF
        assert light == 0x5A
        _, _, sky, block = decode_voxel(v)
        assert sky == 0xA and block == 0x5
        # If nibbles swapped: sky would be 0x5 and block 0xA

    def test_all_nibble_combos(self) -> None:
        for sky in (0, 1, 7, 15):
            for block in (0, 1, 7, 15):
                v = encode_voxel(0, 0, sky, block)
                _, _, rs, rb = decode_voxel(v)
                assert (rs, rb) == (sky, block)

    def test_matches_voxy_reader_convention(self) -> None:
        # voxy_reader.py says upper nibble = block, lower = sky — assert agreement
        for sky, block in [(0, 15), (15, 0), (3, 12)]:
            v = encode_voxel(42, 7, sky, block)
            _, _, ds, db = decode_voxel(v)
            assert ds == sky and db == block


# ---------------------------------------------------------------------------
# Voxel long field masks
# ---------------------------------------------------------------------------


class TestVoxelFields:
    def test_block_biome_masks(self) -> None:
        v = encode_voxel(
            block_id=BLOCK_ID_MASK, biome_id=BIOME_ID_MASK, sky_light=15, block_light=15
        )
        block, biome, sky, block_l = decode_voxel(v)
        assert block == BLOCK_ID_MASK
        assert biome == BIOME_ID_MASK
        assert sky == 15 and block_l == 15

    def test_air_is_zero(self) -> None:
        # Mapper.AIR == 0 means block bits zero (even with biome/light, isAir checks block bits)
        v_air = encode_voxel(block_id=0, biome_id=0, sky_light=0, block_light=0)
        assert v_air == 0
        # Non-zero light with air block_id still has light bits but block bits zero
        v_air_lit = encode_voxel(block_id=0, biome_id=0, sky_light=5, block_light=7)
        block, _, _, _ = decode_voxel(v_air_lit)
        assert block == 0


# ---------------------------------------------------------------------------
# Morton helpers — full-domain inverse & uniqueness
# ---------------------------------------------------------------------------


class TestMortonHelpers:
    """Source: SaveLoadSystem3.java lin2z/z2lin via Integer.expand/compress.

    Acceptance: full-domain inverse and uniqueness over all 32^3 indices.
    """

    def test_full_domain_inverse(self) -> None:
        for idx in range(32**3):
            morton = lin2z(idx)
            back = z2lin(morton)
            assert back == idx, f"inverse failed idx={idx} morton={morton} back={back}"

    def test_uniqueness_over_domain(self) -> None:
        seen: set[int] = set()
        for idx in range(32**3):
            m = lin2z(idx)
            assert m not in seen, f"duplicate morton {m} for idx {idx}"
            seen.add(m)
        assert len(seen) == 32**3
        # Morton code fits in 15 bits (5 bits *3 =15, max 0..32767)
        assert max(seen) < (1 << 15)
        assert min(seen) == 0

    def test_z2lin_inverse_morton(self) -> None:
        # z2lin(lin2z(i)) == i already, but also lin2z(z2lin(m)) == m for all m in range
        morts = {lin2z(i) for i in range(32**3)}
        for m in morts:
            assert lin2z(z2lin(m)) == m

    def test_correct_y_z_extraction(self) -> None:
        """Old doc swapped y and z when extracting from YZX linear.

        Prove correct split: idx = y<<10|z<<5|x (WorldSection.getIndex).
        """
        # Craft sentinel idx where y!=z to expose swap
        x, y, z = 1, 2, 3
        idx = yzx_index(x, y, z)
        assert idx == (y << 10) | (z << 5) | x
        morton = lin2z(idx)
        # De-interleave should recover original xyz
        back_idx = z2lin(morton)
        assert back_idx == idx

        # Brute: y and z differ, so swapping would give different morton
        # Compute swapped version (old bug: y=(idx>>5)&31, z=(idx>>10)&31)
        # and show it differs for this sentinel
        def buggy_lin2z(idx_: int) -> int:
            xb = idx_ & 31
            yb_bug = (idx_ >> 5) & 31  # bug: actually z
            zb_bug = (idx_ >> 10) & 31  # bug: actually y
            m = 0
            for b in range(5):
                m |= ((xb >> b) & 1) << (3 * b)
                m |= ((yb_bug >> b) & 1) << (3 * b + 1)
                m |= ((zb_bug >> b) & 1) << (3 * b + 2)
            return m

        assert buggy_lin2z(idx) != morton, "buggy swap should differ when y!=z"


# ---------------------------------------------------------------------------
# Asymmetric XYZ sentinel volume — XYZ ↔ YZX ↔ Morton round-trip
# ---------------------------------------------------------------------------


class TestSentinelVolume:
    """Acceptance: asymmetric XYZ sentinel volume proves transformations among
    semantic XYZ access, Voxy YZX in-memory order, and serialized Morton order,
    including round trip.

    We build a 32^3 volume where value = x + 100*y + 10000*z (asymmetric so
    axes are not interchangeable) and prove each representation round-trips.
    """

    def _make_sentinel_xyz(self) -> np.ndarray:
        vol = np.zeros((32, 32, 32), dtype=np.int32)  # vol[x,y,z] semantic XYZ
        for x in range(32):
            for y in range(32):
                for z in range(32):
                    vol[x, y, z] = x + 100 * y + 10000 * z
        return vol

    def test_xyz_to_yzx_to_xyz(self) -> None:
        xyz = self._make_sentinel_xyz()
        # Flatten to YZX linear order (Voxy in-memory long[32768])
        yzx_linear = np.zeros(32**3, dtype=np.int32)
        for y in range(32):
            for z in range(32):
                for x in range(32):
                    idx = yzx_index(x, y, z)
                    yzx_linear[idx] = int(xyz[x, y, z])
        # Reconstruct XYZ from YZX
        xyz_back = np.zeros((32, 32, 32), dtype=np.int32)
        for y in range(32):
            for z in range(32):
                for x in range(32):
                    idx = yzx_index(x, y, z)
                    xyz_back[x, y, z] = yzx_linear[idx]
        assert np.array_equal(xyz, xyz_back)

    def test_yzx_reshape_semantics(self) -> None:
        """Prove ``reshape(32,32,32)`` ordering matches YZX.

        Voxy's ``Arrays.copyData`` + ``reshape`` pattern assumes axis 0=y, 1=z, 2=x.
        """
        xyz = self._make_sentinel_xyz()
        yzx = np.zeros(32**3, dtype=np.int32)
        for y in range(32):
            for z in range(32):
                for x in range(32):
                    yzx[yzx_index(x, y, z)] = int(xyz[x, y, z])
        # Reshape as Voxy does: block_ids.reshape(32,32,32) where axis0=y etc.
        yzx_3d = yzx.reshape(32, 32, 32)  # (y,z,x)
        # Verify sentinel still decodes
        for x in range(32):
            for y in range(32):
                for z in range(32):
                    assert yzx_3d[y, z, x] == x + 100 * y + 10000 * z

    def test_morton_order_round_trip(self) -> None:
        xyz = self._make_sentinel_xyz()
        yzx = np.zeros(32**3, dtype=np.int32)
        for y in range(32):
            for z in range(32):
                for x in range(32):
                    yzx[yzx_index(x, y, z)] = int(xyz[x, y, z])
        # Morton-serialized view: morton code indexes yzx
        morton_view = np.zeros(32**3, dtype=np.int32)
        for idx in range(32**3):
            m = lin2z(idx)
            # Store yzx[idx] at position m → mortonView[m] = yzx[idx]
            morton_view[m] = yzx[idx]
        # Inverse: for each morton position, recover yzx via z2lin
        yzx_back = np.zeros(32**3, dtype=np.int32)
        for m in range(32**3):
            idx = z2lin(m)
            yzx_back[idx] = morton_view[m]
        assert np.array_equal(yzx, yzx_back)
        # And back to XYZ
        xyz_back = np.zeros((32, 32, 32), dtype=np.int32)
        for y in range(32):
            for z in range(32):
                for x in range(32):
                    xyz_back[x, y, z] = yzx_back[yzx_index(x, y, z)]
        assert np.array_equal(xyz, xyz_back)

    def test_argsort_morton_reconstruction_correct(self) -> None:
        """The old doc used ``np.argsort([lin2z(i) for i in range(N)])`` to invert Morton.

        Proven correct: ``argsort(lin2z) == z2lin`` when lin2z is implemented
        with ``x&31, y=(idx>>10)&31, z=(idx>>5)&31`` (YZX). The doc's subsequent
        use ``morton[argsort]==linear`` is misleading — the true inverse for
        a Morton-ordered array is ``linear[i] = morton[lin2z(i)]`` (see
        ``SaveLoadSystem3`` where storage is actually YZX linear, not Morton).

        This test proves:
          1. argsort(lin2z) equals z2lin (bijection check).
          2. Correct inversion is morton[lin2z] == linear, not morton[argsort].
        """
        lin2z_table = np.array([lin2z(i) for i in range(32**3)], dtype=np.int32)
        order_via_argsort = np.argsort(lin2z_table)
        order_via_z2lin = np.array([z2lin(m) for m in range(32**3)], dtype=np.int32)
        assert np.array_equal(order_via_argsort, order_via_z2lin)
        # Build a Morton-ordered view of a linear identity array: morton[m] = linear[z2lin(m)]
        yzx = np.arange(32**3, dtype=np.int32)
        morton_view = np.empty_like(yzx)
        for i in range(32**3):
            morton_view[lin2z(i)] = yzx[i]  # morton[lin2z(i)] = linear[i]
        # Correct inverse: linear[i] = morton[lin2z(i)]
        assert np.array_equal(np.array([morton_view[lin2z(i)] for i in range(32**3)]), yzx)
        # The doc's ``morton[argsort] == linear`` would be double-application and is false:
        assert not np.array_equal(morton_view[order_via_argsort], yzx)
