"""Bounded target-side delivery of immutable, Run-owned GHCR pull Secrets."""
from __future__ import annotations

import base64
import json
import os
import re
import stat
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
import uuid

MAX_BODY = 1024 * 1024
LABEL = "skywright.dev/run-id"
BINDING = "skywright.dev/pull-binding"
REVISION = "skywright.dev/pull-revision"


def projection():
    filename = os.environ.get("SKYWRIGHT_KUBECONFIG")
    if not filename:
        raise ValueError("Kubernetes projection unavailable")
    fd = os.open(filename, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(source.fileno())
        if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o400
                or info.st_uid != os.geteuid() or info.st_size > MAX_BODY):
            raise ValueError("Invalid Kubernetes projection")
        config = json.loads(source.read(MAX_BODY + 1))
    if (config.get("apiVersion") != "v1" or config.get("kind") != "Config"
            or any(len(config.get(key, [])) != 1 for key in ("contexts", "clusters", "users"))):
        raise ValueError("Invalid Kubernetes projection")
    user, cluster, context = config["users"][0], config["clusters"][0], config["contexts"][0]
    if (set(user["user"]) != {"token"} or not isinstance(user["user"]["token"], str)
            or not user["user"]["token"]
            or set(cluster["cluster"]) != {"server", "certificate-authority-data"}
            or not cluster["cluster"]["server"].startswith("https://")
            or context["context"].get("user") != user["name"]
            or context["context"].get("cluster") != cluster["name"]):
        raise ValueError("Invalid Kubernetes projection")
    return config


def target(context_name):
    config = projection()
    context = config["contexts"][0]
    if context["name"] != context_name:
        raise ValueError("Unqualified Kubernetes context")
    namespace = context["context"].get("namespace", "default")
    if not isinstance(namespace, str) or not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", namespace):
        raise ValueError("Invalid namespace")
    return config, namespace


class KubernetesSecrets:
    def __init__(self, config, context):
        from kubernetes import client, config as kube_config
        self.api_client = kube_config.new_client_from_config_dict(config, context=context)
        self.api = client.CoreV1Api(self.api_client)

    def read(self, namespace, name):
        from kubernetes.client.exceptions import ApiException
        try:
            return self.api.read_namespaced_secret(name, namespace, _request_timeout=(2, 3)).to_dict()
        except ApiException as error:
            if error.status == 404:
                return None
            raise

    def create(self, namespace, secret):
        from kubernetes.client.exceptions import ApiException
        try:
            self.api.create_namespaced_secret(namespace, secret, _request_timeout=(2, 3))
        except ApiException as error:
            if error.status != 409:
                raise

    def close(self):
        self.api_client.close()


def identity(query):
    if set(query) != {"context", "namespace", "run", "binding", "revision"}:
        raise ValueError("Invalid pull identity")
    values = {key: item[0] for key, item in query.items() if len(item) == 1}
    if len(values) != 5:
        raise ValueError("Invalid pull identity")
    for key in ("run", "binding"):
        if str(uuid.UUID(values[key])) != values[key]:
            raise ValueError("Invalid pull identity")
    if not re.fullmatch(r"[1-9][0-9]{0,18}", values["revision"]):
        raise ValueError("Invalid pull revision")
    return values


def matches(secret, values):
    metadata = secret.get("metadata") or {}
    annotations = metadata.get("annotations") or {}
    data = secret.get("data") or {}
    return (secret.get("immutable") is True and secret.get("type") == "kubernetes.io/dockerconfigjson"
            and metadata.get("name") == "skywright-pull-" + values["run"]
            and metadata.get("namespace") == values["namespace"]
            and (metadata.get("labels") or {}).get(LABEL) == values["run"]
            and annotations.get(BINDING) == values["binding"]
            and annotations.get(REVISION) == values["revision"]
            and set(data) == {".dockerconfigjson"} and bool(data[".dockerconfigjson"]))


def manifest(values, content):
    value = json.loads(content)
    if (not isinstance(value, dict) or set(value) != {"auths"}
            or not isinstance(value["auths"], dict) or set(value["auths"]) != {"ghcr.io"}
            or set(value["auths"]["ghcr.io"]) != {"auth"}):
        raise ValueError("Invalid GHCR projection")
    decoded = base64.b64decode(value["auths"]["ghcr.io"]["auth"], validate=True).decode("utf-8")
    if ":" not in decoded or not all(decoded.split(":", 1)):
        raise ValueError("Invalid GHCR projection")
    return {"apiVersion": "v1", "kind": "Secret", "immutable": True,
            "type": "kubernetes.io/dockerconfigjson",
            "metadata": {"name": "skywright-pull-" + values["run"], "namespace": values["namespace"],
                         "labels": {LABEL: values["run"]},
                         "annotations": {BINDING: values["binding"], REVISION: values["revision"]}},
            "data": {".dockerconfigjson": base64.b64encode(content).decode("ascii")}}


class Handler(BaseHTTPRequestHandler):
    # Neither access logs nor provider exceptions may include credential material.
    def log_message(self, *_args):
        pass

    def setup(self):
        self.request.settimeout(3)
        super().setup()

    def do_GET(self):
        self.handle_request(False)

    def do_PUT(self):
        self.handle_request(True)

    def respond(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def handle_request(self, install):
        try:
            parsed = urlsplit(self.path)
            query = parse_qs(parsed.query, strict_parsing=True)
            if parsed.path == "/health" and not install:
                self.respond(200, {"ready": True})
                return
            if parsed.path == "/namespace" and not install and set(query) == {"context"} and len(query["context"]) == 1:
                _, namespace = target(query["context"][0])
                self.respond(200, {"namespace": namespace})
                return
            if parsed.path != "/pull":
                self.respond(404, {})
                return
            values = identity(query)
            config, namespace = target(values["context"])
            if namespace != values["namespace"]:
                raise ValueError("Pinned namespace differs from target")
            expected = None
            if install:
                if self.headers.get("Transfer-Encoding") or len(self.headers.get_all("Content-Length", [])) != 1:
                    raise ValueError("Content length required")
                length = int(self.headers["Content-Length"])
                if not 0 < length <= MAX_BODY:
                    raise ValueError("Invalid projection size")
                content = self.rfile.read(length)
                if len(content) != length:
                    raise ValueError("Incomplete projection")
                expected = manifest(values, content)
            api = self.server.secrets_factory(config, values["context"])
            try:
                if expected is not None:
                    api.create(namespace, expected)
                found = api.read(namespace, "skywright-pull-" + values["run"])
                if found is None:
                    self.respond(404, {})
                elif not matches(found, values) or expected is not None and found["data"] != expected["data"]:
                    self.respond(409, {"code": "RUNTIME_PULL_IDENTITY_CONFLICT"})
                else:
                    self.respond(200, {"installed": True})
            finally:
                api.close()
        except Exception:
            self.respond(503, {"code": "RUNTIME_PULL_PROJECTION_UNAVAILABLE"})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, secrets_factory=KubernetesSecrets):
        self.secrets_factory = secrets_factory
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, *_args):
        pass


if __name__ == "__main__":
    Server(("0.0.0.0", 46581)).serve_forever()
