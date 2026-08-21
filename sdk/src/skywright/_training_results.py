"""Result construction and durable report publication."""

from collections.abc import Mapping
from dataclasses import replace
from types import MappingProxyType

from skywright._training_context import DefaultRunContext
from skywright._training_errors import (
    ObservabilityShutdownIncomplete,
    SkywrightFailure,
    TrainingContractViolation,
)
from skywright._training_protocols import (
    TrainingProcessRecorder,
    finalize_recorder_observability,
)
from skywright._training_types import (
    CheckpointSnapshot,
    ExecutionAttemptRecord,
    ExecutionTerminationCause,
    ExecutionTerminationReport,
    FailureStage,
    TrainingProcessOutcome,
    TrainingProcessResult,
)


def stopped_result(
    attempt: ExecutionAttemptRecord,
    cause: ExecutionTerminationCause,
    context: DefaultRunContext,
    resume_from: CheckpointSnapshot | None,
    recorder: TrainingProcessRecorder,
    diagnostics: Mapping[str, object],
) -> TrainingProcessResult:
    if cause in (
        ExecutionTerminationCause.INTERRUPTED,
        ExecutionTerminationCause.POLICY_STOPPED,
    ):
        return durable_result(
            attempt,
            (
                TrainingProcessOutcome.INTERRUPTED
                if cause is ExecutionTerminationCause.INTERRUPTED
                else TrainingProcessOutcome.CANCELLED
            ),
            cause,
            context,
            resume_from,
            recorder,
            diagnostics,
            cancellation_can_preempt=True,
        )
    shutdown = context.stop_checkpoint_work()
    cleanup_failure = shutdown.failure
    if cleanup_failure is not None:
        return failure_result(
            attempt,
            cleanup_failure,
            context,
            resume_from,
            "finalization",
            recorder,
            True,
            skywright_failure=True,
        )
    durable_step, durable_checkpoint = context.durable_state()
    result = _result(
        attempt=attempt,
        outcome=TrainingProcessOutcome.CANCELLED,
        cause=cause,
        last_committed_step=context.committed_step,
        latest_durable_step=durable_step,
        latest_durable_checkpoint=durable_checkpoint,
        final_checkpoint=None,
        context=context,
        diagnostics=diagnostics,
    )
    return _publish_report(result, recorder) if shutdown.stopped else result


def durable_result(
    attempt: ExecutionAttemptRecord,
    outcome: TrainingProcessOutcome,
    cause: ExecutionTerminationCause,
    context: DefaultRunContext,
    resume_from: CheckpointSnapshot | None,
    recorder: TrainingProcessRecorder,
    diagnostics: Mapping[str, object] | None = None,
    *,
    cancellation_can_preempt: bool = False,
) -> TrainingProcessResult:
    try:
        checkpoint = context.publish_terminal_checkpoint(
            cancellation_can_preempt=cancellation_can_preempt
        )
    except SkywrightFailure as failure:
        return failure_result(
            attempt,
            failure.failure,
            context,
            resume_from,
            "finalization",
            recorder,
            True,
            skywright_failure=True,
        )
    if checkpoint is None:
        return stopped_result(
            attempt,
            ExecutionTerminationCause.CANCELLED,
            context,
            resume_from,
            recorder,
            {},
        )
    result = _result(
        attempt=attempt,
        outcome=outcome,
        cause=cause,
        last_committed_step=context.committed_step,
        latest_durable_step=checkpoint.step,
        latest_durable_checkpoint=checkpoint.reference,
        final_checkpoint=checkpoint,
        context=context,
        diagnostics=diagnostics or {},
    )
    return _publish_report(result, recorder)


def failure_result(
    attempt: ExecutionAttemptRecord,
    failure: Exception,
    context: DefaultRunContext | None,
    resume_from: CheckpointSnapshot | None,
    stage: FailureStage,
    recorder: TrainingProcessRecorder,
    attempt_published: bool,
    *,
    skywright_failure: bool = False,
) -> TrainingProcessResult:
    shutdown = context.stop_checkpoint_work() if context is not None else None
    cleanup_failure = shutdown.failure if shutdown is not None else None
    if cleanup_failure is failure:
        cleanup_failure = None
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
    last_step = context.committed_step if context is not None else 0
    if context is not None:
        durable_step, durable_checkpoint = context.durable_state()
    else:
        durable_step = resume_from.step if resume_from is not None else None
        durable_checkpoint = resume_from.reference if resume_from is not None else None
    result = _result(
        attempt=attempt,
        outcome=TrainingProcessOutcome.FAILED,
        cause=cause,
        last_committed_step=last_step,
        latest_durable_step=durable_step,
        latest_durable_checkpoint=durable_checkpoint,
        final_checkpoint=None,
        context=context,
        diagnostics=_with_cleanup_failure(diagnostics, cleanup_failure),
    )
    checkpoint_work_stopped = shutdown is None or shutdown.stopped
    return (
        _publish_report(result, recorder)
        if attempt_published and checkpoint_work_stopped
        else result
    )


def unpublished_failure(
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
    context: DefaultRunContext | None,
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
    observability_stopped = True
    try:
        finalize_recorder_observability(recorder)
    except Exception as failure:
        observability_stopped = not isinstance(failure, ObservabilityShutdownIncomplete)
        diagnostics = dict(result.report.diagnostics)
        diagnostics["observability_finalization_failure"] = {
            "exception_type": type(failure).__name__,
            "message": str(failure),
        }
        preserves_cause = result.report.cause in (
            ExecutionTerminationCause.CONTRACT_VIOLATION,
            ExecutionTerminationCause.TRAINING_PROJECT_FAILURE,
            ExecutionTerminationCause.SKYWRIGHT_FAILURE,
        )
        result = replace(
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
    if not observability_stopped:
        return result
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


def _with_cleanup_failure(
    diagnostics: Mapping[str, object], failure: Exception | None
) -> Mapping[str, object]:
    if failure is None:
        return diagnostics
    combined = dict(diagnostics)
    combined["checkpoint_cleanup_failure"] = {
        "exception_type": type(failure).__name__,
        "message": str(failure),
    }
    return combined
