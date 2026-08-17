"""Content identity primitives shared by Action publication modules."""

from __future__ import annotations

import hashlib
import re

DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")


def sha256_bytes(content: bytes) -> str:
    """Return the canonical SHA-256 content identity."""
    return "sha256:" + hashlib.sha256(content).hexdigest()


def sha256_text(content: str) -> str:
    """Return the canonical SHA-256 identity of UTF-8 text."""
    return sha256_bytes(content.encode())


__all__ = ["DIGEST", "sha256_bytes", "sha256_text"]
