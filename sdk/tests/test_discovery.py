"""Exercise pytest discovery with new modules in the real suite directories."""

import subprocess
import sys
import tempfile
from pathlib import Path

SDK_ROOT = Path(__file__).parents[1]


def _collect(*arguments: str) -> str:
    return subprocess.run(
        [sys.executable, "-m", "pytest", "--collect-only", "-q", *arguments],
        cwd=SDK_ROOT,
        text=True,
        capture_output=True,
        check=True,
        timeout=60,
    ).stdout


def test_discovery_collects_new_modules_and_separates_external_suites() -> None:
    with (
        tempfile.TemporaryDirectory(dir=SDK_ROOT / "tests") as ordinary,
        tempfile.TemporaryDirectory(dir=SDK_ROOT / "tests/system") as installed,
    ):
        Path(ordinary, "test_future_unit.py").write_text(
            "def test_future_unit_probe(): pass\n", encoding="utf-8"
        )
        Path(ordinary, "test_future_service.py").write_text(
            "import pytest\npytestmark = pytest.mark.integration\n"
            "def test_future_service_probe(): pass\n",
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
        assert "test_future_artifact_probe" not in unit
        assert "tests/test_run_store_system.py::" not in unit
        assert "tests/test_dataset_access_system.py::" not in unit
        assert "tests/system/" not in unit

        services = _collect("-m", "integration")
        assert "test_future_service_probe" in services
        assert "tests/test_run_store_system.py::" in services
        assert "tests/test_dataset_access_system.py::" in services
        assert "test_future_unit_probe" not in services
        assert "test_future_artifact_probe" not in services

        artifacts = _collect("tests/system", "-m", "system")
        assert "test_future_artifact_probe" in artifacts
        assert "test_future_unit_probe" not in artifacts
        assert "test_future_service_probe" not in artifacts
