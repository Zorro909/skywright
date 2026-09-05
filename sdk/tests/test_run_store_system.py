# boto3 and the Docker-backed fixture expose runtime-shaped integration values.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownLambdaType=false, reportUnknownParameterType=false
# pyright: reportUnknownVariableType=false

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
import uuid
from contextlib import contextmanager
from pathlib import Path

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError
from tensorboard.backend.event_processing.event_file_loader import EventFileLoader

from skywright import (
    ArtifactRecord,
    CheckpointSnapshot,
    DatasetBatch,
    DatasetCursor,
    ExecutionAttemptRecord,
    MetricCatalog,
    TrainingContractViolation,
    run_training_process,
)
from skywright.metrics import MetricSchema
from skywright.run_store import (
    CheckpointCodec,
    CheckpointReference,
    RunStoreProtocol,
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)

SEAWEEDFS_IMAGE = (
    "docker.io/chrislusf/seaweedfs:4.42@"
    "sha256:f7cbc8bdbbf60a1aaba7d61784a3bdff3ec1e0657f6ad0b26d5b6ab2cd9d0dc6"
)


class OneItemDataset:
    ordering_fingerprint = "sha256:ordering"

    def batches(self, cursor):
        if cursor.item_offset == 0:
            yield DatasetBatch(
                ("item",), DatasetCursor(0, 1, 1, self.ordering_fingerprint)
            )


class EmptyMetricContracts:
    def compose(self, project_version, skywright_schema_identity):
        return MetricCatalog(
            project_version,
            "sha256:project",
            skywright_schema_identity,
            "sha256:skywright",
            frozenset(MetricSchema.units()),
            (),
            MetricSchema.definitions(),
        )


class State:
    def __init__(self):
        self.value = 0

    def state_dict(self):
        return {"value": self.value}

    def load_state_dict(self, state):
        self.value = state["value"]


class RecordingProgress:
    def __init__(self):
        self.events = []

    def publish_step(self, *values):
        self.events.append(("step", *values))

    def publish_wall_time(self, observation):
        self.events.append(("wall_time", observation))

    def confirm_checkpoint(self, step, reference):
        self.events.append(("confirmation", step, reference))


@contextmanager
def seaweedfs(credential_file: Path | None = None):
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    container = (
        subprocess.run(
            [
                "docker",
                "run",
                "-d",
                "--rm",
                *(
                    ["-v", f"{credential_file}:/etc/skywright-s3.json:ro,Z"]
                    if credential_file
                    else []
                ),
                "-p",
                f"127.0.0.1:{port}:8333",
                SEAWEEDFS_IMAGE,
                "mini",
                "-master.telemetry=false",
                *(["-s3.config=/etc/skywright-s3.json"] if credential_file else []),
            ],
            check=True,
            text=True,
            capture_output=True,
        )
        .stdout.strip()
        .splitlines()[-1]
    )
    try:
        endpoint = f"http://127.0.0.1:{port}"
        client = boto3.client(
            "s3",
            endpoint_url=endpoint,
            region_name="us-east-1",
            aws_access_key_id="test-access-key",
            aws_secret_access_key="test-secret-key",
            config=Config(s3={"addressing_style": "path"}),
        )
        deadline = time.monotonic() + 20
        while True:
            try:
                client.list_buckets()
                break
            except Exception:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.1)
        yield endpoint, client
    finally:
        try:
            service_logs = Path("target/service-logs")
            service_logs.mkdir(parents=True, exist_ok=True)
            logs = subprocess.run(
                ["docker", "logs", container],
                check=False,
                text=True,
                capture_output=True,
            )
            service_logs.joinpath(f"seaweedfs-{container[:12]}.log").write_text(
                logs.stdout + logs.stderr,
                encoding="utf-8",
            )
        finally:
            subprocess.run(
                ["docker", "rm", "-f", container],
                check=False,
                text=True,
                capture_output=True,
            )


