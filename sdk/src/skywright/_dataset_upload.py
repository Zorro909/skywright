"""Direct object-storage transfer for Dataset Publication."""

# boto3 deliberately exposes a runtime-shaped client. Keep Unknown contained at
# this private storage seam while preserving strict checks elsewhere.
# pyright: reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false

from __future__ import annotations

import base64
import hashlib
import os
import stat
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from contextlib import suppress
from typing import Any, BinaryIO, cast

from skywright._dataset_errors import DatasetPublicationError
from skywright._dataset_publication import (
    InspectedCorpus,
    ManifestEntry,
    SourceIdentity,
    verify_source_inventory,
)

_MULTIPART_THRESHOLD_BYTES = 64 * 1024 * 1024
_MULTIPART_PART_BYTES = 64 * 1024 * 1024


def upload(
    corpus: InspectedCorpus,
    publication: dict[str, object],
    storage: dict[str, object],
    concurrency: int,
    progress: Callable[[int], None],
    active: Callable[[], None],
) -> None:
    """Transfer and validate one publication through its allocated locations."""
    import boto3
    from botocore.exceptions import (
        BotoCoreError,
        ClientError,
        NoCredentialsError,
        PartialCredentialsError,
    )

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

    try:
        client: Any = boto3.client(
            "s3",
            endpoint_url=endpoint,
            region_name=region,
            config=Config(
                s3={"addressing_style": "path" if path_style is True else "virtual"}
            ),
        )
        active()
        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            uploads = [
                executor.submit(
                    _put_source,
                    client,
                    bucket,
                    f"{payload_location}/{entry.object_key}",
                    entry,
                    active,
                )
                for entry in corpus.entries
            ]
            for transfer in uploads:
                progress(transfer.result())
        verify_source_inventory(corpus)
        active()
        _put_bytes(
            client,
            bucket,
            f"{operation_location}/manifest.json",
            corpus.manifest_bytes,
            corpus.manifest_identity,
            active,
        )
    except DatasetPublicationError:
        raise
    except (NoCredentialsError, PartialCredentialsError) as error:
        raise DatasetPublicationError(
            "DATASET_LOCAL_CREDENTIAL_UNAVAILABLE",
            "Local object-storage credentials are unavailable",
            retryable=True,
        ) from error
    except (BotoCoreError, ClientError, OSError) as error:
        raise DatasetPublicationError(
            "DATASET_UPLOAD_FAILED",
            "The direct Dataset upload failed",
            retryable=True,
        ) from error


def _put_source(
    client: Any,
    bucket: str,
    key: str,
    entry: ManifestEntry,
    active: Callable[[], None],
) -> int:
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
            active()
            _put_or_verify(
                client,
                bucket,
                key,
                stream,
                entry.byte_count,
                digest,
                checksum,
                active,
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
            return entry.byte_count
    except DatasetPublicationError:
        raise
    except OSError as error:
        raise _source_mutated() from error
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _put_bytes(
    client: Any,
    bucket: str,
    key: str,
    body: bytes,
    identity: str,
    active: Callable[[], None] = lambda: None,
) -> None:
    from io import BytesIO

    digest = identity.removeprefix("sha256:")
    checksum = base64.b64encode(bytes.fromhex(digest)).decode()
    active()
    _put_or_verify(
        client, bucket, key, BytesIO(body), len(body), digest, checksum, active
    )


def _put_or_verify(
    client: Any,
    bucket: str,
    key: str,
    body: BinaryIO,
    byte_count: int,
    digest: str,
    checksum: str,
    active: Callable[[], None],
) -> None:
    from botocore.exceptions import ClientError

    if byte_count >= _MULTIPART_THRESHOLD_BYTES:
        _put_multipart_or_verify(
            client, bucket, key, body, byte_count, digest, checksum, active
        )
        return
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
        active()
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
        or not _validated_remote_digest(client, bucket, key, head, digest, checksum)
    ):
        raise DatasetPublicationError(
            "DATASET_UPLOAD_CONFLICT",
            "An allocated Dataset object already contains different bytes",
        )


