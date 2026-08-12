from pathlib import Path
from typing import cast

import pytest


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--wheel",
        required=True,
        type=Path,
        help="Path to the built Skywright wheel under test",
    )


@pytest.fixture
def wheel_path(request: pytest.FixtureRequest) -> Path:
    path = cast(Path, request.config.getoption("--wheel"))
    return path.resolve(strict=True)
