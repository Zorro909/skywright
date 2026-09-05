# Test doubles intentionally model boto3's runtime-shaped response dictionaries.
# pyright: reportMissingParameterType=false, reportMissingTypeArgument=false
# pyright: reportMissingImports=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false
# pyright: reportUnknownMemberType=false, reportUnknownParameterType=false
# pyright: reportUnknownVariableType=false

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path

import numpy as np
import pytest
import torch
from tensorboard.backend.event_processing.event_accumulator import EventAccumulator
from tensorboard.backend.event_processing.event_file_loader import (
    EventFileLoader,
    RawEventFileLoader,
)

from skywright import (
    ArtifactRecord,
    CheckpointSnapshot,
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    ExecutionTerminationReport,
    MetricObservation,
    SampleRecord,
    TrainingContractViolation,
)
from skywright._training_errors import ObservabilityShutdownIncomplete
from skywright.run_store import (
    CheckpointCodec,
    CheckpointReference,
    OperationControl,
    ProgressRecord,
    RunStoreCancelledError,
    RunStoreProtocol,
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)


class MemoryS3:
    def __init__(self) -> None:
        self.objects: dict[str, tuple[bytes, dict[str, str], str]] = {}
        self.uploads: dict[str, dict] = {}
        self.fail_part: int | None = None
        self.puts: list[str] = []
        self.put_bodies: dict[str, list[bytes]] = {}

    def put_object(self, **request):
        key = request["Key"]
        body = request["Body"]
        if hasattr(body, "read"):
            body = body.read()
        current = self.objects.get(key)
        current_etag = (
            f'"{hashlib.md5(current[0], usedforsecurity=False).hexdigest()}"'
            if current is not None
            else None
        )
        if (request.get("IfNoneMatch") == "*" and current is not None) or (
            "IfMatch" in request and request["IfMatch"] != current_etag
        ):
            from botocore.exceptions import ClientError

            raise ClientError(
                {"Error": {"Code": "PreconditionFailed"}, "ResponseMetadata": {}},
                "PutObject",
            )
        self.objects[key] = (body, request["Metadata"], request["ContentType"])
        self.puts.append(key)
        self.put_bodies.setdefault(key, []).append(body)
        return {}

    def get_object(self, **request):
        import io

        body, metadata, content_type = self.objects[request["Key"]]
        return {
            "Body": io.BytesIO(body),
            "Metadata": metadata,
            "ContentLength": len(body),
            "ContentType": content_type,
            "ETag": f'"{hashlib.md5(body, usedforsecurity=False).hexdigest()}"',
        }

    def list_objects_v2(self, **request):
        prefix = request["Prefix"]
        return {
            "Contents": [
                {"Key": key, "Size": len(value[0])}
                for key, value in sorted(self.objects.items())
                if key.startswith(prefix)
            ],
            "IsTruncated": False,
        }

    def delete_object(self, **request):
        self.objects.pop(request["Key"], None)
        return {}

    def generate_presigned_url(self, ClientMethod, Params, ExpiresIn):
        assert ClientMethod == "get_object"
        return f"https://download.invalid/{Params['Key']}?expires={ExpiresIn}"

    def create_multipart_upload(self, **request):
        upload_id = f"upload-{len(self.uploads) + 1}"
        self.uploads[upload_id] = {**request, "parts": {}}
        return {"UploadId": upload_id}

    def upload_part(self, **request):
        if request["PartNumber"] == self.fail_part:
            raise OSError("injected part failure")
        self.uploads[request["UploadId"]]["parts"][request["PartNumber"]] = request[
            "Body"
        ]
        return {"ETag": f"part-{request['PartNumber']}"}

    def complete_multipart_upload(self, **request):
        upload = self.uploads.pop(request["UploadId"])
        body = b"".join(upload["parts"][part] for part in sorted(upload["parts"]))
        self.objects[request["Key"]] = (
            body,
            upload["Metadata"],
            upload["ContentType"],
        )
        return {}

    def abort_multipart_upload(self, **request):
        self.uploads.pop(request["UploadId"], None)
        return {}

    def list_multipart_uploads(self, **request):
        return {
            "Uploads": [
                {"Key": upload["Key"], "UploadId": upload_id}
                for upload_id, upload in self.uploads.items()
                if upload["Key"].startswith(request["Prefix"])
            ],
            "IsTruncated": False,
        }

    def list_parts(self, **request):
        upload = self.uploads[request["UploadId"]]
        return {
            "Parts": [
                {"PartNumber": part_number} for part_number in sorted(upload["parts"])
            ]
        }


class ProgressRecorder:
    def __init__(self) -> None:
        self.steps = []

    def publish_step(self, *values) -> None:
        self.steps.append(values)

    def publish_wall_time(self, observation) -> None:
        self.steps.append(("wall_time", observation))

    def confirm_checkpoint(self, step, reference) -> None:
        self.steps.append(("confirmation", step, reference))


def recorder(memory: MemoryS3, tmp_path, progress=None, **options) -> RunStoreRecorder:
    return RunStoreRecorder(
        TargetStorage(
            storage_id="test-storage",
            endpoint_url="http://storage.invalid",
            bucket="runs",
            region="us-east-1",
            training_project_id="project",
            run_id="run",
        ),
        client=memory,
        checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
        progress_recorder=progress,
        **options,
    )


def test_resolved_target_storage_carries_non_secret_compatibility_options() -> None:
    options = {"checksumCalculation": "when-required"}
    target = TargetStorage(
        storage_id="test-storage",
        endpoint_url="http://storage.invalid",
        bucket="runs",
        region="us-east-1",
        training_project_id="project",
        run_id="run",
        compatibility_options=options,
    )

    assert target.compatibility_options == {"checksumCalculation": "when-required"}
    assert target in {target}
    options["checksumCalculation"] = "when-supported"
    assert target.compatibility_options == {"checksumCalculation": "when-required"}


