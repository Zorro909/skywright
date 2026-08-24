from __future__ import annotations

import json
import struct
from io import BytesIO

import pytest
from PIL import Image

from skywright._mds_decoding import validate_encoded_value


def _image(format_name: str) -> bytes:
    output = BytesIO()
    Image.new("RGB", (1, 1), (20, 40, 60)).save(output, format=format_name)
    return output.getvalue()


def _sequence(*values: bytes, placeholder: bool) -> bytes:
    prefix = struct.pack("<I", 0) if placeholder else b""
    return (
        prefix
        + struct.pack("<I", len(values))
        + struct.pack(f"<{len(values)}I", *(len(value) for value in values))
        + b"".join(values)
    )


@pytest.mark.parametrize(
    ("encoding", "value"),
    [
        ("bytes", b"anything"),
        ("str", "é".encode()),
        ("int", b"\0" * 8),
        ("uint8", b"\0"),
        ("uint16", b"\0" * 2),
        ("uint32", b"\0" * 4),
        ("uint64", b"\0" * 8),
        ("int8", b"\0"),
        ("int16", b"\0" * 2),
        ("int32", b"\0" * 4),
        ("int64", b"\0" * 8),
        ("float16", b"\0" * 2),
        ("float32", b"\0" * 4),
        ("float64", b"\0" * 8),
        ("str_int", b"-12345678901234567890"),
        ("str_float", b"1.25e10"),
        ("str_decimal", b"1.234567890123456789"),
        ("json", json.dumps({"safe": True}).encode()),
        ("ndarray", bytes([8, 4, 2, 1, 2])),
        ("ndarray:uint8", bytes([4, 2, 1, 2])),
        ("ndarray:uint8:2", bytes([1, 2])),
        ("pil", struct.pack("<III", 1, 1, 3) + b"RGB" + bytes([20, 40, 60])),
        ("jpeg", _image("JPEG")),
        ("png", _image("PNG")),
        ("jpeg_array", _sequence(_image("JPEG"), placeholder=False)),
        ("jpegarray", _sequence(_image("JPEG"), placeholder=False)),
        (
            "list[pil]",
            _sequence(struct.pack("<III", 1, 1, 1) + b"L" + b"\0", placeholder=True),
        ),
        ("list[jpeg]", _sequence(_image("JPEG"), placeholder=True)),
        ("list[png]", _sequence(_image("PNG"), placeholder=True)),
    ],
)
def test_every_advertised_safe_encoding_decodes(encoding: str, value: bytes) -> None:
    validate_encoded_value(encoding, value)
