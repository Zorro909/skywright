import subprocess
import sys
from importlib.metadata import version
from pathlib import Path


def test_wheel_is_a_complete_dependency_free_install(
    wheel_path: Path, tmp_path: Path
) -> None:
    environment = tmp_path / "consumer-environment"
    subprocess.run(
        [sys.executable, "-m", "venv", str(environment)],
        check=True,
    )

    consumer_python = environment / "bin" / "python"
    subprocess.run(
        [
            consumer_python,
            "-m",
            "pip",
            "--disable-pip-version-check",
            "install",
            "--no-deps",
            wheel_path,
        ],
        check=True,
    )

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
