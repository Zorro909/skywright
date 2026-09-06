"""Qualify bounded runtime history against an actual disposable S3 service."""

# boto3, protobuf and the fixture contracts expose runtime-shaped values.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import threading
import time
import uuid
import weakref
from collections import Counter, deque
from dataclasses import asdict
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

import boto3
from botocore.config import Config
from tensorboard.backend.event_processing.event_file_loader import EventFileLoader
from tensorboard.util import tensor_util

from skywright import (
    DatasetBatch,
    DatasetCursor,
    MetricCatalog,
    MetricDefinition,
    run_training_process,
)
from skywright.metrics import MetricSchema
from skywright.run_store import RunStoreReader, RunStoreRecorder, TargetStorage

MIB = 1024 * 1024


def rss_bytes() -> int:
    return (
        int(Path("/proc/self/status").read_text().split("VmRSS:")[1].split()[0]) * 1024
    )


class SyntheticDataset:
    ordering_fingerprint = "sha256:synthetic-history-order"

    def __init__(self, steps: int) -> None:
        self.steps = steps

    def batches(self, cursor):
        for index in range(cursor.item_offset, self.steps):
            yield DatasetBatch(
                (index,),
                DatasetCursor(0, index + 1, index + 1, self.ordering_fingerprint),
            )


class SyntheticContracts:
    def compose(self, project_version, skywright_schema_identity):
        return MetricCatalog(
            project_version,
            "sha256:synthetic-contract",
            skywright_schema_identity,
            "sha256:synthetic-schema",
            frozenset(MetricSchema.units()),
            (
                MetricDefinition(
                    "train/loss", "real", "dimensionless", comparison="minimize"
                ),
            ),
            MetricSchema.definitions(),
        )


class CounterState:
    def __init__(self) -> None:
        self.value = 0

    def state_dict(self):
        return {"value": self.value}

    def load_state_dict(self, state):
        self.value = state["value"]


