"""Default Run Context implementation and Training Contract lifecycle."""

import copy
from collections.abc import Callable, Mapping
from typing import NoReturn, cast

from skywright._training_checkpoints import capture_checkpoint, restore_checkpoint
from skywright._training_dataset import TrackedDatasetAccess, validate_cursor_shape
from skywright._training_errors import (
    CooperativeStop,
    SkywrightFailure,
    TrainingContractViolation,
)
from skywright._training_metrics import (
    reduce_observations,
    validate_metric_catalog,
    validate_observation,
)
from skywright._training_protocols import (
    CheckpointState,
    DatasetAccess,
    MetricContractResolver,
    TrainingProcessRecorder,
)
from skywright._training_state import freeze, validate_output
from skywright._training_types import (
    Accelerator,
    ArtifactRecord,
    CheckpointSnapshot,
    DatasetBatch,
    DatasetCursor,
    ExecutionTerminationCause,
    MetricObservation,
    ResumeState,
    SampleRecord,
)


class DefaultRunContext:
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
        self._started = False
        self._violated: TrainingContractViolation | None = None
        frozen_configuration = freeze(copy.deepcopy(dict(configuration)))
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
            raise SkywrightFailure(failure, "construction") from failure
        self._definitions = validate_metric_catalog(
            metric_catalog, project_version, skywright_metric_schema
        )
        self._resume_state = ResumeState(resume_from)
        if resume_from is not None and (
            type(resume_from.step) is not int or resume_from.step < 0
        ):
            self._violate(
                "resume/checkpoint-step",
                f"the resume checkpoint has invalid Step {resume_from.step!r}",
                "resume from a checkpoint with a non-negative integer Step",
            )
        if resume_from is not None and not resume_from.reference:
            self._violate(
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
        self._initial_step = self._step
        fingerprint = dataset.ordering_fingerprint
        if not fingerprint:
            raise TrainingContractViolation(
                "dataset-ordering/fingerprint",
                "the Dataset ordering fingerprint is empty",
                "identify the Dataset Definition, seed, ordering policy, and policy version",
            )
        self._dataset_cursor: DatasetCursor = (
            resume_from.dataset_cursor
            if resume_from is not None
            else DatasetCursor(ordering_fingerprint=fingerprint)
        )
        validate_cursor_shape(self._dataset_cursor, self._violate)
        if self._dataset_cursor.ordering_fingerprint != fingerprint:
            raise TrainingContractViolation(
                "dataset-ordering/fingerprint",
                "the checkpoint and configured Dataset ordering fingerprints differ",
                "resume with identical ordering inputs or an explicit Ordering Reset",
            )
        self._dataset = TrackedDatasetAccess(
            dataset, self._dataset_cursor, self._violate
        )
        self._latest_durable_step = (
            resume_from.step if resume_from is not None else None
        )
        self._latest_durable_checkpoint = (
            resume_from.reference if resume_from is not None else None
        )

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
            raise SkywrightFailure(failure, "project") from failure

    @property
    def interruption_requested(self) -> bool:
        if self.cancellation_requested:
            return False
        return self._read_interruption_requested()

    def _read_interruption_requested(self) -> bool:
        try:
            return self._interruption_requested()
        except Exception as failure:
            raise SkywrightFailure(failure, "project") from failure

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
        if any(registered is state for registered in self._states.values()):
            self._violate(
                "checkpoint-state/identity",
                f"Checkpoint State {name!r} is already registered under another name",
                "register each state object exactly once under one stable name",
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
        restore_checkpoint(
            self._resume_state.checkpoint,
            self._states,
            self._run_id,
            self._project_version,
            self._violate,
        )
        self._started = True
        return self._resume_state

    def observe(self, name: str, value: object) -> None:
        self._require_running(f"observe metric {name!r}")
        validated = validate_observation(
            name, value, self._definitions.get(name), self._violate
        )
        self._pending.setdefault(name, []).append(validated)

    def commit_step(self, final_batch: DatasetBatch) -> None:
        self._require_running("commit a Step")
        next_dataset_cursor = self._dataset.consume(final_batch)
        next_step = self._step + 1
        committed = reduce_observations(
            self._pending, self._definitions, next_step, self._violate
        )
        try:
            self._recorder.publish_step(
                next_step,
                next_dataset_cursor,
                committed,
                self._latest_durable_step,
                self._latest_durable_checkpoint,
            )
        except Exception as failure:
            raise SkywrightFailure(failure, "project") from failure
        self._observations.extend(committed)
        self._pending.clear()
        self._step = next_step
        self._dataset_cursor = next_dataset_cursor
        if self.cancellation_requested:
            raise CooperativeStop(ExecutionTerminationCause.CANCELLED)
        interrupted = self._read_interruption_requested()
        if self.cancellation_requested:
            raise CooperativeStop(ExecutionTerminationCause.CANCELLED)
        if interrupted:
            raise CooperativeStop(ExecutionTerminationCause.INTERRUPTED)

    def persist_artifact(self, name: str, data: object) -> None:
        self._require_running(f"persist Artifact {name!r}")
        validated = validate_output(name, data, "Artifact", self._violate)
        artifact = ArtifactRecord(name, validated, self._step)
        try:
            self._recorder.publish_artifact(artifact)
        except Exception as failure:
            raise SkywrightFailure(failure, "project") from failure
        self._artifacts.append(artifact)

    def persist_sample(self, name: str, data: object, *, media_type: str) -> None:
        self._require_running(f"persist Sample {name!r}")
        validated = validate_output(name, data, "Sample", self._violate)
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
            raise SkywrightFailure(failure, "project") from failure
        self._samples.append(sample)

    def snapshot(self) -> CheckpointSnapshot:
        return capture_checkpoint(
            self._step,
            self._states,
            self._dataset_cursor,
            self._run_id,
            self._project_version,
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
        if self._step == self._initial_step:
            self._violate(
                "step/empty-run",
                "the Training Project returned without committing a Step",
                "commit at least one new Step in this Execution Attempt before successful completion",
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
