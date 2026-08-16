"""Compile canonical Project Metric Contracts into immutable Metric Catalogs."""

from __future__ import annotations

import hashlib
import json
import math
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from decimal import Decimal
from importlib.resources import files
from typing import Protocol, cast

from jsonschema import Draft202012Validator

from skywright._training_types import MetricCatalog, MetricDefinition

JsonDocument = str | bytes | Mapping[str, object]


def _resource(name: str) -> str:
    return files("skywright._metric_resources").joinpath(name).read_text("utf-8")


def _pointer(parts: Iterable[object]) -> str:
    return "".join(
        f"/{str(part).replace('~', '~0').replace('/', '~1')}" for part in parts
    )


class _DuplicateProperty(ValueError):
    def __init__(self, name: str) -> None:
        self.name = name


def _without_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for name, value in pairs:
        if name in result:
            raise _DuplicateProperty(name)
        result[name] = value
    return result


def _document(value: JsonDocument) -> dict[str, object]:
    if isinstance(value, (str, bytes)):
        try:
            parsed = json.loads(
                value,
                parse_float=Decimal,
                parse_int=int,
                object_pairs_hook=_without_duplicates,
            )
        except _DuplicateProperty as error:
            raise MetricContractError(
                (
                    MetricError(
                        "METRIC_DUPLICATE_PROPERTY",
                        "project-contract",
                        _pointer((error.name,)),
                        "parse",
                    ),
                )
            ) from error
        except json.JSONDecodeError as error:
            raise MetricContractError(
                (MetricError("METRIC_INVALID_JSON", "project-contract", "", "parse"),)
            ) from error
    else:
        parsed = value
    if not isinstance(parsed, Mapping):
        raise MetricContractError(
            (MetricError("METRIC_SCHEMA_VALIDATION", "project-contract", "", "type"),)
        )
    return dict(cast(Mapping[str, object], parsed))


def _json_text(value: object) -> str:
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
    if isinstance(value, (float, Decimal)):
        numeric = Decimal(str(value))
        if not numeric.is_finite():
            raise ValueError("canonical JSON cannot contain a non-finite number")
        return str(numeric)
    if isinstance(value, list):
        items = cast(list[object], value)
        return "[" + ",".join(_json_text(item) for item in items) + "]"
    if isinstance(value, Mapping):
        mapping = cast(Mapping[str, object], value)
        return (
            "{"
            + ",".join(
                f"{json.dumps(name, ensure_ascii=False)}:{_json_text(mapping[name])}"
                for name in sorted(mapping)
            )
            + "}"
        )
    raise TypeError(f"cannot serialize {type(value).__name__} as canonical JSON")


@dataclass(frozen=True, order=True)
class MetricError:
    """One language-independent Project Metric Contract failure."""

    code: str
    source: str
    pointer: str
    keyword: str


class MetricContractError(ValueError):
    """One or more deterministic Project Metric Contract failures."""

    def __init__(self, errors: Iterable[MetricError]) -> None:
        self.errors: tuple[MetricError, ...] = tuple(
            sorted(errors, key=lambda error: (error.pointer, error.code, error.keyword))
        )
        super().__init__("; ".join(error.code for error in self.errors))


class _SchemaError(Protocol):
    absolute_path: Iterable[object]
    validator: object


class _Validator(Protocol):
    def iter_errors(self, instance: object) -> Iterable[_SchemaError]: ...


