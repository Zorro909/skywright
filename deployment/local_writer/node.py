"""Qualified containerd and Linux observations; never stops or fences a writer."""

from __future__ import annotations

import json
import os
import re
import selectors
import ssl
import subprocess
import time
import urllib.request
from pathlib import Path

from .common import INSPECT_LIMIT, Pending, Uncertain, identifier, identity

CONTAINER = re.compile(r"cri-containerd-([0-9a-f]{64})\.scope")
ESCAPE_CAPABILITIES = {
    "CAP_SYS_ADMIN",
    "CAP_SYS_PTRACE",
    "CAP_SYS_MODULE",
    "CAP_SYS_RAWIO",
    "CAP_SYS_BOOT",
    "CAP_DAC_READ_SEARCH",
    "CAP_BPF",
    "CAP_PERFMON",
}


def runtime_output(arguments):
    """Bound both runtime latency and output without retaining inspection secrets."""
    with subprocess.Popen(
        [
            "crictl",
            "--runtime-endpoint",
            "unix:///run/containerd/containerd.sock",
            *arguments,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    ) as process:
        output = bytearray()
        deadline = time.monotonic() + 3
        assert process.stdout is not None
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0 or not selector.select(remaining):
                        raise Uncertain()
                    data = os.read(process.stdout.fileno(), 65536)
                    if not data:
                        break
                    output.extend(data)
                    if len(output) > INSPECT_LIMIT:
                        raise Uncertain()
            if process.wait(timeout=max(0.01, deadline - time.monotonic())):
                raise Uncertain()
            return bytes(output)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


def runtime_json(arguments):
    return json.loads(runtime_output(arguments))


def validate_mounts(pod, runtime, pod_uid, sandbox_id):
    container = pod["containers"][0]
    if (
        pod.get("hostNetwork")
        or pod.get("hostIPC")
        or container.get("restartPolicy")
        or container.get("restartPolicyRules")
        or container.get("volumeDevices")
    ):
        raise Uncertain()
    pod_root = "/var/lib/kubelet/pods/" + pod_uid
    plugins = {
        "emptyDir": "empty-dir",
        "secret": "secret",
        "configMap": "configmap",
        "projected": "projected",
    }
    volumes = {}
    for volume in pod.get("volumes", []):
        kinds = set(volume) - {"name"}
        if len(kinds) != 1 or volume["name"] in volumes:
            raise Uncertain()
        kind = next(iter(kinds))
        if kind == "hostPath":
            if (
                volume["name"] != "skywright-writer"
                or volume[kind]["path"] != "/var/lib/skywright-writer/socket"
            ):
                raise Uncertain()
            volumes[volume["name"]] = (volume[kind]["path"], True)
        elif kind in plugins:
            volumes[volume["name"]] = (
                pod_root
                + "/volumes/kubernetes.io~"
                + plugins[kind]
                + "/"
                + volume["name"],
                kind != "emptyDir",
            )
        else:
            raise Uncertain()
    expected = {
        "/etc/hosts": (pod_root + "/etc-hosts", False),
        "/etc/hostname": (
            "/var/lib/containerd/io.containerd.grpc.v1.cri/sandboxes/"
            + sandbox_id
            + "/hostname",
            True,
        ),
        "/etc/resolv.conf": (
            "/var/lib/containerd/io.containerd.grpc.v1.cri/sandboxes/"
            + sandbox_id
            + "/resolv.conf",
            True,
        ),
        "/dev/shm": (
            "/run/containerd/io.containerd.grpc.v1.cri/sandboxes/"
            + sandbox_id
            + "/shm",
            False,
        ),
    }
    for mount in container.get("volumeMounts", []):
        destination = mount["mountPath"]
        if (
            mount.get("subPath")
            or mount.get("subPathExpr")
            or mount.get("mountPropagation", "None") != "None"
            or destination in ("/", "/proc", "/sys", "/sys/fs/cgroup", "/run", "/dev")
            or destination.startswith(("/proc/", "/sys/"))
            or destination in expected
            and destination != "/dev/shm"
        ):
            raise Uncertain()
        source, readonly = volumes[mount["name"]]
        readonly = readonly or mount.get("readOnly", False)
        if mount["name"] == "skywright-writer" and (
            destination != "/run/skywright-writer" or not mount.get("readOnly")
        ):
            raise Uncertain()
        expected[destination] = (source, readonly)
    if expected.get("/run/skywright-writer") != (
        "/var/lib/skywright-writer/socket",
        True,
    ):
        raise Uncertain()
    termination = container.get("terminationMessagePath", "/dev/termination-log")
    standard = {
        "/proc": ("proc", "proc"),
        "/dev": ("tmpfs", "tmpfs"),
        "/dev/pts": ("devpts", "devpts"),
        "/dev/mqueue": ("mqueue", "mqueue"),
        "/sys": ("sysfs", "sysfs"),
        "/sys/fs/cgroup": ("cgroup", "cgroup"),
        "/run": ("tmpfs", "tmpfs"),
        "/dev/shm": ("tmpfs", "shm"),
    }
    seen = set()
    for mount in runtime["mounts"]:
        destination = mount["destination"]
        if (
            destination in seen
            or not destination.startswith("/")
            or str(Path(destination)) != destination
            or ".." in Path(destination).parts
        ):
            raise Uncertain()
        seen.add(destination)
        options = mount.get("options", [])
        if any(value in options for value in ("shared", "rshared", "slave", "rslave")):
            raise Uncertain()
        source = mount["source"]
        if mount["type"] == "bind":
            if (
                not source.startswith("/")
                or str(Path(source)) != source
                or ".." in Path(source).parts
            ):
                raise Uncertain()
            if destination == termination and re.fullmatch(
                re.escape(pod_root + "/containers/" + container["name"] + "/")
                + "[0-9a-f]{8}",
                source,
            ):
                continue
            if destination not in expected:
                raise Uncertain()
            wanted, readonly = expected[destination]
            if source != wanted or readonly and "ro" not in options:
                raise Uncertain()
        elif (
            standard.get(destination) != (mount["type"], source)
            or destination.startswith("/sys")
            and "ro" not in options
        ):
            raise Uncertain()
    if "/run/skywright-writer" not in seen:
        raise Uncertain()


class Node:
    def __init__(self, node_name, node_uid, namespace):
        self.node_name, self.node_uid, self.namespace = node_name, node_uid, namespace
        self.proc = Path("/host/proc")
        self.cgroups = Path("/host/cgroup")
        self.boot_id = (self.proc / "sys/kernel/random/boot_id").read_text().strip()
        identifier(self.boot_id)
        self.cgroup_root = identity(self.cgroups)
        if not (self.cgroups / "cgroup.controllers").is_file():
            raise Uncertain()
        version = dict(
            line.split(":", 1)
            for line in runtime_output(["version"]).decode().splitlines()
        )
        if (
            version.get("RuntimeName", "").strip() != "containerd"
            or version.get("RuntimeVersion", "").strip() != "v2.3.1"
        ):
            raise Uncertain()
        self.runtime_version = "containerd/2.3.1"
        if self.api("/api/v1/nodes/" + node_name)["metadata"]["uid"] != node_uid:
            raise Uncertain()

    def api(self, path):
        credentials = Path("/var/run/secrets/kubernetes.io/serviceaccount")
        tls = ssl.create_default_context(cafile=str(credentials / "ca.crt"))
        request = urllib.request.Request(
            "https://kubernetes.default.svc" + path,
            headers={
                "Authorization": "Bearer " + (credentials / "token").read_text().strip()
            },
        )

        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None

        opener = urllib.request.build_opener(
            NoRedirect(), urllib.request.HTTPSHandler(context=tls)
        )
        with opener.open(request, timeout=3) as response:
            data = response.read(INSPECT_LIMIT + 1)
            if len(data) > INSPECT_LIMIT:
                raise Uncertain()
            return json.loads(data)

    def process(self, pid):
        directory = self.proc / str(pid)
        raw = (directory / "stat").read_text()
        start = raw.rsplit(")", 1)[1].split()[19]
        group = (directory / "cgroup").read_text().strip()
        if not group.startswith("0::/") or "\n" in group:
            raise Uncertain()
        group = group[3:]
        parts = Path(group).parts[1:]
        if not parts or any(part in (".", "..") for part in parts):
            raise Uncertain()
        match = CONTAINER.fullmatch(parts[-1])
        if match is None:
            raise Uncertain()
        return {
            "pid": pid,
            "start_ticks": start,
            "pid_namespace": os.readlink(directory / "ns/pid"),
            "cgroup": group,
            "container_id": match[1],
        }

    def cgroup_chain(self, group):
        result = []
        path = self.cgroups
        for part in Path(group).parts[1:]:
            path = path / part
            result.append([part, identity(path)])
        return result

    def peer(self, pid, run_id):
        process = self.process(pid)
        inspected = runtime_json(["inspect", process["container_id"]])
        status, info = inspected["status"], inspected["info"]
        if (
            status["id"] != process["container_id"]
            or status["state"] != "CONTAINER_RUNNING"
            or info.get("removing")
        ):
            raise Uncertain()
        labels = status["labels"]
        if labels["io.kubernetes.pod.namespace"] != self.namespace:
            raise Uncertain()
        pod = self.api(
            "/api/v1/namespaces/"
            + self.namespace
            + "/pods/"
            + labels["io.kubernetes.pod.name"]
        )
        metadata, spec = pod["metadata"], pod["spec"]
        if (
            metadata["uid"] != labels["io.kubernetes.pod.uid"]
            or metadata.get("labels", {}).get("skywright.io/run-id") != run_id
            or spec["nodeName"] != self.node_name
            or spec.get("hostPID")
            or spec.get("shareProcessNamespace")
            or spec["restartPolicy"] != "Never"
            or spec.get("ephemeralContainers")
            or len(spec["containers"]) != 1
            or spec.get("initContainers")
        ):
            raise Uncertain()
        statuses = pod.get("status", {}).get("containerStatuses", [])
        if not any(
            item.get("containerID") == "containerd://" + status["id"]
            and item["name"] == labels["io.kubernetes.container.name"]
            and item.get("restartCount") == 0
            for item in statuses
        ):
            raise Uncertain()
        runtime_spec = info["runtimeSpec"]
        pid_namespaces = [
            value
            for value in runtime_spec["linux"]["namespaces"]
            if value["type"] == "pid"
        ]
        if pid_namespaces != [{"type": "pid"}]:
            raise Uncertain()
        capabilities = runtime_spec["process"].get("capabilities", {})
        if any(
            ESCAPE_CAPABILITIES.intersection(values) for values in capabilities.values()
        ):
            raise Uncertain()
        if spec["containers"][0].get("securityContext", {}).get("privileged"):
            raise Uncertain()
        validate_mounts(spec, runtime_spec, metadata["uid"], info["sandboxID"])
        cgroup_mounts = [
            mount for mount in runtime_spec["mounts"] if mount["type"] == "cgroup"
        ]
        if not cgroup_mounts or any(
            "ro" not in mount.get("options", []) for mount in cgroup_mounts
        ):
            raise Uncertain()
        init = self.process(info["pid"])
        if (
            init["container_id"] != process["container_id"]
            or init["pid_namespace"] != process["pid_namespace"]
            or self.process(pid) != process
        ):
            raise Uncertain()
        return {
            "boot_id": self.boot_id,
            "runtime": self.runtime_version,
            "container_id": status["id"],
            "container_created_at": status["createdAt"],
            "container_started_at": status["startedAt"],
            "image": status["imageRef"],
            "sandbox_id": info["sandboxID"],
            "pod_uid": metadata["uid"],
            "process": process,
            "init": init,
            "cgroup_root": self.cgroup_root,
            "cgroup_chain": self.cgroup_chain(process["cgroup"]),
        }

    def stopped(self, record):
        # Kernel reboot evidence is deliberately not inferred by this first implementation.
        if (
            record["boot_id"] != self.boot_id
            or identity(self.cgroups) != record["cgroup_root"]
        ):
            raise Uncertain()
        inspected = runtime_json(["inspect", record["container_id"]])
        status = inspected["status"]
        if (
            status["id"] != record["container_id"]
            or status["createdAt"] != record["container_created_at"]
            or status["startedAt"] != record["container_started_at"]
        ):
            raise Uncertain()
        if status["state"] in ("CONTAINER_RUNNING", "CONTAINER_CREATED"):
            raise Pending()
        if status["state"] != "CONTAINER_EXITED":
            raise Uncertain()
        path = self.cgroups
        for part, expected in record["cgroup_chain"]:
            path = path / part
            try:
                observed = identity(path)
            except FileNotFoundError:
                return {
                    "kind": "container-exited-cgroup-removed",
                    "container_id": status["id"],
                    "finished_at": status["finishedAt"],
                    "boot_id": self.boot_id,
                }
            if observed != expected:
                raise Uncertain()
        events = (path / "cgroup.events").read_text()
        if "populated 0" not in events.splitlines():
            raise Pending()
        return {
            "kind": "container-exited-cgroup-empty",
            "container_id": status["id"],
            "finished_at": status["finishedAt"],
            "boot_id": self.boot_id,
        }
