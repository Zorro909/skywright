from __future__ import annotations

import json
from decimal import Decimal
from importlib.resources import files
from pathlib import Path
from typing import cast

import pytest

from skywright.configuration import (
    ConfigurationContract,
    ConfigurationContractError,
    ConfigurationError,
    main,
)

_CORPUS = cast(
    dict[str, object],
    json.loads(
        files("skywright._configuration_resources")
        .joinpath("corpus.json")
        .read_text(encoding="utf-8"),
        parse_float=Decimal,
    ),
)


def project_contract(
    project_schema: dict[str, object],
    *,
    defaults: dict[str, object] | None = None,
    witness: dict[str, object] | None = None,
    references: dict[str, object] | None = None,
) -> dict[str, object]:
    return {
        "contractVersion": 1,
        "skywrightSchema": ConfigurationContract.skywright_schema_identity(),
        "projectSchema": project_schema,
        "defaults": defaults or {},
        "defaultsCompletionWitness": witness or {},
        "references": references or {},
    }


def test_submission_resolves_with_the_normative_structural_overlay() -> None:
    contract = ConfigurationContract.compile(
        project_contract(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "type": "object",
                "properties": {
                    "project": {
                        "type": "object",
                        "properties": {
                            "nested": {"type": "object"},
                            "array": {"type": "array"},
                            "replace": {},
                            "nullable": {"type": ["number", "null"]},
                            "large": {"type": "integer"},
                            "decimal": {"type": "number"},
                        },
                        "required": [
                            "nested",
                            "array",
                            "replace",
                            "nullable",
                            "large",
                            "decimal",
                        ],
                        "additionalProperties": False,
                    }
                },
            },
            defaults={
                "project": {
                    "nested": {"left": 1, "overridden": 2},
                    "array": [1, 2],
                    "replace": {"old": True},
                    "nullable": 1,
                    "large": 9_007_199_254_740_993,
                    "decimal": Decimal("0.1"),
                }
            },
        )
    )

    resolved = contract.resolve(
        {
            "project": {
                "nested": {"overridden": 3, "right": 4},
                "array": [3],
                "replace": 2,
                "nullable": None,
            }
        }
    )

    assert resolved == {
        "reproducibility": {"seed": 0},
        "dataset": {
            "ordering": {
                "policy": "deterministic-shuffle",
                "version": "feistel-sha256-v1",
            }
        },
        "checkpoint": {"cadence": 100, "retention": 3, "keepEveryNth": None},
        "metrics": {
            "flushInterval": Decimal("10"),
            "segmentRoll": 1_000,
            "systemSamplingInterval": Decimal("10"),
        },
        "project": {
            "nested": {"left": 1, "overridden": 3, "right": 4},
            "array": [3],
            "replace": 2,
            "nullable": None,
            "large": 9_007_199_254_740_993,
            "decimal": Decimal("0.1"),
        },
    }


@pytest.mark.parametrize(
    ("artifact", "expected"),
    [
        (
            project_contract(
                {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {
                        "reproducibility": {
                            "type": "object",
                            "properties": {"seed": {"type": "integer"}},
                        }
                    },
                }
            ),
            ConfigurationError(
                "CONFIG_OWNERSHIP_COLLISION",
                "project-schema",
                "/reproducibility/seed",
                "properties",
            ),
        ),
        (
            project_contract(
                {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {"project": {"type": "integer"}},
                },
                defaults={"project": 1},
                witness={"project": 1},
            ),
            ConfigurationError(
                "CONFIG_WITNESS_REPLACEMENT",
                "defaults-completion-witness",
                "/project",
                "overlay",
            ),
        ),
    ],
)
def test_contract_rejects_invalid_ownership_and_witness(
    artifact: dict[str, object], expected: ConfigurationError
) -> None:
    with pytest.raises(ConfigurationContractError) as raised:
        ConfigurationContract.compile(artifact)

    assert raised.value.errors == (expected,)


