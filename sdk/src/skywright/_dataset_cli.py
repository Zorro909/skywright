"""Installed source-side command for Dataset Publication."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Sequence
from contextlib import suppress
from pathlib import Path
from typing import cast

from skywright._dataset_errors import DatasetPublicationError
from skywright._dataset_publication import inspect_mds_corpus
from skywright._dataset_upload import upload as _upload


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
    publish.add_argument("--resume", metavar="PUBLICATION_ID")
    abort = commands.add_parser(
        "abort", help="Abort one uncommitted Dataset Publication and verify cleanup"
    )
    abort.add_argument("publication_id", metavar="PUBLICATION_ID")
    abort.add_argument("--control-plane", required=True)
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
                parsed.resume,
            )
            print(_json(result))
            return 0
        if parsed.operation == "abort":
            print(_json(_abort(parsed.control_plane, parsed.publication_id)))
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


def _abort(control_plane: str, publication_id: str) -> object:
    result = _request(
        control_plane,
        "POST",
        f"/api/v1/dataset-publications/{publication_id}/abort",
        {},
    )
    while True:
        if not isinstance(result, dict):
            raise _protocol_error()
        typed_result = cast(dict[str, object], result)
        if typed_result.get("state") != "aborting":
            break
        time.sleep(0.1)
        result = _request(
            control_plane,
            "GET",
            f"/api/v1/dataset-publications/{publication_id}",
            None,
        )
    if typed_result.get("state") == "failed-cleanup":
        raise _publication_failure(typed_result)
    if typed_result.get("state") != "aborted":
        raise _protocol_error()
    return typed_result


def _publish(
    corpus_path: Path,
    control_plane: str,
    target_storage_id: str,
    version_label: str | None,
    concurrency: int,
    dataset_id: str | None = None,
    expected_dataset_revision: int | None = None,
    preferred_definition_decision: str | None = None,
    resume_publication_id: str | None = None,
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
    requested: dict[str, object] = {
        "targetStorageId": target_storage_id,
        "versionLabel": version_label,
        "formatIdentity": corpus.format_identity,
        "manifestIdentity": corpus.manifest_identity,
        "contentFingerprint": corpus.content_fingerprint,
        "objectCount": corpus.object_count,
        "byteCount": corpus.byte_count,
    }
    if dataset_id is not None:
        requested.update(
            {
                "datasetId": dataset_id,
                "expectedDatasetRevision": expected_dataset_revision,
                "preferredDefinitionDecision": preferred_definition_decision,
            }
        )
    if resume_publication_id is None:
        publication = _request(
            control_plane,
            "POST",
            "/api/v1/dataset-publications",
            requested,
        )
    else:
        publication = _request(
            control_plane,
            "POST",
            f"/api/v1/dataset-publications/{resume_publication_id}/resume",
            requested,
        )
    if not isinstance(publication, dict):
        raise _protocol_error()
    publication = cast(dict[str, object], publication)
    _validate_publication_identity(publication, requested, resume_publication_id)
    publication_id = publication.get("publicationId")
    if not isinstance(publication_id, str):
        raise _protocol_error()
    print(
        _json(
            {
                "event": "dataset-publication-identity",
                "publicationId": publication_id,
            }
        ),
        file=sys.stderr,
        flush=True,
    )
    if publication.get("state") == "committed":
        return publication
    if publication.get("state") == "failed-cleanup":
        if publication.get("preferredDefinitionId") is not None:
            return publication
        raise _publication_failure(publication)
    if (
        publication.get("state") == "failed"
        and publication.get("retryable") is not True
    ):
        raise _publication_failure(publication)
    if publication.get("state") in {
        "verifying",
        "committing",
        "published-cleanup-pending",
    }:
        result: object = publication
    else:
        storage = _request(
            control_plane,
            "GET",
            f"/api/v1/target-storages/{publication.get('targetStorageId')}",
            None,
        )
        if not isinstance(storage, dict):
            raise _protocol_error()
        storage = cast(dict[str, object], storage)
        uploaded_objects = 0
        uploaded_bytes = 0

        def progress(byte_count: int) -> None:
            nonlocal uploaded_objects, uploaded_bytes
            uploaded_objects += 1
            uploaded_bytes += byte_count
            _request(
                control_plane,
                "PUT",
                f"/api/v1/dataset-publications/{publication_id}/progress",
                {
                    "uploadedObjectCount": uploaded_objects,
                    "uploadedByteCount": uploaded_bytes,
                },
            )

        def ensure_upload_active() -> None:
            current = _request(
                control_plane,
                "GET",
                f"/api/v1/dataset-publications/{publication_id}",
                None,
            )
            if not isinstance(current, dict):
                raise _protocol_error()
            typed_current = cast(dict[str, object], current)
            state = typed_current.get("state")
            if state in {"awaiting-upload", "uploading"}:
                return
            if state in {"aborting", "aborted"} or (
                state == "failed-cleanup"
                and typed_current.get("preferredDefinitionId") is None
            ):
                raise DatasetPublicationError(
                    "DATASET_PUBLICATION_ABORTED",
                    "The Dataset Publication was aborted while transfer was active",
                )
            raise DatasetPublicationError(
                "DATASET_PUBLICATION_FENCED",
                "The Dataset Publication no longer accepts transfer work",
            )

        try:
            _upload(
                corpus,
                publication,
                storage,
                concurrency,
                progress,
                ensure_upload_active,
            )
        except DatasetPublicationError as error:
            _record_failure(control_plane, publication_id, error)
            raise
        try:
            result = _request(
                control_plane,
                "POST",
                f"/api/v1/dataset-publications/{publication_id}/completion",
                {},
            )
        except DatasetPublicationError as error:
            if not error.retryable:
                _record_failure(control_plane, publication_id, error)
                raise
            result = _request(
                control_plane,
                "GET",
                f"/api/v1/dataset-publications/{publication_id}",
                None,
            )
            result_state = (
                cast(dict[str, object], result).get("state")
                if isinstance(result, dict)
                else None
            )
            if result_state in {"awaiting-upload", "uploading"}:
                ambiguous = DatasetPublicationError(
                    "DATASET_COMMIT_RESPONSE_AMBIGUOUS",
                    "The completion request did not commit and can be retried",
                    retryable=True,
                )
                _record_failure(control_plane, publication_id, ambiguous)
                raise ambiguous from error
    if not isinstance(result, dict):
        raise _protocol_error()
    result = cast(dict[str, object], result)
    while result.get("state") in {
        "awaiting-upload",
        "uploading",
        "verifying",
        "committing",
        "published-cleanup-pending",
    }:
        time.sleep(0.1)
        result = _request(
            control_plane,
            "GET",
            f"/api/v1/dataset-publications/{publication_id}",
            None,
        )
        if not isinstance(result, dict):
            raise _protocol_error()
        result = cast(dict[str, object], result)
    if (
        result.get("state") == "failed-cleanup"
        and result.get("preferredDefinitionId") is not None
    ):
        return result
    if result.get("state") in {"failed", "failed-cleanup"}:
        raise _publication_failure(result)
    if result.get("state") != "committed":
        raise _protocol_error()
    return result


def _publication_failure(publication: dict[str, object]) -> DatasetPublicationError:
    code = publication.get("failureCode")
    detail = publication.get("failureDetail")
    if not isinstance(code, str):
        return _protocol_error()
    return DatasetPublicationError(
        code,
        detail if isinstance(detail, str) else "Dataset Publication failed",
        retryable=publication.get("retryable") is True,
    )


def _record_failure(
    control_plane: str, publication_id: str, error: DatasetPublicationError
) -> None:
    with suppress(DatasetPublicationError):
        _request(
            control_plane,
            "PUT",
            f"/api/v1/dataset-publications/{publication_id}/failure",
            {"failureCode": error.code},
        )


def _validate_publication_identity(
    publication: dict[str, object],
    requested: dict[str, object],
    resume_publication_id: str | None,
) -> None:
    publication_id = publication.get("publicationId")
    if not isinstance(publication_id, str) or (
        resume_publication_id is not None and publication_id != resume_publication_id
    ):
        raise _protocol_error()
    for field in (
        "targetStorageId",
        "expectedDatasetRevision",
        "preferredDefinitionDecision",
        "formatIdentity",
        "manifestIdentity",
        "contentFingerprint",
        "objectCount",
        "byteCount",
    ):
        if publication.get(field) != requested.get(field):
            raise DatasetPublicationError(
                "DATASET_PUBLICATION_CONFLICT",
                "The Dataset Publication does not match the requested immutable facts",
            )
    if (
        "datasetId" in requested
        and publication.get("datasetId") != requested["datasetId"]
    ):
        raise DatasetPublicationError(
            "DATASET_PUBLICATION_CONFLICT",
            "The Dataset Publication does not match the requested immutable facts",
        )
    requested_label = requested["versionLabel"]
    effective_label = publication.get("versionLabel")
    if requested_label is not None and effective_label != requested_label:
        raise DatasetPublicationError(
            "DATASET_PUBLICATION_CONFLICT",
            "The Dataset Publication does not match the requested immutable facts",
        )


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
