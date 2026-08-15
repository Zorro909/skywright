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
from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Literal, NoReturn, Protocol, TypeAlias, cast

NumericKind: TypeAlias = Literal["real", "integer"]
Comparison: TypeAlias = Literal["minimize", "maximize", "none"]
StepReduction: TypeAlias = Literal["mean", "sum", "min", "max", "last"]
AcceleratorKind: TypeAlias = Literal["cpu", "cuda", "rocm"]


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
    step_reduction: StepReduction
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


class RunContext(Protocol):
    """The sole project-facing interface for one training process."""

    @property
    def configuration(self) -> Mapping[str, object]: ...

    @property
    def dataset(self) -> Iterable[object]: ...

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

    def commit_step(self) -> None: ...

    def persist_artifact(self, name: str, data: bytes) -> None: ...

    def persist_sample(self, name: str, data: bytes, *, media_type: str) -> None: ...


TrainingProject: TypeAlias = Callable[[RunContext], None]


class _CooperativeStop(BaseException):
    def __init__(self, cause: ExecutionTerminationCause) -> None:
        super().__init__(cause.value)
        self.cause = cause


class _SignalRequests:
    def __init__(self) -> None:
        self.interruption_requested = False

    def install(self) -> None:
        signal.signal(signal.SIGINT, self._handle)
        signal.signal(signal.SIGTERM, self._handle)

    def _handle(self, _signal_number: int, _frame: object) -> None:
        if self.interruption_requested:
            os._exit(1)
        self.interruption_requested = True


class TrainingContractViolation(RuntimeError):
    """A latched misuse of the Training Contract."""

    def __init__(self, rule: str, problem: str, guidance: str) -> None:
        super().__init__(f"{rule}: {problem}. {guidance}")
        self.rule = rule
        self.problem = problem
        self.guidance = guidance


