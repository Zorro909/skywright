"""Value types shared across the Training Process Boundary."""

from __future__ import annotations

import copy
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Literal, TypeAlias

NumericKind: TypeAlias = Literal["real", "integer"]
Comparison: TypeAlias = Literal["minimize", "maximize", "none"]
StepReduction: TypeAlias = Literal["mean", "sum", "min", "max", "last"]
AcceleratorKind: TypeAlias = Literal["cpu", "cuda", "rocm"]
FailureStage: TypeAlias = Literal["construction", "project", "finalization"]
RecordingBasis: TypeAlias = Literal["step", "wall_time"]


class TrainingProcessOutcome(Enum):
    """Orchestration control emitted by a Training Process Boundary."""

    COMPLETED = "completed"
    INTERRUPTED = "interrupted"
    CANCELLED = "cancelled"
    FAILED = "failed"


class ExecutionTerminationCause(Enum):
    """Canonical process-known reason an Execution Attempt terminated."""

    COMPLETED = "completed"
    CANCELLED = "cancelled"
    INTERRUPTED = "interrupted"
    POLICY_STOPPED = "policy_stopped"
    CONTRACT_VIOLATION = "contract_violation"
    TRAINING_PROJECT_FAILURE = "training_project_failure"
    SKYWRIGHT_FAILURE = "skywright_failure"


@dataclass(frozen=True)
class Accelerator:
    """Explicit accelerator selected by the Environment Profile."""

    kind: AcceleratorKind
    index: int = 0

    @property
    def device(self) -> str:
        if self.kind == "cpu":
            return "cpu"
        return f"cuda:{self.index}"


CPU_ACCELERATOR = Accelerator("cpu")


@dataclass(frozen=True)
class MetricDefinition:
    """Declared semantics for one project-owned Step metric."""

    name: str
    numeric_kind: NumericKind
    unit: str
    comparison: Comparison
    recording_basis: RecordingBasis = "step"
    step_reduction: StepReduction | None = "mean"
    minimum: int | float | None = None
    maximum: int | float | None = None
    display_name: str | None = None
    description: str | None = None


@dataclass(frozen=True)
class MetricObservation:
    """One reduced observation committed at a Step."""

    name: str
    step: int
    value: int | float


@dataclass(frozen=True)
class MetricCatalog:
    """Pinned composition of project and Skywright metric contracts."""

    project_identity: str
    project_contract_digest: str
    skywright_schema_identity: str
    skywright_schema_digest: str
    units: frozenset[str]
    project_definitions: tuple[MetricDefinition, ...]
    system_definitions: tuple[MetricDefinition, ...] = ()
    project_version: str | None = None

    @property
    def definitions(self) -> tuple[MetricDefinition, ...]:
        return self.project_definitions + self.system_definitions


@dataclass(frozen=True)
class DatasetCursor:
    """Location of the next uncommitted Dataset Item."""

    epoch: int = 0
    item_offset: int = 0
    epoch_step: int = 0
    ordering_fingerprint: str = ""


@dataclass(frozen=True)
class DatasetBatch:
    """Project-facing payload and the cursor committed with that work."""

    items: tuple[object, ...]
    next_cursor: DatasetCursor
    epoch: int = 0

    def partition_items(self, accelerator_count: int) -> tuple[tuple[object, ...], ...]:
        """Split one global batch into contiguous single-node accelerator slices.

        Concatenating slices in accelerator-index order reconstructs the logical
        sequence. Empty slices are allowed; no item is padded or dropped. The
        Training Process commits this global batch only after every slice finishes.
        """
        if type(accelerator_count) is not int or accelerator_count < 1:
            raise ValueError("Accelerator count must be a positive integer")
        quotient, remainder = divmod(len(self.items), accelerator_count)
        return tuple(
            self.items[
                rank * quotient + min(rank, remainder) : (rank + 1) * quotient
                + min(rank + 1, remainder)
            ]
            for rank in range(accelerator_count)
        )


@dataclass(frozen=True)
class ArtifactRecord:
    """Opaque project output accepted for later Run Store persistence."""

    name: str
    data: bytes
    step: int


