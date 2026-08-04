"""PROTOTYPE — throwaway. The candidate Run Context (ADR 0001).

THE QUESTION (issue #20): Skywright is a contract-enforcing toolkit, not a
training framework. The project owns its loop; an explicit Run Context owns
configuration, datasets, checkpoint/resume, metrics and persistence. That is
agreed in prose. This file is the smallest concrete API that could implement
it, so it can be judged in the hand rather than in the abstract:

  - Does the contract feel right?
  - Does B3 hold — does the script still run standalone under a debugger?
  - Does B4 bite early and clearly when the contract is misused?

Nothing here is proposed for the product. It exists to be reacted to.
"""

from __future__ import annotations

import random
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Literal

import torch

from .config import Config, resolve
from .dataset import Cursor, Dataset
from .errors import ContractError
from .runstore import RunStore

Phase = Literal["registering", "running", "finished", "interrupted"]


# ---------------------------------------------------------------------------
# Metrics (O1). See docs/research/tensorboard-metric-contract.md: TensorBoard is
# the event sink; this small schema is the part TensorBoard deliberately does
# not model, so Skywright must own it.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class MetricSpec:
    name: str
    value_type: Literal["float", "int"] = "float"
    unit: str = "dimensionless"
    reduction: Literal["mean", "sum", "last", "min", "max"] = "mean"
    comparison: Literal["minimize", "maximize", "none"] = "none"
    description: str = ""

    def as_dict(self) -> dict:
        return dict(self.__dict__)


LIBRARY_METRICS = [
    MetricSpec("sys/step_seconds", unit="seconds", comparison="minimize",
               description="Wall time of one committed Step (O4)."),
    MetricSpec("sys/samples_per_second", unit="samples/s", comparison="maximize",
               description="Throughput over the last committed Step (O4)."),
]


# ---------------------------------------------------------------------------
# Checkpoint State (C2)
# ---------------------------------------------------------------------------


class RngState:
    """A standard piece of Checkpoint State the library supplies, because a project
    that has to remember to checkpoint its RNG by hand will eventually not."""

    def state_dict(self) -> dict:
        return {"torch": torch.get_rng_state(), "python": random.getstate()}

    def load_state_dict(self, state: dict) -> None:
        torch.set_rng_state(state["torch"])
        random.setstate(state["python"])


@dataclass
class _Registration:
    name: str
    obj: Any
    owner: Literal["project", "library"]


# ---------------------------------------------------------------------------
# Run Context
# ---------------------------------------------------------------------------


@dataclass
class RunDefinition:
    """ADR 0002: immutable, fully resolved. Constructed here rather than fetched."""

    run_id: str
    project_version: str
    config: Config
    dataset: Dataset
    seed: int = 0
    checkpoint_every: int = 25
    sample_every: int = 100
    max_steps: int = 400


