from __future__ import annotations

import json
from pathlib import Path

import pytest

from skywright.configuration import ConfigurationContract
from skywright.metrics import MetricSchema
from skywright.project import (
    ArtifactRegistry,
    ProjectImageBuilder,
    ProjectVersionDefinition,
    ProjectVersionError,
    ProjectVersionManifest,
    ProjectVersionPublisher,
    main,
)

REPOSITORY = Path(__file__).resolve().parents[2]


def configuration_contract() -> dict[str, object]:
    return {
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


def metric_contract() -> dict[str, object]:
    return {
        "contractVersion": 1,
        "skywrightSchema": MetricSchema.identity(),
        "definitions": [],
    }


def definition(tmp_path: Path) -> dict[str, object]:
    (tmp_path / "project-configuration.json").write_text(
        json.dumps(configuration_contract()), encoding="utf-8"
    )
    (tmp_path / "project-metrics.json").write_text(
        json.dumps(metric_contract()), encoding="utf-8"
    )
    (tmp_path / "requirements.lock").write_text(
        "example-dependency==1.2.3 --hash=sha256:" + "1" * 64 + "\n",
        encoding="utf-8",
    )
    return {
        "definitionVersion": 1,
        "projectIdentity": "stable-project",
        "registryRepository": "ghcr.io/example/stable-project",
        "configurationContract": "project-configuration.json",
        "metricContract": "project-metrics.json",
        "dependencyLock": "requirements.lock",
        "smokeCommand": ["python", "-m", "project", "--smoke"],
        "backends": {
            "cuda": {
                "environmentProfile": "ghcr.io/example/environment:1-cuda@sha256:"
                + "a" * 64
            },
            "rocm": {
                "environmentProfile": "ghcr.io/example/environment:1-rocm@sha256:"
                + "b" * 64
            },
        },
    }


def test_definition_validates_both_exact_contracts_and_locked_profiles(
    tmp_path: Path,
) -> None:
    compiled = ProjectVersionDefinition.compile(definition(tmp_path), tmp_path)

    assert compiled.project_identity == "stable-project"
    assert compiled.backends == ("cuda", "rocm")
    assert compiled.configuration_contract.skywright_schema == (
        ConfigurationContract.skywright_schema_identity()
    )
    assert compiled.metric_contract.skywright_schema == MetricSchema.identity()
    assert compiled.configuration_contract.digest.startswith("sha256:")
    assert compiled.metric_contract.digest.startswith("sha256:")


def test_definition_rejects_sdk_replacement_and_unpinned_profiles(
    tmp_path: Path,
) -> None:
    source = definition(tmp_path)
    (tmp_path / "requirements.lock").write_text(
        "skywright==99.0 --hash=sha256:" + "1" * 64 + "\n", encoding="utf-8"
    )
    source["backends"] = {
        "cuda": {"environmentProfile": "ghcr.io/example/environment:latest"}
    }

    with pytest.raises(ProjectVersionError) as raised:
        ProjectVersionDefinition.compile(source, tmp_path)

    assert {item.code for item in raised.value.errors} == {
        "PROJECT_DEPENDENCY_REPLACES_SKYWRIGHT",
        "PROJECT_PROFILE_NOT_DIGEST_PINNED",
    }


def test_complete_manifest_is_canonical_and_fails_closed_for_capabilities(
    tmp_path: Path,
) -> None:
    compiled = ProjectVersionDefinition.compile(definition(tmp_path), tmp_path)
    manifest = ProjectVersionManifest.complete(
        compiled,
        source_revision="1" * 40,
        pipeline="github-123-1",
        images={"cuda": "sha256:" + "c" * 64, "rocm": "sha256:" + "d" * 64},
        contract_artifacts={
            "cuda": {
                "configuration": "sha256:" + "e" * 64,
                "metrics": "sha256:" + "f" * 64,
            },
            "rocm": {
                "configuration": "sha256:" + "0" * 64,
                "metrics": "sha256:" + "2" * 64,
            },
        },
    )

    assert manifest.version_label == f"{'1' * 40}-github-123-1"
    assert json.loads(manifest.canonical_json)["acceleratorBackends"] == [
        "cuda",
        "rocm",
    ]
    assert manifest.image_for({"acceleratorBackend": "cuda"}) == "sha256:" + "c" * 64
    with pytest.raises(ProjectVersionError) as raised:
        manifest.image_for({"acceleratorBackend": "tpu"})
    assert raised.value.errors[0].code == "PROJECT_CAPABILITIES_INCOMPATIBLE"

    with pytest.raises(ProjectVersionError) as incomplete:
        ProjectVersionManifest.complete(
            compiled,
            source_revision="1" * 40,
            pipeline="github-123-1",
            images={"cuda": "sha256:" + "c" * 64},
            contract_artifacts={},
        )
    assert {item.code for item in incomplete.value.errors} == {
        "PROJECT_IMAGE_MISSING",
        "PROJECT_CONTRACT_ARTIFACT_MISSING",
    }


class RecordingImages(ProjectImageBuilder):
    def __init__(self, *, fail_backend: str | None = None):
        self.fail_backend = fail_backend
        self.built: list[str] = []

    def build_smoke_and_push(
        self,
        definition: ProjectVersionDefinition,
        backend: str,
        staging_tag: str,
    ) -> str:
        self.built.append(backend)
        if backend == self.fail_backend:
            raise RuntimeError("build failed")
        digit = "c" if backend == "cuda" else "d"
        return "sha256:" + digit * 64


class RecordingRegistry(ArtifactRegistry):
    def __init__(self) -> None:
        self.contracts: list[tuple[str, str]] = []
        self.versions: list[str] = []

    def publish_contract(
        self,
        repository: str,
        image_digest: str,
        kind: str,
        content: bytes,
        content_digest: str,
    ) -> str:
        self.contracts.append((image_digest, kind))
        return "sha256:" + ("e" if kind == "configuration" else "f") * 64

    def publish_version(
        self, repository: str, version_label: str, content: bytes, content_digest: str
    ) -> str:
        self.versions.append(version_label)
        return "sha256:" + "9" * 64


def test_publisher_exposes_the_version_only_after_every_piece_succeeds(
    tmp_path: Path,
) -> None:
    compiled = ProjectVersionDefinition.compile(definition(tmp_path), tmp_path)
    images = RecordingImages()
    registry = RecordingRegistry()

    published = ProjectVersionPublisher(images, registry).publish(
        compiled, source_revision="1" * 40, pipeline="github-123-1"
    )

    assert images.built == ["cuda", "rocm"]
    assert registry.contracts == [
        ("sha256:" + "c" * 64, "configuration"),
        ("sha256:" + "c" * 64, "metrics"),
        ("sha256:" + "d" * 64, "configuration"),
        ("sha256:" + "d" * 64, "metrics"),
    ]
    assert registry.versions == [published.manifest.version_label]

    failed_registry = RecordingRegistry()
    with pytest.raises(RuntimeError, match="build failed"):
        ProjectVersionPublisher(
            RecordingImages(fail_backend="rocm"), failed_registry
        ).publish(compiled, source_revision="1" * 40, pipeline="github-123-1")
    assert failed_registry.versions == []


def test_project_ci_command_validates_and_rejects_publication_outside_ci(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "skywright-project.json"
    source.write_text(json.dumps(definition(tmp_path)), encoding="utf-8")

    assert main(["validate", str(source)]) == 0
    output = json.loads(capsys.readouterr().out)
    assert output["status"] == "runnable"
    assert output["acceleratorBackends"] == ["cuda", "rocm"]

    monkeypatch.delenv("CI", raising=False)
    monkeypatch.delenv("GITHUB_ACTIONS", raising=False)
    assert main(["publish", str(source)]) == 2
    failure = json.loads(capsys.readouterr().err)
    assert failure == {
        "errors": [{"code": "PROJECT_PUBLICATION_REQUIRES_CI", "pointer": "/ci"}],
        "status": "not-runnable",
    }


def test_maintained_environment_profiles_pin_distinct_backend_bases() -> None:
    manifest = json.loads(
        (REPOSITORY / "environment-profiles/manifest.json").read_text(encoding="utf-8")
    )

    assert manifest["profileDefinitionVersion"] == 1
    assert set(manifest["profiles"]) == {"cuda", "rocm"}
    for backend in ("cuda", "rocm"):
        profile = manifest["profiles"][backend]
        assert profile["acceleratorBackend"] == backend
        assert "@sha256:" in profile["baseImage"]
        containerfile = (
            REPOSITORY / f"environment-profiles/{backend}/Containerfile"
        ).read_text(encoding="utf-8")
        assert f"FROM {profile['baseImage']}" in containerfile
        assert "uv sync" in containerfile
        assert "--locked" in containerfile
        assert "skywright-runtime --help" in containerfile
