"""Training Project interface and Training Process Boundary implementation."""

from __future__ import annotations

import copy
import importlib
import importlib.util
import math
import os
import random
import re
import signal
import threading
import uuid
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field, replace
from enum import Enum
from types import MappingProxyType
from typing import Literal, NoReturn, Protocol, TypeAlias, cast

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


_CPU_ACCELERATOR = Accelerator("cpu")


def _empty_runtime_state() -> Mapping[str, object]:
    return {}


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


@dataclass(frozen=True)
class CheckpointSnapshot:
    """Immutable snapshot awaiting publication by Run Store persistence."""

    step: int
    state: Mapping[str, object]
    runtime_state: Mapping[str, object] = field(default_factory=_empty_runtime_state)
    dataset_cursor: DatasetCursor = DatasetCursor()
    reference: str | None = None
    run_id: str = ""
    project_version: str = ""


@dataclass(frozen=True)
class ResumeState:
    """State restored before a Training Project starts its loop."""

    checkpoint: CheckpointSnapshot | None

    @property
    def resumed(self) -> bool:
        return self.checkpoint is not None


@dataclass(frozen=True)
class ExecutionAttemptRecord:
    """Identity and resume evidence created before project code executes."""

    attempt_id: str
    run_id: str
    project_version: str
    seed_checkpoint_step: int | None


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
    final_checkpoint: CheckpointSnapshot | None
    metric_observations: tuple[MetricObservation, ...]
    artifacts: tuple[ArtifactRecord, ...]
    samples: tuple[SampleRecord, ...]


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


class _CooperativeStop(BaseException):
    def __init__(self, cause: ExecutionTerminationCause) -> None:
        super().__init__(cause.value)
        self.cause = cause


class _SkywrightFailure(BaseException):
    def __init__(self, failure: Exception, stage: FailureStage) -> None:
        super().__init__(str(failure))
        self.failure = failure
        self.stage: FailureStage = stage


class _SignalRequests:
    def __init__(self, shutdown_grace_seconds: float) -> None:
        self.interruption_requested = False
        self._shutdown_grace_seconds = shutdown_grace_seconds
        self._forced_exit: threading.Timer | None = None

    def install(self) -> None:
        signal.signal(signal.SIGINT, self._handle)
        signal.signal(signal.SIGTERM, self._handle)

    def _handle(self, _signal_number: int, _frame: object) -> None:
        if self.interruption_requested:
            os._exit(1)
        self.interruption_requested = True
        self._forced_exit = threading.Timer(
            self._shutdown_grace_seconds, os._exit, args=(1,)
        )
        self._forced_exit.daemon = True
        self._forced_exit.start()

    def finalize(self) -> None:
        if self._forced_exit is not None:
            self._forced_exit.cancel()


class TrainingContractViolation(RuntimeError):
    """A latched misuse of the Training Contract."""

    def __init__(self, rule: str, problem: str, guidance: str) -> None:
        super().__init__(f"{rule}: {problem}. {guidance}")
        self.rule = rule
        self.problem = problem
        self.guidance = guidance


class _TrackedDatasetAccess:
    def __init__(
        self,
        dataset: DatasetAccess,
        cursor: DatasetCursor,
        violate: Callable[[str, str, str], NoReturn],
    ) -> None:
        self._dataset = dataset
        self._cursor = cursor
        self._violate = violate
        self._issued: dict[int, DatasetBatch] = {}
        self._latest_issued: DatasetBatch | None = None
        self._commit_count = 0

    @property
    def ordering_fingerprint(self) -> str:
        return self._dataset.ordering_fingerprint

    def batches(self, cursor: DatasetCursor) -> Iterable[DatasetBatch]:
        if cursor != self._cursor:
            self._violate(
                "dataset-cursor/stale",
                f"Dataset access received {cursor!r} instead of current cursor {self._cursor!r}",
                "request batches using context.dataset_cursor",
            )
        previous = cursor
        step_epoch: int | None = None
        observed_commit_count = self._commit_count
        try:
            for batch in self._dataset.batches(cursor):
                if self._commit_count != observed_commit_count:
                    step_epoch = None
                    observed_commit_count = self._commit_count
                _validate_cursor_advance(previous, batch.next_cursor, self._violate)
                if type(batch.epoch) is not int or batch.epoch < 0:
                    self._violate(
                        "dataset-batch/epoch",
                        f"Dataset batch has invalid item epoch {batch.epoch!r}",
                        "issue batches with the global epoch containing their items",
                    )
                if batch.next_cursor.epoch not in (batch.epoch, batch.epoch + 1):
                    self._violate(
                        "dataset-cursor/epoch-transition",
                        "the Dataset batch cursor does not remain in or finish its item epoch",
                        "advance to the same epoch or the immediately following epoch",
                    )
                if batch.next_cursor.ordering_fingerprint != self.ordering_fingerprint:
                    self._violate(
                        "dataset-cursor/fingerprint",
                        "the Dataset batch cursor has a different ordering fingerprint",
                        "use batches issued by the configured Dataset ordering adapter",
                    )
                if step_epoch is None:
                    step_epoch = batch.epoch
                elif batch.epoch != step_epoch:
                    self._violate(
                        "dataset-cursor/epoch-boundary",
                        "the pending Step spans a Dataset epoch boundary",
                        "commit the final batch from one epoch before requesting the next epoch",
                    )
                self._issued[id(batch)] = batch
                self._latest_issued = batch
                previous = batch.next_cursor
                yield batch
        except (TrainingContractViolation, _SkywrightFailure):
            raise
        except Exception as failure:
            raise _SkywrightFailure(failure, "project") from failure

    def consume(self, batch: DatasetBatch) -> DatasetCursor:
        issued = self._issued.get(id(batch))
        if issued is not batch or self._latest_issued is not batch:
            self._violate(
                "dataset-cursor/not-issued",
                "commit_step() received a batch that was not the latest issued by this Run Context",
                "commit the final Dataset batch whose work completed in this Step",
            )
        self._issued.clear()
        self._latest_issued = None
        self._commit_count += 1
        self._cursor = batch.next_cursor
        return self._cursor


