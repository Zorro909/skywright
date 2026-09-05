# boto3 is a dynamically typed transport dependency.
# pyright: reportMissingTypeStubs=false

"""Exclusive, bounded disposable cache and explicit S3 reads."""

from __future__ import annotations

import fcntl
import hashlib
import os
import time
from collections import OrderedDict
from pathlib import Path
from typing import Any

from skywright._dataset_read_types import (
    DatasetCacheLimits,
    DatasetObject,
    DatasetReadError,
    DatasetReadStats,
    StorageLocation,
)
from skywright.credentials import s3_credentials


class DatasetCache:
    def __init__(
        self, directory: Path, location: StorageLocation, limits: DatasetCacheLimits
    ) -> None:
        self.directory = directory
        self.location = location
        self.limits = limits
        self.requests = self.read_bytes = self.hits = self.misses = self.evictions = (
            self.corrupt
        ) = 0
        self.bytes = self.peak = 0
        self.entries: OrderedDict[str, int] = OrderedDict()
        directory.mkdir(parents=True, exist_ok=True)
        self._lock = (directory / ".lock").open("a+b")
        try:
            fcntl.flock(self._lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self._lock.close()
            raise DatasetReadError(
                "Dataset cache is already owned by another reader"
            ) from None
        try:
            # This is a dedicated cache directory. Scan incrementally, bounding retained inventory.
            with os.scandir(directory) as inventory:
                for entry in inventory:
                    if entry.name == ".lock":
                        continue
                    if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
                        raise DatasetReadError(
                            "Dataset cache contains an unexpected file type"
                        )
                    if len(entry.name) != 64 or any(
                        c not in "0123456789abcdef" for c in entry.name
                    ):
                        os.unlink(entry.path)
                        continue
                    size = entry.stat().st_size
                    if size > limits.byte_limit:
                        os.unlink(entry.path)
                        continue
                    self.reserve(size)
                    self.entries[entry.name] = size
                    self.bytes += size
                    self.peak = max(self.peak, self.bytes)
            import boto3
            from botocore.config import Config

            self.client: Any = boto3.client(  # pyright: ignore[reportUnknownMemberType]
                "s3",
                endpoint_url=location.endpoint,
                region_name=location.region,
                **s3_credentials("dataset"),
                config=Config(
                    s3={
                        "addressing_style": "path" if location.path_style else "virtual"
                    },
                    connect_timeout=limits.request_seconds,
                    read_timeout=limits.request_seconds,
                    retries={"total_max_attempts": 1},
                    max_pool_connections=1,
                    request_checksum_calculation=location.checksum_calculation,
                ),
            )
        except BaseException:
            self._lock.close()
            raise

    def close(self) -> None:
        try:
            self.client.close()
        finally:
            self._lock.close()

    def reserve(
        self, additional: int, *, protect: str | None = None, new_file: bool = True
    ) -> None:
        if additional > self.limits.byte_limit:
            raise DatasetReadError(
                "Dataset shard exceeds the configured cache byte limit"
            )
        while self.bytes + additional > self.limits.byte_limit or (
            new_file and len(self.entries) >= self.limits.file_limit
        ):
            victim = next((key for key in self.entries if key != protect), None)
            if victim is None:
                raise DatasetReadError(
                    "Dataset shard and decoding scratch exceed the cache limits"
                )
            self.remove(victim)
            self.evictions += 1

    def remove(self, key: str) -> None:
        (self.directory / key).unlink(missing_ok=True)
        self.bytes -= self.entries.pop(key)

    def _verified(self, path: Path, entry: DatasetObject) -> bool:
        digest = hashlib.sha256()
        count = 0
        try:
            with path.open("rb") as stream:
                while chunk := stream.read(self.limits.read_chunk_bytes):
                    count += len(chunk)
                    if count > entry.byte_count:
                        return False
                    digest.update(chunk)
        except FileNotFoundError:
            return False
        return (
            count == entry.byte_count and "sha256:" + digest.hexdigest() == entry.sha256
        )

    def get(self, entry: DatasetObject, *, scratch_bytes: int = 0) -> Path:
        key = entry.sha256.removeprefix("sha256:")
        path = self.directory / key
        if entry.byte_count + scratch_bytes > self.limits.byte_limit:
            raise DatasetReadError(
                "Dataset shard and decoding scratch exceed the cache byte limit"
            )
        if key in self.entries:
            if self._verified(path, entry):
                self.hits += 1
                self.entries.move_to_end(key)
                self.reserve(scratch_bytes, protect=key, new_file=False)
                self.peak = max(self.peak, self.bytes + scratch_bytes)
                return path
            self.corrupt += 1
            self.remove(key)
        self.misses += 1
        self.reserve(entry.byte_count + scratch_bytes)
        partial = self.directory / ".partial"
        response: Any = None
        try:
            deadline = time.monotonic() + self.limits.request_seconds
            self.requests += 1
            response = self.client.get_object(
                Bucket=self.location.bucket,
                Key=f"{self.location.prefix}/{entry.object_key}",
            )
            if response["ContentLength"] != entry.byte_count:
                raise DatasetReadError(
                    "Dataset object byte count differs from the pinned manifest"
                )
            digest = hashlib.sha256()
            count = 0
            with partial.open("wb") as target:
                while chunk := response["Body"].read(
                    min(self.limits.read_chunk_bytes, entry.byte_count - count + 1)
                ):
                    self.read_bytes += len(chunk)
                    count += len(chunk)
                    if count > entry.byte_count:
                        raise DatasetReadError(
                            "Dataset object exceeds its pinned byte count"
                        )
                    if time.monotonic() > deadline:
                        raise DatasetReadError(
                            "Dataset read exceeded its request deadline"
                        )
                    target.write(chunk)
                    digest.update(chunk)
                    self.peak = max(self.peak, self.bytes + count)
            if (
                count != entry.byte_count
                or "sha256:" + digest.hexdigest() != entry.sha256
            ):
                raise DatasetReadError(
                    "Dataset object digest differs from the pinned manifest"
                )
            partial.replace(path)
            self.entries[key] = count
            self.bytes += count
            self.peak = max(self.peak, self.bytes + scratch_bytes)
            return path
        except DatasetReadError:
            raise
        except Exception:
            raise DatasetReadError("Dataset storage read failed") from None
        finally:
            partial.unlink(missing_ok=True)
            if response is not None:
                response["Body"].close()

    def stats(self) -> DatasetReadStats:
        return DatasetReadStats(
            self.requests,
            self.read_bytes,
            self.hits,
            self.misses,
            self.evictions,
            self.corrupt,
            self.bytes,
            self.peak,
        )
