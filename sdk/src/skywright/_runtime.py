"""Private operational entry point for the Skywright runtime."""

from __future__ import annotations

import argparse
import importlib
import json
from collections.abc import Mapping
from importlib.metadata import version
from pathlib import Path
from typing import TypedDict, cast

from skywright._build_info import SOURCE_REVISION
from skywright._training import (
    Accelerator,
    Comparison,
    MetricDefinition,
    NumericKind,
    StepReduction,
    TrainingProcessOutcome,
    TrainingProject,
    run_training_process,
)

_EXIT_CODES = {
    TrainingProcessOutcome.COMPLETED: 0,
    TrainingProcessOutcome.INTERRUPTED: 75,
    TrainingProcessOutcome.CANCELLED: 64,
    TrainingProcessOutcome.FAILED: 1,
}


class _RuntimeDefinition(TypedDict):
    run_id: str
    project_version: str
    configuration: Mapping[str, object]
    dataset: list[object]
    metric_definitions: tuple[MetricDefinition, ...]
    seed: int
    accelerator: Accelerator


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="skywright-runtime",
        description="Execute a Skywright Training Project.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--version",
        action="version",
        version=(
            f"skywright-runtime {version('skywright')}\n"
            f"source revision: {SOURCE_REVISION}"
        ),
    )
    parser.add_argument(
        "entry_point",
        nargs="?",
        metavar="MODULE:CALLABLE",
        help="Training Project entry point to execute",
    )
    parser.add_argument(
        "--definition",
        type=Path,
        help="resolved JSON runtime definition",
    )
    arguments = parser.parse_args()
    if arguments.entry_point is None or arguments.definition is None:
        parser.error("MODULE:CALLABLE and --definition are required for training")

    try:
        entry_point = _load_entry_point(cast(str, arguments.entry_point))
        definition = _load_definition(cast(Path, arguments.definition))
    except (ImportError, AttributeError, OSError, TypeError, ValueError) as failure:
        parser.error(str(failure))

    result = run_training_process(
        entry_point,
        configuration=definition["configuration"],
        dataset=definition["dataset"],
        metric_definitions=definition["metric_definitions"],
        seed=definition["seed"],
        accelerator=definition["accelerator"],
        run_id=definition["run_id"],
        project_version=definition["project_version"],
    )
    print(
        json.dumps(
            {
                "attempt_id": result.attempt.attempt_id,
                "cause": result.report.cause.value,
                "diagnostics": dict(result.report.diagnostics),
                "last_committed_step": result.report.last_committed_step,
                "latest_durable_step": result.report.latest_durable_step,
                "outcome": result.outcome.value,
                "project_version": result.report.project_version,
                "run_id": result.report.run_id,
                "schema_version": result.report.schema_version,
            },
            default=repr,
            sort_keys=True,
        )
    )
    raise SystemExit(_EXIT_CODES[result.outcome])


def _load_entry_point(reference: str) -> TrainingProject:
    module_name, separator, attribute_name = reference.partition(":")
    if not separator or not module_name or not attribute_name:
        raise ValueError("entry point must use MODULE:CALLABLE form")
    entry_point = getattr(importlib.import_module(module_name), attribute_name)
    if not callable(entry_point):
        raise TypeError(f"entry point {reference!r} is not callable")
    return cast(TrainingProject, entry_point)


def _load_definition(path: Path) -> _RuntimeDefinition:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise TypeError("runtime definition must be a JSON object")
    definition = cast(dict[str, object], raw)
    configuration = definition.get("configuration")
    dataset = definition.get("dataset")
    metrics = definition.get("metric_definitions")
    seed = definition.get("seed")
    accelerator = definition.get("accelerator", {"kind": "cpu"})
    run_id = definition.get("run_id")
    project_version = definition.get("project_version")
    if not isinstance(configuration, dict):
        raise TypeError("runtime definition.configuration must be an object")
    if not isinstance(dataset, list):
        raise TypeError("runtime definition.dataset must be an array")
    if not isinstance(metrics, list):
        raise TypeError("runtime definition.metric_definitions must be an array")
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise TypeError("runtime definition.seed must be an integer")
    if not isinstance(accelerator, dict):
        raise TypeError("runtime definition.accelerator must be an object")
    if not isinstance(run_id, str) or not run_id:
        raise TypeError("runtime definition.run_id must be a non-empty string")
    if not isinstance(project_version, str) or not project_version:
        raise TypeError("runtime definition.project_version must be a non-empty string")

    metric_definitions = tuple(
        _load_metric(item) for item in cast(list[object], metrics)
    )
    accelerator_mapping = cast(dict[str, object], accelerator)
    kind = accelerator_mapping.get("kind")
    index = accelerator_mapping.get("index", 0)
    if kind not in ("cpu", "cuda", "rocm"):
        raise ValueError(
            "runtime definition.accelerator.kind must be cpu, cuda, or rocm"
        )
    if isinstance(index, bool) or not isinstance(index, int) or index < 0:
        raise ValueError(
            "runtime definition.accelerator.index must be a non-negative integer"
        )
    return _RuntimeDefinition(
        run_id=run_id,
        project_version=project_version,
        configuration=cast(Mapping[str, object], configuration),
        dataset=cast(list[object], dataset),
        metric_definitions=metric_definitions,
        seed=seed,
        accelerator=Accelerator(kind, index),
    )


def _load_metric(value: object) -> MetricDefinition:
    if not isinstance(value, dict):
        raise TypeError("each metric definition must be an object")
    metric = cast(dict[str, object], value)
    required = ("name", "numeric_kind", "unit", "comparison", "step_reduction")
    if any(not isinstance(metric.get(name), str) for name in required):
        raise TypeError("metric definition semantic fields must be strings")
    return MetricDefinition(
        name=cast(str, metric["name"]),
        numeric_kind=cast(NumericKind, metric["numeric_kind"]),
        unit=cast(str, metric["unit"]),
        comparison=cast(Comparison, metric["comparison"]),
        step_reduction=cast(StepReduction, metric["step_reduction"]),
        minimum=cast(int | float | None, metric.get("minimum")),
        maximum=cast(int | float | None, metric.get("maximum")),
        display_name=cast(str | None, metric.get("display_name")),
        description=cast(str | None, metric.get("description")),
    )


if __name__ == "__main__":
    main()