class _DefaultRunContext:
    def __init__(
        self,
        *,
        configuration: Mapping[str, object],
        dataset: DatasetAccess,
        metric_contracts: MetricContractResolver,
        skywright_metric_schema: str,
        recorder: TrainingProcessRecorder,
        resume_from: CheckpointSnapshot | None,
        accelerator: Accelerator,
        cancellation_requested: Callable[[], bool],
        interruption_requested: Callable[[], bool],
        run_id: str,
        project_version: str,
    ) -> None:
        frozen_configuration = _freeze(copy.deepcopy(dict(configuration)))
        self._configuration = cast(Mapping[str, object], frozen_configuration)
        self._recorder = recorder
        self._accelerator = accelerator
        self._cancellation_requested = cancellation_requested
        self._interruption_requested = interruption_requested
        self._run_id = run_id
        self._project_version = project_version
        try:
            metric_catalog = metric_contracts.compose(
                project_version, skywright_metric_schema
            )
        except Exception as failure:
            raise _SkywrightFailure(failure, "construction") from failure
        self._definitions = _validate_metric_catalog(
            metric_catalog, project_version, skywright_metric_schema
        )
        self._resume_state = ResumeState(resume_from)
        if resume_from is not None and not resume_from.reference:
            raise TrainingContractViolation(
                "resume/checkpoint-reference",
                "the resume checkpoint has no durable reference",
                "resume only from a checkpoint confirmed by the Run Store publisher",
            )
        self._states: dict[str, CheckpointState] = {}
        self._pending: dict[str, list[int | float]] = {}
        self._observations: list[MetricObservation] = []
        self._artifacts: list[ArtifactRecord] = []
        self._samples: list[SampleRecord] = []
        self._step = resume_from.step if resume_from is not None else 0
        fingerprint = dataset.ordering_fingerprint
        if not fingerprint:
            raise TrainingContractViolation(
                "dataset-ordering/fingerprint",
                "the Dataset ordering fingerprint is empty",
                "identify the Dataset Definition, seed, ordering policy, and policy version",
            )
        self._dataset_cursor = (
            resume_from.dataset_cursor
            if resume_from is not None
            else DatasetCursor(ordering_fingerprint=fingerprint)
        )
        if self._dataset_cursor.ordering_fingerprint != fingerprint:
            raise TrainingContractViolation(
                "dataset-ordering/fingerprint",
                "the checkpoint and configured Dataset ordering fingerprints differ",
                "resume with identical ordering inputs or an explicit Ordering Reset",
            )
        self._dataset = _TrackedDatasetAccess(
            dataset, self._dataset_cursor, self._violate
        )
        self._latest_durable_step = (
            resume_from.step if resume_from is not None else None
        )
        self._latest_durable_checkpoint = (
            resume_from.reference if resume_from is not None else None
        )
        self._started = False
        self._violated: TrainingContractViolation | None = None

    @property
    def configuration(self) -> Mapping[str, object]:
        return self._configuration

    @property
    def dataset(self) -> DatasetAccess:
        return self._dataset

    @property
    def dataset_cursor(self) -> DatasetCursor:
        return self._dataset_cursor

    @property
    def accelerator(self) -> Accelerator:
        return self._accelerator

    @property
    def step(self) -> int:
        return self._step

    @property
    def resume_state(self) -> ResumeState:
        return self._resume_state

    @property
    def cancellation_requested(self) -> bool:
        try:
            return self._cancellation_requested()
        except Exception as failure:
            raise _SkywrightFailure(failure, "project") from failure

    @property
    def interruption_requested(self) -> bool:
        if self.cancellation_requested:
            return False
        try:
            return self._interruption_requested()
        except Exception as failure:
            raise _SkywrightFailure(failure, "project") from failure

    @property
    def observations(self) -> tuple[MetricObservation, ...]:
        return tuple(self._observations)

    @property
    def artifacts(self) -> tuple[ArtifactRecord, ...]:
        return tuple(self._artifacts)

    @property
    def samples(self) -> tuple[SampleRecord, ...]:
        return tuple(self._samples)

    def register_checkpoint_state(self, name: str, state: CheckpointState) -> None:
        self._require_registering("register Checkpoint State")
        if not name or name in self._states:
            self._violate(
                "checkpoint-state/name",
                f"Checkpoint State name {name!r} is empty or already registered",
                "register each state object once under a non-empty unique name",
            )
        if not callable(getattr(state, "state_dict", None)) or not callable(
            getattr(state, "load_state_dict", None)
        ):
            self._violate(
                "checkpoint-state/interface",
                f"Checkpoint State {name!r} is not resumable",
                "provide state_dict() and load_state_dict() methods",
            )
        self._states[name] = state

    def start(self) -> ResumeState:
        self._require_registering("start the Run Context")
        if not self._states:
            self._violate(
                "checkpoint-state/empty",
                "no project Checkpoint State was registered",
                "register all project-owned resumable state before start()",
            )
        checkpoint = self._resume_state.checkpoint
        if checkpoint is not None:
            if checkpoint.run_id and checkpoint.run_id != self._run_id:
                self._violate(
                    "resume/run-identity",
                    f"checkpoint Run {checkpoint.run_id!r} does not match {self._run_id!r}",
                    "resume an Execution Attempt from a checkpoint in the same Run",
                )
            if (
                checkpoint.project_version
                and checkpoint.project_version != self._project_version
            ):
                self._violate(
                    "resume/project-version",
                    f"checkpoint Training Project Version {checkpoint.project_version!r} does not match {self._project_version!r}",
                    "resume with the identical Training Project Version",
                )
            expected = set(checkpoint.state)
            actual = set(self._states)
            if expected != actual:
                self._violate(
                    "checkpoint-state/shape",
                    f"registered names {sorted(actual)!r} do not match checkpoint names {sorted(expected)!r}",
                    "resume with the same complete Checkpoint State shape",
                )
            for name, state in self._states.items():
                restored = checkpoint.state[name]
                if not isinstance(restored, Mapping):
                    self._violate(
                        "checkpoint-state/payload",
                        f"Checkpoint State {name!r} is not a mapping",
                        "publish state_dict() mappings without changing their shape",
                    )
                state.load_state_dict(
                    copy.deepcopy(cast(Mapping[str, object], restored))
                )
            try:
                _restore_runtime_state(checkpoint.runtime_state)
            except Exception as failure:
                raise _SkywrightFailure(failure, "project") from failure
        self._started = True
        return self._resume_state

    def observe(self, name: str, value: object) -> None:
        self._require_running(f"observe metric {name!r}")
        definition = self._definitions.get(name)
        if definition is None:
            self._violate(
                "metric/undeclared",
                f"metric {name!r} is not declared",
                "record only names from the version-pinned Metric Catalog",
            )
        numel = getattr(value, "numel", None)
        if callable(numel) and numel() != 1:
            self._violate(
                "metric/not-scalar",
                f"metric {name!r} received a value with {numel()} elements",
                "reduce it to one scalar where the project owns the semantics",
            )
        item = getattr(value, "item", None)
        if callable(item):
            value = item()
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            self._violate(
                "metric/not-scalar",
                f"metric {name!r} received {type(value).__name__}",
                "record a finite numeric scalar",
            )
        if not math.isfinite(value):
            self._violate(
                "metric/non-finite",
                f"metric {name!r} received {value!r}",
                "record a finite numeric scalar",
            )
        if definition.numeric_kind == "integer" and not isinstance(value, int):
            self._violate(
                "metric/numeric-kind",
                f"integer metric {name!r} received {value!r}",
                "record an integer value",
            )
        if definition.numeric_kind == "integer" and abs(value) > 2**24:
            self._violate(
                "metric/inexact-integer",
                f"integer metric {name!r} received {value!r}",
                "record an integer exactly representable by TensorBoard scalar encoding",
            )
        self._pending.setdefault(name, []).append(value)

    def commit_step(self, final_batch: DatasetBatch) -> None:
        self._require_running("commit a Step")
        next_dataset_cursor = self._dataset.consume(final_batch)
        next_step = self._step + 1
        committed: list[MetricObservation] = []
        for name, values in self._pending.items():
            definition = self._definitions[name]
            reduction = definition.step_reduction
            if reduction is None:
                self._violate(
                    "metric/recording-basis",
                    f"wall-time metric {name!r} cannot be observed at a Step",
                    "record only project-owned Step metrics through observe()",
                )
            reduced = _reduce(values, reduction)
            if definition.minimum is not None and reduced < definition.minimum:
                self._violate(
                    "metric/bounds",
                    f"metric {name!r} reduced to {reduced!r} below {definition.minimum!r}",
                    "record observations whose reduced value satisfies the Metric Definition",
                )
            if definition.maximum is not None and reduced > definition.maximum:
                self._violate(
                    "metric/bounds",
                    f"metric {name!r} reduced to {reduced!r} above {definition.maximum!r}",
                    "record observations whose reduced value satisfies the Metric Definition",
                )
            committed.append(MetricObservation(name, next_step, reduced))
        try:
            self._recorder.publish_step(
                next_step,
                next_dataset_cursor,
                tuple(committed),
                self._latest_durable_step,
                self._latest_durable_checkpoint,
            )
        except Exception as failure:
            raise _SkywrightFailure(failure, "project") from failure
        self._observations.extend(committed)
        self._pending.clear()
        self._step = next_step
        self._dataset_cursor = next_dataset_cursor
        if self.cancellation_requested:
            raise _CooperativeStop(ExecutionTerminationCause.CANCELLED)
        if self.interruption_requested:
            raise _CooperativeStop(ExecutionTerminationCause.INTERRUPTED)

    def persist_artifact(self, name: str, data: object) -> None:
        self._require_running(f"persist Artifact {name!r}")
        validated = _validate_output(name, data, "Artifact", self._violate)
        artifact = ArtifactRecord(name, validated, self._step)
        try:
            self._recorder.publish_artifact(artifact)
        except Exception as failure:
            raise _SkywrightFailure(failure, "project") from failure
        self._artifacts.append(artifact)

    def persist_sample(self, name: str, data: object, *, media_type: str) -> None:
        self._require_running(f"persist Sample {name!r}")
        validated = _validate_output(name, data, "Sample", self._violate)
        if "/" not in media_type:
            self._violate(
                "sample/media-type",
                f"Sample {name!r} has invalid media type {media_type!r}",
                "supply a concrete media type such as image/png or audio/wav",
            )
        sample = SampleRecord(name, validated, media_type, self._step)
        try:
            self._recorder.publish_sample(sample)
        except Exception as failure:
            raise _SkywrightFailure(failure, "project") from failure
        self._samples.append(sample)

    def snapshot(self) -> CheckpointSnapshot:
        state = {
            name: copy.deepcopy(dict(checkpoint_state.state_dict()))
            for name, checkpoint_state in self._states.items()
        }
        return CheckpointSnapshot(
            step=self._step,
            state=MappingProxyType(state),
            runtime_state=MappingProxyType(_capture_runtime_state()),
            dataset_cursor=self._dataset_cursor,
            run_id=self._run_id,
            project_version=self._project_version,
        )

    def validate_completion(self) -> None:
        if self._violated is not None:
            raise self._violated
        if not self._started:
            self._violate(
                "run-context/not-started",
                "the Training Project returned before start()",
                "register Checkpoint State and call start() before running the loop",
            )
        if self._pending:
            self._violate(
                "metric/uncommitted",
                f"the Training Project returned with pending metrics {sorted(self._pending)!r}",
                "commit or discard the current Step before returning",
            )
        if self._step == 0:
            self._violate(
                "step/empty-run",
                "the Training Project returned without committing a Step",
                "commit at least one Step before successful completion",
            )

    def _require_registering(self, operation: str) -> None:
        if self._started:
            self._violate(
                "run-context/lifecycle",
                f"cannot {operation} after start()",
                "perform registration once before the Training Project loop",
            )

    def _require_running(self, operation: str) -> None:
        if not self._started:
            self._violate(
                "run-context/lifecycle",
                f"cannot {operation} before start()",
                "register Checkpoint State and call start() first",
            )

    def _violate(self, rule: str, problem: str, guidance: str) -> NoReturn:
        violation = TrainingContractViolation(rule, problem, guidance)
        if self._violated is None:
            self._violated = violation
        raise violation


