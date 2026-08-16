"""Public Project Configuration Contract compilation interface."""

from collections.abc import Mapping
from typing import TypeAlias

from skywright._configuration_contract import (
    ConfigurationContract,
    ConfigurationContractError,
    ConfigurationError,
    main,
)

JsonDocument: TypeAlias = str | bytes | Mapping[str, object]
JsonObject: TypeAlias = dict[str, object]
JsonValue: TypeAlias = object

__all__ = [
    "ConfigurationContract",
    "ConfigurationContractError",
    "ConfigurationError",
    "JsonDocument",
    "JsonObject",
    "JsonValue",
    "main",
]
