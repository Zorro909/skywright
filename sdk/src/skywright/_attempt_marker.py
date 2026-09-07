"""Versioned navigation markers; durable records remain the identity authority."""

import json
import sys

from skywright._training_types import ExecutionAttemptRecord


def emit_attempt_marker(attempt: ExecutionAttemptRecord) -> None:
    marker = (
        "\x1eSKYWRIGHT_ATTEMPT_V1 "
        + json.dumps(
            {
                "schemaVersion": 1,
                "runId": attempt.run_id,
                "attemptId": attempt.attempt_id,
                "projectVersion": attempt.project_version,
            },
            ensure_ascii=True,
            separators=(",", ":"),
        )
        + "\x1f\n"
    )
    # An unavailable log sink must not change the training outcome. The archive
    # validates markers and reports missing/unverifiable navigation separately.
    if len(marker) > 4096:
        return
    try:
        sys.stdout.write(marker)
        sys.stdout.flush()
    except (OSError, ValueError):
        pass
