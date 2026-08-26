"""Control-plane lifecycle for direct Dataset Publication transfer."""

from __future__ import annotations

import threading
import uuid
from collections.abc import Callable
from typing import cast

from skywright._dataset_errors import DatasetPublicationError

Request = Callable[[str, str, str, object], object]


_HEARTBEAT_SECONDS = 30


class TransferLease:
    """Identified renewable registration for one direct upload process."""

    def __init__(
        self, request: Request, control_plane: str, publication_id: str
    ) -> None:
        self._request = request
        self._control_plane = control_plane
        self._publication_id = publication_id
        self._transfer_id = str(uuid.uuid4())
        self._stopped = threading.Event()
        self._failure: DatasetPublicationError | None = None
        self._thread: threading.Thread | None = None

    def __enter__(self) -> TransferLease:
        start(
            self._request,
            self._control_plane,
            self._publication_id,
            self._transfer_id,
        )
        self._thread = threading.Thread(target=self._heartbeat, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_error: object) -> None:
        self._stopped.set()
        if self._thread is not None:
            self._thread.join()
        stop(
            self._request,
            self._control_plane,
            self._publication_id,
            self._transfer_id,
        )

    def ensure_active(self) -> None:
        if self._failure is not None:
            raise self._failure
        ensure_active(self._request, self._control_plane, self._publication_id)

    def _heartbeat(self) -> None:
        while not self._stopped.wait(_HEARTBEAT_SECONDS):
            try:
                start(
                    self._request,
                    self._control_plane,
                    self._publication_id,
                    self._transfer_id,
                )
            except DatasetPublicationError as failure:
                self._failure = failure
                return


def start(
    request: Request, control_plane: str, publication_id: str, transfer_id: str
) -> None:
    result = request(
        control_plane,
        "POST",
        f"/api/v1/dataset-publications/{publication_id}/transfer-start",
        {"transferId": transfer_id},
    )
    state = _state(result)
    if state in {"aborting", "aborted"}:
        raise DatasetPublicationError(
            "DATASET_PUBLICATION_ABORTED",
            "The Dataset Publication was aborted before transfer began",
        )
    if state not in {"awaiting-upload", "uploading"}:
        raise DatasetPublicationError(
            "DATASET_PUBLICATION_FENCED",
            "The Dataset Publication no longer accepts transfer work",
        )


def stop(
    request: Request, control_plane: str, publication_id: str, transfer_id: str
) -> None:
    request(
        control_plane,
        "POST",
        f"/api/v1/dataset-publications/{publication_id}/transfer-stop",
        {"transferId": transfer_id},
    )


def ensure_active(request: Request, control_plane: str, publication_id: str) -> None:
    result = request(
        control_plane,
        "GET",
        f"/api/v1/dataset-publications/{publication_id}",
        None,
    )
    publication = _publication(result)
    state = publication.get("state")
    if state in {"awaiting-upload", "uploading"}:
        return
    if state in {"aborting", "aborted"} or (
        state == "failed-cleanup" and publication.get("preferredDefinitionId") is None
    ):
        raise DatasetPublicationError(
            "DATASET_PUBLICATION_ABORTED",
            "The Dataset Publication was aborted while transfer was active",
        )
    raise DatasetPublicationError(
        "DATASET_PUBLICATION_FENCED",
        "The Dataset Publication no longer accepts transfer work",
    )


def _state(value: object) -> object:
    return _publication(value).get("state")


def _publication(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise DatasetPublicationError(
            "CONTROL_PLANE_PROTOCOL_FAILURE",
            "The control plane returned an invalid Dataset Publication response",
        )
    return cast(dict[str, object], value)
