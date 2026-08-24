"""Safe value-level decoding checks for supported MosaicML MDS encodings."""

from __future__ import annotations

import json
import struct
from decimal import Decimal, InvalidOperation
from io import BytesIO

_DTYPES = {
    8: ("uint8", 1),
    9: ("int8", 1),
    16: ("uint16", 2),
    17: ("int16", 2),
    18: ("float16", 2),
    32: ("uint32", 4),
    33: ("int32", 4),
    34: ("float32", 4),
    64: ("uint64", 8),
    65: ("int64", 8),
    66: ("float64", 8),
}
_DTYPE_BYTES = {name: size for name, size in _DTYPES.values()}


class MDSValueDecodingError(ValueError):
    """One MDS value cannot be decoded by its advertised safe encoding."""


def validate_encoded_value(encoding: str, value: bytes) -> None:
    """Decode one value without executing user-controlled code."""
    try:
        _validate_encoded_value(encoding, value)
    except MDSValueDecodingError:
        raise
    except (
        UnicodeDecodeError,
        InvalidOperation,
        OSError,
        SyntaxError,
        ValueError,
        KeyError,
        IndexError,
        struct.error,
    ) as error:
        raise MDSValueDecodingError("The encoded MDS value is malformed") from error


def _validate_encoded_value(encoding: str, value: bytes) -> None:
    if encoding in {
        "bytes",
        "int",
        "uint8",
        "uint16",
        "uint32",
        "uint64",
        "int8",
        "int16",
        "int32",
        "int64",
        "float16",
        "float32",
        "float64",
    }:
        return
    if encoding == "str":
        value.decode("utf-8")
    elif encoding == "str_int":
        int(value.decode("utf-8"))
    elif encoding == "str_float":
        float(value.decode("utf-8"))
    elif encoding == "str_decimal":
        Decimal(value.decode("utf-8"))
    elif encoding == "json":
        json.loads(value)
    elif encoding == "pil":
        _validate_raw_image(value)
    elif encoding in {"jpeg", "png"}:
        _validate_image(value, encoding.upper())
    elif encoding in {"jpeg_array", "jpegarray"}:
        _validate_image_sequence(value, "jpeg", has_placeholder=False)
    elif encoding in {"list[pil]", "list[jpeg]", "list[png]"}:
        _validate_image_sequence(value, encoding[5:-1], has_placeholder=True)
    else:
        _validate_ndarray(encoding, value)


def _validate_ndarray(encoding: str, value: bytes) -> None:
    fields = encoding.split(":")
    dtype = fields[1] if len(fields) >= 2 else None
    shape = (
        tuple(int(item) for item in fields[2].split(",")) if len(fields) == 3 else None
    )
    position = 0
    if dtype is None:
        if not value or value[0] not in _DTYPES:
            raise ValueError("invalid ndarray dtype")
        dtype = _DTYPES[value[0]][0]
        position += 1
    if shape is None:
        if position >= len(value):
            raise ValueError("missing ndarray shape")
        shape_header = value[position]
        position += 1
        dimensions = shape_header >> 2
        shape_width = 2 ** (shape_header & 3)
        shape_bytes = dimensions * shape_width
        if dimensions == 0 or position + shape_bytes > len(value):
            raise ValueError("invalid ndarray shape")
        shape = tuple(
            int.from_bytes(value[offset : offset + shape_width], "little")
            for offset in range(position, position + shape_bytes, shape_width)
        )
        position += shape_bytes
    if not shape or any(item < 1 for item in shape):
        raise ValueError("invalid ndarray shape")
    elements = 1
    for dimension in shape:
        elements *= dimension
    expected = elements * _DTYPE_BYTES[dtype]
    if len(value) - position != expected:
        raise ValueError("invalid ndarray payload size")


def _validate_raw_image(value: bytes) -> None:
    from PIL import Image

    if len(value) < 12:
        raise ValueError("truncated raw image")
    width, height, mode_size = struct.unpack("<III", value[:12])
    mode_end = 12 + mode_size
    if width == 0 or height == 0 or mode_end > len(value):
        raise ValueError("invalid raw image dimensions")
    mode = value[12:mode_end].decode("utf-8")
    raw = value[mode_end:]
    image = Image.frombytes(mode, (width, height), raw)
    if len(image.tobytes()) != len(raw):
        raise ValueError("invalid raw image payload size")


def _validate_image(value: bytes, expected_format: str) -> None:
    from PIL import Image

    with Image.open(BytesIO(value)) as image:
        if image.format != expected_format:
            raise ValueError("unexpected image format")
        image.verify()


def _validate_image_sequence(
    value: bytes, item_encoding: str, *, has_placeholder: bool
) -> None:
    position = 4 if has_placeholder else 0
    if len(value) < position + 4:
        raise ValueError("truncated image sequence")
    count = struct.unpack("<I", value[position : position + 4])[0]
    position += 4
    if not has_placeholder and count == 0:
        raise ValueError("empty JPEG array")
    table_end = position + 4 * count
    if table_end > len(value):
        raise ValueError("truncated image sequence table")
    sizes = struct.unpack(f"<{count}I", value[position:table_end]) if count else ()
    position = table_end
    for size in sizes:
        end = position + size
        if end > len(value):
            raise ValueError("truncated image sequence item")
        _validate_encoded_value(item_encoding, value[position:end])
        position = end
    if position != len(value):
        raise ValueError("trailing image sequence bytes")
