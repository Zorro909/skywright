"""Quiesce admission and checkpoint the retained node before release changes."""

from __future__ import annotations

import datetime
import json
import os
import shutil
import subprocess
import time
import urllib.parse
from pathlib import Path

from .local_host import tools
from .local_installation import record, apply_verified
from .local_operations import Kubernetes, api, command
from .local_package import configuration, installation_lock, protected_json, registry_inputs
from .local_release import fetch
from .local_secrets import Vault, write_private


def retained(settings: dict, directory: Path) -> dict:
    path = directory / "installed.json"
    if not path.exists():
        raise SystemExit("No completed installation exists; run install before using lifecycle commands")
    installed = protected_json(path)
    previous = installed["configuration"]
    if any(previous[key] != settings[key] for key in settings if key != "release"):
        raise SystemExit("Retained installation configuration changed; restore its recorded configuration before proceeding")
    tools(directory / "tools")
    return installed


def maintenance(kube: Kubernetes, enabled: bool) -> None:
    kube.run("set", "env", "deployment/skywright-backend", "-n", "skywright",
             "SKYWRIGHT_MANAGED_RUN_MAINTENANCE=" + str(enabled).lower())
    kube.rollout("skywright-backend")


def idle(kube: Kubernetes) -> None:
    deadline = time.monotonic() + 60
    cursor = None
    with kube.forward("skywright-backend", 80) as endpoint:
        for _ in range(100):
            if time.monotonic() >= deadline:
                raise SystemExit("Run inspection exceeded one minute; retry once the instance is idle")
            page = api(endpoint, "/api/v1/runs?limit=50" + (
                "&after=" + urllib.parse.quote(cursor, safe="") if cursor else ""),
                timeout=min(20, deadline - time.monotonic()))
            for run in page["items"]:
                lifecycle = run.get("lifecycle") or {}
                if not lifecycle.get("terminalLatched") or lifecycle.get("state") not in {"finished", "failed", "cancelled"}:
                    raise SystemExit("A Run is active or its terminal state is unknown; finish or cancel it before maintenance")
            next_cursor = page.get("nextCursor")
            if not next_cursor:
                break
            if cursor == next_cursor:
                raise SystemExit("Run inspection did not advance; maintenance was refused")
            cursor = next_cursor
        else:
            raise SystemExit("Run inspection exceeded its page limit; maintenance was refused")
    gpu_idle(kube)


def gpu_idle(kube: Kubernetes) -> None:
    pods = kube.read("pods", "-A", "--chunk-size=100")
    for pod in pods["items"]:
        if pod.get("status", {}).get("phase") in {"Succeeded", "Failed"}:
            continue
        if any(int(c.get("resources", {}).get("requests", {}).get("amd.com/gpu", 0))
               for c in pod["spec"].get("containers", []) + pod["spec"].get("initContainers", [])):
            raise SystemExit("A GPU pod remains active; wait for its cleanup before maintenance")


def quiesce(kube: Kubernetes) -> None:
    maintenance(kube, True)
    try:
        idle(kube)
    except BaseException:
        maintenance(kube, False)
        raise
    kube.run("scale", "deployment/skywright-backend", "deployment/skywright-skypilot-api-server",
             "-n", "skywright", "--replicas=0")
    kube.run("wait", "pods", "-n", "skywright", "-l", "app.kubernetes.io/name=skywright-backend",
             "--for=delete", "--timeout=120s", timeout=130)
    kube.run("wait", "pods", "-n", "skywright", "-l", "app.kubernetes.io/name=skywright-skypilot-api-server",
             "--for=delete", "--timeout=120s", timeout=130)


def start_node(settings: dict, kube: Kubernetes) -> None:
    command(["docker", "start", settings["node"]])
    kube.run("wait", "node/" + settings["node"], "--for=condition=Ready", "--timeout=300s", timeout=310)
    kube.rollout("skywright-vault")
    Vault(kube, Path(settings["secretDirectory"])).initialize()


def resume(settings: dict, kube: Kubernetes) -> None:
    start_node(settings, kube)
    kube.run("scale", "deployment/skywright-skypilot-api-server", "deployment/skywright-backend",
             "-n", "skywright", "--replicas=1")
    kube.rollout("skywright-skypilot-api-server")
    maintenance(kube, False)
    with kube.forward("skywright-backend", 80) as endpoint:
        api(endpoint, "/actuator/health")


