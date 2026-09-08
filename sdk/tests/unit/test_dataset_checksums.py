"""Full-object evidence at the Dataset upload retry boundary."""

import base64
import hashlib
from io import BytesIO

import pytest

from skywright._dataset_upload import (
    _validated_remote_digest,  # pyright: ignore[reportPrivateUsage]
)


@pytest.mark.parametrize("kind", [None, "COMPOSITE", "FULL_OBJECT"])
@pytest.mark.parametrize("damaged", [False, True])
def test_only_explicit_full_object_evidence_skips_byte_verification(
    kind: str | None, damaged: bool
) -> None:
    expected = b"expected bytes"
    digest = hashlib.sha256(expected).hexdigest()
    checksum = base64.b64encode(bytes.fromhex(digest)).decode()
    stream = BytesIO(b"corrupt! bytes" if damaged else expected)
    requests: list[dict[str, object]] = []

    class Storage:
        def get_object(self, **values: object) -> dict[str, object]:
            requests.append(values)
            return {"Body": stream}

    head: dict[str, object] = {
        "ContentLength": len(expected),
        "ChecksumSHA256": checksum,
        "ChecksumType": kind,
        "ETag": '"version"',
    }
    verified = _validated_remote_digest(
        Storage(), "dataset", "key", head, digest, checksum
    )
    if kind == "FULL_OBJECT":
        assert verified
        assert requests == []
    else:
        assert verified is not damaged
        assert requests == [{"Bucket": "dataset", "Key": "key", "IfMatch": '"version"'}]
        assert stream.closed


def test_full_object_digest_mismatch_is_rejected_without_replacing_evidence() -> None:
    expected = b"expected"
    checksum = base64.b64encode(hashlib.sha256(expected).digest()).decode()
    head: dict[str, object] = {
        "ContentLength": len(expected),
        "ChecksumType": "FULL_OBJECT",
        "ChecksumSHA256": base64.b64encode(b"x" * 32).decode(),
    }
    assert not _validated_remote_digest(
        object(), "dataset", "key", head, hashlib.sha256(expected).hexdigest(), checksum
    )


@pytest.mark.parametrize("body", [b"xx", b"xxxx"])
def test_streamed_fallback_rejects_truncation_or_excess_and_closes_body(
    body: bytes,
) -> None:
    stream = BytesIO(body)
    expected = b"xxx"

    class Storage:
        def get_object(self, **_values: object) -> dict[str, object]:
            return {"Body": stream}

    assert not _validated_remote_digest(
        Storage(),
        "dataset",
        "key",
        {"ContentLength": 3},
        hashlib.sha256(expected).hexdigest(),
        base64.b64encode(hashlib.sha256(expected).digest()).decode(),
    )
    assert stream.closed