def test_protocol_builds_portable_v1_object_identities() -> None:
    protocol = RunStoreProtocol("project-\u03b1", "run-1")

    assert protocol.run_prefix == "project-%CE%B1/run-1/v1/"
    assert protocol.checkpoint_key(42, "a" * 64) == (
        "project-%CE%B1/run-1/v1/checkpoints/0000000000000000042/"
        + "a" * 64
        + ".safetensors"
    )
    assert protocol.artifact_key(
        "123e4567-e89b-12d3-a456-426614174000", 7, "plots/loss 100%.png"
    ).endswith(
        "artifacts/123e4567-e89b-12d3-a456-426614174000/"
        "0000000000000000007/plots%2Floss%20100%25.png"
    )
    assert protocol.sample_key(
        "123e4567-e89b-12d3-a456-426614174000", 7, "é/é"
    ).endswith(
        "samples/123e4567-e89b-12d3-a456-426614174000/0000000000000000007/%C3%A9%2Fe%CC%81"
    )
    assert protocol.metric_segment_key("123e4567-e89b-12d3-a456-426614174000", 2) == (
        "project-%CE%B1/run-1/v1/metrics/"
        "123e4567-e89b-12d3-a456-426614174000/"
        "events.out.tfevents.0000000000000000002.skywright"
    )
    assert protocol.progress_key() == "project-%CE%B1/run-1/v1/progress.json"


def test_checkpoint_reference_round_trips_without_a_storage_location() -> None:
    reference = CheckpointReference(step=42, digest="b" * 64)

    assert str(reference) == f"skywright-checkpoint:v1:42:sha256:{'b' * 64}"
    assert CheckpointReference.parse(str(reference)) == reference


@pytest.mark.parametrize(
    "value",
    [
        "skywright-checkpoint:v2:1:sha256:" + "a" * 64,
        "skywright-checkpoint:v1:-1:sha256:" + "a" * 64,
        "skywright-checkpoint:v1:1:sha256:not-a-digest",
    ],
)
def test_checkpoint_reference_rejects_noncanonical_values(value: str) -> None:
    with pytest.raises(ValueError, match="checkpoint reference"):
        CheckpointReference.parse(value)


def test_protocol_rejects_invalid_identities_before_key_construction() -> None:
    with pytest.raises(ValueError, match="Run identity"):
        RunStoreProtocol("project", "")
    with pytest.raises(ValueError, match="Step"):
        RunStoreProtocol("project", "run").checkpoint_key(-1, "a" * 64)
    with pytest.raises(ValueError, match="Execution Attempt"):
        RunStoreProtocol("project", "run").attempt_record_key("not-a-uuid")


def test_python_accepts_the_shared_run_store_golden_corpus() -> None:
    corpus = json.loads(
        (Path(__file__).parents[2] / "protocol/run-store/v1/golden.json").read_text()
    )
    for case in corpus["identities"]:
        protocol = RunStoreProtocol(case["project"], case["run"])
        assert protocol.run_prefix == case["runPrefix"]
        assert (
            protocol.checkpoint_key(case["step"], case["digest"])
            == case["checkpointKey"]
        )
        assert (
            protocol.artifact_key(case["attempt"], case["step"], case["outputName"])
            == case["artifactKey"]
        )
        assert (
            protocol.metric_segment_key(case["attempt"], case["step"])
            == case["metricSegmentKey"]
        )
        assert protocol.progress_key() == case["progressKey"]
        assert (
            str(CheckpointReference(case["step"], case["digest"]))
            == case["checkpointReference"]
        )
    for reference in corpus["invalidReferences"]:
        with pytest.raises(ValueError):
            CheckpointReference.parse(reference)
    for case in corpus["progressRecords"]:
        progress = ProgressRecord.decode(case["json"].encode())
        assert progress.run_id == case["runId"]
        assert progress.current_step == case["currentStep"]
        assert progress.latest_durable_step == case["latestDurableStep"]
        assert progress.latest_durable_checkpoint == case["latestDurableCheckpoint"]
        assert progress.target_step == case["targetStep"]
        assert progress.written_at == case["writtenAt"]
    for invalid in corpus["invalidProgressRecords"]:
        with pytest.raises(ValueError, match="RUN_STORE_INCOMPATIBLE_SCHEMA"):
            ProgressRecord.decode(invalid.encode())
    for invalid in corpus["invalidProgressStepRecords"]:
        with pytest.raises(ValueError, match="RUN_STORE_MALFORMED_PROGRESS"):
            ProgressRecord.decode(invalid.encode())


def test_python_applies_shared_progress_integrity_metadata(tmp_path) -> None:
    corpus = json.loads(
        (Path(__file__).parents[2] / "protocol/run-store/v1/golden.json").read_text()
    )
    body = corpus["progressRecords"][0]["json"].encode()
    key = RunStoreProtocol("project", "run-1").progress_key()
    target = TargetStorage(
        "storage", "http://storage.invalid", "runs", "us-east-1", "project", "run-1"
    )
    for metadata_case in corpus["progressIntegrityMetadata"]:
        memory = MemoryS3()
        metadata = {
            "skywright-sha256": hashlib.sha256(body).hexdigest(),
            "skywright-size": str(len(body)),
            **metadata_case["metadata"],
        }
        memory.objects[key] = (body, metadata, "application/json")
        if metadata_case["valid"]:
            assert (
                RunStoreReader(target, client=memory).read_progress().run_id == "run-1"
            )
        else:
            with pytest.raises(Exception, match="RUN_STORE_METADATA_MISMATCH"):
                RunStoreReader(target, client=memory).read_progress()