def checkpoint(settings: dict, directory: Path, kube: Kubernetes) -> Path:
    # A stopped-node copy includes PostgreSQL, Vault, S3, SkyPilot, writer custody and etcd.
    # Recovery is an explicit same-node restore, never an implicit database downgrade.
    if shutil.disk_usage(directory).free < 80 * 1024**3:
        raise SystemExit("A checkpoint requires at least 80 GiB free in the installation state filesystem")
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = directory / "backups" / timestamp
    backup.mkdir(mode=0o700, parents=True)
    quiesce(kube)
    command(["docker", "stop", "--time=60", settings["node"]], timeout=90)
    try:
        for name, path in (("volumes", "/var/local-path-provisioner"),
                           ("writer", "/var/lib/skywright-writer"), ("etcd", "/var/lib/etcd"),
                           ("kubernetes", "/etc/kubernetes")):
            target = backup / (name + ".tar")
            descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                process = subprocess.Popen(["docker", "cp", settings["node"] + ":" + path + "/.", "-"],
                                           stdout=output, stderr=subprocess.DEVNULL)
                try:
                    code = process.wait(timeout=1800)
                    if code:
                        raise SystemExit("Checkpoint copy failed; retain the original node and inspect the backup directory")
                    output.flush()
                    os.fsync(output.fileno())
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait()
        shutil.copytree(settings["secretDirectory"], backup / "operator-secrets", symlinks=True)
        for name in ("installed.json", "target.json", "storage.json", "demonstration.json", "configuration.json"):
            if (directory / name).exists():
                shutil.copy2(directory / name, backup / name)
        record(backup / "checkpoint.json", {"complete": True, "node": settings["node"],
               "release": protected_json(directory / "installed.json")["release"],
               "recovery": "Same retained node only. Stop services, restore all checkpoint components together, then start the recorded release."})
    finally:
        start_node(settings, kube)
    return backup


def execute(arguments) -> None:
    settings = configuration(arguments.configuration)
    with installation_lock(settings) as directory:
        installed = retained(settings, directory)
        kube = Kubernetes(settings["context"])
        action = arguments.lifecycle
        pending = directory / "pending-update.json"
        if pending.exists() and action != "update":
            raise SystemExit("An update is incomplete; retry its exact requested release or follow checkpoint recovery instructions")
        if action == "update":
            registry = registry_inputs(settings)
            release, metadata = fetch(settings, directory, registry["resolver"])
            print("Installed " + installed["version"] + "; requested " + metadata["version"], flush=True)
            if settings["release"] == installed["release"] and not pending.exists():
                print("The requested digest is already installed")
                return
            # The package supports forward changes only. Every update retains a full checkpoint.
            from .local_release import version_key
            if not pending.exists() and version_key(metadata["version"]) <= version_key(installed["version"]):
                raise SystemExit("Only a newer release can be applied; use explicit checkpoint recovery for a downgrade")
            if pending.exists():
                attempt = protected_json(pending)
                if attempt["requestedRelease"] != settings["release"]:
                    raise SystemExit("Retry the pending release before selecting a different update")
                # The complete checkpoint records terminal Runs. Admission has remained
                # frozen since then, even when the backend itself failed to start.
                gpu_idle(kube)
                kube.run("set", "env", "deployment/skywright-backend", "-n", "skywright",
                         "SKYWRIGHT_MANAGED_RUN_MAINTENANCE=true")
                kube.run("scale", "deployment/skywright-backend", "deployment/skywright-skypilot-api-server",
                         "-n", "skywright", "--replicas=0")
            else:
                backup = checkpoint(settings, directory, kube)
                record(pending, {"requestedRelease": settings["release"], "previousRelease": installed["release"],
                                 "checkpoint": str(backup)})
            try:
                apply_verified(settings, directory, release)
                pending.unlink()
                maintenance(kube, False)
            except BaseException:
                if pending.exists():
                    print("Update incomplete. Retry this exact release; do not downgrade the database. Recovery checkpoint: "
                          + protected_json(pending)["checkpoint"], flush=True)
                else:
                    print("Release installed; admission could not resume. Run start with the installed configuration.", flush=True)
                raise
        elif action == "start":
            resume(settings, kube)
            (directory / "stopped").unlink(missing_ok=True)
        elif action == "stop":
            quiesce(kube)
            (directory / "stopped").touch(mode=0o600)
            command(["docker", "stop", "--time=60", settings["node"]], timeout=90)
        elif action == "restart":
            quiesce(kube)
            command(["docker", "stop", "--time=60", settings["node"]], timeout=90)
            resume(settings, kube)
        elif action == "backup":
            backup = checkpoint(settings, directory, kube)
            resume(settings, kube)
            print("Checkpoint: " + str(backup))