_process_claim_lock = threading.Lock()
_process_claimed = False


def _never_requested() -> bool:
    return False


def run_training_process(
    entry_point: TrainingProject | str,
    *,
    run_id: str,
    project_version: str,
    configuration: Mapping[str, object],
    dataset: DatasetAccess | str,
    metric_contracts: MetricContractResolver | str,
    skywright_metric_schema: str,
    recorder: TrainingProcessRecorder | str,
    seed: int,
    resume_from: CheckpointSnapshot | str | None = None,
    accelerator: Accelerator = _CPU_ACCELERATOR,
    cancellation_requested: Callable[[], bool] = _never_requested,
    interruption_requested: Callable[[], bool] = _never_requested,
    shutdown_grace_seconds: float = 30.0,
) -> TrainingProcessResult:
    """Execute one Training Project through the process's sole Run Context."""

    try:
        _claim_process()
    except TrainingContractViolation as violation:
        attempt = ExecutionAttemptRecord(
            attempt_id=str(uuid.uuid4()),
            run_id=run_id,
            project_version=project_version,
            seed_checkpoint_step=None,
        )
        return _unpublished_failure(attempt, violation, "construction")
    attempt = ExecutionAttemptRecord(
        attempt_id=str(uuid.uuid4()),
        run_id=run_id,
        project_version=project_version,
        seed_checkpoint_step=(
            resume_from.step if isinstance(resume_from, CheckpointSnapshot) else None
        ),
    )
    if not run_id or not project_version:
        violation = TrainingContractViolation(
            "training-process/identity",
            "the Run identity or Training Project Version is empty",
            "provide stable non-empty run_id and project_version values",
        )
        return _unpublished_failure(attempt, violation, "construction")
    if shutdown_grace_seconds <= 0 or not math.isfinite(shutdown_grace_seconds):
        violation = TrainingContractViolation(
            "training-process/shutdown-grace",
            f"shutdown grace {shutdown_grace_seconds!r} is not positive and finite",
            "configure a positive finite shutdown grace in seconds",
        )
        return _unpublished_failure(attempt, violation, "construction")
    signal_requests = _SignalRequests(shutdown_grace_seconds)
    try:
        signal_requests.install()
    except Exception as failure:
        return _unpublished_failure(attempt, failure, "construction")
    try:
        _establish_determinism(seed)
    except Exception as failure:
        return _unpublished_failure(attempt, failure, "construction")

    def finish(result: TrainingProcessResult) -> TrainingProcessResult:
        signal_requests.finalize()
        return result

    def any_interruption_requested() -> bool:
        return signal_requests.interruption_requested or interruption_requested()

    try:
        resolved_recorder = cast(
            TrainingProcessRecorder, _resolve_component(recorder, "recorder")
        )
        resolved_resume = (
            cast(
                CheckpointSnapshot,
                _resolve_component(resume_from, "resume checkpoint"),
            )
            if resume_from is not None
            else None
        )
        attempt = replace(
            attempt,
            seed_checkpoint_step=(
                resolved_resume.step if resolved_resume is not None else None
            ),
        )
        resolved_recorder.publish_attempt(attempt)
    except Exception as failure:
        return finish(_unpublished_failure(attempt, failure, "construction"))
    try:
        resolved_dataset = cast(
            DatasetAccess, _resolve_component(dataset, "Dataset access")
        )
        resolved_metric_contracts = cast(
            MetricContractResolver,
            _resolve_component(metric_contracts, "metric contract resolver"),
        )
        context = _DefaultRunContext(
            configuration=configuration,
            dataset=resolved_dataset,
            metric_contracts=resolved_metric_contracts,
            skywright_metric_schema=skywright_metric_schema,
            recorder=resolved_recorder,
            resume_from=resolved_resume,
            accelerator=accelerator,
            cancellation_requested=cancellation_requested,
            interruption_requested=any_interruption_requested,
            run_id=run_id,
            project_version=project_version,
        )
    except TrainingContractViolation as violation:
        return finish(
            _failure_result(
                attempt,
                violation,
                None,
                resolved_resume,
                "construction",
                resolved_recorder,
                True,
            )
        )
    except _SkywrightFailure as failure:
        return finish(
            _failure_result(
                attempt,
                failure.failure,
                None,
                resolved_resume,
                failure.stage,
                resolved_recorder,
                True,
                skywright_failure=True,
            )
        )
    except Exception as failure:
        return finish(
            _failure_result(
                attempt,
                failure,
                None,
                resolved_resume,
                "construction",
                resolved_recorder,
                True,
            )
        )
    try:
        project = (
            _load_training_project(entry_point)
            if isinstance(entry_point, str)
            else entry_point
        )
        project(context)
        context.validate_completion()
    except _CooperativeStop as stop:
        return finish(
            _stopped_result(
                attempt, stop.cause, context, resolved_resume, resolved_recorder
            )
        )
    except _SkywrightFailure as failure:
        return finish(
            _failure_result(
                attempt,
                failure.failure,
                context,
                resolved_resume,
                failure.stage,
                resolved_recorder,
                True,
                skywright_failure=True,
            )
        )
    except TrainingContractViolation as violation:
        return finish(
            _failure_result(
                attempt,
                violation,
                context,
                resolved_resume,
                "project",
                resolved_recorder,
                True,
            )
        )
    except Exception as failure:
        return finish(
            _failure_result(
                attempt,
                failure,
                context,
                resolved_resume,
                "project",
                resolved_recorder,
                True,
            )
        )
    return finish(
        _durable_result(
            attempt,
            TrainingProcessOutcome.COMPLETED,
            ExecutionTerminationCause.COMPLETED,
            context,
            resolved_resume,
            resolved_recorder,
        )
    )


