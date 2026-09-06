# S3-compatible fixture responses have provider-defined runtime shapes.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false

from __future__ import annotations

from typing import Any

import pytest
from botocore.exceptions import ClientError
from integration.test_run_store_system import seaweedfs

from skywright import CheckpointSnapshot, DatasetCursor, ExecutionAttemptRecord
from skywright.run_store import (
    CheckpointCodec,
    CheckpointReference,
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)


@pytest.mark.parametrize("fault", ["removed", "corrupt"])
def test_real_s3_latest_checkpoint_fault_falls_back_with_verified_evidence(
    tmp_path, fault
):
    with seaweedfs() as (endpoint, client):
        target = TargetStorage(
            "fixture", endpoint, "fallback", "us-east-1", "project", "run"
        )
        client.create_bucket(Bucket=target.bucket)
        writer = RunStoreRecorder(
            target,
            client=client,
            checkpoint_codec=CheckpointCodec(staging_directory=tmp_path),
        )
        writer.publish_attempt(
            ExecutionAttemptRecord(
                "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
            )
        )
        references = [
            writer.publish_checkpoint(
                CheckpointSnapshot(
                    step,
                    {"value": step},
                    run_id="run",
                    project_version="project@digest",
                    dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
                )
            )
            for step in (1, 2)
        ]
        newest = CheckpointReference.parse(references[1])
        key = writer.protocol.checkpoint_key(newest.step, newest.digest)
        if fault == "corrupt":
            response = client.get_object(Bucket=target.bucket, Key=key)
            with response["Body"] as content:
                body = content.read()
            client.put_object(
                Bucket=target.bucket,
                Key=key,
                Body=body[:-1] + bytes([body[-1] ^ 1]),
                Metadata=response["Metadata"],
                ContentType=response["ContentType"],
            )

        class ObservedClient:
            def __init__(self):
                self.deleted = False
                self.gets: list[str] = []
                self.missing_codes: list[str] = []

            def list_objects_v2(self, **request: Any):
                response = client.list_objects_v2(**request)
                if fault == "removed" and not self.deleted:
                    assert any(item["Key"] == key for item in response["Contents"])
                    client.delete_object(Bucket=target.bucket, Key=key)
                    self.deleted = True
                return response

            def get_object(self, **request: Any):
                self.gets.append(request["Key"])
                try:
                    return client.get_object(**request)
                except ClientError as failure:
                    self.missing_codes.append(failure.response["Error"]["Code"])
                    raise

        observed = ObservedClient()
        reader = RunStoreReader(target, client=observed, staging_directory=tmp_path)
        resolution = reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
        assert resolution.checkpoint.reference == references[0]
        assert resolution.checkpoint.state == {"value": 1}
        assert observed.gets[0] == key and len(observed.gets) == 2
        assert [
            (rejection.step, rejection.code) for rejection in resolution.rejected
        ] == [
            (
                2,
                "RUN_STORE_MISSING_OBJECT"
                if fault == "removed"
                else "RUN_STORE_DIGEST_MISMATCH",
            )
        ]
        assert observed.missing_codes == (["NoSuchKey"] if fault == "removed" else [])
        assert not list(tmp_path.glob("skywright-read-*"))
