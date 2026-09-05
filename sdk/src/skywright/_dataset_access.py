"""MosaicML MDS decoding over Skywright-owned storage, cache and item identities."""

from __future__ import annotations

import copy
import hashlib
import json
import threading
from bisect import bisect_right
from collections.abc import Iterable
from pathlib import Path
from typing import Any, BinaryIO, cast

from skywright._dataset_cache import DatasetCache
from skywright._dataset_publication import shard_metadata
from skywright._dataset_read_types import (
    DatasetCacheLimits,
    DatasetDefinition,
    DatasetItem,
    DatasetObject,
    DatasetReadError,
    DatasetReadStats,
    StorageLocation,
    digest,
    safe_key,
)
from skywright._mds_validation import decompress, validate_binary
from skywright._training_types import DatasetBatch, DatasetCursor


class _LimitedWriter:
    def __init__(self, stream: BinaryIO, limit: int) -> None:
        self.stream = stream
        self.remaining = limit

    def seek(self, offset: int, whence: int = 0) -> int:
        return self.stream.seek(offset, whence)

    def tell(self) -> int:
        return self.stream.tell()

    def read(self, size: int = -1) -> bytes:
        return self.stream.read(size)

    def write(self, data: bytes) -> int:
        if len(data) > self.remaining:
            raise DatasetReadError("Decoded MDS shard exceeds its declared byte count")
        self.remaining -= len(data)
        return self.stream.write(data)


