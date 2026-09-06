# Provider-shaped responses and the dictionary storage fake are intentionally dynamic.
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false

from __future__ import annotations

import hashlib
from typing import Any

import pytest
from botocore.exceptions import ClientError
from unit.test_run_store import MemoryS3, recorder

from skywright import CheckpointSnapshot, DatasetCursor, ExecutionAttemptRecord
from skywright.run_store import CheckpointReference, RunStoreReader


def ignore_delay(_: float) -> None:
    return None


def history(tmp_path):
    memory = MemoryS3()
    writer = recorder(memory, tmp_path)
    writer.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
        )
    )
    references = [
        writer.publish_checkpoint(
            CheckpointSnapshot(
                step,
                {"value": step},
                dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
                run_id="run",
                project_version="project@digest",
            )
        )
        for step in (1, 2)
    ]
    parsed = CheckpointReference.parse(references[1])
    key = writer.protocol.checkpoint_key(parsed.step, parsed.digest)
    reader = RunStoreReader(writer.target, client=memory, staging_directory=tmp_path)
    return memory, writer, reader, references, key


def provider_error(code: str | None, status: int) -> ClientError:
    return ClientError(
        {"Error": {"Code": code}, "ResponseMetadata": {"HTTPStatusCode": status}},
        "GetObject",
    )


@pytest.mark.parametrize("code", ["dictionary", "NoSuchKey", "404", "NotFound", None])
def test_listed_checkpoint_missing_at_get_falls_back_with_evidence(tmp_path, code):
    memory, _, reader, references, key = history(tmp_path)
    original = memory.get_object

    def get_object(**request: Any):
        if request["Key"] == key:
            if code == "dictionary":
                raise KeyError(key)
            raise provider_error(code, 404)
        return original(**request)

    memory.get_object = get_object
    resolution = reader.resolve_latest_valid(
        project_version="project@digest", ordering_fingerprint="ordering"
    )
    assert resolution.checkpoint.reference == references[0]
    assert [(item.step, item.code) for item in resolution.rejected] == [
        (2, "RUN_STORE_MISSING_OBJECT")
    ]
    assert not list(tmp_path.glob("skywright-read-*"))


def test_incompatible_checkpoint_schema_does_not_fall_back(tmp_path):
    memory, writer, reader, _, key = history(tmp_path)
    body, metadata, content_type = memory.objects.pop(key)
    body = body.replace(b"checkpoint-v1", b"checkpoint-v2")
    digest = hashlib.sha256(body).hexdigest()
    memory.objects[writer.protocol.checkpoint_key(2, digest)] = (
        body,
        {**metadata, "skywright-sha256": digest},
        content_type,
    )
    with pytest.raises(ValueError, match="RUN_STORE_INCOMPATIBLE_SCHEMA"):
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )


@pytest.mark.parametrize(
    "code,status",
    [
        ("NoSuchBucket", 404),
        ("NoSuchUpload", 404),
        ("ServiceUnavailable", 503),
        ("PreconditionFailed", 412),
    ],
)
def test_provider_failure_never_selects_an_older_checkpoint(
    tmp_path, monkeypatch, code, status
):
    memory, _, reader, _, key = history(tmp_path)
    failure = provider_error(code, status)
    calls = []

    def unavailable(**request):
        calls.append(request["Key"])
        raise failure

    monkeypatch.setattr(memory, "get_object", unavailable)
    monkeypatch.setattr("skywright._run_store.implementation.time.sleep", ignore_delay)
    with pytest.raises(ClientError) as raised:
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    assert raised.value is failure
    assert set(calls) == {key}
    assert not list(tmp_path.glob("skywright-read-*"))


@pytest.mark.parametrize(
    "code,status",
    [
        ("AccessDenied", 403),
        ("AccessDenied", 404),
        ("ExpiredToken", 403),
        ("NoSuchKey", 403),
    ],
)
def test_permission_rejection_takes_precedence_over_missing_codes(
    tmp_path, monkeypatch, code, status
):
    from skywright.credentials import CredentialProjectionError

    memory, _, reader, _, key = history(tmp_path)
    calls = []

    def denied(**request):
        calls.append(request["Key"])
        raise provider_error(code, status)

    monkeypatch.setattr(memory, "get_object", denied)
    with pytest.raises(CredentialProjectionError):
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    assert calls == [key]