def _stopped_result(
    attempt: ExecutionAttemptRecord,
    cause: ExecutionTerminationCause,
    context: _DefaultRunContext,
    resume_from: CheckpointSnapshot | None,
    recorder: TrainingProcessRecorder,
) -> TrainingProcessResult:
    if cause is ExecutionTerminationCause.INTERRUPTED:
        return _durable_result(
            attempt,
            TrainingProcessOutcome.INTERRUPTED,
            cause,
            context,
            resume_from,
            recorder,
        )
    result = _result(
        attempt=attempt,
        outcome=TrainingProcessOutcome.CANCELLED,
        cause=cause,
        last_committed_step=context.step,
        latest_durable_step=resume_from.step if resume_from is not None else None,
        latest_durable_checkpoint=(
            resume_from.reference if resume_from is not None else None
        ),
        final_checkpoint=None,
        context=context,
        diagnostics={},
    )
    return _publish_report(result, recorder)


def _durable_result(
    attempt: ExecutionAttemptRecord,
    outcome: TrainingProcessOutcome,
    cause: ExecutionTerminationCause,
    context: _DefaultRunContext,
    resume_from: CheckpointSnapshot | None,
    recorder: TrainingProcessRecorder,
) -> TrainingProcessResult:
    try:
        checkpoint = context.snapshot()
        reference = recorder.publish_checkpoint(checkpoint)
        if not reference:
            raise ValueError("checkpoint publisher returned an empty reference")
        checkpoint = replace(checkpoint, reference=reference)
    except Exception as failure:
        return _failure_result(
            attempt, failure, context, resume_from, "finalization", recorder, True
        )
    result = _result(
        attempt=attempt,
        outcome=outcome,
        cause=cause,
        last_committed_step=context.step,
        latest_durable_step=checkpoint.step,
        latest_durable_checkpoint=reference,
        final_checkpoint=checkpoint,
        context=context,
        diagnostics={},
    )
    return _publish_report(result, recorder)