def test_checkpoint_codec_round_trips_the_portable_value_tree(tmp_path) -> None:
    snapshot = CheckpointSnapshot(
        step=7,
        state={
            "model": {
                "tensor": torch.tensor([[1.5, 2.5]], dtype=torch.float32),
                "array": np.array([1, 513], dtype=">i2"),
                "scalar": np.float32(0.25),
                "opaque": b"\x00\xff",
                "values": (None, True, -4, 1.25, "é", [3]),
                4: "integer-key",
            }
        },
        runtime_state={"python_random": (3, (1, 2), None)},
        dataset_cursor=DatasetCursor(2, 9, 4, "sha256:ordering"),
        run_id="run-1",
        project_version="project@digest",
    )

    with CheckpointCodec(staging_directory=tmp_path).serialize(snapshot) as encoded:
        assert encoded.path.stat().st_mode & 0o777 == 0o600
        assert encoded.size == encoded.path.stat().st_size
        assert len(encoded.digest) == 64
        restored = CheckpointCodec().deserialize(
            encoded.path, expected_digest=encoded.digest
        )

    assert not encoded.path.exists()
    assert restored.step == 7
    assert restored.run_id == "run-1"
    assert restored.project_version == "project@digest"
    assert restored.dataset_cursor == DatasetCursor(2, 9, 4, "sha256:ordering")
    model = restored.state["model"]
    assert isinstance(model, dict)
    assert torch.equal(model["tensor"], torch.tensor([[1.5, 2.5]]))
    assert np.array_equal(model["array"], np.array([1, 513], dtype=">i2"))
    assert model["array"].dtype == np.dtype(">i2")
    assert type(model["scalar"]) is np.float32
    assert model["opaque"] == b"\x00\xff"
    assert model["values"] == (None, True, -4, 1.25, "é", [3])
    assert model[4] == "integer-key"


def test_checkpoint_codec_rejects_executable_python_values_before_staging(
    tmp_path,
) -> None:
    class ProjectObject:
        pass

    snapshot = CheckpointSnapshot(step=1, state={"unsafe": ProjectObject()})

    with pytest.raises(
        TrainingContractViolation, match="checkpoint-state/portable-value"
    ):
        CheckpointCodec(staging_directory=tmp_path).serialize(snapshot)

    assert list(tmp_path.iterdir()) == []


def test_checkpoint_codec_detects_corruption_before_decoding(tmp_path) -> None:
    snapshot = CheckpointSnapshot(step=1, state={"value": 1})
    codec = CheckpointCodec(staging_directory=tmp_path)

    with codec.serialize(snapshot) as encoded:
        with encoded.path.open("ab") as stream:
            stream.write(b"corruption")
        os.chmod(encoded.path, 0o600)

        with pytest.raises(ValueError, match="RUN_STORE_DIGEST_MISMATCH"):
            codec.deserialize(encoded.path, expected_digest=encoded.digest)


def test_recorder_publishes_attempt_outputs_checkpoint_and_report(tmp_path) -> None:
    memory = MemoryS3()
    progress = ProgressRecorder()
    store = recorder(memory, tmp_path, progress)
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)

    reference = store.publish_checkpoint(
        CheckpointSnapshot(
            3,
            {"state": {"value": 2}},
            dataset_cursor=DatasetCursor(0, 3, 3, "ordering"),
            run_id="run",
            project_version="project@digest",
        )
    )
    store.confirm_checkpoint(3, reference)
    store.publish_step(3, DatasetCursor(item_offset=3), (), 3, reference)
    memory_observation = MetricObservation("skywright/system/memory_used", 3, 42)
    store.publish_wall_time(memory_observation)
    store.publish_artifact(ArtifactRecord("weights/raw", b"artifact", 3))
    store.publish_sample(SampleRecord("preview.png", b"png", "image/png", 3))
    report = ExecutionTerminationReport(
        1,
        attempt.attempt_id,
        "run",
        "project@digest",
        ExecutionTerminationCause.COMPLETED,
        3,
        3,
        reference,
        {},
    )
    store.publish_report(report)

    assert CheckpointReference.parse(reference).step == 3
    assert len(progress.steps) == 3
    assert progress.steps[0] == ("confirmation", 3, reference)
    assert progress.steps[1][0] == 3
    assert progress.steps[2] == ("wall_time", memory_observation)
    artifacts = [value for key, value in memory.objects.items() if "/artifacts/" in key]
    samples = [value for key, value in memory.objects.items() if "/samples/" in key]
    assert artifacts == [(b"artifact", artifacts[0][1], "application/octet-stream")]
    assert samples == [(b"png", samples[0][1], "image/png")]
    assert all(value[1]["skywright-sha256"] for value in memory.objects.values())
    assert all(
        value[1]["skywright-size"] == str(len(value[0]))
        for value in memory.objects.values()
    )


