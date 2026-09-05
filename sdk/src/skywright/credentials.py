"""Role-specific S3 environment/file contract shared by direct and managed runs."""

from __future__ import annotations

import json
import os
import stat
from collections.abc import Mapping
from pathlib import Path
from typing import Literal, cast

__all__ = ("CredentialProjectionError", "s3_credentials")


class CredentialProjectionError(RuntimeError):
    """The fixed credential projection is unavailable or malformed."""


def s3_credentials(
    slot: Literal["dataset", "run_store"],
    environment: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """Return explicit boto3 arguments without falling back to ambient identities.

    A slot contains ACCESS_KEY_ID, SECRET_ACCESS_KEY and optional SESSION_TOKEN,
    or CREDENTIAL_FILE pointing to an owner-only, read-only JSON file with those
    fields. Values are fixed for the consuming client's lifetime.
    """
    if slot not in ("dataset", "run_store"):
        raise CredentialProjectionError("Unknown storage credential slot")
    env = os.environ if environment is None else environment
    prefix = f"SKYWRIGHT_{slot.upper()}_"
    fields = {"ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN"}
    values = {key: env[prefix + key] for key in fields if prefix + key in env}
    filename = env.get(prefix + "CREDENTIAL_FILE")
    try:
        if filename is not None:
            if values:
                raise ValueError
            # O_NOFOLLOW also rejects a symlink supplied by an untrusted caller.
            descriptor = os.open(
                Path(filename), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            )
            with os.fdopen(descriptor, "r", encoding="utf-8") as source:
                mode = os.fstat(source.fileno())
                if (
                    not stat.S_ISREG(mode.st_mode)
                    or mode.st_uid != os.geteuid()
                    or stat.S_IMODE(mode.st_mode) != 0o400
                    or mode.st_size > 16384
                ):
                    raise ValueError
                raw: object = json.load(source)
            if not isinstance(raw, dict):
                raise ValueError
            untyped = cast(dict[object, object], raw)
            if any(
                not isinstance(key, str) or not isinstance(value, str)
                for key, value in untyped.items()
            ):
                raise ValueError
            values = cast(dict[str, str], untyped)
        if not {"ACCESS_KEY_ID", "SECRET_ACCESS_KEY"} <= values.keys() <= fields:
            raise ValueError
        if any(not value.strip() for value in values.values()):
            raise ValueError
        names = {
            "ACCESS_KEY_ID": "aws_access_key_id",
            "SECRET_ACCESS_KEY": "aws_secret_access_key",
            "SESSION_TOKEN": "aws_session_token",
        }
        return {names[key]: value for key, value in values.items()}
    except (OSError, ValueError, TypeError):
        raise CredentialProjectionError(
            "Storage Credential Projection is unavailable"
        ) from None