def test_timeout_never_selects_an_older_checkpoint(tmp_path, monkeypatch):
    memory, _, reader, _, key = history(tmp_path)
    calls = []

    def timeout(**request):
        calls.append(request["Key"])
        raise TimeoutError("storage read timed out")

    monkeypatch.setattr(memory, "get_object", timeout)
    monkeypatch.setattr("skywright._run_store.implementation.time.sleep", ignore_delay)
    with pytest.raises(TimeoutError):
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    assert set(calls) == {key}


@pytest.mark.parametrize(
    "code,status",
    [("NoSuchBucket", 404), ("NoSuchKey", 404), ("ServiceUnavailable", 503)],
)
def test_missing_or_unavailable_history_is_not_an_empty_history(
    tmp_path, monkeypatch, code, status
):
    memory, _, reader, _, _ = history(tmp_path)
    failure = provider_error(code, status)

    def listing(**_):
        raise failure

    def get(**_):
        pytest.fail("history was unavailable; no checkpoint may be selected")

    monkeypatch.setattr(memory, "list_objects_v2", listing)
    monkeypatch.setattr(memory, "get_object", get)
    monkeypatch.setattr("skywright._run_store.implementation.time.sleep", ignore_delay)
    with pytest.raises(ClientError) as raised:
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    assert raised.value is failure


@pytest.mark.parametrize("incompatibility", ["project", "ordering", "metadata"])
def test_incompatible_state_and_metadata_fail_closed(tmp_path, incompatibility):
    memory, writer, reader, _, key = history(tmp_path)
    if incompatibility == "metadata":
        body, metadata, media_type = memory.objects[key]
        memory.objects[key] = (body, {**metadata, "skywright-schema": "v2"}, media_type)
    else:
        del memory.objects[key]
        writer.publish_checkpoint(
            CheckpointSnapshot(
                2,
                {"value": 2},
                run_id="run",
                project_version="other"
                if incompatibility == "project"
                else "project@digest",
                dataset_cursor=DatasetCursor(
                    ordering_fingerprint="other"
                    if incompatibility == "ordering"
                    else "ordering"
                ),
            )
        )
    from skywright.run_store import RunStoreError

    with pytest.raises(RunStoreError):
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )


def test_malformed_provider_response_is_not_a_missing_checkpoint(tmp_path, monkeypatch):
    memory, _, reader, _, key = history(tmp_path)
    calls = []

    def malformed(**request):
        calls.append(request["Key"])
        return {}

    monkeypatch.setattr(memory, "get_object", malformed)
    with pytest.raises(KeyError, match="Body"):
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    assert calls == [key]


def test_missing_head_is_normalized_for_download_links(tmp_path, monkeypatch):
    from skywright.run_store import RunStoreMissingObjectError

    memory, _, reader, _, key = history(tmp_path)

    def missing(**_):
        raise provider_error("404", 404)

    monkeypatch.setattr(memory, "head_object", missing)
    with pytest.raises(RunStoreMissingObjectError, match="RUN_STORE_MISSING_OBJECT"):
        reader.presign_download(key)


@pytest.mark.parametrize("code", ["dictionary", "NoSuchKey", "NoSuchBucket"])
def test_retention_ignores_only_an_already_missing_obsolete_object(
    tmp_path, monkeypatch, code
):
    memory, _, reader, references, latest = history(tmp_path)
    calls = []

    def delete(**request):
        calls.append(request["Key"])
        if code in {"dictionary", "NoSuchKey"}:
            del memory.objects[request["Key"]]
        if code == "dictionary":
            raise KeyError(request["Key"])
        raise provider_error(code, 404)

    monkeypatch.setattr(memory, "delete_object", delete)
    if code == "NoSuchBucket":
        with pytest.raises(ClientError):
            reader.prune_checkpoints(retention=1)
    else:
        reader.prune_checkpoints(retention=1)
        assert reader.read_exact(references[1]).step == 2
    assert len(calls) == 1 and calls[0] != latest
    assert latest in memory.objects


