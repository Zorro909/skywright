"""Versioned ordering identity and exact checkpoint continuation validation."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from typing import NoReturn, cast

from skywright._training_errors import TrainingContractViolation
from skywright._training_types import CheckpointSnapshot


@dataclass(frozen=True)
class DatasetOrdering:
    """Inputs that define logical order, independent of retrieval and topology."""

    definition_id: str
    content_fingerprint: str
    seed: int
    policy: str = "deterministic-shuffle"
    version: str = "feistel-sha256-v1"

    def __post_init__(self) -> None:
        if not self.definition_id or not self.content_fingerprint:
            raise ValueError("Dataset ordering requires a pinned Dataset Definition")
        if type(self.seed) is not int:
            raise ValueError("Dataset ordering seed must be an integer")
        if (
            self.policy != "deterministic-shuffle"
            or self.version != "feistel-sha256-v1"
        ):
            raise ValueError("Unsupported Dataset ordering policy or version")

    def to_document(self) -> dict[str, object]:
        return {
            "definitionId": self.definition_id,
            "contentFingerprint": self.content_fingerprint,
            "seed": self.seed,
            "policy": self.policy,
            "version": self.version,
        }

    @property
    def fingerprint(self) -> str:
        encoded = json.dumps(
            self.to_document(), sort_keys=True, separators=(",", ":")
        ).encode()
        return "sha256:" + hashlib.sha256(encoded).hexdigest()


def prepare_continuation(
    checkpoint: CheckpointSnapshot | None,
    ordering: DatasetOrdering | None,
    *,
    run_id: str,
    source_run_id: str | None,
    ordering_reset: bool,
) -> CheckpointSnapshot | None:
    """Validate seed provenance before allowing project construction or restoration."""

    def reject(field: str, problem: str) -> NoReturn:
        raise TrainingContractViolation(
            "dataset-ordering/" + field,
            problem,
            "keep seed and policy fixed; reset only an explicitly checkpoint-seeded Run's Dataset Definition",
        )

    if type(ordering_reset) is not bool:
        reject("reset", "Ordering Reset must be an explicit boolean")
    if checkpoint is None:
        if source_run_id is not None or ordering_reset:
            reject(
                "seed", "Checkpoint-seeded continuation requires a durable checkpoint"
            )
        return None
    if source_run_id is not None:
        if (
            not source_run_id
            or source_run_id == run_id
            or checkpoint.run_id != source_run_id
        ):
            reject(
                "source-run",
                "Seed checkpoint does not belong to the declared source Run",
            )
    elif ordering_reset:
        reject("reset", "Ordering Reset is invalid during same-Run recovery")
    if ordering is None:
        if ordering_reset:
            reject("inputs", "Ordering Reset requires explicit Dataset ordering inputs")
        return checkpoint
    saved = checkpoint.runtime_state.get("dataset_ordering")
    if not isinstance(saved, Mapping):
        # Older checkpoints still support exact fingerprint equality, never reset.
        if (
            ordering_reset
            or checkpoint.dataset_cursor.ordering_fingerprint != ordering.fingerprint
        ):
            reject(
                "inputs",
                "Checkpoint lacks the ordering inputs needed to diagnose or reset continuation",
            )
        return checkpoint
    saved = cast(Mapping[str, object], saved)
    current = ordering.to_document()
    if set(saved) != set(current):
        reject("inputs", "Checkpoint ordering inputs are incomplete or unknown")
    saved_fingerprint = (
        "sha256:"
        + hashlib.sha256(
            json.dumps(dict(saved), sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
    )
    if saved_fingerprint != checkpoint.dataset_cursor.ordering_fingerprint:
        reject(
            "fingerprint",
            "Checkpoint ordering inputs do not match its Dataset Cursor fingerprint",
        )
    for field in ("seed", "policy", "version"):
        if (
            type(saved[field]) is not type(current[field])
            or saved[field] != current[field]
        ):
            reject(
                field, f"Checkpoint ordering {field} differs from the requested {field}"
            )
    changed = [
        field
        for field in ("definitionId", "contentFingerprint")
        if saved[field] != current[field]
    ]
    if changed and not ordering_reset:
        reject(
            changed[0],
            f"Checkpoint Dataset {changed[0]} differs without an explicit Ordering Reset",
        )
    if ordering_reset and not changed:
        reject("reset", "Ordering Reset requires a changed Dataset Definition")
    if not ordering_reset:
        return checkpoint
    return CheckpointSnapshot(
        step=checkpoint.step,
        state=checkpoint.state,
        runtime_state={**checkpoint.runtime_state, "dataset_ordering": current},
        dataset_cursor=replace(
            checkpoint.dataset_cursor,
            item_offset=0,
            epoch_step=0,
            ordering_fingerprint=ordering.fingerprint,
        ),
        reference=checkpoint.reference,
        run_id=checkpoint.run_id,
        project_version=checkpoint.project_version,
    )


def validate_ordering(
    dataset: object,
    configuration: Mapping[str, object],
    seed: int,
    violate: Callable[[str, str, str], NoReturn],
) -> DatasetOrdering | None:
    """Check production ordering against materialized configuration before project code."""
    candidate = getattr(dataset, "ordering", None)
    ordering = candidate if isinstance(candidate, DatasetOrdering) else None
    if ordering is not None:
        if ordering.seed != seed:
            violate(
                "dataset-ordering/seed",
                "Dataset ordering seed differs from the Training Process seed",
                "assemble Dataset access from the library-owned Run Configuration seed",
            )
        reproducibility = configuration.get("reproducibility", {})
        if (
            isinstance(reproducibility, Mapping)
            and cast(Mapping[str, object], reproducibility).get("seed", seed) != seed
        ):
            violate(
                "dataset-ordering/seed",
                "Run Configuration seed differs from the Training Process seed",
                "use the same materialized seed for determinism and Dataset ordering",
            )
        dataset_configuration = configuration.get("dataset", {})
        if isinstance(dataset_configuration, Mapping):
            configured = cast(Mapping[str, object], dataset_configuration).get(
                "ordering", {}
            )
            if isinstance(configured, Mapping):
                configured = cast(Mapping[str, object], configured)
                for field, actual in (
                    ("policy", ordering.policy),
                    ("version", ordering.version),
                ):
                    if configured.get(field, actual) != actual:
                        violate(
                            "dataset-ordering/" + field,
                            f"Run Configuration ordering {field} differs from Dataset access",
                            "assemble Dataset access from the pinned Run Configuration ordering inputs",
                        )
    return ordering
