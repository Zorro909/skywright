#!/usr/bin/env python3
"""Validate and prepare immutable Skywright SDK releases."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import cast

if sys.version_info >= (3, 11):
    import tomllib as toml_reader
else:
    import tomli as toml_reader

STABLE_TAG = re.compile(
    r"^sdk-v(?P<version>(?:0|[1-9][0-9]*)\."
    r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))$"
)


class ReleaseError(RuntimeError):
    """A release cannot proceed without violating the release contract."""


class PublicationAction(str, Enum):
    """A permitted action for one immutable publication destination."""

    PUBLISH = "publish"
    REPAIR = "repair"
    SKIP = "skip"

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True)
class ValidatedRelease:
    package: str
    source_revision: str
    tag: str
    version: str


@dataclass(frozen=True)
class Distribution:
    filename: str
    sha256: str
    size: int


def run_git(repository: Path, *arguments: str, check: bool = True) -> str:
    completed = subprocess.run(
        ("git", *arguments),
        cwd=repository,
        check=False,
        capture_output=True,
        text=True,
    )
    if check and completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise ReleaseError(f"git {' '.join(arguments)} failed: {detail}")
    return completed.stdout.strip()


def committed_file(repository: Path, revision: str, path: str) -> bytes:
    completed = subprocess.run(
        ("git", "show", f"{revision}:{path}"),
        cwd=repository,
        check=False,
        capture_output=True,
    )
    if completed.returncode != 0:
        raise ReleaseError(f"{revision} does not contain committed {path}")
    return completed.stdout


def validate_release(
    repository: Path, tag: str, main_ref: str, expected_revision: str | None
) -> ValidatedRelease:
    match = STABLE_TAG.fullmatch(tag)
    if match is None:
        raise ReleaseError(
            "release tag must be a strict stable sdk-vMAJOR.MINOR.PATCH tag"
        )

    source_revision = run_git(repository, "rev-parse", f"{tag}^{{commit}}")
    if expected_revision is not None:
        expected_commit = run_git(
            repository, "rev-parse", f"{expected_revision}^{{commit}}"
        )
        if source_revision != expected_commit:
            raise ReleaseError(
                f"tag {tag} resolves to {source_revision}, not expected revision "
                f"{expected_commit}"
            )

    ancestry = subprocess.run(
        ("git", "merge-base", "--is-ancestor", source_revision, main_ref),
        cwd=repository,
        check=False,
    )
    if ancestry.returncode == 1:
        raise ReleaseError(f"tag {tag} is not reachable from {main_ref}")
    if ancestry.returncode != 0:
        raise ReleaseError(
            f"cannot establish whether {tag} is reachable from {main_ref}"
        )

    metadata = cast(
        dict[str, object],
        toml_reader.loads(
            committed_file(repository, source_revision, "sdk/pyproject.toml").decode()
        ),
    )
    project = metadata.get("project")
    if not isinstance(project, dict):
        raise ReleaseError("committed sdk/pyproject.toml has no [project] table")
    project_table = cast(dict[str, object], project)
    package = project_table.get("name")
    package_version = project_table.get("version")
    if package != "skywright":
        raise ReleaseError(
            "committed package identity must remain exactly 'skywright'; "
            f"found {package!r}"
        )
    version = match.group("version")
    if package_version != version:
        raise ReleaseError(
            f"tag version {version} does not match committed package version "
            f"{package_version}"
        )

    release_notes = committed_file(
        repository, source_revision, "sdk/CHANGELOG.md"
    ).decode()
    if re.search(rf"^## {re.escape(version)}$", release_notes, re.MULTILINE) is None:
        raise ReleaseError(f"committed release notes have no '## {version}' section")

    return ValidatedRelease(
        package="skywright",
        source_revision=source_revision,
        tag=tag,
        version=version,
    )


def load_json(path: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"cannot read JSON from {path}: {error}") from error


def load_validated_release(path: Path) -> ValidatedRelease:
    value = load_json(path)
    if not isinstance(value, dict):
        raise ReleaseError(f"{path} is not validated release JSON")
    release = cast(dict[str, object], value)
    try:
        fields = tuple(
            release[name] for name in ("package", "source_revision", "tag", "version")
        )
    except (KeyError, TypeError) as error:
        raise ReleaseError(f"{path} is not validated release JSON") from error
    if not all(isinstance(field, str) for field in fields):
        raise ReleaseError(f"{path} has non-string release identity fields")
    package, source_revision, tag, version = cast(tuple[str, str, str, str], fields)
    return ValidatedRelease(package, source_revision, tag, version)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def distributions(artifact_directory: Path, version: str) -> list[Distribution]:
    expected_names = {
        f"skywright-{version}-py3-none-any.whl",
        f"skywright-{version}.tar.gz",
    }
    paths = sorted(
        path
        for path in artifact_directory.iterdir()
        if path.name.endswith((".whl", ".tar.gz"))
    )
    names = {path.name for path in paths}
    if names != expected_names:
        raise ReleaseError(
            "release artifacts must be exactly "
            f"{', '.join(sorted(expected_names))}; found {', '.join(sorted(names)) or 'none'}"
        )
    return [
        Distribution(path.name, sha256(path), path.stat().st_size) for path in paths
    ]


def commit_timestamp(repository: Path, revision: str) -> str:
    timestamp = run_git(repository, "show", "-s", "--format=%cI", revision)
    parsed = datetime.fromisoformat(timestamp)
    return parsed.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def release_notes_section(repository: Path, release: ValidatedRelease) -> str:
    notes = committed_file(
        repository, release.source_revision, "sdk/CHANGELOG.md"
    ).decode()
    heading = f"## {release.version}"
    start = notes.index(heading)
    following = re.search(r"^## ", notes[start + len(heading) :], re.MULTILINE)
    end = (
        start + len(heading) + following.start()
        if following is not None
        else len(notes)
    )
    return notes[start:end].strip()


def create_evidence(
    repository: Path, release_path: Path, artifact_directory: Path
) -> dict[str, object]:
    release = load_validated_release(release_path)
    artifacts = distributions(artifact_directory, release.version)
    manifest: dict[str, object] = {
        "schema_version": 1,
        "package": release.package,
        "version": release.version,
        "tag": release.tag,
        "source_revision": release.source_revision,
        "distributions": [asdict(artifact) for artifact in artifacts],
    }
    (artifact_directory / "release-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (artifact_directory / "SHA256SUMS").write_text(
        "".join(f"{artifact.sha256}  {artifact.filename}\n" for artifact in artifacts),
        encoding="utf-8",
    )
    created = commit_timestamp(repository, release.source_revision)
    for artifact in artifacts:
        purl_name = artifact.filename.replace("%", "%25")
        sbom = {
            "spdxVersion": "SPDX-2.3",
            "dataLicense": "CC0-1.0",
            "SPDXID": "SPDXRef-DOCUMENT",
            "name": artifact.filename,
            "documentNamespace": (
                "https://github.com/Zorro909/skywright/releases/download/"
                f"{release.tag}/{purl_name}.spdx-{artifact.sha256}"
            ),
            "creationInfo": {
                "created": created,
                "creators": ["Tool: skywright-sdk-release-support-1"],
            },
            "packages": [
                {
                    "name": release.package,
                    "SPDXID": "SPDXRef-Package-skywright",
                    "versionInfo": release.version,
                    "downloadLocation": (
                        "https://files.pythonhosted.org/packages/" + artifact.filename
                    ),
                    "filesAnalyzed": False,
                    "checksums": [
                        {
                            "algorithm": "SHA256",
                            "checksumValue": artifact.sha256,
                        }
                    ],
                    "licenseConcluded": "MIT",
                    "licenseDeclared": "MIT",
                    "copyrightText": "NOASSERTION",
                    "externalRefs": [
                        {
                            "referenceCategory": "PACKAGE-MANAGER",
                            "referenceType": "purl",
                            "referenceLocator": (
                                f"pkg:pypi/{release.package}@{release.version}"
                            ),
                        }
                    ],
                }
            ],
            "relationships": [
                {
                    "spdxElementId": "SPDXRef-DOCUMENT",
                    "relationshipType": "DESCRIBES",
                    "relatedSpdxElement": "SPDXRef-Package-skywright",
                }
            ],
        }
        (artifact_directory / f"{artifact.filename}.spdx.json").write_text(
            json.dumps(sbom, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    notes = release_notes_section(repository, release)
    (artifact_directory / "RELEASE.md").write_text(
        f"{notes}\n\n### Verification\n\n"
        "Download the distributions and `SHA256SUMS`, then run "
        "`sha256sum --check SHA256SUMS`. Verify GitHub artifact provenance with "
        f"`gh attestation verify skywright-{release.version}* --repo "
        "Zorro909/skywright`. PyPI publish attestations are available from each "
        "release file's integrity details.\n",
        encoding="utf-8",
    )
    return manifest


def manifest_distributions(artifact_directory: Path) -> tuple[str, dict[str, str]]:
    unknown_manifest = load_json(artifact_directory / "release-manifest.json")
    if not isinstance(unknown_manifest, dict):
        raise ReleaseError("release-manifest.json has an invalid shape")
    manifest = cast(dict[str, object], unknown_manifest)
    try:
        version = manifest["version"]
        unknown_distributions = manifest["distributions"]
    except (KeyError, TypeError) as error:
        raise ReleaseError("release-manifest.json has an invalid shape") from error
    if not isinstance(version, str) or not isinstance(unknown_distributions, list):
        raise ReleaseError("release-manifest.json has invalid distribution values")
    values: dict[str, str] = {}
    for unknown_item in cast(list[object], unknown_distributions):
        if not isinstance(unknown_item, dict):
            raise ReleaseError("release-manifest.json has an invalid distribution")
        item = cast(dict[str, object], unknown_item)
        filename = item.get("filename")
        digest = item.get("sha256")
        if not isinstance(filename, str) or not isinstance(digest, str):
            raise ReleaseError("release-manifest.json has invalid distribution values")
        values[filename] = digest
    return version, values


def verify_handoff(artifact_directory: Path) -> None:
    version, expected = manifest_distributions(artifact_directory)
    actual = {
        artifact.filename: artifact.sha256
        for artifact in distributions(artifact_directory, version)
    }
    for name, digest in expected.items():
        if actual.get(name) != digest:
            raise ReleaseError(f"artifact handoff changed {name}")
    if actual != expected:
        raise ReleaseError("artifact handoff changed the distribution set")


def published_pypi_files(metadata: object, version: str) -> dict[str, str] | None:
    if not isinstance(metadata, dict):
        return None
    document = cast(dict[str, object], metadata)
    try:
        releases = document["releases"]
    except (KeyError, TypeError):
        return None
    if not isinstance(releases, dict):
        raise ReleaseError("PyPI release metadata has an invalid releases table")
    releases_by_version = cast(dict[str, object], releases)
    if version not in releases_by_version:
        return None
    release_files = releases_by_version[version]
    if not isinstance(release_files, list):
        raise ReleaseError("PyPI release metadata has an invalid file list")
    try:
        result: dict[str, str] = {}
        for unknown_item in cast(list[object], release_files):
            if not isinstance(unknown_item, dict):
                raise ReleaseError("PyPI release metadata has an invalid file")
            item = cast(dict[str, object], unknown_item)
            filename = item["filename"]
            digests = item["digests"]
            if not isinstance(filename, str) or not isinstance(digests, dict):
                raise ReleaseError("PyPI release file has invalid identity fields")
            digest = cast(dict[str, object], digests).get("sha256")
            if not isinstance(digest, str):
                raise ReleaseError("PyPI release file has no SHA-256 digest")
            result[filename] = digest
        return result
    except (KeyError, TypeError) as error:
        raise ReleaseError("PyPI release metadata has an invalid shape") from error


def published_github_files(
    metadata: object,
) -> tuple[dict[str, str], frozenset[str]] | None:
    if not isinstance(metadata, dict) or "assets" not in metadata:
        return None
    document = cast(dict[str, object], metadata)
    assets = document["assets"]
    if not isinstance(assets, list):
        raise ReleaseError("GitHub Release metadata has an invalid assets list")
    try:
        result: dict[str, str] = {}
        names: set[str] = set()
        for unknown_asset in cast(list[object], assets):
            if not isinstance(unknown_asset, dict):
                raise ReleaseError("GitHub Release metadata has an invalid asset")
            asset = cast(dict[str, object], unknown_asset)
            name = asset["name"]
            if not isinstance(name, str):
                raise ReleaseError("GitHub Release asset has an invalid name")
            names.add(name)
            digest = asset.get("digest")
            if isinstance(digest, str) and digest.startswith("sha256:"):
                result[name] = digest.removeprefix("sha256:")
        return result, frozenset(names)
    except (KeyError, TypeError) as error:
        raise ReleaseError("GitHub Release metadata has an invalid shape") from error


def collision_plan(
    artifact_directory: Path, pypi_path: Path, github_path: Path
) -> dict[str, PublicationAction]:
    verify_handoff(artifact_directory)
    version, expected = manifest_distributions(artifact_directory)
    pypi_files = published_pypi_files(load_json(pypi_path), version)
    if pypi_files is not None and pypi_files != expected:
        raise ReleaseError(f"PyPI version {version} conflicts with verified artifacts")
    github_release = published_github_files(load_json(github_path))
    github_state = PublicationAction.PUBLISH
    if github_release is not None:
        github_files, github_names = github_release
        deterministic_evidence = {
            path.name: sha256(path)
            for path in (
                artifact_directory / "SHA256SUMS",
                artifact_directory / "release-manifest.json",
                *artifact_directory.glob("*.spdx.json"),
            )
        }
        immutable_files = expected | deterministic_evidence
        if any(
            name in github_names and github_files.get(name) != digest
            for name, digest in immutable_files.items()
        ) or any(name not in github_names for name in expected):
            raise ReleaseError(
                f"GitHub Release for version {version} conflicts with verified artifacts"
            )
        required_names = frozenset(immutable_files) | frozenset(
            (
                "provenance.intoto.jsonl",
                "wheel-sbom.intoto.jsonl",
                "source-sbom.intoto.jsonl",
            )
        )
        github_state = (
            PublicationAction.SKIP
            if required_names <= github_names
            else PublicationAction.REPAIR
        )
    return {
        "github": github_state,
        "pypi": (
            PublicationAction.SKIP
            if pypi_files is not None
            else PublicationAction.PUBLISH
        ),
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument(
        "--repository", type=Path, default=Path(__file__).resolve().parent.parent
    )
    subcommands = result.add_subparsers(dest="command", required=True)
    validate = subcommands.add_parser("validate")
    validate.add_argument("--tag", required=True)
    validate.add_argument("--main-ref", required=True)
    validate.add_argument("--expected-revision")
    evidence = subcommands.add_parser("evidence")
    evidence.add_argument("--release", type=Path, required=True)
    evidence.add_argument("--artifact-dir", type=Path, required=True)
    handoff = subcommands.add_parser("verify-handoff")
    handoff.add_argument("--artifact-dir", type=Path, required=True)
    collisions = subcommands.add_parser("collisions")
    collisions.add_argument("--artifact-dir", type=Path, required=True)
    collisions.add_argument("--pypi-json", type=Path, required=True)
    collisions.add_argument("--github-release-json", type=Path, required=True)
    collisions.add_argument("--github-output", type=Path)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "validate":
            validated = validate_release(
                arguments.repository.resolve(),
                arguments.tag,
                arguments.main_ref,
                arguments.expected_revision,
            )
            print(json.dumps(asdict(validated), sort_keys=True))
            return 0
        if arguments.command == "evidence":
            manifest = create_evidence(
                arguments.repository.resolve(),
                arguments.release.resolve(),
                arguments.artifact_dir.resolve(),
            )
            print(json.dumps(manifest, sort_keys=True))
            return 0
        if arguments.command == "verify-handoff":
            verify_handoff(arguments.artifact_dir.resolve())
            return 0
        if arguments.command == "collisions":
            plan = collision_plan(
                arguments.artifact_dir.resolve(),
                arguments.pypi_json.resolve(),
                arguments.github_release_json.resolve(),
            )
            if arguments.github_output is not None:
                with arguments.github_output.open("a", encoding="utf-8") as output:
                    output.write(
                        "".join(f"{name}={value}\n" for name, value in plan.items())
                    )
            print(json.dumps(plan, sort_keys=True))
            return 0
        raise AssertionError(f"unhandled command: {arguments.command}")
    except ReleaseError as error:
        print(f"release rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
