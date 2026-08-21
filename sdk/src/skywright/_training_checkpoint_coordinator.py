"""Attempt-scoped checkpoint scheduling and publication."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable

from skywright._training_protocols import TrainingProcessRecorder
from skywright._training_types import CheckpointSnapshot


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
        self._worker: threading.Thread | None = None
        self._failure: Exception | None = None
        self._confirmed = resume_from

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
                self._worker = worker
                worker.start()
            else:
                self._pending = snapshot

    def publish_terminal(
        self, capture: Callable[[], CheckpointSnapshot]
    ) -> CheckpointSnapshot:
        self._discard_pending()
        self._wait_for_active()
        self.raise_if_failed()
        with self._condition:
            confirmed = self._confirmed
        snapshot = capture()
        if confirmed is not None and confirmed.step == snapshot.step:
            return confirmed
        return self._publish_and_confirm(snapshot)

    def stop(self) -> Exception | None:
        self._discard_pending()
        cancel = getattr(self._recorder, "cancel_checkpoint_publication", None)
        if callable(cancel):
            try:
                cancel()
            except Exception as failure:
                self._latch(failure)
        try:
            self._wait_for_active()
        except Exception as failure:
            self._latch(failure)
        with self._condition:
            return self._failure

    def _discard_pending(self) -> None:
        with self._condition:
            self._pending = None

    def _wait_for_active(self) -> None:
        deadline = time.monotonic() + self._shutdown_grace_seconds
        with self._condition:
            while self._active is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(
                        "checkpoint publication exceeded the shutdown grace deadline"
                    )
                self._condition.wait(remaining)

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
                    if self._failure is None:
                        self._failure = failure
                    self._pending = None
                    self._active = None
                    self._worker = None
                    self._condition.notify_all()
                return
            with self._condition:
                if self._pending is None:
                    self._active = None
                    self._worker = None
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
        self._recorder.confirm_checkpoint(published.step, reference)
        with self._condition:
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

    def _latch(self, failure: Exception) -> None:
        with self._condition:
            if self._failure is None:
                self._failure = failure
