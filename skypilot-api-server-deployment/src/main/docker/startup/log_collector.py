"""Skywright's read-only LOCAL log collector; the SkyPilot distribution is unchanged."""
from __future__ import annotations

import base64
import hashlib
import re
from contextlib import closing
import json
import os
from pathlib import Path
import threading
import time
import uuid
import sys
import subprocess

# Only Skywright-owned sibling modules are added under isolated Python.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from archive_read import MAX_BYTES, Unavailable, integer, regular_page

MAX_REQUEST = 8192
MAX_RESPONSE = 2 * 1024 * 1024
ROOT = Path("/var/lib/skypilot/sky_logs")
NODE_PROGRAM = Path(__file__).with_name("archive_read.py").read_text()


def cloud_name(name, maximum, user_hash=""):
    """Pinned SkyPilot 0.13 naming protocol; no SkyPilot import or state access."""
    transformed = re.sub(r"[._]", "-", name).lower()
    suffix = "-" + user_hash if user_hash else ""
    if len(transformed) <= maximum - len(suffix):
        return transformed + suffix
    number = int(hashlib.md5(name.encode(), usedforsecurity=False).hexdigest(), 16)
    encoded = ""
    while number:
        number, remainder = divmod(number, 36)
        encoded = "0123456789abcdefghijklmnopqrstuvwxyz"[remainder] + encoded
    return transformed[:maximum - len(suffix) - 3].rstrip("-") + "-" + (encoded or "0")[:2] + suffix


def bounded_json(response):
    try:
        body = bytearray()
        deadline = time.monotonic() + 2
        while True:
            if time.monotonic() >= deadline:
                raise Unavailable("SOURCE_DEADLINE")
            chunk = response.read1(16384, decode_content=True)
            if not chunk:
                break
            if len(body) + len(chunk) > 256 * 1024:
                raise Unavailable("SOURCE_RESPONSE_TOO_LARGE")
            body.extend(chunk)
        return json.loads(body)
    finally:
        response.close()
        response.release_conn()


def managed_cluster_name(task_name, job_id):
    # SkyPilot 0.13 jobs.constants.JOBS_CLUSTER_NAME_PREFIX_LENGTH.
    return cloud_name(task_name, 25) + f"-{job_id}"


