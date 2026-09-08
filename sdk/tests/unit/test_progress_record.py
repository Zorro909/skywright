"""Progress Record member validation at the JSON decoding boundary."""

import json

import pytest

from skywright.run_store import ProgressRecord


def record() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "runId": "run-1",
        "currentStep": 4,
        "latestDurableStep": 3,
        "latestDurableCheckpoint": "skywright-checkpoint:v1:3:sha256:" + "a" * 64,
        "writtenAt": "2026-09-08T14:00:00Z",
    }


@pytest.mark.parametrize("with_target", [False, True])
@pytest.mark.parametrize(
    "missing",
    [
        "schemaVersion",
        "runId",
        "currentStep",
        "latestDurableStep",
        "latestDurableCheckpoint",
        "writtenAt",
    ],
)
def test_missing_required_member_is_normalized(missing: str, with_target: bool) -> None:
    value = record()
    if with_target:
        value["targetStep"] = 10
    del value[missing]
    expected = (
        "RUN_STORE_INCOMPATIBLE_SCHEMA"
        if missing == "schemaVersion"
        else "RUN_STORE_MALFORMED_PROGRESS"
    )
    with pytest.raises(ValueError, match=expected):
        ProgressRecord.decode(json.dumps(value).encode())


@pytest.mark.parametrize("with_target", [False, True])
def test_valid_members_preserve_progress_and_reject_unknown_members(
    with_target: bool,
) -> None:
    value = record()
    if with_target:
        value["targetStep"] = 10
    result = ProgressRecord.decode(json.dumps(value).encode())
    assert result.run_id == "run-1"
    assert result.current_step == 4
    assert result.latest_durable_step == 3
    assert result.latest_durable_checkpoint == value["latestDurableCheckpoint"]
    assert result.written_at == value["writtenAt"]
    assert result.target_step == (10 if with_target else None)
    value["unknown"] = None
    with pytest.raises(ValueError, match="RUN_STORE_MALFORMED_PROGRESS"):
        ProgressRecord.decode(json.dumps(value).encode())


@pytest.mark.parametrize(
    ("field", "wrong"),
    [
        ("schemaVersion", True),
        ("schemaVersion", 1.0),
        ("schemaVersion", "1"),
        ("schemaVersion", None),
        ("runId", 1),
        ("currentStep", True),
        ("currentStep", 4.0),
        ("currentStep", None),
        ("latestDurableStep", "3"),
        ("latestDurableCheckpoint", 3),
        ("writtenAt", []),
        ("targetStep", False),
        ("targetStep", "10"),
    ],
)
def test_wrong_member_types_are_normalized(field: str, wrong: object) -> None:
    value = record()
    value[field] = wrong
    expected = (
        "RUN_STORE_INCOMPATIBLE_SCHEMA"
        if field == "schemaVersion"
        else "RUN_STORE_MALFORMED_PROGRESS"
    )
    with pytest.raises(ValueError, match=expected):
        ProgressRecord.decode(json.dumps(value).encode())
