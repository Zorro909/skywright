"""Prepare the supported Docker/kind AMD host without administrator package changes."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import shutil
import time
import urllib.request
from pathlib import Path

from .local_operations import Kubernetes, command
from .local_resources import DEVICE_PLUGIN, resource

NODE_IMAGE = "docker.io/kindest/node@sha256:3489c7674813ba5d8b1a9977baea8a6e553784dab7b84759d1014dbd78f7ebd5"
CALICO_SOURCE = "https://raw.githubusercontent.com/projectcalico/calico/db255c554b929afd73552fd3ac81d691107a1607/manifests/calico.yaml"
CALICO_SHA256 = "a8c828a06a87c629a282ebbc424895b77f3a030251993e41ea400a743675bb02"
CALICO_IMAGES = {
    "cni": "0ef740bc587f25565905adf1d1f61a7faff0d571c449c6bdd789feed743d3ef7",
    "node": "99b03fe91e8bfbcb153ae65ef4b701b24ce541ffdd74ff314eb041096008f7fd",
    "kube-controllers": "7870b67ebb13fabc3005252b44fe6e78b21635649bd3072b80afa1684b6565d0",
}
TOOLS = {
    "kind": ("https://github.com/kubernetes-sigs/kind/releases/download/v0.32.0/kind-linux-amd64",
             "50030de23cf40a18505f20426f6a8506bedf13c6e509244bd1fa9463721b0f54"),
    "kubectl": ("https://dl.k8s.io/release/v1.36.3/bin/linux/amd64/kubectl",
                "ebbd080e7c2e275093b55915722043257eb24004363e20acb3c4d71919f88336"),
}


def download(url: str, digest: str, maximum: int) -> bytes:
    try:
        with urllib.request.urlopen(url, timeout=30) as source:
            value = source.read(maximum + 1)
        if len(value) > maximum or hashlib.sha256(value).hexdigest() != digest:
            raise ValueError
        return value
    except (OSError, ValueError):
        raise SystemExit("Pinned prerequisite download failed verification") from None


def inventory() -> list[dict]:
    devices = []
    for card in sorted(Path("/sys/class/drm").glob("card[0-9]*")):
        path = card / "device"
        if not re.fullmatch(r"card[0-9]+", card.name) or not path.exists():
            continue
        try:
            if (path / "vendor").read_text().strip() != "0x1002":
                continue
            devices.append({
                "device": (path / "device").read_text().strip(),
                "memoryBytes": int((path / "mem_info_vram_total").read_text()),
                "usedMemoryBytes": int((path / "mem_info_vram_used").read_text()),
                "busyPercent": int((path / "gpu_busy_percent").read_text()),
            })
        except (OSError, ValueError):
            raise SystemExit("AMD GPU inventory is unavailable from the kernel driver") from None
    return devices


def tools(directory: Path) -> None:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise SystemExit("The local package supports Linux x86_64 hosts")
    if not shutil.which("docker") or not shutil.which("openssl"):
        raise SystemExit("Install Docker Engine and OpenSSL, and grant the operator Docker access before setup")
    command(["docker", "info", "--format", "{{.ServerVersion}}"])
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    for name, (url, digest) in TOOLS.items():
        path = directory / name
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            temporary = directory / (name + ".partial")
            temporary.write_bytes(download(url, digest, 100 * 1024 * 1024))
            temporary.chmod(0o700)
            temporary.replace(path)
    os.environ["PATH"] = str(directory) + os.pathsep + os.environ["PATH"]
    os.environ["SKYWRIGHT_KUBECTL"] = str(directory / "kubectl")
    os.environ["KIND_EXPERIMENTAL_PROVIDER"] = "docker"


def target(settings: dict, directory: Path) -> Kubernetes:
    devices = inventory()
    if (len(devices) != 2 or any(d["device"] != "0x747e" for d in devices)
            or any(d["memoryBytes"] != settings["gpuMemoryBytes"] for d in devices)
            or not Path("/dev/kfd").exists() or not Path("/dev/dri").is_dir()):
        raise SystemExit("The supported host requires two RX 7800 XT devices exposed by the AMD kernel driver")
    tools(directory / "tools")
    cluster = settings["context"].removeprefix("kind-")
    existing = command(["kind", "get", "clusters"]).stdout.decode().splitlines()
    kube = Kubernetes(settings["context"])
    ownership = directory / "target.json"
    if cluster not in existing and ownership.exists():
        raise SystemExit("The retained target disappeared; restore its checkpoint instead of creating a new writer identity")
    if cluster not in existing:
        configuration = {
            "kind": "Cluster", "apiVersion": "kind.x-k8s.io/v1alpha4",
            "networking": {"apiServerAddress": "127.0.0.1", "disableDefaultCNI": True,
                           "podSubnet": "10.244.0.0/16"},
            "nodes": [{"role": "control-plane", "image": NODE_IMAGE,
                       "extraMounts": [{"hostPath": value, "containerPath": value}
                                       for value in ("/dev/dri", "/dev/kfd")]}],
        }
        command(["kind", "create", "cluster", "--name", cluster, "--config=-"],
                data=json.dumps(configuration).encode(), timeout=360)
        manifest = download(CALICO_SOURCE, CALICO_SHA256, 1024 * 1024).decode()
        for name, digest in CALICO_IMAGES.items():
            manifest = manifest.replace("quay.io/calico/" + name + ":v3.32.2",
                                        "quay.io/calico/" + name + "@sha256:" + digest)
        manifest = manifest.replace('# - name: CALICO_IPV4POOL_CIDR\n            #   value: "192.168.0.0/16"',
                                    '- name: CALICO_IPV4POOL_CIDR\n              value: "10.244.0.0/16"')
        kube.run("apply", "--server-side", "-f", "-", data=manifest.encode(), timeout=120)
    else:
        # Do not silently replace another network implementation in a retained cluster.
        calico = kube.run("get", "daemonset", "calico-node", "-n", "kube-system",
                          "--ignore-not-found", "-o", "name")
        if not calico.stdout.strip():
            raise SystemExit("Existing context lacks the packaged Calico policy boundary; select a fresh dedicated kind context")
    observed_uid = kube.read("node", settings["node"])["metadata"]["uid"]
    if ownership.exists():
        recorded = json.loads(ownership.read_text())
        if recorded != {"context": settings["context"], "nodeUid": observed_uid}:
            raise SystemExit("The Kubernetes target identity changed; restore the recorded target and writer custody")
    else:
        ownership.write_text(json.dumps({"context": settings["context"], "nodeUid": observed_uid}))
    command(["docker", "update", "--cpus=12", "--memory=32g", "--memory-swap=32g",
             "--restart=unless-stopped", settings["node"]])
    kube.run("wait", "node/" + settings["node"], "--for=condition=Ready", "--timeout=300s", timeout=310)
    kube.run("label", "node", settings["node"], "skypilot.co/accelerator=rx7800xt", "--overwrite")
    kube.run("label", "node", settings["node"], "skywright.io/host-gpu-observation=required", "--overwrite")
    kube.apply(resource("DaemonSet", "skywright-amdgpu", namespace="kube-system", spec={
        "selector": {"matchLabels": {"app": "skywright-amdgpu"}},
        "template": {"metadata": {"labels": {"app": "skywright-amdgpu"}}, "spec": {
            "automountServiceAccountToken": False,
            "nodeSelector": {"skypilot.co/accelerator": "rx7800xt"},
            "tolerations": [{"operator": "Exists"}],
            "containers": [{"name": "device-plugin", "image": DEVICE_PLUGIN,
                "securityContext": {"privileged": True, "capabilities": {"drop": ["ALL"]}},
                "resources": {"requests": {"cpu": "100m", "memory": "64Mi"},
                              "limits": {"cpu": "1", "memory": "128Mi"}},
                "volumeMounts": [{"name": "plugins", "mountPath": "/var/lib/kubelet/device-plugins"},
                                 {"name": "sys", "mountPath": "/sys", "readOnly": True}]}],
            "volumes": [{"name": "plugins", "hostPath": {"path": "/var/lib/kubelet/device-plugins"}},
                        {"name": "sys", "hostPath": {"path": "/sys"}}]}}}))
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        node = kube.read("node", settings["node"])
        if node["status"].get("allocatable", {}).get("amd.com/gpu") == "2":
            return kube
        time.sleep(2)
    raise SystemExit("AMD device plugin did not report both GPUs within two minutes")
