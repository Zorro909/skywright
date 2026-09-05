# This subprocess receives typed runtime values serialized by the system fixture.
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
# pyright: reportUnknownMemberType=false, reportUnknownArgumentType=false
# pyright: reportUnknownVariableType=false

"""Recover real MDS training from an S3 checkpoint in a fresh Training Process."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from run_store_training_scenario import EmptyMetricContracts

from skywright import run_training_process
from skywright.dataset import (
    DatasetCacheLimits,
    DatasetDefinition,
    DatasetObject,
    MdsDatasetAccess,
    StorageLocation,
)
from skywright.run_store import (
    CheckpointCodec,
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)

inputs = json.loads(Path(sys.argv[1]).read_text())
value = inputs["definition"]
definition = DatasetDefinition(
    value["definition_id"],
    value["content_fingerprint"],
    value["manifest_identity"],
    tuple(DatasetObject(**entry) for entry in value["objects"]),
)
location = StorageLocation(**inputs["location"])
cache = Path(inputs["cache"])
cache.mkdir()
run_id = inputs["run_id"]
target = TargetStorage(
    "outputs",
    location.endpoint,
    "outputs",
    location.region,
    "project",
    run_id,
    credential_slot="run_store",
)
codec = CheckpointCodec(staging_directory=cache.parent)
recorder = RunStoreRecorder(target, checkpoint_codec=codec)
source_id = inputs.get("source_run_id")
source_target = TargetStorage(
    "outputs",
    location.endpoint,
    "outputs",
    location.region,
    "project",
    source_id or run_id,
    credential_slot="run_store",
)
checkpoint = (
    RunStoreReader(source_target, checkpoint_codec=codec).read_exact(
        inputs["reference"]
    )
    if inputs.get("reference")
    else None
)


class State:
    def __init__(self):
        self.ordinals = []

    def state_dict(self):
        return {"ordinals": self.ordinals}

    def load_state_dict(self, state):
        self.ordinals = list(state["ordinals"])


state = State()
observed = {}
stop = False


def train(context):
    global stop
    context.register_checkpoint_state("state", state)
    context.start()
    observed["initial_cursor"] = [
        context.dataset_cursor.epoch,
        context.dataset_cursor.item_offset,
        context.dataset_cursor.epoch_step,
    ]
    observed["initial_step"] = context.step
    while context.dataset_cursor.epoch < inputs.get("epochs", 3):
        iterator = iter(context.dataset.batches(context.dataset_cursor))
        try:
            for batch in iterator:
                partitions = batch.partition_items(inputs.get("accelerators", 1))
                # One owner reassembles completed single-node partitions in rank order.
                for partition in partitions:
                    for item in partition:
                        assert item.payload["number"] == item.ordinal
                        state.ordinals.append(item.ordinal)
                if inputs.get("fail_before_step") == context.step + 1:
                    # This fetched batch and all work since the checkpoint must replay.
                    raise RuntimeError("interruption before Step commit")
                if inputs.get("stop_after_step") == context.step + 1:
                    stop = True
                context.commit_step(batch)
        finally:
            iterator.close()


with MdsDatasetAccess(
    definition,
    location,
    cache_directory=cache,
    limits=DatasetCacheLimits(**inputs["limits"]),
    seed=19,
    batch_size=inputs["batch_size"],
    loader_workers=inputs.get("workers", 0),
) as dataset:
    result = run_training_process(
        train,
        run_id=run_id,
        project_version="project@digest",
        configuration={"reproducibility": {"seed": 19}, "checkpoint": {"cadence": 1}},
        dataset=dataset,
        metric_contracts=EmptyMetricContracts(),
        skywright_metric_schema="metrics@1",
        recorder=recorder,
        seed=19,
        resume_from=checkpoint,
        source_run_id=source_id,
        ordering_reset=inputs.get("ordering_reset", False),
        interruption_requested=lambda: stop,
    )
observed.update(
    outcome=result.outcome.value,
    report_cause=result.report.cause.value,
    reference=result.report.latest_durable_checkpoint,
    step=result.report.last_committed_step,
    ordinals=state.ordinals,
)
print(json.dumps(observed))