def _failure_result(
    attempt: ExecutionAttemptRecord,
    failure: Exception,
    context: _DefaultRunContext | None,
    resume_from: CheckpointSnapshot | None,
    stage: FailureStage,
    recorder: TrainingProcessRecorder,
    attempt_published: bool,
    *,
    skywright_failure: bool = False,
) -> TrainingProcessResult:
    if isinstance(failure, TrainingContractViolation) and not skywright_failure:
        cause = ExecutionTerminationCause.CONTRACT_VIOLATION
        diagnostics: dict[str, object] = {
            "rule": failure.rule,
            "problem": failure.problem,
            "guidance": failure.guidance,
            "stage": stage,
        }
    elif stage == "project" and not skywright_failure:
        cause = ExecutionTerminationCause.TRAINING_PROJECT_FAILURE
        diagnostics = {
            "exception_type": type(failure).__name__,
            "message": str(failure),
            "stage": stage,
        }
    else:
        cause = ExecutionTerminationCause.SKYWRIGHT_FAILURE
        diagnostics = {
            "exception_type": type(failure).__name__,
            "message": str(failure),
            "stage": stage,
        }
    last_step = context.step if context is not None else 0
    durable_step = resume_from.step if resume_from is not None else None
    result = _result(
        attempt=attempt,
        outcome=TrainingProcessOutcome.FAILED,
        cause=cause,
        last_committed_step=last_step,
        latest_durable_step=durable_step,
        latest_durable_checkpoint=(
            resume_from.reference if resume_from is not None else None
        ),
        final_checkpoint=None,
        context=context,
        diagnostics=diagnostics,
    )
    return _publish_report(result, recorder) if attempt_published else result


