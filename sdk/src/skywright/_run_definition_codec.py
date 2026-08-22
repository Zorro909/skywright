from __future__ import annotations

import json
from copy import deepcopy
from decimal import Decimal
from importlib.resources import files
from typing import Any, cast

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


_SCHEMA = json.loads(
    files("skywright._run_definition_resources").joinpath("schema.json").read_text()
)
_VALIDATOR: Any = Draft202012Validator(_SCHEMA, format_checker=FormatChecker())


def decode(document: str) -> dict[str, Any]:
    try:
        value: Any = json.loads(
            document,
            object_pairs_hook=_reject_duplicate,
            parse_float=Decimal,
            parse_int=int,
            parse_constant=_reject_non_json_number,
        )
    except RunDefinitionValidationError:
        raise
    except (json.JSONDecodeError, ValueError) as error:
        raise RunDefinitionValidationError("RUN_DEFINITION_INVALID_JSON") from error
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
    if errors:
        raise RunDefinitionValidationError("RUN_DEFINITION_SCHEMA_VALIDATION")
    return deepcopy(cast(dict[str, Any], value))


def encode(value: dict[str, Any]) -> str:
    return _encode_json(value)


def copy_value(value: dict[str, Any]) -> dict[str, Any]:
    return deepcopy(value)


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
