"""Project and library checkpoint capture and restoration."""

import copy
from collections.abc import Callable, Mapping
from typing import NoReturn, cast

from skywright._dataset_ordering import DatasetOrdering
from skywright._training_errors import SkywrightFailure
from skywright._training_protocols import CheckpointState
from skywright._training_state import capture_runtime_state, restore_runtime_state
from skywright._training_types import CheckpointSnapshot, DatasetCursor


def restore_checkpoint(
    checkpoint: CheckpointSnapshot | None,
    states: Mapping[str, CheckpointState],
    run_id: str,
    project_version: str,
    violate: Callable[[str, str, str], NoReturn],
) -> None:
    if checkpoint is None:
        return
    checkpoint_state = checkpoint.state
    if checkpoint.run_id and checkpoint.run_id != run_id:
        violate(
            "resume/run-identity",
            f"checkpoint Run {checkpoint.run_id!r} does not match {run_id!r}",
            "resume an Execution Attempt from a checkpoint in the same Run",
        )
    if checkpoint.project_version and checkpoint.project_version != project_version:
        violate(
            "resume/project-version",
            f"checkpoint Training Project Version {checkpoint.project_version!r} does not match {project_version!r}",
            "resume with the identical Training Project Version",
        )
    expected = set(checkpoint_state)
    actual = set(states)
    if expected != actual:
        violate(
            "checkpoint-state/shape",
            f"registered names {sorted(actual)!r} do not match checkpoint names {sorted(expected)!r}",
            "resume with the same complete Checkpoint State shape",
        )
    for name, state in states.items():
        restored = checkpoint_state[name]
        if not isinstance(restored, Mapping):
            violate(
                "checkpoint-state/payload",
                f"Checkpoint State {name!r} is not a mapping",
                "publish state_dict() mappings without changing their shape",
            )
        state.load_state_dict(copy.deepcopy(cast(Mapping[str, object], restored)))
    try:
        restore_runtime_state(checkpoint.runtime_state)
    except Exception as failure:
        raise SkywrightFailure(failure, "project") from failure


def capture_checkpoint(
    step: int,
    states: Mapping[str, CheckpointState],
    dataset_cursor: DatasetCursor,
    run_id: str,
    project_version: str,
    ordering: DatasetOrdering | None = None,
    determinism: Mapping[str, object] | None = None,
) -> CheckpointSnapshot:
    state = {
        name: copy.deepcopy(dict(checkpoint_state.state_dict()))
        for name, checkpoint_state in states.items()
    }
    runtime_state = capture_runtime_state()
    if determinism is not None:
        runtime_state["training_determinism"] = dict(determinism)
    if ordering is not None:
        runtime_state["dataset_ordering"] = ordering.to_document()
    return CheckpointSnapshot(
        step=step,
        state=state,
        runtime_state=runtime_state,
        dataset_cursor=dataset_cursor,
        run_id=run_id,
        project_version=project_version,
    )
