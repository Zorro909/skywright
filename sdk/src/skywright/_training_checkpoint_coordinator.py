"""Attempt-scoped checkpoint scheduling and publication."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from skywright._training_errors import CheckpointPublicationCancelled
from skywright._training_protocols import TrainingProcessRecorder
from skywright._training_types import CheckpointSnapshot


@dataclass(frozen=True)
class CheckpointShutdown:
    """Result of bounded attempt-owned checkpoint cleanup."""

    failure: Exception | None
    stopped: bool


class CheckpointCoordinator:
    """Own one active publication, one replaceable pending snapshot, and confirmation."""

    def __init__(
        self,
        recorder: TrainingProcessRecorder,
        resume_from: CheckpointSnapshot | None,
        shutdown_grace_seconds: float,
    ) -> None:
        self._recorder = recorder
        self._shutdown_grace_seconds = shutdown_grace_seconds
        self._condition = threading.Condition()
        self._active: CheckpointSnapshot | None = None
        self._pending: CheckpointSnapshot | None = None
        self._failure: Exception | None = None
        self._confirmed = resume_from
        self._cancelling_active = False
        self._shutdown_deadline: float | None = None

    def durable_state(self) -> tuple[int | None, str | None]:
        """Read the confirmed Step and reference under one synchronization boundary."""
        with self._condition:
            if self._confirmed is None:
                return None, None
            return self._confirmed.step, self._confirmed.reference

    def raise_if_failed(self) -> None:
        with self._condition:
            failure = self._failure
        if failure is not None:
            raise failure

    def schedule(self, snapshot: CheckpointSnapshot) -> None:
        with self._condition:
            if self._failure is not None:
                raise self._failure
            if self._active is None:
                self._active = snapshot
                worker = threading.Thread(
                    target=self._publish_scheduled,
                    name="skywright-checkpoint-publisher",
                    daemon=True,
                )
                worker.start()
            else:
                self._pending = snapshot

    def publish_terminal(
        self,
        capture: Callable[[], CheckpointSnapshot],
        cancellation_requested: Callable[[], bool] | None = None,
    ) -> CheckpointSnapshot | None:
        self._begin_shutdown()
        self._discard_pending()
        if self._wait_for_active(cancellation_requested):
            self._resume_after_cancellation()
            return None
        self.raise_if_failed()
        with self._condition:
            confirmed = self._confirmed
        snapshot = capture()
        if confirmed is not None and confirmed.step == snapshot.step:
            return confirmed
        self.schedule(snapshot)
        if self._wait_for_active(cancellation_requested):
            self._resume_after_cancellation()
            return None
        self.raise_if_failed()
        with self._condition:
            confirmed = self._confirmed
        if confirmed is None or confirmed.step != snapshot.step:
            raise RuntimeError("terminal checkpoint publication was not confirmed")
        return confirmed

    def stop(self) -> CheckpointShutdown:
        self._begin_shutdown()
        self._discard_pending()
        self._request_active_cancellation()
        try:
            self._wait_for_active()
        except Exception as failure:
            self._latch(failure)
        with self._condition:
            stopped = self._active is None
        if stopped:
            self._resume_after_cancellation()
        with self._condition:
            return CheckpointShutdown(self._failure, stopped)

    def _discard_pending(self) -> None:
        with self._condition:
            self._pending = None

    def _wait_for_active(
        self, cancellation_requested: Callable[[], bool] | None = None
    ) -> bool:
        cancellation_observed = False
        while True:
            if cancellation_requested is not None and cancellation_requested():
                cancellation_observed = True
                self._request_active_cancellation()
            with self._condition:
                if self._active is None:
                    return cancellation_observed
                deadline = self._shutdown_deadline
                if deadline is None:
                    raise RuntimeError(
                        "checkpoint shutdown deadline was not established"
                    )
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(
                        "checkpoint publication exceeded the shutdown grace deadline"
                    )
                self._condition.wait(min(remaining, 0.05))

    def _publish_scheduled(self) -> None:
        while True:
            with self._condition:
                snapshot = self._active
            if snapshot is None:
                return
            try:
                self._publish_and_confirm(snapshot)
            except Exception as failure:
                with self._condition:
                    expected_cancellation = self._cancelling_active and isinstance(
                        failure, CheckpointPublicationCancelled
                    )
                    if self._failure is None and not expected_cancellation:
                        self._failure = failure
                    self._pending = None
                    self._active = None
                    self._condition.notify_all()
                return
            with self._condition:
                if self._pending is None:
                    self._active = None
                    self._condition.notify_all()
                    return
                self._active = self._pending
                self._pending = None

    def _publish_and_confirm(self, snapshot: CheckpointSnapshot) -> CheckpointSnapshot:
        reference = self._recorder.publish_checkpoint(snapshot)
        if not reference:
            raise ValueError("checkpoint publisher returned an empty reference")
        published = snapshot.with_reference(reference)
        with self._condition:
            current = self._confirmed
            if current is not None:
                if published.step < current.step:
                    return current
                if published.step == current.step:
                    if published.reference != current.reference:
                        raise ValueError(
                            "the same Durable Safe Point has conflicting references"
                        )
                    return current
        with self._condition:
            self._recorder.confirm_checkpoint(published.step, reference)
            current = self._confirmed
            if current is None or published.step > current.step:
                self._confirmed = published
                return published
            if (
                published.step == current.step
                and published.reference == current.reference
            ):
                return current
            return current

    def _begin_shutdown(self) -> None:
        with self._condition:
            if self._shutdown_deadline is None:
                self._shutdown_deadline = (
                    time.monotonic() + self._shutdown_grace_seconds
                )

    def _request_active_cancellation(self) -> None:
        with self._condition:
            if self._active is None or self._cancelling_active:
                return
            self._cancelling_active = True
        cancel = getattr(self._recorder, "cancel_checkpoint_publication", None)
        if callable(cancel):
            try:
                cancel()
            except Exception as failure:
                self._latch(failure)

    def _resume_after_cancellation(self) -> None:
        with self._condition:
            if not self._cancelling_active or self._active is not None:
                return
        resume = getattr(self._recorder, "resume_after_checkpoint_cancellation", None)
        if callable(resume):
            try:
                resume()
            except Exception as failure:
                self._latch(failure)
        with self._condition:
            self._cancelling_active = False

    def _latch(self, failure: Exception) -> None:
        with self._condition:
            if self._failure is None:
                self._failure = failure
