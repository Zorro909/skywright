"""PROTOTYPE — throwaway. Resolved Run Configuration (B2, B5).

ADR 0002: a Run Submission carries partial overrides; Skywright merges them
onto the Training Project Version's defaults document, validates the result
against that version's JSON Schema, and persists every value explicitly.

The *exact* merge and null semantics are issue #22 and are NOT decided here.
This prototype uses a placeholder (objects merge recursively, arrays and
scalars replace, an explicit null is a value) purely so the script runs.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from .errors import ContractError

_JSON_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
    "number": (int, float),
    "integer": int,
}


def merge(defaults: Any, overrides: Any) -> Any:
    """PLACEHOLDER for issue #22. Recursive object merge; everything else replaces."""
    if isinstance(defaults, dict) and isinstance(overrides, dict):
        out = dict(defaults)
        for k, v in overrides.items():
            out[k] = merge(out[k], v) if k in out else v
        return out
    return overrides


def validate(schema: dict, instance: Any, path: str = "config") -> None:
    """A deliberately small JSON-Schema subset — enough to feel B5, not a real validator."""
    expected = schema.get("type")
    if expected:
        py = _JSON_TYPES[expected]
        ok = isinstance(instance, py) and not (expected != "boolean" and isinstance(instance, bool))
        if not ok:
            raise ContractError(
                "B5/type",
                f"{path} is {type(instance).__name__} but the Project Configuration "
                f"Contract declares {expected}",
                "fix the override, or the version's schema — validation happens before "
                "the run is accepted, not on the instance",
            )
    if "enum" in schema and instance not in schema["enum"]:
        raise ContractError(
            "B5/enum",
            f"{path} is {instance!r}, which is not one of {schema['enum']}",
            "use a declared value",
        )
    for bound, cmp_, word in (("minimum", lambda a, b: a < b, "below"),
                              ("maximum", lambda a, b: a > b, "above")):
        if bound in schema and cmp_(instance, schema[bound]):
            raise ContractError(
                "B5/range",
                f"{path} is {instance!r}, {word} the declared {bound} {schema[bound]}",
                "supply a value inside the declared range",
            )
    if expected == "object":
        props = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in instance:
                raise ContractError(
                    "B5/required",
                    f"{path}.{key} is required by the Project Configuration Contract "
                    "but is missing after defaults were applied",
                    "add it to the version's defaults document, or supply it in the "
                    "Run Submission",
                )
        for key, value in instance.items():
            if key not in props:
                raise ContractError(
                    "B5/unknown",
                    f"{path}.{key} is not declared by the Project Configuration Contract",
                    f"declared keys here are {sorted(props)}; a typo in a Run Submission "
                    "must not silently become a new setting",
                )
            validate(props[key], value, f"{path}.{key}")


class Config(Mapping):
    """Resolved, validated, read-only configuration.

    Unknown-key access raises rather than returning None, so a project that
    misreads its own contract fails at the read, not 40 minutes into a run.
    """

    def __init__(self, data: dict, path: str = "config"):
        self._data = data
        self._path = path

    def __getitem__(self, key: str) -> Any:
        if key not in self._data:
            raise ContractError(
                "B4/config-read",
                f"{self._path}.{key} was read but is not in the resolved Run Configuration",
                f"available here: {sorted(self._data)}",
            )
        value = self._data[key]
        return Config(value, f"{self._path}.{key}") if isinstance(value, dict) else value

    def __iter__(self):
        return iter(self._data)

    def __len__(self) -> int:
        return len(self._data)

    def raw(self) -> dict:
        return self._data


def resolve(schema: dict, defaults: dict, overrides: dict) -> Config:
    resolved = merge(defaults, overrides)
    validate(schema, resolved)
    return Config(resolved)
