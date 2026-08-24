"""Installed source-side command for Dataset Publication."""

# boto3 deliberately exposes a runtime-shaped client. Keep Unknown contained at
# this private storage boundary while preserving strict checks elsewhere.
# pyright: reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import stat
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Sequence
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, BinaryIO, cast

from skywright._dataset_errors import DatasetPublicationError
from skywright._dataset_publication import (
    InspectedCorpus,
    ManifestEntry,
    SourceIdentity,
    inspect_mds_corpus,
    verify_source_inventory,
)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="skywright-datasets",
        description="Publish storage-ready Dataset corpora.",
    )
    commands = parser.add_subparsers(dest="operation", required=True)
    publish = commands.add_parser(
        "publish", help="Publish one storage-ready MosaicML Streaming MDS corpus"
    )
    publish.add_argument("corpus", type=Path)
    publish.add_argument("--control-plane", required=True)
    publish.add_argument("--target-storage", required=True)
    publish.add_argument("--dataset")
    publish.add_argument("--expected-dataset-revision", type=_positive_int)
    preferred = publish.add_mutually_exclusive_group()
    preferred.add_argument(
        "--advance-preferred",
        dest="preferred_definition_decision",
        action="store_const",
        const="advance",
    )
    preferred.add_argument(
        "--keep-preferred",
        dest="preferred_definition_decision",
        action="store_const",
        const="keep",
    )
    publish.add_argument("--version-label")
    publish.add_argument("--concurrency", type=_positive_int, default=4)
    parsed = parser.parse_args(arguments)
    try:
        if parsed.operation == "publish":
            result = _publish(
                parsed.corpus,
                parsed.control_plane,
                parsed.target_storage,
                parsed.version_label,
                parsed.concurrency,
                parsed.dataset,
                parsed.expected_dataset_revision,
                parsed.preferred_definition_decision,
            )
            print(_json(result))
            return 0
    except DatasetPublicationError as error:
        print(_json(_problem(error)), file=sys.stderr)
        return _exit_code(error)
    except OSError:
        error = DatasetPublicationError(
            "DATASET_LOCAL_IO_FAILURE",
            "The local Dataset corpus could not be read",
        )
        print(_json(_problem(error)), file=sys.stderr)
        return 1
    return 64


def _publish(
    corpus_path: Path,
    control_plane: str,
    target_storage_id: str,
    version_label: str | None,
    concurrency: int,
    dataset_id: str | None = None,
    expected_dataset_revision: int | None = None,
    preferred_definition_decision: str | None = None,
) -> object:
    existing_values = (
        dataset_id,
        expected_dataset_revision,
        preferred_definition_decision,
    )
    if any(value is not None for value in existing_values) and not all(
        value is not None for value in existing_values
    ):
        raise DatasetPublicationError(
            "DATASET_PREFERRED_DEFINITION_DECISION_REQUIRED",
            "An existing Dataset requires its identity, current revision, and one explicit preferred-definition decision",
        )
    if version_label is not None and (not version_label or len(version_label) > 255):
        raise DatasetPublicationError(
            "DATASET_VERSION_LABEL_INVALID",
            "The version label must contain between 1 and 255 characters",
        )
    corpus = inspect_mds_corpus(corpus_path, concurrency=concurrency)
    publication = _request(
        control_plane,
        "POST",
        "/api/v1/dataset-publications",
        {
            "targetStorageId": target_storage_id,
            "datasetId": dataset_id,
            "expectedDatasetRevision": expected_dataset_revision,
            "preferredDefinitionDecision": preferred_definition_decision,
            "versionLabel": version_label,
            "formatIdentity": corpus.format_identity,
            "manifestIdentity": corpus.manifest_identity,
            "contentFingerprint": corpus.content_fingerprint,
            "objectCount": corpus.object_count,
            "byteCount": corpus.byte_count,
        },
    )
    if not isinstance(publication, dict):
        raise _protocol_error()
    publication = cast(dict[str, object], publication)
    storage = _request(
        control_plane,
        "GET",
        f"/api/v1/target-storages/{publication.get('targetStorageId')}",
        None,
    )
    if not isinstance(storage, dict):
        raise _protocol_error()
    storage = cast(dict[str, object], storage)
    _upload(corpus, publication, storage, concurrency)
    result = _request(
        control_plane,
        "POST",
        f"/api/v1/dataset-publications/{publication.get('publicationId')}/completion",
        {},
    )
    if not isinstance(result, dict):
        raise _protocol_error()
    result = cast(dict[str, object], result)
    while result.get("state") in {"awaiting-upload", "verifying"}:
        time.sleep(0.1)
        result = _request(
            control_plane,
            "GET",
            f"/api/v1/dataset-publications/{publication.get('publicationId')}",
            None,
        )
        if not isinstance(result, dict):
            raise _protocol_error()
        result = cast(dict[str, object], result)
    if result.get("state") == "failed":
        code = result.get("failureCode")
        if not isinstance(code, str):
            raise _protocol_error()
        raise DatasetPublicationError(
            code,
            "Independent Dataset verification failed",
            retryable=result.get("retryable") is True,
        )
    if result.get("state") != "committed":
        raise _protocol_error()
    return result