class Sources:
    def __init__(self, connection_uri=None, root=ROOT):
        self.connection_uri = connection_uri or os.environ.get("SKYPILOT_DB_CONNECTION_URI")
        self.root = root

    def _connection(self):
        import psycopg2
        return psycopg2.connect(self.connection_uri, connect_timeout=2,
            options="-c default_transaction_read_only=on -c statement_timeout=1000 -c lock_timeout=250")

    def job(self, run_id, job_id):
        name = "skywright-" + str(uuid.UUID(run_id))
        with closing(self._connection()) as connection, connection.cursor() as cursor:
            cursor.execute("""
                SELECT j.spot_job_id,j.name,j.schedule_state,j.user_hash,j.workspace,s.task_id,s.task_name,
                       s.status,s.recovery_count,s.local_log_file,s.logs_cleaned_at,s.end_at
                FROM job_info j JOIN spot s ON s.spot_job_id=j.spot_job_id
                WHERE (%s::bigint IS NULL OR j.spot_job_id=%s) AND j.name=%s AND length(j.name)<=96
                  AND length(s.task_name)<=96 AND coalesce(length(s.local_log_file),0)<=4096
                  AND coalesce(length(j.user_hash),0)<=256 AND coalesce(length(j.workspace),0)<=256
                ORDER BY s.task_id LIMIT 2
                """, (job_id, job_id, name))
            rows = cursor.fetchall()
        if len(rows) != 1 or rows[0][5] != 0 or rows[0][6] != name:
            raise Unavailable("SOURCE_IDENTITY_UNCONFIRMED")
        keys = ("jobId", "name", "schedule", "user", "workspace", "taskId", "taskName", "status",
                "recoveries", "snapshot", "cleanedAt", "endedAt")
        return dict(zip(keys, rows[0]))

    def head(self, job, job_id):
        cluster = managed_cluster_name(job["taskName"], job_id)
        with closing(self._connection()) as connection, connection.cursor() as cursor:
            cursor.execute("""
                SELECT region FROM clusters
                WHERE name=%s AND user_hash=%s AND workspace IS NOT DISTINCT FROM %s
                  AND cloud='Kubernetes' AND length(region)<=256
                LIMIT 2
                """, (cluster, job["user"], job["workspace"]))
            rows = cursor.fetchall()
        if len(rows) != 1:
            raise Unavailable("SOURCE_HEAD_UNAVAILABLE")
        return cluster, cloud_name(cluster, 42, job["user"]), rows[0][0]

    def remote(self, job, job_id, previous, limit):
        from kubernetes import client, config
        from kubernetes.stream import stream
        from runtime_pull import projection
        started = time.monotonic()
        cluster, cloud_cluster, context = self.head(job, job_id)
        kubeconfig = projection()
        selected = kubeconfig["contexts"][0]
        if selected["name"] != context:
            raise Unavailable("SOURCE_CONTEXT_DIFFERS")
        namespace = selected["context"].get("namespace", "default")
        with config.new_client_from_config_dict(kubeconfig, context=context) as api_client:
            api = client.CoreV1Api(api_client)
            selector = f"ray-cluster-name={cloud_cluster},ray-node-type=head"
            listed = bounded_json(api.list_namespaced_pod(namespace, label_selector=selector,
                                  limit=2, _preload_content=False, _request_timeout=(1, 2)))
            pods = listed.get("items", [])
            if len(pods) != 1 or listed.get("metadata", {}).get("continue"):
                raise Unavailable("SOURCE_HEAD_UNCONFIRMED")
            pod = pods[0]
            metadata = pod["metadata"]
            if (metadata.get("annotations", {}).get("skypilot-cluster-name") != cluster
                    or metadata.get("labels", {}).get("ray-cluster-name") != cloud_cluster
                    or metadata.get("labels", {}).get("ray-node-type") != "head"):
                raise Unavailable("SOURCE_HEAD_UNCONFIRMED")
            if not any(container["name"] == "ray-node" for container in pod["spec"]["containers"]):
                raise Unavailable("SOURCE_CONTAINER_UNCONFIRMED")
            pod_name, uid = metadata["name"], metadata["uid"]
            same_pod = previous.get("podUid") == uid
            request = {"taskName": job["taskName"], "limit": limit,
                       "cursor": previous.get("node", {}) if same_pod else {}}
            command = ["python3", "-I", "-c", NODE_PROGRAM, json.dumps(request, separators=(",", ":"))]
            response = stream(api.connect_get_namespaced_pod_exec, pod_name, namespace,
                              container="ray-node", command=command, stderr=True, stdin=False,
                              stdout=True, tty=False, _preload_content=False, _request_timeout=3)
            output = ""
            try:
                while response.is_open():
                    if time.monotonic() - started >= 5:
                        raise Unavailable("SOURCE_DEADLINE")
                    response.update(timeout=0.1)
                    if response.peek_stderr():
                        raise Unavailable("SOURCE_EXEC_FAILED")
                    if response.peek_stdout():
                        chunk = response.read_stdout()
                        if len(output) + len(chunk) > MAX_RESPONSE:
                            raise Unavailable("SOURCE_RESPONSE_TOO_LARGE")
                        output += chunk
                if response.returncode != 0:
                    raise Unavailable("SOURCE_EXEC_FAILED")
            finally:
                response.close()
            # A pod name may be reused; the second identity read detects it.
            after = bounded_json(api.read_namespaced_pod(pod_name, namespace, _preload_content=False, _request_timeout=(1, 2)))
            if after["metadata"]["uid"] != uid:
                raise Unavailable("SOURCE_REPLACED")
        envelope = json.loads(output)
        if envelope.get("schemaVersion") != 1:
            raise Unavailable("SOURCE_PROTOCOL_INVALID")
        if envelope.get("unavailable"):
            raise Unavailable(envelope["unavailable"])
        page = envelope["page"]
        page["generation"] = uid + ":" + page["generation"]
        page["cursor"] = {"podUid": uid, "node": page["cursor"], "recoveries": job["recoveries"]}
        page["snapshot"] = False
        if not previous and job["recoveries"]:
            page["gap"] = "EARLIER_GENERATIONS_UNAVAILABLE"
        elif previous and job["recoveries"] > previous.get("recoveries", 0) + 1:
            page["gap"] = "SOURCE_GENERATION_LOST"
        return page

    def page(self, request):
        run_id = str(uuid.UUID(request["runId"]))
        job_id = integer(request["jobId"], 1) if request.get("jobId") is not None else None
        limit = integer(request.get("limit", MAX_BYTES), 1, MAX_BYTES)
        kind = request["stream"]
        if kind not in {"task", "controller"}:
            raise Unavailable("INVALID_STREAM")
        previous = request.get("cursor", {})
        if not isinstance(previous, dict):
            raise Unavailable("INVALID_CURSOR")
        job = self.job(run_id, job_id)
        job_id = job["jobId"]
        done = job["schedule"] == "DONE" and job["endedAt"] is not None
        if kind == "controller":
            path = self.root / "jobs_controller" / f"{job_id}.log"
            raw = regular_page(path, self.root, request.get("offset", 0), limit,
                               identity=previous.get("identity"), anchor=previous.get("anchor"))
            raw.update({"generation": f"controller:{job_id}:{raw['identity']}",
                        "sealed": done and raw["endOfFile"], "snapshot": False,
                        "cursor": {"identity": raw["identity"], "anchor": raw["anchor"]}})
        elif done and job["snapshot"] and job["cleanedAt"] is None:
            if not previous.get("snapshotIdentity"):
                try:
                    live = self.remote(job, job_id, previous, limit)
                    live.update({"runId": run_id, "jobId": job_id, "stream": kind, "sourceDone": done,
                                 "finalSource": bool(live.get("lastGeneration"))})
                    return live
                except Exception:
                    pass
            if previous.get("podUid"):
                raise Unavailable("SOURCE_GENERATION_UNCONFIRMED")
            path = Path(os.path.expanduser(job["snapshot"]))
            # A first source may use the retained copy; existing live capture
            # cannot be spliced without proof of its pod generation.
            offset = request.get("offset", 0) if previous.get("snapshotIdentity") else 0
            raw = regular_page(path, self.root, offset, limit,
                               identity=previous.get("snapshotIdentity"))
            raw.update({"generation": f"snapshot:{job_id}:{raw['identity']}",
                        "sealed": raw["endOfFile"], "snapshot": True,
                        "cursor": {"snapshotIdentity": raw["identity"], "recoveries": job["recoveries"]}})
            if not previous and job["recoveries"]:
                raw["gap"] = "EARLIER_GENERATIONS_UNAVAILABLE"
        else:
            raw = self.remote(job, job_id, previous, limit)
        raw.update({"runId": run_id, "jobId": job_id, "stream": kind, "sourceDone": done,
                    "finalSource": done and (kind == "controller" or raw.get("snapshot") or raw.get("lastGeneration", False))})
        return raw