def test_recorder_persists_tensorboard_metrics_then_progress_then_report(
    tmp_path,
) -> None:
    memory = MemoryS3()
    store = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_wall_clock=lambda: 1_700_000_000.25,
    )
    store.configure_metrics(
        {
            "metrics": {"flushInterval": 10, "segmentRoll": 1000},
            "project": {"nested": [1, None, {"rate": 0.125}]},
        }
    )
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)
    staged = list(tmp_path.glob("skywright-metrics-*"))
    assert len(staged) == 1
    assert staged[0].stat().st_mode & 0o777 == 0o600
    store.publish_step(
        3,
        DatasetCursor(item_offset=3),
        (
            MetricObservation("loss", 3, 1.5),
            MetricObservation("skywright/system/throughput", 3, 8),
        ),
        None,
        None,
    )
    store.publish_wall_time(MetricObservation("skywright/system/memory_used", 3, 42))
    report = ExecutionTerminationReport(
        1,
        attempt.attempt_id,
        "run",
        "project@digest",
        ExecutionTerminationCause.COMPLETED,
        3,
        None,
        None,
        {},
    )

    store.finalize_observability()
    store.publish_report(report)
    assert list(tmp_path.glob("skywright-metrics-*")) == []

    segment_key = store.protocol.metric_segment_key(attempt.attempt_id, 0)
    progress_key = store.protocol.progress_key()
    report_key = store.protocol.attempt_report_key(attempt.attempt_id)
    assert memory.puts.index(segment_key) < memory.puts.index(progress_key)
    assert memory.puts.index(progress_key) < memory.puts.index(report_key)
    progress = json.loads(memory.objects[progress_key][0])
    assert progress == {
        "schemaVersion": 1,
        "runId": "run",
        "currentStep": 3,
        "latestDurableStep": None,
        "latestDurableCheckpoint": None,
        "writtenAt": "2023-11-14T22:13:20.250000Z",
    }
    read_progress = RunStoreReader(store.target, client=memory).read_progress()
    assert read_progress.current_step == 3
    assert read_progress.target_step is None

    event_path = tmp_path / "published.tfevents"
    event_path.write_bytes(memory.objects[segment_key][0])
    events = list(EventFileLoader(str(event_path)).Load())
    assert events[0].file_version == "brain.Event:2"
    summaries = {
        value.tag: (event.step, event.wall_time, value)
        for event in events
        for value in event.summary.value
    }
    assert summaries["loss"][:2] == (3, 1_700_000_000.25)
    assert summaries["loss"][2].tensor.float_val == [1.5]
    assert summaries["skywright/system/throughput"][2].tensor.float_val == [8]
    assert summaries["skywright/system/memory_used"][:2] == (
        3,
        1_700_000_000.25,
    )
    configuration = summaries["skywright/run_configuration"][2]
    assert configuration.tensor.string_val == [
        b'{"metrics":{"flushInterval":10,"segmentRoll":1000},'
        b'"project":{"nested":[1,null,{"rate":0.125}]}}'
    ]


def test_progress_write_time_is_captured_after_segment_publication(tmp_path) -> None:
    class Clock:
        value = 100.0

        def __call__(self) -> float:
            return self.value

    clock = Clock()

    class AdvancingMemory(MemoryS3):
        def put_object(self, **request):
            result = super().put_object(**request)
            if "/metrics/" in request["Key"]:
                clock.value = 200.0
            return result

    memory = AdvancingMemory()
    store = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_wall_clock=clock,
    )
    store.configure_metrics({"metrics": {"flushInterval": 10, "segmentRoll": 1000}})
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 1),),
        None,
        None,
    )

    store.finalize_observability()

    progress = json.loads(memory.objects[store.protocol.progress_key()][0])
    assert progress["writtenAt"] == "1970-01-01T00:03:20.000000Z"


def test_metric_segments_roll_as_prefix_extensions_and_resume_with_purge(
    tmp_path,
) -> None:
    memory = MemoryS3()
    first = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_wall_clock=lambda: 100.0,
    )
    first.configure_metrics(
        {"metrics": {"flushInterval": 10, "segmentRoll": 2}, "value": 1}
    )
    first_attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    first.publish_attempt(first_attempt)
    first.publish_step(
        3,
        DatasetCursor(item_offset=3),
        tuple(MetricObservation("curve", 3, index) for index in range(3)),
        None,
        None,
    )
    checkpoint = "skywright-checkpoint:v1:2:sha256:" + "a" * 64
    first.confirm_checkpoint(2, checkpoint)
    first.publish_step(
        4,
        DatasetCursor(item_offset=4),
        (MetricObservation("curve", 4, 3),),
        2,
        checkpoint,
    )
    first.finalize_observability()

    first_key = first.protocol.metric_segment_key(first_attempt.attempt_id, 0)
    second_key = first.protocol.metric_segment_key(first_attempt.attempt_id, 1)
    assert len(memory.put_bodies[first_key]) == 1
    assert len(memory.put_bodies[second_key]) == 2
    assert memory.put_bodies[second_key][1].startswith(memory.put_bodies[second_key][0])
    live_path = tmp_path / "live.tfevents"
    live_path.write_bytes(memory.put_bodies[second_key][0])
    live_reader = RawEventFileLoader(str(live_path), detect_file_replacement=True)
    assert len(list(live_reader.Load())) == 2
    replacement = tmp_path / "replacement.tfevents"
    replacement.write_bytes(memory.put_bodies[second_key][1])
    os.replace(replacement, live_path)
    assert len(list(live_reader.Load())) == 1
    assert first_key in memory.objects
    assert second_key in memory.objects

    resumed = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_wall_clock=lambda: 200.0,
    )
    resumed.configure_metrics(
        {"metrics": {"flushInterval": 20, "segmentRoll": 5}, "value": 2}
    )
    resumed_attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174001",
        "run",
        "project@digest",
        2,
        "skywright-checkpoint:v1:2:sha256:" + "a" * 64,
    )
    resumed.publish_attempt(resumed_attempt)
    resumed.publish_step(
        3,
        DatasetCursor(item_offset=3),
        (MetricObservation("curve", 3, 9),),
        2,
        resumed_attempt.seed_checkpoint_reference,
    )
    resumed.finalize_observability()

    resumed_key = resumed.protocol.metric_segment_key(resumed_attempt.attempt_id, 0)
    event_path = tmp_path / "resumed.tfevents"
    event_path.write_bytes(memory.objects[resumed_key][0])
    events = list(EventFileLoader(str(event_path)).Load())
    assert [
        (event.step, event.session_log.status)
        for event in events
        if event.HasField("session_log")
    ] == [(2, 1)]
    assert all(
        value.tag != "skywright/run_configuration"
        for event in events
        for value in event.summary.value
    )
    combined = tmp_path / "combined"
    combined.mkdir()
    for index, key in enumerate((first_key, second_key)):
        combined.joinpath(f"events.out.tfevents.0000000100.first.{index}").write_bytes(
            memory.objects[key][0]
        )
    combined.joinpath("events.out.tfevents.0000000200.resumed.0").write_bytes(
        memory.objects[resumed_key][0]
    )
    accumulator = EventAccumulator(str(combined), purge_orphaned_data=True)
    accumulator.Reload()
    assert [(event.step, event.value) for event in accumulator.Scalars("curve")] == [
        (3, 9.0)
    ]


