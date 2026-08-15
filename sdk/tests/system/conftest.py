import os
import subprocess
import sys
from pathlib import Path
from typing import cast

import pytest


def isolated_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment.pop("PYTHONPATH", None)
    environment.pop("PYTHONHOME", None)
    return environment


@pytest.fixture
def isolated_process_environment() -> dict[str, str]:
    return isolated_environment()


def pytest_collection_modifyitems(items: list[pytest.Item]) -> None:
    system = pytest.mark.system
    for item in items:
        item.add_marker(system)


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--artifact-dir",
        required=True,
        type=Path,
        help="Directory containing one direct wheel and one source distribution",
    )


@pytest.fixture(scope="session")
def distribution_wheels(
    request: pytest.FixtureRequest, tmp_path_factory: pytest.TempPathFactory
) -> tuple[Path, Path]:
    artifact_directory = cast(Path, request.config.getoption("--artifact-dir"))
    artifact_directory = artifact_directory.resolve(strict=True)
    wheels = tuple(artifact_directory.glob("skywright-*.whl"))
    if len(wheels) != 1:
        pytest.fail(
            f"expected one direct Skywright wheel in {artifact_directory}, "
            f"found {len(wheels)}"
        )
    source_distributions = tuple(artifact_directory.glob("skywright-*.tar.gz"))
    if len(source_distributions) != 1:
        pytest.fail(
            f"expected one Skywright source distribution in {artifact_directory}, "
            f"found {len(source_distributions)}"
        )

    rebuilt_directory = tmp_path_factory.mktemp("source-derived-wheel")
    subprocess.run(
        [
            "uv",
            "build",
            source_distributions[0],
            "--wheel",
            "--out-dir",
            rebuilt_directory,
        ],
        check=True,
        cwd=rebuilt_directory,
        env=isolated_environment(),
    )
    rebuilt_wheels = tuple(rebuilt_directory.glob("skywright-*.whl"))
    if len(rebuilt_wheels) != 1:
        pytest.fail(
            "expected the source distribution to produce one Skywright wheel, "
            f"found {len(rebuilt_wheels)}"
        )
    return wheels[0], rebuilt_wheels[0]


@pytest.fixture(
    scope="session",
    params=("wheel", "sdist"),
    ids=("direct-wheel", "source-distribution"),
)
def install_artifact(
    request: pytest.FixtureRequest,
    distribution_wheels: tuple[Path, Path],
) -> Path:
    direct_wheel = distribution_wheels[0]
    if cast(str, request.param) == "wheel":
        return direct_wheel
    source_distributions = tuple(direct_wheel.parent.glob("skywright-*.tar.gz"))
    if len(source_distributions) != 1:
        pytest.fail("expected one source distribution for the installed consumer")
    return source_distributions[0]


@pytest.fixture(scope="session")
def direct_wheel_path(distribution_wheels: tuple[Path, Path]) -> Path:
    return distribution_wheels[0]


@pytest.fixture
def installed_sdk(install_artifact: Path, tmp_path: Path) -> Path:
    environment = tmp_path / "consumer-environment"
    subprocess.run(
        [sys.executable, "-m", "venv", str(environment)],
        check=True,
        env=isolated_environment(),
    )
    subprocess.run(
        [
            environment / "bin" / "python",
            "-m",
            "pip",
            "--disable-pip-version-check",
            "install",
            "--no-deps",
            install_artifact,
        ],
        check=True,
        env=isolated_environment(),
    )
    return environment
