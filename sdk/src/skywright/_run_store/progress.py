"""Versioned Progress Record decoding shared by Run Store readers."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from typing import cast

from skywright._run_store.implementation import CheckpointReference

_MAX_STEP = (1 << 63) - 1


@dataclass(frozen=True)
class ProgressRecord:
    """Validated current progress projection for one Run."""

    run_id: str
    current_step: int
    latest_durable_step: int | None
    latest_durable_checkpoint: str | None
    target_step: int | None
    written_at: str
    schema_version: int = 1

    @classmethod
    def decode(cls, body: bytes) -> ProgressRecord:
        try:
            parsed = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as failure:
            raise ValueError("RUN_STORE_MALFORMED_PROGRESS: invalid JSON") from failure
        if not isinstance(parsed, dict):
            raise ValueError("RUN_STORE_MALFORMED_PROGRESS: expected an object")
        value = cast(dict[str, object], parsed)
        schema_version = value.get("schemaVersion")
        if isinstance(schema_version, bool) or schema_version != 1:
            raise ValueError("RUN_STORE_INCOMPATIBLE_SCHEMA: unknown Progress schema")
        required = {
            "schemaVersion",
            "runId",
            "currentStep",
            "latestDurableStep",
            "latestDurableCheckpoint",
            "writtenAt",
        }
        allowed = required | {"targetStep"}
        if set(value) < required or not set(value) <= allowed:
            raise ValueError("RUN_STORE_MALFORMED_PROGRESS: invalid members")
        run_id = value["runId"]
        current_step = _required_step(value["currentStep"])
        durable_step = _optional_step(value["latestDurableStep"])
        target_step = _optional_step(value.get("targetStep"))
        checkpoint = value["latestDurableCheckpoint"]
        written_at = value["writtenAt"]
        if not isinstance(run_id, str) or not run_id:
            raise ValueError("RUN_STORE_MALFORMED_PROGRESS: invalid Run identity")
        if checkpoint is not None and not isinstance(checkpoint, str):
            raise ValueError(
                "RUN_STORE_MALFORMED_PROGRESS: invalid Checkpoint Reference"
            )
        if (durable_step is None) != (checkpoint is None):
            raise ValueError("RUN_STORE_MALFORMED_PROGRESS: incomplete durable state")
        if durable_step is not None and durable_step > current_step:
            raise ValueError(
                "RUN_STORE_MALFORMED_PROGRESS: Durable Safe Point is ahead"
            )
        if checkpoint is not None:
            try:
                parsed_checkpoint = CheckpointReference.parse(checkpoint)
            except ValueError as failure:
                raise ValueError(
                    "RUN_STORE_MALFORMED_PROGRESS: invalid Checkpoint Reference"
                ) from failure
            if parsed_checkpoint.step != durable_step:
                raise ValueError(
                    "RUN_STORE_MALFORMED_PROGRESS: Checkpoint Reference differs from Durable Safe Point"
                )
        if not isinstance(written_at, str):
            raise ValueError("RUN_STORE_MALFORMED_PROGRESS: invalid write time")
        try:
            parsed_time = datetime.fromisoformat(written_at.replace("Z", "+00:00"))
        except ValueError as failure:
            raise ValueError(
                "RUN_STORE_MALFORMED_PROGRESS: invalid write time"
            ) from failure
        if not written_at.endswith("Z") or parsed_time.utcoffset() is None:
            raise ValueError("RUN_STORE_MALFORMED_PROGRESS: write time is not UTC")
        return cls(
            run_id,
            current_step,
            durable_step,
            checkpoint,
            target_step,
            written_at,
        )


def _required_step(value: object) -> int:
    step = _optional_step(value)
    if step is None:
        raise ValueError("RUN_STORE_MALFORMED_PROGRESS: invalid Step")
    return step


def _optional_step(value: object) -> int | None:
    if value is None:
        return None
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or value < 0
        or value > _MAX_STEP
    ):
        raise ValueError("RUN_STORE_MALFORMED_PROGRESS: invalid Step")
    return value
