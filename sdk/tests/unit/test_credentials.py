from __future__ import annotations

import json
from pathlib import Path

import pytest

from skywright.credentials import CredentialProjectionError, s3_credentials


def test_direct_environment_and_managed_file_share_exact_slots(tmp_path: Path) -> None:
    environment = {
        "SKYWRIGHT_DATASET_ACCESS_KEY_ID": "reader",
        "SKYWRIGHT_DATASET_SECRET_ACCESS_KEY": "reader-secret",
        "SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID": "writer",
        "SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY": "writer-secret",
        "AWS_ACCESS_KEY_ID": "backend",
        "AWS_SECRET_ACCESS_KEY": "backend-secret",
        "VAULT_TOKEN": "never-consumed",
    }
    assert s3_credentials("dataset", environment) == {
        "aws_access_key_id": "reader",
        "aws_secret_access_key": "reader-secret",
    }
    assert s3_credentials("run_store", environment)["aws_access_key_id"] == "writer"
    path = tmp_path / "storage.json"
    path.write_text(
        json.dumps({"ACCESS_KEY_ID": "writer", "SECRET_ACCESS_KEY": "writer-secret"})
    )
    path.chmod(0o400)
    assert s3_credentials(
        "run_store", {"SKYWRIGHT_RUN_STORE_CREDENTIAL_FILE": str(path)}
    ) == s3_credentials("run_store", environment)


@pytest.mark.parametrize("mode", [0o600, 0o644, 0o440, 0o000])
def test_structured_files_must_be_owner_only_and_read_only(
    tmp_path: Path, mode: int
) -> None:
    path = tmp_path / "storage.json"
    path.write_text('{"ACCESS_KEY_ID":"reader","SECRET_ACCESS_KEY":"never-log-this"}')
    path.chmod(mode)
    with pytest.raises(CredentialProjectionError, match="unavailable") as caught:
        s3_credentials("dataset", {"SKYWRIGHT_DATASET_CREDENTIAL_FILE": str(path)})
    assert "never-log-this" not in str(caught.value)
    assert caught.value.__suppress_context__


def test_missing_partial_mixed_and_symlink_projections_never_fall_back(
    tmp_path: Path,
) -> None:
    for environment in (
        {"AWS_ACCESS_KEY_ID": "ambient", "AWS_SECRET_ACCESS_KEY": "ambient"},
        {"SKYWRIGHT_DATASET_ACCESS_KEY_ID": "partial"},
        {"SKYWRIGHT_DATASET_CREDENTIAL_FILE": "/missing"},
        {
            "SKYWRIGHT_DATASET_ACCESS_KEY_ID": "mixed",
            "SKYWRIGHT_DATASET_CREDENTIAL_FILE": "/missing",
        },
    ):
        with pytest.raises(CredentialProjectionError):
            s3_credentials("dataset", environment)
    path = tmp_path / "actual"
    path.write_text("{}")
    path.chmod(0o400)
    link = tmp_path / "link"
    link.symlink_to(path)
    with pytest.raises(CredentialProjectionError):
        s3_credentials("dataset", {"SKYWRIGHT_DATASET_CREDENTIAL_FILE": str(link)})
