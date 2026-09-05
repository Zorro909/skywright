from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

SDK_ROOT = Path(__file__).parents[1]

PROCESS_SUPPORT = """
from skywright import DatasetBatch, DatasetCursor, MetricCatalog, MetricDefinition
from skywright.metrics import MetricSchema


class TestDataset:
    def __init__(self, items=("item",) * 20):
        self.items = tuple(items)

    @property
    def ordering_fingerprint(self):
        return "sha256:test-ordering"

    def batches(self, cursor):
        remaining = self.items[cursor.item_offset:]
        for item_offset, item in enumerate(remaining, start=cursor.item_offset):
            yield DatasetBatch(
                items=(item,),
                next_cursor=DatasetCursor(
                    epoch=cursor.epoch,
                    item_offset=item_offset + 1,
                    epoch_step=(
                        cursor.epoch_step + item_offset - cursor.item_offset + 1
                    ),
                    ordering_fingerprint=self.ordering_fingerprint,
                ),
                epoch=cursor.epoch,
            )


class TestRecorder:
    def __init__(self):
        self.events = []
        self.configuration = None

    def configure_metrics(self, configuration, *, shutdown_grace_seconds):
        self.configuration = configuration

    def publish_attempt(self, attempt):
        self.events.append(("attempt", attempt))

    def publish_checkpoint(self, checkpoint):
        self.events.append(("checkpoint", checkpoint))
        return f"checkpoint:{checkpoint.step}"

    def publish_step(
        self,
        step,
        dataset_cursor,
        observations,
        latest_durable_step,
        latest_durable_checkpoint,
    ):
        self.events.append((
            "step",
            step,
            dataset_cursor,
            observations,
            latest_durable_step,
            latest_durable_checkpoint,
        ))

    def publish_wall_time(self, observation):
        self.events.append(("wall_time", observation))

    def confirm_checkpoint(self, step, reference):
        self.events.append(("confirmation", step, reference))

    def publish_artifact(self, artifact):
        self.events.append(("artifact", artifact))

    def publish_sample(self, sample):
        self.events.append(("sample", sample))

    def publish_report(self, report):
        self.events.append(("report", report))


def test_catalog(*definitions):
    return MetricCatalog(
        project_identity="test-project@abc123",
        project_contract_digest="sha256:project-contract",
        skywright_schema_identity="test-schema@1",
        skywright_schema_digest="sha256:skywright-schema",
        units=frozenset((
            "bytes", "count", "dimensionless", "items_per_second", "seconds",
        )),
        project_definitions=tuple(definitions),
    )


class TestMetricContracts:
    def __init__(self, *definitions, system_definitions=None):
        self.definitions = definitions
        self.system_definitions = (
            MetricSchema.definitions()
            if system_definitions is None
            else system_definitions
        )

    def compose(self, project_version, skywright_schema_identity):
        catalog = test_catalog(*self.definitions)
        return MetricCatalog(
            project_identity=project_version,
            project_contract_digest=catalog.project_contract_digest,
            skywright_schema_identity=skywright_schema_identity,
            skywright_schema_digest=catalog.skywright_schema_digest,
            units=catalog.units,
            project_definitions=catalog.project_definitions,
            system_definitions=tuple(self.system_definitions),
        )


def next_batch(context):
    return next(iter(context.dataset.batches(context.dataset_cursor)))
"""


def process_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(SDK_ROOT / "src")
    return environment


def run_project(source: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-c", PROCESS_SUPPORT + source],
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
    context.commit_step(next_batch(context))


recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"learning_rate": 0.01},
    dataset=TestDataset(("item-0",)),
    metric_contracts=TestMetricContracts(
        MetricDefinition(
            name="train/loss",
            numeric_kind="real",
            unit="dimensionless",
            comparison="minimize",
            step_reduction="mean",
        ),
    ),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=17,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "durable_step": result.report.latest_durable_step,
    "checkpoint_state": result.final_checkpoint.state["counter"],
    "checkpoint_reference": result.report.latest_durable_checkpoint,
    "events": [event[0] for event in recorder.events],
    "recorder_configuration": recorder.configuration,
    "metrics": [
        [observation.name, observation.step, observation.value]
        for observation in result.metric_observations
        if not observation.name.startswith("skywright/")
    ],
}))
"""
    )

    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "cause": "completed",
        "last_step": 1,
        "durable_step": 1,
        "checkpoint_state": {"value": 1},
        "checkpoint_reference": "checkpoint:1",
        "events": ["attempt", "step", "checkpoint", "confirmation", "report"],
        "recorder_configuration": {"learning_rate": 0.01},
        "metrics": [["train/loss", 1, 1.5]],
    }


def test_committed_step_publishes_throughput_and_data_loading_wait_atomically() -> None:
    completed = run_project(
        """
import json

from skywright import MetricDefinition, run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


moments = iter((10.0, 11.0, 11.5, 12.5))


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    batch = next(iter(context.dataset.batches(context.dataset_cursor)))
    context.observe("train/loss", 2.5)
    context.commit_step(batch)


recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("item-0", "item-1")),
    metric_contracts=TestMetricContracts(
        MetricDefinition(
            name="train/loss",
            numeric_kind="real",
            unit="dimensionless",
            comparison="minimize",
            step_reduction="mean",
        ),
        system_definitions=MetricSchema.definitions(),
    ),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=17,
    monotonic_clock=lambda: next(moments),
)
step_event = next(event for event in recorder.events if event[0] == "step")
print(json.dumps({
    "cause": result.report.cause.value,
    "observations": [
        [observation.name, observation.step, observation.value]
        for observation in step_event[3]
    ],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "completed",
        "observations": [
            ["train/loss", 1, 2.5],
            ["skywright/system/throughput", 1, 0.4],
            ["skywright/system/data_loading_wait", 1, 0.5],
        ],
    }


def test_later_step_uses_its_own_interval_and_all_consumed_batches() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


moments = iter((0.0, 1.0, 2.0, 3.0, 5.0, 10.0, 11.0, 12.0, 14.0))


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    batches = iter(context.dataset.batches(context.dataset_cursor))
    next(batches)
    final_batch = next(batches)
    batches.close()
    context.commit_step(final_batch)
    context.commit_step(next_batch(context))


recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("one", "two", "three")),
    metric_contracts=TestMetricContracts(
        system_definitions=MetricSchema.definitions()
    ),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=4,
    monotonic_clock=lambda: next(moments),
)
print(json.dumps({
    "cause": result.report.cause.value,
    "metrics": [
        [observation.name, observation.step, observation.value]
        for observation in result.metric_observations
    ],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "completed",
        "metrics": [
            ["skywright/system/throughput", 1, 0.2],
            ["skywright/system/data_loading_wait", 1, 3.0],
            ["skywright/system/throughput", 2, 0.25],
            ["skywright/system/data_loading_wait", 2, 1.0],
        ],
    }


def test_memory_sampling_omits_unavailable_values_and_recovers_at_the_cadence() -> None:
    completed = run_project(
        """
import json
import threading
import time

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


samples_finished = threading.Event()
waited_intervals = []
memory_values = iter((None, 123456789))


def wait_for_sample(stop, interval):
    waited_intervals.append(interval)
    if len(waited_intervals) <= 2:
        return False
    return stop.wait(1.0)


def read_memory():
    value = next(memory_values)
    if value is not None:
        samples_finished.set()
    return value


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    if not samples_finished.wait(1.0):
        raise RuntimeError("sampler did not run")
    context.commit_step(next_batch(context))


recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"metrics": {"systemSamplingInterval": 2.5}},
    dataset=TestDataset(("item",)),
    metric_contracts=TestMetricContracts(
        system_definitions=MetricSchema.definitions()
    ),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=9,
    cgroup_memory_reader=read_memory,
    system_sampler_wait=wait_for_sample,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "intervals": waited_intervals,
    "wall_time": [
        [event[1].name, event[1].step, event[1].value]
        for event in recorder.events if event[0] == "wall_time"
    ],
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    payload = json.loads(completed.stdout)
    assert payload["cause"] == "completed"
    assert payload["intervals"] == [2.5, 2.5, 2.5]
    assert payload["wall_time"] == [["skywright/system/memory_used", 0, 123456789]]
    assert payload["events"][-1] == "report"


def test_resumed_attempt_starts_a_fresh_throughput_interval() -> None:
    completed = run_project(
        """
import json

from skywright import CheckpointSnapshot, DatasetCursor, run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


moments = iter((100.0, 101.0, 102.0, 105.0))


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("zero", "one", "two", "three", "four", "five")),
    metric_contracts=TestMetricContracts(
        system_definitions=MetricSchema.definitions()
    ),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=5,
    resume_from=CheckpointSnapshot(
        step=4,
        state={"state": {}},
        dataset_cursor=DatasetCursor(
            item_offset=4,
            epoch_step=4,
            ordering_fingerprint="sha256:test-ordering",
        ),
        reference="checkpoint:4",
    ),
    monotonic_clock=lambda: next(moments),
)
print(json.dumps([
    [observation.name, observation.step, observation.value]
    for observation in result.metric_observations
]))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == [
        ["skywright/system/throughput", 5, 0.2],
        ["skywright/system/data_loading_wait", 5, 1.0],
    ]


def test_project_cannot_write_a_declared_system_metric() -> None:
    completed = run_project(
        """
import json

from skywright import TrainingContractViolation, run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    try:
        context.observe("skywright/system/throughput", 10.0)
    except TrainingContractViolation:
        pass


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(
        system_definitions=MetricSchema.definitions()
    ),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=6,
    monotonic_clock=lambda: 1.0,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "metric/undeclared",
    }


@pytest.mark.parametrize(
    ("mode", "expected_outcome", "expected_cause"),
    (
        ("completed", "completed", "completed"),
        ("interrupted", "interrupted", "interrupted"),
        ("cancelled", "cancelled", "cancelled"),
        ("failed", "failed", "training_project_failure"),
    ),
)
def test_memory_sampler_stops_before_the_report_on_every_process_outcome(
    mode: str, expected_outcome: str, expected_cause: str
) -> None:
    completed = run_project(
        f"""
import json
import threading

from skywright import run_training_process


class State:
    def state_dict(self): return {{}}
    def load_state_dict(self, state): pass


sampler_waiting = threading.Event()
sampler_stopped = threading.Event()
wait_calls = 0


def wait_for_sample(stop, interval):
    global wait_calls
    wait_calls += 1
    if wait_calls == 1:
        return False
    sampler_waiting.set()
    stopped = stop.wait(1.0)
    if stopped:
        sampler_stopped.set()
    return stopped


class ClosingRecorder(TestRecorder):
    def publish_report(self, report):
        self.events.append(("report", sampler_stopped.is_set()))


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    if not sampler_waiting.wait(1.0):
        raise RuntimeError("sampler did not reach its wait")
    if {mode!r} == "failed":
        raise ValueError("project failure")
    context.commit_step(next_batch(context))


recorder = ClosingRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={{"metrics": {{"systemSamplingInterval": 3}}}},
    dataset=TestDataset(("item",)),
    metric_contracts=TestMetricContracts(
        system_definitions=MetricSchema.definitions()
    ),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=7,
    cancellation_requested=lambda: {mode!r} == "cancelled",
    interruption_requested=lambda: {mode!r} == "interrupted",
    cgroup_memory_reader=lambda: None,
    system_sampler_wait=wait_for_sample,
)
print(json.dumps({{
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "sampler_stopped_at_report": recorder.events[-1][1],
}}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": expected_outcome,
        "cause": expected_cause,
        "sampler_stopped_at_report": True,
    }


@pytest.mark.parametrize("failure_kind", ("invalid_value", "recorder"))
def test_memory_collection_defects_are_skywright_failures(
    failure_kind: str,
) -> None:
    completed = run_project(
        f"""
import json
import threading
import time

from skywright import run_training_process


class State:
    def state_dict(self): return {{}}
    def load_state_dict(self, state): pass


failure_attempted = threading.Event()


def wait_for_sample(stop, interval):
    if failure_attempted.is_set():
        return stop.wait(1.0)
    return False


def read_memory():
    if {failure_kind!r} == "invalid_value":
        failure_attempted.set()
        return -1
    return 100


class FailingRecorder(TestRecorder):
    def publish_wall_time(self, observation):
        failure_attempted.set()
        raise OSError("metric recorder unavailable")


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    if not failure_attempted.wait(1.0):
        raise RuntimeError("sampler did not attempt publication")
    deadline = time.monotonic() + 1.0
    while time.monotonic() < deadline:
        try:
            context.step
        except BaseException:
            raise
        time.sleep(0.001)
    raise RuntimeError("sampler failure was not surfaced")


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={{"metrics": {{"systemSamplingInterval": 1}}}},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(
        system_definitions=MetricSchema.definitions()
    ),
    skywright_metric_schema="test-schema@1",
    recorder=FailingRecorder(),
    seed=8,
    cgroup_memory_reader=read_memory,
    system_sampler_wait=wait_for_sample,
)
print(json.dumps({{
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
}}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "failed",
        "cause": "skywright_failure",
    }


def test_required_monotonic_clock_failure_is_a_skywright_failure() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def broken_clock():
    raise OSError("monotonic clock unavailable")


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(
        system_definitions=MetricSchema.definitions()
    ),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=9,
    monotonic_clock=broken_clock,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "failed",
        "cause": "skywright_failure",
    }


def test_absolute_checkpoint_cadence_confirms_without_waiting_for_an_extra_step() -> (
    None
):
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def __init__(self): self.value = 0
    def state_dict(self): return {"value": self.value}
    def load_state_dict(self, state): self.value = state["value"]


def train(context):
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    for value in (1, 2):
        state.value = value
        context.commit_step(next_batch(context))


recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 2}},
    dataset=TestDataset(("one", "two")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "checkpoints": [event[1].step for event in recorder.events if event[0] == "checkpoint"],
    "confirmations": [list(event[1:]) for event in recorder.events if event[0] == "confirmation"],
    "durable_step": result.report.latest_durable_step,
    "final_state": (
        result.final_checkpoint.state["state"] if result.final_checkpoint else None
    ),
    "cause": result.report.cause.value,
    "diagnostics": dict(result.report.diagnostics),
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "checkpoints": [2],
        "confirmations": [[2, "checkpoint:2"]],
        "durable_step": 2,
        "final_state": {"value": 2},
        "cause": "completed",
        "diagnostics": {},
    }


def test_blocked_cadence_publication_keeps_only_the_newest_pending_snapshot() -> None:
    completed = run_project(
        """
import json
import threading

from skywright import run_training_process


class State:
    def __init__(self): self.value = 0
    def state_dict(self): return {"value": self.value}
    def load_state_dict(self, state): self.value = state["value"]


class BlockingRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.started = threading.Event()
        self.release = threading.Event()
        self.newest_published = threading.Event()

    def publish_checkpoint(self, checkpoint):
        if checkpoint.step == 1:
            self.started.set()
            self.release.wait(2)
        result = super().publish_checkpoint(checkpoint)
        if checkpoint.step == 4:
            self.newest_published.set()
        return result


recorder = BlockingRecorder()


def train(context):
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    for value in range(1, 5):
        state.value = value
        context.commit_step(next_batch(context))
        if value == 1:
            assert recorder.started.wait(2)
        state.value = value * 10
    recorder.release.set()
    assert recorder.newest_published.wait(2)
    state.value = 99


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(tuple(range(4))),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
checkpoints = [event[1] for event in recorder.events if event[0] == "checkpoint"]
print(json.dumps({
    "outcome": result.outcome.value,
    "steps": [checkpoint.step for checkpoint in checkpoints],
    "values": [checkpoint.state["state"]["value"] for checkpoint in checkpoints],
    "step_commits": len([event for event in recorder.events if event[0] == "step"]),
    "confirmations": [event[1] for event in recorder.events if event[0] == "confirmation"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "steps": [1, 4],
        "values": [1, 4],
        "step_commits": 4,
        "confirmations": [1, 4],
    }


def test_background_checkpoint_failure_is_latched_until_context_interaction() -> None:
    completed = run_project(
        """
import json
import threading

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class FailingRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.failed = threading.Event()

    def publish_checkpoint(self, checkpoint):
        self.failed.set()
        raise OSError("cadence upload failed")


recorder = FailingRecorder()
continued = False


def train(context):
    global continued
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))
    assert recorder.failed.wait(2)
    context.step
    continued = True


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(("one",)),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "message": result.report.diagnostics["message"],
    "last_step": result.report.last_committed_step,
    "events": [event[0] for event in recorder.events],
    "continued": continued,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "failed",
        "cause": "skywright_failure",
        "message": "cadence upload failed",
        "last_step": 1,
        "events": ["attempt", "step", "report"],
        "continued": False,
    }


def test_step_publication_continues_while_checkpoint_confirmation_is_blocked() -> None:
    completed = run_project(
        """
import json
import threading

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class ConfirmingRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.confirming = threading.Event()
        self.release = threading.Event()

    def confirm_checkpoint(self, step, reference):
        if step == 1:
            self.confirming.set()
            assert self.release.wait(2)
        super().confirm_checkpoint(step, reference)


recorder = ConfirmingRecorder()


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))
    assert recorder.confirming.wait(2)
    context.commit_step(next_batch(context))
    recorder.release.set()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(("one", "two")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
step_events = [event for event in recorder.events if event[0] == "step"]
print(json.dumps({
    "outcome": result.outcome.value,
    "second_step_durable": list(step_events[1][4:]),
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "second_step_durable": [None, None],
    }


def test_policy_stop_is_terminal_and_makes_the_committed_step_durable() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {"ready": True}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("one",)),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=1,
    policy_stop_requested=lambda: "ceiling-decision-17",
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "durable_step": result.report.latest_durable_step,
    "decision": result.report.diagnostics["ceiling_stop_decision"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "cancelled",
        "cause": "policy_stopped",
        "last_step": 1,
        "durable_step": 1,
        "decision": "ceiling-decision-17",
    }


def test_snapshot_capture_failure_keeps_the_logical_step_committed() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): raise ValueError("state capture failed")
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(("one",)),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "message": result.report.diagnostics["message"],
    "last_step": result.report.last_committed_step,
    "durable_step": result.report.latest_durable_step,
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "message": "state capture failed",
        "last_step": 1,
        "durable_step": None,
        "events": ["attempt", "step", "report"],
    }


def test_recovery_applies_cadence_to_absolute_steps() -> None:
    completed = run_project(
        """
import json
import threading

from skywright import CheckpointSnapshot, DatasetCursor, run_training_process


class State:
    def __init__(self): self.value = 0
    def state_dict(self): return {"value": self.value}
    def load_state_dict(self, state): self.value = state["value"]


class CadenceRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.scheduled = threading.Event()

    def publish_checkpoint(self, checkpoint):
        self.scheduled.set()
        return super().publish_checkpoint(checkpoint)


recorder = CadenceRecorder()


def train(context):
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    context.commit_step(next_batch(context))
    assert not recorder.scheduled.is_set()
    context.commit_step(next_batch(context))
    assert recorder.scheduled.wait(2)


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 3}},
    dataset=TestDataset(tuple(range(10))),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
    resume_from=CheckpointSnapshot(
        step=4,
        state={"state": {"value": 4}},
        dataset_cursor=DatasetCursor(
            item_offset=4,
            epoch_step=4,
            ordering_fingerprint="sha256:test-ordering",
        ),
        reference="checkpoint:seed",
    ),
)
print(json.dumps({
    "outcome": result.outcome.value,
    "checkpoint_steps": [
        event[1].step for event in recorder.events if event[0] == "checkpoint"
    ],
    "durable_step": result.report.latest_durable_step,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "checkpoint_steps": [6],
        "durable_step": 6,
    }


def test_default_cadence_applies_to_large_absolute_steps() -> None:
    completed = run_project(
        """
import json
import threading

from skywright import (
    CheckpointSnapshot,
    DatasetBatch,
    DatasetCursor,
    run_training_process,
)


class State:
    def __init__(self): self.value = 0
    def state_dict(self): return {"value": self.value}
    def load_state_dict(self, state): self.value = state["value"]


class LargeStepDataset:
    ordering_fingerprint = "sha256:large-step-ordering"

    def batches(self, cursor):
        yield DatasetBatch(
            items=("item",),
            next_cursor=DatasetCursor(
                epoch=cursor.epoch,
                item_offset=cursor.item_offset + 1,
                epoch_step=cursor.epoch_step + 1,
                ordering_fingerprint=self.ordering_fingerprint,
            ),
            epoch=cursor.epoch,
        )


class CadenceRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.scheduled = threading.Event()

    def publish_checkpoint(self, checkpoint):
        self.scheduled.set()
        return super().publish_checkpoint(checkpoint)


recorder = CadenceRecorder()


def train(context):
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    context.commit_step(next_batch(context))
    assert recorder.scheduled.wait(2)


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=LargeStepDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
    resume_from=CheckpointSnapshot(
        step=9_999_999_999,
        state={"state": {"value": 9_999_999_999}},
        dataset_cursor=DatasetCursor(
            item_offset=9_999_999_999,
            epoch_step=9_999_999_999,
            ordering_fingerprint="sha256:large-step-ordering",
        ),
        reference="checkpoint:seed",
    ),
)
print(json.dumps({
    "outcome": result.outcome.value,
    "checkpoint_steps": [
        event[1].step for event in recorder.events if event[0] == "checkpoint"
    ],
    "durable_step": result.report.latest_durable_step,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "checkpoint_steps": [10_000_000_000],
        "durable_step": 10_000_000_000,
    }


def test_cancellation_drains_active_and_pending_cadence_work_without_a_terminal_checkpoint() -> (
    None
):
    completed = run_project(
        """
import json
import threading

from skywright import run_training_process


class State:
    def __init__(self): self.value = 0
    def state_dict(self): return {"value": self.value}
    def load_state_dict(self, state): self.value = state["value"]


class CancellableRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.started = threading.Event()
        self.cancelled = threading.Event()

    def publish_checkpoint(self, checkpoint):
        self.started.set()
        self.cancelled.wait(2)
        return super().publish_checkpoint(checkpoint)

    def cancel_checkpoint_publication(self):
        self.cancelled.set()


recorder = CancellableRecorder()
cancel = False


def train(context):
    global cancel
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    state.value = 1
    context.commit_step(next_batch(context))
    assert recorder.started.wait(2)
    state.value = 2
    context.commit_step(next_batch(context))
    cancel = True
    state.value = 3
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(("one", "two", "three")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
    cancellation_requested=lambda: cancel,
    policy_stop_requested=lambda: "policy-loses" if cancel else None,
    interruption_requested=lambda: cancel,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "durable_step": result.report.latest_durable_step,
    "checkpoints": [event[1].step for event in recorder.events if event[0] == "checkpoint"],
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "cancelled",
        "cause": "cancelled",
        "last_step": 3,
        "durable_step": 1,
        "checkpoints": [1],
        "events": [
            "attempt",
            "step",
            "step",
            "step",
            "checkpoint",
            "confirmation",
            "report",
        ],
    }


def test_expected_active_publication_cancellation_preserves_cancelled_cause() -> None:
    completed = run_project(
        """
import json
import threading

from skywright import run_training_process
from skywright.run_store import RunStoreCancelledError


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class CancellingRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.started = threading.Event()
        self.cancelled = threading.Event()

    def publish_checkpoint(self, checkpoint):
        self.started.set()
        assert self.cancelled.wait(2)
        raise RunStoreCancelledError("publication cancelled")

    def cancel_checkpoint_publication(self):
        self.cancelled.set()

    def resume_after_checkpoint_cancellation(self):
        pass


recorder = CancellingRecorder()
cancel = False


def train(context):
    global cancel
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))
    assert recorder.started.wait(2)
    cancel = True
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(("one", "two")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
    cancellation_requested=lambda: cancel,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "durable_step": result.report.latest_durable_step,
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "cancelled",
        "cause": "cancelled",
        "durable_step": None,
        "events": ["attempt", "step", "step", "report"],
    }


def test_late_cancellation_preempts_policy_stop_terminal_publication() -> None:
    completed = run_project(
        """
import json
import threading

from skywright import run_training_process
from skywright.run_store import RunStoreCancelledError


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class BlockingTerminalRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.confirmation_started = threading.Event()
        self.cancellation_reached_recorder = threading.Event()

    def publish_checkpoint(self, checkpoint):
        return super().publish_checkpoint(checkpoint)

    def confirm_checkpoint(self, step, reference):
        self.confirmation_started.set()
        self.cancellation_reached_recorder.wait()
        raise RunStoreCancelledError("terminal confirmation cancelled")

    def cancel_checkpoint_publication(self):
        assert self.confirmation_started.is_set()
        self.cancellation_reached_recorder.set()

    def resume_after_checkpoint_cancellation(self):
        pass


recorder = BlockingTerminalRecorder()


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("one",)),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
    cancellation_requested=recorder.confirmation_started.is_set,
    policy_stop_requested=lambda: "decision-1",
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "durable_step": result.report.latest_durable_step,
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "cancelled",
        "cause": "cancelled",
        "durable_step": None,
        "events": ["attempt", "step", "checkpoint", "report"],
    }


def test_shutdown_deadline_does_not_publish_report_before_checkpoint_work_stops() -> (
    None
):
    completed = run_project(
        """
import json
import threading

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class IgnoringRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.started = threading.Event()
        self.release = threading.Event()
        self.finished = threading.Event()

    def publish_checkpoint(self, checkpoint):
        return super().publish_checkpoint(checkpoint)

    def confirm_checkpoint(self, step, reference):
        self.started.set()
        self.release.wait(2)
        super().confirm_checkpoint(step, reference)
        self.finished.set()


recorder = IgnoringRecorder()


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))
    assert recorder.started.wait(2)
    raise ValueError("project failed")


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(("one",)),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
    shutdown_grace_seconds=0.05,
)
events_at_return = [event[0] for event in recorder.events]
recorder.release.set()
assert recorder.finished.wait(2)
print(json.dumps({
    "cause": result.report.cause.value,
    "message": result.report.diagnostics["message"],
    "cleanup": result.report.diagnostics["checkpoint_cleanup_failure"]["exception_type"],
    "events_at_return": events_at_return,
    "events_after_release": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "training_project_failure",
        "message": "project failed",
        "cleanup": "TimeoutError",
        "events_at_return": ["attempt", "step", "checkpoint"],
        "events_after_release": ["attempt", "step", "checkpoint", "confirmation"],
    }


def test_report_closes_the_recorder_after_all_checkpoint_writes() -> None:
    completed = run_project(
        """
import json
import threading
import time

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class ClosingRecorder(TestRecorder):
    def __init__(self):
        super().__init__()
        self.closed = False
        self.release = threading.Event()
        self.late_writes = []

    def publish_checkpoint(self, checkpoint):
        if self.closed:
            self.late_writes.append("checkpoint")
            raise RuntimeError("checkpoint after report")
        if checkpoint.step == 1:
            assert self.release.wait(2)
        return super().publish_checkpoint(checkpoint)

    def confirm_checkpoint(self, step, reference):
        if self.closed:
            self.late_writes.append("confirmation")
            raise RuntimeError("confirmation after report")
        super().confirm_checkpoint(step, reference)

    def publish_step(self, *values):
        super().publish_step(*values)
        if values[0] == 2:
            self.release.set()

    def publish_report(self, report):
        super().publish_report(report)
        self.closed = True


recorder = ClosingRecorder()


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"checkpoint": {"cadence": 1}},
    dataset=TestDataset(("one", "two")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
time.sleep(0.05)
print(json.dumps({
    "outcome": result.outcome.value,
    "late_writes": recorder.late_writes,
    "last_event": recorder.events[-1][0],
    "checkpoint_steps": [
        event[1].step for event in recorder.events if event[0] == "checkpoint"
    ],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "completed",
        "late_writes": [],
        "last_event": "report",
        "checkpoint_steps": [1, 2],
    }


def test_run_context_exposes_the_immutable_composed_metric_catalog() -> None:
    completed = run_project(
        """
import json
from dataclasses import FrozenInstanceError

from skywright import run_training_process
from skywright.metrics import MetricContract, MetricSchema, ProjectMetricContract


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


artifact = {
    "contractVersion": 1,
    "skywrightSchema": MetricSchema.identity(),
    "definitions": [{
        "name": "train/loss",
        "numericKind": "real",
        "unit": "dimensionless",
        "recordingBasis": "step",
        "comparison": "minimize",
        "stepReduction": "mean",
    }],
}
compiled = MetricContract.compile(artifact)
seen = {}


def train(context):
    seen["project"] = context.metric_catalog.project_identity
    seen["version"] = context.metric_catalog.project_version
    seen["names"] = [item.name for item in context.metric_catalog.definitions]
    try:
        context.metric_catalog.project_identity = "changed"
    except FrozenInstanceError:
        seen["immutable"] = True
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


run_training_process(
    train,
    run_id="test-run",
    project_version="project@revision",
    configuration={},
    dataset=TestDataset(("item",)),
    metric_contracts=ProjectMetricContract(
        artifact,
        expected_digest=compiled.digest,
        project_identity="stable-project",
    ),
    skywright_metric_schema=MetricSchema.identity()["version"],
    recorder=TestRecorder(),
    seed=1,
)
print(json.dumps(seen))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "project": "stable-project",
        "version": "project@revision",
        "names": [
            "train/loss",
            "skywright/system/throughput",
            "skywright/system/data_loading_wait",
            "skywright/system/memory_used",
        ],
        "immutable": True,
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
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
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
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
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

from skywright import CheckpointSnapshot, DatasetCursor, run_training_process


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
    observed["cursor"] = (
        context.dataset_cursor.epoch,
        context.dataset_cursor.item_offset,
        context.dataset_cursor.epoch_step,
    )
    observed["rng"] = (random.random(), numpy.random.random(), torch.rand(1).item())
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=999,
    resume_from=CheckpointSnapshot(
        step=4,
        state={"counter": {"value": 23}},
        runtime_state=runtime_state,
        dataset_cursor=DatasetCursor(
            epoch=1,
            item_offset=9,
            epoch_step=4,
            ordering_fingerprint="sha256:test-ordering",
        ),
        reference="checkpoint:seed",
    ),
)
assert result.report.last_committed_step == 5
assert result.final_checkpoint.dataset_cursor == DatasetCursor(
    epoch=1,
    item_offset=10,
    epoch_step=5,
    ordering_fingerprint="sha256:test-ordering",
)
assert observed == {
    "resumed": True,
    "counter": 23,
    "cursor": (1, 9, 4),
    "rng": expected,
}
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
    observed["labels"] = sorted(context.configuration["training"]["labels"])
    batches = list(context.dataset.batches(context.dataset_cursor))
    observed["dataset"] = [item for batch in batches for item in batch.items]
    try:
        context.configuration["training"]["batch_size"] = 99
    except TypeError:
        observed["immutable"] = True
    try:
        context.configuration["training"]["labels"].add("validation")
    except AttributeError:
        observed["set_immutable"] = True
    context.register_checkpoint_state("state", State())
    context.start()
    context.persist_artifact("model.txt", b"model summary")
    context.persist_sample("preview", b"png bytes", media_type="image/png")
    context.commit_step(batches[-1])


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={"training": {"batch_size": 8, "labels": {"train"}}},
    dataset=TestDataset(("item-2", "item-1")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
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
            "labels": ["train"],
            "dataset": ["item-2", "item-1"],
            "immutable": True,
            "set_immutable": True,
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
    context.commit_step(next_batch(context))
    continued = True


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
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


def test_cancellation_wins_when_requested_while_checking_interruption() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


checks = iter((False, True))


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=1,
    cancellation_requested=lambda: next(checks),
    interruption_requested=lambda: True,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "cancelled",
        "cause": "cancelled",
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
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
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
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
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
first_recorder = TestRecorder()
first = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(invalid,),
    skywright_metric_schema="test-schema@1",
    recorder=first_recorder,
    seed=1,
)
second = run_training_process(
    train,
    run_id="test-run-2",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=1,
)
print(json.dumps({
    "called": called,
    "first_cause": first.report.cause.value,
    "first_stage": first.report.diagnostics["stage"],
    "second_rule": second.report.diagnostics["rule"],
    "first_events": [event[0] for event in first_recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "called": False,
        "first_cause": "skywright_failure",
        "first_stage": "construction",
        "second_rule": "run-context/one-per-process",
        "first_events": ["attempt", "report"],
    }


def test_invalid_library_metric_is_a_skywright_failure() -> None:
    completed = run_project(
        """
import json

from skywright import MetricCatalog, MetricDefinition, run_training_process


class InvalidSystemContracts:
    def compose(self, project_version, schema_identity):
        return MetricCatalog(
            project_identity=project_version,
            project_contract_digest="sha256:project",
            skywright_schema_identity=schema_identity,
            skywright_schema_digest="sha256:skywright",
            units=frozenset(MetricSchema.units()),
            project_definitions=(),
            system_definitions=(MetricDefinition(
                name="skywright/system/throughput",
                numeric_kind="real",
                unit="dimensionless",
                comparison="maximize",
                step_reduction="mean",
                minimum=0,
            ),),
        )


result = run_training_process(
    lambda context: None,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=InvalidSystemContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=1,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "stage": result.report.diagnostics["stage"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "stage": "construction",
    }


def test_incomplete_system_metric_catalog_is_a_skywright_failure() -> None:
    completed = run_project(
        """
import json

from skywright import MetricCatalog, run_training_process


class IncompleteSystemContracts:
    def compose(self, project_version, schema_identity):
        return MetricCatalog(
            project_identity=project_version,
            project_contract_digest="sha256:project",
            skywright_schema_identity=schema_identity,
            skywright_schema_digest="sha256:skywright",
            units=frozenset(MetricSchema.units()),
            project_definitions=(),
            system_definitions=MetricSchema.definitions()[:-1],
        )


result = run_training_process(
    lambda context: None,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=IncompleteSystemContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=1,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "stage": result.report.diagnostics["stage"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "stage": "construction",
    }


def test_malformed_project_metric_catalog_is_a_skywright_failure() -> None:
    completed = run_project(
        """
import json

from skywright import MetricDefinition, run_training_process


invalid = MetricDefinition(
    name="train/loss",
    numeric_kind="real",
    unit="dimensionless",
    comparison="minimize",
    minimum="0",
)
result = run_training_process(
    lambda context: None,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(invalid),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=1,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "stage": result.report.diagnostics["stage"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "stage": "construction",
    }


def test_identity_mismatched_metric_catalog_is_a_skywright_failure() -> None:
    completed = run_project(
        """
import json

from skywright import MetricCatalog, run_training_process


class WrongIdentityContracts:
    def compose(self, project_version, schema_identity):
        return MetricCatalog(
            project_identity="different-project@revision",
            project_contract_digest="sha256:project",
            skywright_schema_identity=schema_identity,
            skywright_schema_digest="sha256:skywright",
            units=frozenset(("dimensionless",)),
            project_definitions=(),
        )


result = run_training_process(
    lambda context: None,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=WrongIdentityContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=1,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "stage": result.report.diagnostics["stage"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "stage": "construction",
    }


def test_runtime_command_executes_a_training_project_entry_point(
    tmp_path: Path,
) -> None:
    project = tmp_path / "example_project.py"
    project.write_text(
        """
import random

from skywright import DatasetCursor

assert random.random() == random.Random(12).random()


class State:
    def state_dict(self):
        return {"ready": True}

    def load_state_dict(self, state):
        pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.observe("train/loss", 2.0)
    context.commit_step(next(iter(context.dataset.batches(context.dataset_cursor))))


""",
        encoding="utf-8",
    )
    (tmp_path / "runtime_support.py").write_text(
        """
from skywright import DatasetBatch, DatasetCursor, MetricCatalog, MetricDefinition
from skywright.metrics import MetricSchema


class Dataset:
    @property
    def ordering_fingerprint(self): return "sha256:runtime-ordering"

    def batches(self, cursor):
        yield DatasetBatch(("item-0",), DatasetCursor(
            item_offset=1,
            epoch_step=1,
            ordering_fingerprint=self.ordering_fingerprint,
        ))


class Recorder:
    def publish_attempt(self, attempt): pass
    def publish_checkpoint(self, checkpoint): return "checkpoint:runtime"
    def confirm_checkpoint(self, step, reference): pass
    def publish_step(self, step, dataset_cursor, observations, durable_step, durable_ref): pass
    def publish_wall_time(self, observation): pass
    def publish_artifact(self, artifact): pass
    def publish_sample(self, sample): pass
    def publish_report(self, report): pass


def dataset(): return Dataset()
def recorder(): return Recorder()


class MetricContracts:
    def compose(self, project_version, schema_identity):
        return MetricCatalog(
            project_identity=project_version,
            project_contract_digest="sha256:project",
            skywright_schema_identity=schema_identity,
            skywright_schema_digest="sha256:skywright",
            units=frozenset(MetricSchema.units()),
            project_definitions=(MetricDefinition(
                name="train/loss",
                numeric_kind="real",
                unit="dimensionless",
                comparison="minimize",
                step_reduction="mean",
            ),),
            system_definitions=MetricSchema.definitions(),
        )


def metric_contracts(): return MetricContracts()
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
                "dataset_factory": "runtime_support:dataset",
                "recorder_factory": "runtime_support:recorder",
                "metric_contract_factory": "runtime_support:metric_contracts",
                "skywright_metric_schema": "skywright-metrics@1",
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

    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert json.loads(completed.stdout) == {
        "attempt_id": json.loads(completed.stdout)["attempt_id"],
        "cause": "completed",
        "diagnostics": {},
        "last_committed_step": 1,
        "latest_durable_step": 1,
        "latest_durable_checkpoint": "checkpoint:runtime",
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
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(MetricDefinition(
        name="train/loss",
        numeric_kind="real",
        unit="dimensionless",
        comparison="minimize",
        step_reduction="mean",
    ),),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=2,
)
print(json.dumps([
    [observation.name, observation.step, observation.value]
    for observation in result.metric_observations
    if not observation.name.startswith("skywright/")
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
    context.commit_step(next_batch(context))
    raise ValueError("bad project arithmetic")


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
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


def test_checkpoint_publication_failure_is_a_skywright_failure() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class FailingRecorder(TestRecorder):
    def publish_checkpoint(self, checkpoint):
        raise OSError("Run Store unavailable")


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


recorder = FailingRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=3,
)
print(json.dumps({
    "outcome": result.outcome.value,
    "cause": result.report.cause.value,
    "durable_step": result.report.latest_durable_step,
    "checkpoint": result.final_checkpoint,
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "outcome": "failed",
        "cause": "skywright_failure",
        "durable_step": None,
        "checkpoint": None,
        "events": ["attempt", "step", "report"],
    }


def test_commit_rejects_a_fabricated_dataset_batch() -> None:
    completed = run_project(
        """
import json

from skywright import DatasetBatch, run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    issued = next_batch(context)
    fabricated = DatasetBatch(issued.items, issued.next_cursor)
    try:
        context.commit_step(fabricated)
    except Exception:
        pass


recorder = TestRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "observations": len(result.metric_observations),
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "last_step": 0,
        "observations": 0,
        "events": ["attempt", "report"],
    }


def test_dataset_batches_cannot_cross_an_epoch_within_one_step() -> None:
    completed = run_project(
        """
import json

from skywright import DatasetBatch, DatasetCursor, run_training_process


class CrossingDataset:
    ordering_fingerprint = "sha256:crossing-order"

    def batches(self, cursor):
        yield DatasetBatch(
            ("epoch-0-final",),
            DatasetCursor(
                epoch=1,
                item_offset=0,
                epoch_step=0,
                ordering_fingerprint=self.ordering_fingerprint,
            ),
            epoch=0,
        )
        yield DatasetBatch(
            ("epoch-1-first",),
            DatasetCursor(
                epoch=1,
                item_offset=1,
                epoch_step=1,
                ordering_fingerprint=self.ordering_fingerprint,
            ),
            epoch=1,
        )


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    list(context.dataset.batches(context.dataset_cursor))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=CrossingDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "dataset-cursor/epoch-boundary",
    }


def test_dataset_batch_epoch_cannot_jump_from_the_requested_cursor() -> None:
    completed = run_project(
        """
import json

from skywright import DatasetBatch, DatasetCursor, run_training_process


class JumpingDataset:
    ordering_fingerprint = "sha256:jumping-order"

    def batches(self, cursor):
        yield DatasetBatch(
            ("future-item",),
            DatasetCursor(
                epoch=10,
                item_offset=1,
                epoch_step=1,
                ordering_fingerprint=self.ordering_fingerprint,
            ),
            epoch=10,
        )


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    next_batch(context)


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=JumpingDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "dataset-batch/epoch-transition",
    }


def test_only_the_latest_issued_batch_can_commit_a_step() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    batches = list(context.dataset.batches(context.dataset_cursor))
    context.commit_step(batches[0])


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("first", "second")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "dataset-cursor/not-issued",
    }


def test_dataset_iterators_cannot_overlap() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    first = iter(context.dataset.batches(context.dataset_cursor))
    next(first)
    second = iter(context.dataset.batches(context.dataset_cursor))
    next(second)


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("first", "second")),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "dataset-cursor/overlapping-iteration",
    }


def test_failed_atomic_step_publication_does_not_commit_observations() -> None:
    completed = run_project(
        """
import json

from skywright import MetricDefinition, run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class FailingStepRecorder(TestRecorder):
    def publish_step(self, *args):
        raise OSError("metric writer unavailable")


moments = iter((0.0, 1.0, 2.0, 3.0))


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.observe("train/loss", 2.0)
    context.commit_step(next_batch(context))


recorder = FailingStepRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(MetricDefinition(
        name="train/loss",
        numeric_kind="real",
        unit="dimensionless",
        comparison="minimize",
    ), system_definitions=MetricSchema.definitions()),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=3,
    monotonic_clock=lambda: next(moments),
)
print(json.dumps({
    "cause": result.report.cause.value,
    "last_step": result.report.last_committed_step,
    "observations": len(result.metric_observations),
    "events": [event[0] for event in recorder.events],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "last_step": 0,
        "observations": 0,
        "events": ["attempt", "report"],
    }


def test_non_finite_metric_reduction_is_rejected() -> None:
    completed = run_project(
        """
import json

from skywright import MetricDefinition, run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.observe("train/total", 1e308)
    context.observe("train/total", 1e308)
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(MetricDefinition(
        name="train/total",
        numeric_kind="real",
        unit="dimensionless",
        comparison="minimize",
        step_reduction="sum",
    )),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
    "last_step": result.report.last_committed_step,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "metric/non-finite-reduction",
        "last_step": 0,
    }


def test_resume_checkpoint_step_and_cursor_are_validated() -> None:
    cases = (
        ("step=-1", "resume/checkpoint-step"),
        (
            "step=1, dataset_cursor=DatasetCursor("
            "item_offset=-1, ordering_fingerprint='sha256:test-ordering')",
            "dataset-cursor/shape",
        ),
    )

    for checkpoint_arguments, expected_rule in cases:
        completed = run_project(
            f"""
import json

from skywright import CheckpointSnapshot, DatasetCursor, run_training_process


result = run_training_process(
    lambda context: None,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={{}},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
    resume_from=CheckpointSnapshot(
        {checkpoint_arguments},
        state={{}},
        reference="checkpoint:seed",
    ),
)
print(json.dumps({{
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
}}))
"""
        )

        assert completed.returncode == 0, completed.stderr
        assert json.loads(completed.stdout) == {
            "cause": "contract_violation",
            "rule": expected_rule,
        }


def test_resumed_attempt_must_commit_a_new_step() -> None:
    completed = run_project(
        """
import json

from skywright import CheckpointSnapshot, DatasetCursor, run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
    resume_from=CheckpointSnapshot(
        step=4,
        state={"state": {}},
        dataset_cursor=DatasetCursor(
            item_offset=4,
            epoch_step=4,
            ordering_fingerprint="sha256:test-ordering",
        ),
        reference="checkpoint:seed",
    ),
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
    "last_step": result.report.last_committed_step,
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "step/empty-run",
        "last_step": 4,
    }


def test_checkpoint_state_object_cannot_be_registered_twice() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    state = State()
    context.register_checkpoint_state("first", state)
    context.register_checkpoint_state("second", state)


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "rule": result.report.diagnostics["rule"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "checkpoint-state/identity",
    }


def test_checkpoint_snapshot_isolated_from_mutable_state() -> None:
    completed = run_project(
        """
import json

import numpy

from skywright import CheckpointSnapshot


values = numpy.array([1, 2])
source = {"state": {"values": values, "nested": [3]}}
checkpoint = CheckpointSnapshot(step=1, state=source)
values[0] = 99
source["state"]["nested"].append(4)
exposed = checkpoint.state
exposed["state"]["values"][1] = 88
exposed["state"]["nested"].append(5)
preserved = checkpoint.state["state"]
print(json.dumps({
    "values": preserved["values"].tolist(),
    "nested": preserved["nested"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "values": [1, 2],
        "nested": [3],
    }


def test_dataset_adapter_failure_is_a_skywright_failure() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class BrokenDataset(TestDataset):
    def batches(self, cursor):
        raise OSError("Dataset backend unavailable")


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    next_batch(context)


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=BrokenDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "message": result.report.diagnostics["message"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "message": "Dataset backend unavailable",
    }


def test_report_failure_preserves_training_project_failure_evidence() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


class FailingReportRecorder(TestRecorder):
    def publish_report(self, report):
        raise OSError("report store unavailable")


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))
    raise ValueError("original project failure")


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=FailingReportRecorder(),
    seed=3,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "message": result.report.diagnostics["message"],
    "report_failure": result.report.diagnostics["report_publication_failure"]["message"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "training_project_failure",
        "message": "original project failure",
        "report_failure": "report store unavailable",
    }


def test_observability_failure_changes_nominal_cause_before_report() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class FailingObservabilityRecorder(TestRecorder):
    def finalize_observability(self):
        raise OSError("metric storage unavailable")


recorder = FailingObservabilityRecorder()


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("item",)),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
published = [event[1] for event in recorder.events if event[0] == "report"]
print(json.dumps({
    "cause": result.report.cause.value,
    "published_cause": published[0].cause.value,
    "failure": result.report.diagnostics["observability_finalization_failure"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "published_cause": "skywright_failure",
        "failure": {
            "exception_type": "OSError",
            "message": "metric storage unavailable",
        },
    }


def test_observability_failure_does_not_replace_the_project_failure() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process


class FailingObservabilityRecorder(TestRecorder):
    def finalize_observability(self):
        raise OSError("metric storage unavailable")


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    raise LookupError("project failed")


recorder = FailingObservabilityRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(()),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
published = [event[1] for event in recorder.events if event[0] == "report"]
print(json.dumps({
    "cause": result.report.cause.value,
    "published_cause": published[0].cause.value,
    "project_failure": result.report.diagnostics["message"],
    "metric_failure": result.report.diagnostics["observability_finalization_failure"]["message"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "training_project_failure",
        "published_cause": "training_project_failure",
        "project_failure": "project failed",
        "metric_failure": "metric storage unavailable",
    }


def test_incomplete_observability_shutdown_suppresses_the_report() -> None:
    completed = run_project(
        """
import json

from skywright import run_training_process
from skywright._training_errors import ObservabilityShutdownIncomplete


class StuckObservabilityRecorder(TestRecorder):
    def finalize_observability(self):
        raise ObservabilityShutdownIncomplete("writer still active")


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next_batch(context))


recorder = StuckObservabilityRecorder()
result = run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(("item",)),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=recorder,
    seed=1,
)
print(json.dumps({
    "cause": result.report.cause.value,
    "reports": len([event for event in recorder.events if event[0] == "report"]),
    "failure": result.report.diagnostics["observability_finalization_failure"]["message"],
}))
"""
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "skywright_failure",
        "reports": 0,
        "failure": "writer still active",
    }


def test_shutdown_grace_forces_exit_when_project_ignores_interruption() -> None:
    completed = run_project(
        """
import os
import signal

from skywright import run_training_process


class State:
    def state_dict(self): return {}
    def load_state_dict(self, state): pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    os.kill(os.getpid(), signal.SIGTERM)
    while True:
        pass


run_training_process(
    train,
    run_id="test-run",
    project_version="test-project@abc123",
    configuration={},
    dataset=TestDataset(),
    metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1",
    recorder=TestRecorder(),
    seed=3,
    shutdown_grace_seconds=0.05,
)
"""
    )

    assert completed.returncode == 1


@pytest.mark.parametrize(
    ("seed", "configuration", "field"),
    [
        (20, {}, "seed"),
        (19, {"reproducibility": {"seed": 20}}, "seed"),
        (19, {"dataset": {"ordering": {"policy": "replacement"}}}, "policy"),
        (19, {"dataset": {"ordering": {"version": "v2"}}}, "version"),
    ],
)
def test_ordering_inputs_rejected_before_project_construction(
    seed: int, configuration: dict[str, object], field: str
) -> None:
    completed = run_project(
        f"""
import json
from skywright import run_training_process
from skywright.dataset import DatasetOrdering

class OrderedDataset(TestDataset):
    ordering = DatasetOrdering("definition", "content", 19)
    @property
    def ordering_fingerprint(self): return self.ordering.fingerprint

def train(context):
    raise AssertionError("project must not run with mismatched ordering inputs")

result = run_training_process(
    train, run_id="run", project_version="project", configuration={configuration!r},
    dataset=OrderedDataset(), metric_contracts=TestMetricContracts(),
    skywright_metric_schema="test-schema@1", recorder=TestRecorder(), seed={seed},
)
print(json.dumps({{"cause": result.report.cause.value, "rule": result.report.diagnostics["rule"]}}))
"""
    )
    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {
        "cause": "contract_violation",
        "rule": "dataset-ordering/" + field,
    }
