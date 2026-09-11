"""Retained local installation using a versioned, non-secret operator input."""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import stat
import tempfile
import json
import re
import os
import subprocess
import time
from pathlib import Path


CONFIGURATION_FIELDS = {
    "schemaVersion", "release", "context", "node", "stateDirectory",
    "gpuModel", "gpuMemoryBytes", "gpuCount", "secretDirectory",
}


def configuration(path: Path) -> dict:
    try:
        if path.stat().st_size > 65536:
            raise ValueError
        value = json.loads(path.read_text())
    except (OSError, ValueError):
        raise SystemExit("Cannot read installation configuration") from None
    if not isinstance(value, dict) or set(value) - CONFIGURATION_FIELDS:
        raise SystemExit("Unknown installation configuration fields; secrets belong in protected input files")
    if value.get("schemaVersion") != 1:
        raise SystemExit("Unsupported installation configuration version")
    if set(value) != CONFIGURATION_FIELDS:
        raise SystemExit("Installation configuration is incomplete")
    if not isinstance(value["release"], str) or not re.fullmatch(
        r"ghcr\.io/zorro909/skywright-deployment@sha256:[0-9a-f]{64}", value["release"]
    ):
        raise SystemExit("Installation release must use the canonical OCI digest")
    for field in ("context", "node"):
        if not isinstance(value[field], str) or not re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,127}", value[field]):
            raise SystemExit("Invalid installation context or node")
    if not value["context"].startswith("kind-") or value["node"] != value["context"][5:] + "-control-plane":
        raise SystemExit("The supported target is a single-node kind cluster")
    if value["gpuModel"] != "rx7800xt" or type(value["gpuCount"]) is not int or value["gpuCount"] != 2:
        raise SystemExit("This package requires the qualified pair of RX 7800 XT GPUs")
    if type(value["gpuMemoryBytes"]) is not int or not 16_000_000_000 <= value["gpuMemoryBytes"] <= 17_179_869_184:
        raise SystemExit("Invalid qualified GPU memory capacity")
    for field in ("stateDirectory", "secretDirectory"):
        if not isinstance(value[field], str) or not Path(value[field]).is_absolute() or "\x00" in value[field]:
            raise SystemExit("Installation state and secret directories must use absolute paths")
        if Path(value[field]).resolve() == Path("/"):
            raise SystemExit("Installation directories must be dedicated directories")
    state = Path(value["stateDirectory"]).resolve()
    secrets = Path(value["secretDirectory"]).resolve()
    if state.is_relative_to(secrets) or secrets.is_relative_to(state):
        raise SystemExit("State and operator secret directories must be separate, non-nested directories")
    return value


def protected_json(path: Path) -> dict:
    """Read operator input without following symlinks or exposing input on failure."""
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as source:
            info = os.fstat(source.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or info.st_mode & 0o077 or info.st_size > 65536):
                raise ValueError
            value = json.load(source)
            if not isinstance(value, dict):
                raise ValueError
            return value
    except (OSError, ValueError, UnicodeError):
        raise SystemExit("Secret input must be an owner-only regular JSON file: " + str(path)) from None


def registry_inputs(settings: dict) -> dict:
    root = Path(settings["secretDirectory"])
    values = {role: protected_json(root / ("ghcr-" + role + ".json")) for role in ("resolver", "pull")}
    for value in values.values():
        if set(value) != {"username", "token"} or any(
            not isinstance(item, str) or not item or len(item) > 4096 or any(c.isspace() for c in item)
            for item in value.values()
        ):
            raise SystemExit("Registry input requires a username and a nonempty read-only token")
    if values["resolver"]["token"] == values["pull"]["token"]:
        raise SystemExit("Resolver access and private image pulls require distinct read-only tokens")
    return values