class ObservedRecorder(RunStoreRecorder):
    """Observe ownership using bounded weak references, without keeping payloads."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.metric_references = deque(maxlen=64)
        self.output_references = deque(maxlen=64)
        self.metric_counts: Counter[str] = Counter()
        self.sample_published = threading.Event()

    def publish_step(
        self,
        step,
        dataset_cursor,
        observations,
        latest_durable_step,
        latest_durable_checkpoint,
    ):
        super().publish_step(
            step,
            dataset_cursor,
            observations,
            latest_durable_step,
            latest_durable_checkpoint,
        )
        for item in observations:
            self.metric_references.append(weakref.ref(item))
            self.metric_counts[item.name] += 1

    def publish_wall_time(self, observation):
        super().publish_wall_time(observation)
        self.metric_references.append(weakref.ref(observation))
        self.metric_counts[observation.name] += 1
        self.sample_published.set()

    def publish_artifact(self, artifact):
        super().publish_artifact(artifact)
        self.output_references.append(weakref.ref(artifact))

    def publish_sample(self, sample):
        super().publish_sample(sample)
        self.output_references.append(weakref.ref(sample))


def run(args) -> dict[str, Any]:
    client = boto3.client(
        "s3",
        endpoint_url=args.endpoint,
        region_name="us-east-1",
        aws_access_key_id="test-access-key",
        aws_secret_access_key="test-secret-key",
        config=Config(
            s3={"addressing_style": "path"},
            request_checksum_calculation="when_required",
        ),
    )
    target = TargetStorage(
        "history-fixture",
        args.endpoint,
        args.bucket,
        "us-east-1",
        "project",
        str(uuid.uuid4()),
    )
    permits = threading.Semaphore(0)
    samples: list[dict[str, int]] = []
    delivered = 0
    missing = 0
    through = 0
    producer: str | None = None
    peak_measurements = 0
    started = time.monotonic()
    with TemporaryDirectory(prefix="skywright-history-") as directory:
        work = Path(directory)
        recorder = ObservedRecorder(
            target, client=client, metric_staging_directory=work
        )
        trace = (work / "measurements.ndjson").open("w")

        def drain() -> None:
            nonlocal delivered, missing, through, producer, peak_measurements
            peak_measurements = max(peak_measurements, len(recorder.measurements))
            # Keep this runner usable against the unchanged baseline for comparison.
            if not hasattr(recorder, "drain_measurements"):
                return
            batch = recorder.drain_measurements()
            if producer is None:
                producer = batch.producer_id
            assert producer == batch.producer_id
            assert (
                batch.run_id == target.run_id and batch.provenance == target.storage_id
            )
            assert batch.through_sequence >= through
            count = len(batch.measurements) + (batch.gap.count if batch.gap else 0)
            assert count == batch.through_sequence - through
            expected = through + 1
            if batch.gap is not None:
                assert batch.gap.first_sequence == expected
                expected = batch.gap.last_sequence + 1
                missing += batch.gap.count
            assert [item.sequence for item in batch.measurements] == list(
                range(expected, batch.through_sequence + 1)
            )
            delivered += len(batch.measurements)
            through = batch.through_sequence
            trace.write(json.dumps(asdict(batch)) + "\n")

        def wait_sample(stop, _interval):
            while not stop.is_set():
                if permits.acquire(timeout=0.01):
                    return False
            return True

        def train(context):
            state = CounterState()
            context.register_checkpoint_state("counter", state)
            context.start()
            for batch in context.dataset.batches(context.dataset_cursor):
                state.value += 1
                context.observe("train/loss", float(state.value))
                context.commit_step(batch)
                context.persist_artifact(
                    "artifact.bin", bytes([state.value % 256]) * args.payload_bytes
                )
                context.persist_sample(
                    "sample.bin",
                    bytes([(state.value + 1) % 256]) * args.payload_bytes,
                    media_type="application/octet-stream",
                )
                recorder.sample_published.clear()
                permits.release()
                assert recorder.sample_published.wait(5), (
                    "background metric publication stalled"
                )
                # Withhold the first drain to exercise explicit loss without stopping storage.
                if state.value >= args.warmup and state.value % 32 == 0:
                    drain()
                if (
                    state.value >= args.warmup
                    and (state.value - args.warmup) % args.sample_every == 0
                ):
                    gc.collect()
                    samples.append(
                        {
                            "step": state.value,
                            "rss_bytes": rss_bytes(),
                            "retained_measurements": len(recorder.measurements),
                            "live_metric_observations": sum(
                                item() is not None
                                for item in recorder.metric_references
                            ),
                            "live_output_records": sum(
                                item() is not None
                                for item in recorder.output_references
                            ),
                        }
                    )

        result = run_training_process(
            train,
            run_id=target.run_id,
            project_version="project@synthetic",
            configuration={
                "checkpoint": {"cadence": args.steps + 1},
                "metrics": {
                    "flushInterval": 3600,
                    "segmentRoll": 64,
                    "systemSamplingInterval": 1,
                },
            },
            dataset=SyntheticDataset(args.steps),
            metric_contracts=SyntheticContracts(),
            skywright_metric_schema="synthetic@1",
            recorder=recorder,
            seed=17,
            cgroup_memory_reader=rss_bytes,
            system_sampler_wait=wait_sample,
        )
        drain()
        trace.close()
        assert result.outcome.value == "completed", dict(result.report.diagnostics)
        assert result.final_checkpoint is not None
        assert len(samples) >= 2
        max_growth = (
            max(item["rss_bytes"] for item in samples) - samples[0]["rss_bytes"]
        )
        max_metrics = max(item["live_metric_observations"] for item in samples)
        max_outputs = max(item["live_output_records"] for item in samples)
        max_measurements = peak_measurements
        training_seconds = time.monotonic() - started

        # Verify every persisted output independently after the memory measurement.
        protocol = recorder.protocol
        for step in range(1, args.steps + 1):
            for key, byte in (
                (
                    protocol.artifact_key(
                        result.attempt.attempt_id, step, "artifact.bin"
                    ),
                    step % 256,
                ),
                (
                    protocol.sample_key(result.attempt.attempt_id, step, "sample.bin"),
                    (step + 1) % 256,
                ),
            ):
                response = client.get_object(Bucket=args.bucket, Key=key)
                with response["Body"] as body:
                    content = body.read()
                assert content == bytes([byte]) * args.payload_bytes
                assert (
                    hashlib.sha256(content).hexdigest()
                    == response["Metadata"]["skywright-sha256"]
                )
        persisted: dict[str, set[int]] = {
            name: set() for name in recorder.metric_counts
        }
        metric_objects = 0
        for page in client.get_paginator("list_objects_v2").paginate(
            Bucket=args.bucket, Prefix=f"{protocol.run_prefix}metrics/"
        ):
            for item in page.get("Contents", []):
                path = work / "verify.tfevents"
                response = client.get_object(Bucket=args.bucket, Key=item["Key"])
                with response["Body"] as body:
                    path.write_bytes(body.read())
                metric_objects += 1
                for event in EventFileLoader(str(path)).Load():
                    for value in event.summary.value:
                        if value.tag in persisted:
                            assert event.step not in persisted[value.tag], (
                                "duplicate persisted metric"
                            )
                            persisted[value.tag].add(event.step)
                            if value.tag == "train/loss":
                                assert (
                                    float(tensor_util.make_ndarray(value.tensor))
                                    == event.step
                                )
        for name, steps in persisted.items():
            assert steps == set(range(1, args.steps + 1)), name
        assert len(persisted) == 4
        assert all(count == args.steps for count in recorder.metric_counts.values())
        reader = RunStoreReader(target, client=client)
        checkpoint = reader.read_exact(result.final_checkpoint.reference)
        assert checkpoint.state["counter"] == {"value": args.steps}
        assert reader.read_progress().current_step == args.steps
        evidence = {
            "steps": args.steps,
            "payload_bytes": args.payload_bytes,
            "persisted_output_bytes": 2 * args.steps * args.payload_bytes,
            "persisted_outputs_verified": 2 * args.steps,
            "persisted_observations_verified": 4 * args.steps,
            "metric_segments": metric_objects,
            "training_seconds": training_seconds,
            "memory_samples": samples,
            "rss_growth_after_warmup_bytes": max_growth,
            "max_live_metric_observations": max_metrics,
            "max_live_output_records": max_outputs,
            "max_retained_measurements": max_measurements,
            "delivered_measurements": delivered,
            "explicitly_missing_measurements": missing,
            "measurement_through_sequence": through,
            "budgets": {
                "rss_growth_bytes": 32 * MIB,
                "live_metrics": 4,
                "live_outputs": 0,
                "measurements": 256,
            },
        }
        print(json.dumps(evidence), flush=True)
        if args.expect_bounded:
            assert max_growth < 32 * MIB, "runtime RSS grows with published history"
            assert max_metrics <= 4, "committed/background metrics remain owned"
            assert max_outputs == 0, "published output records remain owned"
            assert max_measurements <= 256, "request history grows without bound"
            assert delivered + missing == through
        return evidence


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--steps", type=int, default=3000)
    parser.add_argument("--warmup", type=int, default=512)
    parser.add_argument("--sample-every", type=int, default=256)
    parser.add_argument("--payload-bytes", type=int, default=65536)
    parser.add_argument("--expect-bounded", action="store_true")
    args = parser.parse_args()
    assert (
        0 < args.warmup < args.steps
        and args.sample_every > 0
        and args.payload_bytes > 0
    )
    run(args)


if __name__ == "__main__":
    main()
