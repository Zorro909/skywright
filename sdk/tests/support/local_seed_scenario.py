"""Real S3 checkpoint production and owned-copy recovery for backend acceptance tests."""

# pyright: reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false, reportUnknownVariableType=false
import json
import sys
from uuid import uuid4

import boto3
from botocore.config import Config

from skywright import CheckpointSnapshot, DatasetCursor, ExecutionAttemptRecord
from skywright.recovery import PreviousWriterEvidence
from skywright.run_store import RunStoreReader, RunStoreRecorder, TargetStorage

settings = json.load(sys.stdin)
target = TargetStorage(
    storage_id="local-seed-test",
    endpoint_url=settings["endpoint"],
    bucket=settings["bucket"],
    region="us-east-1",
    training_project_id=settings["project"],
    run_id=settings["run"],
)
client = boto3.client(
    "s3",
    endpoint_url=settings["endpoint"],
    region_name="us-east-1",
    aws_access_key_id="test-key",
    aws_secret_access_key="test-secret",
    config=Config(s3={"addressing_style": "path"}),
)
version = settings["version"]
if settings["mode"] == "publish":
    recorder = RunStoreRecorder(target, client=client)
    recorder.publish_attempt(
        ExecutionAttemptRecord(str(uuid4()), target.run_id, version, None)
    )
    reference = recorder.publish_checkpoint(
        CheckpointSnapshot(
            4,
            {"value": 4},
            run_id=target.run_id,
            project_version=version,
            dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
        )
    )
    print(json.dumps({"reference": reference}))
else:

    def stopped(attempt: ExecutionAttemptRecord) -> PreviousWriterEvidence:
        return PreviousWriterEvidence(
            attempt.run_id,
            attempt.attempt_id,
            "stopped",
            "fixture:previous-process-returned",
        )

    for debt in (0, 1):
        reader = RunStoreReader(target, client=client)
        snapshot = reader.read_owned_seed(
            settings["predecessor"], settings["reference"], project_version=version
        )
        assert snapshot.state["value"] == 4
        recorder = RunStoreRecorder(target, client=client)
        resolution = recorder.prepare_recovery(
            project_version=version,
            ordering_fingerprint="ordering",
            source_run_id=settings["predecessor"],
            seed_checkpoint=snapshot,
            previous_writer_verifier=stopped,
        )
        assert (
            resolution is not None
            and resolution.checkpoint.reference == settings["reference"]
        )
        recorder.publish_attempt(
            ExecutionAttemptRecord(
                str(uuid4()), target.run_id, version, 4, settings["reference"]
            )
        )
        assert recorder.recovery_history(project_version=version).debt == debt
    print(json.dumps({"recovered": True, "debt": 1, "source": snapshot.run_id}))
