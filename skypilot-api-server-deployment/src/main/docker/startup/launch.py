"""Validate external state, then replace this process with SkyPilot."""

from __future__ import annotations

import os
import json
import stat
from pathlib import Path
import sys
import tempfile
from urllib.parse import urlsplit


def fail(message: str) -> None:
    print(
        f"SkyPilot API server configuration error: {message}",
        file=sys.stderr,
        flush=True,
    )
    raise SystemExit(78)


def validate_database() -> None:
    database_uri = os.environ.get("SKYPILOT_DB_CONNECTION_URI")
    if not database_uri:
        fail("SKYPILOT_DB_CONNECTION_URI is required")

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
        fail(
            "SKYPILOT_DB_CONNECTION_URI must be a PostgreSQL URI with a host and database"
        )


def validate_writable_paths() -> None:
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
            fail(f"writable runtime path is unavailable: {writable_path}")


def validate_kubernetes_projection() -> None:
    filename = os.environ.pop("SKYWRIGHT_KUBECONFIG", None)
    if filename is None:
        return
    try:
        descriptor = os.open(filename, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "r", encoding="utf-8") as source:
            metadata = os.fstat(source.fileno())
            if (not stat.S_ISREG(metadata.st_mode)
                    or stat.S_IMODE(metadata.st_mode) != 0o400
                    or metadata.st_uid != os.geteuid()
                    or metadata.st_size > 1024 * 1024):
                raise ValueError
            config = json.load(source)
        if (config.get("apiVersion") != "v1" or config.get("kind") != "Config"
                or len(config.get("users", [])) != 1
                or len(config.get("clusters", [])) != 1
                or len(config.get("contexts", [])) != 1):
            raise ValueError
        user = config["users"][0]["user"]
        cluster = config["clusters"][0]["cluster"]
        if (set(user) != {"token"} or not isinstance(user["token"], str) or not user["token"]
                or set(cluster) != {"server", "certificate-authority-data"}):
            raise ValueError
        os.environ["KUBECONFIG"] = filename
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError):
        fail("local Kubernetes Credential Projection is unavailable")


validate_kubernetes_projection()
validate_database()
validate_writable_paths()
os.execv(
    "/usr/local/bin/python",
    ["python", "-m", "sky.server.server", *sys.argv[1:]],
)
