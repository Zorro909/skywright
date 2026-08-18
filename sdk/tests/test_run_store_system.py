# boto3 and the Docker-backed fixture expose runtime-shaped integration values.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false

from __future__ import annotations

import socket
import subprocess
import time
import urllib.request
import uuid
from contextlib import contextmanager

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

from skywright import (
    DatasetBatch,
    DatasetCursor,
    MetricCatalog,
    run_training_process,
)
from skywright.run_store import (
    CheckpointCodec,
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
            frozenset({"dimensionless"}),
            (),
        )


class State:
    def __init__(self):
        self.value = 0

    def state_dict(self):
        return {"value": self.value}

    def load_state_dict(self, state):
        self.value = state["value"]


@contextmanager
def seaweedfs():
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
                "-p",
                f"127.0.0.1:{port}:8333",
                SEAWEEDFS_IMAGE,
                "mini",
                "-master.telemetry=false",
            ],
            check=True,
            text=True,
            capture_output=True,
        )
        .stdout.strip()
        .splitlines()[-1]
    )
    endpoint = f"http://127.0.0.1:{port}"
    client = boto3.client(
        "s3",
        endpoint_url=endpoint,
        region_name="us-east-1",
        aws_access_key_id="test-access-key",
        aws_secret_access_key="test-secret-key",
        config=Config(s3={"addressing_style": "path"}),
    )
    try:
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
        subprocess.run(
            ["docker", "rm", "-f", container],
            check=False,
            text=True,
            capture_output=True,
        )


@pytest.mark.system
def test_production_run_store_conforms_against_pinned_seaweedfs(tmp_path) -> None:
    with seaweedfs() as (endpoint, client):
        bucket = f"skywright-{uuid.uuid4().hex}"
        client.create_bucket(Bucket=bucket)
        target = TargetStorage(
            "seaweedfs",
            endpoint,
            bucket,
            "us-east-1",
            "project",
            "run",
        )
        store = RunStoreRecorder(
            target,
            client=client,
            checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
            multipart_threshold=1,
            multipart_part_size=5 * 1024 * 1024,
        )

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
            configuration={},
            dataset=OneItemDataset(),
            metric_contracts=EmptyMetricContracts(),
            skywright_metric_schema="metrics@1",
            recorder=store,
            seed=7,
        )
        reference = result.report.latest_durable_checkpoint
        assert reference is not None, result.report
        assert result.outcome.value == "completed"

        reader = RunStoreReader(
            target,
            client=client,
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
        client.delete_object(Bucket=bucket, Key="conditional")
        assert not any(
            item["Key"] == "conditional"
            for item in client.list_objects_v2(Bucket=bucket).get("Contents", ())
        )
