"""Compile and resolve the version-pinned Run Configuration contract."""

from __future__ import annotations

import copy
import hashlib
import json
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from importlib.resources import files
from typing import Protocol, TypeAlias, cast
from urllib.parse import unquote

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012, SchemaRegistry

JsonScalar: TypeAlias = bool | int | Decimal | str | None
JsonValue: TypeAlias = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]
JsonDocument: TypeAlias = str | bytes | Mapping[str, object]

_SCHEMA_VERSION = "0.3.0"
_DIALECT = "https://json-schema.org/draft/2020-12/schema"
_VOCABULARIES = frozenset(
    {
        "https://json-schema.org/draft/2020-12/vocab/core",
        "https://json-schema.org/draft/2020-12/vocab/applicator",
        "https://json-schema.org/draft/2020-12/vocab/unevaluated",
        "https://json-schema.org/draft/2020-12/vocab/validation",
        "https://json-schema.org/draft/2020-12/vocab/meta-data",
        "https://json-schema.org/draft/2020-12/vocab/format-annotation",
        "https://json-schema.org/draft/2020-12/vocab/format-assertion",
        "https://json-schema.org/draft/2020-12/vocab/content",
    }
)
_APPLICATOR_KEYWORDS = (
    "allOf",
    "anyOf",
    "oneOf",
    "not",
    "if",
    "then",
    "else",
    "dependentSchemas",
)
_COMPOSED_KEYWORDS = ("$defs", *_APPLICATOR_KEYWORDS, "dependentRequired")
_SHARED_OBJECT_KEYWORDS = frozenset(
    {
        "$comment",
        "$defs",
        "$schema",
        "additionalProperties",
        "description",
        "properties",
        "required",
        "title",
        "type",
        *_COMPOSED_KEYWORDS,
    }
)
_SHARED_APPLICATOR_COLLISIONS = frozenset(
    {
        "additionalProperties",
        "const",
        "enum",
        "maxProperties",
        "minProperties",
        "patternProperties",
        "propertyNames",
        "unevaluatedProperties",
    }
)


def _resource_text(name: str) -> str:
    return (
        files("skywright._configuration_resources")
        .joinpath(name)
        .read_text(encoding="utf-8")
    )


def _load_resource(name: str) -> JsonObject:
    return cast(
        JsonObject,
        json.loads(_resource_text(name), parse_float=Decimal, parse_int=int),
    )


def _pointer(parts: Iterable[object]) -> str:
    return "".join(
        f"/{str(part).replace('~', '~0').replace('/', '~1')}" for part in parts
    )


@dataclass(frozen=True, order=True)
class ConfigurationError:
    """One language-independent configuration failure."""

    code: str
    source: str
    pointer: str
    keyword: str


class ConfigurationContractError(ValueError):
    """One or more deterministic configuration failures."""

    def __init__(self, errors: Iterable[ConfigurationError]) -> None:
        self.errors: tuple[ConfigurationError, ...] = tuple(
            sorted(errors, key=lambda error: (error.pointer, error.code, error.keyword))
        )
        super().__init__("; ".join(error.code for error in self.errors))


class _DuplicateProperty(ValueError):
    def __init__(self, name: str) -> None:
        self.name = name


def _without_duplicates(pairs: list[tuple[str, JsonValue]]) -> JsonObject:
    result: JsonObject = {}
    for name, value in pairs:
        if name in result:
            raise _DuplicateProperty(name)
        result[name] = value
    return result


def _as_object(document: JsonDocument, source: str) -> JsonObject:
    if isinstance(document, (str, bytes)):
        try:
            parsed = json.loads(
                document,
                parse_float=Decimal,
                parse_int=int,
                object_pairs_hook=_without_duplicates,
            )
        except _DuplicateProperty as error:
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_DUPLICATE_PROPERTY",
                        source,
                        _pointer((error.name,)),
                        "parse",
                    ),
                )
            ) from error
        except json.JSONDecodeError as error:
            raise ConfigurationContractError(
                (ConfigurationError("CONFIG_INVALID_JSON", source, "", "parse"),)
            ) from error
    else:
        parsed = copy.deepcopy(document)
    if not isinstance(parsed, Mapping):
        raise ConfigurationContractError(
            (ConfigurationError("CONFIG_LAYER_NOT_OBJECT", source, "", "type"),)
        )
    unknown_mapping = cast(Mapping[object, object], parsed)
    if not all(isinstance(name, str) for name in unknown_mapping):
        raise ConfigurationContractError(
            (
                ConfigurationError(
                    "CONFIG_PROPERTY_NAME_NOT_STRING", source, "", "parse"
                ),
            )
        )
    return cast(JsonObject, dict(cast(Mapping[str, object], unknown_mapping)))


