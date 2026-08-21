"""Dataset cursor enforcement for one Training Process."""

import math
from collections.abc import Callable, Iterable
from typing import NoReturn

from skywright._training_errors import SkywrightFailure, TrainingContractViolation
from skywright._training_protocols import DatasetAccess
from skywright._training_types import DatasetBatch, DatasetCursor


class TrackedDatasetAccess:
    def __init__(
        self,
        dataset: DatasetAccess,
        cursor: DatasetCursor,
        violate: Callable[[str, str, str], NoReturn],
        monotonic_clock: Callable[[], float] | None,
    ) -> None:
        self._dataset = dataset
        self._cursor = cursor
        self._violate = violate
        self._clock = monotonic_clock
        self._issued: dict[int, tuple[DatasetBatch, float]] = {}
        self._latest_issued: DatasetBatch | None = None
        self._commit_count = 0
        self._active_iterator: object | None = None

    @property
    def ordering_fingerprint(self) -> str:
        return self._dataset.ordering_fingerprint

    def batches(self, cursor: DatasetCursor) -> Iterable[DatasetBatch]:
        iterator_token = object()
        if self._active_iterator is not None or self._issued:
            self._violate(
                "dataset-cursor/overlapping-iteration",
                "Dataset batches were requested before the prior iterator committed or closed",
                "use one Dataset iterator and commit its final issued batch before starting another",
            )
        self._active_iterator = iterator_token
        if cursor != self._cursor:
            self._violate(
                "dataset-cursor/stale",
                f"Dataset access received {cursor!r} instead of current cursor {self._cursor!r}",
                "request batches using context.dataset_cursor",
            )
        previous = cursor
        step_epoch: int | None = None
        observed_commit_count = self._commit_count
        try:
            iterator = iter(self._dataset.batches(cursor))
            while True:
                wait_started = self._read_clock() if self._clock is not None else None
                try:
                    batch = next(iterator)
                except StopIteration:
                    break
                wait_elapsed = (
                    self._read_clock() - wait_started
                    if wait_started is not None
                    else 0.0
                )
                if not math.isfinite(wait_elapsed) or wait_elapsed < 0:
                    raise SkywrightFailure(
                        ValueError(
                            "the monotonic clock produced an invalid Dataset wait"
                        ),
                        "project",
                    )
                if self._commit_count != observed_commit_count:
                    step_epoch = None
                    observed_commit_count = self._commit_count
                _validate_cursor_advance(previous, batch.next_cursor, self._violate)
                if type(batch.epoch) is not int or batch.epoch < 0:
                    self._violate(
                        "dataset-batch/epoch",
                        f"Dataset batch has invalid item epoch {batch.epoch!r}",
                        "issue batches with the global epoch containing their items",
                    )
                if batch.epoch != previous.epoch:
                    self._violate(
                        "dataset-batch/epoch-transition",
                        "the Dataset batch item epoch does not match its starting cursor",
                        "issue items from the cursor epoch before advancing to the next epoch",
                    )
                if batch.next_cursor.epoch not in (batch.epoch, batch.epoch + 1):
                    self._violate(
                        "dataset-cursor/epoch-transition",
                        "the Dataset batch cursor does not remain in or finish its item epoch",
                        "advance to the same epoch or the immediately following epoch",
                    )
                if batch.next_cursor.ordering_fingerprint != self.ordering_fingerprint:
                    self._violate(
                        "dataset-cursor/fingerprint",
                        "the Dataset batch cursor has a different ordering fingerprint",
                        "use batches issued by the configured Dataset ordering adapter",
                    )
                if step_epoch is None:
                    step_epoch = batch.epoch
                elif batch.epoch != step_epoch:
                    self._violate(
                        "dataset-cursor/epoch-boundary",
                        "the pending Step spans a Dataset epoch boundary",
                        "commit the final batch from one epoch before requesting the next epoch",
                    )
                self._issued[id(batch)] = (batch, wait_elapsed)
                self._latest_issued = batch
                previous = batch.next_cursor
                yield batch
        except (TrainingContractViolation, SkywrightFailure):
            raise
        except Exception as failure:
            raise SkywrightFailure(failure, "project") from failure
        finally:
            if self._active_iterator is iterator_token:
                self._active_iterator = None

    def consume(self, batch: DatasetBatch) -> tuple[DatasetCursor, int, float]:
        issued = self._issued.get(id(batch))
        if issued is None or issued[0] is not batch or self._latest_issued is not batch:
            self._violate(
                "dataset-cursor/not-issued",
                "commit_step() received a batch that was not the latest issued by this Run Context",
                "commit the final Dataset batch whose work completed in this Step",
            )
        item_count = sum(len(candidate.items) for candidate, _ in self._issued.values())
        data_loading_wait = sum(wait for _, wait in self._issued.values())
        self._issued.clear()
        self._latest_issued = None
        self._commit_count += 1
        self._cursor = batch.next_cursor
        return self._cursor, item_count, data_loading_wait

    def _read_clock(self) -> float:
        if self._clock is None:
            raise RuntimeError("Dataset wait timing is disabled")
        try:
            moment = self._clock()
            if isinstance(moment, bool) or not math.isfinite(moment):
                raise ValueError("the monotonic clock produced a non-finite value")
        except Exception as failure:
            raise SkywrightFailure(failure, "project") from failure
        return float(moment)


def validate_cursor_shape(
    cursor: DatasetCursor,
    violate: Callable[[str, str, str], NoReturn],
) -> None:
    values = (cursor.epoch, cursor.item_offset, cursor.epoch_step)
    if any(type(value) is not int or value < 0 for value in values):
        violate(
            "dataset-cursor/shape",
            f"Dataset Cursor {cursor!r} contains an invalid position",
            "commit a cursor containing non-negative integer positions",
        )


def _validate_cursor_advance(
    current: DatasetCursor,
    next_cursor: DatasetCursor,
    violate: Callable[[str, str, str], NoReturn],
) -> None:
    validate_cursor_shape(next_cursor, violate)
    if next_cursor.epoch < current.epoch or (
        next_cursor.epoch == current.epoch
        and (
            next_cursor.item_offset <= current.item_offset
            or next_cursor.epoch_step <= current.epoch_step
        )
    ):
        violate(
            "dataset-cursor/not-advanced",
            f"next Dataset Cursor {next_cursor!r} does not advance {current!r}",
            "commit the cursor supplied by the Dataset batch after its work succeeds",
        )
