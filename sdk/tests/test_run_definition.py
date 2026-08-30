import json
from copy import deepcopy
from decimal import Decimal
from importlib.resources import files
from typing import Any, cast

import pytest

from skywright._run_definition import RunDefinition, RunDefinitionValidationError
from skywright._run_definition_codec import encode


def test_python_accepts_shared_run_definition_corpus() -> None:
    corpus = json.loads(
        files("skywright._run_definition_resources")
        .joinpath("corpus.json")
        .read_text(),
        parse_float=Decimal,
    )
    for value in corpus["valid"]:
        document = encode(value)
        decoded = RunDefinition.decode(document)
        assert decoded.value() == value
        assert "0.1000000000000000001" in decoded.to_json()
        mutated = decoded.value()
        mutated["configuration"]["nested"]["array"][0] = "changed"
        assert decoded.value()["configuration"]["nested"]["array"][0] is None
    historical = deepcopy(corpus["valid"][0])
    historical["schemaVersion"] = 1
    del historical["costQuote"]
    assert RunDefinition.decode(encode(historical)).value() == historical
    for invalid in corpus["invalid"]:
        with pytest.raises(RunDefinitionValidationError) as failure:
            RunDefinition.decode(invalid["json"])
        assert failure.value.code == invalid["code"]
    for invalid in corpus["invalidMutations"]:
        value = deepcopy(corpus["valid"][0])
        replacement = json.loads(invalid["replacementJson"], parse_float=Decimal)
        _set_json_pointer(value, invalid["pointer"], replacement)
        document = encode(value)
        with pytest.raises(RunDefinitionValidationError) as failure:
            RunDefinition.decode(document)
        assert failure.value.code == invalid["code"]
    template = encode(corpus["valid"][0])
    for number_case in corpus["numberLengthCases"]:
        document = template.replace("9007199254740993", "1" * number_case["digits"])
        if number_case["code"] is None:
            assert RunDefinition.decode(document).value()["configuration"]["nested"][
                "array"
            ][1] == int("1" * number_case["digits"])
        else:
            with pytest.raises(RunDefinitionValidationError) as failure:
                RunDefinition.decode(document)
            assert failure.value.code == number_case["code"]
    for nesting_case in corpus["nestingCases"]:
        nesting_value = json.loads(template, parse_float=Decimal)
        nested = None
        for _ in range(nesting_case["depth"]):
            nested = [nested]
        nesting_value["configuration"]["nested"] = nested
        document = encode(nesting_value)
        if nesting_case["code"] is None:
            assert isinstance(
                RunDefinition.decode(document).value()["configuration"]["nested"], list
            )
        else:
            with pytest.raises(RunDefinitionValidationError) as failure:
                RunDefinition.decode(document)
            assert failure.value.code == nesting_case["code"]
    for exponent_case in corpus["decimalExponentCases"]:
        document = template.replace("0.1000000000000000001", exponent_case["number"])
        with pytest.raises(RunDefinitionValidationError) as failure:
            RunDefinition.decode(document)
        assert failure.value.code == exponent_case["code"]
    for endpoint_case in corpus["endpointCases"]:
        document = template.replace(
            "https://objects.example", endpoint_case["endpoint"]
        )
        if endpoint_case["code"] is None:
            assert RunDefinition.decode(document)
        else:
            with pytest.raises(RunDefinitionValidationError) as failure:
                RunDefinition.decode(document)
            assert failure.value.code == endpoint_case["code"]


def _set_json_pointer(root: dict[str, Any], pointer: str, replacement: Any) -> None:
    tokens = [
        token.replace("~1", "/").replace("~0", "~") for token in pointer.split("/")[1:]
    ]
    parent: dict[str, Any] | list[Any] = root
    for token in tokens[:-1]:
        child = (
            cast(list[Any], parent)[int(token)]
            if isinstance(parent, list)
            else parent[token]
        )
        assert isinstance(child, (dict, list))
        parent = cast(dict[str, Any] | list[Any], child)
    token = tokens[-1]
    if isinstance(parent, list):
        parent[int(token)] = replacement
    else:
        parent[token] = replacement


def test_python_preserves_large_decimal_exponents_compactly() -> None:
    value = json.loads(
        files("skywright._run_definition_resources").joinpath("corpus.json").read_text()
    )["valid"][0]
    document = json.dumps(value).replace("9007199254740993", "1e1000000000")

    encoded = RunDefinition.decode(document).to_json()

    assert "1E+1000000000" in encoded
    assert len(encoded) < 10_000
    assert RunDefinition.decode(encoded) == RunDefinition.decode(document)


def test_python_equality_preserves_json_scalar_types() -> None:
    corpus = json.loads(
        files("skywright._run_definition_resources").joinpath("corpus.json").read_text()
    )
    numeric = json.dumps(corpus["valid"][0]).replace('"array": [null,', '"array": [1,')
    boolean = numeric.replace('"array": [1,', '"array": [true,')

    assert RunDefinition.decode(numeric) != RunDefinition.decode(boolean)
    with pytest.raises(TypeError):
        hash(RunDefinition.decode(numeric))