def _overlay(lower: JsonObject, higher: Mapping[str, JsonValue]) -> JsonObject:
    result = copy.deepcopy(lower)
    for name, value in higher.items():
        current = result.get(name)
        if isinstance(current, dict) and isinstance(value, Mapping):
            result[name] = _overlay(current, cast(Mapping[str, JsonValue], value))
        else:
            result[name] = copy.deepcopy(value)
    return result


def _composition_errors(
    library: Mapping[str, JsonValue],
    project: Mapping[str, JsonValue],
    path: tuple[str, ...] = (),
    root_project: Mapping[str, JsonValue] | None = None,
    references: Mapping[str, JsonValue] | None = None,
) -> list[ConfigurationError]:
    if root_project is None:
        root_project = project
    if references is None:
        references = {}
    errors: list[ConfigurationError] = []
    library_properties = library.get("properties", {})
    project_properties = project.get("properties", {})
    if not isinstance(library_properties, Mapping) or not isinstance(
        project_properties, Mapping
    ):
        return errors
    for keyword in project:
        if keyword not in _SHARED_OBJECT_KEYWORDS:
            errors.append(
                ConfigurationError(
                    "CONFIG_OWNERSHIP_COLLISION",
                    "project-schema",
                    _pointer(path),
                    keyword,
                )
            )
    project_required = project.get("required", [])
    if isinstance(project_required, Sequence) and not isinstance(
        project_required, (str, bytes)
    ):
        for required_name in project_required:
            if required_name in library_properties:
                errors.append(
                    ConfigurationError(
                        "CONFIG_OWNERSHIP_COLLISION",
                        "project-schema",
                        _pointer((*path, required_name)),
                        "required",
                    )
                )
    for name, project_definition in project_properties.items():
        if name not in library_properties:
            continue
        property_path = (*path, name)
        library_definition = library_properties[name]
        if (
            isinstance(library_definition, Mapping)
            and isinstance(project_definition, Mapping)
            and library_definition.get("type") == "object"
            and project_definition.get("type") == "object"
            and isinstance(project_definition.get("properties"), Mapping)
        ):
            errors.extend(
                _composition_errors(
                    cast(Mapping[str, JsonValue], library_definition),
                    cast(Mapping[str, JsonValue], project_definition),
                    property_path,
                    root_project,
                    references,
                )
            )
            continue
        errors.append(
            ConfigurationError(
                "CONFIG_OWNERSHIP_COLLISION",
                "project-schema",
                _pointer(property_path),
                "properties",
            )
        )
    for keyword in _APPLICATOR_KEYWORDS:
        if keyword in project:
            constraint = project[keyword]
            constraints = (
                constraint.values()
                if keyword == "dependentSchemas" and isinstance(constraint, Mapping)
                else (constraint,)
            )
            for nested_schema in constraints:
                errors.extend(
                    _applicator_ownership_errors(
                        cast(Mapping[str, JsonValue], library_properties),
                        cast(JsonValue, nested_schema),
                        path,
                        root_project,
                        references,
                    )
                )
    return errors


