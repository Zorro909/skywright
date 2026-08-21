"""Read memory charged to the current Linux control group."""

import re
from pathlib import Path, PurePosixPath

_MOUNT_ESCAPE = re.compile(r"\\([0-7]{3})")


def read_cgroup_memory_usage() -> int | None:
    """Return the current cgroup usage in bytes, or ``None`` when unavailable."""
    try:
        memberships = Path("/proc/self/cgroup").read_text(encoding="utf-8")
        mounts = Path("/proc/self/mountinfo").read_text(encoding="utf-8")
        counter = _locate_counter(memberships, mounts)
        if counter is None:
            return None
        value = int(counter.read_text(encoding="ascii").strip())
        return value if value >= 0 else None
    except (IndexError, OSError, UnicodeError, ValueError):
        return None


def _locate_counter(memberships: str, mounts: str) -> Path | None:
    unified_path: str | None = None
    memory_path: str | None = None
    for line in memberships.splitlines():
        hierarchy, controllers, path = line.split(":", 2)
        if hierarchy == "0" and not controllers:
            unified_path = path
        elif "memory" in controllers.split(","):
            memory_path = path

    for line in mounts.splitlines():
        fields = line.split()
        separator = fields.index("-")
        filesystem = fields[separator + 1]
        mount_root = _unescape(fields[3])
        mount_point = _unescape(fields[4])
        if filesystem == "cgroup2" and unified_path is not None:
            return _counter_path(
                mount_root, mount_point, unified_path, "memory.current"
            )
        super_options = fields[separator + 3].split(",")
        if (
            filesystem == "cgroup"
            and memory_path is not None
            and "memory" in super_options
        ):
            return _counter_path(
                mount_root,
                mount_point,
                memory_path,
                "memory.usage_in_bytes",
            )
    return None


def _counter_path(
    mount_root: str, mount_point: str, membership: str, filename: str
) -> Path | None:
    try:
        relative = PurePosixPath(membership).relative_to(PurePosixPath(mount_root))
    except ValueError:
        return None
    return Path(mount_point).joinpath(*relative.parts, filename)


def _unescape(value: str) -> str:
    return _MOUNT_ESCAPE.sub(lambda match: chr(int(match.group(1), 8)), value)
