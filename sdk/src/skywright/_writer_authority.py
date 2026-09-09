"""Bounded local authority protocol; no Kubernetes credentials enter the SDK."""

from __future__ import annotations

import json
import os
import re
import socket
import stat
import struct
import time
from pathlib import Path
from typing import cast

from skywright._training_types import ExecutionAttemptRecord
from skywright.recovery import PreviousWriterEvidence, RecoveryAdmissionError

_LIMIT = 4096
_TIMEOUT = 10.0


class LocalWriterAuthority:
    """Register this container's writer and ask its node for predecessor evidence."""

    def __init__(self, path: Path):
        self._path = path

    @classmethod
    def configured(cls) -> LocalWriterAuthority | None:
        path = os.environ.get("SKYWRIGHT_WRITER_AUTHORITY_SOCKET")
        return cls(Path(path)) if path else None

    def register(self, attempt: ExecutionAttemptRecord) -> None:
        response = self._call("register", attempt)
        if response != {"status": "registered"}:
            raise RecoveryAdmissionError(
                "RECOVERY_AUTHORITY_UNAVAILABLE", "writer registration was refused"
            )

    def previous_writer(
        self, attempt: ExecutionAttemptRecord
    ) -> PreviousWriterEvidence | None:
        deadline = time.monotonic() + 60.0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            response = self._call("verify", attempt, timeout=min(_TIMEOUT, remaining))
            if response != {"status": "pending"}:
                break
            time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
        if response == {"status": "uncertain"}:
            return None
        if (
            set(response) != {"status", "run_id", "attempt_id", "reference"}
            or response["status"] != "stopped"
            or response["run_id"] != attempt.run_id
            or response["attempt_id"] != attempt.attempt_id
            or not isinstance(response["reference"], str)
            or re.fullmatch(
                r"local-writer-proof:[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}:sha256:[0-9a-f]{64}",
                response["reference"],
            )
            is None
        ):
            raise RecoveryAdmissionError(
                "RECOVERY_AUTHORITY_UNAVAILABLE", "writer evidence was invalid"
            )
        return PreviousWriterEvidence(
            attempt.run_id, attempt.attempt_id, "stopped", response["reference"]
        )

    def _call(
        self,
        operation: str,
        attempt: ExecutionAttemptRecord,
        *,
        timeout: float = _TIMEOUT,
    ) -> dict[str, object]:
        try:
            metadata = self._path.lstat()
            if not stat.S_ISSOCK(metadata.st_mode) or metadata.st_uid != 0:
                raise ValueError("untrusted authority socket")
            with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as connection:
                deadline = time.monotonic() + timeout
                connection.settimeout(timeout)
                connection.connect(str(self._path))
                _, uid, _ = struct.unpack(
                    "3i",
                    connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12),
                )
                if uid != 0:
                    raise ValueError("untrusted authority peer")
                request = json.dumps(
                    {
                        "operation": operation,
                        "run_id": attempt.run_id,
                        "attempt_id": attempt.attempt_id,
                    },
                    separators=(",", ":"),
                ).encode()
                if len(request) > _LIMIT:
                    raise ValueError("oversized authority request")
                connection.settimeout(max(0.001, deadline - time.monotonic()))
                connection.sendall(request)
                connection.settimeout(max(0.001, deadline - time.monotonic()))
                data, _, flags, _ = connection.recvmsg(_LIMIT)
                if flags & socket.MSG_TRUNC:
                    raise ValueError("oversized authority response")
                response = json.loads(data)
                if not isinstance(response, dict):
                    raise ValueError("invalid authority response")
                return cast(dict[str, object], response)
        except (OSError, ValueError):
            # Provider paths, response bodies and peer diagnostics are not evidence.
            raise RecoveryAdmissionError(
                "RECOVERY_AUTHORITY_UNAVAILABLE",
                "local writer authority is unavailable",
            ) from None