def _applicator_ownership_errors(
    library_properties: Mapping[str, JsonValue],
    constraint: JsonValue,
    path: tuple[str, ...],
    root_project: Mapping[str, JsonValue],
    references: Mapping[str, JsonValue],
    visited_references: frozenset[tuple[str, tuple[str, ...]]] = frozenset(),
) -> list[ConfigurationError]:
    errors: list[ConfigurationError] = []
    if isinstance(constraint, list):
        for item in constraint:
            errors.extend(
                _applicator_ownership_errors(
                    library_properties,
                    item,
                    path,
                    root_project,
                    references,
                    visited_references,
                )
            )
        return errors
    if not isinstance(constraint, Mapping):
        return errors
    reference = constraint.get("$ref")
    referenced_schema = _referenced_schema(root_project, references, reference)
    reference_key = (reference, path) if isinstance(reference, str) else None
    if referenced_schema is not None:
        if reference_key not in visited_references:
            errors.extend(
                _applicator_ownership_errors(
                    library_properties,
                    referenced_schema,
                    path,
                    root_project,
                    references,
                    visited_references
                    | {cast(tuple[str, tuple[str, ...]], reference_key)},
                )
            )
        else:
            errors.append(
                ConfigurationError(
                    "CONFIG_RECURSIVE_REFERENCE",
                    "project-schema",
                    _pointer(path),
                    "$ref",
                )
            )
    elif "$ref" in constraint or "$dynamicRef" in constraint:
        errors.append(
            ConfigurationError(
                "CONFIG_OWNERSHIP_COLLISION",
                "project-schema",
                _pointer(path),
                "$ref" if "$ref" in constraint else "$dynamicRef",
            )
        )
    constrained_properties = constraint.get("properties")
    for keyword in _SHARED_APPLICATOR_COLLISIONS & constraint.keys():
        errors.append(
            ConfigurationError(
                "CONFIG_OWNERSHIP_COLLISION",
                "project-schema",
                _pointer(path),
                keyword,
            )
        )
    if isinstance(constrained_properties, Mapping):
        for name, definition in constrained_properties.items():
            if name not in library_properties:
                continue
            property_path = (*path, name)
            library_definition = library_properties[name]
            if (
                isinstance(library_definition, Mapping)
                and isinstance(definition, Mapping)
                and isinstance(library_definition.get("properties"), Mapping)
                and isinstance(definition.get("properties"), Mapping)
            ):
                errors.extend(
                    _applicator_ownership_errors(
                        cast(
                            Mapping[str, JsonValue],
                            library_definition["properties"],
                        ),
                        cast(JsonValue, definition),
                        property_path,
                        root_project,
                        references,
                        visited_references,
                    )
                )
            else:
                errors.append(
                    ConfigurationError(
                        "CONFIG_OWNERSHIP_COLLISION",
                        "project-schema",
                        _pointer(property_path),
                        "properties",
                    )
                )
    required = constraint.get("required")
    if isinstance(required, list):
        for name in required:
            if isinstance(name, str) and name in library_properties:
                errors.append(
                    ConfigurationError(
                        "CONFIG_OWNERSHIP_COLLISION",
                        "project-schema",
                        _pointer((*path, name)),
                        "required",
                    )
                )
    dependent_required = constraint.get("dependentRequired")
    if isinstance(dependent_required, Mapping):
        for name, dependents in dependent_required.items():
            if name in library_properties or (
                isinstance(dependents, list)
                and any(
                    isinstance(dependent, str) and dependent in library_properties
                    for dependent in dependents
                )
            ):
                errors.append(
                    ConfigurationError(
                        "CONFIG_OWNERSHIP_COLLISION",
                        "project-schema",
                        _pointer((*path, name)),
                        "dependentRequired",
                    )
                )
    for keyword in _APPLICATOR_KEYWORDS:
        if keyword in constraint:
            nested = constraint[keyword]
            nested_schemas = (
                nested.values()
                if keyword == "dependentSchemas" and isinstance(nested, Mapping)
                else (nested,)
            )
            for nested_schema in nested_schemas:
                errors.extend(
                    _applicator_ownership_errors(
                        library_properties,
                        cast(JsonValue, nested_schema),
                        path,
                        root_project,
                        references,
                        visited_references,
                    )
                )
    return errors


def _referenced_schema(
    root_schema: Mapping[str, JsonValue],
    references: Mapping[str, JsonValue],
    reference: object,
) -> JsonValue | None:
    if not isinstance(reference, str):
        return None
    identity, separator, encoded_fragment = reference.partition("#")
    fragment = unquote(encoded_fragment)
    current = references.get(identity) if identity else cast(JsonValue, root_schema)
    if current is None:
        return None
    if not separator or not fragment:
        return cast(JsonValue, current)
    if not fragment.startswith("/"):
        return _find_anchor(cast(JsonValue, current), fragment)
    for encoded_part in fragment[1:].split("/"):
        part = encoded_part.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, Mapping) or part not in current:
            return None
        current = current[part]
    return current


