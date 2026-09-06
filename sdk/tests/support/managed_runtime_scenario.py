"""Exercise the installed managed CLI with private CPU and supervisor test seams."""

import json
import sys
from pathlib import Path
from unittest.mock import patch

from skywright import Accelerator, ExecutionAttemptRecord
from skywright._managed_runtime import ManagedRuntime
from skywright._runtime import main
from skywright.recovery import PreviousWriterEvidence


def verifier(previous: ExecutionAttemptRecord) -> PreviousWriterEvidence | None:
    if not Path("proof.json").exists():
        return None
    value = json.loads(Path("proof.json").read_text())
    return (
        PreviousWriterEvidence(**value)
        if value["attempt_id"] == previous.attempt_id
        else None
    )


production_run = ManagedRuntime.run


def fixture_run(runtime: ManagedRuntime, cache: Path):
    return production_run(
        runtime,
        cache,
        _accelerator=Accelerator("cpu", 0),
        _previous_writer_verifier=verifier,
    )


sys.argv = [
    "skywright-runtime",
    "--definition",
    "definition.json",
    "--materials",
    "materials.json",
    "--cache-directory",
    "cache",
]
with patch.object(ManagedRuntime, "run", fixture_run):
    main()
