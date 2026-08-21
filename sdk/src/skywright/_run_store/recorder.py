"""Training Process recorder backed by the Run Store implementation."""

from typing import Protocol, cast

from skywright._run_store.implementation import RunStoreRecorder as _RunStoreRecorder
from skywright._training_types import MetricObservation


class _WallTimeMetricRecorder(Protocol):
    def publish_wall_time(self, observation: MetricObservation) -> None: ...


class RunStoreRecorder(_RunStoreRecorder):
    """Run Store recorder extended with wall-time metric publication."""

    def publish_wall_time(self, observation: MetricObservation) -> None:
        self._require_open()
        progress = cast(_WallTimeMetricRecorder | None, self._progress)
        if progress is not None:
            progress.publish_wall_time(observation)
