from importlib.metadata import version

import skywright


def test_package_version_comes_from_installed_metadata() -> None:
    assert skywright.__version__ == version("skywright")