def _find_anchor(value: JsonValue, anchor: str) -> JsonValue | None:
    if isinstance(value, Mapping):
        if value.get("$anchor") == anchor:
            return value
        for child in value.values():
            found = _find_anchor(child, anchor)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = _find_anchor(child, anchor)
            if found is not None:
                return found
    return None


def _check_project_schema(schema: Mapping[str, JsonValue]) -> None:
    required = schema.get("required")
    if required is not None and not (
        isinstance(required, list) and all(isinstance(name, str) for name in required)
    ):
        raise ConfigurationContractError(
            (
                ConfigurationError(
                    "CONFIG_INVALID_PROJECT_SCHEMA",
                    "project-schema",
                    "/required",
                    "type",
                ),
            )
        )
    try:
        Draft202012Validator.check_schema(schema)
    except SchemaError as error:
        raise ConfigurationContractError(
            (
                ConfigurationError(
                    "CONFIG_INVALID_PROJECT_SCHEMA",
                    "project-schema",
                    _pointer(error.absolute_path),
                    str(error.validator),
                ),
            )
        ) from error


def _merge_schema_properties(
    library: JsonObject, project: Mapping[str, JsonValue]
) -> None:
    library_properties = cast(JsonObject, library["properties"])
    project_properties = project.get("properties", {})
    if not isinstance(project_properties, Mapping):
        raise ConfigurationContractError(
            (
                ConfigurationError(
                    "CONFIG_INVALID_PROJECT_SCHEMA",
                    "project-schema",
                    "/properties",
                    "type",
                ),
            )
        )
    for name, definition in project_properties.items():
        if name not in library_properties:
            library_properties[name] = copy.deepcopy(definition)
            continue
        library_definition = library_properties[name]
        if isinstance(library_definition, dict) and isinstance(definition, Mapping):
            _merge_schema_properties(
                library_definition, cast(Mapping[str, JsonValue], definition)
            )
            project_required = definition.get("required", [])
            if isinstance(project_required, list):
                required = cast(
                    list[JsonValue], library_definition.setdefault("required", [])
                )
                required.extend(
                    item for item in project_required if item not in required
                )
    for keyword in _COMPOSED_KEYWORDS:
        if keyword in project:
            library[keyword] = copy.deepcopy(project[keyword])


def _compose_schema(
    library: JsonObject,
    project: Mapping[str, JsonValue],
    references: Mapping[str, JsonValue],
) -> JsonObject:
    errors = _composition_errors(library, project, references=references)
    if errors:
        raise ConfigurationContractError(errors)
    composed = copy.deepcopy(library)
    _merge_schema_properties(composed, project)
    project_required = project.get("required", [])
    if not isinstance(project_required, list):
        raise ValueError("projectSchema.required must be an array")
    required = cast(list[JsonValue], composed["required"])
    required.extend(copy.deepcopy(project_required))
    return composed


def _schema_feature_errors(
    value: JsonValue,
    bundled_references: frozenset[str],
    path: tuple[str, ...],
) -> list[ConfigurationError]:
    errors: list[ConfigurationError] = []
    if isinstance(value, Mapping):
        dialect = value.get("$schema")
        if dialect is not None and dialect != _DIALECT:
            errors.append(
                ConfigurationError(
                    "CONFIG_UNSUPPORTED_DIALECT",
                    "project-contract",
                    _pointer((*path, "$schema")),
                    "$schema",
                )
            )
        vocabulary = value.get("$vocabulary")
        if isinstance(vocabulary, Mapping):
            for name, required in vocabulary.items():
                if required is True and name not in _VOCABULARIES:
                    errors.append(
                        ConfigurationError(
                            "CONFIG_UNSUPPORTED_VOCABULARY",
                            "project-contract",
                            _pointer((*path, "$vocabulary", name)),
                            "$vocabulary",
                        )
                    )
        for reference_keyword in ("$ref", "$dynamicRef"):
            reference = value.get(reference_keyword)
            reference_identity = (
                reference.partition("#")[0] if isinstance(reference, str) else None
            )
            if reference_identity and reference_identity not in bundled_references:
                errors.append(
                    ConfigurationError(
                        "CONFIG_MUTABLE_EXTERNAL_REFERENCE",
                        "project-contract",
                        _pointer((*path, reference_keyword)),
                        reference_keyword,
                    )
                )
        for name, child in value.items():
            if name in {"const", "default", "examples"}:
                continue
            errors.extend(
                _schema_feature_errors(child, bundled_references, (*path, name))
            )
    elif isinstance(value, list):
        for index, child in enumerate(value):
            errors.extend(
                _schema_feature_errors(child, bundled_references, (*path, str(index)))
            )
    return errors


