"""Operational project-CI command for configuration contracts."""

from __future__ import annotations

import json
import sys
from collections.abc import Mapping, Sequence
from decimal import Decimal
from pathlib import Path
from typing import cast

from skywright._configuration_contract import (
    ConfigurationContract,
    ConfigurationContractError,
)


def _json_text(value: object) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (int, Decimal)):
        return str(value)
    if isinstance(value, list):
        items = cast(list[object], value)
        return "[" + ",".join(_json_text(item) for item in items) + "]"
    if isinstance(value, Mapping):
        items_by_name = cast(Mapping[object, object], value)
        return (
            "{"
            + ",".join(
                f"{json.dumps(str(name), ensure_ascii=False)}:{_json_text(item)}"
                for name, item in items_by_name.items()
            )
            + "}"
        )
    raise TypeError(f"cannot serialize {type(value).__name__} as JSON")


def main(arguments: Sequence[str] | None = None) -> int:
    """Validate a project artifact or resolve one submission for project CI."""
    args = list(sys.argv[1:] if arguments is None else arguments)
    if len(args) not in {2, 3} or args[0] not in {"validate", "resolve"}:
        print(
            "usage: skywright-config validate CONTRACT | "
            "skywright-config resolve CONTRACT SUBMISSION",
            file=sys.stderr,
        )
        return 64
    try:
        contract = ConfigurationContract.compile(Path(args[1]).read_bytes())
        if args[0] == "validate" and len(args) == 2:
            print(
                _json_text(
                    {
                        "status": "runnable",
                        "skywrightSchema": contract.skywright_schema_identity(),
                    }
                )
            )
            return 0
        if args[0] == "resolve" and len(args) == 3:
            print(_json_text(contract.resolve(Path(args[2]).read_bytes())))
            return 0
    except (ConfigurationContractError, OSError) as error:
        failures = (
            [
                {
                    "code": item.code,
                    "source": item.source,
                    "pointer": item.pointer,
                    "keyword": item.keyword,
                }
                for item in error.errors
            ]
            if isinstance(error, ConfigurationContractError)
            else [
                {
                    "code": "CONFIG_IO",
                    "source": "project-contract",
                    "pointer": "",
                    "keyword": "read",
                }
            ]
        )
        print(
            _json_text({"status": "not-runnable", "errors": failures}),
            file=sys.stderr,
        )
        return 2
    return 64


__all__ = ["main"]
