from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest
from skywright.configuration import ConfigurationContract
from skywright.metrics import MetricSchema

import skywright_project_action.oci as oci
from skywright_project_action.cli import main
from skywright_project_action.publication import ArtifactRegistry, ProjectImageBuilder
from skywright_project_action.version import ProjectVersionDefinition

pytestmark = pytest.mark.system


class SystemRegistry(ArtifactRegistry):
    def __init__(self) -> None:
        self.authenticated = False
        self.contracts: list[str] = []
        self.version_published = False

    def authenticate_container_engine(self) -> None:
        self.authenticated = True

    def publish_contract(
        self,
        repository: str,
        image_digest: str,
        kind: str,
        content: bytes,
        content_digest: str,
    ) -> str:
        self.contracts.append(kind)
        return "sha256:" + ("e" if kind == "configuration" else "f") * 64

    def publish_version(
        self,
        repository: str,
        version_label: str,
        content: bytes,
        content_digest: str,
    ) -> str:
        self.version_published = True
        return "sha256:" + "9" * 64


class SystemImages(ProjectImageBuilder):
    def __init__(self) -> None:
        self.backends: list[str] = []

    def build_smoke_and_push(
        self,
        definition: ProjectVersionDefinition,
        backend: str,
        staging_tag: str,
    ) -> str:
        self.backends.append(backend)
        return "sha256:" + ("c" if backend == "cuda" else "d") * 64


def test_action_publication_entrypoint_runs_the_complete_application_flow(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    subprocess.run(("git", "init", "--quiet"), cwd=tmp_path, check=True)
    subprocess.run(
        ("git", "config", "user.email", "action-test@example.invalid"),
        cwd=tmp_path,
        check=True,
    )
    subprocess.run(
        ("git", "config", "user.name", "Action Test"), cwd=tmp_path, check=True
    )
    configuration: dict[str, object] = {
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
    metrics: dict[str, object] = {
        "contractVersion": 1,
        "skywrightSchema": MetricSchema.identity(),
        "definitions": [],
    }
    (tmp_path / "configuration.json").write_text(json.dumps(configuration))
    (tmp_path / "metrics.json").write_text(json.dumps(metrics))
    (tmp_path / "requirements.lock").write_text(
        "example==1 --hash=sha256:" + "1" * 64 + "\n"
    )
    definition: dict[str, object] = {
        "definitionVersion": 1,
        "projectIdentity": "system-project",
        "registryRepository": "registry.test/owner/project",
        "configurationContract": "configuration.json",
        "metricContract": "metrics.json",
        "dependencyLock": "requirements.lock",
        "smokeCommand": ["python", "-m", "project", "--smoke"],
        "backends": {
            backend: {
                "environmentProfile": f"registry.test/{backend}@sha256:" + digit * 64
            }
            for backend, digit in (("cuda", "a"), ("rocm", "b"))
        },
    }
    source = tmp_path / "skywright-project.json"
    source.write_text(json.dumps(definition))
    subprocess.run(("git", "add", "."), cwd=tmp_path, check=True)
    subprocess.run(
        ("git", "commit", "--quiet", "-m", "fixture"), cwd=tmp_path, check=True
    )
    revision = subprocess.run(
        ("git", "rev-parse", "HEAD"),
        cwd=tmp_path,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()

    registry = SystemRegistry()
    images = SystemImages()

    def registry_from_environment(repository: str) -> SystemRegistry:
        assert repository == "registry.test/owner/project"
        return registry

    def image_builder(
        registry_adapter: object, *, source_revision: str, pipeline: str
    ) -> SystemImages:
        assert registry_adapter is registry
        assert source_revision == revision
        assert pipeline == "system-1"
        return images

    monkeypatch.setattr(
        oci.OciArtifactRegistry,
        "from_environment",
        staticmethod(registry_from_environment),
    )
    monkeypatch.setattr(oci, "DockerProjectImageBuilder", image_builder)
    monkeypatch.setenv("CI", "true")
    monkeypatch.setenv("CI_COMMIT_SHA", revision)
    monkeypatch.setenv("CI_PIPELINE_ID", "system-1")

    assert main(["publish", str(source)]) == 0

    result = json.loads(capsys.readouterr().out)
    assert result["status"] == "runnable"
    assert result["versionLabel"] == f"{revision}-system-1"
    assert result["artifactDigest"] == "sha256:" + "9" * 64
    assert registry.authenticated
    assert registry.contracts == ["configuration", "metrics"] * 2
    assert registry.version_published
    assert images.backends == ["cuda", "rocm"]
