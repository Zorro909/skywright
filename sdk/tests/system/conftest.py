from pathlib import Path
from typing import cast

import pytest


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--wheel-dir",
        required=True,
        type=Path,
        help="Directory containing the single built Skywright wheel under test",
    )


@pytest.fixture
def wheel_path(request: pytest.FixtureRequest) -> Path:
    wheel_directory = cast(Path, request.config.getoption("--wheel-dir"))
    wheels = tuple(wheel_directory.resolve(strict=True).glob("skywright-*.whl"))
    if len(wheels) != 1:
        pytest.fail(
            f"expected one Skywright wheel in {wheel_directory}, found {len(wheels)}"
        )
    return wheels[0]
