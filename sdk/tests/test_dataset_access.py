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
