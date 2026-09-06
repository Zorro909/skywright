# Provider and subprocess fixture documents have runtime-defined shapes.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false

"""Installed-SDK process fixture; its parent is the stopped-writer authority."""

from __future__ import annotations

import json
import os
import sys
import time
from dataclasses import asdict
from pathlib import Path

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from skywright import DatasetBatch, DatasetCursor, MetricCatalog, run_training_process
from skywright.metrics import MetricSchema
from skywright.recovery import PreviousWriterEvidence, RecoveryAdmissionError
from skywright.run_store import (
    CheckpointCodec,
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)


def main():
    settings = json.load(sys.stdin)
    directory = Path(settings["directory"])
    mode = settings["mode"]
    target = TargetStorage(
        "fixture",
        settings["endpoint"],
        settings["bucket"],
        "us-east-1",
        "project",
        settings["run_id"],
    )
    real = boto3.client(
        "s3",
        endpoint_url=settings["endpoint"],
        region_name="us-east-1",
        aws_access_key_id="test-access-key",
        aws_secret_access_key="test-secret-key",
        config=Config(s3={"addressing_style": "path"}),
    )

    def signal_file(name, data):
        (directory / name).write_text(json.dumps(data))

    class Client:
        def __getattr__(self, name):
            operation = getattr(real, name)

            def invoke(**request):
                if mode == "denied" and name == "get_object":
                    raise ClientError(
                        {
                            "Error": {"Code": "AccessDenied"},
                            "ResponseMetadata": {"HTTPStatusCode": 403},
                        },
                        name,
                    )
                if (
                    mode == "report-loss"
                    and name == "put_object"
                    and request["Key"].endswith("/report.json")
                ):
                    raise TimeoutError("fixture report publication failed")
                if mode == "timeout" and name == "get_object":
                    raise TimeoutError("fixture storage timeout")
                if mode == "upload-loss" and name == "upload_part":
                    signal_file(
                        "upload-pending.json", {"upload_id": request["UploadId"]}
                    )
                    while True:
                        time.sleep(0.01)
                return operation(**request)

            return invoke

    class Recorder(RunStoreRecorder):
        def publish_attempt(self, attempt):
            super().publish_attempt(attempt)
            signal_file("attempt.json", asdict(attempt))

    store = Recorder(
        target,
        client=Client(),
        checkpoint_codec=CheckpointCodec(staging_directory=directory),
        multipart_threshold=1 if mode == "upload-loss" else 64 * 1024 * 1024,
        multipart_part_size=5 * 1024 * 1024,
    )

    class Dataset:
        ordering_fingerprint = "ordering"

        def batches(self, cursor):
            yield DatasetBatch(
                (cursor.item_offset,),
                DatasetCursor(
                    cursor.epoch,
                    cursor.item_offset + 1,
                    cursor.epoch_step + 1,
                    self.ordering_fingerprint,
                ),
            )

    class Contracts:
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
        value = 0

        def state_dict(self):
            return {"value": self.value}

        def load_state_dict(self, state):
            self.value = state["value"]

    def proof(attempt):
        path = directory / "stopped-proof.json"
        if not path.exists():
            return None
        # Only the parent supervisor writes this after wait() reaps this exact
        # process. This private fixture file is not a production proof mechanism.
        raw = json.loads(path.read_text())
        if raw["attempt_id"] != attempt.attempt_id:
            return None
        return PreviousWriterEvidence(**raw)

    def train(context):
        state = State()
        context.register_checkpoint_state(
            "other" if mode == "incomplete" else "state", state
        )
        signal_file("registered.json", {"registered": True})
        context.start()
        signal_file(
            "entered.json",
            {
                "step": context.step,
                "value": state.value,
                "cursor": context.dataset_cursor.item_offset,
            },
        )
        if mode == "crash":
            os._exit(137)
        if mode == "hold":
            while not (directory / "advance").exists():
                time.sleep(0.01)
            for _ in range(2):
                state.value += 1
                context.commit_step(
                    next(iter(context.dataset.batches(context.dataset_cursor)))
                )
                deadline = time.monotonic() + 30
                while True:
                    history = store.recovery_history(project_version="project@digest")
                    if (
                        history.checkpoints
                        and history.checkpoints[-1].step == context.step
                    ):
                        break
                    if time.monotonic() >= deadline:
                        raise TimeoutError("checkpoint did not become durable")
                    time.sleep(0.01)
            RunStoreReader(target, client=real).prune_checkpoints(retention=1)
            signal_file("advanced.json", {"step": context.step})
            while True:
                time.sleep(0.01)
        state.value += 1
        context.commit_step(next(iter(context.dataset.batches(context.dataset_cursor))))

    def clone_seed():
        from dataclasses import replace

        return RunStoreReader(
            replace(target, run_id=settings["source_run_id"]), client=real
        ).read_exact(settings["seed_reference"])

    globals()["clone_seed"] = clone_seed
    try:
        result = run_training_process(
            train,
            run_id=target.run_id,
            project_version="project@digest",
            configuration={"checkpoint": {"cadence": 1}},
            dataset=Dataset(),
            metric_contracts=Contracts(),
            skywright_metric_schema="metrics@1",
            recorder=store,
            seed=7,
            source_run_id=settings.get("source_run_id"),
            resume_from="__main__:clone_seed"
            if settings.get("source_run_id")
            else None,
            previous_writer_verifier=proof,
            interruption_requested=lambda: (
                mode in {"interrupted", "upload-loss", "report-loss"}
            ),
        )
    except RecoveryAdmissionError as failure:
        print(
            json.dumps(
                {
                    "code": failure.code,
                    "detail": failure.detail,
                    "outcome": "startup-refused",
                }
            ),
            flush=True,
        )
        return 1
    print(
        json.dumps(
            {
                "attempt_id": result.attempt.attempt_id,
                "outcome": result.outcome.value,
                "cause": result.report.cause.value,
                "step": result.report.latest_durable_step,
                "checkpoint": result.report.latest_durable_checkpoint,
            }
        ),
        flush=True,
    )
    return {"completed": 0, "interrupted": 75, "cancelled": 64, "failed": 1}[
        result.outcome.value
    ]


if __name__ == "__main__":
    raise SystemExit(main())
