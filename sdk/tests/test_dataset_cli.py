from __future__ import annotations

import json
from pathlib import Path

import pytest

from skywright._dataset_publication import DatasetPublicationError, inspect_mds_corpus


def write_corpus(root: Path, *, encoding: str = "bytes") -> None:
    shard = b"one stable shard\n"
    root.mkdir()
    (root / "shard.00000.mds").write_bytes(shard)
    (root / "index.json").write_text(
        json.dumps(
            {
                "version": 2,
                "shards": [
                    {
                        "column_encodings": [encoding],
                        "column_names": ["value"],
                        "column_sizes": [None],
                        "compression": None,
                        "format": "mds",
                        "hashes": [],
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
    assert inspected.byte_count == 288
    assert inspected.manifest_identity == (
        "sha256:81a3acbabf7bdc340bb7ef49fc013dbe693056713c548338200b155b3a080df5"
    )
    assert inspected.content_fingerprint == (
        "sha256:26f437dff89ccab4a77c80923227b5a893801ae0e3260eaaa278ca3601d963aa"
    )


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
