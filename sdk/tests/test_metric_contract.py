from __future__ import annotations

import json
from importlib.resources import files
from pathlib import Path
from typing import cast

import pytest

from skywright.metrics import (
    MetricContract,
    MetricContractError,
    MetricError,
    MetricSchema,
    ProjectMetricContract,
    main,
    project_metrics_comparable,
)


def project_contract(*definitions: dict[str, object]) -> dict[str, object]:
    return {
        "contractVersion": 1,
        "skywrightSchema": MetricSchema.identity(),
        "definitions": list(definitions),
    }


def metric_definition(**overrides: object) -> dict[str, object]:
    definition: dict[str, object] = {
        "name": "train/loss",
        "numericKind": "real",
        "unit": "dimensionless",
        "recordingBasis": "step",
        "comparison": "minimize",
        "stepReduction": "mean",
    }
    definition.update(overrides)
    return definition


def test_compiles_a_canonical_content_addressed_metric_catalog() -> None:
    artifact = project_contract(
        metric_definition(
            displayName="Training loss",
            description="Loss over the committed Step.",
            bounds={"minimum": 0},
        )
    )

    contract = MetricContract.compile(artifact)
    catalog = contract.catalog("project-identity")

    assert contract.canonical_json == (
        '{"contractVersion":1,"definitions":[{"bounds":{"minimum":0},'
        '"comparison":"minimize","description":"Loss over the committed Step.",'
        '"displayName":"Training loss","name":"train/loss",'
        '"numericKind":"real","recordingBasis":"step",'
        '"stepReduction":"mean","unit":"dimensionless"}],'
        f'"skywrightSchema":{json.dumps(MetricSchema.identity(), separators=(",", ":"), sort_keys=True)}}}'
    )
    assert contract.digest.startswith("sha256:")
    assert catalog.project_identity == "project-identity"
    assert catalog.project_contract_digest == contract.digest
    assert catalog.skywright_schema_identity == MetricSchema.identity()["version"]
    assert catalog.skywright_schema_digest == MetricSchema.identity()["digest"]
    assert catalog.units == frozenset(MetricSchema.units())
    assert catalog.project_definitions[0].name == "train/loss"
    assert catalog.system_definitions


def test_rejects_contract_rules_with_stable_deterministic_errors() -> None:
    artifact = project_contract(
        metric_definition(name="skywright/project/loss"),
        metric_definition(name="train/count", numericKind="integer"),
    )

    with pytest.raises(MetricContractError) as raised:
        MetricContract.compile(artifact)

    assert raised.value.errors == (
        MetricError(
            "METRIC_RESERVED_NAME",
            "project-contract",
            "/definitions/0/name",
            "pattern",
        ),
        MetricError(
            "METRIC_INTEGER_MEAN",
            "project-contract",
            "/definitions/1/stepReduction",
            "semantic",
        ),
    )


def test_project_metric_comparability_ignores_only_presentation_metadata() -> None:
    left = MetricContract.compile(
        project_contract(metric_definition(displayName="Loss"))
    ).catalog("stable-project")
    renamed = MetricContract.compile(
        project_contract(
            metric_definition(displayName="Objective", description="New wording")
        )
    ).catalog("stable-project")
    changed_unit = MetricContract.compile(
        project_contract(metric_definition(unit="ratio"))
    ).catalog("stable-project")

    assert project_metrics_comparable(left, renamed, "train/loss")
    assert not project_metrics_comparable(left, changed_unit, "train/loss")
    assert not project_metrics_comparable(left, renamed, "missing/name")
    assert not project_metrics_comparable(
        left,
        MetricContract.compile(project_contract(metric_definition())).catalog(
            "another-project"
        ),
        "train/loss",
    )


def test_versioned_metric_conformance_corpus() -> None:
    corpus = json.loads(
        files("skywright._metric_resources")
        .joinpath("corpus.json")
        .read_text(encoding="utf-8")
    )
    for raw_case in corpus["cases"]:
        case = cast(dict[str, object], raw_case)
        artifact = {
            "contractVersion": 1,
            "skywrightSchema": MetricSchema.identity(),
            "definitions": case["definitions"],
        }
        expected_errors = case.get("errors")
        if expected_errors is not None:
            with pytest.raises(MetricContractError) as raised:
                MetricContract.compile(artifact)
            assert raised.value.errors == tuple(
                MetricError(**cast(dict[str, str], error))
                for error in cast(list[object], expected_errors)
            ), case["name"]
        else:
            assert (
                MetricContract.compile(artifact).catalog("project").project_definitions
            )


def test_project_ci_command_publishes_canonical_contract(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    source = tmp_path / "metrics.json"
    destination = tmp_path / "published.json"
    source.write_text(
        json.dumps(project_contract(metric_definition()), indent=2), encoding="utf-8"
    )

    assert main(["publish", str(source), str(destination)]) == 0
    output = json.loads(capsys.readouterr().out)
    assert output == {
        "status": "runnable",
        "digest": MetricContract.compile(source.read_bytes()).digest,
        "skywrightSchema": MetricSchema.identity(),
    }
    assert (
        destination.read_text(encoding="utf-8")
        == MetricContract.compile(source.read_bytes()).canonical_json
    )


def test_project_metric_contract_resolves_the_pinned_artifact() -> None:
    artifact = project_contract(metric_definition())
    contract = MetricContract.compile(artifact)
    assert contract.digest == (
        "sha256:c7362328bdffe43207f2422e1fdeb32fd4996edfeee0112c6f6b745d419464d3"
    )
    resolver = ProjectMetricContract(artifact, expected_digest=contract.digest)

    assert resolver.compose("project@revision", MetricSchema.identity()["version"]) == (
        contract.catalog("project@revision")
    )

    with pytest.raises(MetricContractError) as raised:
        ProjectMetricContract(artifact, expected_digest="sha256:" + "0" * 64)
    assert raised.value.errors[0].code == "METRIC_CONTRACT_DIGEST_MISMATCH"


def test_compiled_contract_is_unchanged_when_the_source_is_mutated() -> None:
    definition = metric_definition()
    artifact = project_contract(definition)
    contract = MetricContract.compile(artifact)

    definition["name"] = "skywright/project/bypass"
    cast(list[object], artifact["definitions"]).clear()

    assert [item.name for item in contract.catalog("project").project_definitions] == [
        "train/loss"
    ]
    assert contract.digest == (
        "sha256:c7362328bdffe43207f2422e1fdeb32fd4996edfeee0112c6f6b745d419464d3"
    )


def test_comparability_normalizes_equivalent_numeric_representations() -> None:
    artifact = project_contract(metric_definition(bounds={"minimum": 0.1}))
    mapping_catalog = MetricContract.compile(artifact).catalog("project")
    json_catalog = MetricContract.compile(json.dumps(artifact)).catalog("project")

    assert project_metrics_comparable(mapping_catalog, json_catalog, "train/loss")


def test_project_ci_command_marks_an_invalid_contract_not_runnable(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    source = tmp_path / "invalid-metrics.json"
    source.write_text(
        json.dumps(project_contract(metric_definition(unit="unknown"))),
        encoding="utf-8",
    )

    assert main(["validate", str(source)]) == 2
    assert json.loads(capsys.readouterr().err) == {
        "status": "not-runnable",
        "errors": [
            {
                "code": "METRIC_UNKNOWN_UNIT",
                "source": "project-contract",
                "pointer": "/definitions/0/unit",
                "keyword": "registry",
            }
        ],
    }
