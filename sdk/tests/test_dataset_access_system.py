# MosaicML and boto3 expose dynamically typed integration values.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownLambdaType=false, reportUnknownParameterType=false
# pyright: reportUnknownVariableType=false

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from dataclasses import asdict
from pathlib import Path
from typing import cast

import pytest
from test_dataset_access import published_definition
from test_run_store_system import seaweedfs

from skywright import DatasetCursor
from skywright.dataset import (
    DatasetCacheLimits,
    DatasetItem,
    DatasetReadError,
    MdsDatasetAccess,
    StorageLocation,
)


@pytest.mark.system
@pytest.mark.parametrize("compression", [None, "gz", "bz2", "br", "zstd", "snappy"])
def test_real_s3_cache_lifecycle_location_changes_and_direct_process(
    tmp_path, monkeypatch, compression
) -> None:
    source = tmp_path / "source"
    shutil.copytree(
        Path(__file__).parent / "fixtures" / "mds-reader" / (compression or "raw"),
        source,
    )
    definition = published_definition(source)
    cache = tmp_path / "cache"
    limits = DatasetCacheLimits(
        byte_limit=1800, file_limit=3, metadata_byte_limit=16000
    )
    # The index has its own explicit cap, but must also fit the cache budget.
    index_bytes = next(
        entry.byte_count
        for entry in definition.objects
        if entry.object_key == "index.json"
    )
    limits = DatasetCacheLimits(byte_limit=index_bytes + 1800, file_limit=3)
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="datasets")
        client.create_bucket(Bucket="outputs")
        monkeypatch.setenv("SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID", "test-access-key")
        monkeypatch.setenv("SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY", "test-secret-key")
        for prefix in ("authority", "replica"):
            for entry in definition.objects:
                client.put_object(
                    Bucket="datasets",
                    Key=f"{prefix}/{entry.object_key}",
                    Body=(source / entry.object_key).read_bytes(),
                )
        monkeypatch.setenv("SKYWRIGHT_DATASET_ACCESS_KEY_ID", "test-access-key")
        monkeypatch.setenv("SKYWRIGHT_DATASET_SECRET_ACCESS_KEY", "test-secret-key")
        selected = StorageLocation(
            "storage",
            endpoint,
            "datasets",
            "us-east-1",
            "authority",
            "authority-copy",
            1,
        )
        with MdsDatasetAccess(
            definition,
            selected,
            cache_directory=cache,
            limits=limits,
            seed=19,
            batch_size=5,
        ) as dataset:
            first = dataset.read_item(0)
            assert first == DatasetItem(
                "definition-1", 0, {"number": 0, "text": "item-00-" + "x" * 24}
            )
            cold = dataset.statistics
            assert cold.requests == 2
            assert dataset.read_item(0) == first
            assert dataset.statistics.requests == cold.requests
            assert dataset.statistics.read_bytes == cold.read_bytes
            for epoch in range(3):
                assert sorted(dataset.ordinal_at(epoch, i) for i in range(24)) == list(
                    range(24)
                )
            batches = list(dataset.batches(DatasetCursor()))
            baseline = [
                cast(DatasetItem, item) for batch in batches for item in batch.items
            ]
            assert sorted(item.ordinal for item in baseline) == list(range(24))
            assert all(
                cast(dict[str, object], item.payload)["number"] == item.ordinal
                for item in baseline
            )
            assert batches[-1].next_cursor == DatasetCursor(
                1, 0, 0, dataset.ordering_fingerprint
            )
            assert dataset.statistics.evictions > 0
            assert dataset.statistics.peak_cache_bytes <= limits.byte_limit
            assert (
                sum(path.stat().st_size for path in cache.iterdir())
                <= limits.byte_limit
            )
            fingerprint = dataset.ordering_fingerprint
            measurements = {
                "cold": asdict(cold),
                "epoch": asdict(dataset.statistics),
                "limits": asdict(limits),
            }
        with MdsDatasetAccess(
            definition,
            selected,
            cache_directory=tmp_path / "byte-limited-cache",
            limits=DatasetCacheLimits(byte_limit=index_bytes, file_limit=128),
        ) as byte_limited:
            for ordinal in range(24):
                byte_limited.read_item(ordinal)
            assert byte_limited.statistics.evictions > 0
            assert byte_limited.statistics.peak_cache_bytes <= index_bytes
        replica = StorageLocation(
            "storage",
            endpoint,
            "datasets",
            "us-east-1",
            "replica",
            "replica-copy",
            2,
            "lease-id",
        )
        with MdsDatasetAccess(
            definition,
            replica,
            cache_directory=cache,
            limits=limits,
            seed=19,
            batch_size=7,
        ) as restarted:
            assert restarted.ordering_fingerprint == fingerprint
            assert [
                item
                for batch in restarted.batches(DatasetCursor())
                for item in batch.items
            ] == baseline
        shutil.rmtree(cache)
        with MdsDatasetAccess(
            definition, replica, cache_directory=cache, limits=limits, seed=19
        ) as rebuilt:
            assert [
                item
                for batch in rebuilt.batches(DatasetCursor())
                for item in batch.items
            ] == baseline
            measurements["rebuild"] = asdict(rebuilt.statistics)
        report = Path("target/dataset-read-measurements")
        report.mkdir(parents=True, exist_ok=True)
        (report / f"{compression or 'raw'}.json").write_text(
            json.dumps(measurements, indent=2)
        )

        # A fresh direct Training Process consumes the production adapter with file credentials.
        credential_file = tmp_path / "credentials.json"
        credential_file.write_text(
            json.dumps(
                {
                    "ACCESS_KEY_ID": "test-access-key",
                    "SECRET_ACCESS_KEY": "test-secret-key",
                }
            )
        )
        credential_file.chmod(0o400)
        monkeypatch.delenv("SKYWRIGHT_DATASET_ACCESS_KEY_ID")
        monkeypatch.delenv("SKYWRIGHT_DATASET_SECRET_ACCESS_KEY")
        monkeypatch.setenv("SKYWRIGHT_DATASET_CREDENTIAL_FILE", str(credential_file))
        configuration = tmp_path / "inputs.json"
        configuration.write_text(
            json.dumps(
                {
                    "definition": asdict(definition),
                    "location": asdict(replica),
                    "cache": str(tmp_path / "process-cache"),
                    "limits": asdict(limits),
                }
            )
        )
        process = subprocess.run(
            [
                sys.executable,
                str(Path(__file__).parent / "support" / "dataset_training_scenario.py"),
                str(configuration),
            ],
            capture_output=True,
            text=True,
        )
        assert process.returncode == 0, process.stdout + process.stderr
        assert "completed:24" in process.stdout

        # Rejection checks use actual changed bytes at the selected remote location.
        shard = next(
            entry for entry in definition.objects if entry.object_key != "index.json"
        )
        client.put_object(
            Bucket="datasets",
            Key=f"replica/{shard.object_key}",
            Body=b"!" * shard.byte_count,
        )
        with (
            MdsDatasetAccess(
                definition,
                replica,
                cache_directory=tmp_path / "bad-cache",
                limits=limits,
            ) as changed,
            pytest.raises(DatasetReadError, match="digest"),
        ):
            for ordinal in range(24):
                changed.read_item(ordinal)
