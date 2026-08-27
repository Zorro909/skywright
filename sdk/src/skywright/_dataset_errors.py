"""Stable internal errors shared by Dataset Publication modules."""


class DatasetPublicationError(Exception):
    """A safe, stable publication failure for command output."""

    def __init__(self, code: str, detail: str, *, retryable: bool = False) -> None:
        super().__init__(detail)
        self.code = code
        self.detail = detail
        self.retryable = retryable


def publication_error(code: str, detail: str) -> DatasetPublicationError:
    return DatasetPublicationError(code, detail)


def metadata_error(detail: str) -> DatasetPublicationError:
    return publication_error("DATASET_MDS_DECODING_METADATA_INVALID", detail)


def source_mutated_error() -> DatasetPublicationError:
    return publication_error(
        "DATASET_SOURCE_MUTATED", "A local corpus file changed during publication"
    )


def protocol_error() -> DatasetPublicationError:
    return publication_error(
        "CONTROL_PLANE_PROTOCOL_FAILURE",
        "The control plane returned an invalid Dataset Publication response",
    )


def problem(error: DatasetPublicationError) -> dict[str, object]:
    """Render one stable RFC 9457-shaped command error."""
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


def exit_code(error: DatasetPublicationError) -> int:
    """Map a publication failure onto the command's documented exit contract."""
    if (
        error.code.startswith("DATASET_CORPUS_")
        or error.code.startswith("DATASET_MDS_")
        or error.code == "DATASET_STREAMING_FORMAT_UNSUPPORTED"
        or error.code == "DATASET_PREFERRED_DEFINITION_DECISION_REQUIRED"
        or error.code.endswith("_INVALID")
        or error.code in {"DATASET_SOURCE_MUTATED", "DATASET_LOCAL_IO_FAILURE"}
    ):
        return 2
    if error.code in {"DATASET_UPLOAD_FAILED", "DATASET_COMMIT_RESPONSE_AMBIGUOUS"}:
        return 75
    if error.retryable or error.code in {
        "CONTROL_PLANE_UNAVAILABLE",
        "CONTROL_PLANE_PROTOCOL_FAILURE",
    }:
        return 69
    return 3