def _unpublished_failure(
    attempt: ExecutionAttemptRecord, failure: Exception, stage: FailureStage
) -> TrainingProcessResult:
    if isinstance(failure, TrainingContractViolation):
        cause = ExecutionTerminationCause.CONTRACT_VIOLATION
        diagnostics: dict[str, object] = {
            "rule": failure.rule,
            "problem": failure.problem,
            "guidance": failure.guidance,
            "stage": stage,
        }
    else:
        cause = ExecutionTerminationCause.SKYWRIGHT_FAILURE
        diagnostics = {
            "exception_type": type(failure).__name__,
            "message": str(failure),
            "stage": stage,
        }
    return _result(
        attempt=attempt,
        outcome=TrainingProcessOutcome.FAILED,
        cause=cause,
        last_committed_step=0,
        latest_durable_step=None,
        latest_durable_checkpoint=None,
        final_checkpoint=None,
        context=None,
        diagnostics=diagnostics,
    )


def _result(
    *,
    attempt: ExecutionAttemptRecord,
    outcome: TrainingProcessOutcome,
    cause: ExecutionTerminationCause,
    last_committed_step: int,
    latest_durable_step: int | None,
    latest_durable_checkpoint: str | None,
    final_checkpoint: CheckpointSnapshot | None,
    context: _DefaultRunContext | None,
    diagnostics: Mapping[str, object],
) -> TrainingProcessResult:
    report = ExecutionTerminationReport(
        schema_version=1,
        attempt_id=attempt.attempt_id,
        run_id=attempt.run_id,
        project_version=attempt.project_version,
        cause=cause,
        last_committed_step=last_committed_step,
        latest_durable_step=latest_durable_step,
        latest_durable_checkpoint=latest_durable_checkpoint,
        diagnostics=MappingProxyType(dict(diagnostics)),
    )
    return TrainingProcessResult(
        outcome=outcome,
        attempt=attempt,
        report=report,
        final_checkpoint=final_checkpoint,
        metric_observations=context.observations if context is not None else (),
        artifacts=context.artifacts if context is not None else (),
        samples=context.samples if context is not None else (),
    )


def _publish_report(
    result: TrainingProcessResult, recorder: TrainingProcessRecorder
) -> TrainingProcessResult:
    try:
        recorder.publish_report(result.report)
        return result
    except Exception as failure:
        diagnostics = dict(result.report.diagnostics)
        diagnostics["report_publication_failure"] = {
            "exception_type": type(failure).__name__,
            "message": str(failure),
        }
        preserves_cause = result.report.cause in (
            ExecutionTerminationCause.CONTRACT_VIOLATION,
            ExecutionTerminationCause.TRAINING_PROJECT_FAILURE,
            ExecutionTerminationCause.SKYWRIGHT_FAILURE,
        )
        return replace(
            result,
            outcome=TrainingProcessOutcome.FAILED,
            report=replace(
                result.report,
                cause=(
                    result.report.cause
                    if preserves_cause
                    else ExecutionTerminationCause.SKYWRIGHT_FAILURE
                ),
                diagnostics=MappingProxyType(diagnostics),
            ),
        )


def _load_training_project(reference: str) -> TrainingProject:
    module_name, separator, attribute_name = reference.partition(":")
    if not separator or not module_name or not attribute_name:
        raise ValueError("entry point must use MODULE:CALLABLE form")
    project = getattr(importlib.import_module(module_name), attribute_name)
    if not callable(project):
        raise TypeError(f"entry point {reference!r} is not callable")
    return cast(TrainingProject, project)


def _resolve_component(component: object, kind: str) -> object:
    if not isinstance(component, str):
        return component
    module_name, separator, attribute_name = component.partition(":")
    if not separator or not module_name or not attribute_name:
        raise ValueError(f"{kind} factory must use MODULE:CALLABLE form")
    factory = getattr(importlib.import_module(module_name), attribute_name)
    if not callable(factory):
        raise TypeError(f"{kind} factory {component!r} is not callable")
    return factory()


def _claim_process() -> None:
    global _process_claimed
    with _process_claim_lock:
        if _process_claimed:
            raise TrainingContractViolation(
                "run-context/one-per-process",
                "this process already attempted to construct a Run Context",
                "start every direct or managed invocation in a fresh process",
            )
        _process_claimed = True


def _establish_determinism(seed: int) -> None:
    random.seed(seed)
    if importlib.util.find_spec("numpy") is not None:
        numpy = importlib.import_module("numpy")
        numpy.random.seed(seed % (2**32))
    if importlib.util.find_spec("torch") is not None:
        torch = importlib.import_module("torch")
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)
        torch.use_deterministic_algorithms(True)
        if hasattr(torch.backends, "cudnn"):
            torch.backends.cudnn.benchmark = False
            torch.backends.cudnn.deterministic = True


