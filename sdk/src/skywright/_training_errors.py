"""Internal and public Training Process failure types."""

from skywright._training_types import ExecutionTerminationCause, FailureStage


class CheckpointPublicationCancelled(Exception):
    """Expected end of an in-flight publication during non-durable shutdown."""


class ObservabilityShutdownIncomplete(TimeoutError):
    """Attempt-owned observability work did not stop before its deadline."""


class CooperativeStop(BaseException):
    def __init__(
        self,
        cause: ExecutionTerminationCause,
        diagnostics: dict[str, object] | None = None,
    ) -> None:
        super().__init__(cause.value)
        self.cause = cause
        self.diagnostics = diagnostics or {}


class SkywrightFailure(BaseException):
    def __init__(self, failure: Exception, stage: FailureStage) -> None:
        super().__init__(str(failure))
        self.failure = failure
        self.stage: FailureStage = stage


class TrainingContractViolation(RuntimeError):
    """A latched misuse of the Training Contract."""

    def __init__(self, rule: str, problem: str, guidance: str) -> None:
        super().__init__(f"{rule}: {problem}. {guidance}")
        self.rule = rule
        self.problem = problem
        self.guidance = guidance
