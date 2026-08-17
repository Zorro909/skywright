"""All-or-nothing Training Project Version publication coordinator."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass

from skywright_project_action.identity import DIGEST
from skywright_project_action.version import (
    ProjectVersionDefinition,
    ProjectVersionError,
    ProjectVersionFailure,
    ProjectVersionManifest,
)


class ProjectImageBuilder(ABC):
    """CI adapter that builds, smokes, pushes, and resolves one image digest."""

    @abstractmethod
    def build_smoke_and_push(
        self,
        definition: ProjectVersionDefinition,
        backend: str,
        staging_tag: str,
    ) -> str:
        """Return the immutable pushed image digest."""


class ArtifactRegistry(ABC):
    """OCI publication adapter used before the version becomes discoverable."""

    @abstractmethod
    def publish_contract(
        self,
        repository: str,
        image_digest: str,
        kind: str,
        content: bytes,
        content_digest: str,
    ) -> str:
        """Publish one contract and return its OCI manifest digest."""

    @abstractmethod
    def publish_version(
        self,
        repository: str,
        version_label: str,
        content: bytes,
        content_digest: str,
    ) -> str:
        """Atomically expose the complete version as the final operation."""


@dataclass(frozen=True)
class PublishedProjectVersion:
    """Evidence returned after the final discoverable artifact is published."""

    manifest: ProjectVersionManifest
    artifact_digest: str


class ProjectVersionPublisher:
    """All-or-nothing coordinator for the project CI publication seam."""

    def __init__(self, images: ProjectImageBuilder, registry: ArtifactRegistry):
        self._images = images
        self._registry = registry

    def publish(
        self,
        definition: ProjectVersionDefinition,
        *,
        source_revision: str,
        pipeline: str,
    ) -> PublishedProjectVersion:
        ProjectVersionManifest.validate_provenance(
            source_revision=source_revision, pipeline=pipeline
        )
        version_label = f"{source_revision}-{pipeline}"
        images: dict[str, str] = {}
        for backend in definition.backends:
            staging_tag = (
                f"{definition.registry_repository}:{version_label}-{backend}-staging"
            )
            digest = self._images.build_smoke_and_push(definition, backend, staging_tag)
            if DIGEST.fullmatch(digest) is None:
                raise ProjectVersionError(
                    (
                        ProjectVersionFailure(
                            "PROJECT_IMAGE_DIGEST_INVALID", f"/images/{backend}"
                        ),
                    )
                )
            images[backend] = digest

        artifacts: dict[str, dict[str, str]] = {}
        for backend in definition.backends:
            artifacts[backend] = {}
            for kind, contract in (
                ("configuration", definition.configuration_contract),
                ("metrics", definition.metric_contract),
            ):
                artifacts[backend][kind] = self._registry.publish_contract(
                    definition.registry_repository,
                    images[backend],
                    kind,
                    contract.canonical_json.encode(),
                    contract.digest,
                )

        manifest = ProjectVersionManifest.complete(
            definition,
            source_revision=source_revision,
            pipeline=pipeline,
            images=images,
            contract_artifacts=artifacts,
        )
        digest = self._registry.publish_version(
            definition.registry_repository,
            manifest.version_label,
            manifest.canonical_json.encode(),
            manifest.digest,
        )
        if DIGEST.fullmatch(digest) is None:
            raise ProjectVersionError(
                (
                    ProjectVersionFailure(
                        "PROJECT_VERSION_ARTIFACT_DIGEST_INVALID", "/artifactDigest"
                    ),
                )
            )
        return PublishedProjectVersion(manifest, digest)


__all__ = [
    "ArtifactRegistry",
    "ProjectImageBuilder",
    "ProjectVersionPublisher",
    "PublishedProjectVersion",
]
