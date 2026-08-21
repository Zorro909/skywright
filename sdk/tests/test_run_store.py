# Test doubles intentionally model boto3's runtime-shaped response dictionaries.
# pyright: reportMissingParameterType=false, reportMissingTypeArgument=false
# pyright: reportMissingImports=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false
# pyright: reportUnknownMemberType=false, reportUnknownParameterType=false
# pyright: reportUnknownVariableType=false

from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np
import pytest
import torch

from skywright import (
    ArtifactRecord,
    CheckpointSnapshot,
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    ExecutionTerminationReport,
    SampleRecord,
    TrainingContractViolation,
)
from skywright.run_store import (
    CheckpointCodec,
    CheckpointReference,
    OperationControl,
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

    def put_object(self, **request):
        key = request["Key"]
        body = request["Body"]
        if hasattr(body, "read"):
            body = body.read()
        if request.get("IfNoneMatch") == "*" and key in self.objects:
            from botocore.exceptions import ClientError

            raise ClientError(
                {"Error": {"Code": "PreconditionFailed"}, "ResponseMetadata": {}},
                "PutObject",
            )
        self.objects[key] = (body, request["Metadata"], request["ContentType"])
        return {}

    def get_object(self, **request):
        import io

        body, metadata, content_type = self.objects[request["Key"]]
        return {
            "Body": io.BytesIO(body),
            "Metadata": metadata,
            "ContentLength": len(body),
            "ContentType": content_type,
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
            str(CheckpointReference(case["step"], case["digest"]))
            == case["checkpointReference"]
        )
    for reference in corpus["invalidReferences"]:
        with pytest.raises(ValueError):
            CheckpointReference.parse(reference)


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
    assert len(progress.steps) == 2
    assert progress.steps[0] == ("confirmation", 3, reference)
    assert progress.steps[1][0] == 3
    artifacts = [value for key, value in memory.objects.items() if "/artifacts/" in key]
    samples = [value for key, value in memory.objects.items() if "/samples/" in key]
    assert artifacts == [(b"artifact", artifacts[0][1], "application/octet-stream")]
    assert samples == [(b"png", samples[0][1], "image/png")]
    assert all(value[1]["skywright-sha256"] for value in memory.objects.values())
    assert all(
        value[1]["skywright-size"] == str(len(value[0]))
        for value in memory.objects.values()
    )


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
