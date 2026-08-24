"""Internal Dataset Publication implementation used by the datasets command."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import tempfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, cast

from ._dataset_errors import DatasetPublicationError
from ._dataset_errors import metadata_error as _metadata
from ._dataset_errors import publication_error as _error
from ._mds_validation import SUPPORTED_COMPRESSIONS as _COMPRESSIONS
from ._mds_validation import decompress as _decompress
from ._mds_validation import file_descriptor as _file_descriptor
from ._mds_validation import is_nonnegative_int as _is_nonnegative_int
from ._mds_validation import validate_binary as _validate_mds_binary
from ._mds_validation import validate_columns as _validate_columns
from ._mds_validation import validate_descriptor_hashes as _validate_descriptor_hashes
from ._mds_validation import validate_hash_names as _validate_hash_names
from ._mds_validation import validate_stream_hashes as _validate_stream_hashes

FORMAT_IDENTITY = "mosaicml-streaming-mds@2"
MANIFEST_VERSION = "skywright-dataset-manifest@1"
_CONTENT_VERSION = "skywright-dataset-content@1"
_CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class SourceIdentity:
    device: int
    inode: int
    mode: int
    byte_count: int
    modified_ns: int
    changed_ns: int
    links: int

    @classmethod
    def from_stat(cls, value: os.stat_result) -> SourceIdentity:
        return cls(
            value.st_dev,
            value.st_ino,
            value.st_mode,
            value.st_size,
            value.st_mtime_ns,
            value.st_ctime_ns,
            value.st_nlink,
        )


@dataclass(frozen=True)
class ManifestEntry:
    object_key: str
    byte_count: int
    checksum_sha256: str
    source: Path
    source_identity: SourceIdentity


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


def inspect_mds_corpus(root: Path, *, concurrency: int = 4) -> InspectedCorpus:
    """Validate and fingerprint one storage-ready Streaming MDS v2 corpus."""
    root = _corpus_root(root)
    inventory = _inventory(root)
    root_index, root_identity = _read_index(root / "index.json")
    if root_index.get("version") != 2:
        raise _error(
            "DATASET_MDS_VERSION_UNSUPPORTED",
            "index.json must use MosaicML Streaming index version 2",
        )
    shards_value = root_index.get("shards")
    if not isinstance(shards_value, list):
        raise _metadata("index.json must contain a shards array")
    expected = {"index.json"}
    declared_paths = {"index.json"}
    identities = {"index.json": root_identity}
    partitions: dict[str, list[dict[str, object]]] = {}
    shard_values = cast(list[object], shards_value)

    def inspect(
        value: object,
    ) -> tuple[dict[str, object], str, SourceIdentity, frozenset[str]]:
        return _inspect_shard(root, value)

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        validated = executor.map(inspect, shard_values)
        inspected_shards = list(validated)
    for shard, object_key, identity, shard_paths in inspected_shards:
        if declared_paths & shard_paths:
            raise _path_error("The MDS index references a duplicate object path")
        declared_paths.update(shard_paths)
        expected.add(object_key)
        identities[object_key] = identity
        parent = object_key.rpartition("/")[0]
        if parent:
            partitions.setdefault(parent, []).append(shard)

    for partition, merged_shards in partitions.items():
        key = f"{partition}/index.json"
        if key not in inventory:
            continue
        partition_index, identity = _read_index(root / Path(key))
        if partition_index.get("version") != 2:
            raise _error(
                "DATASET_MDS_VERSION_UNSUPPORTED",
                "A partition index must use MosaicML Streaming index version 2",
            )
        local_shards = partition_index.get("shards")
        if (
            not isinstance(local_shards, list)
            or _qualify_partition(partition, cast(list[object], local_shards))
            != merged_shards
        ):
            raise _metadata(
                "A merged partition index does not match the root MDS index"
            )
        expected.add(key)
        identities[key] = identity

    if expected - inventory:
        raise _error(
            "DATASET_CORPUS_FILE_MISSING",
            "The MDS index references a missing corpus file",
        )
    if inventory - expected:
        raise _error(
            "DATASET_CORPUS_FILE_UNREFERENCED",
            "The corpus contains an unreferenced payload file",
        )
    keys = sorted(expected, key=lambda item: item.encode("utf-8"))

    def entry(key: str) -> ManifestEntry:
        return _entry(root, key, identities[key])

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        entries = tuple(executor.map(entry, keys))
    manifest_bytes = _manifest_bytes(entries)
    manifest_identity = _digest(manifest_bytes)
    fingerprint = json.dumps(
        {
            "format": FORMAT_IDENTITY,
            "manifest": manifest_identity,
            "version": _CONTENT_VERSION,
        },
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return InspectedCorpus(
        FORMAT_IDENTITY,
        entries,
        manifest_bytes,
        manifest_identity,
        _digest(fingerprint),
    )


def _corpus_root(root: Path) -> Path:
    try:
        value = root.lstat()
        resolved = root.resolve(strict=True)
    except OSError as error:
        raise _error(
            "DATASET_CORPUS_FILE_MISSING",
            "The local corpus does not exist or cannot be read",
        ) from error
    if stat.S_ISLNK(value.st_mode) or not stat.S_ISDIR(value.st_mode):
        raise _error(
            "DATASET_CORPUS_FILE_TYPE_INVALID",
            "The local corpus root must be a directory, not a link or special file",
        )
    return resolved


def _read_index(path: Path) -> tuple[dict[str, object], SourceIdentity]:
    stream, before = _open_regular(path)
    try:
        data = stream.read()
        after = SourceIdentity.from_stat(os.fstat(stream.fileno()))
    except OSError as error:
        raise _error(
            "DATASET_CORPUS_FILE_MISSING", "A corpus index could not be read"
        ) from error
    finally:
        stream.close()
    if before != after:
        raise _source_mutated()
    try:
        value = cast(object, json.loads(data))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise _metadata("A corpus index must contain valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise _metadata("A corpus index must contain a JSON object")
    return cast(dict[str, object], value), before


def _shard(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise _metadata("Every MDS shard descriptor must be an object")
    shard = cast(dict[str, object], value)
    if shard.get("format") != "mds":
        raise _error(
            "DATASET_STREAMING_FORMAT_UNSUPPORTED",
            "Publication supports only MosaicML Streaming MDS shards",
        )
    if shard.get("version") != 2:
        raise _error(
            "DATASET_MDS_VERSION_UNSUPPORTED", "Every MDS shard must use version 2"
        )
    _validate_columns(shard)
    if not _is_nonnegative_int(shard.get("samples")):
        raise _metadata("The MDS sample count is malformed")
    hashes = shard.get("hashes")
    size_limit = shard.get("size_limit")
    hash_values = cast(list[object], hashes) if isinstance(hashes, list) else None
    if (
        hash_values is None
        or not all(isinstance(item, str) for item in hash_values)
        or len(set(cast(list[str], hash_values))) != len(hash_values)
        or hash_values != sorted(cast(list[str], hash_values))
        or not (size_limit is None or _is_nonnegative_int(size_limit))
    ):
        raise _metadata("The MDS shard configuration metadata is malformed")
    _validate_hash_names(cast(list[str], hash_values))
    return shard


def _inspect_shard(
    root: Path, value: object
) -> tuple[dict[str, object], str, SourceIdentity, frozenset[str]]:
    shard = _shard(value)
    object_key, identity, declared_paths = _validate_shard(root, shard)
    return shard, object_key, identity, declared_paths


def _validate_shard(
    root: Path, shard: dict[str, object]
) -> tuple[str, SourceIdentity, frozenset[str]]:
    compression = shard.get("compression")
    if compression is not None and (
        not isinstance(compression, str) or compression not in _COMPRESSIONS
    ):
        raise _error(
            "DATASET_MDS_COMPRESSION_UNSUPPORTED",
            "The MDS shard uses an unsupported compression",
        )
    raw = _file_descriptor(shard.get("raw_data"))
    raw_key = _safe_object_key(cast(str, raw["basename"]))
    hash_names = cast(list[str], shard["hashes"])
    _validate_descriptor_hashes(raw, hash_names)
    zipped = shard.get("zip_data")
    if compression is None:
        if zipped is not None:
            raise _metadata("An uncompressed MDS shard must not declare zip_data")
        selected = raw
    else:
        selected = _file_descriptor(zipped)
        _validate_descriptor_hashes(selected, hash_names)
    object_key = _safe_object_key(cast(str, selected["basename"]))
    stream, identity = _open_regular(root / Path(*object_key.split("/")))
    try:
        if identity.byte_count != selected["bytes"]:
            raise _error(
                "DATASET_MDS_BYTE_METADATA_MISMATCH",
                "An MDS shard byte count does not match index.json",
            )
        _validate_stream_hashes(stream, selected, hash_names)
        with tempfile.TemporaryFile() as decoded:
            decoded_size = _decompress(stream, decoded, compression)
            if decoded_size != raw["bytes"]:
                raise _error(
                    "DATASET_MDS_BYTE_METADATA_MISMATCH",
                    "An MDS raw byte count does not match the published shard",
                )
            if compression is not None:
                _validate_stream_hashes(decoded, raw, hash_names)
            _validate_mds_binary(decoded, decoded_size, shard)
        if identity != SourceIdentity.from_stat(os.fstat(stream.fileno())):
            raise _source_mutated()
    finally:
        stream.close()
    return object_key, identity, frozenset({raw_key, object_key})


def _qualify_partition(partition: str, shards: list[object]) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for value in shards:
        shard = _shard(value)
        qualified = cast(dict[str, object], json.loads(json.dumps(shard)))
        for name in ("raw_data", "zip_data"):
            descriptor = qualified.get(name)
            if descriptor is None and name == "zip_data":
                continue
            valid = _file_descriptor(descriptor)
            basename = _safe_object_key(cast(str, valid["basename"]))
            valid["basename"] = f"{partition}/{basename}"
        result.append(qualified)
    return result


def _safe_object_key(value: str) -> str:
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as error:
        raise _path_error("A corpus object path is not valid UTF-8") from error
    if (
        not value
        or value.startswith("/")
        or "\\" in value
        or "\x00" in value
        or any(part in {"", ".", ".."} for part in value.split("/"))
    ):
        raise _path_error("An MDS shard object path is unsafe")
    return value


def _inventory(root: Path) -> set[str]:
    result: set[str] = set()
    for directory, names, files in os.walk(root, followlinks=False):
        parent = Path(directory)
        for name in names:
            try:
                value = (parent / name).lstat()
            except OSError as error:
                raise _source_mutated() from error
            if stat.S_ISLNK(value.st_mode) or not stat.S_ISDIR(value.st_mode):
                raise _error(
                    "DATASET_CORPUS_FILE_TYPE_INVALID",
                    "The corpus may contain only regular files and directories",
                )
        for name in files:
            path = parent / name
            try:
                value = path.lstat()
            except OSError as error:
                raise _source_mutated() from error
            if stat.S_ISLNK(value.st_mode) or not stat.S_ISREG(value.st_mode):
                raise _error(
                    "DATASET_CORPUS_FILE_TYPE_INVALID",
                    "The corpus may contain only regular files",
                )
            stream, _ = _open_regular(path)
            stream.close()
            relative = path.relative_to(root).as_posix()
            _safe_object_key(relative)
            if relative in result:
                raise _path_error("The corpus contains duplicate object paths")
            result.add(relative)
    return result


def _open_regular(path: Path) -> tuple[BinaryIO, SourceIdentity]:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        before = path.lstat()
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
            raise _error(
                "DATASET_CORPUS_FILE_TYPE_INVALID",
                "The corpus may contain only regular files",
            )
        descriptor = os.open(path, flags)
    except DatasetPublicationError:
        raise
    except FileNotFoundError as error:
        raise _error(
            "DATASET_CORPUS_FILE_MISSING", "The corpus contains a missing file"
        ) from error
    except OSError as error:
        raise _error(
            "DATASET_CORPUS_FILE_TYPE_INVALID",
            "The corpus may contain only regular files",
        ) from error
    value = os.fstat(descriptor)
    if not stat.S_ISREG(value.st_mode):
        os.close(descriptor)
        raise _error(
            "DATASET_CORPUS_FILE_TYPE_INVALID",
            "The corpus may contain only regular files",
        )
    return os.fdopen(descriptor, "rb", closefd=True), SourceIdentity.from_stat(value)


def _entry(root: Path, key: str, accepted: SourceIdentity) -> ManifestEntry:
    source = root / Path(*key.split("/"))
    stream, before = _open_regular(source)
    if before != accepted:
        stream.close()
        raise _source_mutated()
    digest = hashlib.sha256()
    count = 0
    try:
        while chunk := stream.read(_CHUNK_BYTES):
            digest.update(chunk)
            count += len(chunk)
        after = SourceIdentity.from_stat(os.fstat(stream.fileno()))
    except OSError as error:
        raise _source_mutated() from error
    finally:
        stream.close()
    if before != after or count != before.byte_count:
        raise _source_mutated()
    return ManifestEntry(key, count, f"sha256:{digest.hexdigest()}", source, before)


def _manifest_bytes(entries: tuple[ManifestEntry, ...]) -> bytes:
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
    return json.dumps(
        manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode()


def _digest(value: bytes) -> str:
    return f"sha256:{hashlib.sha256(value).hexdigest()}"


def _path_error(detail: str) -> DatasetPublicationError:
    return _error("DATASET_CORPUS_PATH_INVALID", detail)


def _source_mutated() -> DatasetPublicationError:
    return _error(
        "DATASET_SOURCE_MUTATED", "A local corpus file changed during publication"
    )
