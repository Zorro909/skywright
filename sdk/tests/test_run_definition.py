import json
from importlib.resources import files

import pytest

from skywright._run_definition import RunDefinition, RunDefinitionValidationError


def test_python_accepts_shared_run_definition_corpus() -> None:
    corpus = json.loads(
        files("skywright._run_definition_resources").joinpath("corpus.json").read_text()
    )
    for value in corpus["valid"]:
        document = json.dumps(value, ensure_ascii=False).replace(
            "0.1", "0.1000000000000000001"
        )
        decoded = RunDefinition.decode(document)
        assert json.loads(decoded.to_json(), parse_float=str) == json.loads(
            document, parse_float=str
        )
        assert "0.1000000000000000001" in decoded.to_json()
        mutated = decoded.value()
        mutated["configuration"]["nested"]["array"][0] = "changed"
        assert decoded.value()["configuration"]["nested"]["array"][0] is None
    for invalid in corpus["invalid"]:
        with pytest.raises(RunDefinitionValidationError) as failure:
            RunDefinition.decode(invalid["json"])
        assert failure.value.code == invalid["code"]
    for invalid in corpus["invalidMutations"]:
        value = corpus["valid"][0]
        document = json.dumps(value)
        replacement = invalid["replacementJson"]
        if invalid["pointer"] == "/targetRequest/purchaseMode":
            document = document.replace(
                '"purchaseMode": "spot"', f'"purchaseMode": {replacement}'
            )
        elif invalid["pointer"] == "/targetRequest/acceleratorBackend":
            document = document.replace(
                '"acceleratorBackend": "cuda"', f'"acceleratorBackend": {replacement}'
            )
        else:
            document = document.replace("0.1", replacement)
        with pytest.raises(RunDefinitionValidationError) as failure:
            RunDefinition.decode(document)
        assert failure.value.code == invalid["code"]


def test_python_preserves_large_decimal_exponents_compactly() -> None:
    value = json.loads(
        files("skywright._run_definition_resources").joinpath("corpus.json").read_text()
    )["valid"][0]
    document = json.dumps(value).replace("9007199254740993", "1e1000000000")

    encoded = RunDefinition.decode(document).to_json()

    assert "1E+1000000000" in encoded
    assert len(encoded) < 10_000
    assert RunDefinition.decode(encoded) == RunDefinition.decode(document)
