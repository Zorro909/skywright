"""Orchestration for one Training Process Boundary invocation."""

import math
import time
import uuid
from collections.abc import Callable, Mapping
from dataclasses import replace
from typing import cast

from skywright._cgroup_memory import read_cgroup_memory_usage
from skywright._training_context import DefaultRunContext
from skywright._training_environment import (
    claim_process,
    establish_determinism,
    load_training_project,
    resolve_component,
)
from skywright._training_errors import (
    CooperativeStop,
    SkywrightFailure,
    TrainingContractViolation,
)
from skywright._training_protocols import (
    DatasetAccess,
    MetricContractResolver,
    TrainingProcessRecorder,
    TrainingProject,
    configure_recorder_observability,
)
from skywright._training_results import (
    durable_result,
    failure_result,
    stopped_result,
    unpublished_failure,
)
from skywright._training_signals import SignalRequests
from skywright._training_system_metrics import SamplerWait, wait_for_sampling
from skywright._training_types import (
    CPU_ACCELERATOR,
    Accelerator,
    CheckpointRejectionEvidence,
    CheckpointSnapshot,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    TrainingProcessOutcome,
    TrainingProcessResult,
)


def _never_requested() -> bool:
    return False


def _no_policy_stop() -> str | None:
    return None


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
    source_run_id: str | None = None,
    ordering_reset: bool = False,
    accelerator: Accelerator = CPU_ACCELERATOR,
    cancellation_requested: Callable[[], bool] = _never_requested,
    interruption_requested: Callable[[], bool] = _never_requested,
    policy_stop_requested: Callable[[], str | None] = _no_policy_stop,
    shutdown_grace_seconds: float = 30.0,
    rejected_corrupt_checkpoints: tuple[CheckpointRejectionEvidence, ...] = (),
    monotonic_clock: Callable[[], float] = time.monotonic,
    cgroup_memory_reader: Callable[[], int | None] = read_cgroup_memory_usage,
    system_sampler_wait: SamplerWait = wait_for_sampling,
) -> TrainingProcessResult:
    """Execute one Training Project through the process's sole Run Context."""

    try:
        claim_process()
    except TrainingContractViolation as violation:
        attempt = ExecutionAttemptRecord(
            attempt_id=str(uuid.uuid4()),
            run_id=run_id,
            project_version=project_version,
            seed_checkpoint_step=None,
            rejected_corrupt_checkpoints=rejected_corrupt_checkpoints,
        )
        return unpublished_failure(attempt, violation, "construction")
    attempt = ExecutionAttemptRecord(
        attempt_id=str(uuid.uuid4()),
        run_id=run_id,
        project_version=project_version,
        seed_checkpoint_step=(
            resume_from.step if isinstance(resume_from, CheckpointSnapshot) else None
        ),
        seed_checkpoint_reference=(
            resume_from.reference
            if isinstance(resume_from, CheckpointSnapshot)
            else None
        ),
        rejected_corrupt_checkpoints=rejected_corrupt_checkpoints,
    )
    if not run_id or not project_version:
        violation = TrainingContractViolation(
            "training-process/identity",
            "the Run identity or Training Project Version is empty",
            "provide stable non-empty run_id and project_version values",
        )
        return unpublished_failure(attempt, violation, "construction")
    if shutdown_grace_seconds <= 0 or not math.isfinite(shutdown_grace_seconds):
        violation = TrainingContractViolation(
            "training-process/shutdown-grace",
            f"shutdown grace {shutdown_grace_seconds!r} is not positive and finite",
            "configure a positive finite shutdown grace in seconds",
        )
        return unpublished_failure(attempt, violation, "construction")
    signal_requests = SignalRequests(shutdown_grace_seconds)
    try:
        signal_requests.install()
    except Exception as failure:
        return unpublished_failure(attempt, failure, "construction")
    try:
        establish_determinism(seed)
    except Exception as failure:
        return unpublished_failure(attempt, failure, "construction")

    def finish(result: TrainingProcessResult) -> TrainingProcessResult:
        signal_requests.finalize()
        return result

    def any_interruption_requested() -> bool:
        return signal_requests.interruption_requested or interruption_requested()

    try:
        resolved_recorder = cast(
            TrainingProcessRecorder, resolve_component(recorder, "recorder")
        )
        configure_recorder_observability(
            resolved_recorder,
            configuration,
            shutdown_grace_seconds,
        )
        resolved_resume = (
            cast(
                CheckpointSnapshot,
                resolve_component(resume_from, "resume checkpoint"),
            )
            if resume_from is not None
            else None
        )
        attempt = replace(
            attempt,
            seed_checkpoint_step=(
                resolved_resume.step if resolved_resume is not None else None
            ),
            seed_checkpoint_reference=(
                resolved_resume.reference if resolved_resume is not None else None
            ),
        )
        resolved_recorder.publish_attempt(attempt)
    except Exception as failure:
        return finish(unpublished_failure(attempt, failure, "construction"))
    try:
        resolved_dataset = cast(
            DatasetAccess, resolve_component(dataset, "Dataset access")
        )
        resolved_metric_contracts = cast(
            MetricContractResolver,
            resolve_component(metric_contracts, "metric contract resolver"),
        )
        context = DefaultRunContext(
            configuration=configuration,
            dataset=resolved_dataset,
            metric_contracts=resolved_metric_contracts,
            skywright_metric_schema=skywright_metric_schema,
            recorder=resolved_recorder,
            resume_from=resolved_resume,
            accelerator=accelerator,
            cancellation_requested=cancellation_requested,
            interruption_requested=any_interruption_requested,
            policy_stop_requested=policy_stop_requested,
            run_id=run_id,
            project_version=project_version,
            shutdown_grace_seconds=shutdown_grace_seconds,
            monotonic_clock=monotonic_clock,
            cgroup_memory_reader=cgroup_memory_reader,
            system_sampler_wait=system_sampler_wait,
            seed=seed,
            source_run_id=source_run_id,
            ordering_reset=ordering_reset,
        )
    except TrainingContractViolation as violation:
        return finish(
            failure_result(
                attempt,
                violation,
                None,
                resolved_resume,
                "construction",
                resolved_recorder,
                True,
            )
        )
    except SkywrightFailure as failure:
        return finish(
            failure_result(
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
            failure_result(
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
            load_training_project(entry_point)
            if isinstance(entry_point, str)
            else entry_point
        )
        project(context)
        context.validate_completion()
    except CooperativeStop as stop:
        return finish(
            stopped_result(
                attempt,
                stop.cause,
                context,
                resolved_resume,
                resolved_recorder,
                stop.diagnostics,
            )
        )
    except SkywrightFailure as failure:
        return finish(
            failure_result(
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
            failure_result(
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
            failure_result(
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
        durable_result(
            attempt,
            TrainingProcessOutcome.COMPLETED,
            ExecutionTerminationCause.COMPLETED,
            context,
            resolved_resume,
            resolved_recorder,
        )
    )
