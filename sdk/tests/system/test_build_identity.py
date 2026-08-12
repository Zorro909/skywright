import os
import subprocess
import tarfile
from email.parser import Parser
from pathlib import Path
from zipfile import ZipFile

import pytest

SDK_ROOT = Path(__file__).parents[2]


def build_sdk(
    output_directory: Path,
    artifact: str,
    *,
    source: Path | None = None,
    source_revision: str | None = None,
    release: bool = False,
    check: bool = True,
    capture_output: bool = False,
) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment.pop("SKYWRIGHT_BUILD_MODE", None)
    environment.pop("SKYWRIGHT_SOURCE_REVISION", None)
    if release:
        environment["SKYWRIGHT_BUILD_MODE"] = "release"
    if source_revision is not None:
        environment["SKYWRIGHT_SOURCE_REVISION"] = source_revision

    command: list[str | Path] = ["uv", "build"]
    if source is not None:
        command.append(source)
    command.extend([f"--{artifact}", "--out-dir", output_directory])
    return subprocess.run(
        command,
        check=check,
        cwd=SDK_ROOT,
        env=environment,
        text=True,
        capture_output=capture_output,
    )


def test_local_wheel_build_information_matches_package_metadata(
    wheel_path: Path,
) -> None:
    with ZipFile(wheel_path) as wheel:
        metadata_path = next(
            name for name in wheel.namelist() if name.endswith(".dist-info/METADATA")
        )
        metadata = Parser().parsestr(wheel.read(metadata_path).decode())
        build_information = wheel.read("skywright/_build_info.py").decode()

    assert build_information == (
        f'PACKAGE_VERSION = "{metadata["Version"]}"\nSOURCE_REVISION = "unknown"\n'
    )


def test_local_sdist_contains_one_frozen_build_information_file(
    wheel_path: Path,
) -> None:
    sdist_path = next(wheel_path.parent.glob("skywright-*.tar.gz"))
    with tarfile.open(sdist_path) as sdist:
        build_information_members = [
            member
            for member in sdist.getmembers()
            if member.name.endswith("/src/skywright/_build_info.py")
        ]

    assert len(build_information_members) == 1


def test_release_build_requires_an_explicit_source_revision(tmp_path: Path) -> None:
    completed = build_sdk(
        tmp_path,
        "wheel",
        release=True,
        check=False,
        capture_output=True,
    )

    assert completed.returncode != 0
    assert (
        "SKYWRIGHT_SOURCE_REVISION is required when "
        "SKYWRIGHT_BUILD_MODE=release" in completed.stderr
    )


def test_release_wheel_contains_the_explicit_source_revision(tmp_path: Path) -> None:
    build_sdk(
        tmp_path,
        "wheel",
        release=True,
        source_revision="0123456789abcdef",
    )
    wheel_path = next(tmp_path.glob("skywright-*.whl"))

    with ZipFile(wheel_path) as wheel:
        build_information = wheel.read("skywright/_build_info.py").decode()

    assert build_information == (
        'PACKAGE_VERSION = "0.1.0"\nSOURCE_REVISION = "0123456789abcdef"\n'
    )


@pytest.mark.parametrize(
    ("sdist_source_revision", "expected_source_revision"),
    [
        pytest.param("fedcba9876543210", "fedcba9876543210", id="release"),
        pytest.param(None, "unknown", id="local"),
    ],
)
def test_wheel_rebuilt_from_sdist_retains_the_frozen_source_revision(
    tmp_path: Path,
    sdist_source_revision: str | None,
    expected_source_revision: str,
) -> None:
    sdist_directory = tmp_path / "sdist"
    build_sdk(
        sdist_directory,
        "sdist",
        release=sdist_source_revision is not None,
        source_revision=sdist_source_revision,
    )

    wheel_directory = tmp_path / "wheel"
    build_sdk(
        wheel_directory,
        "wheel",
        source=next(sdist_directory.glob("skywright-*.tar.gz")),
        source_revision="different-environment-revision",
    )

    with ZipFile(next(wheel_directory.glob("skywright-*.whl"))) as wheel:
        build_information = wheel.read("skywright/_build_info.py").decode()

    assert f'SOURCE_REVISION = "{expected_source_revision}"\n' in build_information
