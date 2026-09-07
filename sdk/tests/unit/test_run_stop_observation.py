# Wire fixtures and the S3 boundary deliberately use dynamically shaped values.
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
# pyright: reportUnknownMemberType=false, reportUnknownArgumentType=false, reportUnknownVariableType=false
import hashlib
import io
import json
from uuid import uuid4

import pytest
from botocore.exceptions import ClientError  # pyright: ignore[reportMissingTypeStubs]

from skywright._run_store.control import RunStopObservation
from skywright._run_store.implementation import RunStoreIntegrityError, TargetStorage
from skywright.recovery import RecoveryAdmissionError


def missing():
    return ClientError(
        {"Error": {"Code": "NoSuchKey"}, "ResponseMetadata": {"HTTPStatusCode": 404}},
        "GetObject",
    )


class Store:
    def __init__(self):
        self.objects = {}
        self.closed = False
        self.failure = None
        self.reads = 0

    def get_object(self, *, Bucket, Key):
        self.reads += 1
        if self.failure:
            raise self.failure
        if Key not in self.objects:
            raise missing()
        body, metadata = self.objects[Key]
        return {
            "Body": io.BytesIO(body),
            "ContentLength": len(body),
            "Metadata": metadata,
        }

    def put_object(self, *, Bucket, Key, Body, Metadata, **_kwargs):
        if Key in self.objects:
            raise ClientError(
                {"ResponseMetadata": {"HTTPStatusCode": 412}}, "PutObject"
            )
        self.objects[Key] = (Body, Metadata)

    def close(self):
        self.closed = True


@pytest.fixture
def observation():
    store = Store()
    target = TargetStorage(
        storage_id="output",
        endpoint_url="http://s3.invalid",
        region="us-east-1",
        bucket="outputs",
        training_project_id="project",
        run_id=str(uuid4()),
    )
    return RunStopObservation(target, "sha256:" + "a" * 64, client=store), store


def publish(observer, store, kind="cancellation", **changes):
    value = {
        "schemaVersion": 1,
        "runId": observer.target.run_id,
        "projectVersion": observer.version,
        "commandId": str(uuid4()),
        "kind": kind,
        "requestedAt": "2026-09-07T00:00:00Z",
    }
    value.update(changes)
    body = json.dumps(value).encode()
    store.objects[observer._key(kind)] = (
        body,
        {  # pyright: ignore[reportPrivateUsage]
            "skywright-schema": "v1",
            "skywright-kind": "run-stop-request",
            "skywright-size": str(len(body)),
            "skywright-sha256": hashlib.sha256(body).hexdigest(),
        },
    )
    return value["commandId"]


def test_callbacks_are_memory_only_and_observed_intent_survives_missing_objects(
    observation,
):
    observer, store = observation
    observer.refresh()
    assert not observer.cancelled() and observer.policy_stop() is None
    command = publish(observer, store, "policy-stop")
    observer.refresh()
    assert observer.policy_stop() == command
    store.objects.clear()
    observer.refresh()
    assert observer.policy_stop() == command
    publish(observer, store)
    observer.refresh()
    store.failure = RuntimeError("offline")
    reads = store.reads
    for _ in range(100):
        assert observer.cancelled()
        assert observer.policy_stop() == command
        observer.refresh()
    assert store.reads == reads


@pytest.mark.parametrize("kind", ["cancellation", "policy-stop"])
def test_preexisting_stop_refuses_new_attempt_and_preserves_original_refusal(
    observation, kind
):
    observer, store = observation
    command = publish(observer, store, kind)
    with pytest.raises(RecoveryAdmissionError) as refusal:
        observer.start()
    assert refusal.value.code == "RUN_STOP_REQUESTED"
    body, _ = store.objects[observer._key("startup-refusal")]  # pyright: ignore[reportPrivateUsage]
    assert json.loads(body)["commandId"] == command
    assert store.closed
    replacement = RunStopObservation(observer.target, observer.version, client=store)
    with pytest.raises(RecoveryAdmissionError):
        replacement.start()
    assert store.objects[observer._key("startup-refusal")][0] == body  # pyright: ignore[reportPrivateUsage]


@pytest.mark.parametrize(
    "changes",
    [
        {"runId": str(uuid4())},
        {"commandId": "bad"},
        {"schemaVersion": True},
        {"projectVersion": "foreign"},
        {"requestedAt": "2026-09-07"},
    ],
)
def test_foreign_or_malformed_stop_is_not_acknowledged(observation, changes):
    observer, store = observation
    publish(observer, store, **changes)
    with pytest.raises((ValueError, RunStoreIntegrityError)):
        observer.refresh()
    assert not observer.cancelled()
    with pytest.raises(RecoveryAdmissionError) as refusal:
        observer.start()
    assert refusal.value.code == "RECOVERY_UNAVAILABLE"


def test_owned_poll_worker_closes_and_absence_requires_a_successful_read(observation):
    observer, store = observation
    observer.start()
    observer.close()
    assert store.closed
    other = RunStopObservation(observer.target, observer.version, client=store)
    store.failure = RuntimeError("storage outage")
    with pytest.raises(RecoveryAdmissionError) as refusal:
        other.start()
    assert refusal.value.code == "RECOVERY_UNAVAILABLE"
