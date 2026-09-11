"""Keep private access and Vault renewal alive under the operator's user service."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

from .local_host import inventory, tools
from .local_operations import Kubernetes, command
from .local_package import configuration, installation_lock, protected_json
from .local_secrets import Vault


def observe(kube: Kubernetes, settings: dict) -> None:
    devices = inventory()
    idle = len(devices) == 2 and all(d["busyPercent"] < 5 and d["usedMemoryBytes"] < 512 * 1024**2 for d in devices)
    kube.run("annotate", "node", settings["node"], "skywright.io/host-gpu-observed-at=" + str(int(time.time())),
             "skywright.io/host-gpus-idle=" + str(idle).lower(), "--overwrite")


def renew(kube: Kubernetes, settings: dict) -> None:
    root = Path(settings["secretDirectory"])
    vault = Vault(kube, root)
    vault.initialize()
    for consumer in ("backend", "skypilot"):
        vault.token = protected_json(root / ("skywright-" + consumer + "-vault.json"))["token"]
        vault.run("token", "renew", "-self")


def install(settings: dict, directory: Path, source: Path) -> None:
    if command(["loginctl", "show-user", str(os.getuid()), "-p", "Linger", "--value"]).stdout.strip() != b"yes":
        raise SystemExit("Enable lingering for the operator user before continuous installation: loginctl enable-linger")
    # All values are operator-owned paths; reject systemd expansions and line breaks.
    paths = [str(source / "scripts/deploy"), str(directory / "configuration.json")]
    if any(any(character in value for character in '\n\r%"\\') for value in paths):
        raise SystemExit("Service installation paths contain unsupported systemd characters")
    unit = Path.home() / ".config/systemd/user" / (settings["context"] + ".service")
    unit.parent.mkdir(parents=True, exist_ok=True)
    unit.write_text('''[Unit]
Description=Skywright private local instance maintenance
After=default.target

[Service]
Type=simple
ExecStart="''' + paths[0] + '''" maintain --configuration "''' + paths[1] + '''"
Restart=always
RestartSec=15
MemoryMax=256M
CPUQuota=25%
TasksMax=32
UMask=0077
StandardOutput=null
StandardError=journal

[Install]
WantedBy=default.target
''')
    command(["systemctl", "--user", "daemon-reload"])
    command(["systemctl", "--user", "enable", "--now", unit.name])
    command(["systemctl", "--user", "restart", unit.name])


def run(arguments) -> None:
    settings = configuration(arguments.configuration)
    directory = Path(settings["stateDirectory"])
    tools(directory / "tools")
    kube = Kubernetes(settings["context"])
    forward = None
    next_renewal = 0
    try:
        while True:
            try:
                with installation_lock(settings, wait_seconds=0):
                    if (directory / "stopped").exists() or (directory / "pending-update.json").exists():
                        if forward is not None:
                            forward.terminate()
                            forward.wait(timeout=5)
                            forward = None
                    else:
                        if time.monotonic() >= next_renewal:
                            renew(kube, settings)
                            next_renewal = time.monotonic() + 600
                        observe(kube, settings)
                        if forward is None or forward.poll() is not None:
                            forward = subprocess.Popen([*kube.prefix, "-n", "skywright", "port-forward",
                                "--address=127.0.0.1", "service/skywright-backend", "8080:80"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except (SystemExit, OSError, ValueError, subprocess.TimeoutExpired):
                # Stale host observation fails admission closed. Retry without writing provider data to logs.
                pass
            time.sleep(10)
    finally:
        if forward is not None:
            forward.terminate()
            try:
                forward.wait(timeout=5)
            except subprocess.TimeoutExpired:
                forward.kill()
                forward.wait()
