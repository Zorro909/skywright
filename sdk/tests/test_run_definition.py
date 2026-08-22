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
        elif invalid["pointer"] == "/targetRequest/targetClass":
            document = document.replace(
                '"targetClass": "cloud-spot"', f'"targetClass": {replacement}'
            )
        elif invalid["pointer"] == "/configuration/nested/array/2":
            document = document.replace("0.1", replacement)
        elif invalid["pointer"] == "/storage/execution/endpoint":
            document = document.replace(
                '"endpoint": "https://objects.example"',
                f'"endpoint": {replacement}',
            )
        elif invalid["pointer"] == "/storage/repatriation/destination/endpoint":
            document = document.replace(
                '"endpoint": "https://home.example"', f'"endpoint": {replacement}'
            )
        elif invalid["pointer"] == "/storage/execution/bucket":
            document = document.replace('"bucket": "runs"', f'"bucket": {replacement}')
        elif invalid["pointer"] == "/storage/repatriation/destination/region":
            document = document.replace('"region": "local"', f'"region": {replacement}')
        elif invalid["pointer"] == "/storage/execution/compatibilityOptions":
            document = document.replace(
                '"compatibilityOptions": {"chunkedEncoding": "disabled"}',
                f'"compatibilityOptions": {replacement}',
            )
        elif invalid["pointer"] == "/trainingProjectVersion/versionLabel":
            document = document.replace(
                '"versionLabel": "0123456789abcdef0123456789abcdef01234567-42"',
                f'"versionLabel": {replacement}',
            )
        else:
            start = document.index('"environmentProfiles": {')
            end = document.index('}, "configurationContract"', start) + 1
            document = (
                document[:start]
                + f'"environmentProfiles": {replacement}'
                + document[end:]
            )
        with pytest.raises(RunDefinitionValidationError) as failure:
            RunDefinition.decode(document)
        assert failure.value.code == invalid["code"]
    template = json.dumps(corpus["valid"][0])
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
        nesting_value = json.loads(template)
        nested = None
        for _ in range(nesting_case["depth"]):
            nested = [nested]
        nesting_value["configuration"]["nested"] = nested
        document = json.dumps(nesting_value)
        if nesting_case["code"] is None:
            assert isinstance(
                RunDefinition.decode(document).value()["configuration"]["nested"], list
            )
        else:
            with pytest.raises(RunDefinitionValidationError) as failure:
                RunDefinition.decode(document)
            assert failure.value.code == nesting_case["code"]
    for exponent_case in corpus["decimalExponentCases"]:
        document = template.replace(", 0.1]", f", {exponent_case['number']}]")
        with pytest.raises(RunDefinitionValidationError) as failure:
            RunDefinition.decode(document)
        assert failure.value.code == exponent_case["code"]


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
