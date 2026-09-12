from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
import datetime
import contextlib
import hashlib
import io
import stat
import sys
import threading
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deployment"))
LAUNCH = ROOT / "skypilot-api-server-deployment/src/main/docker/startup/launch.py"
PULL = ROOT / "deployment/scripts/local-runtime-pull"


class LocalCredentialsTest(unittest.TestCase):
    def test_provider_validation_uses_only_bounded_reads_and_redacts_rejected_responses(self):
        from skywright_deployment import local_vast

        secret = "provider-secret-sentinel"
        value = {"identity": "sha256:" + hashlib.sha256(secret.encode()).hexdigest(),
                 "providerKeyId": "1", "enrolledAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 "requestedPermissions": {"api": {name: {} for name in (
                     "misc", "user_read", "instance_read", "instance_write")}}}
        expected_rights = {"api": {**value["requestedPermissions"]["api"], "*": local_vast.BASELINE_RIGHTS}}
        for scenario in ("accepted", "redirect", "oversized", "wrong-key", "wrong-rights", "failed-status"):
            with self.subTest(scenario=scenario):
                calls, output, errors = [], io.StringIO(), io.StringIO()
                account = {"key_id": "1", "rights": expected_rights, "credit": "1.25", "extra": secret}
                if scenario == "wrong-key":
                    account["key_id"] = "another-key"
                if scenario == "wrong-rights":
                    account["rights"] = {"billing": {}}

                class Response(io.BytesIO):
                    status = 200

                def build_opener(handler):
                    def open_request(request, timeout):
                        calls.append(request.full_url)
                        self.assertEqual(request.get_method(), "GET")
                        self.assertEqual(request.get_header("Authorization"), "Bearer " + secret)
                        self.assertEqual(timeout, 10)
                        if scenario == "redirect":
                            handler.redirect_request(request, None, 302, "redirect", {}, "https://other.invalid")
                            self.fail("Credential validation followed a redirect")
                        body = account if len(calls) == 1 else {"instances": [{"extra": secret}]}
                        response = Response(b"x" * (1024 * 1024 + 1) if scenario == "oversized"
                                            else json.dumps(body).encode())
                        if scenario == "failed-status":
                            response.status = 503
                        return response
                    return SimpleNamespace(open=open_request)

                def execute(*args, data, **kwargs):
                    self.assertEqual(args[2], "pod/server-1")
                    self.assertEqual(kwargs["timeout"], 45)
                    argv = list(args[args.index("python") + 1:])
                    metadata = SimpleNamespace(st_mode=stat.S_IFREG | 0o400, st_uid=os.getuid())
                    code = 0
                    with patch.object(sys, "argv", argv), patch.object(Path, "stat", return_value=metadata), \
                            patch.object(Path, "read_text", return_value=secret), patch("logging.disable"), \
                            patch("urllib.request.build_opener", side_effect=build_opener), \
                            contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                        try:
                            exec(compile(data, "provider-validation", "exec"), {})
                        except SystemExit as failure:
                            code = failure.code
                    return subprocess.CompletedProcess(args, code, output.getvalue().encode(), errors.getvalue().encode())

                kube = Mock()
                kube.run.side_effect = execute
                observed = local_vast.validation_result(kube, value, "server-1")
                self.assertNotIn(secret, output.getvalue() + errors.getvalue())
                if scenario == "accepted":
                    self.assertFalse(observed["adapterAvailable"])
                    self.assertFalse(observed["provisioningQualified"])
                    self.assertEqual(observed["instanceCount"], 1)
                    self.assertEqual(observed["effectivePermissions"], expected_rights)
                    self.assertEqual(calls, ["https://console.vast.ai/api/v0/users/current/",
                                             "https://console.vast.ai/api/v0/instances/?owner=me"])
                else:
                    self.assertIsNone(observed)
                    self.assertEqual(output.getvalue(), "")
                    self.assertEqual(errors.getvalue(), "Provider projection verification failed; provider values suppressed.\n")

    def test_optional_provider_validation_does_not_block_host_observation_or_lifecycle_lock(self):
        from skywright_deployment import local_service, local_vast
        from skywright_deployment.local_package import installation_lock

        with tempfile.TemporaryDirectory() as temporary:
            settings = {"context": "kind-fixture", "stateDirectory": temporary, "vastProvider": {
                "revision": 1, "identity": "sha256:" + "a" * 64, "providerKeyId": "1",
                "enrolledAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "requestedPermissions": {"api": {key: {} for key in (
                    "misc", "user_read", "instance_read", "instance_write")}},
            }}
            pod = {"metadata": {"uid": "pod-1", "name": "server-1"}, "status": {
                "phase": "Running", "initContainerStatuses": [{"name": "project-kubernetes-credential",
                "state": {"terminated": {"exitCode": 0, "finishedAt": "2026-09-13T00:00:00Z"}}}]}}
            kube = Mock()
            kube.prefix = ["kubectl"]
            kube.read.return_value = {"items": [pod]}
            started, observed_again = threading.Event(), threading.Event()
            checks = []
            observations = []

            def observe(*args):
                observations.append(True)
                if len(observations) == 2:
                    observed_again.set()
                if len(observations) == 3:
                    raise KeyboardInterrupt()

            def validate(*args, **kwargs):
                started.set()
                checks.append(observed_again.wait(0.5))
                return {"observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                        "effectivePermissions": {}}

            def sleep(_seconds):
                self.assertTrue(started.wait(0.5))
                with installation_lock(settings, wait_seconds=0):
                    pass

            forward = Mock()
            forward.poll.return_value = None
            with patch.object(local_service, "configuration", return_value=settings), \
                    patch.object(local_service, "tools"), patch.object(local_service, "Kubernetes", return_value=kube), \
                    patch.object(local_service, "renew"), patch.object(local_service, "observe", side_effect=observe), \
                    patch.object(local_service.time, "sleep", side_effect=sleep), \
                    patch.object(local_service.subprocess, "Popen", return_value=forward), \
                    patch.object(local_vast, "validate", side_effect=validate):
                with self.assertRaises(KeyboardInterrupt):
                    local_service.run(SimpleNamespace(configuration=Path(temporary) / "instance.json"))
            self.assertEqual(checks, [True])
            self.assertEqual(len(observations), 3)

    def test_provider_validation_discards_replaced_consumer_and_preserves_projection_evidence(self):
        from skywright_deployment import local_vast
        from skywright_deployment.local_package import installation_lock

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            settings = {"stateDirectory": temporary, "vastProvider": {
                "revision": 1, "identity": "sha256:" + "a" * 64, "providerKeyId": "1",
                "enrolledAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "requestedPermissions": {"api": {key: {} for key in (
                    "misc", "user_read", "instance_read", "instance_write")}},
            }}

            def pod(number):
                return {"metadata": {"uid": "pod-" + number, "name": "server-" + number}, "status": {
                    "phase": "Running", "initContainerStatuses": [{"name": "project-kubernetes-credential",
                    "state": {"terminated": {"exitCode": 0, "finishedAt": "2026-09-13T00:00:00Z"}}}]}}

            kube = Mock()
            kube.read.return_value = {"items": [pod("1")]}
            started, release = threading.Event(), threading.Event()
            consumers = []

            def execute(*args, **kwargs):
                consumers.append(args[2])
                if args[2] == "pod/server-1":
                    started.set()
                    self.assertTrue(release.wait(1))
                observed = {"identity": settings["vastProvider"]["identity"], "effectivePermissions": {},
                            "observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}
                return subprocess.CompletedProcess(args, 0, json.dumps(observed).encode(), b"")

            kube.run.side_effect = execute
            worker = local_vast.ProviderValidation()
            try:
                with installation_lock(settings):
                    worker.poll(kube, settings, directory)
                self.assertTrue(started.wait(1))
                old_path = next((directory / "credential-projections").glob("*.projection.json"))
                original = old_path.read_bytes()
                old = json.loads(original)
                with installation_lock(settings, wait_seconds=0):
                    kube.read.return_value = {"items": [pod("2")]}
                    worker.poll(kube, settings, directory)
                release.set()
                worker.pending[1].result(timeout=1)
                with installation_lock(settings):
                    worker.poll(kube, settings, directory)
                worker.pending[1].result(timeout=1)
                with installation_lock(settings):
                    worker.poll(kube, settings, directory)
                records = directory / "credential-projections"
                self.assertEqual(old_path.read_bytes(), original)
                self.assertTrue((records / (old["id"] + ".release.json")).exists())
                self.assertEqual(list(records.glob(old["id"] + ".*.validation.json")), [])
                validations = list(records.glob("*.validation.json"))
                self.assertEqual(len(validations), 1)
                self.assertEqual(consumers, ["pod/server-1", "pod/server-2"])
            finally:
                release.set()
                worker.close()

    def test_skypilot_accepts_only_read_only_self_contained_kubernetes_projection(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "kubeconfig"
            config = {"apiVersion": "v1", "kind": "Config", "clusters": [{"cluster": {
                "server": "https://local.invalid", "certificate-authority-data": "fixture-ca"}}],
                "contexts": [{}], "users": [{"user": {"token": "sentinel-kubernetes"}}]}
            path.write_text(json.dumps(config))
            env = dict(os.environ, SKYWRIGHT_KUBECONFIG=str(path))
            env.pop("SKYPILOT_DB_CONNECTION_URI", None)
            for mode, expected in [(0o400, "SKYPILOT_DB_CONNECTION_URI is required"),
                                   (0o600, "Kubernetes Credential Projection is unavailable")]:
                path.chmod(mode)
                result = subprocess.run(["python3", str(LAUNCH)], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 78)
                self.assertIn(expected, result.stderr)
                self.assertNotIn("sentinel-kubernetes", result.stderr)
            config["users"][0]["user"] = {"exec": {"command": "untrusted"}}
            path.chmod(0o600)
            path.write_text(json.dumps(config))
            path.chmod(0o400)
            result = subprocess.run(["python3", str(LAUNCH)], env=env, capture_output=True, text=True)
            self.assertIn("Kubernetes Credential Projection is unavailable", result.stderr)

    def test_runtime_pull_uses_immutable_secret_stdin_and_redacts_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            kubectl = directory / "kubectl"
            captured = directory / "captured"
            kubectl.write_text("#!/usr/bin/env python3\nimport os,sys\nfrom pathlib import Path\n"
                               "Path(os.environ['CAPTURE']).write_text(sys.stdin.read())\n"
                               "print('provider-sentinel', file=sys.stderr)\n"
                               "raise SystemExit(int(os.environ.get('EXIT', '0')))\n")
            kubectl.chmod(0o700)
            auth = directory / "config.json"
            auth.write_text(json.dumps({"auths": {"ghcr.io": {"auth": "fixture"}}}))
            auth.chmod(0o400)
            env = dict(os.environ, PATH=f"{directory}:{os.environ['PATH']}", CAPTURE=str(captured))
            run_id = str(uuid.uuid4())
            args = [str(PULL), "install", "--run-id", run_id, "--namespace", "training",
                    "--context", "local", "--credential-file", str(auth)]
            result = subprocess.run(args, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            secret = json.loads(captured.read_text())
            self.assertTrue(secret["immutable"])
            self.assertEqual(secret["metadata"]["name"], f"skywright-pull-{run_id}")
            self.assertEqual(secret["type"], "kubernetes.io/dockerconfigjson")
            result = subprocess.run(args, env=dict(env, EXIT="1"), capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("provider-sentinel", result.stderr)


if __name__ == "__main__":
    unittest.main()
