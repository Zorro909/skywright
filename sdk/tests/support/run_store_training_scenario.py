# boto3 and this subprocess boundary expose runtime-shaped integration values.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false

"""Run one Training Process lifecycle scenario against a real S3-compatible service."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import boto3
from botocore.config import Config

from skywright import (
    ArtifactRecord,
    DatasetBatch,
    DatasetCursor,
    MetricCatalog,
    TrainingContractViolation,
    run_training_process,
)
from skywright.run_store import (
    CheckpointCodec,
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)


class TwoItemDataset:
    ordering_fingerprint = "sha256:ordering"

    def batches(self, cursor):
        if cursor.item_offset < 2:
            yield DatasetBatch(
                (f"item-{cursor.item_offset}",),
                DatasetCursor(
                    cursor.epoch,
                    cursor.item_offset + 1,
                    cursor.epoch_step + 1,
                    self.ordering_fingerprint,
                ),
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "scenario", choices=("interrupted", "resume", "cancelled", "failed")
    )
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--staging-directory", type=Path, required=True)
    arguments = parser.parse_args()

    client = boto3.client(
        "s3",
        endpoint_url=arguments.endpoint,
        region_name="us-east-1",
        aws_access_key_id="test-access-key",
        aws_secret_access_key="test-secret-key",
        config=Config(s3={"addressing_style": "path"}),
    )
    target = TargetStorage(
        "seaweedfs",
        arguments.endpoint,
        arguments.bucket,
        "us-east-1",
        "project",
        arguments.run_id,
    )
    arguments.staging_directory.mkdir(parents=True, exist_ok=True)
    codec = CheckpointCodec(staging_directory=arguments.staging_directory)
    recorder = RunStoreRecorder(target, client=client, checkpoint_codec=codec)
    reader = RunStoreReader(target, client=client, checkpoint_codec=codec)
    resume_from = None
    if arguments.scenario == "resume":
        resume_from = reader.resolve_latest_valid(
            project_version="project@digest",
            ordering_fingerprint=TwoItemDataset.ordering_fingerprint,
        ).checkpoint

    observed: dict[str, object] = {}

    def train(context):
        state = State()
        context.register_checkpoint_state("state", state)
        resume = context.start()
        observed["resumed"] = resume.resumed
        observed["initial_state"] = state.value
        batch = next(iter(context.dataset.batches(context.dataset_cursor)))
        state.value += 1
        context.commit_step(batch)
        if arguments.scenario == "failed":
            raise ValueError("qualification failure")

    result = run_training_process(
        train,
        run_id=arguments.run_id,
        project_version="project@digest",
        configuration={},
        dataset=TwoItemDataset(),
        metric_contracts=EmptyMetricContracts(),
        skywright_metric_schema="metrics@1",
        recorder=recorder,
        seed=7,
        resume_from=resume_from,
        cancellation_requested=lambda: arguments.scenario == "cancelled",
        interruption_requested=lambda: arguments.scenario == "interrupted",
    )

    terminal_report_closed_writer = False
    try:
        recorder.publish_artifact(ArtifactRecord("late", b"late", 99))
    except TrainingContractViolation as violation:
        terminal_report_closed_writer = "execution-attempt/closed" in str(violation)

    print(
        json.dumps(
            {
                "outcome": result.outcome.value,
                "cause": result.report.cause.value,
                "last_step": result.report.last_committed_step,
                "durable_step": result.report.latest_durable_step,
                "checkpoint": result.report.latest_durable_checkpoint,
                "terminal_report_closed_writer": terminal_report_closed_writer,
                **observed,
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