def test_progress_publication_reuses_provider_missing_normalization(
    tmp_path, monkeypatch
):
    memory = MemoryS3()
    original = memory.get_object

    def get(**request):
        try:
            return original(**request)
        except KeyError:
            raise provider_error("NoSuchKey", 404) from None

    monkeypatch.setattr(memory, "get_object", get)
    writer = recorder(memory, tmp_path, metric_staging_directory=tmp_path)
    writer.configure_metrics({"metrics": {"flushInterval": 10, "segmentRoll": 1000}})
    writer.publish_attempt(
        ExecutionAttemptRecord(
            "123e4567-e89b-12d3-a456-426614174000", "run", "project@digest", None
        )
    )
    try:
        writer.publish_step(1, DatasetCursor(item_offset=1), (), None, None)
    finally:
        writer.finalize_observability()
    reader = RunStoreReader(writer.target, client=memory)
    assert reader.read_progress().current_step == 1


@pytest.mark.parametrize("cancelled", [False, True])
def test_deadline_and_cancellation_stop_before_candidate_reads(
    tmp_path, monkeypatch, cancelled: bool
):
    from skywright.run_store import (
        OperationControl,
        RunStoreCancelledError,
        RunStoreDeadlineError,
    )

    memory, writer, _, _, _ = history(tmp_path)
    elapsed = [0.0]
    original = memory.list_objects_v2

    def listing(**request):
        response = original(**request)
        elapsed[0] = 2.0
        return response

    def forbidden(**_):
        pytest.fail("no candidate may be read after the operation stops")

    monkeypatch.setattr(memory, "list_objects_v2", listing)
    monkeypatch.setattr(memory, "get_object", forbidden)
    monkeypatch.setattr(
        "skywright._run_store.implementation.time.monotonic", lambda: elapsed[0]
    )
    control = OperationControl(
        deadline=None if cancelled else 1.0,
        cancellation_requested=lambda: cancelled and elapsed[0] > 1,
    )
    reader = RunStoreReader(
        writer.target,
        client=memory,
        operation_control=control,
        staging_directory=tmp_path,
    )
    with pytest.raises(RunStoreCancelledError if cancelled else RunStoreDeadlineError):
        reader.resolve_latest_valid(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    assert not list(tmp_path.glob("skywright-read-*"))


@pytest.mark.parametrize(
    "code", ["NoSuchUpload", "404", "NoSuchBucket", "NoSuchKey", "AccessDenied"]
)
def test_missing_upload_is_distinct_from_missing_object_bucket_or_permission(
    tmp_path, monkeypatch, code
):
    from skywright.credentials import CredentialProjectionError
    from skywright.run_store import MultipartUpload

    memory, _, reader, _, key = history(tmp_path)

    def abort(**_):
        raise provider_error(code, 403 if code == "AccessDenied" else 404)

    monkeypatch.setattr(memory, "abort_multipart_upload", abort)
    upload = MultipartUpload(key, "gone-upload", ())
    if code in {"NoSuchUpload", "404"}:
        reader.abort_incomplete_upload(upload)
    else:
        with pytest.raises(
            CredentialProjectionError if code == "AccessDenied" else ClientError
        ):
            reader.abort_incomplete_upload(upload)


def test_malformed_checkpoint_container_with_matching_digest_can_fall_back(tmp_path):
    memory, writer, reader, references, key = history(tmp_path)
    _, metadata, content_type = memory.objects.pop(key)
    body = b"invalid"
    digest = hashlib.sha256(body).hexdigest()
    memory.objects[writer.protocol.checkpoint_key(2, digest)] = (
        body,
        {**metadata, "skywright-sha256": digest, "skywright-size": str(len(body))},
        content_type,
    )
    resolution = reader.resolve_latest_valid(
        project_version="project@digest", ordering_fingerprint="ordering"
    )
    assert resolution.checkpoint.reference == references[0]
    assert resolution.rejected[0].code == "RUN_STORE_MALFORMED_SAFETENSORS"