class _DefaultRunContext:
    def __init__(
        self,
        *,
        configuration: Mapping[str, object],
        dataset: Iterable[object],
        metric_definitions: Iterable[MetricDefinition],
        resume_from: CheckpointSnapshot | None,
        accelerator: Accelerator,
        cancellation_requested: Callable[[], bool],
        interruption_requested: Callable[[], bool],
        run_id: str,
        project_version: str,
    ) -> None:
        frozen_configuration = _freeze(copy.deepcopy(dict(configuration)))
        self._configuration = cast(Mapping[str, object], frozen_configuration)
        self._dataset = dataset
        self._accelerator = accelerator
        self._cancellation_requested = cancellation_requested
        self._interruption_requested = interruption_requested
        self._run_id = run_id
        self._project_version = project_version
        self._definitions = _validate_metric_definitions(metric_definitions)
        self._resume_state = ResumeState(resume_from)
        self._states: dict[str, CheckpointState] = {}
        self._pending: dict[str, list[int | float]] = {}
        self._observations: list[MetricObservation] = []
        self._artifacts: list[ArtifactRecord] = []
        self._samples: list[SampleRecord] = []
        self._step = resume_from.step if resume_from is not None else 0
        self._started = False
        self._violated: TrainingContractViolation | None = None

    @property
    def configuration(self) -> Mapping[str, object]:
        return self._configuration

    @property
    def dataset(self) -> Iterable[object]:
        return self._dataset

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
        return self._cancellation_requested()

    @property
    def interruption_requested(self) -> bool:
        return not self.cancellation_requested and self._interruption_requested()

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
            _restore_runtime_state(checkpoint.runtime_state)
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

    def commit_step(self) -> None:
        self._require_running("commit a Step")
        next_step = self._step + 1
        for name, values in self._pending.items():
            definition = self._definitions[name]
            reduced = _reduce(values, definition.step_reduction)
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
            self._observations.append(MetricObservation(name, next_step, reduced))
        self._pending.clear()
        self._step = next_step
        if self.cancellation_requested:
            raise _CooperativeStop(ExecutionTerminationCause.CANCELLED)
        if self.interruption_requested:
            raise _CooperativeStop(ExecutionTerminationCause.INTERRUPTED)

    def persist_artifact(self, name: str, data: object) -> None:
        self._require_running(f"persist Artifact {name!r}")
        validated = _validate_output(name, data, "Artifact", self._violate)
        self._artifacts.append(ArtifactRecord(name, validated, self._step))

    def persist_sample(self, name: str, data: object, *, media_type: str) -> None:
        self._require_running(f"persist Sample {name!r}")
        validated = _validate_output(name, data, "Sample", self._violate)
        if "/" not in media_type:
            self._violate(
                "sample/media-type",
                f"Sample {name!r} has invalid media type {media_type!r}",
                "supply a concrete media type such as image/png or audio/wav",
            )
        self._samples.append(SampleRecord(name, validated, media_type, self._step))

    def snapshot(self) -> CheckpointSnapshot:
        state = {
            name: copy.deepcopy(dict(checkpoint_state.state_dict()))
            for name, checkpoint_state in self._states.items()
        }
        return CheckpointSnapshot(
            step=self._step,
            state=MappingProxyType(state),
            runtime_state=MappingProxyType(_capture_runtime_state()),
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
    entry_point: TrainingProject,
    *,
    run_id: str,
    project_version: str,
    configuration: Mapping[str, object],
    dataset: Iterable[object],
    metric_definitions: Iterable[MetricDefinition],
    seed: int,
    resume_from: CheckpointSnapshot | None = None,
    accelerator: Accelerator = _CPU_ACCELERATOR,
    cancellation_requested: Callable[[], bool] = _never_requested,
    interruption_requested: Callable[[], bool] = _never_requested,
) -> TrainingProcessResult:
    """Execute one Training Project through the process's sole Run Context."""

    attempt = ExecutionAttemptRecord(
        attempt_id=str(uuid.uuid4()),
        run_id=run_id,
        project_version=project_version,
        seed_checkpoint_step=resume_from.step if resume_from is not None else None,
    )
    try:
        _claim_process()
    except TrainingContractViolation as violation:
        return _failure_result(attempt, violation, None, resume_from, "construction")
    if not run_id or not project_version:
        violation = TrainingContractViolation(
            "training-process/identity",
            "the Run identity or Training Project Version is empty",
            "provide stable non-empty run_id and project_version values",
        )
        return _failure_result(attempt, violation, None, resume_from, "construction")
    signal_requests = _SignalRequests()
    try:
        signal_requests.install()
    except Exception as failure:
        return _failure_result(attempt, failure, None, resume_from, "construction")
    try:
        _establish_determinism(seed)
    except Exception as failure:
        return _failure_result(attempt, failure, None, resume_from, "construction")

    def any_interruption_requested() -> bool:
        return signal_requests.interruption_requested or interruption_requested()

    try:
        context = _DefaultRunContext(
            configuration=configuration,
            dataset=dataset,
            metric_definitions=metric_definitions,
            resume_from=resume_from,
            accelerator=accelerator,
            cancellation_requested=cancellation_requested,
            interruption_requested=any_interruption_requested,
            run_id=run_id,
            project_version=project_version,
        )
    except TrainingContractViolation as violation:
        return _failure_result(attempt, violation, None, resume_from, "construction")
    except Exception as failure:
        return _failure_result(attempt, failure, None, resume_from, "construction")
    try:
        entry_point(context)
        context.validate_completion()
    except _CooperativeStop as stop:
        return _stopped_result(attempt, stop.cause, context, resume_from)
    except TrainingContractViolation as violation:
        return _failure_result(attempt, violation, context, resume_from, "project")
    except Exception as failure:
        return _failure_result(attempt, failure, context, resume_from, "project")
    try:
        checkpoint = context.snapshot()
    except Exception as failure:
        return _failure_result(attempt, failure, context, resume_from, "finalization")
    report = ExecutionTerminationReport(
        schema_version=1,
        attempt_id=attempt.attempt_id,
        run_id=attempt.run_id,
        project_version=attempt.project_version,
        cause=ExecutionTerminationCause.COMPLETED,
        last_committed_step=context.step,
        latest_durable_step=checkpoint.step,
        diagnostics=MappingProxyType({}),
    )
    return TrainingProcessResult(
        outcome=TrainingProcessOutcome.COMPLETED,
        attempt=attempt,
        report=report,
        final_checkpoint=checkpoint,
        metric_observations=context.observations,
        artifacts=context.artifacts,
        samples=context.samples,
    )


