"""Ports exposed by and required by the Training Process Boundary."""

from collections.abc import Callable, Iterable, Mapping
from typing import Protocol, TypeAlias

from skywright._training_types import (
    Accelerator,
    ArtifactRecord,
    CheckpointSnapshot,
    DatasetBatch,
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationReport,
    MetricCatalog,
    MetricObservation,
    ResumeState,
    SampleRecord,
)


class CheckpointState(Protocol):
    """Project-owned state that Skywright can snapshot and restore."""

    def state_dict(self) -> Mapping[str, object]: ...

    def load_state_dict(self, state: Mapping[str, object]) -> None: ...


class ScalarValue(Protocol):
    """NumPy/PyTorch-compatible scalar observation."""

    def item(self) -> object: ...


class DatasetAccess(Protocol):
    """Backend-neutral access to the exact Dataset Item Sequence."""

    @property
    def ordering_fingerprint(self) -> str: ...

    def batches(self, cursor: DatasetCursor) -> Iterable[DatasetBatch]: ...


class TrainingProcessRecorder(Protocol):
    """Durability seam owned by the Training Process Boundary."""

    def publish_attempt(self, attempt: ExecutionAttemptRecord) -> None: ...

    def publish_checkpoint(self, checkpoint: CheckpointSnapshot) -> str: ...

    def confirm_checkpoint(self, step: int, reference: str) -> None: ...

    def cancel_checkpoint_publication(self) -> None: ...

    def resume_after_checkpoint_cancellation(self) -> None: ...

    def publish_step(
        self,
        step: int,
        dataset_cursor: DatasetCursor,
        observations: tuple[MetricObservation, ...],
        latest_durable_step: int | None,
        latest_durable_checkpoint: str | None,
    ) -> None: ...

    def publish_artifact(self, artifact: ArtifactRecord) -> None: ...

    def publish_sample(self, sample: SampleRecord) -> None: ...

    def publish_report(self, report: ExecutionTerminationReport) -> None: ...


class MetricContractResolver(Protocol):
    """Loads pinned contracts and composes their immutable Metric Catalog."""

    def compose(
        self, project_version: str, skywright_schema_identity: str
    ) -> MetricCatalog: ...


class RunContext(Protocol):
    """The sole project-facing interface for one training process."""

    @property
    def configuration(self) -> Mapping[str, object]: ...

    @property
    def dataset(self) -> DatasetAccess: ...

    @property
    def dataset_cursor(self) -> DatasetCursor: ...

    @property
    def accelerator(self) -> Accelerator: ...

    @property
    def metric_catalog(self) -> MetricCatalog: ...

    @property
    def step(self) -> int: ...

    @property
    def resume_state(self) -> ResumeState: ...

    @property
    def cancellation_requested(self) -> bool: ...

    @property
    def interruption_requested(self) -> bool: ...

    def register_checkpoint_state(self, name: str, state: CheckpointState) -> None: ...

    def start(self) -> ResumeState: ...

    def observe(self, name: str, value: int | float | ScalarValue) -> None: ...

    def commit_step(self, final_batch: DatasetBatch) -> None: ...

    def persist_artifact(self, name: str, data: bytes) -> None: ...

    def persist_sample(self, name: str, data: bytes, *, media_type: str) -> None: ...


TrainingProject: TypeAlias = Callable[[RunContext], None]
