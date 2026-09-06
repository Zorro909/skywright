"""Measure actual S3 checkpoint recovery in a fresh process, including native allocations."""

# pyright: reportMissingTypeStubs=false, reportMissingImports=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
# pyright: reportUnknownArgumentType=false
from __future__ import annotations

import argparse
import gc
import json
import os
import tempfile
import threading
import time
from contextlib import suppress
from pathlib import Path

import boto3
import numpy as np
import torch
from botocore.config import Config

from skywright import CheckpointSnapshot, DatasetCursor
from skywright.run_store import CheckpointCodec, RunStoreReader, TargetStorage


def rss() -> int:
    return int(Path("/proc/self/statm").read_text().split()[1]) * os.sysconf(
        "SC_PAGE_SIZE"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("prepare", "recover"))
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--payload-mib", type=int, default=128)
    parser.add_argument("--expect-bounded", action="store_true")
    args = parser.parse_args()
    client = boto3.client(
        "s3",
        endpoint_url=args.endpoint,
        region_name="us-east-1",
        aws_access_key_id="test-key",
        aws_secret_access_key="test-secret",
        config=Config(
            s3={"addressing_style": "path"},
            request_checksum_calculation="when_required",
        ),
    )
    target = TargetStorage(
        "seaweedfs", args.endpoint, args.bucket, "us-east-1", "project", "run"
    )
    reader = RunStoreReader(target, client=client)
    payload = args.payload_mib * 1024 * 1024
    if args.mode == "prepare":
        state = {
            name: np.full(payload // 8, index, dtype=np.float32)
            for index, name in enumerate(("model", "optimizer"), 1)
        }
        snapshot = CheckpointSnapshot(
            1,
            state,
            dataset_cursor=DatasetCursor(ordering_fingerprint="order"),
            run_id="run",
            project_version="project@digest",
        )
        serialized = CheckpointCodec().serialize(snapshot)
        try:
            key = reader.protocol.checkpoint_key(1, serialized.digest)
            with serialized.path.open("rb") as body:
                client.put_object(
                    Bucket=args.bucket,
                    Key=key,
                    Body=body,
                    ContentType="application/octet-stream",
                    Metadata={
                        "skywright-schema": "v1",
                        "skywright-kind": "checkpoint",
                        "skywright-size": str(serialized.size),
                        "skywright-sha256": serialized.digest,
                    },
                )
        finally:
            serialized.path.unlink()
        return

    torch.empty(
        0
    )  # Import and initialize the optional adapter before the measured read.
    candidate = reader.list_checkpoints()[0]
    gc.collect()
    baseline = rss()
    peak = baseline
    disk_peak = 0
    stop = threading.Event()
    with tempfile.TemporaryDirectory(prefix="skywright-recovery-budget-") as directory:
        staging = Path(directory)
        # TMPDIR also covers the old reader, for an identical baseline disk observation.
        tempfile.tempdir = directory

        def sample() -> None:
            nonlocal peak, disk_peak
            while not stop.is_set():
                peak = max(peak, rss())
                with suppress(FileNotFoundError):
                    disk_peak = max(
                        disk_peak,
                        sum(path.stat().st_size for path in staging.iterdir()),
                    )
                stop.wait(0.002)

        worker = threading.Thread(target=sample)
        worker.start()
        started = time.monotonic()
        try:
            recovered = reader.read_exact(
                candidate.reference,
                project_version="project@digest",
                ordering_fingerprint="order",
            )
        finally:
            peak = max(peak, rss())
            stop.set()
            worker.join()
        elapsed = time.monotonic() - started
        assert not list(staging.iterdir()), "recovery staging was not released"
    state = recovered.state
    for index, name in enumerate(("model", "optimizer"), 1):
        value = state[name]
        assert isinstance(value, np.ndarray)
        assert np.array_equal(value, np.full(payload // 8, index, dtype=np.float32))
    budget = 2 * payload + 64 * 1024 * 1024
    evidence = {
        "payload_bytes": payload,
        "object_bytes": candidate.size,
        "baseline_rss_bytes": baseline,
        "peak_rss_bytes": peak,
        "additional_rss_bytes": peak - baseline,
        "host_budget_bytes": budget,
        "staged_disk_peak_bytes": disk_peak,
        "disk_budget_bytes": candidate.size,
        "elapsed_seconds": elapsed,
        "state_verified": True,
        "budget_passed": peak - baseline <= budget and disk_peak <= candidate.size,
    }
    print(json.dumps(evidence, sort_keys=True), flush=True)
    if args.expect_bounded:
        assert evidence["budget_passed"], evidence


if __name__ == "__main__":
    main()
