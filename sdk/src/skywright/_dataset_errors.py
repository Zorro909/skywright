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
