"""Fixed read-only byte reader, also sent unchanged to a LOCAL task head pod.

This module imports no SkyPilot code and performs no schema initialization.
"""
from __future__ import annotations

import base64
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import time

MAX_BYTES = 1024 * 1024
TERMINAL = {"SUCCEEDED", "FAILED", "FAILED_SETUP", "FAILED_DRIVER", "CANCELLED"}


class Unavailable(Exception):
    pass


def integer(value, minimum=0, maximum=2**63 - 1):
    if type(value) is not int or not minimum <= value <= maximum:
        raise Unavailable("INVALID_CURSOR")
    return value


def regular_page(path, root, offset, limit, *, identity=None, anchor=None):
    """Open only regular files below a trusted root, never follow symlinks."""
    offset = integer(offset)
    limit = integer(limit, 1, MAX_BYTES)
    root = Path(root).absolute()
    path = Path(path).absolute()
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        raise Unavailable("SOURCE_PATH_INVALID") from None
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise Unavailable("SOURCE_PATH_INVALID")
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            next_directory = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                     dir_fd=directory)
            os.close(directory)
            directory = next_directory
        descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                             dir_fd=directory)
    finally:
        os.close(directory)
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise Unavailable("SOURCE_PATH_INVALID")
        file_identity = f"{before.st_dev}:{before.st_ino}"
        if identity is not None and identity != file_identity:
            raise Unavailable("SOURCE_REPLACED")
        if before.st_size < offset:
            raise Unavailable("SOURCE_TRUNCATED")
        previous = os.pread(descriptor, min(offset, 64), max(0, offset - 64))
        if anchor is not None and hashlib.sha256(previous).hexdigest() != anchor:
            raise Unavailable("SOURCE_PREFIX_CHANGED")
        data = os.pread(descriptor, min(limit, before.st_size - offset), offset)
        after = os.fstat(descriptor)
        if after.st_size < before.st_size or len(data) != min(limit, before.st_size - offset):
            raise Unavailable("SOURCE_CHANGED_DURING_READ")
        next_offset = offset + len(data)
        following = os.pread(descriptor, min(next_offset, 64), max(0, next_offset - 64))
        return {
            "offset": offset, "size": after.st_size, "bytes": base64.b64encode(data).decode("ascii"),
            "sha256": hashlib.sha256(data).hexdigest(), "identity": file_identity,
            "anchor": hashlib.sha256(following).hexdigest(), "endOfFile": next_offset == after.st_size,
        }
    finally:
        os.close(descriptor)


def driver_stopped(pid, job_id):
    if pid <= 0:
        return False
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as source:
            command = source.read(65537)
        if len(command) > 65536:
            return False
        return f'SKYPILOT_JOB_ID <{job_id}>'.encode() not in command
    except FileNotFoundError:
        return True
    except OSError:
        return False


def task_page(request, *, home=None):
    home = Path.home() if home is None else Path(home)
    name = request.get("taskName")
    if not isinstance(name, str) or re.fullmatch(r"skywright-[0-9a-f-]{36}", name) is None:
        raise Unavailable("TASK_IDENTITY_INVALID")
    cursor = request.get("cursor", {})
    if not isinstance(cursor, dict):
        raise Unavailable("INVALID_CURSOR")
    previous_id = integer(cursor.get("jobId", 0))
    after = bool(cursor.get("advance", False))
    # The caller supplies no SQL, database path, file path or shell fragment.
    runtime = Path(os.path.expanduser(os.environ.get("SKY_RUNTIME_DIR", str(home))))
    database = runtime / ".sky/jobs.db"
    if database.is_symlink() or not database.is_file():
        raise Unavailable("SOURCE_NOT_YET_AVAILABLE")
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True, timeout=0.25)) as connection:
        connection.execute("PRAGMA query_only=ON")
        deadline = time.monotonic() + 1
        connection.set_progress_handler(lambda: int(time.monotonic() >= deadline), 1000)
        rows = connection.execute(
            "SELECT job_id,run_timestamp,status,pid,log_dir FROM jobs "
            "WHERE job_name=? AND job_id>=? AND length(run_timestamp)<=256 "
            "AND length(log_dir)<=4096 ORDER BY job_id LIMIT 1",
            (name, previous_id + int(after)),
        ).fetchall()
        if not rows and after and previous_id:
            # Keep the last sealed generation readable until the managed
            # controller supplies its later DONE finalization barrier.
            rows = connection.execute(
                "SELECT job_id,run_timestamp,status,pid,log_dir FROM jobs "
                "WHERE job_name=? AND job_id=? AND length(run_timestamp)<=256 AND length(log_dir)<=4096 LIMIT 1",
                (name, previous_id),
            ).fetchall()
            after = False
        later = bool(rows) and connection.execute("SELECT 1 FROM jobs WHERE job_name=? AND job_id>? LIMIT 1", (name, rows[0][0])).fetchone() is not None
    if not rows:
        raise Unavailable("SOURCE_NOT_YET_AVAILABLE")
    job_id, timestamp, status_value, pid, log_dir = rows[0]
    if previous_id and not after and job_id != previous_id:
        raise Unavailable("SOURCE_GENERATION_LOST")
    if not isinstance(log_dir, str) or not isinstance(timestamp, str):
        raise Unavailable("SOURCE_IDENTITY_INVALID")
    path = Path(log_dir.replace("~/", str(home) + "/", 1)) / "run.log"
    if not path.is_absolute():
        raise Unavailable("SOURCE_PATH_INVALID")
    same = previous_id == job_id and not after
    if same and cursor.get("runTimestamp") != timestamp:
        raise Unavailable("SOURCE_REPLACED")
    # Establish writer closure before reading any page that may be final.
    sealed = status_value in TERMINAL and driver_stopped(pid or 0, job_id)
    result = regular_page(
        path, home / "sky_logs", cursor.get("offset", 0) if same else 0,
        request.get("limit", MAX_BYTES), identity=cursor.get("identity") if same else None,
        anchor=cursor.get("anchor") if same else None,
    )
    result.update({
        "generation": f"{job_id}:{timestamp}", "sealed": sealed and result["endOfFile"],
        "jobId": job_id, "runTimestamp": timestamp, "status": status_value, "lastGeneration": not later,
        "logDirectory": str(path.parent.relative_to(home / "sky_logs")),
    })
    result["cursor"] = {
        "jobId": job_id, "runTimestamp": timestamp, "identity": result["identity"],
        "offset": result["offset"] + len(base64.b64decode(result["bytes"])),
        "anchor": result["anchor"], "advance": sealed and result["endOfFile"],
        "logDirectory": result["logDirectory"],
    }
    return result


def main():
    try:
        if len(sys.argv) != 2 or len(sys.argv[1]) > 8192:
            raise Unavailable("INVALID_REQUEST")
        result = task_page(json.loads(sys.argv[1]))
        print(json.dumps({"schemaVersion": 1, "page": result}, separators=(",", ":")))
    except Unavailable as failure:
        print(json.dumps({"schemaVersion": 1, "unavailable": str(failure)}))
    except Exception:
        print(json.dumps({"schemaVersion": 1, "unavailable": "SOURCE_UNAVAILABLE"}))


if __name__ == "__main__":
    main()
