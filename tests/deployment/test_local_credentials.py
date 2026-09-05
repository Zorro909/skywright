from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
LAUNCH = ROOT / "skypilot-api-server-deployment/src/main/docker/startup/launch.py"
PULL = ROOT / "deployment/scripts/local-runtime-pull"


class LocalCredentialsTest(unittest.TestCase):
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