def test_configured_periodic_flush_publishes_a_readable_prefix(tmp_path) -> None:
    class ControlledWait:
        def __init__(self) -> None:
            self.waiting = threading.Event()
            self.flush_now = threading.Event()
            self.calls = 0

        def __call__(self, stop: threading.Event, interval: float) -> bool:
            assert interval == 7
            self.calls += 1
            if self.calls == 1:
                self.waiting.set()
                self.flush_now.wait(timeout=2)
                return False
            stop.wait(timeout=2)
            return True

    wait = ControlledWait()
    memory = MemoryS3()
    store = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_periodic_wait=wait,
    )
    store.configure_metrics({"metrics": {"flushInterval": 7, "segmentRoll": 1000}})
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 2),),
        None,
        None,
    )
    assert wait.waiting.wait(timeout=2)
    key = store.protocol.metric_segment_key(attempt.attempt_id, 0)
    assert key not in memory.objects

    wait.flush_now.set()
    deadline = time.monotonic() + 2
    while key not in memory.objects and time.monotonic() < deadline:
        time.sleep(0.001)

    assert key in memory.objects
    store.finalize_observability()


def test_metric_publication_reconciles_a_lost_success_response(tmp_path) -> None:
    class LostResponseMemory(MemoryS3):
        def __init__(self) -> None:
            super().__init__()
            self.lost = False

        def put_object(self, **request):
            response = super().put_object(**request)
            if "/metrics/" in request["Key"] and not self.lost:
                self.lost = True
                raise OSError("response lost after storage committed the object")
            return response

    memory = LostResponseMemory()
    store = recorder(memory, tmp_path, metric_staging_directory=tmp_path)
    store.configure_metrics({"metrics": {"flushInterval": 10, "segmentRoll": 1000}})
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 1),),
        None,
        None,
    )

    store.finalize_observability()

    assert memory.lost
    assert store.protocol.metric_segment_key(attempt.attempt_id, 0) in memory.objects


def test_first_metric_flush_accepts_the_s3_missing_object_response(tmp_path) -> None:
    class S3MissingMemory(MemoryS3):
        def get_object(self, **request):
            if request["Key"] not in self.objects:
                from botocore.exceptions import ClientError

                raise ClientError(
                    {
                        "Error": {"Code": "NoSuchKey"},
                        "ResponseMetadata": {"HTTPStatusCode": 404},
                    },
                    "GetObject",
                )
            return super().get_object(**request)

    memory = S3MissingMemory()
    store = recorder(memory, tmp_path, metric_staging_directory=tmp_path)
    store.configure_metrics({"metrics": {"flushInterval": 10, "segmentRoll": 1000}})
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 1),),
        None,
        None,
    )

    store.finalize_observability()

    assert store.protocol.metric_segment_key(attempt.attempt_id, 0) in memory.objects


def test_metric_publication_rejects_a_non_prefix_replacement(tmp_path) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path, metric_staging_directory=tmp_path)
    store.configure_metrics({"metrics": {"flushInterval": 10, "segmentRoll": 1000}})
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 1),),
        None,
        None,
    )
    checkpoint = "skywright-checkpoint:v1:1:sha256:" + "a" * 64
    store.confirm_checkpoint(1, checkpoint)
    key = store.protocol.metric_segment_key(attempt.attempt_id, 0)
    _, metadata, content_type = memory.objects[key]
    conflicting = b"valid metadata, conflicting bytes"
    memory.objects[key] = (
        conflicting,
        {
            **metadata,
            "skywright-size": str(len(conflicting)),
            "skywright-sha256": hashlib.sha256(conflicting).hexdigest(),
        },
        content_type,
    )
    store.publish_step(
        2,
        DatasetCursor(item_offset=2),
        (MetricObservation("loss", 2, 0.5),),
        1,
        checkpoint,
    )

    with pytest.raises(RuntimeError, match="changed concurrently"):
        store.finalize_observability()


def test_background_metric_failure_is_latched_for_the_next_interaction(
    tmp_path,
) -> None:
    failed = threading.Event()
    flush_now = threading.Event()

    class FailingMemory(MemoryS3):
        def put_object(self, **request):
            if "/metrics/" in request["Key"]:
                failed.set()
                raise RuntimeError("metric write failed")
            return super().put_object(**request)

    class ControlledWait:
        def __init__(self) -> None:
            self.first = True

        def __call__(self, stop: threading.Event, interval: float) -> bool:
            if self.first:
                self.first = False
                flush_now.wait(timeout=2)
                return False
            stop.wait(timeout=2)
            return True

    memory = FailingMemory()
    store = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_periodic_wait=ControlledWait(),
    )
    store.configure_metrics({"metrics": {"flushInterval": 1, "segmentRoll": 1000}})
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000",
            "run",
            "project@digest",
            None,
        )
    )
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 1),),
        None,
        None,
    )
    flush_now.set()
    assert failed.wait(timeout=2)

    with pytest.raises(RuntimeError, match="metric write failed"):
        store.publish_wall_time(MetricObservation("memory", 1, 1))
    with pytest.raises(RuntimeError, match="metric write failed"):
        store.finalize_observability()
    assert list(tmp_path.glob("skywright-metrics-*")) == []


