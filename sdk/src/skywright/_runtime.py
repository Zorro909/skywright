"""Private operational entry point for the Skywright runtime."""

from __future__ import annotations

import argparse
import json
from collections.abc import Mapping
from importlib.metadata import version
from pathlib import Path
from typing import TypedDict, cast

from skywright._build_info import SOURCE_REVISION
from skywright._training import (
    Accelerator,
    TrainingProcessOutcome,
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
    dataset_factory: str
    recorder_factory: str
    resume_factory: str | None
    metric_contract_factory: str
    skywright_metric_schema: str
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
        definition = _load_definition(cast(Path, arguments.definition))
    except (ImportError, AttributeError, OSError, TypeError, ValueError) as failure:
        parser.error(str(failure))

    result = run_training_process(
        cast(str, arguments.entry_point),
        configuration=definition["configuration"],
        dataset=definition["dataset_factory"],
        metric_contracts=definition["metric_contract_factory"],
        skywright_metric_schema=definition["skywright_metric_schema"],
        recorder=definition["recorder_factory"],
        seed=definition["seed"],
        resume_from=definition["resume_factory"],
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
                "latest_durable_checkpoint": result.report.latest_durable_checkpoint,
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


def _load_definition(path: Path) -> _RuntimeDefinition:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise TypeError("runtime definition must be a JSON object")
    definition = cast(dict[str, object], raw)
    configuration = definition.get("configuration")
    dataset_factory = definition.get("dataset_factory")
    recorder_factory = definition.get("recorder_factory")
    resume_factory = definition.get("resume_factory")
    metric_contract_factory = definition.get("metric_contract_factory")
    skywright_metric_schema = definition.get("skywright_metric_schema")
    seed = definition.get("seed")
    accelerator = definition.get("accelerator", {"kind": "cpu"})
    run_id = definition.get("run_id")
    project_version = definition.get("project_version")
    if not isinstance(configuration, dict):
        raise TypeError("runtime definition.configuration must be an object")
    if not isinstance(dataset_factory, str) or not dataset_factory:
        raise TypeError("runtime definition.dataset_factory must be MODULE:CALLABLE")
    if not isinstance(recorder_factory, str) or not recorder_factory:
        raise TypeError("runtime definition.recorder_factory must be MODULE:CALLABLE")
    if resume_factory is not None and (
        not isinstance(resume_factory, str) or not resume_factory
    ):
        raise TypeError("runtime definition.resume_factory must be MODULE:CALLABLE")
    if not isinstance(metric_contract_factory, str) or not metric_contract_factory:
        raise TypeError(
            "runtime definition.metric_contract_factory must be MODULE:CALLABLE"
        )
    if not isinstance(skywright_metric_schema, str) or not skywright_metric_schema:
        raise TypeError(
            "runtime definition.skywright_metric_schema must be a non-empty string"
        )
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise TypeError("runtime definition.seed must be an integer")
    if not isinstance(accelerator, dict):
        raise TypeError("runtime definition.accelerator must be an object")
    if not isinstance(run_id, str) or not run_id:
        raise TypeError("runtime definition.run_id must be a non-empty string")
    if not isinstance(project_version, str) or not project_version:
        raise TypeError("runtime definition.project_version must be a non-empty string")

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
        dataset_factory=dataset_factory,
        recorder_factory=recorder_factory,
        resume_factory=resume_factory,
        metric_contract_factory=metric_contract_factory,
        skywright_metric_schema=skywright_metric_schema,
        seed=seed,
        accelerator=Accelerator(kind, index),
    )


if __name__ == "__main__":
    main()
