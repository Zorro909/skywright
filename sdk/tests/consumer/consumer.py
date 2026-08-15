# pyright: strict

from collections.abc import Callable, Mapping

import skywright
from skywright import (
    Accelerator,
    ArtifactRecord,
    CheckpointSnapshot,
    CheckpointState,
    DatasetAccess,
    DatasetBatch,
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    ExecutionTerminationReport,
    MetricCatalog,
    MetricContractResolver,
    MetricDefinition,
    MetricObservation,
    ResumeState,
    RunContext,
    SampleRecord,
    ScalarValue,
    TrainingContractViolation,
    TrainingProcessOutcome,
    TrainingProcessRecorder,
    TrainingProcessResult,
    TrainingProject,
    __version__,
    run_training_process,
    version,
)


def accepts_string(value: str) -> None:
    del value


accepts_string(__version__)
accepts_string(version)
public_names: tuple[str, ...] = skywright.__all__


class ConsumerState:
    def state_dict(self) -> Mapping[str, object]:
        return {"step": 0}

    def load_state_dict(self, state: Mapping[str, object]) -> None:
        del state


def training_project(context: RunContext) -> None:
    state: CheckpointState = ConsumerState()
    context.register_checkpoint_state("consumer", state)
    context.start()
    batch = next(iter(context.dataset.batches(context.dataset_cursor)))
    context.commit_step(batch)


project: TrainingProject = training_project
runner: Callable[..., TrainingProcessResult] = run_training_process
public_types: tuple[type[object], ...] = (
    Accelerator,
    ArtifactRecord,
    CheckpointSnapshot,
    DatasetBatch,
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    ExecutionTerminationReport,
    MetricDefinition,
    MetricCatalog,
    MetricObservation,
    ResumeState,
    SampleRecord,
    TrainingContractViolation,
    TrainingProcessOutcome,
    TrainingProcessResult,
)
scalar: ScalarValue
dataset_access: DatasetAccess
recorder: TrainingProcessRecorder
metric_contract_resolver: MetricContractResolver
del project, public_types, runner
