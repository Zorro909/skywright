from __future__ import annotations

import hashlib
import io
import json
import urllib.error
import urllib.request
from email.message import Message
from pathlib import Path
from typing import cast

import pytest

from skywright._project_oci import DockerProjectImageBuilder, OciArtifactRegistry
from skywright.configuration import ConfigurationContract
from skywright.metrics import MetricSchema
from skywright.project import ProjectVersionDefinition


class Response:
    def __init__(self, status: int, content: bytes = b"", **headers: str):
        self.status = status
        self.headers = Message()
        for name, value in headers.items():
            self.headers[name.replace("_", "-")] = value
        self._content = content

    def read(self) -> bytes:
        return self._content

    def __enter__(self) -> Response:
        return self

    def __exit__(self, *args: object) -> None:
        return None


def test_oci_adapter_publishes_content_before_an_immutable_manifest(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    blobs: set[str] = set()
    manifests: dict[str, bytes] = {}
    requests: list[tuple[str, str]] = []

    def urlopen(request: object, timeout: int) -> Response:
        typed = cast(urllib.request.Request, request)
        method = typed.get_method()
        requests.append((method, typed.full_url))
        if "/blobs/" in typed.full_url and method == "HEAD":
            digest = typed.full_url.rsplit("/", 1)[1]
            if digest in blobs:
                return Response(200)
            raise urllib.error.HTTPError(
                typed.full_url, 404, "missing", Message(), io.BytesIO()
            )
        if typed.full_url.endswith("/blobs/uploads/") and method == "POST":
            return Response(202, Location="https://registry.test/upload/1")
        if "/upload/1?digest=" in typed.full_url and method == "PUT":
            blobs.add(typed.full_url.split("digest=", 1)[1].replace("%3A", ":"))
            return Response(201)
        if "/manifests/" in typed.full_url:
            tag = typed.full_url.rsplit("/", 1)[1]
            if method == "GET" and tag in manifests:
                return Response(200, manifests[tag])
            if method == "GET":
                raise urllib.error.HTTPError(
                    typed.full_url, 404, "missing", Message(), io.BytesIO()
                )
            manifests[tag] = cast(bytes, typed.data)
            return Response(201)
        raise AssertionError(f"unexpected request {method} {typed.full_url}")

    monkeypatch.setattr(urllib.request, "urlopen", urlopen)
    registry = OciArtifactRegistry("registry.test", "owner/project")
    content = b'{"contractVersion":1}'
    digest = "sha256:" + hashlib.sha256(content).hexdigest()
    artifact = registry.publish_contract(
        "registry.test/owner/project",
        "sha256:" + "a" * 64,
        "configuration",
        content,
        digest,
    )

    assert artifact.startswith("sha256:")
    assert list(manifests) == ["sha256-" + "a" * 64 + ".skywright-configuration.v1"]
    assert requests[-1][0] == "PUT"
    assert (
        registry.publish_contract(
            "registry.test/owner/project",
            "sha256:" + "a" * 64,
            "configuration",
            content,
            digest,
        )
        == artifact
    )

    version_content = b'{"manifestVersion":1}'
    version_digest = "sha256:" + hashlib.sha256(version_content).hexdigest()
    version_artifact = registry.publish_version(
        "registry.test/owner/project",
        "1" * 40 + "-github-123-1",
        version_content,
        version_digest,
    )

    assert version_artifact.startswith("sha256:")
    assert (
        f"sha256-{version_digest.removeprefix('sha256:')}.skywright-version.v1"
        in manifests
    )


def test_docker_adapter_builds_from_the_profile_and_checks_sdk_authority(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    configuration = tmp_path / "configuration.json"
    configuration.write_text(
        json.dumps(
            {
                "contractVersion": 1,
                "skywrightSchema": ConfigurationContract.skywright_schema_identity(),
                "projectSchema": {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {},
                },
                "defaults": {},
                "defaultsCompletionWitness": {},
                "references": {},
            }
        ),
        encoding="utf-8",
    )
    metrics = tmp_path / "metrics.json"
    metrics.write_text(
        json.dumps(
            {
                "contractVersion": 1,
                "skywrightSchema": MetricSchema.identity(),
                "definitions": [],
            }
        ),
        encoding="utf-8",
    )
    (tmp_path / "requirements.lock").write_text(
        "example==1 --hash=sha256:" + "1" * 64 + "\n", encoding="utf-8"
    )
    definition = ProjectVersionDefinition.compile(
        {
            "definitionVersion": 1,
            "projectIdentity": "project",
            "registryRepository": "registry.test/owner/project",
            "configurationContract": "configuration.json",
            "metricContract": "metrics.json",
            "dependencyLock": "requirements.lock",
            "smokeCommand": ["python", "-m", "project", "--smoke"],
            "backends": {
                "cuda": {
                    "environmentProfile": "registry.test/profile@sha256:" + "a" * 64
                }
            },
        },
        tmp_path,
    )
    registry = OciArtifactRegistry("registry.test", "owner/project")

    def manifest_digest(repository: str, reference: str) -> str:
        return "sha256:" + "b" * 64

    monkeypatch.setattr(registry, "manifest_digest", manifest_digest)
    commands: list[tuple[str, ...]] = []
    containerfile = ""

    def run(*command: str) -> None:
        nonlocal containerfile
        commands.append(command)
        if command[1] == "build":
            containerfile = Path(command[command.index("--file") + 1]).read_text()

    monkeypatch.setattr("skywright._project_oci._run", run)

    digest = DockerProjectImageBuilder(
        registry, source_revision="1" * 40, pipeline="github-123-1"
    ).build_smoke_and_push(definition, "cuda", "registry.test/owner/project:staging")

    assert digest == "sha256:" + "b" * 64
    assert [command[1] for command in commands] == ["build", "run", "push"]
    assert "FROM registry.test/profile@sha256:" in containerfile
    assert "pip install --no-deps --require-hashes" in containerfile
    assert "is_relative_to('/opt/skywright')" in containerfile
