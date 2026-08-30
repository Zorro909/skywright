from __future__ import annotations

import json
import re
from collections.abc import Iterable
from copy import deepcopy
from decimal import Decimal
from importlib.resources import files
from ipaddress import ip_address
from typing import Any, Protocol, cast
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


class _SchemaError(Protocol):
    path: Iterable[object]


class _Validator(Protocol):
    def iter_errors(self, instance: object) -> Iterable[_SchemaError]: ...


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


_RESOURCE_ROOT = files("skywright._run_definition_resources")
_SCHEMAS: dict[int, dict[str, Any]] = {
    1: cast(
        dict[str, Any],
        json.loads(_RESOURCE_ROOT.joinpath("schema-v1.json").read_text()),
    ),
    2: cast(
        dict[str, Any],
        json.loads(_RESOURCE_ROOT.joinpath("schema.json").read_text()),
    ),
}
_VALIDATORS: dict[int, _Validator] = {
    version: cast(
        _Validator,
        Draft202012Validator(schema, format_checker=FormatChecker()),
    )
    for version, schema in _SCHEMAS.items()
}
_CURRENCY_MINOR_UNITS = cast(
    dict[str, int],
    json.loads(_RESOURCE_ROOT.joinpath("iso-4217.json").read_text()),
)
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
        and schema_version not in _VALIDATORS
    ):
        raise RunDefinitionValidationError("RUN_DEFINITION_SCHEMA_VERSION_UNSUPPORTED")
    if isinstance(schema_version, Decimal):
        raise RunDefinitionValidationError("RUN_DEFINITION_SCHEMA_VALIDATION")
    validator = (
        _VALIDATORS[schema_version]
        if isinstance(schema_version, int)
        and not isinstance(schema_version, bool)
        and schema_version in _VALIDATORS
        else _VALIDATORS[2]
    )
    errors = sorted(
        validator.iter_errors(cast(object, value)),
        key=lambda error: list(error.path),
    )
    if (
        errors
        or not _has_portable_decimals(value)
        or not _has_valid_project_version_relationships(value)
        or not _has_valid_target_relationships(value)
        or not _has_valid_storage_endpoints(value)
        or not _has_valid_quote_relationships(value)
    ):
        raise RunDefinitionValidationError("RUN_DEFINITION_SCHEMA_VALIDATION")
    return deepcopy(cast(dict[str, Any], value))


def _has_valid_quote_relationships(value: Any) -> bool:
    if not isinstance(value, dict):
        return True
    definition = cast(dict[str, Any], value)
    if definition.get("schemaVersion") != 2:
        return True
    quote_value = definition.get("costQuote")
    if not isinstance(quote_value, dict):
        return True
    quote = cast(dict[str, Any], quote_value)
    reporting_value = quote.get("reportingCurrency")
    if not isinstance(reporting_value, dict):
        return True
    reporting = cast(dict[str, Any], reporting_value)
    reporting_code = reporting.get("code")
    if not isinstance(reporting_code, str) or _CURRENCY_MINOR_UNITS.get(
        reporting_code
    ) != reporting.get("minorUnit"):
        return False
    candidates_value = quote.get("candidates")
    if not isinstance(candidates_value, list):
        return True
    candidates = cast(list[Any], candidates_value)
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        candidate_mapping = cast(dict[str, Any], candidate)
        native_rate_value = candidate_mapping.get("nativeRate")
        if not isinstance(native_rate_value, dict):
            continue
        native_rate = cast(dict[str, Any], native_rate_value)
        native_currency = native_rate.get("currency")
        if (
            not isinstance(native_currency, str)
            or native_currency not in _CURRENCY_MINOR_UNITS
        ):
            return False
        conversion_value = candidate_mapping.get("conversion")
        if native_currency == reporting_code:
            if conversion_value is not None:
                return False
        elif not isinstance(conversion_value, dict):
            return False
        else:
            conversion = cast(dict[str, Any], conversion_value)
            if (
                conversion.get("nativeCurrency") != native_currency
                or conversion.get("reportingCurrency") != reporting_code
            ):
                return False
    for name in ("hourly", "daily", "weekly"):
        interval_value = quote.get(name)
        interval = (
            cast(dict[str, Any], interval_value)
            if isinstance(interval_value, dict)
            else None
        )
        if (
            interval is not None
            and isinstance(interval.get("minimum"), (int, Decimal))
            and isinstance(interval.get("maximum"), (int, Decimal))
            and interval["minimum"] > interval["maximum"]
        ):
            return False
    policy_value = definition.get("executionPolicy")
    policy = (
        cast(dict[str, Any], policy_value) if isinstance(policy_value, dict) else None
    )
    ceiling_value = policy.get("costCeiling") if policy is not None else None
    if not isinstance(ceiling_value, dict):
        return True
    ceiling = cast(dict[str, Any], ceiling_value)
    ceiling_currency: object = ceiling.get("currency")
    return ceiling_currency == reporting_code


def _has_portable_nesting(value: Any) -> bool:
    pending: list[tuple[Any, int]] = [(value, 1)]
    while pending:
        current, depth = pending.pop()
        if isinstance(current, (list, dict)):
            if depth > _MAXIMUM_PORTABLE_NESTING_DEPTH:
                return False
            children: Iterable[Any] = (
                cast(list[Any], current)
                if isinstance(current, list)
                else cast(dict[str, Any], current).values()
            )
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
        _port = endpoint.port
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
        dns_host = host[:-1] if host.endswith(".") else host
        if not dns_host:
            return False
        return all(
            re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?", label)
            for label in dns_host.split(".")
        )


def encode(value: dict[str, Any]) -> str:
    return _encode_json(value)


def copy_value(value: dict[str, Any]) -> dict[str, Any]:
    return deepcopy(value)


def equal_values(left: Any, right: Any) -> bool:
    if type(left) is not type(right):
        return False
    if isinstance(left, list):
        left_items = cast(list[Any], left)
        right_items = cast(list[Any], right)
        return len(left_items) == len(right_items) and all(
            equal_values(left_item, right_item)
            for left_item, right_item in zip(left_items, right_items, strict=True)
        )
    if isinstance(left, dict):
        left_mapping = cast(dict[str, Any], left)
        right_mapping = cast(dict[str, Any], right)
        return left_mapping.keys() == right_mapping.keys() and all(
            equal_values(left_mapping[key], right_mapping[key]) for key in left_mapping
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