def test_readiness_failure_retains_diagnostics_and_removes_service(
    tmp_path, monkeypatch
) -> None:
    fake_docker = tmp_path / "docker"
    removed = tmp_path / "removed"
    fake_docker.write_text(
        """#!/bin/sh
case "$1" in
  run) echo container-id ;;
  logs) echo readiness-failed ;;
  rm) printf removed > "$SKYWRIGHT_FAKE_DOCKER_STATE" ;;
esac
""",
        encoding="utf-8",
    )
    fake_docker.chmod(0o755)
    monkeypatch.setenv("PATH", f"{tmp_path}:{os.environ['PATH']}")
    monkeypatch.setenv("SKYWRIGHT_FAKE_DOCKER_STATE", str(removed))

    class UnreadyClient:
        def list_buckets(self):
            raise RuntimeError("service never became ready")

    monkeypatch.setattr(boto3, "client", lambda *args, **kwargs: UnreadyClient())
    moments = iter((0.0, 21.0))
    monkeypatch.setattr(time, "monotonic", lambda: next(moments))
    monkeypatch.setattr(time, "sleep", lambda _: None)

    with (
        pytest.raises(RuntimeError, match="service never became ready"),
        seaweedfs(),
    ):
        pytest.fail("an unready service must not be yielded")

    assert removed.read_text(encoding="utf-8") == "removed"
    assert (
        Path("target/service-logs/seaweedfs-container-id.log").read_text(
            encoding="utf-8"
        )
        == "readiness-failed\n"
    )


