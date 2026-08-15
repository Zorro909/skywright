from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

SDK_ROOT = Path(__file__).parents[1]


def process_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(SDK_ROOT / "src")
    return environment


def run_project(source: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-c", source],
        check=False,
        cwd=SDK_ROOT,
        env=process_environment(),
        text=True,
        capture_output=True,
    )


def test_training_project_completes_through_the_public_run_context() -> None:
    completed = run_project(
        """
import json

from skywright import MetricDefinition, run_training_process


class Counter:
    def __init__(self):
        self.value = 0

    def state_dict(self):
        return {"value": self.value}

    def load_state_dict(self, state):
        self.value = state["value"]


def train(context):
    counter = Counter()
    context.register_checkpoint_state("counter", counter)
    context.start()
    counter.value = 1
    context.observe("train/loss", 1.5)
    context.commit_step()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"learning_rate": 0.01},
    dataset=("item-0",),
    metric_definitions=(
        MetricDefinition(
            name="train/loss",
            numeric_kind="real",
            unit="dimensionless",
            comparison="minimize",
            step_reduction="mean",
        ),
    ),
    seed=17,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "durable_step": result.report.latest_durable_step,
    "checkpoint_state": result.final_checkpoint.state["counter"],
    "metrics": [
        [observation.name, observation.step, observation.value]
        for observation in result.metric_observations
    ],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "cause": "completed",
        "last_step": 1,
        "durable_step": 1,
        "checkpoint_state": {"value": 1},
        "metrics": [["train/loss", 1, 1.5]],
    }


def test_caught_project_misuse_still_terminates_as_a_contract_violation() -> None:
    completed = run_project(
        """
import json

from skywright import TrainingContractViolation, run_training_process


def train(context):
    try:
        context.start()
    except TrainingContractViolation:
        pass


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=1,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
    "last_step": result.report.last_committed_step,
    "checkpoint": result.final_checkpoint,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "failed",
        "cause": "contract_violation",
        "rule": "checkpoint-state/empty",
        "last_step": 0,
        "checkpoint": None,
    }


def test_runtime_establishes_determinism_before_project_setup() -> None:
    source = """
import json
import random

import numpy
import torch

from skywright import Accelerator, run_training_process


class State:
    def state_dict(self):
        return {}

    def load_state_dict(self, state):
        pass


values = {}


def train(context):
    values.update({
        "python": random.random(),
        "numpy": numpy.random.random(),
        "torch": torch.rand(1).item(),
        "deterministic": torch.are_deterministic_algorithms_enabled(),
        "accelerator": context.accelerator.device,
    })
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=1234,
    accelerator=Accelerator("cpu"),
)
print(json.dumps({"values": values, "outcome": result.outcome.value}))
"""

    first = run_project(source)
    second = run_project(source)

    assert first.returncode == 0, first.stderr
    assert second.returncode == 0, second.stderr
    assert json.loads(first.stdout) == json.loads(second.stdout)
    assert json.loads(first.stdout)["values"]["deterministic"] is True
    assert json.loads(first.stdout)["values"]["accelerator"] == "cpu"


def test_resume_restores_project_and_library_owned_rng_state() -> None:
    completed = run_project(
        """
import random

import numpy
import torch

from skywright import CheckpointSnapshot, run_training_process


random.seed(55)
numpy.random.seed(55)
torch.manual_seed(55)
random.random()
numpy.random.random()
torch.rand(1)
runtime_state = {
    "python_random": random.getstate(),
    "numpy_random": numpy.random.get_state(),
    "torch_cpu_random": torch.get_rng_state(),
}
expected = (random.random(), numpy.random.random(), torch.rand(1).item())


class Counter:
    def __init__(self):
        self.value = -1

    def state_dict(self):
        return {"value": self.value}

    def load_state_dict(self, state):
        self.value = state["value"]


observed = {}


def train(context):
    random.random()
    numpy.random.random()
    torch.rand(1)
    counter = Counter()
    context.register_checkpoint_state("counter", counter)
    resume = context.start()
    observed["resumed"] = resume.resumed
    observed["counter"] = counter.value
    observed["rng"] = (random.random(), numpy.random.random(), torch.rand(1).item())
    context.commit_step()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=999,
    resume_from=CheckpointSnapshot(
        step=4,
        state={"counter": {"value": 23}},
        runtime_state=runtime_state,
    ),
)
assert result.report.last_committed_step == 5
assert observed == {"resumed": True, "counter": 23, "rng": expected}
"""
    )

    assert completed.returncode == 0, completed.stderr


