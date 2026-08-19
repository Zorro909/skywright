"""Consume a real control-plane resolution through the production Python Run Store."""

from __future__ import annotations

import json
import sys
from typing import cast

from skywright import ArtifactRecord, ExecutionAttemptRecord
from skywright.run_store import (
    ResolvedTargetStorageDescriptor,
    RunStoreRecorder,
    TargetStorage,
)


def main() -> None:
    payload = json.load(sys.stdin)
    descriptor = cast(ResolvedTargetStorageDescriptor, payload["descriptor"])
    target = TargetStorage.from_resolved_descriptor(
        descriptor,
        training_project_id=payload["trainingProjectId"],
        run_id=payload["runId"],
    )
    store = RunStoreRecorder(target)
    store.publish_attempt(
        ExecutionAttemptRecord(
            payload["attemptId"],
            payload["runId"],
            "project@digest",
            None,
        )
    )
    store.publish_artifact(ArtifactRecord("direct.txt", b"resolved", 8))


if __name__ == "__main__":
    main()