@pytest.mark.system
def test_registered_descriptor_uses_standard_credentials_against_pinned_seaweedfs(
    tmp_path, monkeypatch
) -> None:
    with seaweedfs() as (endpoint, client):
        monkeypatch.setenv("AWS_ACCESS_KEY_ID", "test-access-key")
        monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "test-secret-key")
        monkeypatch.setenv("AWS_DEFAULT_REGION", "us-east-1")
        observed_checksum_modes: list[str] = []
        standard_session = boto3.Session

        def recording_session(*args, **kwargs):
            delegate = standard_session(*args, **kwargs)

            class RecordingSession:
                def client(self, service_name, **client_kwargs):
                    observed_checksum_modes.append(
                        client_kwargs["config"].request_checksum_calculation
                    )
                    return delegate.client(service_name, **client_kwargs)

            return RecordingSession()

        monkeypatch.setattr(boto3, "Session", recording_session)
        bucket = f"skywright-{uuid.uuid4().hex}"
        client.create_bucket(Bucket=bucket)
        target = TargetStorage(
            "seaweedfs",
            endpoint,
            bucket,
            "us-east-1",
            "project",
            "run",
            compatibility_options={"checksumCalculation": "when-supported"},
        )
        progress = RecordingProgress()
        store = RunStoreRecorder(
            target,
            progress_recorder=progress,
            checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
            multipart_threshold=1,
            multipart_part_size=5 * 1024 * 1024,
        )
        assert observed_checksum_modes == ["when_supported"]

        def train(context):
            state = State()
            context.register_checkpoint_state("state", state)
            context.start()
            state.value = 7
            batch = next(iter(context.dataset.batches(context.dataset_cursor)))
            context.persist_artifact("reports/final.txt", b"finished")
            context.persist_sample("preview.png", b"png", media_type="image/png")
            context.commit_step(batch)

        result = run_training_process(
            train,
            run_id="run",
            project_version="project@digest",
            configuration={"checkpoint": {"cadence": 1}},
            dataset=OneItemDataset(),
            metric_contracts=EmptyMetricContracts(),
            skywright_metric_schema="metrics@1",
            recorder=store,
            seed=7,
        )
        reference = result.report.latest_durable_checkpoint
        assert reference is not None, result.report
        assert result.outcome.value == "completed"
        assert [event[0] for event in progress.events] == ["step", "confirmation"]
        assert progress.events[0][4:] == (None, None)
        assert progress.events[1] == ("confirmation", 1, reference)

        reader = RunStoreReader(
            target,
            checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
        )
        assert reader.read_exact(reference).state["state"] == {"value": 7}
        checkpoint = reader.list_checkpoints()[0]
        ranged = client.get_object(
            Bucket=bucket, Key=checkpoint.key, Range="bytes=0-7"
        )["Body"].read()
        assert len(ranged) == 8

        artifact_key = next(
            item["Key"]
            for item in client.list_objects_v2(Bucket=bucket)["Contents"]
            if "/artifacts/" in item["Key"]
        )
        url = reader.presign_download(artifact_key, expires_in=60)
        with urllib.request.urlopen(url, timeout=5) as response:
            assert response.read() == b"finished"

        upload = client.create_multipart_upload(Bucket=bucket, Key="incomplete")
        client.upload_part(
            Bucket=bucket,
            Key="incomplete",
            UploadId=upload["UploadId"],
            PartNumber=1,
            Body=b"not-visible",
        )
        assert not any(
            item["Key"] == "incomplete"
            for item in client.list_objects_v2(Bucket=bucket).get("Contents", ())
        )
        assert (
            client.list_multipart_uploads(Bucket=bucket)["Uploads"][0]["UploadId"]
            == upload["UploadId"]
        )
        client.abort_multipart_upload(
            Bucket=bucket, Key="incomplete", UploadId=upload["UploadId"]
        )

        client.put_object(
            Bucket=bucket, Key="conditional", Body=b"one", IfNoneMatch="*"
        )
        with pytest.raises(ClientError) as conflict:
            client.put_object(
                Bucket=bucket, Key="conditional", Body=b"two", IfNoneMatch="*"
            )
        assert conflict.value.response["ResponseMetadata"]["HTTPStatusCode"] == 412

        qualification_target = TargetStorage(
            "seaweedfs",
            endpoint,
            bucket,
            "us-east-1",
            "project",
            "qualification",
        )
        qualification_store = RunStoreRecorder(
            qualification_target,
            client=client,
            checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
            multipart_threshold=1,
            multipart_part_size=5 * 1024 * 1024,
        )
        qualification_store.publish_attempt(
            ExecutionAttemptRecord(
                "123e4567-e89b-12d3-a456-426614174000",
                "qualification",
                "project@digest",
                None,
            )
        )
        qualification_store.publish_artifact(ArtifactRecord("result", b"same", 1))
        qualification_store.publish_artifact(ArtifactRecord("result", b"same", 1))
        with pytest.raises(
            TrainingContractViolation, match="run-output/immutable-identity"
        ):
            qualification_store.publish_artifact(
                ArtifactRecord("result", b"different", 1)
            )

        references = [
            qualification_store.publish_checkpoint(
                CheckpointSnapshot(
                    step,
                    {"value": step},
                    dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
                    run_id="qualification",
                    project_version="project@digest",
                )
            )
            for step in range(1, 5)
        ]
        qualification_reader = RunStoreReader(
            qualification_target,
            client=client,
            checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
        )
        qualification_reader.prune_checkpoints(
            retention=2,
            keep_every_nth=3,
            final_reference=references[0],
        )
        assert [item.step for item in qualification_reader.list_checkpoints()] == [
            1,
            3,
            4,
        ]

        newest = CheckpointReference.parse(references[-1])
        newest_key = qualification_store.protocol.checkpoint_key(
            newest.step, newest.digest
        )
        newest_object = client.get_object(Bucket=bucket, Key=newest_key)
        client.put_object(
            Bucket=bucket,
            Key=newest_key,
            Body=newest_object["Body"].read() + b"corrupt",
            Metadata=newest_object["Metadata"],
            ContentType=newest_object["ContentType"],
        )
        resolution = qualification_reader.resolve_latest_valid(
            project_version="project@digest",
            ordering_fingerprint="ordering",
        )
        assert resolution.checkpoint.reference == references[2]
        assert [(item.step, item.code) for item in resolution.rejected] == [
            (4, "RUN_STORE_DIGEST_MISMATCH")
        ]

        pending_key = qualification_store.protocol.run_prefix + "checkpoints/pending"
        pending = client.create_multipart_upload(Bucket=bucket, Key=pending_key)
        client.upload_part(
            Bucket=bucket,
            Key=pending_key,
            UploadId=pending["UploadId"],
            PartNumber=1,
            Body=b"pending",
        )
        incomplete = qualification_reader.list_incomplete_uploads()
        assert [(item.key, item.part_numbers) for item in incomplete] == [
            (pending_key, (1,))
        ]
        qualification_reader.abort_incomplete_upload(incomplete[0])
        assert qualification_reader.list_incomplete_uploads() == ()
        client.delete_object(Bucket=bucket, Key="conditional")
        assert not any(
            item["Key"] == "conditional"
            for item in client.list_objects_v2(Bucket=bucket).get("Contents", ())
        )


