"""Library-owned System Metric collection for one Execution Attempt."""

import math
import threading
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import cast

from skywright._training_clock import read_monotonic
from skywright._training_errors import SkywrightFailure
from skywright._training_types import MetricDefinition, MetricObservation

THROUGHPUT = "skywright/system/throughput"
DATA_LOADING_WAIT = "skywright/system/data_loading_wait"
MEMORY_USED = "skywright/system/memory_used"

SamplerWait = Callable[[threading.Event, float], bool]


@dataclass(frozen=True)
class SamplerShutdown:
    failure: Exception | None
    stopped: bool


def wait_for_sampling(stop: threading.Event, interval: float) -> bool:
    return stop.wait(interval)


def system_sampling_interval(configuration: Mapping[str, object]) -> float:
    metrics = configuration.get("metrics", {})
    interval: object = 10
    if isinstance(metrics, Mapping):
        interval = cast(Mapping[str, object], metrics).get("systemSamplingInterval", 10)
    if (
        isinstance(interval, bool)
        or not isinstance(interval, int | float)
        or not math.isfinite(interval)
        or interval <= 0
    ):
        raise SkywrightFailure(
            ValueError("metrics.systemSamplingInterval is not positive and finite"),
            "construction",
        )
    return float(interval)


def validate_system_metric_definitions(
    definitions: tuple[MetricDefinition, ...],
) -> None:
    expected = {
        THROUGHPUT: (
            "real",
            "items_per_second",
            "step",
            "maximize",
            "mean",
            0,
            None,
        ),
        DATA_LOADING_WAIT: (
            "real",
            "seconds",
            "step",
            "minimize",
            "sum",
            0,
            None,
        ),
        MEMORY_USED: (
            "integer",
            "bytes",
            "wall_time",
            "none",
            None,
            0,
            None,
        ),
    }
    names = {definition.name for definition in definitions}
    if names != set(expected):
        raise SkywrightFailure(
            ValueError(
                "System Metric definitions do not match the runtime collector: "
                f"expected {sorted(expected)!r}, received {sorted(names)!r}"
            ),
            "construction",
        )
    for definition in definitions:
        semantics = (
            definition.numeric_kind,
            definition.unit,
            definition.recording_basis,
            definition.comparison,
            definition.step_reduction,
            definition.minimum,
            definition.maximum,
        )
        if definition.name not in expected or semantics != expected[definition.name]:
            raise SkywrightFailure(
                ValueError(
                    f"System Metric definition {definition.name!r} does not match "
                    "the runtime collector"
                ),
                "construction",
            )


class StepSystemMetrics:
    """Produce provisional Step observations from runtime-owned measurements."""

    def __init__(
        self,
        definitions: tuple[MetricDefinition, ...],
        monotonic_clock: Callable[[], float],
    ) -> None:
        self._definitions = tuple(
            definition
            for definition in definitions
            if definition.name in (THROUGHPUT, DATA_LOADING_WAIT)
        )
        self._clock = monotonic_clock
        self._interval_start: float | None = None

    @property
    def enabled(self) -> bool:
        return bool(self._definitions)

    def start(self) -> None:
        if self._definitions:
            self._interval_start = read_monotonic(self._clock)

    def prepare(
        self,
        step: int,
        item_count: int,
        data_loading_wait: float,
    ) -> tuple[tuple[MetricObservation, ...], float | None]:
        if not self._definitions:
            return (), None
        interval_start = self._interval_start
        if interval_start is None:
            raise SkywrightFailure(
                RuntimeError(
                    "Step metric timing started without a Run Context interval"
                ),
                "project",
            )
        interval_end = read_monotonic(self._clock)
        elapsed = interval_end - interval_start
        if not math.isfinite(elapsed) or elapsed <= 0:
            raise SkywrightFailure(
                ValueError("the monotonic clock produced a non-positive Step interval"),
                "project",
            )
        values = {
            THROUGHPUT: item_count / elapsed,
            DATA_LOADING_WAIT: data_loading_wait,
        }
        observations = tuple(
            self._observation(definition, step, values[definition.name])
            for definition in self._definitions
        )
        return observations, interval_end

    def committed(self, interval_end: float | None) -> None:
        if interval_end is not None:
            self._interval_start = interval_end

    @staticmethod
    def _observation(
        definition: MetricDefinition, step: int, value: int | float
    ) -> MetricObservation:
        if not math.isfinite(value):
            raise SkywrightFailure(
                ValueError(f"System Metric {definition.name!r} is not finite"),
                "project",
            )
        if definition.minimum is not None and value < definition.minimum:
            raise SkywrightFailure(
                ValueError(f"System Metric {definition.name!r} is below its minimum"),
                "project",
            )
        if definition.maximum is not None and value > definition.maximum:
            raise SkywrightFailure(
                ValueError(f"System Metric {definition.name!r} is above its maximum"),
                "project",
            )
        return MetricObservation(definition.name, step, value)


class MemorySystemMetrics:
    """Sample cgroup memory at wall-time intervals owned by the runtime."""

    def __init__(
        self,
        definitions: tuple[MetricDefinition, ...],
        interval: float,
        read_memory: Callable[[], int | None],
        wait: SamplerWait,
        current_step: Callable[[], int],
        publish: Callable[[MetricObservation], None],
        publication_lock: threading.Lock,
    ) -> None:
        self._definition = next(
            (item for item in definitions if item.name == MEMORY_USED), None
        )
        self._interval = interval
        self._read_memory = read_memory
        self._wait = wait
        self._current_step = current_step
        self._publish = publish
        self._publication_lock = publication_lock
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._failure: Exception | None = None
        self._observations: list[MetricObservation] = []
        self._worker: threading.Thread | None = None

    @property
    def observations(self) -> tuple[MetricObservation, ...]:
        with self._lock:
            return tuple(self._observations)

    def start(self) -> None:
        if self._definition is None:
            return
        self._worker = threading.Thread(target=self._run, daemon=True)
        self._worker.start()

    def raise_if_failed(self) -> None:
        with self._lock:
            failure = self._failure
        if failure is not None:
            raise failure

    def stop(self, timeout: float) -> SamplerShutdown:
        self._stop.set()
        worker = self._worker
        if worker is not None:
            worker.join(timeout)
        stopped = worker is None or not worker.is_alive()
        with self._lock:
            failure = self._failure
        if not stopped and failure is None:
            failure = TimeoutError("System Metric sampler did not stop")
        return SamplerShutdown(failure, stopped)

    def _run(self) -> None:
        try:
            while not self._wait(self._stop, self._interval):
                value = self._read_memory()
                if value is None:
                    continue
                with self._publication_lock:
                    observation = self._memory_observation(value)
                    self._publish(observation)
                with self._lock:
                    self._observations.append(observation)
        except Exception as failure:
            with self._lock:
                self._failure = failure

    def _memory_observation(self, value: object) -> MetricObservation:
        definition = self._definition
        if definition is None:
            raise RuntimeError("memory sampling started without a Metric Definition")
        if type(value) is not int:
            raise ValueError("cgroup memory usage is not an integer")
        if definition.minimum is not None and value < definition.minimum:
            raise ValueError("cgroup memory usage is below the declared minimum")
        if definition.maximum is not None and value > definition.maximum:
            raise ValueError("cgroup memory usage is above the declared maximum")
        return MetricObservation(definition.name, self._current_step(), value)
