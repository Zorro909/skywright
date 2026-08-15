from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

SDK_ROOT = Path(__file__).parents[1]
RELEASE_COMMAND = SDK_ROOT / "release_support.py"


def git(repository: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )


@pytest.fixture
def release_repository(tmp_path: Path) -> Path:
    repository = tmp_path / "repository"
    (repository / "sdk").mkdir(parents=True)
    (repository / "sdk" / "pyproject.toml").write_text(
        '[project]\nname = "skywright"\nversion = "0.1.0"\n',
        encoding="utf-8",
    )
    (repository / "sdk" / "CHANGELOG.md").write_text(
        "# Release notes\n\n## 0.1.0\n\n- Initial release.\n",
        encoding="utf-8",
    )
    git(repository, "init", "--initial-branch=main")
    git(repository, "config", "user.name", "Release Test")
    git(repository, "config", "user.email", "release-test@example.invalid")
    git(repository, "add", ".")
    git(repository, "commit", "-m", "Prepare release")
    git(repository, "tag", "sdk-v0.1.0")
    return repository


def release_command(
    repository: Path, *arguments: str | Path
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            str(item)
            for item in (
                sys.executable,
                RELEASE_COMMAND,
                "--repository",
                repository,
                *arguments,
            )
        ],
        check=False,
        capture_output=True,
        text=True,
    )


def write_release_json(repository: Path, output: Path) -> dict[str, str]:
    completed = release_command(
        repository,
        "validate",
        "--tag",
        "sdk-v0.1.0",
        "--main-ref",
        "main",
    )
    assert completed.returncode == 0, completed.stderr
    output.write_text(completed.stdout, encoding="utf-8")
    return json.loads(completed.stdout)


def make_distributions(directory: Path) -> dict[str, bytes]:
    directory.mkdir()
    distributions = {
        "skywright-0.1.0-py3-none-any.whl": b"wheel-contents",
        "skywright-0.1.0.tar.gz": b"source-contents",
    }
    for name, contents in distributions.items():
        (directory / name).write_bytes(contents)
    return distributions