def _capture_runtime_state() -> dict[str, object]:
    state: dict[str, object] = {"python_random": random.getstate()}
    if importlib.util.find_spec("numpy") is not None:
        numpy = importlib.import_module("numpy")
        state["numpy_random"] = numpy.random.get_state()
    if importlib.util.find_spec("torch") is not None:
        torch = importlib.import_module("torch")
        state["torch_cpu_random"] = torch.get_rng_state()
        if torch.cuda.is_available():
            state["torch_accelerator_random"] = torch.cuda.get_rng_state_all()
    return state


def _restore_runtime_state(state: Mapping[str, object]) -> None:
    python_state = state.get("python_random")
    if python_state is not None:
        random.setstate(cast(tuple[object, ...], python_state))
    if "numpy_random" in state and importlib.util.find_spec("numpy") is not None:
        numpy = importlib.import_module("numpy")
        numpy.random.set_state(state["numpy_random"])
    if "torch_cpu_random" in state and importlib.util.find_spec("torch") is not None:
        torch = importlib.import_module("torch")
        torch.set_rng_state(state["torch_cpu_random"])
        if "torch_accelerator_random" in state and torch.cuda.is_available():
            torch.cuda.set_rng_state_all(state["torch_accelerator_random"])


def _reduce(values: list[int | float], reduction: StepReduction) -> int | float:
    if reduction == "last":
        return values[-1]
    if reduction == "sum":
        return sum(values)
    if reduction == "min":
        return min(values)
    if reduction == "max":
        return max(values)
    return sum(values) / len(values)


def _validate_metric_catalog(
    catalog: MetricCatalog,
    project_version: str,
    skywright_schema_identity: str,
) -> dict[str, MetricDefinition]:
    if not (catalog.project_identity and catalog.project_contract_digest):
        raise TrainingContractViolation(
            "metric-catalog/project-identity",
            "the Metric Catalog is missing its project identity or contract digest",
            "load the contract pinned by the exact Training Project Version",
        )
    if not (
        catalog.skywright_schema_identity
        and catalog.skywright_schema_digest
        and catalog.units
    ):
        _raise_catalog_failure(
            "metric-catalog/schema-identity",
            "the composed catalog is missing its Skywright schema identity, digest, or unit registry",
            "load the complete pinned Skywright Metric Schema",
        )
    if catalog.project_identity != project_version:
        raise TrainingContractViolation(
            "metric-catalog/project-identity",
            f"Metric Catalog project {catalog.project_identity!r} does not match {project_version!r}",
            "use the catalog composed for the exact Training Project Version",
        )
    if catalog.skywright_schema_identity != skywright_schema_identity:
        _raise_catalog_failure(
            "metric-catalog/schema-identity",
            f"Metric Catalog schema {catalog.skywright_schema_identity!r} does not match {skywright_schema_identity!r}",
            "compose with the exact Skywright Metric Schema pinned by the Run Definition",
        )
    project = _validate_metric_definitions(catalog.project_definitions, system=False)
    try:
        system = _validate_metric_definitions(catalog.system_definitions, system=True)
    except TrainingContractViolation as failure:
        raise _SkywrightFailure(failure, "construction") from failure
    overlap = set(project).intersection(system)
    if overlap:
        _raise_catalog_failure(
            "metric-catalog/duplicate",
            f"Metric Catalog contains duplicate names {sorted(overlap)!r}",
            "compose exactly one Metric Definition for each canonical name",
        )
    unknown_project_units = {
        definition.unit
        for definition in catalog.project_definitions
        if definition.unit not in catalog.units
    }
    if unknown_project_units:
        raise TrainingContractViolation(
            "metric-catalog/unit-registry",
            f"Project Metric Contract uses units outside the pinned registry {sorted(unknown_project_units)!r}",
            "declare only units from the versioned Skywright Metric Schema",
        )
    unknown_system_units = {
        definition.unit
        for definition in catalog.system_definitions
        if definition.unit not in catalog.units
    }
    if unknown_system_units:
        failure = TrainingContractViolation(
            "metric-catalog/system-unit-registry",
            f"Skywright Metric Schema uses unknown units {sorted(unknown_system_units)!r}",
            "publish a valid internally consistent Skywright Metric Schema",
        )
        raise _SkywrightFailure(failure, "construction")
    return project


def _raise_catalog_failure(rule: str, problem: str, guidance: str) -> NoReturn:
    failure = TrainingContractViolation(rule, problem, guidance)
    raise _SkywrightFailure(failure, "construction")


