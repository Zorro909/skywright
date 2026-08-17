"""Docker and OCI Distribution adapters for project-CI publication."""

from __future__ import annotations

import base64
import json
import os
import re
import shlex
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from email.message import Message
from pathlib import Path
from typing import cast

from skywright_project_action.identity import sha256_bytes
from skywright_project_action.publication import ArtifactRegistry, ProjectImageBuilder
from skywright_project_action.version import (
    ProjectVersionDefinition,
)

_OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
_OCI_CONFIG = "application/vnd.oci.empty.v1+json"
_ACCEPT_MANIFESTS = ", ".join(
    (
        _OCI_MANIFEST,
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.docker.distribution.manifest.v2+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
    )
)


class OciArtifactRegistry(ArtifactRegistry):
    """Small standard-library OCI Distribution client with fail-closed writes."""

    def __init__(
        self,
        registry_host: str,
        repository_path: str,
        *,
        username: str | None = None,
        password: str | None = None,
    ):
        self._host = registry_host
        self._path = repository_path
        self._username = username
        self._password = password
        self._token: str | None = None

    @classmethod
    def from_environment(cls, repository: str) -> OciArtifactRegistry:
        host, path = _split_repository(repository)
        username = os.environ.get("SKYWRIGHT_REGISTRY_USERNAME") or os.environ.get(
            "GITHUB_ACTOR"
        )
        password = os.environ.get("SKYWRIGHT_REGISTRY_PASSWORD") or os.environ.get(
            "GITHUB_TOKEN"
        )
        return cls(host, path, username=username, password=password)

    def publish_contract(
        self,
        repository: str,
        image_digest: str,
        kind: str,
        content: bytes,
        content_digest: str,
    ) -> str:
        self._assert_repository(repository)
        tag = f"{image_digest.replace(':', '-')}.skywright-{kind}.v1"
        return self._publish_artifact(
            tag,
            f"application/vnd.skywright.project.{kind}.v1+json",
            content,
            content_digest,
            annotations={
                "org.skywright.image.digest": image_digest,
                "org.opencontainers.image.title": f"project-{kind}.json",
            },
        )

    def publish_version(
        self,
        repository: str,
        version_label: str,
        content: bytes,
        content_digest: str,
    ) -> str:
        self._assert_repository(repository)
        tag = f"{content_digest.replace(':', '-')}.skywright-version.v1"
        return self._publish_artifact(
            tag,
            "application/vnd.skywright.project.version.v1+json",
            content,
            content_digest,
            annotations={
                "org.opencontainers.image.revision": version_label.split("-", 1)[0],
                "org.skywright.version.label": version_label,
            },
        )

    def manifest_digest(self, repository: str, reference: str) -> str:
        self._assert_repository(repository)
        status, headers, content = self._request(
            "GET",
            f"/v2/{self._path}/manifests/{urllib.parse.quote(reference, safe=':')}",
            headers={"Accept": _ACCEPT_MANIFESTS},
        )
        if status != 200:
            raise RuntimeError(
                f"registry manifest resolution failed with HTTP {status}"
            )
        return _verified_digest(headers, content)

    def authenticate_container_engine(self) -> None:
        """Project the configured registry credential into Docker without exposing it."""
        if self._username is None or self._password is None:
            raise RuntimeError("registry publication credentials are unavailable")
        subprocess.run(
            (
                "docker",
                "login",
                self._host,
                "--username",
                self._username,
                "--password-stdin",
            ),
            input=self._password,
            text=True,
            check=True,
        )

    def _publish_artifact(
        self,
        tag: str,
        artifact_type: str,
        content: bytes,
        content_digest: str,
        *,
        annotations: dict[str, str],
    ) -> str:
        if sha256_bytes(content) != content_digest:
            raise RuntimeError(
                "artifact content digest does not match its declared identity"
            )
        empty = b"{}"
        self._push_blob(empty)
        self._push_blob(content)
        manifest = json.dumps(
            {
                "schemaVersion": 2,
                "mediaType": _OCI_MANIFEST,
                "artifactType": artifact_type,
                "config": {
                    "mediaType": _OCI_CONFIG,
                    "digest": sha256_bytes(empty),
                    "size": len(empty),
                },
                "layers": [
                    {
                        "mediaType": artifact_type,
                        "digest": content_digest,
                        "size": len(content),
                    }
                ],
                "annotations": annotations,
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
        suffix = f"/v2/{self._path}/manifests/{urllib.parse.quote(tag, safe='')}"
        existing_status, existing_headers, existing = self._request(
            "GET", suffix, headers={"Accept": _OCI_MANIFEST}, allow_not_found=True
        )
        if existing_status == 200:
            if existing != manifest:
                raise RuntimeError(
                    f"immutable OCI artifact tag {tag!r} already differs"
                )
            return _verified_digest(existing_headers, existing)
        status, _, _ = self._request(
            "PUT",
            suffix,
            data=manifest,
            headers={"Content-Type": _OCI_MANIFEST, "If-None-Match": "*"},
            allow_precondition_failed=True,
        )
        if status not in {201, 412}:
            raise RuntimeError(
                f"registry manifest publication failed with HTTP {status}"
            )
        verified_status, verified_headers, verified = self._request(
            "GET", suffix, headers={"Accept": _OCI_MANIFEST}
        )
        if verified_status != 200 or verified != manifest:
            raise RuntimeError(f"immutable OCI artifact tag {tag!r} was replaced")
        return _verified_digest(verified_headers, verified)

    def _push_blob(self, content: bytes) -> None:
        digest = sha256_bytes(content)
        status, _, _ = self._request(
            "HEAD", f"/v2/{self._path}/blobs/{digest}", allow_not_found=True
        )
        if status == 200:
            return
        status, headers, _ = self._request(
            "POST", f"/v2/{self._path}/blobs/uploads/", data=b""
        )
        if status != 202 or "Location" not in headers:
            raise RuntimeError(f"registry blob upload could not start (HTTP {status})")
        upload_base = f"https://{self._host}/v2/{self._path}/blobs/uploads/"
        location = urllib.parse.urljoin(upload_base, headers["Location"])
        separator = "&" if "?" in location else "?"
        status, _, _ = self._request(
            "PUT",
            location,
            data=content,
            headers={"Content-Type": "application/octet-stream"},
            query=f"{separator}digest={urllib.parse.quote(digest, safe=':')}",
        )
        if status != 201:
            raise RuntimeError(f"registry blob publication failed with HTTP {status}")

    def _request(
        self,
        method: str,
        target: str,
        *,
        data: bytes | None = None,
        headers: dict[str, str] | None = None,
        query: str = "",
        allow_not_found: bool = False,
        allow_precondition_failed: bool = False,
        _auth_retry: bool = True,
        _redirects: int = 3,
    ) -> tuple[int, Message, bytes]:
        url = (
            target
            if target.startswith(("http://", "https://"))
            else f"https://{self._host}{target}"
        ) + query
        request_headers = dict(headers or {})
        registry_origin = self._is_registry_origin(url)
        if registry_origin and self._token:
            request_headers["Authorization"] = f"Bearer {self._token}"
        elif (
            registry_origin
            and self._username is not None
            and self._password is not None
        ):
            credentials = base64.b64encode(
                f"{self._username}:{self._password}".encode()
            ).decode()
            request_headers["Authorization"] = f"Basic {credentials}"
        request = urllib.request.Request(
            url, data=data, headers=request_headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            if (
                error.code in {301, 302, 303, 307, 308}
                and method in {"POST", "PUT"}
                and _redirects > 0
                and error.headers.get("Location")
            ):
                redirected = urllib.parse.urljoin(url, error.headers["Location"])
                if urllib.parse.urlsplit(redirected).scheme != "https":
                    raise RuntimeError("registry redirect must use HTTPS") from error
                return self._request(
                    method,
                    redirected,
                    data=data,
                    headers=headers,
                    query="",
                    allow_not_found=allow_not_found,
                    allow_precondition_failed=allow_precondition_failed,
                    _auth_retry=_auth_retry,
                    _redirects=_redirects - 1,
                )
            challenge = error.headers.get("WWW-Authenticate", "")
            if (
                error.code == 401
                and registry_origin
                and _auth_retry
                and challenge.startswith("Bearer ")
            ):
                self._token = self._bearer_token(challenge)
                return self._request(
                    method,
                    target,
                    data=data,
                    headers=headers,
                    query=query,
                    allow_not_found=allow_not_found,
                    allow_precondition_failed=allow_precondition_failed,
                    _auth_retry=False,
                    _redirects=_redirects,
                )
            if allow_not_found and error.code == 404:
                return error.code, error.headers, error.read()
            if allow_precondition_failed and error.code == 412:
                return error.code, error.headers, error.read()
            raise RuntimeError(
                f"registry request failed with HTTP {error.code}"
            ) from error

    def _bearer_token(self, challenge: str) -> str:
        values: dict[str, str] = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
        realm = values.get("realm")
        if realm is None:
            raise RuntimeError("registry bearer challenge has no realm")
        if urllib.parse.urlsplit(realm).scheme != "https":
            raise RuntimeError("registry bearer realm must use HTTPS")
        query = urllib.parse.urlencode(
            {
                "service": values.get("service", ""),
                "scope": values.get("scope", f"repository:{self._path}:pull,push"),
            }
        )
        headers: dict[str, str] = {}
        if self._username is not None and self._password is not None:
            credentials = base64.b64encode(
                f"{self._username}:{self._password}".encode()
            ).decode()
            headers["Authorization"] = f"Basic {credentials}"
        separator = "&" if "?" in realm else "?"
        request = urllib.request.Request(f"{realm}{separator}{query}", headers=headers)
        token: object = None
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                document = json.loads(response.read())
                if isinstance(document, dict):
                    typed_document = cast(dict[str, object], document)
                    token = typed_document.get("token") or typed_document.get(
                        "access_token"
                    )
        except (urllib.error.URLError, ValueError) as error:
            raise RuntimeError("registry bearer token exchange failed") from error
        if not isinstance(token, str) or not token:
            raise RuntimeError("registry bearer token exchange returned no token")
        return token

    def _is_registry_origin(self, url: str) -> bool:
        target = urllib.parse.urlsplit(url)
        registry = urllib.parse.urlsplit(f"https://{self._host}")
        return target.scheme == registry.scheme and target.netloc == registry.netloc

    def _assert_repository(self, repository: str) -> None:
        if _split_repository(repository) != (self._host, self._path):
            raise RuntimeError("publication repository changed after authentication")


class DockerProjectImageBuilder(ProjectImageBuilder):
    """Build thin images from locked dependencies and smoke them before pushing."""

    def __init__(
        self,
        registry: OciArtifactRegistry,
        *,
        source_revision: str,
        pipeline: str,
    ):
        self._registry = registry
        self._source_revision = source_revision
        self._pipeline = pipeline

    def build_smoke_and_push(
        self,
        definition: ProjectVersionDefinition,
        backend: str,
        staging_tag: str,
    ) -> str:
        lock = definition.dependency_lock.relative_to(definition.root).as_posix()
        profile = definition.environment_profiles[backend]
        schema_check = "; ".join(
            (
                "import pathlib, skywright",
                "from skywright.configuration import ConfigurationContract",
                "from skywright.metrics import MetricSchema",
                "assert pathlib.Path(skywright.__file__).is_relative_to('/opt/skywright')",
                "assert ConfigurationContract.skywright_schema_identity() == "
                + repr(definition.configuration_contract.skywright_schema),
                "assert MetricSchema.identity() == "
                + repr(definition.metric_contract.skywright_schema),
            )
        )
        containerfile = (
            "\n".join(
                (
                    f"FROM {profile}",
                    f"LABEL org.opencontainers.image.revision={json.dumps(self._source_revision)}",
                    f"LABEL org.skywright.pipeline={json.dumps(self._pipeline)}",
                    f"LABEL org.skywright.environment-profile={json.dumps(profile)}",
                    f"COPY {json.dumps([lock, '/tmp/skywright-project.lock'])}",
                    "RUN python -m venv --system-site-packages /opt/skywright-project && "
                    "/opt/skywright-project/bin/python -m pip install --no-deps "
                    "--require-hashes -r /tmp/skywright-project.lock",
                    'COPY [".","/workspace"]',
                    "WORKDIR /workspace",
                    'ENV VIRTUAL_ENV="/opt/skywright-project"',
                    'ENV PATH="/opt/skywright-project/bin:${PATH}"',
                    f"RUN python -c {shlex.quote(schema_check)}",
                )
            )
            + "\n"
        )
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "Containerfile"
            path.write_text(containerfile, encoding="utf-8")
            _run(
                "docker",
                "build",
                "--file",
                str(path),
                "--tag",
                staging_tag,
                str(definition.root),
            )
        _run(
            "docker",
            "run",
            "--rm",
            "--entrypoint",
            definition.smoke_command[0],
            staging_tag,
            *definition.smoke_command[1:],
        )
        _run("docker", "push", staging_tag)
        return self._registry.manifest_digest(
            definition.registry_repository, staging_tag.rsplit(":", 1)[1]
        )


def _run(*command: str) -> None:
    subprocess.run(command, check=True)


def _split_repository(repository: str) -> tuple[str, str]:
    host, separator, path = repository.partition("/")
    if not separator or not host or not path:
        raise RuntimeError("registry repository must contain a host and path")
    return host, path


def _verified_digest(headers: Message, content: bytes) -> str:
    computed = sha256_bytes(content)
    declared = headers.get("Docker-Content-Digest")
    if declared is not None and declared != computed:
        raise RuntimeError("registry content digest does not match response bytes")
    return computed


__all__ = ["DockerProjectImageBuilder", "OciArtifactRegistry"]
