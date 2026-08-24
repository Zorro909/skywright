"""Bounded structural validation for MosaicML Streaming MDS v2 shards."""

from __future__ import annotations

import bz2
import gzip
import hashlib
import json
import os
import struct
from collections.abc import Callable
from typing import BinaryIO, Protocol, cast

from ._dataset_errors import DatasetPublicationError, metadata_error, publication_error

CHUNK_BYTES = 1024 * 1024
MAX_CONFIGURATION_BYTES = 1024 * 1024
SUPPORTED_COMPRESSIONS = frozenset(
    {"br", "bz2", "gz", "snappy", "zstd"}
    | {f"br:{level}" for level in range(12)}
    | {f"bz2:{level}" for level in range(1, 10)}
    | {f"gz:{level}" for level in range(10)}
    | {f"zstd:{level}" for level in range(1, 23)}
)
_SAFE_ENCODINGS = frozenset(
    {
        "bytes",
        "str",
        "int",
        "ndarray",
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
        "str_int",
        "str_float",
        "str_decimal",
        "pil",
        "jpeg",
        "jpeg_array",
        "jpegarray",
        "png",
        "list[pil]",
        "list[jpeg]",
        "list[png]",
        "json",
    }
)
_FIXED_ENCODING_BYTES = {
    "int": 8,
    "uint8": 1,
    "uint16": 2,
    "uint32": 4,
    "uint64": 8,
    "int8": 1,
    "int16": 2,
    "int32": 4,
    "int64": 8,
    "float16": 2,
    "float32": 4,
    "float64": 8,
}


class _ReadableBytes(Protocol):
    def read(self, size: int = -1, /) -> bytes: ...


class _BrotliDecoder(Protocol):
    def process(self, value: bytes, /) -> bytes: ...

    def is_finished(self) -> bool: ...


class _Hasher(Protocol):
    def update(self, value: bytes, /) -> object: ...

    def hexdigest(self) -> str: ...


def validate_columns(shard: dict[str, object]) -> None:
    names_value = shard.get("column_names")
    encodings_value = shard.get("column_encodings")
    sizes_value = shard.get("column_sizes")
    if not all(
        isinstance(value, list) for value in (names_value, encodings_value, sizes_value)
    ):
        raise metadata_error("The MDS decoding metadata is malformed")
    names = cast(list[object], names_value)
    encodings = cast(list[object], encodings_value)
    sizes = cast(list[object], sizes_value)
    if (
        not names
        or len(names) != len(encodings)
        or len(names) != len(sizes)
        or not all(isinstance(name, str) and name for name in names)
        or len(set(cast(list[str], names))) != len(names)
        or not all(isinstance(encoding, str) for encoding in encodings)
        or not all(size is None or is_nonnegative_int(size) for size in sizes)
    ):
        raise metadata_error("The MDS decoding metadata is malformed")
    for encoding_value, size in zip(encodings, sizes, strict=True):
        encoding = cast(str, encoding_value)
        if encoding == "pkl":
            raise publication_error(
                "DATASET_CORPUS_UNSAFE_ENCODING",
                "The MDS corpus uses an unsafe executable serialization encoding",
            )
        expected_size = _encoding_size(encoding)
        if expected_size is False:
            raise publication_error(
                "DATASET_MDS_ENCODING_UNSUPPORTED",
                "The MDS corpus uses an unsupported column encoding",
            )
        if size != expected_size:
            raise metadata_error(
                "An MDS column size does not match its declared encoding"
            )