def test_submission_errors_are_stable_and_deterministically_ordered() -> None:
    contract = ConfigurationContract.compile(
        project_contract(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "type": "object",
                "properties": {
                    "project": {
                        "type": "object",
                        "properties": {
                            "count": {"type": "integer"},
                            "name": {"type": "string"},
                        },
                        "required": ["count", "name"],
                        "additionalProperties": False,
                    }
                },
                "required": ["project"],
            },
            witness={"project": {"count": 1, "name": "valid"}},
        )
    )

    with pytest.raises(ConfigurationContractError) as raised:
        contract.resolve({"project": {"count": "wrong"}})

    assert raised.value.errors == (
        ConfigurationError(
            "CONFIG_SCHEMA_VALIDATION", "submission", "/project", "required"
        ),
        ConfigurationError(
            "CONFIG_SCHEMA_VALIDATION", "submission", "/project/count", "type"
        ),
    )


def test_raw_json_rejects_duplicate_names_and_non_object_layers() -> None:
    with pytest.raises(ConfigurationContractError) as duplicate:
        ConfigurationContract.compile('{"contractVersion":1,"contractVersion":1}')
    assert duplicate.value.errors == (
        ConfigurationError(
            "CONFIG_DUPLICATE_PROPERTY", "project-contract", "/contractVersion", "parse"
        ),
    )

    contract = ConfigurationContract.compile(
        project_contract(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "type": "object",
                "properties": {},
            }
        )
    )
    with pytest.raises(ConfigurationContractError) as non_object:
        contract.resolve("[]")
    assert non_object.value.errors == (
        ConfigurationError("CONFIG_LAYER_NOT_OBJECT", "submission", "", "type"),
    )


def test_bundled_immutable_references_and_format_assertions_are_applied() -> None:
    contract = ConfigurationContract.compile(
        project_contract(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "type": "object",
                "properties": {
                    "contact": {"$ref": "urn:example:configuration:contact:v1"}
                },
                "required": ["contact"],
            },
            witness={"contact": "maintainer@example.com"},
            references={
                "urn:example:configuration:contact:v1": {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "$id": "urn:example:configuration:contact:v1",
                    "type": "string",
                    "format": "email",
                }
            },
        )
    )

    with pytest.raises(ConfigurationContractError) as raised:
        contract.resolve({"contact": "not-an-email"})
    assert raised.value.errors == (
        ConfigurationError(
            "CONFIG_SCHEMA_VALIDATION", "submission", "/contact", "format"
        ),
    )


@pytest.mark.parametrize(
    ("schema_change", "expected_code", "pointer", "keyword"),
    [
        (
            {"$schema": "http://json-schema.org/draft-07/schema#"},
            "CONFIG_UNSUPPORTED_DIALECT",
            "/projectSchema/$schema",
            "$schema",
        ),
        (
            {"$vocabulary": {"https://example.com/vocabulary": True}},
            "CONFIG_UNSUPPORTED_VOCABULARY",
            "/projectSchema/$vocabulary/https:~1~1example.com~1vocabulary",
            "$vocabulary",
        ),
        (
            {"properties": {"remote": {"$ref": "https://example.com/live.json"}}},
            "CONFIG_MUTABLE_EXTERNAL_REFERENCE",
            "/projectSchema/properties/remote/$ref",
            "$ref",
        ),
    ],
)
def test_contract_rejects_unsupported_schema_features(
    schema_change: dict[str, object], expected_code: str, pointer: str, keyword: str
) -> None:
    schema: dict[str, object] = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": {},
    }
    schema.update(schema_change)

    with pytest.raises(ConfigurationContractError) as raised:
        ConfigurationContract.compile(project_contract(schema))

    assert raised.value.errors == (
        ConfigurationError(expected_code, "project-contract", pointer, keyword),
    )


def test_contract_requires_every_artifact_member() -> None:
    artifact = project_contract(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {},
        }
    )
    del artifact["references"]

    with pytest.raises(ConfigurationContractError) as raised:
        ConfigurationContract.compile(artifact)

    assert raised.value.errors == (
        ConfigurationError(
            "CONFIG_LAYER_NOT_OBJECT", "project-contract", "/references", "type"
        ),
    )


