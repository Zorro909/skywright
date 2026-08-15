from importlib.metadata import version

import skywright


def test_package_root_declares_its_complete_public_surface() -> None:
    assert skywright.__all__ == (
        "Accelerator",
        "ArtifactRecord",
        "CheckpointSnapshot",
        "CheckpointState",
        "ExecutionAttemptRecord",
        "ExecutionTerminationCause",
        "ExecutionTerminationReport",
        "MetricDefinition",
        "MetricObservation",
        "ResumeState",
        "RunContext",
        "SampleRecord",
        "ScalarValue",
        "TrainingContractViolation",
        "TrainingProcessOutcome",
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
