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
from skywright.configuration import ConfigurationContract
from skywright.metrics import MetricSchema

from skywright_project_action.oci import DockerProjectImageBuilder, OciArtifactRegistry
from skywright_project_action.version import ProjectVersionDefinition


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
    requests: list[tuple[str, str, str | None]] = []

    def urlopen(request: object, timeout: int) -> Response:
        typed = cast(urllib.request.Request, request)
        method = typed.get_method()
        requests.append((method, typed.full_url, typed.get_header("Authorization")))
        if "/blobs/" in typed.full_url and method == "HEAD":
            digest = typed.full_url.rsplit("/", 1)[1]
            if digest in blobs:
                return Response(200)
            raise urllib.error.HTTPError(
                typed.full_url, 404, "missing", Message(), io.BytesIO()
            )
        if typed.full_url.endswith("/blobs/uploads/") and method == "POST":
            return Response(202, Location="https://uploads.test/upload/1")
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
    registry = OciArtifactRegistry(
        "registry.test", "owner/project", username="ci", password="secret"
    )
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
    assert any(
        url.startswith("https://uploads.test/") and authorization is None
        for _, url, authorization in requests
    )
    assert any(
        url.startswith("https://registry.test/")
        and authorization is not None
        and authorization.startswith("Basic ")
        for _, url, authorization in requests
    )
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


def test_oci_adapter_bounds_bearer_retries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[str] = []

    def urlopen(request: object, timeout: int) -> Response:
        typed = cast(urllib.request.Request, request)
        requests.append(typed.full_url)
        if typed.full_url.startswith("https://auth.test/token"):
            return Response(200, b'{"token":"rejected"}')
        headers = Message()
        headers["WWW-Authenticate"] = (
            'Bearer realm="https://auth.test/token",service="registry.test"'
        )
        raise urllib.error.HTTPError(
            typed.full_url, 401, "unauthorized", headers, io.BytesIO()
        )

    monkeypatch.setattr(urllib.request, "urlopen", urlopen)

    with pytest.raises(RuntimeError, match="HTTP 401"):
        OciArtifactRegistry("registry.test", "owner/project").manifest_digest(
            "registry.test/owner/project", "tag"
        )

    assert requests == [
        "https://registry.test/v2/owner/project/manifests/tag",
        "https://auth.test/token?service=registry.test&scope=repository%3Aowner%2Fproject%3Apull%2Cpush",
        "https://registry.test/v2/owner/project/manifests/tag",
    ]


def test_oci_adapter_accepts_access_token_and_preserves_realm_query(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[tuple[str, str | None]] = []

    def urlopen(request: object, timeout: int) -> Response:
        typed = cast(urllib.request.Request, request)
        authorization = typed.get_header("Authorization")
        requests.append((typed.full_url, authorization))
        if typed.full_url.startswith("https://auth.test/token?existing=1&"):
            return Response(200, b'{"access_token":"accepted"}')
        if authorization == "Bearer accepted":
            return Response(200, b"{}", Docker_Content_Digest="sha256:" + "a" * 64)
        headers = Message()
        headers["WWW-Authenticate"] = (
            'Bearer realm="https://auth.test/token?existing=1",service="registry.test"'
        )
        raise urllib.error.HTTPError(
            typed.full_url, 401, "unauthorized", headers, io.BytesIO()
        )

    monkeypatch.setattr(urllib.request, "urlopen", urlopen)

    assert (
        OciArtifactRegistry("registry.test", "owner/project").manifest_digest(
            "registry.test/owner/project", "tag"
        )
        == "sha256:" + "a" * 64
    )
    assert requests[1][0].startswith("https://auth.test/token?existing=1&service=")
    assert requests[-1][1] == "Bearer accepted"


def test_oci_adapter_detects_a_manifest_replaced_during_publication(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = OciArtifactRegistry("registry.test", "owner/project")

    def push_blob(content: bytes) -> None:
        return None

    calls = 0

    def request(
        method: str,
        target: str,
        *,
        data: bytes | None = None,
        headers: dict[str, str] | None = None,
        query: str = "",
        allow_not_found: bool = False,
        allow_precondition_failed: bool = False,
        _auth_retry: bool = True,
    ) -> tuple[int, Message, bytes]:
        nonlocal calls
        calls += 1
        if calls == 1:
            return 404, Message(), b""
        if calls == 2:
            assert method == "PUT"
            assert headers is not None and headers["If-None-Match"] == "*"
            return 201, Message(), b""
        return 200, Message(), b'{"competing":"manifest"}'

    monkeypatch.setattr(registry, "_push_blob", push_blob)
    monkeypatch.setattr(registry, "_request", request)
    content = b"{}"

    with pytest.raises(RuntimeError, match="was replaced"):
        registry.publish_contract(
            "registry.test/owner/project",
            "sha256:" + "a" * 64,
            "configuration",
            content,
            "sha256:" + hashlib.sha256(content).hexdigest(),
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

    monkeypatch.setattr("skywright_project_action.oci._run", run)

    digest = DockerProjectImageBuilder(
        registry, source_revision="1" * 40, pipeline="github-123-1"
    ).build_smoke_and_push(definition, "cuda", "registry.test/owner/project:staging")

    assert digest == "sha256:" + "b" * 64
    assert [command[1] for command in commands] == ["build", "run", "push"]
    assert "FROM registry.test/profile@sha256:" in containerfile
    assert "pip install --no-deps --require-hashes" in containerfile
    assert "is_relative_to" in containerfile
    assert (
        cast(str, ConfigurationContract.skywright_schema_identity()["digest"])
        in containerfile
    )
    assert MetricSchema.identity()["digest"] in containerfile