def capture_page(request):
    """Enforce the deadline outside Kubernetes' unbounded websocket client."""
    body = json.dumps(request, separators=(",", ":")).encode("ascii")
    if len(body) > MAX_REQUEST:
        raise Unavailable("INVALID_REQUEST")
    process = subprocess.Popen([sys.executable, "-I", str(Path(__file__).resolve()), "--page"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        output, _ = process.communicate(body, timeout=8)
        if process.returncode != 0 or len(output) > MAX_RESPONSE:
            raise Unavailable("SOURCE_WORKER_FAILED")
        envelope = json.loads(output)
        if envelope.get("unavailable"):
            raise Unavailable(envelope["unavailable"])
        return envelope["page"]
    except subprocess.TimeoutExpired:
        raise Unavailable("SOURCE_DEADLINE") from None
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=2)


def worker():
    import resource
    resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024, 512 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CPU, (8, 8))
    try:
        data = sys.stdin.buffer.read(MAX_REQUEST + 1)
        if len(data) > MAX_REQUEST:
            raise Unavailable("INVALID_REQUEST")
        page = Sources().page(json.loads(data))
        body = json.dumps({"schemaVersion": 1, "page": page}, separators=(",", ":")).encode("ascii")
        if len(body) > MAX_RESPONSE:
            raise Unavailable("SOURCE_RESPONSE_TOO_LARGE")
        sys.stdout.buffer.write(body)
    except Unavailable as failure:
        print(json.dumps({"schemaVersion": 1, "unavailable": str(failure)}))
    except Exception:
        print(json.dumps({"schemaVersion": 1, "unavailable": "SOURCE_UNAVAILABLE"}))


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 4

    def __init__(self, address):
        self.slots = threading.BoundedSemaphore(2)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        request.settimeout(10)
        super().process_request(request, address)

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode("ascii")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply(200 if self.path == "/health" else 404, {})

    def do_POST(self):
        try:
            if self.path != "/v1/page":
                self.reply(404, {})
                return
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= MAX_REQUEST:
                raise Unavailable("INVALID_REQUEST")
            data = bytearray()
            deadline = time.monotonic() + 10
            while len(data) < size:
                if time.monotonic() >= deadline:
                    raise Unavailable("INVALID_REQUEST")
                chunk = self.rfile.read1(size - len(data))
                if not chunk:
                    raise Unavailable("INVALID_REQUEST")
                data.extend(chunk)
            page = capture_page(json.loads(data))
            self.reply(200, {"schemaVersion": 1, "page": page})
        except Unavailable as failure:
            self.reply(503, {"schemaVersion": 1, "unavailable": str(failure)})
        except Exception:
            self.reply(503, {"schemaVersion": 1, "unavailable": "SOURCE_UNAVAILABLE"})


if __name__ == "__main__":
    import resource

    resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
    if sys.argv[1:] == ["--page"]:
        worker()
    else:
        Server(("0.0.0.0", 46582)).serve_forever(poll_interval=0.25)
