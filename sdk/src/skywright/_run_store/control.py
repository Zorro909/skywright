"""Bounded managed stop-request observation, separate from training state."""

from __future__ import annotations

import hashlib
import json
import threading
from datetime import datetime, timezone
from typing import Any, cast
from uuid import UUID

from skywright._run_store.implementation import (
    RunStoreIntegrityError,
    TargetStorage,
    _is_missing_resource,  # pyright: ignore[reportPrivateUsage]
    _validated_metadata,  # pyright: ignore[reportPrivateUsage]
)
from skywright.credentials import s3_credentials
from skywright.recovery import RecoveryAdmissionError

_LIMIT = 65536


def _pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in items:
        if key in result:
            raise RunStoreIntegrityError("duplicate stop-request field")
        result[key] = value
    return result


class RunStopObservation:
    """Callbacks are memory reads; one owned worker observes immutable requests."""

    def __init__(
        self, target: TargetStorage, project_version: str, *, client: Any = None
    ):
        self.target = target
        self.version = project_version
        self._client = client or self._make_client()
        self._requests: dict[str, str] = {}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def _make_client(self) -> Any:
        import boto3  # pyright: ignore[reportMissingTypeStubs]
        from botocore.config import Config  # pyright: ignore[reportMissingTypeStubs]

        session = cast(Any, boto3.Session)(
            profile_name=self.target.profile_name,
            region_name=self.target.region,
            **(s3_credentials("run_store") if self.target.credential_slot else {}),
        )
        return session.client(
            "s3",
            endpoint_url=self.target.endpoint_url,
            config=Config(
                connect_timeout=1,
                read_timeout=1,
                retries={"max_attempts": 0},
                s3={"addressing_style": self.target.addressing_style},
                request_checksum_calculation=self.target.compatibility_options.get(
                    "checksumCalculation", "when-required"
                ).replace("-", "_"),
            ),
        )

    def _key(self, kind: str) -> str:
        return f"{self.target.training_project_id}/{self.target.run_id}/v1/control/{kind}.json"

    def _read(self, kind: str) -> str | None:
        key = self._key(kind)
        try:
            response = self._client.get_object(Bucket=self.target.bucket, Key=key)
        except Exception as failure:
            if _is_missing_resource(failure, "NoSuchKey"):
                return None
            raise
        with response["Body"] as source:
            size, digest = _validated_metadata(key, response)
            if (
                size > _LIMIT
                or response["Metadata"].get("skywright-kind") != "run-stop-request"
            ):
                raise RunStoreIntegrityError("invalid stop-request metadata")
            body = source.read(size + 1)
        if len(body) != size or hashlib.sha256(body).hexdigest() != digest:
            raise RunStoreIntegrityError("invalid stop-request digest")
        raw = json.loads(body, object_pairs_hook=_pairs)
        if not isinstance(raw, dict):
            raise RunStoreIntegrityError("invalid stop-request document")
        value = cast(dict[str, Any], raw)
        if (
            set(value)
            != {
                "schemaVersion",
                "runId",
                "projectVersion",
                "commandId",
                "kind",
                "requestedAt",
            }
            or type(value["schemaVersion"]) is not int
            or value["schemaVersion"] != 1
            or value["runId"] != self.target.run_id
            or value["projectVersion"] != self.version
            or value["kind"] != kind
            or str(UUID(value["commandId"])) != value["commandId"]
        ):
            raise RunStoreIntegrityError("invalid stop-request identity")
        requested = datetime.fromisoformat(value["requestedAt"].replace("Z", "+00:00"))
        if requested.tzinfo is None:
            raise RunStoreIntegrityError("stop-request time requires an offset")
        return cast(str, value["commandId"])

    def refresh(self) -> None:
        if self.cancelled():
            return
        for kind in ("cancellation", "policy-stop"):
            command = self._read(kind)
            if command is not None:
                previous = self._requests.get(kind)
                if previous is not None and previous != command:
                    raise RunStoreIntegrityError("stop-request identity changed")
                self._requests[kind] = command
                if kind == "cancellation":
                    return

    def start(self) -> None:
        try:
            self.refresh()
            kind = "cancellation" if self.cancelled() else "policy-stop"
            if command := self._requests.get(kind):
                self._refuse_startup(kind, command)
                raise RecoveryAdmissionError(
                    "RUN_STOP_REQUESTED",
                    "a durable stop request prevents a new Execution Attempt",
                )
        except RecoveryAdmissionError:
            self._client.close()
            raise
        except Exception as failure:
            self._client.close()
            raise RecoveryAdmissionError(
                "RECOVERY_UNAVAILABLE", "stop-request admission is unavailable"
            ) from failure
        self._thread = threading.Thread(
            target=self._observe, name="skywright-stop-requests", daemon=True
        )
        self._thread.start()

    def _refuse_startup(self, kind: str, command: str) -> None:
        body = json.dumps(
            {
                "schemaVersion": 1,
                "runId": self.target.run_id,
                "projectVersion": self.version,
                "commandId": command,
                "kind": kind,
                "refusedAt": datetime.now(timezone.utc).isoformat(),
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
        try:
            self._client.put_object(
                Bucket=self.target.bucket,
                Key=self._key("startup-refusal"),
                Body=body,
                ContentType="application/json",
                IfNoneMatch="*",
                Metadata={
                    "skywright-schema": "v1",
                    "skywright-kind": "run-stop-refusal",
                    "skywright-size": str(len(body)),
                    "skywright-sha256": hashlib.sha256(body).hexdigest(),
                },
            )
        except Exception as failure:
            response = getattr(failure, "response", {})
            if response.get("ResponseMetadata", {}).get("HTTPStatusCode") != 412:
                raise
            # A prior refusal is immutable. It already fences this startup; it is
            # validated independently by lifecycle reads.

    def _observe(self) -> None:
        try:
            while not self._stop.wait(1):
                try:
                    self.refresh()
                except Exception:
                    # Temporary loss never erases an observed request. Backend
                    # forced cancellation owns the bounded remote fallback.
                    continue
        finally:
            self._client.close()

    def cancelled(self) -> bool:
        return "cancellation" in self._requests

    def policy_stop(self) -> str | None:
        return self._requests.get("policy-stop")

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=3)
