import subprocess
from importlib.metadata import version
from pathlib import Path


def test_wheel_is_a_complete_dependency_free_install(
    installed_sdk: Path, tmp_path: Path
) -> None:
    consumer_python = installed_sdk / "bin" / "python"

    consumer_check = """
import importlib.util
from importlib.metadata import metadata, version
from importlib.resources import files

import skywright

assert version("skywright") == skywright.__version__
assert metadata("skywright").get_all("Requires-Dist") is None
assert files(skywright).joinpath("py.typed").is_file()
assert importlib.util.find_spec("torch") is None
print(skywright.__version__)
"""
    completed = subprocess.run(
        [consumer_python, "-I", "-c", consumer_check],
        check=True,
        cwd=tmp_path,
        text=True,
        capture_output=True,
    )

    assert completed.stdout == f"{version('skywright')}\n"


def test_runtime_command_help_is_available_without_runtime_services(
    installed_sdk: Path,
) -> None:
    completed = subprocess.run(
        [installed_sdk / "bin" / "skywright-runtime", "--help"],
        check=True,
        text=True,
        capture_output=True,
    )

    assert "usage: skywright-runtime" in completed.stdout
    assert "Execute a Skywright Training Project" in completed.stdout


def test_runtime_command_reports_version_and_source_revision(
    installed_sdk: Path,
) -> None:
    completed = subprocess.run(
        [installed_sdk / "bin" / "skywright-runtime", "--version"],
        check=True,
        text=True,
        capture_output=True,
    )

    assert completed.stdout == (
        f"skywright-runtime {version('skywright')}\nsource revision: unknown\n"
    )


def test_runtime_command_rejects_training_until_the_process_boundary_exists(
    installed_sdk: Path,
) -> None:
    completed = subprocess.run(
        [installed_sdk / "bin" / "skywright-runtime"],
        check=False,
        text=True,
        capture_output=True,
    )

    assert completed.returncode == 2
    assert completed.stdout == ""
    assert (
        "training execution is unavailable until the Training Process Boundary "
        "is implemented" in completed.stderr
    )
