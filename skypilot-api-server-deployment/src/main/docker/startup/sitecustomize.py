"""Fail closed when the packaged SkyPilot server lacks external state."""

from __future__ import annotations

import os
from pathlib import Path
import sys
import tempfile
from urllib.parse import urlsplit


def _fail(message: str) -> None:
    print(
        f"SkyPilot API server configuration error: {message}",
        file=sys.stderr,
        flush=True,
    )
    os._exit(78)


database_uri = os.environ.get("SKYPILOT_DB_CONNECTION_URI")
if not database_uri:
    _fail("SKYPILOT_DB_CONNECTION_URI is required")

try:
    parsed_database_uri = urlsplit(database_uri)
    valid_database_uri = (
        parsed_database_uri.scheme in {"postgres", "postgresql"}
        and parsed_database_uri.hostname is not None
        and parsed_database_uri.path not in {"", "/"}
    )
except ValueError:
    valid_database_uri = False
if not valid_database_uri:
    _fail(
        "SKYPILOT_DB_CONNECTION_URI must be a PostgreSQL URI with a host and database"
    )

for writable_path in (
    Path("/var/lib/skypilot/.sky"),
    Path("/var/lib/skypilot/sky_logs"),
    Path("/var/lib/skypilot/.sky/api_server/clients"),
):
    try:
        writable_path.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=writable_path):
            pass
    except OSError:
        _fail(f"writable runtime path is unavailable: {writable_path}")