def _encoding_size(encoding: str) -> int | bool | None:
    if encoding in _FIXED_ENCODING_BYTES:
        return _FIXED_ENCODING_BYTES[encoding]
    if encoding in _SAFE_ENCODINGS:
        return None
    if not encoding.startswith("ndarray:"):
        return False
    fields = encoding.split(":")
    if len(fields) not in {2, 3}:
        return False
    dtype_bytes = {
        "uint8": 1,
        "int8": 1,
        "uint16": 2,
        "int16": 2,
        "float16": 2,
        "uint32": 4,
        "int32": 4,
        "float32": 4,
        "uint64": 8,
        "int64": 8,
        "float64": 8,
    }.get(fields[1])
    if dtype_bytes is None:
        return False
    if len(fields) == 2:
        return None
    try:
        dimensions = [int(item) for item in fields[2].split(",")]
    except ValueError:
        return False
    if not dimensions or any(item < 1 for item in dimensions):
        return False
    result = dtype_bytes
    for dimension in dimensions:
        result *= dimension
    return result


def file_descriptor(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise metadata_error("The MDS shard file descriptor is malformed")
    descriptor = cast(dict[str, object], value)
    hashes = descriptor.get("hashes")
    hash_values = (
        cast(dict[object, object], hashes) if isinstance(hashes, dict) else None
    )
    if (
        not isinstance(descriptor.get("basename"), str)
        or not is_nonnegative_int(descriptor.get("bytes"))
        or hash_values is None
        or not all(
            isinstance(key, str) and isinstance(digest, str)
            for key, digest in hash_values.items()
        )
    ):
        raise metadata_error("The MDS shard file descriptor is malformed")
    return descriptor


def validate_hash_names(value: list[str]) -> None:
    import xxhash

    xxhash_names: set[str] = xxhash.algorithms_available
    available = {
        name for name in hashlib.algorithms_available if not name.startswith("shake_")
    } | xxhash_names
    if any(name not in available for name in value):
        raise metadata_error("The MDS shard declares an unsupported hash algorithm")


def validate_descriptor_hashes(
    descriptor: dict[str, object], hash_names: list[str]
) -> None:
    hashes = cast(dict[str, str], descriptor["hashes"])
    if set(hashes) != set(hash_names):
        raise metadata_error("The MDS shard digest metadata is malformed")
    if any(
        not digest or any(character not in "0123456789abcdef" for character in digest)
        for digest in hashes.values()
    ):
        raise metadata_error("The MDS shard digest metadata is malformed")


def validate_stream_hashes(
    stream: BinaryIO, descriptor: dict[str, object], hash_names: list[str]
) -> None:
    expected = cast(dict[str, str], descriptor["hashes"])
    hashers = {name: _new_hasher(name) for name in hash_names}
    while chunk := stream.read(CHUNK_BYTES):
        for hasher in hashers.values():
            hasher.update(chunk)
    stream.seek(0)
    if any(hashers[name].hexdigest() != expected[name] for name in hash_names):
        raise publication_error(
            "DATASET_MDS_DIGEST_METADATA_MISMATCH",
            "An MDS shard digest does not match index.json",
        )


def _new_hasher(name: str) -> _Hasher:
    if name in hashlib.algorithms_available and not name.startswith("shake_"):
        return cast(_Hasher, hashlib.new(name))
    import xxhash

    constructor = cast(Callable[[], _Hasher], getattr(xxhash, name))
    return constructor()


def decompress(source: BinaryIO, target: BinaryIO, compression: str | None) -> int:
    try:
        if compression is None:
            decoded: _ReadableBytes = source
        elif compression.split(":", 1)[0] == "gz":
            decoded = gzip.GzipFile(fileobj=source)
        elif compression.split(":", 1)[0] == "bz2":
            decoded = bz2.BZ2File(source)
        else:
            return _decompress_optional(source, target, compression)
        return _copy_decoded(decoded, target)
    except (OSError, EOFError) as error:
        raise metadata_error("The compressed MDS shard is malformed") from error


def _decompress_optional(source: BinaryIO, target: BinaryIO, compression: str) -> int:
    family = compression.split(":", 1)[0]
    if family == "br":
        return _decompress_brotli(source, target)
    if family == "snappy":
        try:
            return _decompress_snappy(source, target)
        except ValueError as error:
            raise metadata_error("The compressed MDS shard is malformed") from error
    return _decompress_zstandard(source, target)


def _decompress_brotli(source: BinaryIO, target: BinaryIO) -> int:
    import brotli  # pyright: ignore[reportMissingTypeStubs]

    try:
        decoder = cast(
            _BrotliDecoder,
            brotli.Decompressor(),  # pyright: ignore[reportUnknownMemberType]
        )
        total = 0
        while chunk := source.read(CHUNK_BYTES):
            decoded = decoder.process(chunk)
            target.write(decoded)
            total += len(decoded)
        if not decoder.is_finished():
            raise ValueError("truncated Brotli stream")
        target.seek(0)
        return total
    except brotli.error:  # pyright: ignore[reportUnknownMemberType]
        raise metadata_error("The compressed MDS shard is malformed") from None
    except ValueError as error:
        raise metadata_error("The compressed MDS shard is malformed") from error


def _decompress_zstandard(source: BinaryIO, target: BinaryIO) -> int:
    import zstandard

    try:
        with zstandard.ZstdDecompressor().stream_reader(
            source, closefd=False
        ) as reader:
            return _copy_decoded(reader, target)
    except (zstandard.ZstdError, OSError, EOFError) as error:
        raise metadata_error("The compressed MDS shard is malformed") from error


def _copy_decoded(source: _ReadableBytes, target: BinaryIO) -> int:
    total = 0
    while chunk := source.read(CHUNK_BYTES):
        target.write(chunk)
        total += len(chunk)
    target.seek(0)
    return total


def _decompress_snappy(source: BinaryIO, target: BinaryIO) -> int:
    expected = _snappy_varint(source)
    written = 0
    while written < expected:
        tag_bytes = source.read(1)
        if not tag_bytes:
            raise ValueError("truncated Snappy tag")
        tag = tag_bytes[0]
        kind = tag & 3
        if kind == 0:
            encoded_length = tag >> 2
            if encoded_length < 60:
                length = encoded_length + 1
            else:
                width = encoded_length - 59
                length_bytes = source.read(width)
                if len(length_bytes) != width:
                    raise ValueError("truncated Snappy literal length")
                length = int.from_bytes(length_bytes, "little") + 1
            written += _copy_exact(source, target, length)
            continue
        if kind == 1:
            length = 4 + ((tag >> 2) & 7)
            offset_bytes = source.read(1)
            if not offset_bytes:
                raise ValueError("truncated Snappy copy offset")
            offset = ((tag & 0xE0) << 3) | offset_bytes[0]
        else:
            length = 1 + (tag >> 2)
            width = 2 if kind == 2 else 4
            offset_bytes = source.read(width)
            if len(offset_bytes) != width:
                raise ValueError("truncated Snappy copy offset")
            offset = int.from_bytes(offset_bytes, "little")
        if offset < 1 or offset > written:
            raise ValueError("invalid Snappy copy offset")
        _copy_from_output(target, offset, length)
        written += length
    if written != expected or source.read(1):
        raise ValueError("invalid Snappy decoded length")
    target.seek(0)
    return written


def _snappy_varint(source: BinaryIO) -> int:
    result = 0
    for shift in range(0, 35, 7):
        value = source.read(1)
        if not value:
            raise ValueError("truncated Snappy length")
        result |= (value[0] & 0x7F) << shift
        if value[0] < 0x80:
            return result
    raise ValueError("invalid Snappy length")


def _copy_exact(source: BinaryIO, target: BinaryIO, count: int) -> int:
    remaining = count
    while remaining:
        chunk = source.read(min(CHUNK_BYTES, remaining))
        if not chunk:
            raise ValueError("truncated compressed shard")
        target.write(chunk)
        remaining -= len(chunk)
    return count


def _copy_from_output(target: BinaryIO, offset: int, count: int) -> None:
    remaining = count
    while remaining:
        end = target.seek(0, os.SEEK_END)
        target.seek(end - offset)
        chunk = target.read(min(remaining, offset, CHUNK_BYTES))
        if not chunk:
            raise ValueError("invalid compressed shard back-reference")
        target.seek(0, os.SEEK_END)
        target.write(chunk)
        remaining -= len(chunk)


def validate_binary(stream: BinaryIO, file_size: int, shard: dict[str, object]) -> None:
    samples = cast(int, shard["samples"])
    names = cast(list[object], shard["column_names"])
    encodings = cast(list[object], shard["column_encodings"])
    sizes = cast(list[object], shard["column_sizes"])
    try:
        observed = stream.read(4)
        if len(observed) != 4 or struct.unpack("<I", observed)[0] != samples:
            raise publication_error(
                "DATASET_MDS_SAMPLE_METADATA_MISMATCH",
                "The MDS shard sample count does not match index.json",
            )
        header_size = 4 * (samples + 2)
        if file_size < header_size:
            raise metadata_error("The MDS shard header is truncated")
        first_offset_bytes = stream.read(4)
        if len(first_offset_bytes) != 4:
            raise metadata_error("The MDS shard offsets are truncated")
        first_offset = struct.unpack("<I", first_offset_bytes)[0]
        if first_offset < header_size:
            raise metadata_error("The MDS shard offsets are malformed")
        configuration_size = first_offset - header_size
        if configuration_size > MAX_CONFIGURATION_BYTES:
            raise metadata_error(
                "The MDS shard configuration exceeds the 1 MiB validation limit"
            )
        stream.seek(header_size)
        configuration = cast(
            object, json.loads(_read_exact(stream, configuration_size))
        )
        expected = {
            "column_encodings": encodings,
            "column_names": names,
            "column_sizes": sizes,
            "compression": shard.get("compression"),
            "format": "mds",
            "hashes": shard.get("hashes", []),
            "size_limit": shard.get("size_limit"),
            "version": 2,
        }
        if configuration != expected:
            raise metadata_error(
                "The MDS shard configuration does not match index.json"
            )
        start = first_offset
        for sample_index in range(samples):
            stream.seek(8 + 4 * sample_index)
            end_bytes = stream.read(4)
            if len(end_bytes) != 4:
                raise metadata_error("The MDS shard offsets are truncated")
            end = struct.unpack("<I", end_bytes)[0]
            if end < start or end > file_size:
                raise metadata_error("The MDS shard offsets are malformed")
            stream.seek(start)
            variable_count = sum(size is None for size in sizes)
            size_bytes = stream.read(4 * variable_count)
            if len(size_bytes) != 4 * variable_count:
                raise metadata_error("An MDS sample header is truncated")
            variable_sizes = iter(
                struct.unpack(f"<{variable_count}I", size_bytes)
                if variable_count
                else ()
            )
            value_sizes = [
                next(variable_sizes) if size is None else cast(int, size)
                for size in sizes
            ]
            if start + len(size_bytes) + sum(value_sizes) != end:
                raise metadata_error(
                    "An MDS sample does not match its decoding metadata"
                )
            for encoding, value_size in zip(encodings, value_sizes, strict=True):
                value = stream.read(value_size)
                if len(value) != value_size:
                    raise metadata_error("An MDS sample value is truncated")
                if encoding == "str":
                    value.decode("utf-8")
                elif encoding == "json":
                    json.loads(value)
            start = end
        if start != file_size:
            raise metadata_error("The MDS shard offsets are malformed")
    except DatasetPublicationError:
        raise
    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        struct.error,
        ValueError,
    ) as error:
        raise metadata_error("The MDS shard is not structurally decodable") from error


def _read_exact(stream: BinaryIO, count: int) -> bytes:
    value = stream.read(count)
    if len(value) != count:
        raise metadata_error("The MDS shard configuration is truncated")
    return value


def is_nonnegative_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0
