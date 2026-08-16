"""Project-CI command for canonical Project Metric Contracts."""

from __future__ import annotations

import json
import sys
from collections.abc import Sequence
from pathlib import Path

from skywright._metric_contract import MetricContract, MetricContractError, MetricSchema


def main(arguments: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if arguments is None else arguments)
    if len(args) not in {2, 3} or args[0] not in {"validate", "publish"}:
        print(
            "usage: skywright-metrics validate CONTRACT | "
            "skywright-metrics publish CONTRACT DESTINATION",
            file=sys.stderr,
        )
        return 64
    try:
        contract = MetricContract.compile(Path(args[1]).read_bytes())
        if args[0] == "publish" and len(args) == 3:
            Path(args[2]).write_text(contract.canonical_json, encoding="utf-8")
        elif args[0] != "validate" or len(args) != 2:
            return 64
        print(
            json.dumps(
                {
                    "status": "runnable",
                    "digest": contract.digest,
                    "skywrightSchema": MetricSchema.identity(),
                },
                separators=(",", ":"),
                sort_keys=True,
            )
        )
        return 0
    except (MetricContractError, OSError) as error:
        failures = (
            [item.__dict__ for item in error.errors]
            if isinstance(error, MetricContractError)
            else [
                {
                    "code": "METRIC_IO",
                    "source": "project-contract",
                    "pointer": "",
                    "keyword": "read",
                }
            ]
        )
        print(
            json.dumps(
                {"status": "not-runnable", "errors": failures},
                separators=(",", ":"),
                sort_keys=True,
            ),
            file=sys.stderr,
        )
        return 2


__all__ = ["main"]
