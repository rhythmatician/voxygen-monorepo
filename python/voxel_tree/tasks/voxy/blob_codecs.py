from __future__ import annotations

import zlib
from typing import Final

import numpy as np

VOXY_LABELS_CODEC_LEGACY: Final[str] = "zlib-int32-v1"
VOXY_LABELS_CODEC_BITPACK_V1_PREFIX: Final[str] = "zlib-bitpack-v1:"
VOXY_LABELS_CODEC_DEFAULT: Final[str] = f"{VOXY_LABELS_CODEC_BITPACK_V1_PREFIX}11"


def compress_voxy_labels(
    labels32: np.ndarray,
    *,
    codec: str = VOXY_LABELS_CODEC_DEFAULT,
    compression_level: int = 1,
) -> bytes:
    """Encode a Voxy labels32 grid into a compressed storage blob.

    Supported codecs:
    - ``zlib-int32-v1``: legacy format, raw int32 bytes inside zlib.
    - ``zlib-bitpack-v1:<bits>``: non-negative integers packed to ``bits`` bits,
      then compressed with zlib.
    """
    arr = np.asarray(labels32)
    if codec == VOXY_LABELS_CODEC_LEGACY:
        payload = arr.astype(np.int32, copy=False).tobytes()
        return zlib.compress(payload, level=compression_level)

    bits = _parse_bitpack_codec(codec)
    flat = arr.reshape(-1)
    if flat.size == 0:
        payload = b""
    else:
        if np.any(flat < 0):
            raise ValueError("bit-packed Voxy labels must be non-negative")
        max_value = int(flat.max())
        if max_value >= (1 << bits):
            raise ValueError(f"values up to {max_value} do not fit in {bits} bits")
        values = flat.astype(np.uint16, copy=False)
        bit_matrix = ((values[:, None] >> np.arange(bits, dtype=np.uint16)) & 1).astype(np.uint8)
        payload = np.packbits(bit_matrix.reshape(-1), bitorder="little").tobytes()
    return zlib.compress(payload, level=compression_level)


def decompress_voxy_labels(
    blob: bytes,
    *,
    codec: str,
    shape: tuple[int, ...] = (32, 32, 32),
) -> np.ndarray:
    """Decode a compressed Voxy labels blob back to int32 grid form."""
    raw = zlib.decompress(blob)
    if codec == VOXY_LABELS_CODEC_LEGACY:
        return np.frombuffer(raw, dtype=np.int32).reshape(shape).copy()

    bits = _parse_bitpack_codec(codec)
    count = int(np.prod(shape, dtype=np.int64))
    if count == 0:
        return np.empty(shape, dtype=np.int32)

    packed = np.frombuffer(raw, dtype=np.uint8)
    unpacked = np.unpackbits(packed, bitorder="little")
    needed = count * bits
    if unpacked.size < needed:
        raise ValueError(
            f"packed Voxy labels blob is truncated: need {needed} bits, got {unpacked.size}"
        )
    bit_matrix = unpacked[:needed].reshape(count, bits)
    weights = 1 << np.arange(bits, dtype=np.uint32)
    values = (bit_matrix.astype(np.uint32) * weights).sum(axis=1, dtype=np.uint32)
    return values.astype(np.int32, copy=False).reshape(shape)


def is_bitpack_codec(codec: str) -> bool:
    return codec.startswith(VOXY_LABELS_CODEC_BITPACK_V1_PREFIX)


def _parse_bitpack_codec(codec: str) -> int:
    if not codec.startswith(VOXY_LABELS_CODEC_BITPACK_V1_PREFIX):
        raise ValueError(f"unsupported Voxy label codec: {codec}")
    bits_str = codec[len(VOXY_LABELS_CODEC_BITPACK_V1_PREFIX) :]
    try:
        bits = int(bits_str)
    except ValueError as exc:
        raise ValueError(f"invalid bit width in Voxy label codec: {codec}") from exc
    if bits <= 0 or bits > 16:
        raise ValueError(f"unsupported bit width for Voxy label codec: {codec}")
    return bits