@pytest.mark.system
def test_training_lifecycle_scenarios_persist_to_pinned_seaweedfs(tmp_path) -> None:
    scenario_runner = Path(__file__).parent / "support/run_store_training_scenario.py"
    with seaweedfs() as (endpoint, client):
        bucket = f"skywright-{uuid.uuid4().hex}"
        client.create_bucket(Bucket=bucket)

        def run_scenario(scenario: str, run_id: str) -> dict[str, object]:
            completed = subprocess.run(
                [
                    sys.executable,
                    str(scenario_runner),
                    scenario,
                    "--endpoint",
                    endpoint,
                    "--bucket",
                    bucket,
                    "--run-id",
                    run_id,
                    "--staging-directory",
                    str(tmp_path / f"{run_id}-{scenario}"),
                ],
                check=True,
                text=True,
                capture_output=True,
            )
            return json.loads(completed.stdout)

        interrupted = run_scenario("interrupted", "resume-run")
        assert interrupted == {
            "cause": "interrupted",
            "checkpoint": interrupted["checkpoint"],
            "durable_step": 1,
            "initial_state": 0,
            "last_step": 1,
            "outcome": "interrupted",
            "resumed": False,
            "terminal_report_closed_writer": True,
        }
        assert isinstance(interrupted["checkpoint"], str)

        resumed = run_scenario("resume", "resume-run")
        assert resumed == {
            "cause": "completed",
            "checkpoint": resumed["checkpoint"],
            "durable_step": 2,
            "initial_state": 1,
            "last_step": 2,
            "outcome": "completed",
            "resumed": True,
            "terminal_report_closed_writer": True,
        }
        resume_target = TargetStorage(
            "seaweedfs",
            endpoint,
            bucket,
            "us-east-1",
            "project",
            "resume-run",
        )
        progress = RunStoreReader(resume_target, client=client).read_progress()
        assert progress.current_step == 2
        assert progress.latest_durable_step == 2
        assert progress.latest_durable_checkpoint == resumed["checkpoint"]
        assert progress.target_step is None
        metric_objects = [
            item
            for item in client.list_objects_v2(
                Bucket=bucket,
                Prefix=RunStoreProtocol("project", "resume-run").run_prefix
                + "metrics/",
            )["Contents"]
        ]
        metric_events = []
        for index, item in enumerate(metric_objects):
            path = tmp_path / f"resume-metrics-{index}.tfevents"
            path.write_bytes(
                client.get_object(Bucket=bucket, Key=item["Key"])["Body"].read()
            )
            metric_events.extend(EventFileLoader(str(path)).Load())
        tags = [value.tag for event in metric_events for value in event.summary.value]
        assert tags.count("skywright/run_configuration") == 1
        assert "skywright/system/throughput" in tags
        assert "skywright/system/data_loading_wait" in tags
        assert [
            event.step for event in metric_events if event.HasField("session_log")
        ] == [1]

        cancelled = run_scenario("cancelled", "cancelled-run")
        assert cancelled == {
            "cause": "cancelled",
            "checkpoint": None,
            "durable_step": None,
            "initial_state": 0,
            "last_step": 1,
            "outcome": "cancelled",
            "resumed": False,
            "terminal_report_closed_writer": True,
        }

        failed = run_scenario("failed", "failed-run")
        assert failed == {
            "cause": "training_project_failure",
            "checkpoint": None,
            "durable_step": None,
            "initial_state": 0,
            "last_step": 1,
            "outcome": "failed",
            "resumed": False,
            "terminal_report_closed_writer": True,
        }