def _reference_registry(references: Mapping[str, JsonValue]) -> SchemaRegistry:
    registry: SchemaRegistry = Registry()
    for identity, schema in references.items():
        if not identity.startswith("urn:") or not isinstance(schema, Mapping):
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_INVALID_BUNDLED_REFERENCE",
                        "project-contract",
                        _pointer(("references", identity)),
                        "$id",
                    ),
                )
            )
        if schema.get("$id") != identity:
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_INVALID_BUNDLED_REFERENCE",
                        "project-contract",
                        _pointer(("references", identity, "$id")),
                        "$id",
                    ),
                )
            )
        registry = registry.with_resource(
            identity,
            Resource.from_contents(
                cast(Mapping[str, object], schema), default_specification=DRAFT202012
            ),
        )
    return registry


class _Validator(Protocol):
    def validate(self, instance: object) -> None: ...

    def iter_errors(self, instance: object) -> Iterable[_SchemaError]: ...


class _SchemaError(Protocol):
    absolute_path: Iterable[object]
    validator: object


def _validation_errors(
    schema: JsonObject,
    instance: JsonObject,
    source: str,
    registry: SchemaRegistry,
) -> tuple[ConfigurationError, ...]:
    validator = cast(
        _Validator,
        Draft202012Validator(schema, format_checker=FormatChecker(), registry=registry),
    )
    errors = (
        ConfigurationError(
            "CONFIG_SCHEMA_VALIDATION",
            source,
            _pointer(error.absolute_path),
            str(error.validator),
        )
        for error in validator.iter_errors(instance)
    )
    return tuple(
        sorted(errors, key=lambda error: (error.pointer, error.code, error.keyword))
    )


def _fill_absent(
    existing: JsonObject,
    witness: Mapping[str, JsonValue],
    path: tuple[str, ...] = (),
) -> JsonObject:
    completed = copy.deepcopy(existing)
    errors: list[ConfigurationError] = []
    for name, value in witness.items():
        property_path = (*path, name)
        if name not in existing:
            completed[name] = copy.deepcopy(value)
        elif isinstance(existing[name], dict) and isinstance(value, Mapping):
            try:
                completed[name] = _fill_absent(
                    cast(JsonObject, existing[name]),
                    cast(Mapping[str, JsonValue], value),
                    property_path,
                )
            except ConfigurationContractError as error:
                errors.extend(error.errors)
        else:
            errors.append(
                ConfigurationError(
                    "CONFIG_WITNESS_REPLACEMENT",
                    "defaults-completion-witness",
                    _pointer(property_path),
                    "overlay",
                )
            )
    if errors:
        raise ConfigurationContractError(errors)
    return completed


