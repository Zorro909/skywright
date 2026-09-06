"""Immutable recovery history and conditional admission, behind the S3 recorder."""

from __future__ import annotations

import hashlib
import json
import math
import re
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import asdict, replace
from typing import Any, NoReturn, cast

from skywright._training_types import (
    CheckpointConfirmation,
    CheckpointRejectionEvidence,
    ExecutionAttemptRecord,
)
from skywright.recovery import (
    PreviousWriterEvidence,
    PreviousWriterVerifier,
    RecoveryAdmissionError,
    RecoveryHistory,
    RecoverySeed,
)

# Limits fail closed rather than silently truncating the evidence used for debt.
_MAX_HISTORY_BYTES = 16 * 1024 * 1024
_MAX_RECORD_BYTES = 64 * 1024
_MAX_EVENTS = 100_000


def encoded(document: Mapping[str, object]) -> bytes:
    return json.dumps(
        document, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode()


def fail(detail: str) -> NoReturn:
    raise RecoveryAdmissionError("RECOVERY_HISTORY_INVALID", detail)


def document(body: bytes, *, max_bytes: int = _MAX_RECORD_BYTES) -> dict[str, Any]:
    if len(body) > max_bytes:
        fail("recovery record exceeds its byte budget")
    try:
        value: object = json.loads(body)
    except (ValueError, UnicodeError) as failure:
        raise RecoveryAdmissionError(
            "RECOVERY_HISTORY_INVALID", "invalid JSON record"
        ) from failure
    if not isinstance(value, dict) or encoded(cast(dict[str, Any], value)) != body:
        fail("recovery record is not a canonical object")
    return cast(dict[str, Any], value)


def attempt_document(attempt: ExecutionAttemptRecord) -> dict[str, object]:
    return asdict(attempt)


def read_attempt(raw: Any) -> ExecutionAttemptRecord:
    try:
        if not isinstance(raw, dict):
            fail("attempt is not an object")
        data = dict(cast(dict[str, Any], raw))
        data["rejected_corrupt_checkpoints"] = tuple(
            CheckpointRejectionEvidence(**item)
            for item in data["rejected_corrupt_checkpoints"]
        )
        attempt = ExecutionAttemptRecord(**data)
        import uuid

        if str(uuid.UUID(attempt.attempt_id)) != attempt.attempt_id:
            fail("attempt identity is not a canonical UUID")
        if not attempt.run_id or not attempt.project_version:
            fail("attempt identity is empty")
        if (attempt.seed_checkpoint_step is None) != (
            attempt.seed_checkpoint_reference is None
        ):
            fail("attempt seed identity is incomplete")
        if attempt.seed_checkpoint_step is not None and (
            type(attempt.seed_checkpoint_step) is not int
            or not 1 <= attempt.seed_checkpoint_step <= 2**63 - 1
            or not isinstance(attempt.seed_checkpoint_reference, str)
            or re.fullmatch(
                r"skywright-checkpoint:v1:"
                + str(attempt.seed_checkpoint_step)
                + r":sha256:[0-9a-f]{64}",
                attempt.seed_checkpoint_reference,
            )
            is None
        ):
            fail("attempt seed identity is invalid")
        return attempt
    except (ValueError, TypeError, KeyError, AttributeError) as failure:
        raise RecoveryAdmissionError(
            "RECOVERY_HISTORY_INVALID", "invalid attempt record"
        ) from failure


def read_seed(raw: object) -> RecoverySeed | None:
    if raw is None:
        return None
    try:
        if not isinstance(raw, dict):
            fail("external seed is not an object")
        values = cast(dict[str, Any], raw)
        if not isinstance(values.get("source_run_id"), str) or not isinstance(
            values.get("reference"), str
        ):
            fail("external seed identities must be strings")
        seed = RecoverySeed(**values)
        if (
            not seed.source_run_id
            or type(seed.step) is not int
            or not 1 <= seed.step <= 2**63 - 1
            or type(seed.ordering_reset) is not bool
            or re.fullmatch(
                r"skywright-checkpoint:v1:" + str(seed.step) + r":sha256:[0-9a-f]{64}",
                seed.reference,
            )
            is None
        ):
            fail("external seed identity is invalid")
        return seed
    except (TypeError, ValueError) as failure:
        raise RecoveryAdmissionError(
            "RECOVERY_HISTORY_INVALID", "invalid external seed"
        ) from failure


def validate_attempt_seed(
    attempt: ExecutionAttemptRecord,
    checkpoints: tuple[CheckpointConfirmation, ...],
    pinned: RecoverySeed | None,
    active: RecoverySeed | None,
) -> None:
    if active is not None:
        if (
            checkpoints
            or active != pinned
            or attempt.seed_checkpoint_step != active.step
            or attempt.seed_checkpoint_reference != active.reference
            or active.source_run_id == attempt.run_id
        ):
            fail("attempt external seed differs from its pinned continuation")
    elif checkpoints:
        if not any(
            c.step == attempt.seed_checkpoint_step
            and c.reference == attempt.seed_checkpoint_reference
            for c in checkpoints
        ):
            fail("attempt seed is not a confirmed checkpoint")
    elif pinned is not None or attempt.seed_checkpoint_reference is not None:
        fail("attempt omitted its pinned external seed")


class RecoveryJournal:
    """Replay a hash-linked history and commit each new fact through one CAS head.

    The callbacks provide verified, bounded reads and immutable/conditional writes.
    A conditional head serializes admissions; previous-writer proof is still
    required because object-level conditions do not fence a suspended process.
    """

    def __init__(
        self,
        *,
        run_id: str,
        project_version: str,
        maximum_debt: int,
        read: Callable[[str], tuple[bytes, str] | None],
        immutable: Callable[[str, bytes], None],
        exchange: Callable[[str, bytes, str | None], None],
        empty_store: Callable[[], bool],
        verify_attempt: Callable[[ExecutionAttemptRecord], None],
        verify_report: Callable[
            [ExecutionAttemptRecord], tuple[str, int | None, str | None] | None
        ],
        wall_clock: Callable[[], float] = time.time,
    ) -> None:
        if type(maximum_debt) is not int or not 1 <= maximum_debt <= 2**31 - 1:
            raise RecoveryAdmissionError(
                "RECOVERY_POLICY_INVALID",
                "maximum debt must be a positive finite integer",
            )
        self.run_id = run_id
        self.project_version = project_version
        self.maximum_debt = maximum_debt
        self._read = read
        self._immutable = immutable
        self._exchange = exchange
        self._empty_store = empty_store
        self._verify_attempt = verify_attempt
        self._verify_report = verify_report
        self._wall_clock = wall_clock
        self._etag: str | None = None
        self._history: RecoveryHistory | None = None
        self._evidence: PreviousWriterEvidence | None = None
        self._active_attempt: str | None = None
        self._lock = threading.Lock()
        self._history_bytes = 0
        self._event_count = 0

    def load(self) -> RecoveryHistory:
        head_record = self._read("recovery/head.json")
        if head_record is None:
            if not self._empty_store():
                fail("Run Store has records without a complete recovery journal")
            self._etag = None
            self._history = RecoveryHistory((), (), 0, self.maximum_debt, None)
            return self._history
        head_bytes, self._etag = head_record
        head = document(head_bytes)
        if (
            set(head) != {"schemaVersion", "digest"}
            or type(head["schemaVersion"]) is not int
            or head["schemaVersion"] != 1
        ):
            fail("unsupported recovery head")
        digest = head["digest"]
        events: list[dict[str, Any]] = []
        seen: set[str] = set()
        total = 0
        while digest is not None:
            if (
                not isinstance(digest, str)
                or len(digest) != 64
                or any(c not in "0123456789abcdef" for c in digest)
            ):
                fail("invalid journal digest")
            if digest in seen or len(events) >= _MAX_EVENTS:
                fail("cyclic or oversized recovery history")
            seen.add(digest)
            record = self._read(f"recovery/events/{digest}.json")
            if record is None:
                fail("an addressed immutable history record is missing")
            body, _ = record
            total += len(body)
            if total > _MAX_HISTORY_BYTES or hashlib.sha256(body).hexdigest() != digest:
                fail("history byte budget or digest verification failed")
            event = document(body)
            if (
                type(event.get("schemaVersion")) is not int
                or event.get("schemaVersion") != 1
                or event.get("runId") != self.run_id
                or event.get("projectVersion") != self.project_version
            ):
                fail("history schema or identity mismatch")
            if (
                type(event.get("maximumDebt")) is not int
                or event.get("maximumDebt") != self.maximum_debt
            ):
                fail("the Run's maximum Recovery Debt changed")
            if "previous" not in event:
                fail("history link is missing")
            events.append(event)
            digest = event["previous"]
        self._history_bytes = total
        self._event_count = len(events)
        attempts: list[ExecutionAttemptRecord] = []
        checkpoints: dict[int, CheckpointConfirmation] = {}
        debt = 0
        pinned_seed: RecoverySeed | None = None
        for event in reversed(events):
            if event.get("kind") == "attempt":
                attempt = read_attempt(event.get("attempt"))
                if (
                    attempt.run_id != self.run_id
                    or attempt.project_version != self.project_version
                    or any(a.attempt_id == attempt.attempt_id for a in attempts)
                ):
                    fail("duplicate or foreign attempt")
                if attempts:
                    cause = self._report_cause(
                        attempts[-1], tuple(checkpoints.values())
                    )
                    if cause is not None and cause != "interrupted":
                        fail("a later attempt follows a terminal report")
                    evidence = event.get("previousWriter")
                    self._validate_evidence(evidence, attempts[-1])
                    debt += 1
                elif event.get("previousWriter") is not None:
                    fail("initial attempt names a previous writer")
                if (
                    debt > self.maximum_debt
                    or type(event.get("admittedDebt")) is not int
                    or event.get("admittedDebt") != debt
                ):
                    fail("attempt debt does not match durable history")
                active_seed = read_seed(event.get("externalSeed"))
                if not attempts:
                    pinned_seed = active_seed
                validate_attempt_seed(
                    attempt, tuple(checkpoints.values()), pinned_seed, active_seed
                )
                self._verify_attempt(attempt)
                attempts.append(attempt)
            elif event.get("kind") == "checkpoint":
                step, reference = event.get("step"), event.get("reference")
                if (
                    not attempts
                    or event.get("attemptId") != attempts[-1].attempt_id
                    or type(step) is not int
                    or step < 1
                    or not isinstance(reference, str)
                ):
                    fail("checkpoint does not identify its publishing attempt and Step")
                if (
                    re.fullmatch(
                        r"skywright-checkpoint:v1:"
                        + str(step)
                        + r":sha256:[0-9a-f]{64}",
                        reference,
                    )
                    is None
                ):
                    fail("checkpoint reference differs from its Step or schema")
                previous = checkpoints.get(step)
                if previous is not None:
                    fail("a checkpoint Step was counted more than once")
                checkpoints[step] = CheckpointConfirmation(step, reference)
                debt = max(0, debt - 1)
            else:
                fail("unknown recovery event")
        if not attempts:
            fail("nonempty journal has no initial attempt")
        self._history = RecoveryHistory(
            tuple(attempts),
            tuple(sorted(checkpoints.values(), key=lambda c: c.step)),
            debt,
            self.maximum_debt,
            head["digest"],
            pinned_seed,
        )
        return self._history

    def prepare(self, verifier: PreviousWriterVerifier) -> RecoveryHistory:
        history = self.load()
        exhaustion = self._read("recovery/exhaustion.json")
        if exhaustion is not None:
            self._validate_exhaustion(
                document(exhaustion[0], max_bytes=_MAX_HISTORY_BYTES), history
            )
            raise RecoveryAdmissionError(
                "RECOVERY_EXHAUSTED", "the Run has durable Recovery Exhaustion evidence"
            )
        if not history.attempts:
            return history
        previous = history.attempts[-1]
        cause = self._report_cause(previous, history.checkpoints)
        if cause is not None and cause != "interrupted":
            raise RecoveryAdmissionError(
                "RECOVERY_RUN_TERMINAL",
                "the previous attempt reported a terminal outcome",
            )
        evidence = verifier(previous)
        if evidence is None:
            raise RecoveryAdmissionError(
                "RECOVERY_WRITER_UNCERTAIN",
                "the previous writer has not been proven stopped or stripped of write authority",
            )
        self._validate_evidence(asdict(evidence), previous)
        # Proof may arrive after the old writer's final checkpoint/report. Never
        # exhaust or admit using the earlier observation of its history.
        current = self._read("recovery/head.json")
        if current is None or current[1] != self._etag:
            raise RecoveryAdmissionError(
                "RECOVERY_HISTORY_CHANGED",
                "durable progress changed while verifying the previous writer",
            )
        cause = self._report_cause(previous, history.checkpoints)
        if cause is not None and cause != "interrupted":
            raise RecoveryAdmissionError(
                "RECOVERY_RUN_TERMINAL",
                "the stopped attempt reported a terminal outcome",
            )
        self._evidence = evidence
        if history.debt + 1 > self.maximum_debt:
            exhaustion_body = self._exhaustion(history)
            self._immutable("recovery/exhaustion.json", encoded(exhaustion_body))
            raise RecoveryAdmissionError(
                "RECOVERY_EXHAUSTED",
                "the next attempt would exceed the Run's maximum Recovery Debt",
            )
        return history

    def admit(
        self,
        attempt: ExecutionAttemptRecord,
        *,
        external_seed: RecoverySeed | None = None,
    ) -> None:
        with self._lock:
            history = self._required_history()
            if self._active_attempt is not None:
                raise RecoveryAdmissionError(
                    "RECOVERY_ADMISSION_CONFLICT",
                    "this recorder already admitted an attempt",
                )
            if history.attempts and self._evidence is None:
                raise RecoveryAdmissionError(
                    "RECOVERY_WRITER_UNCERTAIN", "previous writer proof is required"
                )
            read_attempt(attempt_document(attempt))
            if (
                attempt.run_id != self.run_id
                or attempt.project_version != self.project_version
            ):
                fail("prospective attempt identity differs from its prepared Run")
            pinned_seed = history.seed if history.attempts else external_seed
            if external_seed is not None:
                read_seed(asdict(external_seed))
            validate_attempt_seed(
                attempt, history.checkpoints, pinned_seed, external_seed
            )
            debt = history.debt + bool(history.attempts)
            if debt > self.maximum_debt:
                raise RecoveryAdmissionError(
                    "RECOVERY_EXHAUSTED", "prospective admission exceeds maximum debt"
                )
            self._append(
                {
                    "kind": "attempt",
                    "attempt": attempt_document(attempt),
                    "admittedDebt": debt,
                    "previousWriter": asdict(self._evidence)
                    if self._evidence
                    else None,
                    "externalSeed": asdict(external_seed)
                    if external_seed is not None
                    else None,
                }
            )
            self._history = replace(
                history,
                attempts=(*history.attempts, attempt),
                debt=debt,
                head=self._required_history().head,
                seed=pinned_seed,
            )
            self._active_attempt = attempt.attempt_id

    def checkpoint(self, step: int, reference: str) -> None:
        with self._lock:
            history = self._required_history()
            if self._active_attempt is None:
                fail("checkpoint publication preceded admission")
            for item in history.checkpoints:
                if item.step == step:
                    if item.reference != reference:
                        fail(
                            "a durable Step already identifies different checkpoint bytes"
                        )
                    return
            self._append(
                {
                    "kind": "checkpoint",
                    "attemptId": self._active_attempt,
                    "step": step,
                    "reference": reference,
                }
            )
            self._history = replace(
                history,
                checkpoints=(
                    *history.checkpoints,
                    CheckpointConfirmation(step, reference),
                ),
                debt=max(0, history.debt - 1),
                head=self._required_history().head,
            )

    def _append(self, fields: dict[str, object]) -> None:
        history = self._required_history()
        event = {
            "schemaVersion": 1,
            "runId": self.run_id,
            "projectVersion": self.project_version,
            "maximumDebt": self.maximum_debt,
            "previous": history.head,
            **fields,
        }
        body = encoded(event)
        if (
            len(body) > _MAX_RECORD_BYTES
            or self._history_bytes + len(body) > _MAX_HISTORY_BYTES
            or self._event_count >= _MAX_EVENTS
        ):
            fail("new journal event exceeds the record or history budget")
        digest = hashlib.sha256(body).hexdigest()
        self._immutable(f"recovery/events/{digest}.json", body)
        head_body = encoded({"schemaVersion": 1, "digest": digest})
        self._exchange("recovery/head.json", head_body, self._etag)
        current = self._read("recovery/head.json")
        if current is None or current[0] != head_body:
            raise RecoveryAdmissionError(
                "RECOVERY_ADMISSION_CONFLICT",
                "recovery head changed during publication",
            )
        self._etag = current[1]
        self._history_bytes += len(body)
        self._event_count += 1
        self._history = replace(history, head=digest)

    def _required_history(self) -> RecoveryHistory:
        if self._history is None:
            raise RecoveryAdmissionError(
                "RECOVERY_HISTORY_INVALID", "recovery was not prepared"
            )
        return self._history

    @staticmethod
    def _validate_evidence(raw: object, previous: ExecutionAttemptRecord) -> None:
        if not isinstance(raw, dict):
            raise RecoveryAdmissionError(
                "RECOVERY_WRITER_UNCERTAIN", "writer proof is not an object"
            )
        raw = cast(dict[str, Any], raw)
        if (
            raw.get("run_id") != previous.run_id
            or raw.get("attempt_id") != previous.attempt_id
            or raw.get("condition") not in {"stopped", "write-authority-revoked"}
            or not isinstance(raw.get("reference"), str)
            or not raw["reference"].strip()
        ):
            raise RecoveryAdmissionError(
                "RECOVERY_WRITER_UNCERTAIN",
                "writer proof does not identify the previous attempt and an authoritative observation",
            )

    def _report_cause(
        self,
        attempt: ExecutionAttemptRecord,
        checkpoints: tuple[CheckpointConfirmation, ...],
    ) -> str | None:
        report = self._verify_report(attempt)
        if report is None:
            return None
        cause, step, reference = report
        if reference is not None and not any(
            c.step == step and c.reference == reference for c in checkpoints
        ):
            fail("termination report names an unconfirmed Durable Safe Point")
        return cause

    def _exhaustion(self, history: RecoveryHistory) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "runId": self.run_id,
            "projectVersion": self.project_version,
            "maximumDebt": self.maximum_debt,
            "prospectiveDebt": history.debt + 1,
            "historyHead": history.head,
            "priorAttempts": [a.attempt_id for a in history.attempts],
            "latestDurableSafePoint": asdict(history.checkpoints[-1])
            if history.checkpoints
            else None,
            "exhaustedAt": self._wall_clock(),
        }

    def _validate_exhaustion(
        self, record: dict[str, Any], history: RecoveryHistory
    ) -> None:
        expected = self._exhaustion(history)
        when = record.get("exhaustedAt")
        if (
            not isinstance(when, int | float)
            or isinstance(when, bool)
            or not math.isfinite(when)
            or when <= 0
        ):
            fail("exhaustion time is invalid")
        expected["exhaustedAt"] = when
        if record != expected or history.debt + 1 <= self.maximum_debt:
            fail("exhaustion record conflicts with the complete history")
