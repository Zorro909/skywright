"""Recovery admission contracts for the runtime and its orchestration authority."""

from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal, TypeAlias

from skywright._training_types import CheckpointConfirmation, ExecutionAttemptRecord


class RecoveryAdmissionError(RuntimeError):
    """Startup was refused without opening an attempt or diagnosing its predecessor."""

    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class PreviousWriterEvidence:
    """Authority-verified, permanent quiescence of exactly one previous writer.

    A trusted supervisor must establish process death or removal of that attempt's
    write authority. Missing visibility, a report, or a self-declared token is not
    evidence. The reference identifies the supervisor's inspectable evidence.
    """

    run_id: str
    attempt_id: str
    condition: Literal["stopped", "write-authority-revoked"]
    reference: str


PreviousWriterVerifier: TypeAlias = Callable[
    [ExecutionAttemptRecord], PreviousWriterEvidence | None
]


def uncertain_previous_writer(
    attempt: ExecutionAttemptRecord,
) -> PreviousWriterEvidence | None:
    """Default to unavailable recovery until a trustworthy authority is wired in."""
    return None


@dataclass(frozen=True)
class RecoverySeed:
    """Pinned external continuation retained until a clone publishes its own state."""

    source_run_id: str
    step: int
    reference: str
    ordering_reset: bool


@dataclass(frozen=True)
class RecoveryHistory:
    """Validated durable history; checkpoint identities survive payload retention."""

    attempts: tuple[ExecutionAttemptRecord, ...]
    checkpoints: tuple[CheckpointConfirmation, ...]
    debt: int
    maximum_debt: int
    head: str | None
    seed: RecoverySeed | None = None