class ConfigurationContract:
    """A validated Project Configuration Contract ready to resolve submissions."""

    __slots__ = ("_defaults", "_registry", "_schema")

    _schema: JsonObject
    _defaults: JsonObject
    _registry: object

    def __init__(self) -> None:
        raise TypeError("use ConfigurationContract.compile()")

    @classmethod
    def _compiled(
        cls,
        schema: JsonObject,
        defaults: JsonObject,
        registry: SchemaRegistry,
    ) -> ConfigurationContract:
        contract = object.__new__(cls)
        contract._schema = schema
        contract._defaults = defaults
        contract._registry = registry
        return contract

    @staticmethod
    def skywright_schema_identity() -> dict[str, object]:
        schema_bytes = _resource_text("schema.json").encode()
        identity: dict[str, object] = {
            "version": _SCHEMA_VERSION,
            "digest": f"sha256:{hashlib.sha256(schema_bytes).hexdigest()}",
        }
        manifest = _load_resource("manifest.json")
        if identity != {
            "version": manifest["schemaVersion"],
            "digest": manifest["schemaDigest"],
        }:
            raise RuntimeError("packaged Skywright Configuration Schema digest drifted")
        return identity

    @classmethod
    def compile(
        cls, artifact: str | bytes | Mapping[str, object]
    ) -> ConfigurationContract:
        artifact_object = _as_object(artifact, "project-contract")
        if artifact_object.get("contractVersion") != 1:
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_CONTRACT_VERSION",
                        "project-contract",
                        "/contractVersion",
                        "const",
                    ),
                )
            )
        if artifact_object.get("skywrightSchema") != cls.skywright_schema_identity():
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_SCHEMA_IDENTITY_MISMATCH",
                        "project-contract",
                        "/skywrightSchema",
                        "const",
                    ),
                )
            )
        project_schema = artifact_object.get("projectSchema")
        defaults = artifact_object.get("defaults")
        if not isinstance(project_schema, Mapping) or not isinstance(defaults, Mapping):
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_LAYER_NOT_OBJECT", "project-contract", "", "type"
                    ),
                )
            )
        if project_schema.get("$schema") != _DIALECT:
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_UNSUPPORTED_DIALECT",
                        "project-contract",
                        "/projectSchema/$schema",
                        "$schema",
                    ),
                )
            )
        _check_project_schema(cast(Mapping[str, JsonValue], project_schema))
        references = artifact_object.get("references")
        if not isinstance(references, Mapping):
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_LAYER_NOT_OBJECT",
                        "project-contract",
                        "/references",
                        "type",
                    ),
                )
            )
        untyped_references = cast(Mapping[object, JsonValue], references)
        for identity in untyped_references:
            if not isinstance(identity, str):
                raise ConfigurationContractError(
                    (
                        ConfigurationError(
                            "CONFIG_PROPERTY_NAME_NOT_STRING",
                            "project-contract",
                            _pointer(("references", identity)),
                            "parse",
                        ),
                    )
                )
        typed_references = cast(Mapping[str, JsonValue], untyped_references)
        artifact_errors = _validation_errors(
            _load_resource("project-contract.schema.json"),
            artifact_object,
            "project-contract",
            Registry(),
        )
        if artifact_errors:
            raise ConfigurationContractError(artifact_errors)
        for reference_schema in typed_references.values():
            if isinstance(reference_schema, Mapping):
                _check_project_schema(cast(Mapping[str, JsonValue], reference_schema))
        feature_errors = _schema_feature_errors(
            cast(JsonValue, project_schema),
            frozenset(typed_references),
            ("projectSchema",),
        )
        for identity, reference_schema in typed_references.items():
            feature_errors.extend(
                _schema_feature_errors(
                    reference_schema,
                    frozenset(typed_references),
                    ("references", identity),
                )
            )
        if feature_errors:
            raise ConfigurationContractError(feature_errors)
        registry = _reference_registry(typed_references)
        schema = _compose_schema(
            _load_resource("schema.json"), project_schema, typed_references
        )
        Draft202012Validator.check_schema(schema)
        merged_defaults = _overlay(
            _load_resource("defaults.json"), cast(Mapping[str, JsonValue], defaults)
        )
        witness = artifact_object.get("defaultsCompletionWitness")
        if not isinstance(witness, Mapping):
            raise ConfigurationContractError(
                (
                    ConfigurationError(
                        "CONFIG_LAYER_NOT_OBJECT",
                        "defaults-completion-witness",
                        "",
                        "type",
                    ),
                )
            )
        completed = _fill_absent(
            merged_defaults, cast(Mapping[str, JsonValue], witness)
        )
        errors = _validation_errors(
            schema, completed, "defaults-completion-witness", registry
        )
        if errors:
            raise ConfigurationContractError(errors)
        return cls._compiled(schema, merged_defaults, registry)

    def resolve(
        self, submission: str | bytes | Mapping[str, object]
    ) -> dict[str, object]:
        submission_object = _as_object(submission, "submission")
        resolved = _overlay(self._defaults, submission_object)
        errors = _validation_errors(
            self._schema,
            resolved,
            "submission",
            cast(SchemaRegistry, self._registry),
        )
        if errors:
            raise ConfigurationContractError(errors)
        return cast(dict[str, object], resolved)


__all__ = [
    "ConfigurationContract",
    "ConfigurationContractError",
    "ConfigurationError",
    "JsonDocument",
    "JsonObject",
    "JsonValue",
]
