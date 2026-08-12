from importlib.metadata import version

import skywright


def test_package_root_declares_its_complete_public_surface() -> None:
    assert skywright.__all__ == ("__version__", "version")
    assert all(hasattr(skywright, name) for name in skywright.__all__)
    assert {name for name in vars(skywright) if not name.startswith("_")} == {"version"}


def test_package_version_comes_from_installed_metadata() -> None:
    assert skywright.__version__ == version("skywright")
    assert skywright.version == skywright.__version__