@contextlib.contextmanager
def installation_lock(settings: dict, wait_seconds: float = 60):
    directory = Path(settings["stateDirectory"])
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise SystemExit("Installation state must be an owner-only directory")
    descriptor = os.open(directory / "installation.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        deadline = time.monotonic() + wait_seconds
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise SystemExit("Another installation lifecycle command is running") from None
                time.sleep(0.2)
        yield directory


def install(arguments: argparse.Namespace) -> None:
    from .local_installation import install as execute
    execute(arguments)


class Cluster:
    def __init__(self, settings: dict):
        self.context = settings["context"]
        self.deadline = time.monotonic() + 60

    def read(self, *arguments: str) -> dict:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError("Preflight deadline exceeded")
        result = subprocess.run(
            [os.environ.get("SKYWRIGHT_KUBECTL", "kubectl"), "--context", self.context,
             "--request-timeout=10s", "get", *arguments, "--output=json"],
            capture_output=True, timeout=min(15, remaining), check=False,
        )
        if result.returncode or len(result.stdout) > 2 * 1024 * 1024:
            raise ValueError("Cluster read unavailable")
        return json.loads(result.stdout)


def preflight(arguments: argparse.Namespace) -> None:
    settings = configuration(arguments.configuration)
    installed_tool = Path(settings["stateDirectory"]) / "tools/kubectl"
    if "SKYWRIGHT_KUBECTL" not in os.environ and installed_tool.is_file():
        os.environ["SKYWRIGHT_KUBECTL"] = str(installed_tool)
    cluster = Cluster(settings)
    checks = {}
    count = 0
    try:
        node = cluster.read("node", settings["node"])
        count = int(node["status"]["capacity"].get("amd.com/gpu", 0))
        available = int(node["status"]["allocatable"].get("amd.com/gpu", 0))
        node_ready = any(c["type"] == "Ready" and c["status"] == "True"
                         for c in node["status"].get("conditions", []))
        ready = (node_ready and count == settings["gpuCount"] and available == count
                 and node["metadata"]["labels"].get("skypilot.co/accelerator") == settings["gpuModel"])
        checks["target"] = {"status": "ready" if ready else "unavailable",
                            "detail": "Configured node and AMD GPU capacity checked; this is not a reservation."}
    except (KeyError, TypeError, ValueError, OSError, subprocess.TimeoutExpired):
        checks["target"] = {"status": "unavailable", "detail": "Check the Kubernetes context, node and AMD device plugin."}
    try:
        deployments = cluster.read("deployments", "--namespace=skywright").get("items", [])
        by_name = {d["metadata"]["name"]: d for d in deployments}
        names = ("skywright-backend", "skywright-skypilot-api-server", "skywright-postgresql")
        ready = all(by_name.get(name, {}).get("status", {}).get("availableReplicas", 0) == 1 for name in names)
        checks["controlPlane"] = {"status": "ready" if ready else "unavailable",
                                  "detail": "Backend, SkyPilot and PostgreSQL must each have one available replica."}
    except (KeyError, TypeError, ValueError, OSError, subprocess.TimeoutExpired):
        checks["controlPlane"] = {"status": "unavailable", "detail": "Check the skywright namespace and control-plane deployments."}
    from .local_host import inventory
    from .local_operations import Kubernetes, api
    try:
        devices = inventory()
        ready = len(devices) == 2 and all(d["device"] == "0x747e" and d["memoryBytes"] == settings["gpuMemoryBytes"]
                                         and d["busyPercent"] < 5 and d["usedMemoryBytes"] < 512 * 1024**2
                                         for d in devices)
        checks["hostGpu"] = {"status": "ready" if ready else "unavailable",
                             "detail": "Both host GPUs must be idle before admission; finish external GPU jobs first."}
    except SystemExit:
        checks["hostGpu"] = {"status": "unavailable", "detail": "Check the AMD kernel driver and host GPU inventory."}
    if checks["controlPlane"]["status"] == "ready":
        try:
            with Kubernetes(settings["context"]).forward("skywright-backend", 80) as endpoint:
                form = api(endpoint, "/api/v1/managed-run-form")
            for check in form["checks"]:
                checks[check["component"]] = {"status": "ready" if check["ready"] else "unavailable",
                                              "detail": check["detail"], "code": check["code"]}
        except (SystemExit, KeyError, TypeError):
            checks["admission"] = {"status": "unavailable", "detail": "Check backend health and Managed Run admission."}
    else:
        checks["admission"] = {"status": "unavailable", "detail": "Start the installed control plane to inspect project, Dataset, storage and credentials."}
    ready = bool(checks) and all(check["status"] == "ready" for check in checks.values())
    print(json.dumps({"ready": ready, "gpuCount": count, "checks": checks}, sort_keys=True))
    raise SystemExit(0 if ready else 1)
