# This subprocess boundary consumes runtime-shaped integration values.
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
# pyright: reportUnknownMemberType=false, reportUnknownArgumentType=false
# pyright: reportUnknownVariableType=false

"""Direct execution through the real Dataset adapter and durable S3 Run Store."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from run_store_training_scenario import EmptyMetricContracts, State

from skywright import run_training_process
from skywright.dataset import (
    DatasetCacheLimits,
    DatasetDefinition,
    DatasetLocation,
    DatasetObject,
    MdsDatasetAccess,
)
from skywright.run_store import CheckpointCodec, RunStoreRecorder, TargetStorage

inputs = json.loads(Path(sys.argv[1]).read_text())
value = inputs["definition"]
definition = DatasetDefinition(
    value["definition_id"],
    value["content_fingerprint"],
    value["manifest_identity"],
    tuple(DatasetObject(**entry) for entry in value["objects"]),
)
location = DatasetLocation(**inputs["location"])
cache = Path(inputs["cache"])
cache.mkdir()

# The fixture grants a separate Run Store credential slot and uses a distinct bucket.
target = TargetStorage(
    "outputs",
    location.endpoint,
    "outputs",
    location.region,
    "project",
    "direct",
    credential_slot="run_store",
)
recorder = RunStoreRecorder(
    target, checkpoint_codec=CheckpointCodec(staging_directory=cache.parent)
)
seen: list[int] = []


def train(context):
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    for batch in context.dataset.batches(context.dataset_cursor):
        for item in batch.items:
            assert item.payload["number"] == item.ordinal
            seen.append(item.ordinal)
        state.value += len(batch.items)
        context.commit_step(batch)


with MdsDatasetAccess(
    definition,
    location,
    cache_directory=cache,
    limits=DatasetCacheLimits(**inputs["limits"]),
    batch_size=5,
) as dataset:
    result = run_training_process(
        train,
        run_id="direct",
        project_version="project@digest",
        configuration={},
        dataset=dataset,
        metric_contracts=EmptyMetricContracts(),
        skywright_metric_schema="metrics@1",
        recorder=recorder,
        seed=0,
    )
assert result.outcome.value == "completed", result.report
assert sorted(seen) == list(range(24))
print(f"completed:{len(seen)}")
