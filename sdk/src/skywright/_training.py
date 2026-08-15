"""Compatibility facade for the public Training Project authoring surface."""

from skywright._training_errors import TrainingContractViolation
from skywright._training_process import run_training_process
from skywright._training_protocols import (
    CheckpointState,
    DatasetAccess,
    MetricContractResolver,
    RunContext,
    ScalarValue,
    TrainingProcessRecorder,
    TrainingProject,
)
from skywright._training_types import (
    Accelerator,
    ArtifactRecord,
    CheckpointSnapshot,
    DatasetBatch,
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    ExecutionTerminationReport,
    MetricCatalog,
    MetricDefinition,
    MetricObservation,
    ResumeState,
    SampleRecord,
    TrainingProcessOutcome,
    TrainingProcessResult,
)

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
    "run_training_process",
)
