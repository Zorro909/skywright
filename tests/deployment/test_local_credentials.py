from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
import datetime
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
