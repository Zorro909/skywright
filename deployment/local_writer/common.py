"""Bounded value decoding shared by the local writer authority."""

from __future__ import annotations

import json
import stat
import uuid

LIMIT = 4096
INSPECT_LIMIT = 2 * 1024 * 1024


class Uncertain(Exception):
    """The authority cannot establish its custody or an exact permanent death."""


class Pending(Uncertain):
    """The exact registered container is still running or draining."""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def bounded_json(path, limit=INSPECT_LIMIT):
    with path.open("rb") as source:
        value = source.read(limit + 1)
    if len(value) > limit:
        raise Uncertain()
    return json.loads(value)


def identifier(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise Uncertain()
    return value


def identity(path):
    value = path.stat(follow_symlinks=False)
    if stat.S_ISLNK(value.st_mode):
        raise Uncertain()
    return [value.st_dev, value.st_ino]