class RunContext:
    def __init__(self, definition: RunDefinition, store: RunStore,
                 preempt: Callable[[], bool] | None = None):
        self.definition = definition
        self.store = store
        self.config = definition.config
        self.dataset = definition.dataset
        self.phase: Phase = "registering"
        self.step = 0
        self.cursor = Cursor()
        self.resumed_from: int | None = None
        self.resume_notes: list[str] = []
        self.stop_reason: str | None = None
        self._preempt = preempt or (lambda: False)
        self._state: dict[str, _Registration] = {}
        self._metrics: dict[str, MetricSpec] = {}
        self._pending: dict[str, float] = {}
        self._last_values: dict[str, float] = {}
        self._step_started: float | None = None
        self._samples_this_step = 0

        # The library owns determinism setup, before the project constructs anything
        # random. A Training Project never calls manual_seed itself — if it did,
        # D5 would depend on where in the project's file that call happened to sit.
        torch.manual_seed(definition.seed)
        random.seed(definition.seed)

        # The library registers its own resumable state up front.
        self._state["__rng"] = _Registration("__rng", RngState(), "library")
        for spec in LIBRARY_METRICS:
            self._metrics[spec.name] = spec

    # --- declaration phase ------------------------------------------------

    def register_state(self, name: str, obj: Any) -> None:
        """C2. Everything resumable is declared before training begins."""
        if self.phase != "registering":
            raise ContractError(
                "C2/late-registration",
                f"Checkpoint State {name!r} was registered after the run started",
                "register every resumable object before start(); a checkpoint written "
                "earlier in this run could not have contained it",
            )
        if name in self._state:
            raise ContractError(
                "C2/duplicate",
                f"Checkpoint State {name!r} is already registered",
                "use a distinct name — silently replacing one would silently drop the other",
            )
        for method in ("state_dict", "load_state_dict"):
            if not callable(getattr(obj, method, None)):
                raise ContractError(
                    "C2/not-resumable",
                    f"Checkpoint State {name!r} ({type(obj).__name__}) has no {method}()",
                    "register an object the library can serialize and restore, or wrap it",
                )
        self._state[name] = _Registration(name, obj, "project")

    def declare_metric(self, spec: MetricSpec) -> None:
        """O1. Undeclared metrics are rejected at record time; this is the declaration."""
        if self.phase != "registering":
            raise ContractError(
                "O1/late-declaration",
                f"Metric {spec.name!r} was declared after the run started",
                "declare every metric before start(); a metric that appears mid-run is "
                "not comparable across runs (O6)",
            )
        existing = self._metrics.get(spec.name)
        if existing and existing != spec:
            raise ContractError(
                "O1/conflicting-declaration",
                f"Metric {spec.name!r} is already declared with different semantics",
                f"existing: {existing.as_dict()}",
            )
        self._metrics[spec.name] = spec

    # --- execution --------------------------------------------------------

    @contextmanager
    def start(self):
        if self.phase != "registering":
            raise ContractError("B4/lifecycle", "start() called twice", "one Run Context, one run")
        project_state = [r for r in self._state.values() if r.owner == "project"]
        if not project_state:
            raise ContractError(
                "C2/empty",
                "the run started with no project Checkpoint State registered",
                "register at least the model and optimizer — without them C1 cannot hold",
            )
        project_metrics = [n for n in self._metrics if not n.startswith("sys/")]
        if not project_metrics:
            raise ContractError(
                "O1/empty",
                "the run started with no project metric declared",
                "declare at least one metric — a run with no metrics is not comparable (O6)",
            )

        self._restore()
        self.store.write_metric_catalog([s.as_dict() for s in self._metrics.values()])
        self.phase = "running"
        try:
            yield self
        finally:
            if self.phase == "running":
                self.phase = "finished" if self.stop_reason is None else "interrupted"

    def _restore(self) -> None:
        payload, notes = self.store.load_latest_checkpoint()
        self.resume_notes = notes
        if payload is None:
            return
        if payload["project_version"] != self.definition.project_version:
            raise ContractError(
                "C1/version-mismatch",
                f"the Run Store holds a checkpoint from Training Project Version "
                f"{payload['project_version']!r}, but this run is "
                f"{self.definition.project_version!r}",
                "resume is only defined within one Training Project Version; clone the "
                "run instead (R5)",
            )
        missing = set(payload["state"]) - set(self._state)
        extra = set(self._state) - set(payload["state"])
        if missing or extra:
            raise ContractError(
                "C2/shape-drift",
                f"registered Checkpoint State does not match the checkpoint "
                f"(missing here: {sorted(missing)}, new here: {sorted(extra)})",
                "a resumed run must register exactly what it registered before",
            )
        for name, blob in payload["state"].items():
            self._state[name].obj.load_state_dict(blob)
        self.step = payload["step"]
        self.cursor.load_state_dict(payload["cursor"])
        self.resumed_from = self.step

    def batches(self, batch_size: int):
        """The project's data source. Stops iterating at a safe point on interruption."""
        if self.phase != "running":
            raise ContractError(
                "B4/lifecycle",
                "batches() was requested outside start()",
                "the Run Context resolves the Dataset Location and restores the cursor "
                "in start(); iterating before that would read from an unresolved dataset",
            )
        for batch in self.dataset.batches(self.cursor, batch_size):
            if self.stop_reason or self.step >= self.definition.max_steps:
                return
            self._step_started = time.perf_counter()
            self._samples_this_step = len(batch.sample_ids)
            yield batch

    def record(self, name: str, value: float) -> None:
        """O1. The only project-facing metric writer — there is no raw SummaryWriter to bypass it."""
        if self.phase != "running":
            raise ContractError("B4/lifecycle", f"record({name!r}) outside start()",
                                "record metrics inside the run")
        spec = self._metrics.get(name)
        if spec is None:
            raise ContractError(
                "O1/undeclared",
                f"metric {name!r} was recorded but never declared",
                f"declared metrics are {sorted(self._metrics)}",
            )
        if isinstance(value, torch.Tensor):
            if value.numel() != 1:
                raise ContractError(
                    "O1/not-scalar",
                    f"metric {name!r} got a tensor with {value.numel()} elements",
                    "record a scalar; reduce it in the project where the semantics are known",
                )
            value = value.item()
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise ContractError("O1/not-scalar", f"metric {name!r} got {type(value).__name__}",
                                "record an int or float")
        if value != value or value in (float("inf"), float("-inf")):
            raise ContractError(
                "O1/non-finite",
                f"metric {name!r} got {value}",
                "a non-finite metric is a program error, not a datapoint — a diverged run "
                "should fail loudly rather than log NaN for six hours",
            )
        if spec.value_type == "int" and float(value) != int(value):
            raise ContractError("O1/type", f"metric {name!r} is declared int but got {value}",
                                "record an integral value or redeclare the metric")
        if name in self._pending:
            raise ContractError(
                "O1/twice-in-one-step",
                f"metric {name!r} was recorded twice within Step {self.step}",
                f"one value per metric per Step; its declared reduction is "
                f"{spec.reduction!r} but reduction across Steps is not the same question "
                "as two values inside one",
            )
        self._pending[name] = float(value)

    def commit_step(self) -> None:
        """The safe point. The library flushes metrics, applies checkpoint cadence,
        persists progress, and honors interruption — without owning the loop."""
        if self.phase != "running":
            raise ContractError("B4/lifecycle", "commit_step() outside start()",
                                "commit Steps inside the run")
        if self._step_started is None:
            raise ContractError(
                "B4/step-without-work",
                f"commit_step() was called twice for Step {self.step}",
                "one commit per batch — the Step is the unit of committed progress",
            )
        elapsed = time.perf_counter() - self._step_started
        self._pending["sys/step_seconds"] = elapsed
        self._pending["sys/samples_per_second"] = self._samples_this_step / max(elapsed, 1e-9)

        wall = time.time()
        self.store.append_metric_events(
            [{"tag": k, "step": self.step, "wall_time": wall, "value": v}
             for k, v in self._pending.items()]
        )
        self._last_values.update(self._pending)
        self._pending.clear()
        self._step_started = None
        self.step += 1

        if self.step % self.definition.checkpoint_every == 0:
            self.checkpoint()
        if self._preempt():
            self.stop_reason = "preempted"
            self.checkpoint()

    def checkpoint(self) -> Path:
        """C3/C5/C6 are the Run Store's problem, not the project's."""
        return self.store.write_checkpoint(self.step, {
            "run_id": self.definition.run_id,
            "project_version": self.definition.project_version,
            "step": self.step,
            "cursor": self.cursor.state_dict(),
            "state": {name: reg.obj.state_dict() for name, reg in self._state.items()},
        })

    def last_value(self, name: str) -> float | None:
        """Read back the last committed value of a declared metric (for the project's own logging)."""
        if name not in self._metrics:
            raise ContractError("O1/undeclared", f"metric {name!r} was read but never declared",
                                f"declared metrics are {sorted(self._metrics)}")
        return self._last_values.get(name)

    def save_sample(self, name: str, image: torch.Tensor) -> Path:
        return self.store.write_sample(self.step, name, image)

    def save_artifact(self, name: str, data: bytes) -> Path:
        return self.store.write_artifact(name, data)

    # --- introspection, for the prototype driver --------------------------

    def snapshot(self) -> dict:
        return {
            "run_id": self.definition.run_id,
            "project_version": self.definition.project_version,
            "phase": self.phase,
            "step": self.step,
            "max_steps": self.definition.max_steps,
            "stop_reason": self.stop_reason,
            "resumed_from": self.resumed_from,
            "resume_notes": self.resume_notes,
            "cursor": self.cursor.state_dict(),
            "dataset": f"{self.dataset.definition_id}@{self.dataset.version}",
            "location": self.dataset.resolved_location,
            "state_keys": sorted(self._state),
            "metrics": sorted(self._metrics),
            "last_values": dict(self._last_values),
            "pending": dict(self._pending),
            "checkpoints": [p.name for p in self.store.checkpoints()],
        }


def local_run_context(
    *,
    run_id: str,
    project_version: str,
    schema: dict,
    defaults: dict,
    overrides: dict | None = None,
    store_root: Path,
    preempt: Callable[[], bool] | None = None,
    **definition_kwargs,
) -> RunContext:
    """B3. The standalone entry point: the same script, with no orchestration layer.

    Under orchestration this would instead be handed an already-resolved Run
    Definition (ADR 0002). The project-facing object is identical either way —
    that identity is exactly what B3 asks for.
    """
    config = resolve(schema, defaults, overrides or {})
    dataset = Dataset(
        definition_id=config["data"]["dataset"],
        version=config["data"]["version"],
        size=config["data"]["size"],
        seed=config["seed"],
    )
    # NOTE: `seed` sits in the *project's* schema here, which is probably wrong —
    # ADR 0001 splits Run Configuration into library-defined common options and
    # project-defined options, and D5 makes the seed a library concern. Left as-is
    # so the prototype shows the seam rather than hiding it.
    definition = RunDefinition(run_id, project_version, config, dataset,
                               seed=config["seed"], **definition_kwargs)
    return RunContext(definition, RunStore(store_root), preempt=preempt)
