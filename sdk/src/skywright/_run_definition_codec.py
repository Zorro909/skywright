from __future__ import annotations

import json
import re
from copy import deepcopy
from decimal import Decimal
from importlib.resources import files
from ipaddress import ip_address
from typing import Any, cast
from urllib.parse import urlsplit

from jsonschema import Draft202012Validator, FormatChecker


class RunDefinitionValidationError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _reject_duplicate(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON")
        result[key] = value
    return result


def _reject_non_json_number(value: str) -> None:
    raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON")


_MAXIMUM_PORTABLE_NUMBER_LENGTH = 4_000
_MAXIMUM_PORTABLE_NESTING_DEPTH = 256


def _parse_integer(value: str) -> int:
    if len(value) > _MAXIMUM_PORTABLE_NUMBER_LENGTH:
        raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON")
    return int(value)


def _parse_decimal(value: str) -> Decimal:
    if len(value) > _MAXIMUM_PORTABLE_NUMBER_LENGTH:
        raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON")
    parsed = Decimal(value)
    exponent = parsed.as_tuple().exponent
    if isinstance(exponent, int) and not (-2_147_483_647 <= exponent <= 2_147_483_648):
        raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON")
    return parsed


_SCHEMA = json.loads(
    files("skywright._run_definition_resources").joinpath("schema.json").read_text()
)
_VALIDATOR: Any = Draft202012Validator(_SCHEMA, format_checker=FormatChecker())
_MAXIMUM_PORTABLE_DECIMAL_EXPONENT = 1_000_000_000


def decode(document: str) -> dict[str, Any]:
    try:
        value: Any = json.loads(
            document,
            object_pairs_hook=_reject_duplicate,
            parse_float=_parse_decimal,
            parse_int=_parse_integer,
            parse_constant=_reject_non_json_number,
        )
    except RunDefinitionValidationError:
        raise
    except (json.JSONDecodeError, RecursionError, ValueError) as error:
        raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON") from error
    if not _has_portable_nesting(value):
        raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON")
    schema_version = (
        cast(dict[str, Any], value).get("schemaVersion")
        if isinstance(value, dict)
        else None
    )
    if (
        isinstance(schema_version, int)
        and not isinstance(schema_version, bool)
        and schema_version != 1
    ):
        raise RunDefinitionValidationError("RUN_DEFINITION_SCHEMA_VERSION_UNSUPPORTED")
    if isinstance(schema_version, Decimal):
        raise RunDefinitionValidationError("RUN_DEFINITION_SCHEMA_VALIDATION")
    errors = sorted(_VALIDATOR.iter_errors(value), key=lambda error: list(error.path))
    if (
        errors
        or not _has_portable_decimals(value)
        or not _has_valid_project_version_relationships(value)
        or not _has_valid_target_relationships(value)
        or not _has_valid_storage_endpoints(value)
    ):
        raise RunDefinitionValidationError("RUN_DEFINITION_SCHEMA_VALIDATION")
    return deepcopy(cast(dict[str, Any], value))


def _has_portable_nesting(value: Any) -> bool:
    pending = [(value, 1)]
    while pending:
        current, depth = pending.pop()
        if isinstance(current, (list, dict)):
            if depth > _MAXIMUM_PORTABLE_NESTING_DEPTH:
                return False
            children = current if isinstance(current, list) else current.values()
            pending.extend((child, depth + 1) for child in children)
    return True


def _has_portable_decimals(value: Any) -> bool:
    if isinstance(value, Decimal):
        exponent = value.as_tuple().exponent
        return isinstance(exponent, int) and (
            abs(exponent) <= _MAXIMUM_PORTABLE_DECIMAL_EXPONENT
        )
    if isinstance(value, list):
        return all(_has_portable_decimals(item) for item in cast(list[Any], value))
    if isinstance(value, dict):
        mapping = cast(dict[str, Any], value)
        return all(_has_portable_decimals(item) for item in mapping.values())
    return True


def _has_valid_project_version_relationships(value: Any) -> bool:
    if not isinstance(value, dict):
        return True
    version = cast(dict[str, Any], value).get("trainingProjectVersion")
    if not isinstance(version, dict):
        return True
    project_version = cast(dict[str, Any], version)
    images = project_version.get("images")
    profiles = project_version.get("environmentProfiles")
    return (
        project_version.get("versionLabel")
        == (
            f"{project_version.get('sourceRevision')}-{project_version.get('pipeline')}"
        )
        and isinstance(images, dict)
        and isinstance(profiles, dict)
        and images.keys() == profiles.keys()
    )


def _has_valid_target_relationships(value: Any) -> bool:
    if not isinstance(value, dict):
        return True
    definition = cast(dict[str, Any], value)
    target_value = definition.get("targetRequest")
    if not isinstance(target_value, dict):
        return True
    target = cast(dict[str, Any], target_value)
    required_modes = {
        "local-single-gpu": "local",
        "local-multi-gpu": "local",
        "cloud-on-demand": "on-demand",
        "cloud-spot": "spot",
    }
    target_class = target.get("targetClass")
    if not isinstance(target_class, str):
        return False
    gpu_count = target.get("gpuCount")
    return (
        target.get("purchaseMode") == required_modes.get(target_class)
        and not (target_class == "local-single-gpu" and gpu_count != 1)
        and not (
            target_class == "local-multi-gpu"
            and (not isinstance(gpu_count, int) or gpu_count < 2)
        )
    )


def _has_valid_storage_endpoints(value: Any) -> bool:
    if not isinstance(value, dict):
        return True
    definition = cast(dict[str, Any], value)
    storage_value = definition.get("storage")
    if not isinstance(storage_value, dict):
        return True
    storage = cast(dict[str, Any], storage_value)
    execution_value = storage.get("execution")
    repatriation_value = storage.get("repatriation")
    if not isinstance(execution_value, dict) or not isinstance(
        repatriation_value, dict
    ):
        return True
    execution = cast(dict[str, Any], execution_value)
    repatriation = cast(dict[str, Any], repatriation_value)
    destination_value = repatriation.get("destination")
    if not isinstance(destination_value, dict):
        return True
    destination = cast(dict[str, Any], destination_value)
    return _is_valid_storage_endpoint(
        execution.get("endpoint")
    ) and _is_valid_storage_endpoint(destination.get("endpoint"))


def _is_valid_storage_endpoint(value: Any) -> bool:
    if not isinstance(value, str) or len(value) > 2048:
        return False
    try:
        endpoint = urlsplit(value)
        return (
            endpoint.scheme.lower() in {"http", "https"}
            and endpoint.hostname is not None
            and _is_java_uri_host(endpoint.hostname)
            and endpoint.username is None
            and endpoint.password is None
            and "?" not in value
            and "#" not in value
        )
    except ValueError:
        return False


def _is_java_uri_host(host: str) -> bool:
    try:
        ip_address(host)
        return True
    except ValueError:
        return all(
            re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?", label)
            for label in host.split(".")
        )


def encode(value: dict[str, Any]) -> str:
    return _encode_json(value)


def copy_value(value: dict[str, Any]) -> dict[str, Any]:
    return deepcopy(value)


def equal_values(left: Any, right: Any) -> bool:
    if type(left) is not type(right):
        return False
    if isinstance(left, list):
        return len(left) == len(right) and all(
            equal_values(left_item, right_item)
            for left_item, right_item in zip(left, right, strict=True)
        )
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(
            equal_values(left[key], right[key]) for key in left
        )
    return bool(left == right)


def _encode_json(value: Any) -> str:
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
        return str(value)
    if isinstance(value, list):
        items = cast(list[Any], value)
        return "[" + ",".join(_encode_json(item) for item in items) + "]"
    if isinstance(value, dict):
        mapping = cast(dict[str, Any], value)
        return (
            "{"
            + ",".join(
                json.dumps(key, ensure_ascii=False) + ":" + _encode_json(item)
                for key, item in mapping.items()
            )
            + "}"
        )
    raise TypeError(f"unsupported JSON value {type(value).__name__}")