def test_project_accesses_resolved_inputs_and_persists_distinct_output_kinds() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self):
        return {}

    def load_state_dict(self, state):
        pass


observed = {}


def train(context):
    observed["batch_size"] = context.configuration["training"]["batch_size"]
    observed["dataset"] = list(context.dataset)
    try:
        context.configuration["training"]["batch_size"] = 99
    except TypeError:
        observed["immutable"] = True
    context.register_checkpoint_state("state", State())
    context.start()
    context.persist_artifact("model.txt", b"model summary")
    context.persist_sample("preview", b"png bytes", media_type="image/png")
    context.commit_step()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"training": {"batch_size": 8}},
    dataset=("item-2", "item-1"),
    metric_definitions=(),
    seed=5,
)
print(json.dumps({
    "observed": observed,
    "artifacts": [
        [artifact.name, artifact.data.decode(), artifact.step]
        for artifact in result.artifacts
    ],
    "samples": [
        [sample.name, sample.media_type, sample.data.decode(), sample.step]
        for sample in result.samples
    ],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "observed": {
            "batch_size": 8,
            "dataset": ["item-2", "item-1"],
            "immutable": True,
        },
        "artifacts": [["model.txt", "model summary", 0]],
        "samples": [["preview", "image/png", "png bytes", 0]],
    }


def test_cancellation_stops_at_the_next_step_without_a_checkpoint() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def __init__(self):
        self.value = 0

    def state_dict(self):
        return {"value": self.value}

    def load_state_dict(self, state):
        self.value = state["value"]


continued = False


def train(context):
    global continued
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    state.value = 1
    context.commit_step()
    continued = True


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=1,
    cancellation_requested=lambda: True,
    interruption_requested=lambda: True,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "durable_step": result.report.latest_durable_step,
    "checkpoint": result.final_checkpoint,
    "continued": continued,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "cancelled",
        "cause": "cancelled",
        "last_step": 1,
        "durable_step": None,
        "checkpoint": None,
        "continued": False,
    }


def test_interruption_stops_at_the_next_step_with_a_recoverable_checkpoint() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def __init__(self):
        self.value = 0

    def state_dict(self):
        return {"value": self.value}

    def load_state_dict(self, state):
        self.value = state["value"]


def train(context):
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    state.value = 7
    context.commit_step()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=1,
    interruption_requested=lambda: True,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "durable_step": result.report.latest_durable_step,
    "checkpoint_step": result.final_checkpoint.step,
    "checkpoint_state": result.final_checkpoint.state["state"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "interrupted",
        "cause": "interrupted",
        "last_step": 1,
        "durable_step": 1,
        "checkpoint_step": 1,
        "checkpoint_state": {"value": 7},
    }


def test_first_process_signal_requests_cooperative_interruption() -> None:
    completed = run_project(
        """
import json
import os
import signal

from skywright import run_training_process


class State:
    def state_dict(self):
        return {}

    def load_state_dict(self, state):
        pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    os.kill(os.getpid(), signal.SIGTERM)
    assert context.interruption_requested
    context.commit_step()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=1,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "interrupted",
        "cause": "interrupted",
    }


def test_invalid_metric_catalog_fails_before_project_code_and_consumes_process() -> (
    None
):
    completed = run_project(
        """
import json

from skywright import MetricDefinition, run_training_process


called = False


def train(context):
    global called
    called = True


invalid = MetricDefinition(
    name="skywright/system/fake",
    numeric_kind="integer",
    unit="dimensionless",
    comparison="none",
    step_reduction="mean",
)
first = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(invalid,),
    seed=1,
)
second = run_training_process(
    train,
    run_id="test-run-2",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=1,
)
print(json.dumps({
    "called": called,
    "first_cause": first.report.cause.value,
    "first_rule": first.report.diagnostics["rule"],
    "second_rule": second.report.diagnostics["rule"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "called": False,
        "first_cause": "contract_violation",
        "first_rule": "metric-definition/reserved-name",
        "second_rule": "run-context/one-per-process",
    }


def test_runtime_command_executes_a_training_project_entry_point(
    tmp_path: Path,
) -> None:
    project = tmp_path / "example_project.py"
    project.write_text(
        """
class State:
    def state_dict(self):
        return {"ready": True}

    def load_state_dict(self, state):
        pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.observe("train/loss", 2.0)
    context.commit_step()
""",
        encoding="utf-8",
    )
    definition = tmp_path / "run.json"
    definition.write_text(
        json.dumps(
            {
                "run_id": "run-123",
                "project_version": "example@abc123",
                "configuration": {"epochs": 1},
                "dataset": ["item-0"],
                "metric_definitions": [
                    {
                        "name": "train/loss",
                        "numeric_kind": "real",
                        "unit": "dimensionless",
                        "comparison": "minimize",
                        "step_reduction": "mean",
                    }
                ],
                "seed": 12,
                "accelerator": {"kind": "cpu"},
            }
        ),
        encoding="utf-8",
    )
    environment = process_environment()
    environment["PYTHONPATH"] = os.pathsep.join((str(SDK_ROOT / "src"), str(tmp_path)))

    completed = subprocess.run(
        [
            sys.executable,
            "-m",
            "skywright._runtime",
            "example_project:train",
            "--definition",
            str(definition),
        ],
        check=False,
        cwd=tmp_path,
        env=environment,
        text=True,
        capture_output=True,
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "attempt_id": json.loads(completed.stdout)["attempt_id"],
        "cause": "completed",
        "diagnostics": {},
        "last_committed_step": 1,
        "latest_durable_step": 1,
        "outcome": "completed",
        "project_version": "example@abc123",
        "run_id": "run-123",
        "schema_version": 1,
    }


def test_metric_observations_reduce_at_the_committed_step() -> None:
    completed = run_project(
        """
import json

import torch

from skywright import MetricDefinition, run_training_process


class State:
    def state_dict(self):
        return {}

    def load_state_dict(self, state):
        pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.observe("train/loss", torch.tensor(1.0))
    context.observe("train/loss", 3.0)
    context.commit_step()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(MetricDefinition(
        name="train/loss",
        numeric_kind="real",
        unit="dimensionless",
        comparison="minimize",
        step_reduction="mean",
    ),),
    seed=2,
)
print(json.dumps([
    [observation.name, observation.step, observation.value]
    for observation in result.metric_observations
]))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == [["train/loss", 1, 2.0]]


def test_project_failure_preserves_cause_without_creating_a_checkpoint() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self):
        return {}

    def load_state_dict(self, state):
        pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step()
    raise ValueError("bad project arithmetic")


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=(),
    metric_definitions=(),
    seed=3,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "exception_type": result.report.diagnostics["exception_type"],
    "message": result.report.diagnostics["message"],
    "last_step": result.report.last_committed_step,
    "checkpoint": result.final_checkpoint,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "failed",
        "cause": "training_project_failure",
        "exception_type": "ValueError",
        "message": "bad project arithmetic",
        "last_step": 1,
        "checkpoint": None,
    }
