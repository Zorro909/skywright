# pyright: reportMissingTypeStubs=false

from __future__ import annotations

import base64
import bz2
import gzip
import hashlib
import json
import os
import socket
import struct
from collections.abc import Callable
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path
from typing import cast
from unittest.mock import patch

import pytest

from skywright._dataset_cli import main
from skywright._dataset_errors import DatasetPublicationError
from skywright._dataset_publication import inspect_mds_corpus


def write_corpus(
    root: Path,
    *,
    encoding: str = "bytes",
    values: tuple[bytes, ...] = (b"one stable sample",),
    partition: str | None = None,
    compression: str | None = None,
    hash_names: tuple[str, ...] = (),
) -> None:
    fixed_size = 8 if encoding in {"int", "int64", "uint64"} else None
    configuration: dict[str, object] = {
        "column_encodings": [encoding],
        "column_names": ["value"],
        "column_sizes": [fixed_size],
        "compression": compression,
        "format": "mds",
        "hashes": list(hash_names),
        "size_limit": 1024,
        "version": 2,
    }
    configuration_bytes = json.dumps(
        configuration, separators=(",", ":"), sort_keys=True
    ).encode()
    fixed = fixed_size is not None
    samples = [
        value if fixed else struct.pack("<I", len(value)) + value for value in values
    ]
    first_offset = 4 * (len(samples) + 2) + len(configuration_bytes)
    offsets = [first_offset]
    for sample in samples:
        offsets.append(offsets[-1] + len(sample))
    shard = (
        struct.pack("<I", len(samples))
        + struct.pack(f"<{len(offsets)}I", *offsets)
        + configuration_bytes
        + b"".join(samples)
    )
    root.mkdir()
    shard_directory = root if partition is None else root / partition
    shard_directory.mkdir(exist_ok=True)
    raw_basename = "shard.00000.mds"
    object_basename = raw_basename
    object_bytes: bytes = shard
    zip_data: dict[str, object] | None = None
    if compression is not None:
        family = compression.split(":", 1)[0]
        object_basename += f".{family}"
        if family == "gz":
            object_bytes = gzip.compress(shard, mtime=0)
        elif family == "bz2":
            object_bytes = bz2.compress(shard)
        elif family == "br":
            import brotli  # pyright: ignore[reportMissingTypeStubs]

            object_bytes = cast(
                bytes,
                brotli.compress(shard),  # pyright: ignore[reportUnknownMemberType]
            )
        elif family == "snappy":
            import snappy  # pyright: ignore[reportMissingTypeStubs]

            object_bytes = snappy.compress(  # pyright: ignore[reportUnknownMemberType]
                shard
            )
        else:
            import zstandard

            object_bytes = zstandard.compress(shard)
        zip_data = {
            "basename": object_basename,
            "bytes": len(object_bytes),
            "hashes": {
                name: hashlib.new(name, object_bytes).hexdigest() for name in hash_names
            },
        }
    (shard_directory / object_basename).write_bytes(object_bytes)
    referenced_basename = (
        object_basename if partition is None else f"{partition}/{object_basename}"
    )
    raw_referenced_basename = (
        raw_basename if partition is None else f"{partition}/{raw_basename}"
    )
    descriptor: dict[str, object] = {
        **configuration,
        "compression": compression,
        "raw_data": {
            "basename": raw_referenced_basename,
            "bytes": len(shard),
            "hashes": {
                name: hashlib.new(name, shard).hexdigest() for name in hash_names
            },
        },
        "samples": len(samples),
        "size_limit": 1024,
        "version": 2,
        "zip_data": (
            None if zip_data is None else {**zip_data, "basename": referenced_basename}
        ),
    }
    (root / "index.json").write_text(
        json.dumps(
            {
                "version": 2,
                "shards": [descriptor],
            },
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )
    if partition is not None:
        partition_descriptor = json.loads(json.dumps(descriptor))
        partition_descriptor["raw_data"]["basename"] = raw_basename
        if partition_descriptor["zip_data"] is not None:
            partition_descriptor["zip_data"]["basename"] = object_basename
        (shard_directory / "index.json").write_text(
            json.dumps(
                {"version": 2, "shards": [partition_descriptor]},
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


def test_multi_shard_partitioned_and_compressed_mds_has_one_complete_manifest(
    tmp_path: Path,
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(
        corpus,
        encoding="int64",
        values=((1).to_bytes(8, "little", signed=True),),
        partition="part-é",
        compression="gz",
    )
    index = json.loads((corpus / "index.json").read_text())
    first = index["shards"][0]
    second = json.loads(json.dumps(first))
    second["raw_data"]["basename"] = "part-β/shard.00001.mds"
    second["zip_data"]["basename"] = "part-β/shard.00001.mds.gz"
    second_bytes = (corpus / first["zip_data"]["basename"]).read_bytes()
    second["zip_data"]["bytes"] = len(second_bytes)
    (corpus / "part-β").mkdir()
    (corpus / second["zip_data"]["basename"]).write_bytes(second_bytes)
    partition_second = json.loads(json.dumps(second))
    partition_second["raw_data"]["basename"] = "shard.00001.mds"
    partition_second["zip_data"]["basename"] = "shard.00001.mds.gz"
    (corpus / "part-β" / "index.json").write_text(
        json.dumps({"version": 2, "shards": [partition_second]}, separators=(",", ":"))
    )
    index["shards"].append(second)
    (corpus / "index.json").write_text(json.dumps(index, separators=(",", ":")))

    inspected = inspect_mds_corpus(corpus)

    assert [entry.object_key for entry in inspected.entries] == [
        "index.json",
        "part-é/index.json",
        "part-é/shard.00000.mds.gz",
        "part-β/index.json",
        "part-β/shard.00001.mds.gz",
    ]
    assert inspected.object_count == 5
    assert inspected.byte_count == sum(entry.byte_count for entry in inspected.entries)


def test_versioned_mds_fixture_has_fixed_manifest_and_content_identities(
    tmp_path: Path,
) -> None:
    fixture_path = (
        Path(__file__).parents[2]
        / "tests/fixtures/dataset-publication/mds-v2-contract.json"
    )
    fixture = json.loads(fixture_path.read_text())
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    for item in fixture["files"]:
        destination = corpus / item["objectKey"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(base64.b64decode(item["base64"]))

    inspected = inspect_mds_corpus(corpus)
    expected = fixture["expected"]

    assert inspected.format_identity == expected["formatIdentity"]
    assert inspected.object_count == expected["objectCount"]
    assert inspected.byte_count == expected["byteCount"]
    assert inspected.manifest_bytes == base64.b64decode(expected["manifestBase64"])
    assert inspected.manifest_identity == expected["manifestIdentity"]
    assert inspected.content_fingerprint == expected["contentFingerprint"]


@pytest.mark.parametrize("compression", ["br", "bz2", "gz", "snappy", "zstd"])
def test_mds_validation_accepts_every_supported_compression_family(
    tmp_path: Path, compression: str
) -> None:
    corpus = tmp_path / compression
    write_corpus(corpus, compression=compression)

    inspected = inspect_mds_corpus(corpus)

    assert inspected.object_count == 2
    assert inspected.entries[1].object_key.endswith(f".{compression}")


@pytest.mark.parametrize(
    "object_key",
    [
        "",
        "/absolute.mds",
        "../outside.mds",
        "part//shard.mds",
        "part\\shard.mds",
        "./shard.mds",
        "shard\x00.mds",
    ],
)
def test_mds_validation_rejects_unsafe_object_key_forms(
    tmp_path: Path, object_key: str
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    index = json.loads((corpus / "index.json").read_text())
    index["shards"][0]["raw_data"]["basename"] = object_key
    (corpus / "index.json").write_text(json.dumps(index))

    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(corpus)

    assert raised.value.code == "DATASET_CORPUS_PATH_INVALID"


def test_mds_validation_rejects_unsafe_and_duplicate_compressed_raw_paths(
    tmp_path: Path,
) -> None:
    unsafe = tmp_path / "unsafe-compressed"
    write_corpus(unsafe, compression="gz")
    index = json.loads((unsafe / "index.json").read_text())
    index["shards"][0]["raw_data"]["basename"] = "../outside.mds"
    (unsafe / "index.json").write_text(json.dumps(index))
    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(unsafe)
    assert raised.value.code == "DATASET_CORPUS_PATH_INVALID"

    duplicate = tmp_path / "duplicate-compressed"
    write_corpus(duplicate, compression="gz")
    index = json.loads((duplicate / "index.json").read_text())
    second = json.loads(json.dumps(index["shards"][0]))
    second["zip_data"]["basename"] = "shard.00001.mds.gz"
    source = duplicate / index["shards"][0]["zip_data"]["basename"]
    (duplicate / second["zip_data"]["basename"]).write_bytes(source.read_bytes())
    index["shards"].append(second)
    (duplicate / "index.json").write_text(json.dumps(index))
    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(duplicate)
    assert raised.value.code == "DATASET_CORPUS_PATH_INVALID"


def test_mds_validation_verifies_declared_raw_and_compressed_hashes(
    tmp_path: Path,
) -> None:
    valid = tmp_path / "valid"
    write_corpus(valid, compression="gz", hash_names=("sha256",))
    assert inspect_mds_corpus(valid).object_count == 2

    for descriptor_name in ("raw_data", "zip_data"):
        corpus = tmp_path / descriptor_name
        write_corpus(corpus, compression="gz", hash_names=("sha256",))
        index = json.loads((corpus / "index.json").read_text())
        index["shards"][0][descriptor_name]["hashes"]["sha256"] = "0" * 64
        (corpus / "index.json").write_text(json.dumps(index))

        with pytest.raises(DatasetPublicationError) as raised:
            inspect_mds_corpus(corpus)

        assert raised.value.code == "DATASET_MDS_DIGEST_METADATA_MISMATCH"


def test_mds_validation_requires_each_declared_descriptor_hash(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus, hash_names=("sha256",))
    index = json.loads((corpus / "index.json").read_text())
    index["shards"][0]["raw_data"]["hashes"] = {}
    (corpus / "index.json").write_text(json.dumps(index))

    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(corpus)

    assert raised.value.code == "DATASET_MDS_DECODING_METADATA_INVALID"


def test_mds_validation_accepts_unsorted_supported_hash_names(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus, hash_names=("sha256", "sha1"))

    assert inspect_mds_corpus(corpus).object_count == 2


@pytest.mark.parametrize(
    ("encoding", "value"),
    [
        ("str_int", b"not-an-int"),
        ("jpeg", b"not-a-jpeg"),
        ("ndarray", b"\xff"),
        ("list[jpeg]", struct.pack("<III", 0, 1, 100) + b"short"),
    ],
)
def test_mds_validation_rejects_values_that_their_safe_encoding_cannot_decode(
    tmp_path: Path, encoding: str, value: bytes
) -> None:
    corpus = tmp_path / encoding.replace("/", "-")
    write_corpus(corpus, encoding=encoding, values=(value,))

    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(corpus)

    assert raised.value.code == "DATASET_MDS_DECODING_METADATA_INVALID"


def test_partition_descriptor_must_be_structurally_valid(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus, partition="part")
    partition_index = json.loads((corpus / "part" / "index.json").read_text())
    del partition_index["shards"][0]["raw_data"]["basename"]
    (corpus / "part" / "index.json").write_text(json.dumps(partition_index))

    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(corpus)

    assert raised.value.code == "DATASET_MDS_DECODING_METADATA_INVALID"


def test_mds_validation_rejects_duplicate_references_and_special_files(
    tmp_path: Path,
) -> None:
    duplicate = tmp_path / "duplicate"
    write_corpus(duplicate)
    index = json.loads((duplicate / "index.json").read_text())
    index["shards"].append(index["shards"][0])
    (duplicate / "index.json").write_text(json.dumps(index))
    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(duplicate)
    assert raised.value.code == "DATASET_CORPUS_PATH_INVALID"

    fifo = tmp_path / "fifo"
    write_corpus(fifo)
    os.mkfifo(fifo / "device")
    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(fifo)
    assert raised.value.code == "DATASET_CORPUS_FILE_TYPE_INVALID"

    socket_corpus = tmp_path / "socket"
    write_corpus(socket_corpus)
    with socket.socket(socket.AF_UNIX) as special:
        special.bind(str(socket_corpus / "special.socket"))
        with pytest.raises(DatasetPublicationError) as raised:
            inspect_mds_corpus(socket_corpus)
    assert raised.value.code == "DATASET_CORPUS_FILE_TYPE_INVALID"


IndexMutation = Callable[[dict[str, object]], None]


def _first_shard(index: dict[str, object]) -> dict[str, object]:
    shards = cast(list[object], index["shards"])
    return cast(dict[str, object], shards[0])


def _set_old_index_version(index: dict[str, object]) -> None:
    index["version"] = 1


def _set_wrong_format(index: dict[str, object]) -> None:
    _first_shard(index)["format"] = "json"


def _remove_column_size(index: dict[str, object]) -> None:
    _first_shard(index)["column_sizes"] = []


def _set_wrong_raw_size(index: dict[str, object]) -> None:
    raw = cast(dict[str, object], _first_shard(index)["raw_data"])
    raw["bytes"] = 1


def _set_wrong_sample_count(index: dict[str, object]) -> None:
    _first_shard(index)["samples"] = 2


@pytest.mark.parametrize(
    ("mutation", "code"),
    [
        (_set_old_index_version, "DATASET_MDS_VERSION_UNSUPPORTED"),
        (_set_wrong_format, "DATASET_STREAMING_FORMAT_UNSUPPORTED"),
        (_remove_column_size, "DATASET_MDS_DECODING_METADATA_INVALID"),
        (_set_wrong_raw_size, "DATASET_MDS_BYTE_METADATA_MISMATCH"),
        (_set_wrong_sample_count, "DATASET_MDS_SAMPLE_METADATA_MISMATCH"),
    ],
)
def test_mds_validation_uses_repairable_contract_codes(
    tmp_path: Path, mutation: IndexMutation, code: str
) -> None:
    corpus = tmp_path / code
    write_corpus(corpus)
    index = cast(dict[str, object], json.loads((corpus / "index.json").read_text()))
    mutation(index)
    (corpus / "index.json").write_text(json.dumps(index))

    with pytest.raises(DatasetPublicationError) as raised:
        inspect_mds_corpus(corpus)

    assert raised.value.code == code


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
    assert raised.value.code == "DATASET_CORPUS_FILE_UNREFERENCED"


def test_mds_validation_requires_version_and_supported_encoding(tmp_path: Path) -> None:
    unknown = tmp_path / "unknown"
    write_corpus(unknown, encoding="definitely-not-an-mds-encoding")

    with pytest.raises(DatasetPublicationError, match="unsupported") as raised:
        inspect_mds_corpus(unknown)
    assert raised.value.code == "DATASET_MDS_ENCODING_UNSUPPORTED"

    missing_version = tmp_path / "missing-version"
    write_corpus(missing_version)
    index = json.loads((missing_version / "index.json").read_text())
    del index["version"]
    (missing_version / "index.json").write_text(json.dumps(index))

    with pytest.raises(DatasetPublicationError, match="version 2") as raised:
        inspect_mds_corpus(missing_version)
    assert raised.value.code == "DATASET_MDS_VERSION_UNSUPPORTED"


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
    assert raised.value.code == "DATASET_CORPUS_FILE_TYPE_INVALID"


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
    assert raised.value.code == "DATASET_MDS_SAMPLE_METADATA_MISMATCH"


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
    assert json.loads(stderr.getvalue())["errorCode"] == (
        "SKYWRIGHT_DATASET_CORPUS_FILE_MISSING"
    )


def test_command_allows_the_version_label_to_be_omitted(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    stdout = StringIO()

    with (
        patch(
            "skywright._dataset_cli._publish",
            return_value={"versionLabel": "0123456789abcdef"},
        ) as publish,
        redirect_stdout(stdout),
    ):
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                "00000000-0000-0000-0000-000000000001",
            ]
        )

    assert status == 0
    assert json.loads(stdout.getvalue())["versionLabel"] == "0123456789abcdef"
    publish.assert_called_once_with(
        corpus,
        "http://control-plane",
        "00000000-0000-0000-0000-000000000001",
        None,
        4,
        None,
        None,
        None,
        None,
    )


def test_command_accepts_bounded_publication_concurrency(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)

    with patch("skywright._dataset_cli._publish", return_value={}) as publish:
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                "00000000-0000-0000-0000-000000000001",
                "--concurrency",
                "3",
            ]
        )

    assert status == 0
    publish.assert_called_once_with(
        corpus,
        "http://control-plane",
        "00000000-0000-0000-0000-000000000001",
        None,
        3,
        None,
        None,
        None,
        None,
    )


def test_command_reports_and_explicitly_resumes_one_publication(
    tmp_path: Path,
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    inspected = inspect_mds_corpus(corpus)
    publication_id = "00000000-0000-0000-0000-000000000010"
    storage_id = "00000000-0000-0000-0000-000000000001"
    publication = {
        "publicationId": publication_id,
        "state": "awaiting-upload",
        "targetStorageId": storage_id,
        "versionLabel": "v1",
        "formatIdentity": inspected.format_identity,
        "manifestIdentity": inspected.manifest_identity,
        "contentFingerprint": inspected.content_fingerprint,
        "objectCount": inspected.object_count,
        "byteCount": inspected.byte_count,
        "payloadLocation": "datasets/payload",
        "operationLocation": "operations/publication",
    }
    requests: list[tuple[str, str]] = []

    def request(_base: str, method: str, path: str, _body: object) -> object:
        requests.append((method, path))
        if path == f"/api/v1/dataset-publications/{publication_id}/resume":
            return publication
        if path == f"/api/v1/target-storages/{storage_id}":
            return {"storage": "descriptor"}
        if path.endswith("/completion"):
            return {**publication, "state": "committed"}
        raise AssertionError(path)

    stdout = StringIO()
    stderr = StringIO()
    with (
        patch("skywright._dataset_cli._request", side_effect=request),
        patch("skywright._dataset_cli._upload") as upload,
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
                storage_id,
                "--version-label",
                "v1",
                "--resume",
                publication_id,
            ]
        )

    assert status == 0
    assert json.loads(stdout.getvalue())["publicationId"] == publication_id
    identity = json.loads(stderr.getvalue().splitlines()[0])
    assert identity == {
        "event": "dataset-publication-identity",
        "publicationId": publication_id,
    }
    assert requests[0] == (
        "POST",
        f"/api/v1/dataset-publications/{publication_id}/resume",
    )
    upload.assert_called_once()


def test_command_reconciles_a_lost_completion_response_by_reading_the_operation(
    tmp_path: Path,
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    inspected = inspect_mds_corpus(corpus)
    publication_id = "00000000-0000-0000-0000-000000000010"
    storage_id = "00000000-0000-0000-0000-000000000001"
    publication = {
        "publicationId": publication_id,
        "state": "awaiting-upload",
        "targetStorageId": storage_id,
        "versionLabel": "v1",
        "formatIdentity": inspected.format_identity,
        "manifestIdentity": inspected.manifest_identity,
        "contentFingerprint": inspected.content_fingerprint,
        "objectCount": inspected.object_count,
        "byteCount": inspected.byte_count,
        "payloadLocation": "datasets/payload",
        "operationLocation": "operations/publication",
    }
    initiated = 0

    def request(_base: str, method: str, path: str, _body: object) -> object:
        nonlocal initiated
        if method == "POST" and path == "/api/v1/dataset-publications":
            initiated += 1
            return publication
        if method == "GET" and path == f"/api/v1/target-storages/{storage_id}":
            return {"storage": "descriptor"}
        if method == "POST" and path.endswith("/completion"):
            raise DatasetPublicationError(
                "CONTROL_PLANE_UNAVAILABLE",
                "The control plane is unavailable",
                retryable=True,
            )
        if method == "GET" and path == f"/api/v1/dataset-publications/{publication_id}":
            return {**publication, "state": "committed"}
        raise AssertionError(path)

    stdout = StringIO()
    with (
        patch("skywright._dataset_cli._request", side_effect=request),
        patch("skywright._dataset_cli._upload"),
        redirect_stdout(stdout),
    ):
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                storage_id,
                "--version-label",
                "v1",
            ]
        )

    assert status == 0
    assert initiated == 1
    assert json.loads(stdout.getvalue())["publicationId"] == publication_id


def test_command_rejects_an_existing_object_without_validated_sha256(
    tmp_path: Path,
) -> None:
    from botocore.exceptions import ClientError

    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    inspected = inspect_mds_corpus(corpus)
    publication_id = "00000000-0000-0000-0000-000000000010"
    storage_id = "00000000-0000-0000-0000-000000000001"
    publication = {
        "publicationId": publication_id,
        "state": "awaiting-upload",
        "targetStorageId": storage_id,
        "versionLabel": "v1",
        "formatIdentity": inspected.format_identity,
        "manifestIdentity": inspected.manifest_identity,
        "contentFingerprint": inspected.content_fingerprint,
        "objectCount": inspected.object_count,
        "byteCount": inspected.byte_count,
        "payloadLocation": "datasets/payload",
        "operationLocation": "operations/publication",
    }
    expected = {
        f"datasets/payload/{entry.object_key}": (
            entry.byte_count,
            entry.checksum_sha256.removeprefix("sha256:"),
        )
        for entry in inspected.entries
    }
    expected["operations/publication/manifest.json"] = (
        len(inspected.manifest_bytes),
        inspected.manifest_identity.removeprefix("sha256:"),
    )

    def request(_base: str, method: str, path: str, _body: object) -> object:
        if method == "POST" and path == "/api/v1/dataset-publications":
            return publication
        if method == "GET" and path == f"/api/v1/target-storages/{storage_id}":
            return {
                "bucket": "dataset",
                "configuration": {
                    "endpoint": "http://storage",
                    "region": "us-east-1",
                    "pathStyleAccess": True,
                },
            }
        if method == "PUT" and path.endswith("/failure"):
            return publication
        raise AssertionError(path)

    class Storage:
        def put_object(self, **_values: object) -> None:
            raise ClientError(
                {
                    "Error": {"Code": "PreconditionFailed"},
                    "ResponseMetadata": {"HTTPStatusCode": 412},
                },
                "PutObject",
            )

        def head_object(self, **values: object) -> dict[str, object]:
            byte_count, digest = expected[cast(str, values["Key"])]
            return {
                "ContentLength": byte_count,
                "Metadata": {"skywright-sha256": digest},
                "ChecksumSHA256": base64.b64encode(b"wrong digest evidence").decode(),
            }

        def get_object(self, **_values: object) -> dict[str, BytesIO]:
            return {"Body": BytesIO(b"different remote bytes")}

    stderr = StringIO()
    with (
        patch("skywright._dataset_cli._request", side_effect=request),
        patch("boto3.client", return_value=Storage()),
        redirect_stderr(stderr),
    ):
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                storage_id,
                "--version-label",
                "v1",
            ]
        )

    assert status == 1
    assert json.loads(stderr.getvalue().splitlines()[-1])["errorCode"] == (
        "SKYWRIGHT_DATASET_UPLOAD_CONFLICT"
    )