def test_periodic_upload_does_not_block_step_acceptance(tmp_path) -> None:
    flush_now = threading.Event()
    upload_started = threading.Event()
    release_upload = threading.Event()

    class BlockingMemory(MemoryS3):
        def put_object(self, **request):
            if "/metrics/" in request["Key"]:
                upload_started.set()
                assert release_upload.wait(timeout=2)
            return super().put_object(**request)

    class ControlledWait:
        def __init__(self) -> None:
            self.first = True

        def __call__(self, stop: threading.Event, interval: float) -> bool:
            if self.first:
                self.first = False
                flush_now.wait(timeout=2)
                return False
            stop.wait(timeout=2)
            return True

    memory = BlockingMemory()
    store = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_periodic_wait=ControlledWait(),
    )
    store.configure_metrics({"metrics": {"flushInterval": 1, "segmentRoll": 1000}})
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000",
            "run",
            "project@digest",
            None,
        )
    )
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 1),),
        None,
        None,
    )
    flush_now.set()
    assert upload_started.wait(timeout=2)
    accepted = threading.Event()

    def accept_next_step() -> None:
        store.publish_step(
            2,
            DatasetCursor(item_offset=2),
            (MetricObservation("loss", 2, 0.5),),
            None,
            None,
        )
        accepted.set()

    acceptance = threading.Thread(target=accept_next_step)
    acceptance.start()
    try:
        assert accepted.wait(timeout=0.2)
    finally:
        release_upload.set()
        acceptance.join(timeout=2)
    store.finalize_observability()


def test_observability_finalization_respects_the_shutdown_grace(tmp_path) -> None:
    flush_now = threading.Event()
    upload_started = threading.Event()
    release_upload = threading.Event()

    class BlockingMemory(MemoryS3):
        def put_object(self, **request):
            if "/metrics/" in request["Key"]:
                upload_started.set()
                release_upload.wait(timeout=2)
            return super().put_object(**request)

    class ControlledWait:
        first = True

        def __call__(self, stop: threading.Event, interval: float) -> bool:
            if not self.first:
                return stop.wait(timeout=2)
            self.first = False
            flush_now.wait(timeout=2)
            return False

    memory = BlockingMemory()
    store = recorder(
        memory,
        tmp_path,
        metric_staging_directory=tmp_path,
        metric_periodic_wait=ControlledWait(),
    )
    store.configure_metrics(
        {"metrics": {"flushInterval": 1, "segmentRoll": 1000}},
        shutdown_grace_seconds=0.05,
    )
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000",
            "run",
            "project@digest",
            None,
        )
    )
    store.publish_step(
        1,
        DatasetCursor(item_offset=1),
        (MetricObservation("loss", 1, 1),),
        None,
        None,
    )
    flush_now.set()
    assert upload_started.wait(timeout=2)

    started = time.monotonic()
    try:
        with pytest.raises(
            ObservabilityShutdownIncomplete, match="shutdown grace deadline"
        ):
            store.finalize_observability()
        assert time.monotonic() - started < 0.5
        report = ExecutionTerminationReport(
            1,
            "123e4567-e89b-12d3-a456-426614174000",
            "run",
            "project@digest",
            ExecutionTerminationCause.COMPLETED,
            1,
            None,
            None,
            {},
        )
        with pytest.raises(ObservabilityShutdownIncomplete):
            store.publish_report(report)
        assert (
            store.protocol.attempt_report_key(report.attempt_id) not in memory.objects
        )
    finally:
        release_upload.set()


def test_recorder_reconciles_identical_retries_and_rejects_conflicts(tmp_path) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path)
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )

    store.publish_attempt(attempt)
    store.publish_attempt(attempt)
    store.publish_artifact(ArtifactRecord("result", b"same", 1))
    store.publish_artifact(ArtifactRecord("result", b"same", 1))
    with pytest.raises(
        TrainingContractViolation, match="run-output/immutable-identity"
    ):
        store.publish_artifact(ArtifactRecord("result", b"different", 1))


def test_checkpoint_confirmation_is_monotonic_idempotent_and_immutable(
    tmp_path,
) -> None:
    memory = MemoryS3()
    progress = ProgressRecorder()
    store = recorder(memory, tmp_path, progress)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000",
            "run",
            "project@digest",
            None,
        )
    )
    older = store.publish_checkpoint(
        CheckpointSnapshot(3, {"state": {"value": 3}}, run_id="run")
    )
    newer = store.publish_checkpoint(
        CheckpointSnapshot(4, {"state": {"value": 4}}, run_id="run")
    )

    store.confirm_checkpoint(3, older)
    store.confirm_checkpoint(3, older)
    store.confirm_checkpoint(4, newer)
    store.confirm_checkpoint(3, older)

    assert progress.steps == [
        ("confirmation", 3, older),
        ("confirmation", 4, newer),
    ]
    conflicting = str(CheckpointReference(4, "0" * 64))
    with pytest.raises(
        TrainingContractViolation, match="checkpoint/confirmation-conflict"
    ):
        store.confirm_checkpoint(4, conflicting)


def test_report_closes_only_the_process_writer_partition(tmp_path) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path)
    attempt = ExecutionAttemptRecord(
        "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
    )
    store.publish_attempt(attempt)
    report = ExecutionTerminationReport(
        1,
        attempt.attempt_id,
        "run",
        "project@digest",
        ExecutionTerminationCause.CANCELLED,
        0,
        None,
        None,
        {},
    )

    store.publish_report(report)
    store.publish_report(report)
    with pytest.raises(TrainingContractViolation, match="execution-attempt/closed"):
        store.publish_sample(SampleRecord("late", b"bytes", "text/plain", 0))