def complete_github_assets(artifact_directory: Path) -> list[dict[str, str]]:
    deterministic = [
        *artifact_directory.glob("*.whl"),
        *artifact_directory.glob("*.tar.gz"),
        artifact_directory / "SHA256SUMS",
        artifact_directory / "release-manifest.json",
        *artifact_directory.glob("*.spdx.json"),
    ]
    assets = [
        {
            "name": path.name,
            "digest": "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        for path in deterministic
    ]
    assets.extend(
        {"name": name, "digest": "sha256:" + "f" * 64}
        for name in (
            "provenance.intoto.jsonl",
            "wheel-sbom.intoto.jsonl",
            "source-sbom.intoto.jsonl",
        )
    )
    return assets


def test_stable_tag_reachable_from_main_matches_version_and_release_notes(
    release_repository: Path,
) -> None:
    completed = release_command(
        release_repository,
        "validate",
        "--tag",
        "sdk-v0.1.0",
        "--main-ref",
        "main",
    )

    assert completed.returncode == 0, completed.stderr
    result = json.loads(completed.stdout)
    assert result == {
        "package": "skywright",
        "source_revision": git(
            release_repository, "rev-parse", "sdk-v0.1.0^{commit}"
        ).stdout.strip(),
        "tag": "sdk-v0.1.0",
        "version": "0.1.0",
    }


@pytest.mark.parametrize(
    "tag",
    ["sdk-v0.1", "sdk-v0.1.0rc1", "sdk-v01.1.0", "v0.1.0", "sdk-v0.1.0-1"],
)
def test_only_strict_stable_sdk_tags_are_accepted(
    release_repository: Path, tag: str
) -> None:
    completed = release_command(
        release_repository, "validate", "--tag", tag, "--main-ref", "main"
    )

    assert completed.returncode == 2
    assert "strict stable sdk-vMAJOR.MINOR.PATCH" in completed.stderr


def test_tag_must_be_reachable_from_main(release_repository: Path) -> None:
    git(release_repository, "switch", "-c", "unmerged-release")
    (release_repository / "sdk" / "pyproject.toml").write_text(
        '[project]\nname = "skywright"\nversion = "0.2.0"\n', encoding="utf-8"
    )
    (release_repository / "sdk" / "CHANGELOG.md").write_text(
        "# Release notes\n\n## 0.2.0\n\n- Unmerged.\n", encoding="utf-8"
    )
    git(release_repository, "add", ".")
    git(release_repository, "commit", "-m", "Unmerged release")
    git(release_repository, "tag", "sdk-v0.2.0")

    completed = release_command(
        release_repository,
        "validate",
        "--tag",
        "sdk-v0.2.0",
        "--main-ref",
        "main",
    )

    assert completed.returncode == 2
    assert "is not reachable from main" in completed.stderr


def test_tag_version_must_match_committed_package_metadata(
    release_repository: Path,
) -> None:
    git(release_repository, "tag", "sdk-v0.2.0")
    completed = release_command(
        release_repository,
        "validate",
        "--tag",
        "sdk-v0.2.0",
        "--main-ref",
        "main",
    )

    assert completed.returncode == 2
    assert "does not match committed package version 0.1.0" in completed.stderr


def test_release_notes_must_contain_the_exact_version_heading(
    release_repository: Path,
) -> None:
    (release_repository / "sdk" / "CHANGELOG.md").write_text(
        "# Release notes\n\n## Next\n", encoding="utf-8"
    )
    git(release_repository, "add", ".")
    git(release_repository, "commit", "-m", "Remove release notes")
    git(release_repository, "tag", "--force", "sdk-v0.1.0")

    completed = release_command(
        release_repository,
        "validate",
        "--tag",
        "sdk-v0.1.0",
        "--main-ref",
        "main",
    )

    assert completed.returncode == 2
    assert "committed release notes have no '## 0.1.0' section" in completed.stderr


def test_release_evidence_uses_the_complete_version_heading(
    release_repository: Path, tmp_path: Path
) -> None:
    (release_repository / "sdk" / "CHANGELOG.md").write_text(
        "# Release notes\n\n"
        "## 0.1.00\n\n- Different release.\n\n"
        "## 0.1.0\n\n- Exact release.\n",
        encoding="utf-8",
    )
    git(release_repository, "add", ".")
    git(release_repository, "commit", "-m", "Add prefixed release notes")
    git(release_repository, "tag", "--force", "sdk-v0.1.0")
    release_json = tmp_path / "release.json"
    write_release_json(release_repository, release_json)
    artifact_directory = tmp_path / "dist"
    make_distributions(artifact_directory)

    completed = release_command(
        release_repository,
        "evidence",
        "--release",
        release_json,
        "--artifact-dir",
        artifact_directory,
    )

    assert completed.returncode == 0, completed.stderr
    release_notes = (artifact_directory / "RELEASE.md").read_text()
    assert release_notes.startswith("## 0.1.0\n\n- Exact release.\n")
    assert "Different release" not in release_notes


def test_release_never_substitutes_a_different_pypi_project(
    release_repository: Path,
) -> None:
    (release_repository / "sdk" / "pyproject.toml").write_text(
        '[project]\nname = "skywright-renamed"\nversion = "0.1.0"\n',
        encoding="utf-8",
    )
    git(release_repository, "add", ".")
    git(release_repository, "commit", "-m", "Attempt package rename")
    git(release_repository, "tag", "--force", "sdk-v0.1.0")

    completed = release_command(
        release_repository,
        "validate",
        "--tag",
        "sdk-v0.1.0",
        "--main-ref",
        "main",
    )

    assert completed.returncode == 2
    assert "package identity must remain exactly 'skywright'" in completed.stderr


def test_release_evidence_binds_both_verified_distributions(
    release_repository: Path, tmp_path: Path
) -> None:
    release_json = tmp_path / "release.json"
    validated = write_release_json(release_repository, release_json)
    artifact_directory = tmp_path / "dist"
    distributions = make_distributions(artifact_directory)

    completed = release_command(
        release_repository,
        "evidence",
        "--release",
        release_json,
        "--artifact-dir",
        artifact_directory,
    )

    assert completed.returncode == 0, completed.stderr
    manifest = json.loads((artifact_directory / "release-manifest.json").read_text())
    assert manifest["package"] == "skywright"
    assert manifest["version"] == "0.1.0"
    assert manifest["source_revision"] == validated["source_revision"]
    assert manifest["distributions"] == [
        {
            "filename": name,
            "sha256": hashlib.sha256(contents).hexdigest(),
            "size": len(contents),
        }
        for name, contents in sorted(distributions.items())
    ]
    checksums = (artifact_directory / "SHA256SUMS").read_text().splitlines()
    assert checksums == [
        f"{hashlib.sha256(contents).hexdigest()}  {name}"
        for name, contents in sorted(distributions.items())
    ]
    for name, contents in distributions.items():
        sbom = json.loads((artifact_directory / f"{name}.spdx.json").read_text())
        assert sbom["spdxVersion"] == "SPDX-2.3"
        assert sbom["packages"][0]["versionInfo"] == "0.1.0"
        assert sbom["packages"][0]["checksums"] == [
            {
                "algorithm": "SHA256",
                "checksumValue": hashlib.sha256(contents).hexdigest(),
            }
        ]


def test_manifest_verification_rejects_changed_artifact_handoff(
    release_repository: Path, tmp_path: Path
) -> None:
    release_json = tmp_path / "release.json"
    write_release_json(release_repository, release_json)
    artifact_directory = tmp_path / "dist"
    make_distributions(artifact_directory)
    evidence = release_command(
        release_repository,
        "evidence",
        "--release",
        release_json,
        "--artifact-dir",
        artifact_directory,
    )
    assert evidence.returncode == 0, evidence.stderr
    (artifact_directory / "skywright-0.1.0.tar.gz").write_bytes(b"changed")

    completed = release_command(
        release_repository,
        "verify-handoff",
        "--artifact-dir",
        artifact_directory,
    )

    assert completed.returncode == 2
    assert "artifact handoff changed skywright-0.1.0.tar.gz" in completed.stderr


def test_identical_published_files_make_a_rerun_safe(
    release_repository: Path, tmp_path: Path
) -> None:
    release_json = tmp_path / "release.json"
    write_release_json(release_repository, release_json)
    artifact_directory = tmp_path / "dist"
    distributions = make_distributions(artifact_directory)
    evidence = release_command(
        release_repository,
        "evidence",
        "--release",
        release_json,
        "--artifact-dir",
        artifact_directory,
    )
    assert evidence.returncode == 0, evidence.stderr
    pypi = tmp_path / "pypi.json"
    pypi.write_text(
        json.dumps(
            {
                "releases": {
                    "0.1.0": [
                        {
                            "filename": name,
                            "digests": {"sha256": hashlib.sha256(contents).hexdigest()},
                        }
                        for name, contents in distributions.items()
                    ]
                }
            }
        ),
        encoding="utf-8",
    )
    github = tmp_path / "github.json"
    github.write_text(
        json.dumps({"assets": complete_github_assets(artifact_directory)}),
        encoding="utf-8",
    )

    completed = release_command(
        release_repository,
        "collisions",
        "--artifact-dir",
        artifact_directory,
        "--pypi-json",
        pypi,
        "--github-release-json",
        github,
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {"github": "skip", "pypi": "skip"}


def test_incomplete_matching_github_release_is_repaired_without_republishing_pypi(
    release_repository: Path, tmp_path: Path
) -> None:
    release_json = tmp_path / "release.json"
    write_release_json(release_repository, release_json)
    artifact_directory = tmp_path / "dist"
    distributions = make_distributions(artifact_directory)
    evidence = release_command(
        release_repository,
        "evidence",
        "--release",
        release_json,
        "--artifact-dir",
        artifact_directory,
    )
    assert evidence.returncode == 0, evidence.stderr
    published_distributions = [
        {
            "filename": name,
            "digests": {"sha256": hashlib.sha256(contents).hexdigest()},
        }
        for name, contents in distributions.items()
    ]
    pypi = tmp_path / "pypi.json"
    pypi.write_text(
        json.dumps({"releases": {"0.1.0": published_distributions}}),
        encoding="utf-8",
    )
    github = tmp_path / "github.json"
    github.write_text(
        json.dumps(
            {
                "assets": [
                    asset
                    for asset in complete_github_assets(artifact_directory)
                    if asset["name"] in distributions
                ]
            }
        ),
        encoding="utf-8",
    )

    completed = release_command(
        release_repository,
        "collisions",
        "--artifact-dir",
        artifact_directory,
        "--pypi-json",
        pypi,
        "--github-release-json",
        github,
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout) == {"github": "repair", "pypi": "skip"}


def test_conflicting_published_file_rejects_a_rerun(
    release_repository: Path, tmp_path: Path
) -> None:
    release_json = tmp_path / "release.json"
    write_release_json(release_repository, release_json)
    artifact_directory = tmp_path / "dist"
    make_distributions(artifact_directory)
    evidence = release_command(
        release_repository,
        "evidence",
        "--release",
        release_json,
        "--artifact-dir",
        artifact_directory,
    )
    assert evidence.returncode == 0, evidence.stderr
    pypi = tmp_path / "pypi.json"
    pypi.write_text(
        json.dumps(
            {
                "releases": {
                    "0.1.0": [
                        {
                            "filename": "skywright-0.1.0.tar.gz",
                            "digests": {"sha256": "0" * 64},
                        }
                    ]
                }
            }
        ),
        encoding="utf-8",
    )
    github = tmp_path / "github.json"
    github.write_text("{}", encoding="utf-8")

    completed = release_command(
        release_repository,
        "collisions",
        "--artifact-dir",
        artifact_directory,
        "--pypi-json",
        pypi,
        "--github-release-json",
        github,
    )

    assert completed.returncode == 2
    assert "PyPI version 0.1.0 conflicts" in completed.stderr