def test_contract_rejects_unknown_members_and_non_string_reference_names() -> None:
    schema: dict[str, object] = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": {},
    }
    unknown = project_contract(schema)
    unknown["unknown"] = True
    with pytest.raises(ConfigurationContractError) as raised:
        ConfigurationContract.compile(unknown)
    assert raised.value.errors == (
        ConfigurationError(
            "CONFIG_SCHEMA_VALIDATION",
            "project-contract",
            "",
            "additionalProperties",
        ),
    )

    invalid_reference = project_contract(
        schema, references=cast(dict[str, object], {1: {}})
    )
    with pytest.raises(ConfigurationContractError) as raised:
        ConfigurationContract.compile(invalid_reference)
    assert raised.value.errors[0].code == "CONFIG_PROPERTY_NAME_NOT_STRING"


def test_object_payloads_are_not_scanned_as_schemas() -> None:
    contract = ConfigurationContract.compile(
        project_contract(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "type": "object",
                "properties": {
                    "payload": {"const": {"$ref": "https://example.com/data"}}
                },
            },
            defaults={"payload": {"$ref": "https://example.com/data"}},
        )
    )
    assert contract.resolve({})["payload"] == {"$ref": "https://example.com/data"}


def test_invalid_project_schema_has_a_stable_failure() -> None:
    artifact = project_contract(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {},
            "required": "not-an-array",
        }
    )

    with pytest.raises(ConfigurationContractError) as raised:
        ConfigurationContract.compile(artifact)

    assert raised.value.errors == (
        ConfigurationError(
            "CONFIG_INVALID_PROJECT_SCHEMA", "project-schema", "/required", "type"
        ),
    )


def test_applicators_cannot_constrain_library_owned_properties() -> None:
    artifact = project_contract(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {},
            "allOf": [
                {
                    "properties": {
                        "reproducibility": {"properties": {"seed": {"maximum": 10}}}
                    }
                }
            ],
        }
    )

    with pytest.raises(ConfigurationContractError) as raised:
        ConfigurationContract.compile(artifact)

    assert raised.value.errors == (
        ConfigurationError(
            "CONFIG_OWNERSHIP_COLLISION",
            "project-schema",
            "/reproducibility/seed",
            "properties",
        ),
    )


@pytest.mark.parametrize(
    "case",
    cast(list[dict[str, object]], _CORPUS["cases"]),
    ids=lambda case: cast(dict[str, object], case)["name"],
)
def test_versioned_configuration_conformance_corpus(case: dict[str, object]) -> None:
    artifact: dict[str, object] = {
        "contractVersion": 1,
        "skywrightSchema": ConfigurationContract.skywright_schema_identity(),
        "projectSchema": case["projectSchema"],
        "defaults": case["projectDefaults"],
        "defaultsCompletionWitness": case["witness"],
        "references": case.get("references", {}),
    }
    contract_errors = case.get("contractErrors")
    if contract_errors is not None:
        with pytest.raises(ConfigurationContractError) as raised:
            ConfigurationContract.compile(artifact)
        assert raised.value.errors == tuple(
            ConfigurationError(**cast(dict[str, str], error))
            for error in cast(list[object], contract_errors)
        )
        return

    contract = ConfigurationContract.compile(artifact)
    submission = case.get("rawSubmission", case.get("submission", {}))
    expected_errors = case.get("expectedErrors")
    if expected_errors is not None:
        with pytest.raises(ConfigurationContractError) as raised:
            contract.resolve(cast(str | dict[str, object], submission))
        assert raised.value.errors == tuple(
            ConfigurationError(**cast(dict[str, str], error))
            for error in cast(list[object], expected_errors)
        )
        return

    assert (
        contract.resolve(cast(str | dict[str, object], submission))
        == case["expectedResolved"]
    )


def test_project_ci_command_validates_a_runnable_contract(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    path = tmp_path / "project-contract.json"
    path.write_text(
        json.dumps(
            project_contract(
                {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {},
                }
            ),
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )

    assert main(["validate", str(path)]) == 0
    assert json.loads(capsys.readouterr().out) == {
        "status": "runnable",
        "skywrightSchema": ConfigurationContract.skywright_schema_identity(),
    }