def test_latest_resolution_falls_back_only_from_corrupt_checkpoints(tmp_path) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
        )
    )
    older = store.publish_checkpoint(
        CheckpointSnapshot(
            1,
            {"value": 1},
            dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
            run_id="run",
            project_version="project@digest",
        )
    )
    newest = store.publish_checkpoint(
        CheckpointSnapshot(
            2,
            {"value": 2},
            dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
            run_id="run",
            project_version="project@digest",
        )
    )
    newest_reference = CheckpointReference.parse(newest)
    newest_key = store.protocol.checkpoint_key(
        newest_reference.step, newest_reference.digest
    )
    body, metadata, content_type = memory.objects[newest_key]
    memory.objects[newest_key] = (body + b"corrupt", metadata, content_type)

    resolution = RunStoreReader(
        store.target,
        client=memory,
        checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
    ).resolve_latest_valid(
        project_version="project@digest", ordering_fingerprint="ordering"
    )

    assert resolution.checkpoint.reference == older
    assert resolution.checkpoint.state == {"value": 1}
    assert [(item.step, item.code) for item in resolution.rejected] == [
        (2, "RUN_STORE_DIGEST_MISMATCH")
    ]


def test_exact_resolution_never_substitutes_an_older_checkpoint(tmp_path) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
        )
    )
    reference = store.publish_checkpoint(
        CheckpointSnapshot(
            1, {"value": 1}, run_id="run", project_version="project@digest"
        )
    )
    parsed = CheckpointReference.parse(reference)
    key = store.protocol.checkpoint_key(parsed.step, parsed.digest)
    body, metadata, content_type = memory.objects[key]
    memory.objects[key] = (body[:-1], metadata, content_type)
    reader = RunStoreReader(
        store.target,
        client=memory,
        checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
    )

    with pytest.raises(ValueError, match="RUN_STORE_DIGEST_MISMATCH"):
        reader.read_exact(reference)


def test_retention_keeps_union_and_verifies_newer_before_deleting(tmp_path) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
        )
    )
    references = [
        store.publish_checkpoint(
            CheckpointSnapshot(
                step,
                {"value": step},
                run_id="run",
                project_version="project@digest",
            )
        )
        for step in range(1, 7)
    ]
    reader = RunStoreReader(
        store.target,
        client=memory,
        checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
    )

    reader.prune_checkpoints(
        retention=2, keep_every_nth=3, final_reference=references[1]
    )

    remaining = [item.step for item in reader.list_checkpoints()]
    assert remaining == [2, 3, 5, 6]


def test_checkpoint_multipart_publication_is_atomic_and_cleans_known_failures(
    tmp_path,
) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path, multipart_threshold=1, multipart_part_size=8)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
        )
    )

    reference = store.publish_checkpoint(
        CheckpointSnapshot(1, {"bytes": b"large-enough"}, run_id="run")
    )

    assert CheckpointReference.parse(reference).step == 1
    assert memory.uploads == {}
    assert len([key for key in memory.objects if "/checkpoints/" in key]) == 1

    memory.fail_part = 2
    with pytest.raises(OSError, match="injected part failure"):
        store.publish_checkpoint(
            CheckpointSnapshot(2, {"bytes": b"also-large"}, run_id="run")
        )
    assert memory.uploads == {}
    assert not any("0000000000000000002" in key for key in memory.objects)


def test_reader_inventories_parts_and_aborts_uploads_under_the_stable_run_prefix(
    tmp_path,
) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path)
    key = store.protocol.run_prefix + "checkpoints/pending"
    created = memory.create_multipart_upload(
        Bucket="runs", Key=key, Metadata={}, ContentType="application/octet-stream"
    )
    memory.upload_part(
        Bucket="runs",
        Key=key,
        UploadId=created["UploadId"],
        PartNumber=1,
        Body=b"part",
    )
    reader = RunStoreReader(store.target, client=memory)

    uploads = reader.list_incomplete_uploads()

    assert [(item.key, item.part_numbers) for item in uploads] == [(key, (1,))]
    reader.abort_incomplete_upload(uploads[0])
    assert reader.list_incomplete_uploads() == ()


def test_operations_honor_cancellation_and_retain_non_secret_measurements(
    tmp_path,
) -> None:
    memory = MemoryS3()
    store = recorder(memory, tmp_path)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
        )
    )

    assert store.measurements[-1].operation == "put_object"
    assert store.measurements[-1].run_id == "run"
    assert store.measurements[-1].provenance == "test-storage"
    checkpoint = CheckpointSnapshot(
        1,
        {"state": {}},
        dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
        run_id="run",
        project_version="project@digest",
    )
    store.cancel_checkpoint_publication()
    with pytest.raises(RunStoreCancelledError):
        store.publish_checkpoint(checkpoint)
    store.resume_after_checkpoint_cancellation()
    assert CheckpointReference.parse(store.publish_checkpoint(checkpoint)).step == 1

    cancelled = recorder(
        MemoryS3(),
        tmp_path,
        operation_control=OperationControl(cancellation_requested=lambda: True),
    )
    with pytest.raises(RunStoreCancelledError):
        cancelled.publish_attempt(
            ExecutionAttemptRecord(
                "123e4567-e89b-12d3-a456-426614174000",
                "run",
                "project@digest",
                None,
            )
        )


def test_capture_and_publication_use_one_owned_copy(tmp_path) -> None:
    from skywright._training_checkpoints import capture_checkpoint

    copies = []

    class CountingArray(np.ndarray):
        def __deepcopy__(self, memo):
            copies.append(1)
            return self.copy()

    source = np.array([1.0, 2.0], dtype=np.float32).view(CountingArray)

    class State:
        def state_dict(self):
            return {"weights": source}

        def load_state_dict(self, state):
            raise AssertionError("capture does not restore project state")

    state = State()
    snapshot = capture_checkpoint(
        1, {"model": state}, DatasetCursor(), "run", "version"
    )
    assert len(copies) == 1
    source[:] = 99
    with CheckpointCodec(staging_directory=tmp_path).serialize(snapshot) as staged:
        assert len(copies) == 1
        restored = CheckpointCodec().deserialize(
            staged.path, expected_digest=staged.digest
        )
    published = snapshot.with_reference("checkpoint:1")
    assert len(copies) == 1

    def weights(checkpoint):
        model_state = checkpoint.state["model"]
        assert isinstance(model_state, dict)
        return model_state["weights"]

    exposed = weights(published)
    exposed[:] = 100
    np.testing.assert_array_equal(weights(snapshot), [1, 2])
    np.testing.assert_array_equal(weights(published), [1, 2])
    np.testing.assert_array_equal(weights(restored), [1, 2])


