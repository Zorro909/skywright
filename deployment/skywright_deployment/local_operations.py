"""Bounded processes and private API access used by the local installer."""

from __future__ import annotations

import contextlib
import json
import os
import socket
import selectors
import tempfile
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


def command(arguments: list[str], *, data: bytes | None = None, timeout: float = 60,
            allow_failure: bool = False, environment: dict | None = None) -> subprocess.CompletedProcess:
    """Never include provider output, arguments or input in a failure message."""
    with tempfile.TemporaryFile() as source:
        source.write(data or b"")
        source.seek(0)
        try:
            process = subprocess.Popen(arguments, stdin=source, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       env=environment)
        except OSError:
            raise SystemExit("Installation process unavailable; check the current setup step") from None
        outputs = {process.stdout: bytearray(), process.stderr: bytearray()}
        deadline = time.monotonic() + timeout
        try:
            with selectors.DefaultSelector() as channels:
                for channel in outputs:
                    channels.register(channel, selectors.EVENT_READ)
                while channels.get_map():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise SystemExit("Installation process timed out; check the current setup step")
                    for key, _ in channels.select(min(1, remaining)):
                        block = os.read(key.fd, 65536)
                        if not block:
                            channels.unregister(key.fileobj)
                        else:
                            outputs[key.fileobj].extend(block)
                            if len(outputs[key.fileobj]) > 4 * 1024 * 1024:
                                raise SystemExit("Installation process exceeded its output limit")
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
            result = subprocess.CompletedProcess(arguments, process.returncode,
                bytes(outputs[process.stdout]), bytes(outputs[process.stderr]))
            if not allow_failure and result.returncode:
                raise SystemExit("Installation process failed; check the current setup step")
            return result
        except subprocess.TimeoutExpired:
            raise SystemExit("Installation process timed out; check the current setup step") from None
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            process.stdout.close()
            process.stderr.close()


class Kubernetes:
    def __init__(self, context: str):
        self.prefix = [os.environ.get("SKYWRIGHT_KUBECTL", "kubectl"),
                       "--context", context, "--request-timeout=20s"]

    def run(self, *arguments: str, data: bytes | None = None, timeout: float = 60,
            allow_failure: bool = False) -> subprocess.CompletedProcess:
        return command([*self.prefix, *arguments], data=data, timeout=timeout, allow_failure=allow_failure)

    def read(self, *arguments: str) -> dict:
        try:
            return json.loads(self.run("get", *arguments, "-o", "json").stdout)
        except (ValueError, TypeError):
            raise SystemExit("Kubernetes returned an invalid installation observation") from None

    def apply(self, *objects: dict) -> None:
        if any(item.get("kind") == "Secret" and ("data" in item or "stringData" in item) for item in objects):
            raise SystemExit("Secret values must enter Kubernetes through protected files")
        self.run("apply", "-f", "-", data=json.dumps(
            {"apiVersion": "v1", "kind": "List", "items": objects}).encode())

    def secret(self, namespace: str, name: str, inputs: dict[str, Path], secret_type: str = "Opaque") -> None:
        existing = self.run("get", "secret", name, "-n", namespace, "--ignore-not-found", "-o", "name")
        if existing.stdout.strip():
            return
        self.run("create", "secret", "generic", name, "-n", namespace, "--type=" + secret_type,
                 *("--from-file=" + key + "=" + str(path) for key, path in inputs.items()))

    def rollout(self, name: str) -> None:
        self.run("rollout", "status", "deployment/" + name, "-n", "skywright",
                 "--timeout=300s", timeout=310)

    @contextlib.contextmanager
    def forward(self, service: str, port: int):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            local_port = reserved.getsockname()[1]
        process = subprocess.Popen([*self.prefix, "-n", "skywright", "port-forward",
                                    "--address=127.0.0.1", "service/" + service,
                                    str(local_port) + ":" + str(port)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 15
            while True:
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise SystemExit("Private installation API connection unavailable")
                try:
                    with socket.create_connection(("127.0.0.1", local_port), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.1)
            yield "http://127.0.0.1:" + str(local_port)
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def api(endpoint: str, path: str, method: str = "GET", value: dict | None = None,
        headers: dict | None = None, timeout: float = 20) -> dict | list:
    request = urllib.request.Request(endpoint + path, method=method,
        data=None if value is None else json.dumps(value).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(1024 * 1024 + 1)
            if len(body) > 1024 * 1024:
                raise ValueError
            return json.loads(body) if body else {}
    except (urllib.error.URLError, ValueError, TimeoutError):
        raise SystemExit("Installation API request failed; check the current setup step") from None
