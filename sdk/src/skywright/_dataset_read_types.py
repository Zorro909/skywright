"""Immutable inputs and cumulative, constant-size evidence for Dataset reads."""

from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass
from urllib.parse import urlsplit


class DatasetReadError(RuntimeError):
    """Dataset access failed without exposing storage credentials."""


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def safe_key(value: str) -> str:
    if (
        not value
        or "\\" in value
        or "\x00" in value
        or any(part in ("", ".", "..") for part in value.split("/"))
    ):
        raise ValueError("Dataset object paths must be relative and normalized")
    return value


@dataclass(frozen=True)
class DatasetObject:
    object_key: str
    byte_count: int
    sha256: str

    def __post_init__(self) -> None:
        safe_key(self.object_key)
        if type(self.byte_count) is not int or self.byte_count < 0:
            raise ValueError("Dataset object byte count must be nonnegative")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", self.sha256):
            raise ValueError("Dataset object requires a SHA-256 digest")


@dataclass(frozen=True)
class DatasetDefinition:
    definition_id: str
    content_fingerprint: str
    manifest_identity: str
    objects: tuple[DatasetObject, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "objects", tuple(self.objects))
        if not self.definition_id or not self.objects:
            raise ValueError("A pinned Dataset Definition and manifest are required")
        keys = [entry.object_key for entry in self.objects]
        if len(set(keys)) != len(keys) or "index.json" not in keys:
            raise ValueError("Dataset manifest requires unique paths and index.json")
        manifest = {
            "byteCount": sum(entry.byte_count for entry in self.objects),
            "format": "mosaicml-streaming-mds@2",
            "objectCount": len(self.objects),
            "objects": [
                {
                    "byteCount": entry.byte_count,
                    "objectKey": entry.object_key,
                    "sha256": entry.sha256,
                }
                for entry in sorted(
                    self.objects, key=lambda entry: entry.object_key.encode("utf-8")
                )
            ],
            "version": "skywright-dataset-manifest@1",
        }
        observed = digest(
            json.dumps(
                manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            ).encode()
        )
        content = digest(
            json.dumps(
                {
                    "format": "mosaicml-streaming-mds@2",
                    "manifest": observed,
                    "version": "skywright-dataset-content@1",
                },
                sort_keys=True,
                separators=(",", ":"),
            ).encode()
        )
        if observed != self.manifest_identity or content != self.content_fingerprint:
            raise ValueError(
                "Dataset manifest does not match the pinned content fingerprint"
            )


@dataclass(frozen=True)
class StorageLocation:
    storage_id: str
    endpoint: str
    bucket: str
    region: str
    prefix: str
    copy_id: str
    generation: int
    lease_id: str | None = None
    path_style: bool = True
    checksum_calculation: str = "when_required"

    def __post_init__(self) -> None:
        endpoint = urlsplit(self.endpoint)
        if (
            endpoint.scheme not in ("http", "https")
            or not endpoint.hostname
            or endpoint.username
            or endpoint.password
        ):
            raise ValueError("Dataset endpoint must be an HTTP URL without credentials")
        if endpoint.query or endpoint.fragment:
            raise ValueError("Dataset endpoint cannot contain a query or fragment")
        safe_key(self.prefix)
        if (
            not all((self.storage_id, self.bucket, self.region, self.copy_id))
            or type(self.generation) is not int
            or self.generation < 1
        ):
            raise ValueError(
                "Dataset location requires exact storage and copy generation identities"
            )
        if self.checksum_calculation not in ("when_required", "when_supported"):
            raise ValueError("Unknown S3 checksum compatibility option")


@dataclass(frozen=True)
class DatasetCacheLimits:
    byte_limit: int = 256 * 1024 * 1024
    file_limit: int = 128
    metadata_byte_limit: int = 16 * 1024 * 1024
    request_seconds: float = 30.0
    read_chunk_bytes: int = 1024 * 1024

    def __post_init__(self) -> None:
        for value in (
            self.byte_limit,
            self.file_limit,
            self.metadata_byte_limit,
            self.read_chunk_bytes,
        ):
            if type(value) is not int or value < 1:
                raise ValueError("Dataset cache limits must be positive integers")
        if not math.isfinite(self.request_seconds) or self.request_seconds <= 0:
            raise ValueError("Dataset request deadline must be finite and positive")


@dataclass(frozen=True)
class DatasetReadStats:
    requests: int
    read_bytes: int
    cache_hits: int
    cache_misses: int
    evictions: int
    corrupt_cache_entries: int
    cache_bytes: int
    peak_cache_bytes: int


@dataclass(frozen=True)
class DatasetItem:
    """Canonical identity plus decoded MDS columns, before project transformations."""

    definition_id: str
    ordinal: int
    payload: object