def test_command_aborts_a_known_multipart_upload_after_transfer_failure(
    tmp_path: Path,
) -> None:
    from botocore.exceptions import ClientError

    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    inspected = inspect_mds_corpus(corpus)
    publication_id = "00000000-0000-0000-0000-000000000010"
    storage_id = "00000000-0000-0000-0000-000000000001"
    publication = {
        "publicationId": publication_id,
        "state": "awaiting-upload",
        "targetStorageId": storage_id,
        "versionLabel": "v1",
        "formatIdentity": inspected.format_identity,
        "manifestIdentity": inspected.manifest_identity,
        "contentFingerprint": inspected.content_fingerprint,
        "objectCount": inspected.object_count,
        "byteCount": inspected.byte_count,
        "payloadLocation": "datasets/payload",
        "operationLocation": "operations/publication",
    }
    failures: list[object] = []

    def request(_base: str, method: str, path: str, body: object) -> object:
        if method == "POST" and path == "/api/v1/dataset-publications":
            return publication
        if method == "GET" and path == f"/api/v1/target-storages/{storage_id}":
            return {
                "bucket": "dataset",
                "configuration": {
                    "endpoint": "http://storage",
                    "region": "us-east-1",
                    "pathStyleAccess": True,
                },
            }
        if method == "PUT" and path.endswith("/failure"):
            failures.append(body)
            return publication
        raise AssertionError(path)

    created: list[str] = []
    aborted: list[str] = []

    class Storage:
        def list_multipart_uploads(self, **_values: object) -> dict[str, object]:
            return {
                "Uploads": [
                    {
                        "Key": "datasets/payload/index.json",
                        "UploadId": "abandoned-upload",
                    }
                ],
                "IsTruncated": False,
            }

        def head_object(self, **_values: object) -> None:
            raise ClientError(
                {
                    "Error": {"Code": "NotFound"},
                    "ResponseMetadata": {"HTTPStatusCode": 404},
                },
                "HeadObject",
            )

        def put_object(self, **_values: object) -> None:
            raise ClientError({"Error": {"Code": "Unavailable"}}, "PutObject")

        def create_multipart_upload(self, **_values: object) -> dict[str, str]:
            upload_id = f"known-upload-{len(created)}"
            created.append(upload_id)
            return {"UploadId": upload_id}

        def upload_part(self, **_values: object) -> None:
            raise ClientError({"Error": {"Code": "Unavailable"}}, "UploadPart")

        def abort_multipart_upload(self, **values: object) -> None:
            aborted.append(cast(str, values["UploadId"]))

    stderr = StringIO()
    with (
        patch("skywright._dataset_cli._request", side_effect=request),
        patch("skywright._dataset_upload._MULTIPART_THRESHOLD_BYTES", 1),
        patch("boto3.client", return_value=Storage()),
        redirect_stderr(stderr),
    ):
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                storage_id,
                "--version-label",
                "v1",
            ]
        )

    assert status == 75
    assert created
    assert sorted(aborted) == sorted([*created, "abandoned-upload"])
    assert failures == [{"failureCode": "DATASET_UPLOAD_FAILED"}]
    assert json.loads(stderr.getvalue().splitlines()[-1])["errorCode"] == (
        "SKYWRIGHT_DATASET_UPLOAD_FAILED"
    )


