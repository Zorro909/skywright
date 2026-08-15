from importlib.metadata import version
from pathlib import Path

import skywright

PACKAGE_ROOT = Path(skywright.__file__).parent


def test_package_root_declares_its_complete_public_surface() -> None:
    assert skywright.__all__ == (
        "Accelerator",
        "ArtifactRecord",
        "CheckpointSnapshot",
        "CheckpointState",
        "DatasetAccess",
        "DatasetBatch",
        "DatasetCursor",
        "ExecutionAttemptRecord",
        "ExecutionTerminationCause",
        "ExecutionTerminationReport",
        "MetricCatalog",
        "MetricContractResolver",
        "MetricDefinition",
        "MetricObservation",
        "ResumeState",
        "RunContext",
        "SampleRecord",
        "ScalarValue",
        "TrainingContractViolation",
        "TrainingProcessOutcome",
        "TrainingProcessRecorder",
        "TrainingProcessResult",
        "TrainingProject",
        "__version__",
        "run_training_process",
        "version",
    )
    assert all(hasattr(skywright, name) for name in skywright.__all__)
    assert {name for name in vars(skywright) if not name.startswith("_")} == {
        name for name in skywright.__all__ if not name.startswith("_")
    }


def test_package_version_comes_from_installed_metadata() -> None:
    assert skywright.__version__ == version("skywright")
    assert skywright.version == skywright.__version__


def test_sdk_source_files_stay_below_the_absolute_size_cap() -> None:
    oversized = {
        path.name: len(path.read_text().splitlines())
        for path in PACKAGE_ROOT.glob("*.py")
        if len(path.read_text().splitlines()) > 500
    }

    assert oversized == {}
