"""Qualify Dataset Publication workers in an owned namespace using the production pod budget.

Run with the repository's locked SDK environment for boto3. The caller owns the
cluster; this script creates and removes only its unique test namespace.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import socket
import subprocess
import tempfile
import threading
import time
import uuid
from pathlib import Path

import boto3
from botocore.config import Config

ROOT = Path(__file__).resolve().parents[3]
SHARD_BYTES = 256 * 1024 * 1024
FORMAT = "mosaicml-streaming-mds@2"
CREDENTIAL = json.dumps(
    {"accessKeyId": "test-key", "secretAccessKey": "test-secret", "sessionToken": None}
)


def run(arguments, *, input=None, check=True, timeout=90):
    return subprocess.run(
        arguments,
        input=input,
        text=True,
        capture_output=True,
        check=check,
        timeout=timeout,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--kubeconfig", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    namespace = "skywright-publication-" + uuid.uuid4().hex[:10]
    kube = ["kubectl", "--kubeconfig", args.kubeconfig, "--namespace", namespace]
    rendered = run(
        ["kubectl", "kustomize", str(ROOT / "deployment/overlays/production")]
    ).stdout
    converted = run(
        [
            "kubectl",
            "--kubeconfig",
            args.kubeconfig,
            "create",
            "--dry-run=client",
            "--validate=false",
            "-f",
            "-",
            "-o",
            "json",
        ],
        input=rendered,
    ).stdout.strip()
    documents = []
    decoder = json.JSONDecoder()
    while converted:
        document, end = decoder.raw_decode(converted)
        documents.append(document)
        converted = converted[end:].strip()
    backend = next(
        item
        for item in documents
        if item["kind"] == "Deployment"
        and item["metadata"]["name"] == "skywright-backend"
    )
    pod_spec = copy.deepcopy(backend["spec"]["template"]["spec"])
    container = pod_spec["containers"][0]
    container.update(
        image=args.image, imagePullPolicy="Never", command=["sleep", "infinity"], env=[]
    )
    for field in ("ports", "readinessProbe", "livenessProbe", "startupProbe"):
        container.pop(field, None)
    pod = {
        "apiVersion": "v1",
        "kind": "Pod",
        "metadata": {"name": "verifier", "namespace": namespace},
        "spec": pod_spec,
    }
    volume = next(
        item["emptyDir"]
        for item in pod_spec["volumes"]
        if item["name"] == "temporary-files"
    )
    assert volume == {"medium": "Memory", "sizeLimit": "64Mi"}, volume
    credential_configuration = json.dumps(
        {
            "identities": [
                {
                    "name": "fixture",
                    "credentials": [
                        {"accessKey": "test-key", "secretKey": "test-secret"}
                    ],
                    "actions": ["Admin", "Read", "List", "Write", "Tagging"],
                }
            ]
        }
    )
    resources = [
        {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": "s3-fixture"},
            "data": {"s3.json": credential_configuration},
        },
        {
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {"name": "object-storage"},
            "spec": {
                "containers": [
                    {
                        "name": "s3",
                        "image": "docker.io/chrislusf/seaweedfs:4.42",
                        "imagePullPolicy": "Never",
                        "readinessProbe": {
                            "tcpSocket": {"port": 8333},
                            "periodSeconds": 1,
                        },
                        "args": [
                            "mini",
                            "-s3.config=/config/s3.json",
                            "-master.telemetry=false",
                        ],
                        "volumeMounts": [
                            {
                                "name": "configuration",
                                "mountPath": "/config",
                                "readOnly": True,
                            }
                        ],
                    }
                ],
                "volumes": [
                    {"name": "configuration", "configMap": {"name": "s3-fixture"}}
                ],
            },
        },
        {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": {"name": "object-storage"},
            "spec": {
                "selector": {"fixture": "object-storage"},
                "ports": [{"port": 8333, "targetPort": 8333}],
            },
        },
        pod,
    ]
    resources[1]["metadata"]["labels"] = {"fixture": "object-storage"}
    forward = None
    pending = None
    try:
        run([*kube, "create", "namespace", namespace])
        run(
            [*kube, "apply", "-f", "-"],
            input=json.dumps({"apiVersion": "v1", "kind": "List", "items": resources}),
        )
        run(
            [
                *kube,
                "wait",
                "--for=condition=Ready",
                "pod/object-storage",
                "pod/verifier",
                "--timeout=90s",
            ],
            timeout=100,
        )
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        with args.output.with_suffix(".port-forward.log").open("w") as forward_log:
            forward = subprocess.Popen(
                [*kube, "port-forward", "service/object-storage", f"{port}:8333"],
                stdout=forward_log,
                stderr=subprocess.STDOUT,
            )
        endpoint = f"http://127.0.0.1:{port}"
        client = boto3.client(
            "s3",
            endpoint_url=endpoint,
            region_name="us-east-1",
            aws_access_key_id="test-key",
            aws_secret_access_key="test-secret",
            config=Config(
                s3={"addressing_style": "path"},
                request_checksum_calculation="when_required",
                connect_timeout=1,
                retries={"total_max_attempts": 1},
            ),
        )
        for attempt in range(100):
            try:
                client.list_buckets()
                break
            except Exception:
                if attempt == 99:
                    raise
                time.sleep(0.1)
        bucket = "qualification"
        client.create_bucket(Bucket=bucket)
        checksum = hashlib.sha256()
        with tempfile.TemporaryDirectory(
            prefix="skywright-publication-pod-source-"
        ) as temporary:
            shard = Path(temporary) / "shard.mds"
            with shard.open("wb") as output:
                chunk = b"x" * (1024 * 1024)
                for _ in range(256):
                    output.write(chunk)
                    checksum.update(chunk)
            objects = [
                {
                    "objectKey": f"{index}.mds",
                    "byteCount": SHARD_BYTES,
                    "sha256": "sha256:" + checksum.hexdigest(),
                }
                for index in range(4)
            ]
            for item in objects:
                with shard.open("rb") as content:
                    client.put_object(
                        Bucket=bucket, Key="payload/" + item["objectKey"], Body=content
                    )
        manifest = json.dumps(
            {
                "version": "skywright-dataset-manifest@1",
                "format": FORMAT,
                "objectCount": 4,
                "byteCount": 4 * SHARD_BYTES,
                "objects": objects,
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
        manifest_identity = "sha256:" + hashlib.sha256(manifest).hexdigest()
        fingerprint = (
            "sha256:"
            + hashlib.sha256(
                json.dumps(
                    {
                        "format": FORMAT,
                        "manifest": manifest_identity,
                        "version": "skywright-dataset-content@1",
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode()
            ).hexdigest()
        )
        client.put_object(Bucket=bucket, Key="operation/manifest.json", Body=manifest)
        job = {
            "action": "VERIFY",
            "endpoint": "http://object-storage:8333",
            "bucket": bucket,
            "region": "us-east-1",
            "pathStyleAccess": True,
            "chunkedEncoding": False,
            "formatIdentity": FORMAT,
            "manifestIdentity": manifest_identity,
            "contentFingerprint": fingerprint,
            "objectCount": 4,
            "byteCount": 4 * SHARD_BYTES,
            "payloadLocation": "payload",
            "operationLocation": "operation",
            "verificationConcurrency": 4,
        }
        java = [
            *kube,
            "exec",
            "-i",
            "verifier",
            "--",
            "java",
            "-Dloader.main=de.zorro909.skywright.backend.datasetpublication.DatasetPublicationWorkerMain",
            "-cp",
            "/opt/skywright/application.jar",
            "org.springframework.boot.loader.launch.PropertiesLauncher",
            "/tmp/job.json",
            "/tmp/result.json",
        ]

        def write_job():
            run(
                [
                    *kube,
                    "exec",
                    "-i",
                    "verifier",
                    "--",
                    "sh",
                    "-c",
                    "cat > /tmp/job.json",
                ],
                input=json.dumps(job),
            )

        def result():
            return json.loads(
                run([*kube, "exec", "verifier", "--", "cat", "/tmp/result.json"]).stdout
            )

        def disk_used():
            return int(
                run([*kube, "exec", "verifier", "--", "df", "-B1", "/tmp"])
                .stdout.splitlines()[-1]
                .split()[2]
            )

        write_job()
        stop = threading.Event()
        peak = disk_used()
        sampling_errors = []

        def sample():
            nonlocal peak
            while not stop.is_set():
                try:
                    peak = max(peak, disk_used())
                except (subprocess.SubprocessError, OSError, ValueError) as failure:
                    sampling_errors.append(failure)
                    return
                stop.wait(0.1)

        sampler = threading.Thread(target=sample)
        sampler.start()
        started = time.monotonic()
        try:
            run(java, input=CREDENTIAL)
        finally:
            stop.set()
            sampler.join()
        assert not sampling_errors, sampling_errors
        elapsed = time.monotonic() - started
        verified = result()
        assert (
            verified["verified"]
            and verified["byteCount"] == 4 * SHARD_BYTES
            and verified["objectCount"] == 4
        ), verified
        run([*kube, "exec", "verifier", "--", "rm", "/tmp/result.json"])
        run(
            [
                *kube,
                "exec",
                "verifier",
                "--",
                "sh",
                "-c",
                "dd if=/dev/zero of=/tmp/exhausted bs=1M count=64 2>/dev/null",
            ],
            check=False,
        )
        exhaustion = run(java, input=CREDENTIAL, check=False)
        assert (
            exhaustion.returncode != 0
            and "DATASET_WORKER_TEMPORARY_STORAGE_UNAVAILABLE" in exhaustion.stderr
        ), exhaustion.stderr
        assert (
            run(
                [*kube, "exec", "verifier", "--", "test", "-e", "/tmp/result.json"],
                check=False,
            ).returncode
            != 0
        )
        run(
            [
                *kube,
                "exec",
                "verifier",
                "--",
                "rm",
                "-f",
                "/tmp/exhausted",
                "/tmp/result.json.pending",
            ]
        )
        run([*kube, "exec", "object-storage", "--", "sh", "-c", "kill -STOP 1"])
        pending = subprocess.Popen(
            java,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        pending.stdin.write(CREDENTIAL.encode())
        pending.stdin.close()
        time.sleep(2)
        assert pending.poll() is None, "verification was not held before cancellation"
        run([*kube, "delete", "pod", "verifier", "--grace-period=1", "--wait=true"])
        assert pending.wait(timeout=10) != 0
        pending = None
        run([*kube, "exec", "object-storage", "--", "sh", "-c", "kill -CONT 1"])
        run([*kube, "apply", "-f", "-"], input=json.dumps(pod))
        run([*kube, "wait", "--for=condition=Ready", "pod/verifier", "--timeout=60s"])
        assert (
            run(
                [*kube, "exec", "verifier", "--", "test", "-e", "/tmp/result.json"],
                check=False,
            ).returncode
            != 0
        )
        write_job()
        run(java, input=CREDENTIAL)
        assert result()["verified"]
        evidence = {
            "shards": 4,
            "shard_bytes": SHARD_BYTES,
            "verification_concurrency": 4,
            "temporary_volume": volume,
            "peak_temporary_bytes": peak,
            "verification_seconds": elapsed,
            "verified_bytes": verified["byteCount"],
            "exhaustion_exit_code": exhaustion.returncode,
            "exhaustion_published_result": False,
            "cancelled_worker_stopped": True,
            "restart_verified": True,
            "cluster": json.loads(run([*kube, "version", "-o", "json"]).stdout)[
                "serverVersion"
            ]["gitVersion"],
            "image": args.image,
        }
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
        print(json.dumps(evidence), flush=True)
    finally:
        run(
            [*kube, "exec", "object-storage", "--", "sh", "-c", "kill -CONT 1"],
            check=False,
            timeout=10,
        )
        if pending is not None:
            pending.kill()
            pending.wait()
        if forward is not None:
            forward.terminate()
            forward.wait(timeout=10)
        run(
            [*kube, "delete", "namespace", namespace, "--wait=true", "--timeout=90s"],
            check=False,
            timeout=100,
        )


if __name__ == "__main__":
    main()