def _validate_metric_definitions(
    definitions: Iterable[MetricDefinition], *, system: bool
) -> dict[str, MetricDefinition]:
    catalog: dict[str, MetricDefinition] = {}
    for definition in definitions:
        if not system and definition.name.startswith("skywright/"):
            raise TrainingContractViolation(
                "metric-definition/reserved-name",
                f"project metric {definition.name!r} uses the skywright/ namespace",
                "declare project metrics outside the library-owned namespace",
            )
        if system and not definition.name.startswith("skywright/system/"):
            raise TrainingContractViolation(
                "metric-definition/system-name",
                f"system metric {definition.name!r} is outside the skywright/ namespace",
                "declare library-owned metrics under skywright/system/",
            )
        if not system and definition.recording_basis != "step":
            raise TrainingContractViolation(
                "metric-definition/project-basis",
                f"project metric {definition.name!r} is not Step-based",
                "declare project metrics with the step Recording Basis",
            )
        if re.fullmatch(r"[a-z][a-z0-9_]*(/[a-z][a-z0-9_]*)+", definition.name) is None:
            raise TrainingContractViolation(
                "metric-definition/name",
                f"project metric name {definition.name!r} is not lowercase slash-separated",
                "use a canonical name such as train/loss",
            )
        if definition.name in catalog:
            raise TrainingContractViolation(
                "metric-definition/duplicate",
                f"project metric {definition.name!r} is declared more than once",
                "publish exactly one definition for each canonical name",
            )
        if definition.numeric_kind not in ("real", "integer"):
            raise TrainingContractViolation(
                "metric-definition/numeric-kind",
                f"metric {definition.name!r} has unknown numeric kind {definition.numeric_kind!r}",
                "use real or integer",
            )
        if definition.comparison not in ("minimize", "maximize", "none"):
            raise TrainingContractViolation(
                "metric-definition/comparison",
                f"metric {definition.name!r} has unknown comparison {definition.comparison!r}",
                "use minimize, maximize, or none",
            )
        if definition.recording_basis not in ("step", "wall_time"):
            raise TrainingContractViolation(
                "metric-definition/recording-basis",
                f"metric {definition.name!r} has unknown Recording Basis {definition.recording_basis!r}",
                "use step or wall_time",
            )
        if (
            definition.recording_basis == "wall_time"
            and definition.step_reduction is not None
        ):
            raise TrainingContractViolation(
                "metric-definition/wall-time-reduction",
                f"wall-time metric {definition.name!r} declares a Step Reduction",
                "omit step_reduction for wall-time metrics",
            )
        if definition.recording_basis == "step" and definition.step_reduction not in (
            "mean",
            "sum",
            "min",
            "max",
            "last",
        ):
            raise TrainingContractViolation(
                "metric-definition/reduction",
                f"metric {definition.name!r} has unknown Step Reduction {definition.step_reduction!r}",
                "use mean, sum, min, max, or last",
            )
        if (
            definition.recording_basis == "step"
            and definition.numeric_kind == "integer"
            and definition.step_reduction == "mean"
        ):
            raise TrainingContractViolation(
                "metric-definition/integer-mean",
                f"integer metric {definition.name!r} uses mean reduction",
                "declare a real metric or choose a reduction that preserves integers",
            )
        if not definition.unit:
            raise TrainingContractViolation(
                "metric-definition/unit",
                f"metric {definition.name!r} has no Metric Unit",
                "use a unit from the pinned Skywright Metric Schema",
            )
        for bound_name, bound in (
            ("minimum", definition.minimum),
            ("maximum", definition.maximum),
        ):
            if bound is not None and (
                isinstance(bound, bool) or not math.isfinite(bound)
            ):
                raise TrainingContractViolation(
                    "metric-definition/bounds",
                    f"metric {definition.name!r} has invalid {bound_name} {bound!r}",
                    "declare finite numeric bounds",
                )
        if (
            definition.minimum is not None
            and definition.maximum is not None
            and definition.minimum > definition.maximum
        ):
            raise TrainingContractViolation(
                "metric-definition/bounds",
                f"metric {definition.name!r} has minimum above maximum",
                "declare an ordered bounds interval",
            )
        catalog[definition.name] = definition
    return catalog


def _validate_cursor_advance(
    current: DatasetCursor,
    next_cursor: DatasetCursor,
    violate: Callable[[str, str, str], NoReturn],
) -> None:
    values = (
        next_cursor.epoch,
        next_cursor.item_offset,
        next_cursor.epoch_step,
    )
    if any(type(value) is not int or value < 0 for value in values):
        violate(
            "dataset-cursor/shape",
            f"next Dataset Cursor {next_cursor!r} contains an invalid position",
            "commit a cursor containing non-negative integer positions",
        )
    if next_cursor.epoch < current.epoch or (
        next_cursor.epoch == current.epoch
        and (
            next_cursor.item_offset <= current.item_offset
            or next_cursor.epoch_step <= current.epoch_step
        )
    ):
        violate(
            "dataset-cursor/not-advanced",
            f"next Dataset Cursor {next_cursor!r} does not advance {current!r}",
            "commit the cursor supplied by the Dataset batch after its work succeeds",
        )


def _freeze(value: object) -> object:
    if isinstance(value, Mapping):
        mapping = cast(Mapping[object, object], value)
        return MappingProxyType(
            {str(key): _freeze(item) for key, item in mapping.items()}
        )
    if isinstance(value, list | tuple):
        sequence = cast(list[object] | tuple[object, ...], value)
        return tuple(_freeze(item) for item in sequence)
    return value


def _validate_output(
    name: str,
    data: object,
    kind: str,
    violate: Callable[[str, str, str], NoReturn],
) -> bytes:
    if not name or name.startswith("/") or ".." in name.split("/"):
        violate(
            "run-output/name",
            f"{kind} name {name!r} is not a safe relative name",
            "use a non-empty relative name without parent traversal",
        )
    if not isinstance(data, bytes):
        violate(
            "run-output/data",
            f"{kind} {name!r} received {type(data).__name__}",
            "persist immutable bytes",
        )
    return data