def _request(base: str, method: str, path: str, body: object | None) -> object:
    url = base.rstrip("/") + path
    data = None if body is None else _json(body).encode()
    try:
        request = urllib.request.Request(url, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(request, timeout=30) as response:
            try:
                return cast(object, json.load(response))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise _protocol_error() from error
    except urllib.error.HTTPError as error:
        try:
            problem = cast(object, json.loads(error.read()))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise DatasetPublicationError(
                "CONTROL_PLANE_PROTOCOL_FAILURE",
                "The control plane returned an invalid error response",
                retryable=error.code >= 500,
            ) from None
        if isinstance(problem, dict):
            problem = cast(dict[str, object], problem)
            code = problem.get("errorCode")
            detail = problem.get("detail")
            retryable = problem.get("retryable")
            if isinstance(code, str) and isinstance(detail, str):
                raise DatasetPublicationError(
                    code.removeprefix("SKYWRIGHT_"),
                    detail,
                    retryable=retryable is True,
                ) from None
        raise _protocol_error() from None
    except (OSError, urllib.error.URLError) as error:
        raise DatasetPublicationError(
            "CONTROL_PLANE_UNAVAILABLE",
            "The control plane is unavailable",
            retryable=True,
        ) from error
    except ValueError as error:
        raise DatasetPublicationError(
            "CONTROL_PLANE_ADDRESS_INVALID",
            "The control-plane address is invalid",
        ) from error


def _upload(
    corpus: InspectedCorpus,
    publication: dict[str, object],
    storage: dict[str, object],
    concurrency: int,
) -> None:
    import boto3
    from botocore.exceptions import BotoCoreError, ClientError

    configuration = storage.get("configuration")
    bucket = storage.get("bucket")
    payload_location = publication.get("payloadLocation")
    operation_location = publication.get("operationLocation")
    if (
        not isinstance(configuration, dict)
        or not isinstance(bucket, str)
        or not isinstance(payload_location, str)
        or not isinstance(operation_location, str)
    ):
        raise _protocol_error()
    typed_configuration = cast(dict[str, object], configuration)
    endpoint = typed_configuration.get("endpoint")
    region = typed_configuration.get("region")
    path_style = typed_configuration.get("pathStyleAccess")
    if not isinstance(endpoint, str) or not isinstance(region, str):
        raise _protocol_error()
    from botocore.config import Config

    client: Any = boto3.client(
        "s3",
        endpoint_url=endpoint,
        region_name=region,
        config=Config(
            s3={"addressing_style": "path" if path_style is True else "virtual"}
        ),
    )
    try:
        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            uploads = [
                executor.submit(
                    _put_source,
                    client,
                    bucket,
                    f"{payload_location}/{entry.object_key}",
                    entry,
                )
                for entry in corpus.entries
            ]
            for upload in uploads:
                upload.result()
        verify_source_inventory(corpus)
        _put_bytes(
            client,
            bucket,
            f"{operation_location}/manifest.json",
            corpus.manifest_bytes,
            corpus.manifest_identity,
        )
    except DatasetPublicationError:
        raise
    except (BotoCoreError, ClientError, OSError) as error:
        raise DatasetPublicationError(
            "DATASET_UPLOAD_FAILED",
            "The direct Dataset upload failed",
            retryable=True,
        ) from error


def _put_source(client: Any, bucket: str, key: str, entry: ManifestEntry) -> None:
    digest = entry.checksum_sha256.removeprefix("sha256:")
    checksum = base64.b64encode(bytes.fromhex(digest)).decode()
    descriptor: int | None = None
    try:
        descriptor = os.open(entry.source, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "rb", closefd=True) as stream:
            descriptor = None
            before = SourceIdentity.from_stat(os.fstat(stream.fileno()))
            if (
                before != entry.source_identity
                or not stat.S_ISREG(before.mode)
                or _stream_digest(stream) != digest
            ):
                raise _source_mutated()
            stream.seek(0)
            _put_or_verify(
                client, bucket, key, stream, entry.byte_count, digest, checksum
            )
            stream.seek(0)
            after = SourceIdentity.from_stat(os.fstat(stream.fileno()))
            path_after = SourceIdentity.from_stat(entry.source.lstat())
            if (
                after != entry.source_identity
                or path_after != entry.source_identity
                or _stream_digest(stream) != digest
            ):
                raise _source_mutated()
    except DatasetPublicationError:
        raise
    except OSError as error:
        raise _source_mutated() from error
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _put_bytes(client: Any, bucket: str, key: str, body: bytes, identity: str) -> None:
    from io import BytesIO

    digest = identity.removeprefix("sha256:")
    checksum = base64.b64encode(bytes.fromhex(digest)).decode()
    _put_or_verify(client, bucket, key, BytesIO(body), len(body), digest, checksum)


def _put_or_verify(
    client: Any,
    bucket: str,
    key: str,
    body: BinaryIO,
    byte_count: int,
    digest: str,
    checksum: str,
) -> None:
    from botocore.exceptions import ClientError

    try:
        client.put_object(
            Bucket=bucket,
            Key=key,
            Body=body,
            ContentLength=byte_count,
            ChecksumSHA256=checksum,
            IfNoneMatch="*",
            Metadata={"skywright-sha256": digest},
        )
        return
    except ClientError as error:
        status = error.response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        if status not in {409, 412}:
            raise
    head = client.head_object(Bucket=bucket, Key=key, ChecksumMode="ENABLED")
    metadata = head.get("Metadata", {})
    if (
        head.get("ContentLength") != byte_count
        or metadata.get("skywright-sha256") != digest
    ):
        raise DatasetPublicationError(
            "DATASET_UPLOAD_CONFLICT",
            "An allocated Dataset object already contains different bytes",
        )


def _stream_digest(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
    return digest.hexdigest()


def _source_mutated() -> DatasetPublicationError:
    return DatasetPublicationError(
        "DATASET_SOURCE_MUTATED",
        "A local corpus file changed during publication",
    )


def _problem(error: DatasetPublicationError) -> dict[str, object]:
    return {
        "type": "about:blank",
        "title": "Dataset publication failed",
        "status": 503 if error.retryable else 422,
        "detail": error.detail,
        "instance": "skywright-datasets:publish",
        "errorCode": f"SKYWRIGHT_{error.code}",
        "correlationId": "local",
        "fieldViolations": [],
        "unavailableSource": None,
        "retryable": error.retryable,
    }


def _protocol_error() -> DatasetPublicationError:
    return DatasetPublicationError(
        "CONTROL_PLANE_PROTOCOL_FAILURE",
        "The control plane returned an invalid Dataset Publication response",
    )


def _exit_code(error: DatasetPublicationError) -> int:
    if (
        error.code.startswith("DATASET_CORPUS_")
        or error.code.startswith("DATASET_MDS_")
        or error.code == "DATASET_STREAMING_FORMAT_UNSUPPORTED"
        or error.code == "DATASET_PREFERRED_DEFINITION_DECISION_REQUIRED"
        or error.code.endswith("_INVALID")
    ):
        return 2
    if error.retryable:
        return 75
    return 1


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return parsed