@dataclass(frozen=True)
class SampleRecord:
    """Typed media output accepted for later Run Store persistence."""

    name: str
    data: bytes
    media_type: str
    step: int


@dataclass(frozen=True, init=False)
class CheckpointSnapshot:
    """Snapshot isolated from mutable state awaiting Run Store publication."""

    step: int
    _state: dict[str, object]
    _runtime_state: dict[str, object]
    dataset_cursor: DatasetCursor
    reference: str | None
    run_id: str
    project_version: str

    def __init__(
        self,
        step: int,
        state: Mapping[str, object],
        runtime_state: Mapping[str, object] | None = None,
        dataset_cursor: DatasetCursor | None = None,
        reference: str | None = None,
        run_id: str = "",
        project_version: str = "",
    ) -> None:
        object.__setattr__(self, "step", step)
        object.__setattr__(self, "_state", copy.deepcopy(dict(state)))
        object.__setattr__(
            self,
            "_runtime_state",
            copy.deepcopy(dict(runtime_state)) if runtime_state is not None else {},
        )
        object.__setattr__(
            self,
            "dataset_cursor",
            dataset_cursor if dataset_cursor is not None else DatasetCursor(),
        )
        object.__setattr__(self, "reference", reference)
        object.__setattr__(self, "run_id", run_id)
        object.__setattr__(self, "project_version", project_version)

    @property
    def state(self) -> Mapping[str, object]:
        """Return a defensive copy of project-owned checkpoint state."""
        return copy.deepcopy(self._state)

    @property
    def runtime_state(self) -> Mapping[str, object]:
        """Return a defensive copy of library-owned checkpoint state."""
        return copy.deepcopy(self._runtime_state)

    def with_reference(self, reference: str) -> CheckpointSnapshot:
        """Return the published form without exposing stored mutable state."""
        published = copy.copy(self)
        object.__setattr__(published, "reference", reference)
        return published


def checkpoint_payload(
    snapshot: CheckpointSnapshot,
) -> tuple[Mapping[str, object], Mapping[str, object]]:
    """Borrow owned state for trusted internal readers; never mutate or expose it."""
    return snapshot._state, snapshot._runtime_state  # pyright: ignore[reportPrivateUsage]


@dataclass(frozen=True)
class CheckpointConfirmation:
    """Published Durable Safe Point identity without its tensor payload."""

    step: int
    reference: str


@dataclass(frozen=True)
class ResumeState:
    """State restored before a Training Project starts its loop."""

    checkpoint: CheckpointSnapshot | None

    @property
    def resumed(self) -> bool:
        return self.checkpoint is not None


@dataclass(frozen=True)
class CheckpointRejectionEvidence:
    """One newer corrupt recovery candidate rejected before an attempt starts."""

    step: int
    reference: str
    code: str
    summary: str


@dataclass(frozen=True)
class ExecutionAttemptRecord:
    """Identity and resume evidence created before project code executes."""

    attempt_id: str
    run_id: str
    project_version: str
    seed_checkpoint_step: int | None
    seed_checkpoint_reference: str | None = None
    rejected_corrupt_checkpoints: tuple[CheckpointRejectionEvidence, ...] = ()


@dataclass(frozen=True)
class ExecutionTerminationReport:
    """Detailed evidence retained separately from orchestration control."""

    schema_version: int
    attempt_id: str
    run_id: str
    project_version: str
    cause: ExecutionTerminationCause
    last_committed_step: int
    latest_durable_step: int | None
    latest_durable_checkpoint: str | None
    diagnostics: Mapping[str, object]


@dataclass(frozen=True)
class TrainingProcessResult:
    """Observable result of executing one Training Project process."""

    outcome: TrainingProcessOutcome
    attempt: ExecutionAttemptRecord
    report: ExecutionTerminationReport
    final_checkpoint: CheckpointConfirmation | None
    metric_observations: tuple[MetricObservation, ...]
    artifacts: tuple[ArtifactRecord, ...]
    samples: tuple[SampleRecord, ...]
