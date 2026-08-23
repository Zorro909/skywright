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
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Sequence
from pathlib import Path
from typing import Any, BinaryIO, cast

from skywright._dataset_publication import (
    DatasetPublicationError,
    InspectedCorpus,
    ManifestEntry,
    inspect_mds_corpus,
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
    publish.add_argument("--version-label", required=True)
    parsed = parser.parse_args(arguments)
    try:
        if parsed.operation == "publish":
            result = _publish(
                parsed.corpus,
                parsed.control_plane,
                parsed.target_storage,
                parsed.version_label,
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
    corpus_path: Path, control_plane: str, target_storage_id: str, version_label: str
) -> object:
    if not version_label or len(version_label) > 255:
        raise DatasetPublicationError(
            "DATASET_VERSION_LABEL_INVALID",
            "The version label must contain between 1 and 255 characters",
        )
    corpus = inspect_mds_corpus(corpus_path)
    publication = _request(
        control_plane,
        "POST",
        "/api/v1/dataset-publications",
        {
            "targetStorageId": target_storage_id,
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
    _upload(corpus, publication, storage)
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
        for entry in corpus.entries:
            _put_source(client, bucket, f"{payload_location}/{entry.object_key}", entry)
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
    before = entry.source.stat(follow_symlinks=False)
    digest = entry.checksum_sha256.removeprefix("sha256:")
    checksum = base64.b64encode(bytes.fromhex(digest)).decode()
    with entry.source.open("rb") as stream:
        _put_or_verify(client, bucket, key, stream, entry.byte_count, digest, checksum)
    after = entry.source.stat(follow_symlinks=False)
    if (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
    ) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
        raise DatasetPublicationError(
            "DATASET_SOURCE_MUTATED",
            "A local corpus file changed during publication",
        )
    observed = _file_digest(entry.source)
    if observed != digest:
        raise DatasetPublicationError(
            "DATASET_SOURCE_MUTATED",
            "A local corpus file changed during publication",
        )


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


def _file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


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
    if error.code == "DATASET_CORPUS_INVALID" or error.code.endswith("_INVALID"):
        return 2
    if error.retryable:
        return 75
    return 1


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