class MetricSchema:
    """The packaged, versioned Skywright Metric Schema."""

    _schema = cast(dict[str, object], json.loads(_resource("schema.json")))
    _manifest = cast(dict[str, object], json.loads(_resource("manifest.json")))

    @classmethod
    def identity(cls) -> dict[str, str]:
        canonical = _json_text(cls._schema)
        actual_digest = "sha256:" + hashlib.sha256(canonical.encode()).hexdigest()
        if actual_digest != cls._manifest["schemaDigest"]:
            raise RuntimeError(
                "packaged Skywright Metric Schema digest does not match its manifest"
            )
        return {
            "version": cast(str, cls._manifest["schemaVersion"]),
            "digest": cast(str, cls._manifest["schemaDigest"]),
        }

    @classmethod
    def units(cls) -> tuple[str, ...]:
        return tuple(
            cast(str, cast(dict[str, object], unit)["name"])
            for unit in cast(list[object], cls._schema["units"])
        )

    @classmethod
    def definitions(cls) -> tuple[MetricDefinition, ...]:
        return tuple(
            _definition(cast(dict[str, object], item))
            for item in cast(list[object], cls._schema["definitions"])
        )


def _definition(value: Mapping[str, object]) -> MetricDefinition:
    bounds = cast(Mapping[str, object], value.get("bounds", {}))

    def numeric_bound(name: str) -> int | float | None:
        bound = bounds.get(name)
        if isinstance(bound, Decimal):
            return float(bound)
        return cast(int | float | None, bound)

    return MetricDefinition(
        name=cast(str, value["name"]),
        numeric_kind=cast(str, value["numericKind"]),  # type: ignore[arg-type]
        unit=cast(str, value["unit"]),
        recording_basis=cast(str, value["recordingBasis"]),  # type: ignore[arg-type]
        comparison=cast(str, value["comparison"]),  # type: ignore[arg-type]
        step_reduction=cast(str | None, value.get("stepReduction")),  # type: ignore[arg-type]
        minimum=numeric_bound("minimum"),
        maximum=numeric_bound("maximum"),
        display_name=cast(str | None, value.get("displayName")),
        description=cast(str | None, value.get("description")),
    )


def _semantic_errors(artifact: Mapping[str, object]) -> list[MetricError]:
    errors: list[MetricError] = []
    names: set[str] = set()
    units = frozenset(MetricSchema.units())
    definitions = cast(list[object], artifact.get("definitions", []))
    for index, item in enumerate(definitions):
        if not isinstance(item, Mapping):
            continue
        definition = cast(Mapping[str, object], item)
        name = definition.get("name")
        base = ("definitions", index)
        if isinstance(name, str) and name.startswith("skywright/"):
            errors.append(
                MetricError(
                    "METRIC_RESERVED_NAME",
                    "project-contract",
                    _pointer((*base, "name")),
                    "pattern",
                )
            )
        if isinstance(name, str) and name in names:
            errors.append(
                MetricError(
                    "METRIC_DEFINITION_COLLISION",
                    "project-contract",
                    _pointer((*base, "name")),
                    "unique",
                )
            )
        if isinstance(name, str):
            names.add(name)
        unit = definition.get("unit")
        if isinstance(unit, str) and unit not in units:
            errors.append(
                MetricError(
                    "METRIC_UNKNOWN_UNIT",
                    "project-contract",
                    _pointer((*base, "unit")),
                    "registry",
                )
            )
        if (
            definition.get("numericKind") == "integer"
            and definition.get("stepReduction") == "mean"
        ):
            errors.append(
                MetricError(
                    "METRIC_INTEGER_MEAN",
                    "project-contract",
                    _pointer((*base, "stepReduction")),
                    "semantic",
                )
            )
        bounds = definition.get("bounds")
        if isinstance(bounds, Mapping):
            typed_bounds = cast(Mapping[str, object], bounds)
            minimum = typed_bounds.get("minimum")
            maximum = typed_bounds.get("maximum")
            numeric_bounds = [item for item in (minimum, maximum) if item is not None]
            if any(
                isinstance(item, bool)
                or not isinstance(item, (int, float, Decimal))
                or not math.isfinite(item)
                for item in numeric_bounds
            ):
                errors.append(
                    MetricError(
                        "METRIC_INVALID_BOUND",
                        "project-contract",
                        _pointer((*base, "bounds")),
                        "number",
                    )
                )
            elif minimum is not None and maximum is not None and minimum > maximum:  # type: ignore[operator]
                errors.append(
                    MetricError(
                        "METRIC_BOUNDS_ORDER",
                        "project-contract",
                        _pointer((*base, "bounds")),
                        "semantic",
                    )
                )
    return errors