def test_command_requires_one_preferred_definition_choice_for_existing_dataset(
    tmp_path: Path,
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    stderr = StringIO()

    with redirect_stderr(stderr):
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "http://control-plane",
                "--target-storage",
                "00000000-0000-0000-0000-000000000001",
                "--dataset",
                "00000000-0000-0000-0000-000000000002",
                "--expected-dataset-revision",
                "1",
            ]
        )

    assert status == 2
    assert "DATASET_PREFERRED_DEFINITION_DECISION_REQUIRED" in stderr.getvalue()


@pytest.mark.parametrize(
    "mutation",
    [
        "replacement",
        "equal-length",
        "truncation",
        "deletion",
        "symlink",
        "directory",
        "addition",
    ],
)
def test_command_rejects_every_source_change_after_scan(
    tmp_path: Path, mutation: str
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    inspected = inspect_mds_corpus(corpus)
    shard = corpus / "shard.00000.mds"
    original = shard.read_bytes()
    calls = 0

    def request(*_args: object) -> object:
        nonlocal calls
        calls += 1
        if calls == 1:
            initiation = cast(dict[str, object], _args[3])
            assert "datasetId" not in initiation
            assert "expectedDatasetRevision" not in initiation
            assert "preferredDefinitionDecision" not in initiation
            if mutation == "replacement":
                shard.unlink()
                shard.write_bytes(original)
            elif mutation == "equal-length":
                shard.write_bytes(bytes([original[0] ^ 1]) + original[1:])
            elif mutation == "truncation":
                shard.write_bytes(original[:-1])
            elif mutation == "deletion":
                shard.unlink()
            elif mutation == "symlink":
                outside = tmp_path / "outside.mds"
                outside.write_bytes(original)
                shard.unlink()
                shard.symlink_to(outside)
            elif mutation == "addition":
                (corpus / "new-unreferenced.bin").write_bytes(b"added after scan")
            else:
                shard.unlink()
                shard.mkdir()
            return {
                "publicationId": "00000000-0000-0000-0000-000000000010",
                "targetStorageId": "00000000-0000-0000-0000-000000000001",
                "versionLabel": inspected.content_fingerprint[7:23],
                "formatIdentity": inspected.format_identity,
                "manifestIdentity": inspected.manifest_identity,
                "contentFingerprint": inspected.content_fingerprint,
                "objectCount": inspected.object_count,
                "byteCount": inspected.byte_count,
                "payloadLocation": "datasets/payload",
                "operationLocation": "operations/publication",
            }
        return {
            "bucket": "dataset",
            "configuration": {
                "endpoint": "http://storage",
                "region": "us-east-1",
                "pathStyleAccess": True,
            },
        }

    class Storage:
        def put_object(self, **values: object) -> None:
            body = values["Body"]
            body.read()  # type: ignore[union-attr]

    stderr = StringIO()
    with (
        patch("skywright._dataset_cli._request", side_effect=request),
        patch("boto3.client", return_value=Storage()),
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
            ]
        )

    assert status == 1
    assert json.loads(stderr.getvalue().splitlines()[-1])["errorCode"] == (
        "SKYWRIGHT_DATASET_SOURCE_MUTATED"
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


def test_command_reports_invalid_control_plane_as_one_problem_line(
    tmp_path: Path,
) -> None:
    corpus = tmp_path / "corpus"
    write_corpus(corpus)
    stdout = StringIO()
    stderr = StringIO()

    with redirect_stdout(stdout), redirect_stderr(stderr):
        status = main(
            [
                "publish",
                str(corpus),
                "--control-plane",
                "::bad",
                "--target-storage",
                "00000000-0000-0000-0000-000000000001",
                "--version-label",
                "v1",
            ]
        )

    assert status == 2
    assert stdout.getvalue() == ""
    assert stderr.getvalue().count("\n") == 1
    assert "Traceback" not in stderr.getvalue()
    assert json.loads(stderr.getvalue())["errorCode"] == (
        "SKYWRIGHT_CONTROL_PLANE_ADDRESS_INVALID"
    )
