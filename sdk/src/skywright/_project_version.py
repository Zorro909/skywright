"""Immutable Training Project Version definitions and published manifests."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import cast

from skywright._configuration_contract import (
    ConfigurationContract,
    ConfigurationContractError,
)
from skywright._metric_contract import MetricContract, MetricContractError

_DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
_PROFILE = re.compile(r"[^@\s]+@sha256:[0-9a-f]{64}\Z")
_PROJECT_IDENTITY = re.compile(r"[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?\Z")
_LOCKED_SKYWRIGHT = re.compile(
    r"(?im)^\s*(?:skywright|skywright\[[^]]+\])\s*(?:==|@|$)"
)


@dataclass(frozen=True)
class ProjectVersionFailure:
    """One stable reason a Training Project Version cannot run."""

    code: str
    pointer: str


class ProjectVersionError(ValueError):
    """Stable, ordered failures in a version definition or manifest."""

    def __init__(self, errors: Sequence[ProjectVersionFailure]):
        self.errors: tuple[ProjectVersionFailure, ...] = tuple(
            sorted(errors, key=lambda item: (item.pointer, item.code))
        )
        super().__init__("; ".join(item.code for item in self.errors))


@dataclass(frozen=True)
class ContractArtifact:
    """Validated canonical contract bytes and their exact schema identity."""

    canonical_json: str
    digest: str
    skywright_schema: dict[str, object]


@dataclass(frozen=True)
class ProjectVersionDefinition:
    """Committed project-CI inputs for one complete version build."""

    project_identity: str
    registry_repository: str
    root: Path
    dependency_lock: Path
    smoke_command: tuple[str, ...]
    environment_profiles: dict[str, str]
    configuration_contract: ContractArtifact
    metric_contract: ContractArtifact

    @property
    def backends(self) -> tuple[str, ...]:
        return tuple(sorted(self.environment_profiles))

    @classmethod
    def compile(
        cls, source: str | bytes | Mapping[str, object], root: Path
    ) -> ProjectVersionDefinition:
        try:
            document = _object(source)
        except (TypeError, ValueError) as error:
            raise ProjectVersionError(
                (ProjectVersionFailure("PROJECT_DEFINITION_INVALID", ""),)
            ) from error
        failures: list[ProjectVersionFailure] = []
        if document.get("definitionVersion") != 1:
            failures.append(
                ProjectVersionFailure(
                    "PROJECT_DEFINITION_VERSION_UNSUPPORTED", "/definitionVersion"
                )
            )
        identity = document.get("projectIdentity")
        if (
            not isinstance(identity, str)
            or _PROJECT_IDENTITY.fullmatch(identity) is None
        ):
            failures.append(
                ProjectVersionFailure("PROJECT_IDENTITY_INVALID", "/projectIdentity")
            )
        repository = document.get("registryRepository")
        if not isinstance(repository, str) or not _registry_repository(repository):
            failures.append(
                ProjectVersionFailure("PROJECT_REGISTRY_INVALID", "/registryRepository")
            )
        smoke = document.get("smokeCommand")
        smoke_items = cast(list[object], smoke) if isinstance(smoke, list) else []
        if (
            not isinstance(smoke, list)
            or not smoke
            or not all(isinstance(item, str) and item for item in smoke_items)
        ):
            failures.append(
                ProjectVersionFailure("PROJECT_SMOKE_COMMAND_INVALID", "/smokeCommand")
            )
        backends = document.get("backends")
        profiles: dict[str, str] = {}
        if not isinstance(backends, Mapping) or not backends:
            failures.append(
                ProjectVersionFailure("PROJECT_BACKENDS_EMPTY", "/backends")
            )
        else:
            for name, raw in cast(Mapping[object, object], backends).items():
                pointer = f"/backends/{name}/environmentProfile"
                if name not in {"cuda", "rocm"} or not isinstance(raw, Mapping):
                    failures.append(
                        ProjectVersionFailure(
                            "PROJECT_BACKEND_UNSUPPORTED", f"/backends/{name}"
                        )
                    )
                    continue
                typed_backend = cast(Mapping[object, object], raw)
                profile = typed_backend.get("environmentProfile")
                if not isinstance(profile, str) or _PROFILE.fullmatch(profile) is None:
                    failures.append(
                        ProjectVersionFailure(
                            "PROJECT_PROFILE_NOT_DIGEST_PINNED", pointer
                        )
                    )
                else:
                    profiles[str(name)] = profile

        resolved_root = root.resolve()
        paths: dict[str, Path] = {}
        for field in ("configurationContract", "metricContract", "dependencyLock"):
            value = document.get(field)
            try:
                path = (resolved_root / cast(str, value)).resolve()
                if not isinstance(value, str) or not path.is_relative_to(resolved_root):
                    raise ValueError
                paths[field] = path
            except (TypeError, ValueError):
                failures.append(
                    ProjectVersionFailure("PROJECT_PATH_INVALID", f"/{field}")
                )

        configuration = _configuration_artifact(
            paths.get("configurationContract"), failures
        )
        metrics = _metric_artifact(paths.get("metricContract"), failures)
        lock = paths.get("dependencyLock")
        if lock is not None:
            try:
                if _LOCKED_SKYWRIGHT.search(lock.read_text(encoding="utf-8")):
                    failures.append(
                        ProjectVersionFailure(
                            "PROJECT_DEPENDENCY_REPLACES_SKYWRIGHT", "/dependencyLock"
                        )
                    )
            except OSError:
                failures.append(
                    ProjectVersionFailure(
                        "PROJECT_ARTIFACT_UNAVAILABLE", "/dependencyLock"
                    )
                )
        if failures:
            raise ProjectVersionError(failures)
        assert isinstance(identity, str)
        assert isinstance(repository, str)
        assert isinstance(smoke, list)
        assert lock is not None and configuration is not None and metrics is not None
        return cls(
            identity,
            repository,
            resolved_root,
            lock,
            tuple(cast(list[str], smoke)),
            profiles,
            configuration,
            metrics,
        )


@dataclass(frozen=True)
class ProjectVersionManifest:
    """Canonical, independently verifiable complete publication record."""

    canonical_json: str
    digest: str
    version_label: str
    images: dict[str, str]

    @classmethod
    def complete(
        cls,
        definition: ProjectVersionDefinition,
        *,
        source_revision: str,
        pipeline: str,
        images: Mapping[str, str],
        contract_artifacts: Mapping[str, Mapping[str, str]],
    ) -> ProjectVersionManifest:
        failures: list[ProjectVersionFailure] = []
        if re.fullmatch(r"[0-9a-f]{40}", source_revision) is None:
            failures.append(
                ProjectVersionFailure(
                    "PROJECT_SOURCE_REVISION_INVALID", "/sourceRevision"
                )
            )
        if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,86}", pipeline) is None:
            failures.append(
                ProjectVersionFailure("PROJECT_PIPELINE_INVALID", "/pipeline")
            )
        artifacts: dict[str, dict[str, str]] = {}
        pinned_images: dict[str, str] = {}
        for backend in definition.backends:
            image = images.get(backend)
            if not isinstance(image, str) or _DIGEST.fullmatch(image) is None:
                failures.append(
                    ProjectVersionFailure("PROJECT_IMAGE_MISSING", f"/images/{backend}")
                )
            else:
                pinned_images[backend] = image
            backend_artifacts = contract_artifacts.get(backend)
            typed_artifacts: Mapping[str, object] = (
                cast(Mapping[str, object], backend_artifacts)
                if isinstance(backend_artifacts, Mapping)
                else dict[str, object]()
            )
            configuration_artifact = typed_artifacts.get("configuration")
            metric_artifact = typed_artifacts.get("metrics")
            if (
                not isinstance(configuration_artifact, str)
                or _DIGEST.fullmatch(configuration_artifact) is None
                or not isinstance(metric_artifact, str)
                or _DIGEST.fullmatch(metric_artifact) is None
            ):
                failures.append(
                    ProjectVersionFailure(
                        "PROJECT_CONTRACT_ARTIFACT_MISSING",
                        f"/contractArtifacts/{backend}",
                    )
                )
            else:
                artifacts[backend] = {
                    "configuration": configuration_artifact,
                    "metrics": metric_artifact,
                }
        if failures:
            raise ProjectVersionError(failures)
        label = f"{source_revision}-{pipeline}"
        document = {
            "manifestVersion": 1,
            "projectIdentity": definition.project_identity,
            "versionLabel": label,
            "sourceRevision": source_revision,
            "pipeline": pipeline,
            "acceleratorBackends": list(definition.backends),
            "images": pinned_images,
            "environmentProfiles": definition.environment_profiles,
            "configurationContract": {
                "digest": definition.configuration_contract.digest,
                "skywrightSchema": definition.configuration_contract.skywright_schema,
            },
            "metricContract": {
                "digest": definition.metric_contract.digest,
                "skywrightSchema": definition.metric_contract.skywright_schema,
            },
            "contractArtifacts": artifacts,
        }
        canonical = _canonical(document)
        return cls(canonical, _sha256(canonical), label, pinned_images)

    def image_for(self, capabilities: Mapping[str, object]) -> str:
        backend = capabilities.get("acceleratorBackend")
        if isinstance(backend, str) and backend in self.images:
            return self.images[backend]
        raise ProjectVersionError(
            (
                ProjectVersionFailure(
                    "PROJECT_CAPABILITIES_INCOMPATIBLE", "/acceleratorBackend"
                ),
            )
        )


def _configuration_artifact(
    path: Path | None, failures: list[ProjectVersionFailure]
) -> ContractArtifact | None:
    if path is None:
        return None
    try:
        source = path.read_bytes()
        ConfigurationContract.compile(source)
        document = _object(source)
        canonical = _canonical(document)
        return ContractArtifact(
            canonical,
            _sha256(canonical),
            ConfigurationContract.skywright_schema_identity(),
        )
    except (OSError, ValueError, ConfigurationContractError):
        failures.append(
            ProjectVersionFailure(
                "PROJECT_CONFIGURATION_CONTRACT_INVALID", "/configurationContract"
            )
        )
        return None


def _metric_artifact(
    path: Path | None, failures: list[ProjectVersionFailure]
) -> ContractArtifact | None:
    if path is None:
        return None
    try:
        contract = MetricContract.compile(path.read_bytes())
        document = cast(dict[str, object], json.loads(contract.canonical_json))
        return ContractArtifact(
            contract.canonical_json,
            contract.digest,
            cast(dict[str, object], document["skywrightSchema"]),
        )
    except (OSError, ValueError, MetricContractError):
        failures.append(
            ProjectVersionFailure("PROJECT_METRIC_CONTRACT_INVALID", "/metricContract")
        )
        return None


def _object(source: str | bytes | Mapping[str, object]) -> dict[str, object]:
    if isinstance(source, Mapping):
        return dict(source)
    value = json.loads(source, parse_float=Decimal)
    if not isinstance(value, dict):
        raise ValueError("document must be an object")
    return cast(dict[str, object], value)


def _canonical(value: object) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, Decimal):
        rendered = format(value, "f")
        if "." in rendered:
            rendered = rendered.rstrip("0").rstrip(".")
        return rendered or "0"
    if isinstance(value, list):
        items = cast(list[object], value)
        return "[" + ",".join(_canonical(item) for item in items) + "]"
    if isinstance(value, Mapping):
        items = cast(Mapping[str, object], value)
        return (
            "{"
            + ",".join(
                f"{json.dumps(name, ensure_ascii=False)}:{_canonical(items[name])}"
                for name in sorted(items)
            )
            + "}"
        )
    raise TypeError(f"unsupported JSON value {type(value).__name__}")


def _sha256(source: str) -> str:
    return "sha256:" + hashlib.sha256(source.encode()).hexdigest()


def _registry_repository(value: str) -> bool:
    return (
        "://" not in value
        and "/" in value
        and not value.endswith("/")
        and "@" not in value
    )


__all__ = [
    "ContractArtifact",
    "ProjectVersionDefinition",
    "ProjectVersionError",
    "ProjectVersionFailure",
    "ProjectVersionManifest",
]
