"""Process entry point hidden behind the reusable GitHub Action."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path

from skywright_project_action.publication import ProjectVersionPublisher
from skywright_project_action.version import (
    ProjectVersionDefinition,
    ProjectVersionError,
    ProjectVersionFailure,
)


def main(arguments: Sequence[str] | None = None) -> int:
    """Validate a committed definition or publish it from a recognized CI process."""
    args = list(sys.argv[1:] if arguments is None else arguments)
    if len(args) != 2 or args[0] not in {"validate", "publish"}:
        print(
            "usage: publish-training-project validate DEFINITION | "
            "publish-training-project publish DEFINITION",
            file=sys.stderr,
        )
        return 64
    definition_path = Path(args[1]).resolve()
    try:
        definition = ProjectVersionDefinition.compile(
            definition_path.read_bytes(), definition_path.parent
        )
        if args[0] == "validate":
            _print(
                {
                    "status": "runnable",
                    "projectIdentity": definition.project_identity,
                    "acceleratorBackends": list(definition.backends),
                    "configurationContract": {
                        "digest": definition.configuration_contract.digest,
                        "skywrightSchema": definition.configuration_contract.skywright_schema,
                    },
                    "metricContract": {
                        "digest": definition.metric_contract.digest,
                        "skywrightSchema": definition.metric_contract.skywright_schema,
                    },
                }
            )
            return 0
        provenance = _ci_provenance(definition.root)
        from skywright_project_action.oci import (
            DockerProjectImageBuilder,
            OciArtifactRegistry,
        )

        registry = OciArtifactRegistry.from_environment(definition.registry_repository)
        registry.authenticate_container_engine()
        publisher = ProjectVersionPublisher(
            DockerProjectImageBuilder(
                registry,
                source_revision=provenance["sourceRevision"],
                pipeline=provenance["pipeline"],
            ),
            registry,
        )
        published = publisher.publish(
            definition,
            source_revision=provenance["sourceRevision"],
            pipeline=provenance["pipeline"],
        )
        _print(
            {
                "status": "runnable",
                "versionLabel": published.manifest.version_label,
                "manifestDigest": published.manifest.digest,
                "artifactDigest": published.artifact_digest,
                "images": published.manifest.images,
            }
        )
        return 0
    except (
        OSError,
        ProjectVersionError,
        RuntimeError,
        subprocess.SubprocessError,
    ) as error:
        failures = (
            error.errors
            if isinstance(error, ProjectVersionError)
            else (ProjectVersionFailure("PROJECT_PUBLICATION_FAILED", ""),)
        )
        _print(
            {
                "status": "not-runnable",
                "errors": [
                    {"code": item.code, "pointer": item.pointer} for item in failures
                ],
            },
            stderr=True,
        )
        return 2


def _ci_provenance(project_root: Path) -> dict[str, str]:
    if os.environ.get("CI") != "true" and os.environ.get("GITHUB_ACTIONS") != "true":
        raise ProjectVersionError(
            (ProjectVersionFailure("PROJECT_PUBLICATION_REQUIRES_CI", "/ci"),)
        )
    revision = os.environ.get("GITHUB_SHA") or os.environ.get("CI_COMMIT_SHA", "")
    github_run = os.environ.get("GITHUB_RUN_ID")
    if github_run:
        pipeline = f"github-{github_run}-{os.environ.get('GITHUB_RUN_ATTEMPT', '1')}"
    else:
        pipeline = os.environ.get("CI_PIPELINE_ID", "")
    try:
        head = subprocess.run(
            ("git", "rev-parse", "HEAD"),
            check=True,
            capture_output=True,
            text=True,
            cwd=project_root,
        ).stdout.strip()
        dirty = subprocess.run(
            ("git", "status", "--porcelain"),
            check=True,
            capture_output=True,
            text=True,
            cwd=project_root,
        ).stdout
    except subprocess.SubprocessError as error:
        raise ProjectVersionError(
            (ProjectVersionFailure("PROJECT_SOURCE_UNAVAILABLE", "/sourceRevision"),)
        ) from error
    failures: list[ProjectVersionFailure] = []
    if revision != head:
        failures.append(
            ProjectVersionFailure("PROJECT_SOURCE_REVISION_MISMATCH", "/sourceRevision")
        )
    if dirty:
        failures.append(
            ProjectVersionFailure("PROJECT_SOURCE_DIRTY", "/sourceRevision")
        )
    if not pipeline:
        failures.append(ProjectVersionFailure("PROJECT_PIPELINE_INVALID", "/pipeline"))
    if failures:
        raise ProjectVersionError(failures)
    return {"sourceRevision": revision, "pipeline": pipeline}


def _print(value: Mapping[str, object], *, stderr: bool = False) -> None:
    print(
        json.dumps(value, separators=(",", ":"), sort_keys=True),
        file=sys.stderr if stderr else sys.stdout,
    )


__all__ = ["main"]