def _put_multipart_or_verify(
    client: Any,
    bucket: str,
    key: str,
    body: BinaryIO,
    byte_count: int,
    digest: str,
    checksum: str,
    active: Callable[[], None],
) -> None:
    from botocore.exceptions import BotoCoreError, ClientError

    _abort_stale_multipart_uploads(client, bucket, key)
    active()
    if _matches_existing(client, bucket, key, byte_count, digest, checksum):
        return
    created = client.create_multipart_upload(
        Bucket=bucket,
        Key=key,
        ChecksumAlgorithm="SHA256",
        Metadata={"skywright-sha256": digest},
    )
    upload_id = created.get("UploadId")
    if not isinstance(upload_id, str):
        raise DatasetPublicationError(
            "DATASET_UPLOAD_FAILED",
            "The storage service did not allocate a multipart upload identity",
            retryable=True,
        )
    parts: list[dict[str, object]] = []
    completed = False
    try:
        part_number = 1
        while chunk := body.read(_MULTIPART_PART_BYTES):
            active()
            part_checksum = base64.b64encode(hashlib.sha256(chunk).digest()).decode()
            transferred = client.upload_part(
                Bucket=bucket,
                Key=key,
                UploadId=upload_id,
                PartNumber=part_number,
                Body=chunk,
                ContentLength=len(chunk),
                ChecksumSHA256=part_checksum,
            )
            part: dict[str, object] = {
                "PartNumber": part_number,
                "ETag": transferred["ETag"],
            }
            if isinstance(transferred.get("ChecksumSHA256"), str):
                part["ChecksumSHA256"] = transferred["ChecksumSHA256"]
            parts.append(part)
            part_number += 1
        try:
            active()
            client.complete_multipart_upload(
                Bucket=bucket,
                Key=key,
                UploadId=upload_id,
                MultipartUpload={"Parts": parts},
                IfNoneMatch="*",
            )
        except (BotoCoreError, ClientError, OSError):
            if _matches_existing(client, bucket, key, byte_count, digest, checksum):
                completed = True
                return
            raise
        if not _matches_existing(client, bucket, key, byte_count, digest, checksum):
            raise DatasetPublicationError(
                "DATASET_UPLOAD_CONFLICT",
                "The completed Dataset object lacks validated SHA-256 evidence",
            )
        active()
        completed = True
    finally:
        if not completed:
            with suppress(BotoCoreError, ClientError, OSError):
                client.abort_multipart_upload(
                    Bucket=bucket,
                    Key=key,
                    UploadId=upload_id,
                )


def _abort_stale_multipart_uploads(client: Any, bucket: str, key: str) -> None:
    request: dict[str, object] = {"Bucket": bucket, "Prefix": key}
    while True:
        response = client.list_multipart_uploads(**request)
        for stale_upload in response.get("Uploads", []):
            if stale_upload.get("Key") == key and isinstance(
                stale_upload.get("UploadId"), str
            ):
                client.abort_multipart_upload(
                    Bucket=bucket,
                    Key=key,
                    UploadId=stale_upload["UploadId"],
                )
        if response.get("IsTruncated") is not True:
            return
        key_marker = response.get("NextKeyMarker")
        upload_marker = response.get("NextUploadIdMarker")
        if not isinstance(key_marker, str) or not isinstance(upload_marker, str):
            raise DatasetPublicationError(
                "DATASET_UPLOAD_FAILED",
                "The storage service returned invalid multipart inventory pagination",
                retryable=True,
            )
        request["KeyMarker"] = key_marker
        request["UploadIdMarker"] = upload_marker


def _matches_existing(
    client: Any,
    bucket: str,
    key: str,
    byte_count: int,
    digest: str,
    checksum: str,
) -> bool:
    from botocore.exceptions import ClientError

    try:
        head = client.head_object(Bucket=bucket, Key=key, ChecksumMode="ENABLED")
    except ClientError as error:
        status = error.response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        if status == 404:
            return False
        raise
    metadata = head.get("Metadata", {})
    if (
        head.get("ContentLength") == byte_count
        and metadata.get("skywright-sha256") == digest
        and _validated_remote_digest(client, bucket, key, head, digest, checksum)
    ):
        return True
    raise DatasetPublicationError(
        "DATASET_UPLOAD_CONFLICT",
        "An allocated Dataset object already contains different bytes",
    )


def _validated_remote_digest(
    client: Any,
    bucket: str,
    key: str,
    head: dict[str, object],
    digest: str,
    checksum: str,
) -> bool:
    if head.get("ChecksumSHA256") == checksum:
        return True
    response = client.get_object(Bucket=bucket, Key=key, ChecksumMode="ENABLED")
    stream = response.get("Body")
    if stream is None or not hasattr(stream, "read"):
        return False
    try:
        return _stream_digest(cast(BinaryIO, stream)) == digest
    finally:
        close = getattr(stream, "close", None)
        if callable(close):
            close()


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


def _protocol_error() -> DatasetPublicationError:
    return DatasetPublicationError(
        "CONTROL_PLANE_PROTOCOL_FAILURE",
        "The control plane returned an invalid Dataset Publication response",
    )
