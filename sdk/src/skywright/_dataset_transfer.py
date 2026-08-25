"""Control-plane lifecycle for direct Dataset Publication transfer."""

from __future__ import annotations

from collections.abc import Callable
from typing import cast

from skywright._dataset_errors import DatasetPublicationError

Request = Callable[[str, str, str, object], object]


def start(request: Request, control_plane: str, publication_id: str) -> None:
    result = request(
        control_plane,
        "POST",
        f"/api/v1/dataset-publications/{publication_id}/transfer-start",
        {},
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


def stop(request: Request, control_plane: str, publication_id: str) -> None:
    request(
        control_plane,
        "POST",
        f"/api/v1/dataset-publications/{publication_id}/transfer-stop",
        {},
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
