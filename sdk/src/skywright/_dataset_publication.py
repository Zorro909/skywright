"""Internal Dataset Publication implementation used by the datasets command."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import struct
from dataclasses import dataclass
from itertools import pairwise
from pathlib import Path, PurePosixPath
from typing import cast

FORMAT_IDENTITY = "mosaicml-streaming-mds@2"
MANIFEST_VERSION = "skywright-dataset-manifest@1"
_UNSAFE_ENCODINGS = frozenset({"pkl", "pickle"})
_SUPPORTED_ENCODINGS = frozenset({"bytes", "str"})


class DatasetPublicationError(Exception):
    """A safe, stable publication failure for command output."""

    def __init__(self, code: str, detail: str, *, retryable: bool = False) -> None:
        super().__init__(detail)
        self.code = code
        self.detail = detail
        self.retryable = retryable


@dataclass(frozen=True)
class ManifestEntry:
    object_key: str
    byte_count: int
    checksum_sha256: str
    source: Path


@dataclass(frozen=True)
class InspectedCorpus:
    format_identity: str
    entries: tuple[ManifestEntry, ...]
    manifest_bytes: bytes
    manifest_identity: str
    content_fingerprint: str

    @property
    def object_count(self) -> int:
        return len(self.entries)

    @property
    def byte_count(self) -> int:
        return sum(entry.byte_count for entry in self.entries)


def inspect_mds_corpus(root: Path) -> InspectedCorpus:
    """Validate and fingerprint one storage-ready, single-shard MDS corpus."""
    try:
        root = root.resolve(strict=True)
    except OSError as error:
        raise _invalid("The local corpus does not exist or cannot be read") from error
    if not root.is_dir():
        raise _invalid("The local corpus must be a directory")
    index_path = root / "index.json"
    _require_regular(index_path)
    try:
        index = cast(object, json.loads(index_path.read_bytes()))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise _invalid("index.json must contain valid UTF-8 JSON") from error
    if not isinstance(index, dict):
        raise _invalid("index.json must contain an object")
    typed_index = cast(dict[str, object], index)
    if typed_index.get("version") != 2:
        raise _invalid("index.json must use MosaicML Streaming index version 2")
    shards = typed_index.get("shards")
    if not isinstance(shards, list):
        raise _invalid("The initial publication format requires exactly one MDS shard")
    typed_shards = cast(list[object], shards)
    if len(typed_shards) != 1:
        raise _invalid("The initial publication format requires exactly one MDS shard")
    shard = typed_shards[0]
    if not isinstance(shard, dict):
        raise _invalid("The shard format must be mds")
    typed_shard = cast(dict[str, object], shard)
    if typed_shard.get("format") != "mds":
        raise _invalid("The shard format must be mds")
    if typed_shard.get("version") != 2:
        raise _invalid("The MDS shard must use version 2")
    if typed_shard.get("compression") is not None:
        raise _invalid(
            "The initial publication format requires an uncompressed MDS shard"
        )
    _validate_columns(typed_shard)
    data = _selected_data(typed_shard)
    basename = data.get("basename")
    byte_count = data.get("bytes")
    if (
        not isinstance(basename, str)
        or not isinstance(byte_count, int)
        or byte_count < 0
    ):
        raise _invalid("The shard data descriptor is malformed")
    object_key = _safe_object_key(basename)
    shard_path = root / Path(*PurePosixPath(object_key).parts)
    _require_regular(shard_path)
    if shard_path.stat(follow_symlinks=False).st_size != byte_count:
        raise _invalid("The shard byte count does not match index.json")
    _validate_mds_binary(shard_path, typed_shard)

    expected = {"index.json", object_key}
    actual = _inventory(root)
    extras = actual - expected
    missing = expected - actual
    if extras or missing:
        raise _invalid("The corpus contains missing or unreferenced files")

    entries = tuple(
        _entry(root, key) for key in sorted(expected, key=lambda value: value.encode())
    )
    manifest = {
        "byteCount": sum(entry.byte_count for entry in entries),
        "format": FORMAT_IDENTITY,
        "objectCount": len(entries),
        "objects": [
            {
                "byteCount": entry.byte_count,
                "objectKey": entry.object_key,
                "sha256": entry.checksum_sha256,
            }
            for entry in entries
        ],
        "version": MANIFEST_VERSION,
    }
    manifest_bytes = json.dumps(
        manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode()
    manifest_identity = _digest(manifest_bytes)
    fingerprint_document = json.dumps(
        {
            "format": FORMAT_IDENTITY,
            "manifest": manifest_identity,
            "version": "skywright-dataset-content@1",
        },
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return InspectedCorpus(
        FORMAT_IDENTITY,
        entries,
        manifest_bytes,
        manifest_identity,
        _digest(fingerprint_document),
    )


def _validate_columns(shard: dict[str, object]) -> None:
    names = shard.get("column_names")
    encodings = shard.get("column_encodings")
    sizes = shard.get("column_sizes")
    if (
        not isinstance(names, list)
        or not isinstance(encodings, list)
        or not isinstance(sizes, list)
    ):
        raise _invalid("The MDS decoding metadata is malformed")
    typed_names = cast(list[object], names)
    typed_encodings = cast(list[object], encodings)
    typed_sizes = cast(list[object], sizes)
    if (
        not typed_names
        or not all(isinstance(name, str) and name for name in typed_names)
        or len(typed_encodings) != len(typed_names)
        or not all(isinstance(encoding, str) for encoding in typed_encodings)
        or len(typed_sizes) != len(typed_names)
        or not all(
            size is None or (isinstance(size, int) and size >= 0)
            for size in typed_sizes
        )
    ):
        raise _invalid("The MDS decoding metadata is malformed")
    if any(
        cast(str, encoding).lower() in _UNSAFE_ENCODINGS for encoding in typed_encodings
    ):
        raise DatasetPublicationError(
            "DATASET_CORPUS_UNSAFE_ENCODING",
            "The MDS corpus uses an unsafe executable serialization encoding",
        )
    if any(encoding not in _SUPPORTED_ENCODINGS for encoding in typed_encodings):
        raise _invalid("The MDS corpus uses an unsupported column encoding")
    samples = shard.get("samples")
    if not isinstance(samples, int) or samples < 0:
        raise _invalid("The MDS sample count is malformed")


def _validate_mds_binary(path: Path, shard: dict[str, object]) -> None:
    samples = cast(int, shard["samples"])
    names = cast(list[object], shard["column_names"])
    encodings = cast(list[object], shard["column_encodings"])
    sizes = cast(list[object], shard["column_sizes"])
    try:
        file_size = path.stat(follow_symlinks=False).st_size
        header_size = 4 * (samples + 2)
        if file_size < header_size:
            raise _invalid("The MDS shard header is truncated")
        with path.open("rb") as stream:
            observed_samples = struct.unpack("<I", stream.read(4))[0]
            if observed_samples != samples:
                raise _invalid("The MDS shard sample count does not match index.json")
            offsets = struct.unpack(f"<{samples + 1}I", stream.read(4 * (samples + 1)))
            if (
                offsets[0] < header_size
                or offsets[-1] != file_size
                or any(right < left for left, right in pairwise(offsets))
            ):
                raise _invalid("The MDS shard offsets are malformed")
            configuration_bytes = stream.read(offsets[0] - header_size)
            configuration = cast(object, json.loads(configuration_bytes))
            if not isinstance(configuration, dict):
                raise _invalid("The MDS shard configuration is malformed")
            typed_configuration = cast(dict[str, object], configuration)
            expected_configuration = {
                "column_encodings": encodings,
                "column_names": names,
                "column_sizes": sizes,
                "compression": None,
                "format": "mds",
                "hashes": shard.get("hashes", []),
                "size_limit": shard.get("size_limit"),
                "version": 2,
            }
            if typed_configuration != expected_configuration:
                raise _invalid("The MDS shard configuration does not match index.json")
            for sample_index in range(samples):
                start, end = offsets[sample_index], offsets[sample_index + 1]
                stream.seek(start)
                variable_count = sum(size is None for size in sizes)
                size_bytes = stream.read(4 * variable_count)
                if len(size_bytes) != 4 * variable_count:
                    raise _invalid("An MDS sample header is truncated")
                variable_sizes = iter(
                    struct.unpack(f"<{variable_count}I", size_bytes)
                    if variable_count
                    else ()
                )
                value_sizes = [
                    next(variable_sizes) if size is None else cast(int, size)
                    for size in sizes
                ]
                if any(size < 0 for size in value_sizes) or (
                    start + len(size_bytes) + sum(value_sizes) != end
                ):
                    raise _invalid("An MDS sample does not match its decoding metadata")
                for encoding, value_size in zip(encodings, value_sizes, strict=True):
                    value = stream.read(value_size)
                    if len(value) != value_size:
                        raise _invalid("An MDS sample value is truncated")
                    if encoding == "str":
                        value.decode("utf-8")
    except DatasetPublicationError:
        raise
    except (
        OSError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        struct.error,
        ValueError,
    ) as error:
        raise _invalid("The MDS shard is not structurally decodable") from error


def _selected_data(shard: dict[str, object]) -> dict[str, object]:
    compression = shard.get("compression")
    selected = (
        shard.get("zip_data") if compression is not None else shard.get("raw_data")
    )
    if not isinstance(selected, dict):
        raise _invalid("The MDS shard does not describe its published data object")
    return cast(dict[str, object], selected)


def _safe_object_key(value: str) -> str:
    path = PurePosixPath(value)
    if (
        not value
        or "\\" in value
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
        or path.as_posix() != value
    ):
        raise _invalid("The shard object path is unsafe")
    return value


def _inventory(root: Path) -> set[str]:
    result: set[str] = set()
    for directory, names, files in os.walk(root, followlinks=False):
        parent = Path(directory)
        for name in names:
            path = parent / name
            if path.is_symlink():
                raise _invalid(
                    "The corpus may contain only regular files and directories"
                )
        for name in files:
            path = parent / name
            _require_regular(path)
            relative = path.relative_to(root).as_posix()
            if relative in result:
                raise _invalid("The corpus contains duplicate object paths")
            result.add(relative)
    return result


def _require_regular(path: Path) -> None:
    try:
        mode = path.stat(follow_symlinks=False).st_mode
    except OSError as error:
        raise _invalid("The corpus contains a missing file") from error
    if not stat.S_ISREG(mode):
        raise _invalid("The corpus may contain only regular files")


def _entry(root: Path, key: str) -> ManifestEntry:
    source = root / Path(*PurePosixPath(key).parts)
    digest = hashlib.sha256()
    byte_count = 0
    try:
        with source.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
                byte_count += len(chunk)
    except OSError as error:
        raise _invalid("The corpus could not be read") from error
    return ManifestEntry(key, byte_count, f"sha256:{digest.hexdigest()}", source)


def _digest(value: bytes) -> str:
    return f"sha256:{hashlib.sha256(value).hexdigest()}"


def _invalid(detail: str) -> DatasetPublicationError:
    return DatasetPublicationError("DATASET_CORPUS_INVALID", detail)