def test_projected_storage_roles_are_enforced_by_real_service(tmp_path, monkeypatch):
    from skywright.credentials import CredentialProjectionError, s3_credentials

    config = tmp_path / "s3.json"
    config.write_text(
        json.dumps(
            {
                "identities": [
                    {
                        "name": "admin",
                        "credentials": [
                            {
                                "accessKey": "test-access-key",
                                "secretKey": "test-secret-key",
                            }
                        ],
                        "actions": ["Admin", "Read", "Write", "List"],
                    },
                    {
                        "name": "dataset",
                        "credentials": [
                            {"accessKey": "reader", "secretKey": "reader-secret"}
                        ],
                        "actions": ["Read:dataset", "List:dataset"],
                    },
                    {
                        "name": "training",
                        "credentials": [
                            {"accessKey": "writer", "secretKey": "writer-secret"}
                        ],
                        "actions": ["Read:outputs", "Write:outputs", "List:outputs"],
                    },
                ]
            }
        )
    )
    with seaweedfs(config) as (endpoint, admin):
        admin.create_bucket(Bucket="dataset")
        admin.create_bucket(Bucket="outputs")
        admin.put_object(Bucket="dataset", Key="item", Body=b"data")
        monkeypatch.setenv("SKYWRIGHT_DATASET_ACCESS_KEY_ID", "reader")
        monkeypatch.setenv("SKYWRIGHT_DATASET_SECRET_ACCESS_KEY", "reader-secret")
        monkeypatch.setenv("SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID", "writer")
        monkeypatch.setenv("SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY", "writer-secret")
        # Broader ambient credentials cannot replace either selected role.
        monkeypatch.setenv("AWS_ACCESS_KEY_ID", "test-access-key")
        monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "test-secret-key")
        reader = boto3.client(
            "s3",
            endpoint_url=endpoint,
            region_name="us-east-1",
            **s3_credentials("dataset"),
        )
        assert reader.get_object(Bucket="dataset", Key="item")["Body"].read() == b"data"
        with pytest.raises(ClientError):
            reader.put_object(Bucket="dataset", Key="forbidden", Body=b"no")
        target = TargetStorage(
            "outputs",
            endpoint,
            "outputs",
            "us-east-1",
            "project",
            "run",
            credential_slot="run_store",
        )
        writer = boto3.client(
            "s3",
            endpoint_url=endpoint,
            region_name="us-east-1",
            **s3_credentials("run_store"),
        )
        retained = RunStoreReader(target)
        writer.put_object(Bucket="outputs", Key="checkpoint", Body=b"state")
        assert (
            writer.get_object(Bucket="outputs", Key="checkpoint")["Body"].read()
            == b"state"
        )
        with pytest.raises(ClientError):
            writer.get_object(Bucket="dataset", Key="item")
        wrong_target = TargetStorage(
            "dataset",
            endpoint,
            "dataset",
            "us-east-1",
            "project",
            "run",
            credential_slot="run_store",
        )
        with pytest.raises(CredentialProjectionError, match="rejected"):
            RunStoreReader(wrong_target).list_checkpoints()
        # A constructed client's projection remains fixed after the environment changes.
        monkeypatch.setenv("SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY", "revoked-secret")
        assert (
            writer.get_object(Bucket="outputs", Key="checkpoint")["Body"].read()
            == b"state"
        )
        assert retained.list_checkpoints() == ()
        rejected = RunStoreReader(target)
        with pytest.raises(CredentialProjectionError, match="rejected") as failure:
            rejected.list_checkpoints()
        assert "revoked-secret" not in str(failure.value)
        assert len(rejected.measurements) == 1
        boundary = subprocess.run(
            [
                sys.executable,
                "-c",
                """
import json, sys
from skywright import run_training_process
from skywright.run_store import RunStoreRecorder, TargetStorage
store = RunStoreRecorder(TargetStorage("outputs", sys.argv[1], "outputs", "us-east-1", "project", "run", credential_slot="run_store"))
result = run_training_process(lambda context: None, run_id="run", project_version="fixture", configuration={}, dataset="unused:dataset", metric_contracts="unused:metrics", skywright_metric_schema="fixture", recorder=store, seed=0)
assert result.report.cause.value == "skywright_failure"
assert result.outcome.value == "failed"
assert result.report.diagnostics["exception_type"] == "CredentialProjectionError"
print(json.dumps(dict(result.report.diagnostics)))
""",
                endpoint,
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        assert "revoked-secret" not in boundary.stdout + boundary.stderr
        monkeypatch.delenv("SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID")
        with pytest.raises(CredentialProjectionError, match="unavailable"):
            RunStoreReader(target)


def test_model_optimizer_checkpoint_memory_scenario_uses_real_s3() -> None:
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="checkpoint-memory")
        result = subprocess.run(
            [
                sys.executable,
                str(
                    Path(__file__).parent / "support" / "checkpoint_memory_scenario.py"
                ),
                "--endpoint",
                endpoint,
                "--bucket",
                "checkpoint-memory",
                "--device",
                "cpu",
                "--width",
                "32",
                "--layers",
                "2",
                "--expect-bounded",
            ],
            check=True,
            text=True,
            capture_output=True,
            timeout=90,
        )
        evidence = json.loads(result.stdout)
        assert evidence["whole_state_resume_verified"] is True
        assert len(evidence["capture_ms"]) == 3
        assert evidence["upload_held_during_training_and_capture_ms"] > 0
