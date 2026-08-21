"""Attempt-scoped metric and progress writer behind the Run Store recorder."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable, Mapping
from datetime import datetime, timezone
from pathlib import Path

from skywright._run_store.metric_events import MetricSegment, canonical_json
from skywright._training_errors import ObservabilityShutdownIncomplete
from skywright._training_types import ExecutionAttemptRecord, MetricObservation

SegmentPublisher = Callable[[str, bytes, bytes | None], None]
ProgressPublisher = Callable[[bytes, bytes | None], None]
PeriodicWait = Callable[[threading.Event, float], bool]


def wait_for_flush(stop: threading.Event, interval: float) -> bool:
    return stop.wait(interval)


class MetricProgressWriter:
    """Serialize event mutations, segment publication, and progress replacement."""

    def __init__(
        self,
        *,
        attempt: ExecutionAttemptRecord,
        configuration: Mapping[str, object],
        flush_interval: float,
        segment_roll: int,
        segment_key: Callable[[int], str],
        publish_segment: SegmentPublisher,
        publish_progress: ProgressPublisher,
        configuration_already_exported: bool,
        staging_directory: Path | None,
        wall_clock: Callable[[], float],
        periodic_wait: PeriodicWait = wait_for_flush,
    ) -> None:
        self._attempt = attempt
        self._flush_interval = flush_interval
        self._segment_roll = segment_roll
        self._segment_key = segment_key
        self._publish_segment = publish_segment
        self._publish_progress = publish_progress
        self._staging_directory = staging_directory
        self._wall_clock = wall_clock
        self._periodic_wait = periodic_wait
        self._configuration = (
            None if configuration_already_exported else canonical_json(configuration)
        )
        self._lock = threading.RLock()
        self._interaction = threading.RLock()
        self._publication = threading.Lock()
        self._stop = threading.Event()
        self._failure: Exception | None = None
        self._segment_number = 0
        self._segment: MetricSegment | None = None
        self._published_segment: bytes | None = None
        self._segment_sealed = False
        self._published_progress: bytes | None = None
        self._current_step = attempt.seed_checkpoint_step or 0
        self._durable_step = attempt.seed_checkpoint_step
        self._durable_checkpoint = attempt.seed_checkpoint_reference
        self._closed = False
        self._start_segment()
        self._thread = threading.Thread(
            target=self._periodic_flush,
            name=f"skywright-metrics-{attempt.attempt_id}",
            daemon=True,
        )
        self._thread.start()

    def publish_step(
        self,
        step: int,
        observations: tuple[MetricObservation, ...],
        latest_durable_step: int | None,
        latest_durable_checkpoint: str | None,
    ) -> None:
        with self._interaction:
            rolled = False
            with self._lock:
                self._raise_failure()
                self._require_open()
            for observation in observations:
                with self._lock:
                    needs_roll = self._append(observation, self._wall_clock())
                if needs_roll:
                    self._flush()
                    with self._lock:
                        self._segment_sealed = True
                    rolled = True
            with self._lock:
                self._current_step = step
                self._durable_step = latest_durable_step
                self._durable_checkpoint = latest_durable_checkpoint
            if rolled:
                self._flush()

    def publish_wall_time(
        self, observation: MetricObservation, accepted_at: float
    ) -> None:
        with self._interaction:
            with self._lock:
                self._raise_failure()
                self._require_open()
                needs_roll = self._append(observation, accepted_at)
            if needs_roll:
                self._flush()
                with self._lock:
                    self._segment_sealed = True

    def confirm_checkpoint(self, step: int, reference: str) -> None:
        with self._interaction:
            with self._lock:
                self._raise_failure()
                self._require_open()
                self._durable_step = step
                self._durable_checkpoint = reference
            self._flush()

    def finalize(self, deadline: float | None = None) -> None:
        with self._interaction, self._lock:
            if self._closed:
                self._raise_failure()
                return
            self._closed = True
        self._stop.set()
        remaining = None if deadline is None else max(deadline - time.monotonic(), 0)
        self._thread.join(remaining)
        if self._thread.is_alive():
            raise ObservabilityShutdownIncomplete(
                "metric publication exceeded the shutdown grace deadline"
            )
        with self._lock:
            self._raise_failure()

    def _append(self, observation: MetricObservation, wall_time: float) -> bool:
        if self._segment_sealed:
            assert self._segment is not None
            self._segment.close()
            self._segment_number += 1
            self._published_segment = None
            self._segment_sealed = False
            self._start_segment()
        assert self._segment is not None
        self._segment.append(observation, wall_time)
        return self._segment.scalar_count >= self._segment_roll

    def _start_segment(self) -> None:
        self._segment = MetricSegment(
            wall_time=self._wall_clock(),
            staging_directory=self._staging_directory,
            purge_step=(
                self._attempt.seed_checkpoint_step
                if self._segment_number == 0
                else None
            ),
            configuration=(self._configuration if self._segment_number == 0 else None),
        )

    def _flush(self) -> None:
        with self._interaction:
            self._publication.acquire()
            with self._lock:
                assert self._segment is not None
                segment_number = self._segment_number
                body = self._segment.bytes()
                previous_segment = self._published_segment
                progress_state = (
                    self._current_step,
                    self._durable_step,
                    self._durable_checkpoint,
                )
                previous_progress = self._published_progress
        try:
            if body != previous_segment:
                self._publish_segment(
                    self._segment_key(segment_number),
                    body,
                    previous_segment,
                )
            progress = self._progress_bytes(*progress_state)
            self._publish_progress(progress, previous_progress)
            with self._lock:
                if (
                    self._segment_number == segment_number
                    and self._published_segment == previous_segment
                ):
                    self._published_segment = body
                if self._published_progress == previous_progress:
                    self._published_progress = progress
        finally:
            self._publication.release()

    def _progress_bytes(
        self,
        current_step: int,
        durable_step: int | None,
        durable_checkpoint: str | None,
    ) -> bytes:
        written_at = (
            datetime.fromtimestamp(self._wall_clock(), timezone.utc)
            .isoformat(timespec="microseconds")
            .replace("+00:00", "Z")
        )
        fields: dict[str, object] = {
            "schemaVersion": 1,
            "runId": self._attempt.run_id,
            "currentStep": current_step,
            "latestDurableStep": durable_step,
            "latestDurableCheckpoint": durable_checkpoint,
            "writtenAt": written_at,
        }
        return canonical_json(fields).encode("utf-8")

    def _periodic_flush(self) -> None:
        try:
            while not self._periodic_wait(self._stop, self._flush_interval):
                self._flush()
            self._flush()
        except Exception as failure:
            with self._lock:
                self._failure = failure
        finally:
            with self._lock:
                if self._segment is not None:
                    self._segment.close()

    def _raise_failure(self) -> None:
        if self._failure is not None:
            raise self._failure

    def _require_open(self) -> None:
        if self._closed:
            raise RuntimeError("metric and progress writer is closed")