def _stopped_result(
    attempt: ExecutionAttemptRecord,
    cause: ExecutionTerminationCause,
    context: _DefaultRunContext,
    resume_from: CheckpointSnapshot | None,
) -> TrainingProcessResult:
    checkpoint: CheckpointSnapshot | None = None
    if cause is ExecutionTerminationCause.INTERRUPTED:
        try:
            checkpoint = context.snapshot()
        except Exception as failure:
            return _failure_result(
                attempt, failure, context, resume_from, "finalization"
            )
    durable_step = (
        checkpoint.step
        if checkpoint is not None
        else (resume_from.step if resume_from is not None else None)
    )
    report = ExecutionTerminationReport(
        schema_version=1,
        attempt_id=attempt.attempt_id,
        run_id=attempt.run_id,
        project_version=attempt.project_version,
        cause=cause,
        last_committed_step=context.step,
        latest_durable_step=durable_step,
        diagnostics=MappingProxyType({}),
    )
    outcome = (
        TrainingProcessOutcome.INTERRUPTED
        if cause is ExecutionTerminationCause.INTERRUPTED
        else TrainingProcessOutcome.CANCELLED
    )
    return TrainingProcessResult(
        outcome=outcome,
        attempt=attempt,
        report=report,
        final_checkpoint=checkpoint,
        metric_observations=context.observations,
        artifacts=context.artifacts,
        samples=context.samples,
    )


def _failure_result(
    attempt: ExecutionAttemptRecord,
    failure: Exception,
    context: _DefaultRunContext | None,
    resume_from: CheckpointSnapshot | None,
    stage: str,
) -> TrainingProcessResult:
    if isinstance(failure, TrainingContractViolation):
        cause = ExecutionTerminationCause.CONTRACT_VIOLATION
        diagnostics: dict[str, object] = {
            "rule": failure.rule,
            "problem": failure.problem,
            "guidance": failure.guidance,
            "stage": stage,
        }
    elif stage == "project":
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
    report = ExecutionTerminationReport(
        schema_version=1,
        attempt_id=attempt.attempt_id,
        run_id=attempt.run_id,
        project_version=attempt.project_version,
        cause=cause,
        last_committed_step=last_step,
        latest_durable_step=durable_step,
        diagnostics=MappingProxyType(diagnostics),
    )
    return TrainingProcessResult(
        outcome=TrainingProcessOutcome.FAILED,
        attempt=attempt,
        report=report,
        final_checkpoint=None,
        metric_observations=context.observations if context is not None else (),
        artifacts=context.artifacts if context is not None else (),
        samples=context.samples if context is not None else (),
    )


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


def _validate_metric_definitions(
    definitions: Iterable[MetricDefinition],
) -> dict[str, MetricDefinition]:
    catalog: dict[str, MetricDefinition] = {}
    for definition in definitions:
        if definition.name.startswith("skywright/"):
            raise TrainingContractViolation(
                "metric-definition/reserved-name",
                f"project metric {definition.name!r} uses the skywright/ namespace",
                "declare project metrics outside the library-owned namespace",
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
        if definition.step_reduction not in ("mean", "sum", "min", "max", "last"):
            raise TrainingContractViolation(
                "metric-definition/reduction",
                f"metric {definition.name!r} has unknown Step Reduction {definition.step_reduction!r}",
                "use mean, sum, min, max, or last",
            )
        if definition.numeric_kind == "integer" and definition.step_reduction == "mean":
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