class MdsDatasetAccess:
    """Reads canonical ordinals through MosaicML Streaming's MDSReader.

    One reader owns one dedicated cache directory until close(). Batches contain
    DatasetItems, each with its canonical ordinal and separately decoded payload.
    The initial batching adapter assumes one batch per Step; exact Step regrouping
    belongs to the Dataset continuation contract.
    """

    def __init__(
        self,
        definition: DatasetDefinition,
        location: StorageLocation,
        *,
        cache_directory: Path,
        limits: DatasetCacheLimits | None = None,
        seed: int = 0,
        batch_size: int = 1,
    ) -> None:
        limits = limits or DatasetCacheLimits()
        if type(seed) is not int or type(batch_size) is not int or batch_size < 1:
            raise ValueError(
                "Dataset seed must be an integer and batch size must be positive"
            )
        from skywright._vendor.mosaicml_streaming.reader import MDSReader

        self._reader_type: Any = MDSReader
        self._definition = definition
        self._batch_size = batch_size
        self._mutex = threading.RLock()
        self._closed = False
        self._cache = DatasetCache(cache_directory, location, limits)
        try:
            self._objects = {entry.object_key: entry for entry in definition.objects}
            index_entry = self._objects["index.json"]
            if index_entry.byte_count > limits.metadata_byte_limit:
                raise DatasetReadError(
                    "MDS index exceeds the configured metadata byte limit"
                )
            index = json.loads(self._cache.get(index_entry).read_bytes())
            if index.get("version") != 2 or not isinstance(index.get("shards"), list):
                raise DatasetReadError("Dataset requires an MDS v2 index")
            self._shards: list[dict[str, Any]] = []
            self._ends: list[int] = []
            count = 0
            for value in index["shards"]:
                shard = shard_metadata(value)
                raw = cast(dict[str, Any], shard["raw_data"])
                safe_key(raw["basename"])
                compression = shard.get("compression")
                remote = cast(dict[str, Any], shard["zip_data"] if compression else raw)
                entry = self._objects[safe_key(remote["basename"])]
                if entry.byte_count != remote["bytes"]:
                    raise DatasetReadError("MDS index and manifest byte counts differ")
                scratch = raw["bytes"] if compression else 0
                if entry.byte_count + scratch > limits.byte_limit:
                    raise DatasetReadError(
                        "Dataset shard and decoding scratch exceed the cache byte limit"
                    )
                # Check the library's safe encoding gate before any payload decoding.
                reader = self._reader_type.from_json(str(cache_directory), "", shard)
                reader.validate(allow_unsafe_types=False)
                count += cast(int, shard["samples"])
                self._shards.append(shard)
                self._ends.append(count)
            if count == 0:
                raise DatasetReadError("Dataset must contain at least one item")
            self._size = count
            self._ordering_fingerprint = digest(
                json.dumps(
                    {
                        "definitionId": definition.definition_id,
                        "contentFingerprint": definition.content_fingerprint,
                        "seed": seed,
                        "policy": "deterministic-shuffle",
                        "version": "feistel-sha256-v1",
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode()
            )
        except BaseException:
            self._cache.close()
            raise

    @property
    def ordering_fingerprint(self) -> str:
        return self._ordering_fingerprint

    @property
    def item_count(self) -> int:
        return self._size

    @property
    def statistics(self) -> DatasetReadStats:
        with self._mutex:
            return self._cache.stats()

    def close(self) -> None:
        with self._mutex:
            if not self._closed:
                self._closed = True
                self._cache.close()

    def __enter__(self) -> MdsDatasetAccess:
        return self

    def __exit__(self, *_error: object) -> None:
        self.close()

    def read_item(self, ordinal: int) -> DatasetItem:
        """Resolve identity before decoding, independently of ordering and batches."""
        if type(ordinal) is not int or not 0 <= ordinal < self._size:
            raise ValueError("Dataset ordinal is outside the pinned definition")
        with self._mutex:
            if self._closed:
                raise DatasetReadError("Dataset reader is closed")
            shard_index = bisect_right(self._ends, ordinal)
            shard = self._shards[shard_index]
            offset = ordinal - (self._ends[shard_index - 1] if shard_index else 0)
            raw = shard["raw_data"]
            compression = shard.get("compression")
            remote = shard["zip_data"] if compression else raw
            entry: DatasetObject = self._objects[remote["basename"]]
            scratch = self._cache.directory / ".raw"
            try:
                path = self._cache.get(
                    entry, scratch_bytes=raw["bytes"] if compression else 0
                )
                if compression:
                    with path.open("rb") as source, scratch.open("w+b") as target:
                        written = decompress(
                            source,
                            cast(BinaryIO, _LimitedWriter(target, raw["bytes"])),
                            compression,
                        )
                    if written != raw["bytes"]:
                        raise DatasetReadError(
                            "Decoded MDS shard differs from its declared byte count"
                        )
                    path = scratch
                with path.open("rb") as source:
                    validate_binary(source, raw["bytes"], shard)
                local = copy.deepcopy(shard)
                local["raw_data"]["basename"] = path.name
                reader = self._reader_type.from_json(str(path.parent), "", local)
                payload: object = reader.decode_sample(reader.get_sample_data(offset))
                return DatasetItem(self._definition.definition_id, ordinal, payload)
            except DatasetReadError:
                raise
            except Exception:
                raise DatasetReadError(
                    "MDS item validation or decoding failed"
                ) from None
            finally:
                scratch.unlink(missing_ok=True)

    def ordinal_at(self, epoch: int, item_offset: int) -> int:
        """A bounded-memory deterministic permutation, versioned in the fingerprint."""
        if (
            type(epoch) is not int
            or epoch < 0
            or type(item_offset) is not int
            or not 0 <= item_offset < self._size
        ):
            raise ValueError("Dataset sequence position is outside the epoch")
        bits = max(1, ((self._size - 1).bit_length() + 1) // 2)
        mask = (1 << bits) - 1
        key = f"{self.ordering_fingerprint}:{epoch}:".encode()
        value = item_offset
        while True:
            left, right = value >> bits, value & mask
            for round_number in range(6):
                hashed = hashlib.sha256(
                    key + f"{round_number}:{right}".encode()
                ).digest()
                left, right = right, left ^ (int.from_bytes(hashed[:8], "big") & mask)
            value = (left << bits) | right
            if value < self._size:
                return value

    def batches(self, cursor: DatasetCursor) -> Iterable[DatasetBatch]:
        if cursor.ordering_fingerprint not in ("", self.ordering_fingerprint):
            raise DatasetReadError(
                "Dataset ordering fingerprint does not match the cursor"
            )
        if (
            any(
                type(value) is not int or value < 0
                for value in (cursor.epoch, cursor.item_offset, cursor.epoch_step)
            )
            or cursor.item_offset >= self._size
        ):
            raise DatasetReadError("Dataset Cursor is outside the epoch")
        step = cursor.epoch_step
        for start in range(cursor.item_offset, self._size, self._batch_size):
            end = min(start + self._batch_size, self._size)
            items = tuple(
                self.read_item(self.ordinal_at(cursor.epoch, position))
                for position in range(start, end)
            )
            step += 1
            next_cursor = (
                DatasetCursor(cursor.epoch, end, step, self.ordering_fingerprint)
                if end < self._size
                else DatasetCursor(cursor.epoch + 1, 0, 0, self.ordering_fingerprint)
            )
            yield DatasetBatch(items, next_cursor, cursor.epoch)