@pytest.mark.parametrize(
    "outcome", ["success", "cancel", "failure", "confirmation-failure"]
)
def test_coordinator_releases_owned_payloads_after_publication(
    tmp_path, outcome
) -> None:
    import weakref
    from concurrent.futures import ThreadPoolExecutor

    from skywright._training_checkpoint_coordinator import CheckpointCoordinator
    from skywright._training_types import checkpoint_payload

    entered = threading.Event()
    release = threading.Event()
    cancelled = threading.Event()

    class HeldS3(MemoryS3):
        def put_object(self, **request):
            if "/checkpoints/" in request["Key"]:
                entered.set()
                assert release.wait(3)
                if outcome == "failure":
                    raise ValueError("checkpoint write failed")
            return super().put_object(**request)

    memory = HeldS3()

    class Progress(ProgressRecorder):
        def confirm_checkpoint(self, step, reference):
            if outcome == "confirmation-failure":
                raise ValueError("checkpoint confirmation failed")
            super().confirm_checkpoint(step, reference)

    progress = Progress()
    store = recorder(memory, tmp_path, progress)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "00000000-0000-0000-0000-000000000001", "run", "version", None
        )
    )
    original_cancel = store.cancel_checkpoint_publication

    def cancel():
        original_cancel()
        cancelled.set()

    store.cancel_checkpoint_publication = cancel
    coordinator = CheckpointCoordinator(store, None, 2)
    references = []

    def capture(step):
        snapshot = CheckpointSnapshot(step, {"weights": torch.ones(32)}, run_id="run")
        references.append(weakref.ref(checkpoint_payload(snapshot)[0]["weights"]))
        return snapshot

    try:
        coordinator.schedule(capture(1))
        assert entered.wait(2)
        coordinator.schedule(capture(2))
        assert references[1]() is not None
        coordinator.schedule(capture(3))
        assert references[1]() is None
        assert coordinator.durable_state() == (None, None)
        assert not progress.steps
        if outcome == "cancel":
            with ThreadPoolExecutor(max_workers=1) as stopping:
                future = stopping.submit(coordinator.stop)
                assert cancelled.wait(1)
                release.set()
                shutdown = future.result(timeout=3)
        else:
            release.set()
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                try:
                    coordinator.raise_if_failed()
                except Exception:
                    break
                if coordinator.durable_state()[0] == 3:
                    break
                time.sleep(0.001)
            shutdown = coordinator.stop()
        assert shutdown.stopped
        assert all(reference() is None for reference in references)
        if outcome == "success":
            assert shutdown.failure is None
            assert coordinator.durable_state()[0] == 3
            assert all(event[0] == "confirmation" for event in progress.steps)
        elif outcome == "cancel":
            assert shutdown.failure is None
            assert coordinator.durable_state() == (None, None)
        else:
            import traceback

            assert shutdown.failure is not None
            locations = traceback.extract_tb(shutdown.failure.__traceback__)
            assert "_publish_and_confirm" in [frame.name for frame in locations]
            assert coordinator.durable_state() == (None, None)
            with pytest.raises(type(shutdown.failure)) as rejected:
                coordinator.schedule(capture(4))
            assert rejected.value is shutdown.failure
            assert all(reference() is None for reference in references)
    finally:
        release.set()
        coordinator.stop()


@pytest.mark.parametrize(
    "value", [torch.tensor(1.0), torch.tensor(3, dtype=torch.int64)]
)
def test_checkpoint_codec_round_trips_scalar_optimizer_tensors(tmp_path, value) -> None:
    codec = CheckpointCodec(staging_directory=tmp_path)
    with codec.serialize(CheckpointSnapshot(1, {"step": value})) as staged:
        restored = codec.deserialize(staged.path, expected_digest=staged.digest)
    restored_step = restored.state["step"]
    assert isinstance(restored_step, torch.Tensor)
    assert torch.equal(restored_step, value)
    assert restored_step.shape == torch.Size([])


def test_terminal_confirmation_retains_only_identity_and_reuses_durable_step(
    tmp_path,
) -> None:
    import weakref

    from skywright import CheckpointConfirmation
    from skywright._training_checkpoint_coordinator import CheckpointCoordinator
    from skywright._training_types import checkpoint_payload

    memory = MemoryS3()
    progress = ProgressRecorder()
    store = recorder(memory, tmp_path, progress)
    store.publish_attempt(
        ExecutionAttemptRecord(
            "00000000-0000-0000-0000-000000000001", "run", "version", None
        )
    )
    coordinator = CheckpointCoordinator(store, None, 2)
    references = []

    def capture():
        snapshot = CheckpointSnapshot(1, {"weights": torch.ones(32)}, run_id="run")
        references.append(weakref.ref(checkpoint_payload(snapshot)[0]["weights"]))
        return snapshot

    try:
        confirmation = coordinator.publish_terminal(1, capture)
        assert isinstance(confirmation, CheckpointConfirmation)
        assert set(vars(confirmation)) == {"step", "reference"}
        assert confirmation.step == 1
        assert references[0]() is None
        assert coordinator.publish_terminal(1, capture) == confirmation
        assert len(references) == 1
        assert progress.steps == [("confirmation", 1, confirmation.reference)]
        loaded = RunStoreReader(store.target, client=memory).read_exact(
            confirmation.reference
        )
        weights = loaded.state["weights"]
        assert isinstance(weights, torch.Tensor)
        assert torch.equal(weights, torch.ones(32))
    finally:
        coordinator.stop()
