"""Public Training Project authoring surface for the Skywright runtime SDK."""

from importlib.metadata import version as _distribution_version

from skywright._training import (
    Accelerator,
    ArtifactRecord,
    CheckpointSnapshot,
    CheckpointState,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    ExecutionTerminationReport,
    MetricDefinition,
    MetricObservation,
    ResumeState,
    RunContext,
    SampleRecord,
    ScalarValue,
    TrainingContractViolation,
    TrainingProcessOutcome,
    TrainingProcessResult,
    TrainingProject,
    run_training_process,
)

__all__ = (
    "Accelerator",
    "ArtifactRecord",
    "CheckpointSnapshot",
    "CheckpointState",
    "ExecutionAttemptRecord",
    "ExecutionTerminationCause",
    "ExecutionTerminationReport",
    "MetricDefinition",
    "MetricObservation",
    "ResumeState",
    "RunContext",
    "SampleRecord",
    "ScalarValue",
    "TrainingContractViolation",
    "TrainingProcessOutcome",
    "TrainingProcessResult",
    "TrainingProject",
    "__version__",
    "run_training_process",
    "version",
)

__version__: str = _distribution_version("skywright")
version: str = __version__
