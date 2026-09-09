"""Exercise the published example through actual Run Context commits on CPU."""

import subprocess
import sys
from pathlib import Path

from unit.test_training_process import PROCESS_SUPPORT, process_environment

EXAMPLE = Path(__file__).parents[3] / "examples/cifar10"


def test_image_training_commits_full_and_partial_epoch_batches():
    source = (
        PROCESS_SUPPORT
        + "\nimport sys\nsys.path.insert(0, "
        + repr(str(EXAMPLE))
        + ")\n"
        + r"""
import json
import math
import torch
from skywright import Accelerator, run_training_process
from skywright.dataset import DatasetItem
from skywright_project import train

torch.set_num_threads(1)

class Images:
    ordering_fingerprint = "sha256:test-ordering"

    def batches(self, cursor):
        for index in range(cursor.item_offset, 10):
            finished = index == 9
            yield DatasetBatch(
                (DatasetItem("images", index, {
                    "image": bytes([index * 20]) * 3072,
                    "label": index,
                }),),
                DatasetCursor(
                    epoch=cursor.epoch + int(finished),
                    item_offset=0 if finished else index + 1,
                    epoch_step=0 if finished else cursor.epoch_step + index - cursor.item_offset + 1,
                    ordering_fingerprint=self.ordering_fingerprint,
                ),
                epoch=cursor.epoch,
            )

recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="cifar-example",
    project_version="cifar-example@fixture",
    configuration={"project": {
        "steps": 4, "batchSize": 4, "learningRate": 0.02, "outputEvery": 1,
    }},
    dataset=Images(),
    metric_contracts=TestMetricContracts(
        MetricDefinition("train/loss", "real", "dimensionless", comparison="minimize"),
        MetricDefinition("train/accuracy", "real", "dimensionless", comparison="maximize"),
    ),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=17,
    accelerator=Accelerator("cpu"),
)
assert result.outcome.value == "completed", result.report
assert result.report.last_committed_step == 4
artifacts = [json.loads(item.data) for item in recorder.artifacts]
assert [item["ordinals"] for item in artifacts] == [list(range(4)), list(range(4,8)), [8,9], list(range(4))]
assert [item["cursor"]["epoch"] for item in artifacts] == [0, 0, 1, 1]
assert all(math.isfinite(item["loss"]) and 0 <= item["accuracy"] <= 1 for item in artifacts)
assert all(len(item["predictions"]) == len(item["labels"]) == len(item["ordinals"]) for item in artifacts)
assert len(recorder.samples) == 4
assert all(item.data.startswith(b"\x89PNG\r\n\x1a\n") for item in recorder.samples)
steps = [event for event in recorder.events if event[0] == "step"]
for event in steps:
    names = {observation.name for observation in event[3]}
    assert {"train/loss", "train/accuracy"} <= names
print("example-context-passed")
"""
    )
    completed = subprocess.run(
        [sys.executable, "-c", source],
        env=process_environment(),
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert completed.stdout.strip().endswith("example-context-passed")


def test_preparation_rejects_wrong_archive_before_creating_corpus(tmp_path: Path):
    archive = tmp_path / "wrong.tar.gz"
    archive.write_bytes(b"not the immutable source")
    destination = tmp_path / "mds"
    completed = subprocess.run(
        [
            sys.executable,
            str(EXAMPLE / "prepare_dataset.py"),
            str(archive),
            str(destination),
        ],
        capture_output=True,
        text=True,
        timeout=15,
    )
    assert completed.returncode != 0
    assert "download checksum" in completed.stderr
    assert not destination.exists()
