from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
from typing import Any

import pytest

from skywright._dataset_cache import DatasetCache
from skywright._dataset_publication import inspect_mds_corpus
from skywright.dataset import (
    DatasetCacheLimits,
    DatasetDefinition,
    DatasetObject,
    DatasetReadError,
    StorageLocation,
)


def location(prefix: str = "corpus") -> StorageLocation:
    return StorageLocation(
        "storage", "http://127.0.0.1:8333", "dataset", "us-east-1", prefix, "copy", 1
    )


def test_definition_rejects_changed_manifest_and_unsafe_paths() -> None:
    entry = DatasetObject("index.json", 1, "sha256:" + "0" * 64)
    with pytest.raises(ValueError, match="pinned content"):
        DatasetDefinition("id", "fingerprint", "manifest", (entry,))
    with pytest.raises(ValueError, match="normalized"):
        DatasetObject("../escape", 1, entry.sha256)
    with pytest.raises(ValueError, match="credentials"):
        StorageLocation(
            "storage",
            "https://key:secret@host",
            "bucket",
            "region",
            "prefix",
            "copy",
            1,
        )


def test_actual_cache_reads_verify_hits_and_rebuild_corruption(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data = b"published bytes"
    entry = DatasetObject(
        "shard.mds", len(data), "sha256:" + hashlib.sha256(data).hexdigest()
    )
    requests: list[dict[str, object]] = []

    class Client:
        def get_object(self, **kwargs: object) -> dict[str, object]:
            requests.append(kwargs)
            return {"ContentLength": len(data), "Body": io.BytesIO(data)}

        def close(self) -> None:
            pass

    monkeypatch.setenv("SKYWRIGHT_DATASET_ACCESS_KEY_ID", "dataset-key")
    monkeypatch.setenv("SKYWRIGHT_DATASET_SECRET_ACCESS_KEY", "dataset-secret")
    client_arguments: dict[str, Any] = {}

    def create_client(*args: object, **kwargs: Any) -> Client:
        client_arguments.update(kwargs)
        return Client()

    monkeypatch.setattr("boto3.client", create_client)
    cache = DatasetCache(
        tmp_path, location(), DatasetCacheLimits(byte_limit=32, file_limit=1)
    )
    try:
        assert cache.get(entry).read_bytes() == data
        assert cache.get(entry).read_bytes() == data
        assert len(requests) == 1
        cache.get(entry).write_bytes(b"corrupt")
        assert cache.get(entry).read_bytes() == data
        assert cache.stats().corrupt_cache_entries == 1
        assert cache.stats().read_bytes == 2 * len(data)
        assert cache.stats().peak_cache_bytes <= 32
        assert client_arguments["aws_access_key_id"] == "dataset-key"
        assert client_arguments["config"].retries["total_max_attempts"] == 1
        with pytest.raises(DatasetReadError, match="already owned"):
            DatasetCache(tmp_path, location(), DatasetCacheLimits())
    finally:
        cache.close()
    restarted = DatasetCache(
        tmp_path, location("replica"), DatasetCacheLimits(byte_limit=32)
    )
    try:
        assert restarted.get(entry).read_bytes() == data
        assert restarted.stats().requests == 0
    finally:
        restarted.close()


def test_cache_rejects_bad_remote_digest_and_cleans_staging(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    class Client:
        def get_object(self, **kwargs: object) -> dict[str, object]:
            return {"ContentLength": 4, "Body": io.BytesIO(b"evil")}

        def close(self) -> None:
            pass

    monkeypatch.setenv("SKYWRIGHT_DATASET_ACCESS_KEY_ID", "key")
    monkeypatch.setenv("SKYWRIGHT_DATASET_SECRET_ACCESS_KEY", "secret")

    def create_client(*args: object, **kwargs: object) -> Client:
        return Client()

    monkeypatch.setattr("boto3.client", create_client)
    cache = DatasetCache(tmp_path, location(), DatasetCacheLimits(byte_limit=4))
    try:
        with pytest.raises(DatasetReadError, match="digest"):
            cache.get(
                DatasetObject(
                    "shard", 4, "sha256:" + hashlib.sha256(b"good").hexdigest()
                )
            )
        assert cache.stats().cache_bytes == 0
        assert [path.name for path in tmp_path.iterdir()] == [".lock"]
        with pytest.raises(DatasetReadError, match="byte limit"):
            cache.get(DatasetObject("large", 5, "sha256:" + "0" * 64))
        assert cache.stats().requests == 1
    finally:
        cache.close()


def published_definition(directory: Path) -> DatasetDefinition:
    corpus = inspect_mds_corpus(directory)
    return DatasetDefinition(
        "definition-1",
        corpus.content_fingerprint,
        corpus.manifest_identity,
        tuple(
            DatasetObject(entry.object_key, entry.byte_count, entry.checksum_sha256)
            for entry in corpus.entries
        ),
    )


def test_upstream_fixture_identity() -> None:
    root = Path(__file__).parent / "fixtures" / "mds-reader"
    provenance = json.loads((root / "provenance.json").read_text())
    for relative, expected in provenance["files"].items():
        assert hashlib.sha256((root / relative).read_bytes()).hexdigest() == expected


@pytest.mark.parametrize("count", [1, 2, 3, 8])
@pytest.mark.parametrize("size", [0, 1, 2, 7, 24])
def test_single_node_partition_reassembles_without_padding(
    size: int, count: int
) -> None:
    from skywright import DatasetBatch, DatasetCursor

    batch = DatasetBatch(tuple(range(size)), DatasetCursor())
    partitions = batch.partition_items(count)
    assert len(partitions) == count
    assert tuple(item for partition in partitions for item in partition) == batch.items
    assert max(map(len, partitions)) - min(map(len, partitions)) <= 1


def test_checkpoint_ordering_reset_preserves_other_state(tmp_path: Path) -> None:
    from skywright import CheckpointSnapshot, DatasetCursor
    from skywright._dataset_ordering import DatasetOrdering, prepare_continuation
    from skywright.run_store import CheckpointCodec

    old = DatasetOrdering("old", "sha256:old", 19)
    new = DatasetOrdering("new", "sha256:new", 19)
    checkpoint = CheckpointSnapshot(
        42,
        {"model": {"weight": 9}},
        {"dataset_ordering": old.to_document(), "other": (1, 2, 3)},
        DatasetCursor(3, 17, 4, old.fingerprint),
        "durable",
        "source",
        "project@digest",
    )
    codec = CheckpointCodec(staging_directory=tmp_path)
    serialized = codec.serialize(checkpoint)
    try:
        restored = codec.deserialize(serialized.path, expected_digest=serialized.digest)
    finally:
        serialized.path.unlink()
    reset = prepare_continuation(
        restored, new, run_id="clone", source_run_id="source", ordering_reset=True
    )
    assert reset is not None
    assert reset.step == 42
    assert reset.dataset_cursor == DatasetCursor(3, 0, 0, new.fingerprint)
    assert reset.state == checkpoint.state
    assert reset.runtime_state["other"] == (1, 2, 3)
    assert checkpoint.dataset_cursor.item_offset == 17
    assert reset.run_id == "source"


@pytest.mark.parametrize(
    ("change", "reset", "source", "rule"),
    [
        ({"seed": 20}, False, None, "seed"),
        ({"seed": 20}, True, "source", "seed"),
        ({"definition_id": "different"}, False, "source", "definitionId"),
        ({"content_fingerprint": "different"}, False, None, "contentFingerprint"),
        ({}, True, None, "reset"),
        ({}, True, "source", "reset"),
        ({}, False, "wrong-source", "source-run"),
    ],
)
def test_continuation_rejects_mismatched_inputs(
    change: dict[str, Any], reset: bool, source: str | None, rule: str
) -> None:
    from dataclasses import replace

    from skywright import CheckpointSnapshot, DatasetCursor, TrainingContractViolation
    from skywright._dataset_ordering import DatasetOrdering, prepare_continuation

    old = DatasetOrdering("definition", "sha256:content", 19)
    checkpoint = CheckpointSnapshot(
        3,
        {},
        {"dataset_ordering": old.to_document()},
        DatasetCursor(1, 7, 2, old.fingerprint),
        "durable",
        "source",
        "project",
    )
    with pytest.raises(TrainingContractViolation, match=rule):
        prepare_continuation(
            checkpoint,
            replace(old, **change),
            run_id="clone" if source else "source",
            source_run_id=source,
            ordering_reset=reset,
        )


def test_continuation_rejects_tampered_inputs_and_unknown_policy() -> None:
    from skywright import CheckpointSnapshot, DatasetCursor, TrainingContractViolation
    from skywright._dataset_ordering import DatasetOrdering, prepare_continuation

    ordering = DatasetOrdering("definition", "content", 19)
    checkpoint = CheckpointSnapshot(
        1,
        {},
        {"dataset_ordering": {**ordering.to_document(), "seed": 20}},
        DatasetCursor(0, 5, 1, ordering.fingerprint),
        "durable",
        "run",
    )
    with pytest.raises(TrainingContractViolation, match="fingerprint"):
        prepare_continuation(
            checkpoint, ordering, run_id="run", source_run_id=None, ordering_reset=False
        )
    with pytest.raises(ValueError, match="policy"):
        DatasetOrdering("definition", "content", 19, policy="replacement")
    with pytest.raises(ValueError, match="version"):
        DatasetOrdering("definition", "content", 19, version="v2")


@pytest.mark.parametrize("count", [1, 2, 4])
def test_runtime_rng_restores_only_surviving_accelerator_indices(
    count: int, monkeypatch: pytest.MonkeyPatch
) -> None:
    from types import SimpleNamespace

    from skywright import _training_state

    restored: list[tuple[object, int]] = []

    def set_rng_state(value: object, index: int) -> None:
        assert index < count
        restored.append((value, index))

    def set_cpu_rng_state(value: object) -> None:
        pass

    def find_spec(name: str) -> object:
        return object()

    torch = SimpleNamespace(
        set_rng_state=set_cpu_rng_state,
        cuda=SimpleNamespace(
            is_available=lambda: True,
            device_count=lambda: count,
            set_rng_state=set_rng_state,
        ),
    )

    def import_module(name: str) -> SimpleNamespace:
        return torch

    monkeypatch.setattr(_training_state.importlib.util, "find_spec", find_spec)
    monkeypatch.setattr(_training_state.importlib, "import_module", import_module)
    _training_state.restore_runtime_state(
        {"torch_cpu_random": b"cpu", "torch_accelerator_random": [b"first", b"second"]}
    )
    assert restored == [
        (value, index) for index, value in enumerate([b"first", b"second"][:count])
    ]
