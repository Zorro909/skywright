# Runtime JSON fixtures deliberately exercise malformed wire inputs.
# pyright: reportUnknownMemberType=false, reportUnknownArgumentType=false, reportUnknownVariableType=false
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
import json
from copy import deepcopy
from pathlib import Path
from typing import Any

import pytest

from skywright._managed_runtime import ManagedRuntime

FIXTURE = Path(__file__).parents[1] / "fixtures/managed-runtime"


def documents() -> tuple[dict[str, Any], dict[str, Any]]:
    definition = json.loads((FIXTURE / "definition.json").read_text())
    storage = definition["storage"]["execution"]
    materials = {
        "materialsVersion": 1,
        "runId": "00000000-0000-0000-0000-000000000301",
        "image": "registry.example/project@"
        + definition["trainingProjectVersion"]["images"]["rocm"],
        "configurationContract": (FIXTURE / "configuration.json")
        .read_text()
        .rstrip("\n"),
        "metricContract": (FIXTURE / "metrics.json").read_text().rstrip("\n"),
        "dataset": json.loads((FIXTURE / "dataset.json").read_text()),
        "datasetLocation": {
            "storage_id": "dataset-storage",
            "endpoint": storage["endpoint"],
            "bucket": "datasets",
            "region": storage["region"],
            "prefix": "authority",
            "copy_id": "authority",
            "generation": 1,
            "lease_id": None,
            "path_style": True,
            "checksum_calculation": "when_required",
        },
        "sourceCheckpoint": None,
    }
    return definition, materials


def test_consumes_exact_backend_resolved_definition():
    definition, materials = documents()
    runtime = ManagedRuntime.decode(
        (FIXTURE / "definition.json").read_text(), json.dumps(materials)
    )
    assert runtime.definition.value() == definition
    assert runtime.configuration["reproducibility"] == {"seed": 9}
    assert (
        runtime.dataset_definition.content_fingerprint
        == definition["datasetDefinition"]["contentFingerprint"]
    )


@pytest.mark.parametrize(
    "case",
    [
        "version",
        "extra",
        "image",
        "configuration",
        "metric",
        "dataset",
        "unresolved",
        "seed",
        "reset",
    ],
)
def test_rejects_inconsistent_inputs_before_project_import(case):
    definition, materials = deepcopy(documents())
    if case == "version":
        definition["schemaVersion"] = 999
    elif case == "extra":
        materials["factory"] = "project:bad"
    elif case == "image":
        materials["image"] = "registry.example/project:latest"
    elif case == "configuration":
        materials["configurationContract"] += " "
    elif case == "metric":
        materials["metricContract"] += " "
    elif case == "dataset":
        materials["dataset"]["version"] = "other"
    elif case == "unresolved":
        del definition["configuration"]["checkpoint"]
    elif case == "seed":
        materials["seed"] = 123
    elif case == "reset":
        definition["orderingReset"] = True
    with pytest.raises(ValueError):
        ManagedRuntime.decode(json.dumps(definition), json.dumps(materials))


@pytest.mark.parametrize("mutation", ["extra", "missing"])
def test_rejects_incompatible_source_storage_shape(mutation):
    definition, materials = documents()
    source_storage = deepcopy(definition["storage"]["execution"])
    if mutation == "extra":
        source_storage["futureField"] = "unsupported"
    else:
        del source_storage["configurationRevision"]
    materials["sourceCheckpoint"] = {
        "runId": "00000000-0000-0000-0000-000000000302",
        "reference": "checkpoint",
        "storage": source_storage,
    }
    with pytest.raises(
        ValueError, match="source storage has missing or unsupported fields"
    ):
        ManagedRuntime.decode(json.dumps(definition), json.dumps(materials))
