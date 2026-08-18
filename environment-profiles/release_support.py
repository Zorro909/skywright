#!/usr/bin/env python3
"""Validate and prepare coordinated Environment Profile releases."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from enum import Enum
from pathlib import Path
from typing import NamedTuple, cast

STABLE_TAG = re.compile(
    r"^profile-v(?P<version>(?:0|[1-9][0-9]*)\."
    r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))$"
)
RELEASE_INPUTS = (
    "environment-profiles/VERSION",
    "environment-profiles/CHANGELOG.md",
)


class ReleaseError(RuntimeError):
    """Publication would violate the Environment Profile release contract."""


class ValidatedRelease(NamedTuple):
    source_revision: str
    tag: str
    version: str
    release_name: str


class PublicationAction(str, Enum):
    PUBLISH = "publish"
    SKIP = "skip"

    def __str__(self) -> str:
        return self.value


def run_git(repository: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ("git", *arguments),
        cwd=repository,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
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
    repository: Path,
    tag: str,
    main_ref: str,
    expected_revision: str | None,
) -> ValidatedRelease:
    match = STABLE_TAG.fullmatch(tag)
    if match is None:
        raise ReleaseError(
            "release tag must be a strict stable profile-vMAJOR.MINOR.PATCH tag"
        )

    source_revision = run_git(repository, "rev-parse", f"{tag}^{{commit}}")
    if expected_revision is not None:
        expected = run_git(repository, "rev-parse", f"{expected_revision}^{{commit}}")
        if source_revision != expected:
            raise ReleaseError(
                f"tag {tag} does not match expected revision {expected}; "
                f"it resolves to {source_revision}"
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

    tracked_changes = subprocess.run(
        ("git", "diff", "--quiet", source_revision, "--"),
        cwd=repository,
        check=False,
    )
    if tracked_changes.returncode == 1:
        raise ReleaseError(
            "workflow source mutation rejected: working tree differs from the tagged source revision"
        )
    if tracked_changes.returncode != 0:
        raise ReleaseError(
            "cannot verify that the tagged workflow source is unmodified"
        )

    for path in RELEASE_INPUTS:
        committed = committed_file(repository, source_revision, path)
        try:
            checkout = (repository / path).read_bytes()
        except OSError as error:
            raise ReleaseError(f"cannot read release input {path}: {error}") from error
        if checkout != committed:
            raise ReleaseError(
                f"workflow source mutation rejected: working tree differs from {source_revision}:{path}"
            )

    version = match.group("version")
    committed_version = (
        committed_file(repository, source_revision, "environment-profiles/VERSION")
        .decode()
        .strip()
    )
    if committed_version != version:
        raise ReleaseError(
            f"tag version {version} does not match committed Environment Profile version {committed_version}"
        )
    notes = committed_file(
        repository, source_revision, "environment-profiles/CHANGELOG.md"
    ).decode()
    if re.search(rf"^## {re.escape(version)}$", notes, re.MULTILINE) is None:
        raise ReleaseError(f"committed release notes have no '## {version}' section")

    return ValidatedRelease(
        source_revision=source_revision,
        tag=tag,
        version=version,
        release_name=f"Environment Profiles {version}",
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _required_string(mapping: dict[str, object], name: str, context: str) -> str:
    value = mapping.get(name)
    if not isinstance(value, str) or not value:
        raise ReleaseError(f"{context}.{name} must be a non-empty string")
    return value


def create_manifest(
    release: ValidatedRelease,
    definitions: dict[str, object],
    evidence_by_backend: dict[str, dict[str, str]],
    *,
    workflow: dict[str, str],
) -> dict[str, object]:
    if set(evidence_by_backend) != {"cuda", "rocm"}:
        raise ReleaseError("release evidence must contain exactly cuda and rocm")
    raw_profiles = definitions.get("profiles")
    if not isinstance(raw_profiles, dict) or set(raw_profiles) != {"cuda", "rocm"}:
        raise ReleaseError("profile definitions must contain exactly cuda and rocm")
    architecture = _required_string(definitions, "architecture", "definitions")
    pytorch = _required_string(definitions, "pytorch", "definitions")
    sdk = _required_string(definitions, "sdk", "definitions")
    python = _required_string(definitions, "pythonCompatibility", "definitions")
    if workflow.get("revision") != release.source_revision:
        raise ReleaseError("workflow revision must equal the release source revision")
    if set(workflow) != {"name", "path", "revision", "run_id", "run_attempt"} or any(
        not value for value in workflow.values()
    ):
        raise ReleaseError("workflow facts must be complete")

    profiles: dict[str, object] = {}
    for backend in ("cuda", "rocm"):
        definition_value = raw_profiles[backend]
        if not isinstance(definition_value, dict):
            raise ReleaseError(f"definition for {backend} must be an object")
        definition = cast(dict[str, object], definition_value)
        evidence = cast(dict[str, object], evidence_by_backend[backend])
        expected_image = (
            f"ghcr.io/zorro909/skywright-environment:{release.version}-{backend}"
        )
        checks = {
            "backend": backend,
            "architecture": architecture,
            "pytorch": pytorch,
            "sdk": sdk,
            "runtime_version": sdk,
            "source_revision": release.source_revision,
            "image": expected_image,
        }
        labels = {
            "backend": "backend",
            "architecture": "architecture",
            "pytorch": "PyTorch",
            "sdk": "SDK",
            "runtime_version": "runtime command version",
            "source_revision": "source revision",
            "image": "immutable image tag",
        }
        for key, expected in checks.items():
            if evidence.get(key) != expected:
                raise ReleaseError(
                    f"{backend} {labels[key]} fact {evidence.get(key)!r} does not match {expected!r}"
                )
        digest = _required_string(evidence, "digest", f"evidence.{backend}")
        if re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None:
            raise ReleaseError(f"{backend} image digest is not sha256-pinnable")
        base_image = _required_string(definition, "baseImage", f"profiles.{backend}")
        if re.search(r"@sha256:[0-9a-f]{64}$", base_image) is None:
            raise ReleaseError(f"{backend} base image must be digest-pinned")
        profiles[backend] = {
            "image": {
                "repository": "ghcr.io/zorro909/skywright-environment",
                "tag": f"{release.version}-{backend}",
                "digest": digest,
                "consumer_reference": (
                    "ghcr.io/zorro909/skywright-environment@" + digest
                ),
            },
            "compatibility": {
                "accelerator_backend": _required_string(
                    definition, "acceleratorBackend", f"profiles.{backend}"
                ),
                "accelerator_runtime": _required_string(
                    definition, "acceleratorRuntime", f"profiles.{backend}"
                ),
                "architecture": architecture,
                "base_image": base_image,
                "python": python,
                "pytorch": pytorch,
                "sdk": _required_string(evidence, "sdk", f"evidence.{backend}"),
                "runtime_command_version": _required_string(
                    evidence, "runtime_version", f"evidence.{backend}"
                ),
            },
            "source": {
                "containerfile": _required_string(
                    definition, "containerfile", f"profiles.{backend}"
                ),
                "revision": release.source_revision,
            },
            "supply_chain": {
                "sbom": {
                    "filename": _required_string(
                        evidence, "sbom", f"evidence.{backend}"
                    ),
                    "format": "SPDX-2.3-json",
                    "sha256": _required_string(
                        evidence, "sbom_sha256", f"evidence.{backend}"
                    ),
                    "attestation": {
                        "filename": _required_string(
                            evidence, "sbom_attestation", f"evidence.{backend}"
                        ),
                        "format": "in-toto-jsonl",
                        "sha256": _required_string(
                            evidence,
                            "sbom_attestation_sha256",
                            f"evidence.{backend}",
                        ),
                    },
                },
                "provenance": {
                    "filename": _required_string(
                        evidence, "provenance", f"evidence.{backend}"
                    ),
                    "format": "in-toto-jsonl",
                    "sha256": _required_string(
                        evidence, "provenance_sha256", f"evidence.{backend}"
                    ),
                },
            },
        }
    cuda_digest = cast(
        dict[str, object], cast(dict[str, object], profiles["cuda"])["image"]
    )["digest"]
    rocm_digest = cast(
        dict[str, object], cast(dict[str, object], profiles["rocm"])["image"]
    )["digest"]
    if cuda_digest == rocm_digest:
        raise ReleaseError("CUDA and ROCm must resolve to distinct image digests")
    return {
        "schema": "https://skywright.dev/schemas/environment-profile-release/v1",
        "schema_version": 1,
        "media_type": "application/vnd.skywright.environment-profile.release.v1+json",
        "release": {
            "name": release.release_name,
            "tag": release.tag,
            "version": release.version,
        },
        "source_revision": release.source_revision,
        "workflow": dict(sorted(workflow.items())),
        "profiles": profiles,
    }


def qualify_image(
    release: ValidatedRelease,
    definitions: dict[str, object],
    backend: str,
    image_inspect: dict[str, object],
    runtime_facts: dict[str, object],
) -> dict[str, str]:
    raw_profiles = definitions.get("profiles")
    if not isinstance(raw_profiles, dict) or backend not in raw_profiles:
        raise ReleaseError(f"unknown Environment Profile backend {backend!r}")
    raw_definition = raw_profiles[backend]
    if not isinstance(raw_definition, dict):
        raise ReleaseError(f"definition for {backend} must be an object")
    definition = cast(dict[str, object], raw_definition)
    raw_config = image_inspect.get("Config")
    if not isinstance(raw_config, dict) or not isinstance(
        raw_config.get("Labels"), dict
    ):
        raise ReleaseError("image inspection has no OCI labels")
    labels = cast(dict[str, object], raw_config["Labels"])
    architecture = _required_string(definitions, "architecture", "definitions")
    pytorch = _required_string(definitions, "pytorch", "definitions")
    sdk = _required_string(definitions, "sdk", "definitions")
    expected_labels = {
        "org.opencontainers.image.revision": release.source_revision,
        "org.skywright.accelerator-backend": backend,
        "org.skywright.accelerator-runtime": _required_string(
            definition, "acceleratorRuntime", f"profiles.{backend}"
        ),
        "org.skywright.architecture": architecture,
        "org.skywright.base-image": _required_string(
            definition, "baseImage", f"profiles.{backend}"
        ),
        "org.skywright.pytorch": pytorch,
        "org.skywright.sdk": sdk,
    }
    for name, expected in expected_labels.items():
        if labels.get(name) != expected:
            raise ReleaseError(
                f"{backend} image label {name} is {labels.get(name)!r}, expected {expected!r}"
            )
    expected_facts: dict[str, object] = {
        "accelerator_available": False,
        "architecture": "x86_64",
        "pytorch": pytorch,
        "sdk": sdk,
        "source_revision": release.source_revision,
        "runtime_command_version": sdk,
    }
    if runtime_facts.get("accelerator_available") is not False:
        raise ReleaseError(
            f"{backend} qualification must run without accelerator hardware"
        )
    for name, expected in expected_facts.items():
        if runtime_facts.get(name) != expected:
            raise ReleaseError(
                f"{backend} runtime fact {name} is {runtime_facts.get(name)!r}, expected {expected!r}"
            )
    if image_inspect.get("Architecture") != "amd64":
        raise ReleaseError(f"{backend} image architecture must be amd64")
    return {
        "architecture": architecture,
        "backend": backend,
        "pytorch": pytorch,
        "runtime_version": sdk,
        "sdk": sdk,
        "source_revision": release.source_revision,
    }


def verify_handoff(
    directory: Path, checksums_path: Path, expected_files: set[str]
) -> None:
    actual_files = {
        path.name
        for path in directory.iterdir()
        if path.is_file() and path.resolve() != checksums_path.resolve()
    }
    if actual_files != expected_files:
        missing = sorted(expected_files - actual_files)
        unexpected = sorted(actual_files - expected_files)
        raise ReleaseError(
            f"exact handoff mismatch; missing={missing}, unexpected={unexpected}"
        )
    declared: dict[str, str] = {}
    for line in checksums_path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([^/]+)", line)
        if match is None or match.group(2) in declared:
            raise ReleaseError("handoff checksum file is malformed")
        declared[match.group(2)] = match.group(1)
    if set(declared) != expected_files:
        missing = sorted(expected_files - set(declared))
        unexpected = sorted(set(declared) - expected_files)
        raise ReleaseError(
            f"unexpected checksum coverage; missing={missing}, unexpected={unexpected}"
        )
    for filename, expected in declared.items():
        actual = sha256(directory / filename)
        if actual != expected:
            raise ReleaseError(
                f"checksum mismatch for {filename}: expected {expected}, received {actual}"
            )


def publication_action(
    expected_digest: str, existing_digest: str | None
) -> PublicationAction:
    if existing_digest is None:
        return PublicationAction.PUBLISH
    if existing_digest == expected_digest:
        return PublicationAction.SKIP
    raise ReleaseError(
        "conflicting content already exists for immutable release destination: "
        f"expected {expected_digest}, received {existing_digest}"
    )


def publication_plan(
    expected: dict[str, str], existing: dict[str, str]
) -> dict[str, str]:
    unknown = set(existing) - set(expected)
    if unknown:
        raise ReleaseError(
            f"unexpected existing publication destinations: {sorted(unknown)}"
        )
    return {
        destination: str(publication_action(digest, existing.get(destination)))
        for destination, digest in expected.items()
    }


def load_json(path: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"cannot read JSON from {path}: {error}") from error


def load_release(path: Path) -> ValidatedRelease:
    value = load_json(path)
    if not isinstance(value, dict):
        raise ReleaseError(f"{path} is not validated release JSON")
    try:
        return ValidatedRelease(
            source_revision=value["source_revision"],
            tag=value["tag"],
            version=value["version"],
            release_name=value["release_name"],
        )
    except (KeyError, TypeError) as error:
        raise ReleaseError(f"{path} is not validated release JSON") from error


def write_json(path: Path | None, value: object) -> None:
    serialized = json.dumps(value, indent=2, sort_keys=True) + "\n"
    if path is None:
        print(serialized, end="")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(serialized, encoding="utf-8")


def release_notes(repository: Path, release: ValidatedRelease) -> str:
    notes = committed_file(
        repository, release.source_revision, "environment-profiles/CHANGELOG.md"
    ).decode()
    heading = f"## {release.version}"
    match = re.search(rf"^{re.escape(heading)}$", notes, re.MULTILINE)
    if match is None:
        raise ReleaseError(f"committed release notes have no '{heading}' section")
    following = re.search(r"^## ", notes[match.end() :], re.MULTILINE)
    end = match.end() + following.start() if following is not None else len(notes)
    guidance = (
        "\n\nPin one backend image by digest from "
        "`environment-profile-release-manifest.json`; do not consume the version tag "
        "as a mutable deployment reference."
    )
    return notes[match.start() : end].strip() + guidance + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("--repository", type=Path, default=Path.cwd())
    validate.add_argument("--tag", required=True)
    validate.add_argument("--main-ref", default="origin/main")
    validate.add_argument("--expected-revision")
    validate.add_argument("--output", type=Path)
    qualify = commands.add_parser("qualify-image")
    qualify.add_argument("--release", type=Path, required=True)
    qualify.add_argument("--definitions", type=Path, required=True)
    qualify.add_argument("--backend", choices=("cuda", "rocm"), required=True)
    qualify.add_argument("--inspect", type=Path, required=True)
    qualify.add_argument("--facts", type=Path, required=True)
    qualify.add_argument("--output", type=Path)
    manifest = commands.add_parser("manifest")
    manifest.add_argument("--release", type=Path, required=True)
    manifest.add_argument("--definitions", type=Path, required=True)
    manifest.add_argument("--evidence", action="append", required=True)
    manifest.add_argument("--workflow-name", required=True)
    manifest.add_argument("--workflow-path", required=True)
    manifest.add_argument("--workflow-revision", required=True)
    manifest.add_argument("--workflow-run-id", required=True)
    manifest.add_argument("--workflow-run-attempt", required=True)
    manifest.add_argument("--output", type=Path)
    handoff = commands.add_parser("verify-handoff")
    handoff.add_argument("--directory", type=Path, required=True)
    handoff.add_argument("--checksums", type=Path, required=True)
    handoff.add_argument("--expected", action="append", required=True)
    notes = commands.add_parser("notes")
    notes.add_argument("--repository", type=Path, default=Path.cwd())
    notes.add_argument("--release", type=Path, required=True)
    notes.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    try:
        if arguments.command == "validate":
            release = validate_release(
                arguments.repository,
                arguments.tag,
                arguments.main_ref,
                arguments.expected_revision,
            )
            write_json(arguments.output, release._asdict())
        elif arguments.command == "qualify-image":
            definitions = load_json(arguments.definitions)
            inspect = load_json(arguments.inspect)
            facts = load_json(arguments.facts)
            if not all(
                isinstance(value, dict) for value in (definitions, inspect, facts)
            ):
                raise ReleaseError("qualification inputs must be JSON objects")
            qualified_evidence = qualify_image(
                load_release(arguments.release),
                cast(dict[str, object], definitions),
                arguments.backend,
                cast(dict[str, object], inspect),
                cast(dict[str, object], facts),
            )
            write_json(arguments.output, qualified_evidence)
        elif arguments.command == "manifest":
            evidence_by_backend: dict[str, dict[str, str]] = {}
            for item in arguments.evidence:
                backend, separator, filename = item.partition("=")
                if not separator or backend in evidence_by_backend:
                    raise ReleaseError("evidence must use unique BACKEND=PATH values")
                value = load_json(Path(filename))
                if not isinstance(value, dict) or not all(
                    isinstance(key, str) and isinstance(field, str)
                    for key, field in value.items()
                ):
                    raise ReleaseError(f"{filename} is not string-valued evidence JSON")
                evidence_by_backend[backend] = cast(dict[str, str], value)
            definitions = load_json(arguments.definitions)
            if not isinstance(definitions, dict):
                raise ReleaseError("definitions must be a JSON object")
            result = create_manifest(
                load_release(arguments.release),
                cast(dict[str, object], definitions),
                evidence_by_backend,
                workflow={
                    "name": arguments.workflow_name,
                    "path": arguments.workflow_path,
                    "revision": arguments.workflow_revision,
                    "run_id": arguments.workflow_run_id,
                    "run_attempt": arguments.workflow_run_attempt,
                },
            )
            write_json(arguments.output, result)
        elif arguments.command == "verify-handoff":
            verify_handoff(
                arguments.directory, arguments.checksums, set(arguments.expected)
            )
            print("Exact Environment Profile artifact handoff verified.")
        else:
            content = release_notes(
                arguments.repository, load_release(arguments.release)
            )
            if arguments.output:
                arguments.output.write_text(content, encoding="utf-8")
            else:
                print(content, end="")
    except ReleaseError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
