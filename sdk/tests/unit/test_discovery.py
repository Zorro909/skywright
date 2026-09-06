"""Exercise pytest discovery with new modules in the real suite directories."""

import os
import subprocess
import sys
import tempfile
from pathlib import Path

SDK_ROOT = Path(__file__).parents[2]


def _collect(*arguments: str, optional_packages: str | None = None) -> str:
    return subprocess.run(
        [sys.executable, "-m", "pytest", "--collect-only", "-q", *arguments],
        cwd=SDK_ROOT,
        env={
            **os.environ,
            **({"PYTHONPATH": optional_packages} if optional_packages else {}),
        },
        text=True,
        capture_output=True,
        check=True,
        timeout=60,
    ).stdout


def test_discovery_collects_new_modules_and_separates_external_suites() -> None:
    with (
        tempfile.TemporaryDirectory(dir=SDK_ROOT / "tests/unit") as ordinary,
        tempfile.TemporaryDirectory(dir=SDK_ROOT / "tests/integration") as service,
        tempfile.TemporaryDirectory(
            dir=SDK_ROOT / "tests/integration/dataset"
        ) as dataset,
        tempfile.TemporaryDirectory(dir=SDK_ROOT / "tests/system") as installed,
        tempfile.TemporaryDirectory() as optional,
    ):
        Path(ordinary, "test_future_unit.py").write_text(
            "def test_future_unit_probe(): pass\n", encoding="utf-8"
        )
        Path(service, "test_future_service.py").write_text(
            "def test_future_service_probe(): pass\n", encoding="utf-8"
        )
        Path(dataset, "test_future_dataset.py").write_text(
            "import skywright_collection_optional_probe\n"
            "def test_future_dataset_probe(): pass\n",
            encoding="utf-8",
        )
        Path(installed, "test_future_artifact.py").write_text(
            "def test_future_artifact_probe(): pass\n", encoding="utf-8"
        )
        unit = _collect()
        assert "test_future_unit_probe" in unit
        assert (
            "test_mds_decoding.py::test_every_advertised_safe_encoding_decodes" in unit
        )
        assert "test_future_service_probe" not in unit
        assert "test_future_dataset_probe" not in unit
        assert "test_future_artifact_probe" not in unit
        assert "tests/integration/" not in unit
        assert "tests/system/" not in unit

        services = _collect("tests/integration", "--ignore=tests/integration/dataset")
        assert "test_future_service_probe" in services
        assert "tests/integration/test_run_store_system.py::" in services
        assert "test_future_unit_probe" not in services
        assert "test_future_dataset_probe" not in services
        assert "test_future_artifact_probe" not in services

        Path(optional, "skywright_collection_optional_probe.py").write_text(
            "", encoding="utf-8"
        )
        datasets = _collect("tests/integration/dataset", optional_packages=optional)
        assert "test_future_dataset_probe" in datasets
        assert "test_dataset_access_system.py::" in datasets
        assert "test_future_service_probe" not in datasets
        assert "test_future_unit_probe" not in datasets
        assert "test_future_artifact_probe" not in datasets

        artifacts = _collect("tests/system", "-m", "system")
        assert "test_future_artifact_probe" in artifacts
        assert "test_future_unit_probe" not in artifacts
        assert "test_future_service_probe" not in artifacts
        assert "test_future_dataset_probe" not in artifacts
