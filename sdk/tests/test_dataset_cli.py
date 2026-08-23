from __future__ import annotations

import json
import struct
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path
from unittest.mock import patch

import pytest

from skywright._dataset_cli import main
from skywright._dataset_publication import DatasetPublicationError, inspect_mds_corpus


def write_corpus(root: Path, *, encoding: str = "bytes") -> None:
    configuration = {
        "column_encodings": [encoding],
        "column_names": ["value"],
        "column_sizes": [None],
        "compression": None,
        "format": "mds",
        "hashes": [],
        "size_limit": 1024,
        "version": 2,
    }
    configuration_bytes = json.dumps(
        configuration, separators=(",", ":"), sort_keys=True
    ).encode()
    value = b"one stable sample"
    sample = struct.pack("<I", len(value)) + value
    first_offset = 12 + len(configuration_bytes)
    shard = (
        struct.pack("<I", 1)
        + struct.pack("<II", first_offset, first_offset + len(sample))
        + configuration_bytes
        + sample
    )
    root.mkdir()
    (root / "shard.00000.mds").write_bytes(shard)
    (root / "index.json").write_text(
        json.dumps(
            {
                "version": 2,
                "shards": [
                    {
                        **configuration,
                        "raw_data": {
                            "basename": "shard.00000.mds",
                            "bytes": len(shard),
                            "hashes": {},
                        },
                        "samples": 1,
                        "size_limit": 1024,
                        "version": 2,
                        "zip_data": None,
                    }
                ],
            },
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )


def test_single_shard_mds_has_a_stable_complete_manifest(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)

    inspected = inspect_mds_corpus(corpus)

    assert inspected.format_identity == "mosaicml-streaming-mds@2"
    assert [entry.object_key for entry in inspected.entries] == [
        "index.json",
        "shard.00000.mds",
    ]
    assert inspected.object_count == 2
    assert inspected.byte_count > len((corpus / "index.json").read_bytes())
    assert inspected.manifest_identity.startswith("sha256:")
    assert inspected.content_fingerprint.startswith("sha256:")


def test_mds_validation_rejects_unsafe_serialization_and_extra_files(
    tmp_path: Path,
) -> None:
    unsafe = tmp_path / "unsafe"
    write_corpus(unsafe, encoding="pkl")

    with pytest.raises(DatasetPublicationError, match="unsafe") as raised:
        inspect_mds_corpus(unsafe)
    assert raised.value.code == "DATASET_CORPUS_UNSAFE_ENCODING"

    corpus = tmp_path / "extra"
    write_corpus(corpus)
    (corpus / "unreferenced.bin").write_bytes(b"not in index")

    with pytest.raises(DatasetPublicationError, match="unreferenced") as raised:
        inspect_mds_corpus(corpus)
    assert raised.value.code == "DATASET_CORPUS_INVALID"


def test_mds_validation_requires_version_and_supported_encoding(tmp_path: Path) -> None:
    unknown = tmp_path / "unknown"
    write_corpus(unknown, encoding="definitely-not-an-mds-encoding")

    with pytest.raises(DatasetPublicationError, match="unsupported") as raised:
        inspect_mds_corpus(unknown)
    assert raised.value.code == "DATASET_CORPUS_INVALID"

    missing_version = tmp_path / "missing-version"
    write_corpus(missing_version)
    index = json.loads((missing_version / "index.json").read_text())
    del index["version"]
    (missing_version / "index.json").write_text(json.dumps(index))

    with pytest.raises(DatasetPublicationError, match="version 2") as raised:
        inspect_mds_corpus(missing_version)
    assert raised.value.code == "DATASET_CORPUS_INVALID"


def test_mds_validation_rejects_symlinked_payload(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    shard = corpus / "shard.00000.mds"
    original = tmp_path / "outside.mds"
    original.write_bytes(shard.read_bytes())
    shard.unlink()
    shard.symlink_to(original)

    with pytest.raises(DatasetPublicationError, match="regular file") as raised:
        inspect_mds_corpus(corpus)
    assert raised.value.code == "DATASET_CORPUS_INVALID"


def test_mds_validation_rejects_arbitrary_bytes_with_plausible_index(
    tmp_path: Path,
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    shard = corpus / "shard.00000.mds"
    shard.write_bytes(b"not an mds shard")
    index = json.loads((corpus / "index.json").read_text())
    index["shards"][0]["raw_data"]["bytes"] = shard.stat().st_size
    (corpus / "index.json").write_text(json.dumps(index))

    with pytest.raises(DatasetPublicationError, match="MDS shard") as raised:
        inspect_mds_corpus(corpus)
    assert raised.value.code == "DATASET_CORPUS_INVALID"


def test_command_reports_missing_corpus_as_one_safe_problem_line(
    tmp_path: Path,
) -> None:
    stdout = StringIO()
    stderr = StringIO()
    missing = tmp_path / "secret-name" / "missing"

    with redirect_stdout(stdout), redirect_stderr(stderr):
        status = main(
            [
                "publish",
                str(missing),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                "00000000-0000-0000-0000-000000000001",
                "--version-label",
                "v1",
            ]
        )

    assert status == 2
    assert stdout.getvalue() == ""
    assert stderr.getvalue().count("\n") == 1
    assert "secret-name" not in stderr.getvalue()
    assert "Traceback" not in stderr.getvalue()
    assert (
        json.loads(stderr.getvalue())["errorCode"] == "SKYWRIGHT_DATASET_CORPUS_INVALID"
    )


def test_command_reports_malformed_success_response_as_one_problem_line(
    tmp_path: Path,
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    stdout = StringIO()
    stderr = StringIO()

    with (
        patch("urllib.request.urlopen", return_value=BytesIO(b"not-json")),
        redirect_stdout(stdout),
        redirect_stderr(stderr),
    ):
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                "00000000-0000-0000-0000-000000000001",
                "--version-label",
                "v1",
            ]
        )

    assert status == 1
    assert stdout.getvalue() == ""
    assert stderr.getvalue().count("\n") == 1
    assert "Traceback" not in stderr.getvalue()
    assert json.loads(stderr.getvalue())["errorCode"] == (
        "SKYWRIGHT_CONTROL_PLANE_PROTOCOL_FAILURE"
    )
