"""Public Training Project authoring surface for the Skywright runtime SDK."""

from importlib import import_module as _import_module
from importlib.metadata import version as _distribution_version
from typing import TYPE_CHECKING as _TYPE_CHECKING

from skywright._training import (
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
    run_training_process,
)

if _TYPE_CHECKING:
    from skywright import configuration as configuration


def __getattr__(name: str) -> object:
    if name == "configuration":
        module = _import_module("skywright.configuration")
        globals()[name] = module
        return module
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


__all__ = (
    "Accelerator",
    "ArtifactRecord",
    "CheckpointSnapshot",
    "CheckpointState",
    "DatasetAccess",
    "DatasetBatch",
    "DatasetCursor",
    "ExecutionAttemptRecord",
    "ExecutionTerminationCause",
    "ExecutionTerminationReport",
    "MetricCatalog",
    "MetricContractResolver",
    "MetricDefinition",
    "MetricObservation",
    "ResumeState",
    "RunContext",
    "SampleRecord",
    "ScalarValue",
    "TrainingContractViolation",
    "TrainingProcessOutcome",
    "TrainingProcessRecorder",
    "TrainingProcessResult",
    "TrainingProject",
    "__version__",
    "configuration",
    "run_training_process",
    "version",
)

__version__: str = _distribution_version("skywright")
version: str = __version__
