"""Bounded storage diagnostics with explicit accounting delivery gaps."""

import threading
import uuid
from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class OperationMeasurement:
    """One adapter operation attempt, identified by producer and sequence."""

    operation: str
    bytes: int
    direction: str
    request_number: int
    run_id: str
    timestamp: float
    provenance: str
    succeeded: bool
    producer_id: str
    sequence: int


@dataclass(frozen=True)
class OperationMeasurementGap:
    """Inclusive identities and time bounds evicted before a consumer drained."""

    first_sequence: int
    last_sequence: int
    earliest_timestamp: float
    latest_timestamp: float

    @property
    def count(self) -> int:
        return self.last_sequence - self.first_sequence + 1


@dataclass(frozen=True)
class OperationMeasurementBatch:
    """Immutable delivery; a gap denotes unknown usage, never zero usage."""

    producer_id: str
    run_id: str
    provenance: str
    through_sequence: int
    measurements: tuple[OperationMeasurement, ...]
    gap: OperationMeasurementGap | None


class OperationMeasurements:
    """Keep recent diagnostics without blocking storage on an absent consumer."""

    def __init__(self, run_id: str, provenance: str, capacity: int = 256) -> None:
        if type(capacity) is not int or capacity < 1:
            raise ValueError("measurement capacity must be a positive integer")
        self._run_id = run_id
        self._provenance = provenance
        self._capacity = capacity
        self._producer_id = str(uuid.uuid4())
        self._sequence = 0
        self._records: deque[OperationMeasurement] = deque()
        self._gap: OperationMeasurementGap | None = None
        self._lock = threading.Lock()

    def record(
        self,
        operation: str,
        transferred: int,
        direction: str,
        attempt: int,
        timestamp: float,
        succeeded: bool,
    ) -> None:
        with self._lock:
            self._sequence += 1
            if len(self._records) == self._capacity:
                evicted = self._records.popleft()
                previous = self._gap
                self._gap = OperationMeasurementGap(
                    previous.first_sequence if previous else evicted.sequence,
                    evicted.sequence,
                    min(previous.earliest_timestamp, evicted.timestamp)
                    if previous
                    else evicted.timestamp,
                    max(previous.latest_timestamp, evicted.timestamp)
                    if previous
                    else evicted.timestamp,
                )
            self._records.append(
                OperationMeasurement(
                    operation,
                    transferred,
                    direction,
                    attempt,
                    self._run_id,
                    timestamp,
                    self._provenance,
                    succeeded,
                    self._producer_id,
                    self._sequence,
                )
            )

    def snapshot(self) -> tuple[OperationMeasurement, ...]:
        with self._lock:
            return tuple(self._records)

    def drain(self) -> OperationMeasurementBatch:
        with self._lock:
            batch = OperationMeasurementBatch(
                self._producer_id,
                self._run_id,
                self._provenance,
                self._sequence,
                tuple(self._records),
                self._gap,
            )
            self._records.clear()
            self._gap = None
            return batch