@dataclass(frozen=True)
class MetricContract:
    """Validated canonical Project Metric Contract."""

    canonical_json: str
    digest: str
    _definitions: tuple[MetricDefinition, ...]

    @classmethod
    def compile(cls, document: JsonDocument) -> MetricContract:
        artifact = _document(document)
        schema = cast(
            dict[str, object], json.loads(_resource("project-contract.schema.json"))
        )
        validator = cast(_Validator, Draft202012Validator(schema))
        errors = [
            MetricError(
                "METRIC_SCHEMA_VALIDATION",
                "project-contract",
                _pointer(error.absolute_path),
                str(error.validator or "schema"),
            )
            for error in validator.iter_errors(artifact)
        ]
        if artifact.get("skywrightSchema") != MetricSchema.identity():
            errors.append(
                MetricError(
                    "METRIC_SCHEMA_IDENTITY_MISMATCH",
                    "project-contract",
                    "/skywrightSchema",
                    "const",
                )
            )
        errors.extend(_semantic_errors(artifact))
        if errors:
            raise MetricContractError(errors)
        canonical = _json_text(artifact)
        digest = "sha256:" + hashlib.sha256(canonical.encode()).hexdigest()
        definitions = cast(list[object], artifact["definitions"])
        compiled_definitions = tuple(
            _definition(cast(dict[str, object], item)) for item in definitions
        )
        return cls(canonical, digest, compiled_definitions)

    def catalog(
        self, project_identity: str, project_version: str | None = None
    ) -> MetricCatalog:
        identity = MetricSchema.identity()
        return MetricCatalog(
            project_identity=project_identity,
            project_contract_digest=self.digest,
            skywright_schema_identity=identity["version"],
            skywright_schema_digest=identity["digest"],
            units=frozenset(MetricSchema.units()),
            project_definitions=self._definitions,
            system_definitions=MetricSchema.definitions(),
            project_version=project_version or project_identity,
        )


class ProjectMetricContract:
    """Resolver for one content-pinned Training Project Version artifact."""

    def __init__(
        self,
        document: JsonDocument,
        *,
        expected_digest: str,
        project_identity: str = "",
    ) -> None:
        self._contract = MetricContract.compile(document)
        self._project_identity = project_identity
        if self._contract.digest != expected_digest:
            raise MetricContractError(
                (
                    MetricError(
                        "METRIC_CONTRACT_DIGEST_MISMATCH",
                        "project-contract",
                        "",
                        "digest",
                    ),
                )
            )

    def compose(
        self, project_version: str, skywright_schema_identity: str
    ) -> MetricCatalog:
        if skywright_schema_identity != MetricSchema.identity()["version"]:
            raise MetricContractError(
                (
                    MetricError(
                        "METRIC_UNSUPPORTED_SCHEMA_VERSION",
                        "skywright-schema",
                        "",
                        "version",
                    ),
                )
            )
        return self._contract.catalog(
            self._project_identity or project_version, project_version
        )


def project_metrics_comparable(
    left: MetricCatalog, right: MetricCatalog, name: str
) -> bool:
    if left.project_identity != right.project_identity:
        return False
    left_definition = next(
        (item for item in left.project_definitions if item.name == name), None
    )
    right_definition = next(
        (item for item in right.project_definitions if item.name == name), None
    )
    if left_definition is None or right_definition is None:
        return False
    semantic = (
        "name",
        "numeric_kind",
        "unit",
        "recording_basis",
        "comparison",
        "minimum",
        "maximum",
        "step_reduction",
    )
    return all(
        getattr(left_definition, field) == getattr(right_definition, field)
        for field in semantic
    )


__all__ = [
    "JsonDocument",
    "MetricContract",
    "MetricContractError",
    "MetricError",
    "MetricSchema",
    "ProjectMetricContract",
    "project_metrics_comparable",
]
