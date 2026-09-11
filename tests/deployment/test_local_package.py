from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class LocalPackageTest(unittest.TestCase):
    def test_preflight_reports_both_gpus_and_missing_control_plane_without_launching_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "instance.json"
            config.write_text(json.dumps({
                "schemaVersion": 1, "release": "ghcr.io/zorro909/skywright-deployment@sha256:" + "a" * 64,
                "context": "kind-skywright", "node": "skywright-control-plane",
                "stateDirectory": directory + "/state", "secretDirectory": directory + "/secrets",
                "gpuModel": "rx7800xt", "gpuMemoryBytes": 17163091968, "gpuCount": 2,
            }))
            kubectl = root / "kubectl"
            kubectl.write_text('''#!/usr/bin/env python3
import json,sys
assert "get" in sys.argv
if "node" in sys.argv:
    print(json.dumps({"metadata":{"uid":"node-uid", "labels":{"skypilot.co/accelerator":"rx7800xt"}},
      "status":{"capacity":{"amd.com/gpu":"2"}, "allocatable":{"amd.com/gpu":"2"},
      "conditions":[{"type":"Ready","status":"True"}]}}))
else:
    print(json.dumps({"items":[]}))
''')
            kubectl.chmod(0o700)
            result = subprocess.run(
                [str(ROOT / "scripts/deploy"), "preflight", "--configuration", str(config)],
                env=os.environ | {"SKYWRIGHT_KUBECTL": str(kubectl)},
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 1, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["gpuCount"], 2)
            self.assertFalse(report["ready"])
            self.assertEqual(report["checks"]["target"]["status"], "ready")
            self.assertEqual(report["checks"]["controlPlane"]["status"], "unavailable")

    def test_install_requires_a_digest_pinned_release_before_contacting_the_host(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "instance.json"
            config.write_text(json.dumps({
                "schemaVersion": 1, "release": "ghcr.io/zorro909/skywright-deployment:latest",
                "context": "kind-skywright", "node": "skywright-control-plane",
                "stateDirectory": directory + "/state", "secretDirectory": directory + "/secrets",
                "gpuModel": "rx7800xt", "gpuMemoryBytes": 17163091968, "gpuCount": 2,
            }))
            result = subprocess.run(
                [str(ROOT / "scripts/deploy"), "install", "--configuration", str(config)],
                capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Installation release must use the canonical OCI digest", result.stderr)

    def test_install_refuses_exposed_or_shared_registry_inputs_without_printing_them(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "instance.json"
            config.write_text(json.dumps({
                "schemaVersion": 1, "release": "ghcr.io/zorro909/skywright-deployment@sha256:" + "a" * 64,
                "context": "kind-skywright", "node": "skywright-control-plane",
                "stateDirectory": directory + "/state", "secretDirectory": directory + "/secrets",
                "gpuModel": "rx7800xt", "gpuMemoryBytes": 17163091968, "gpuCount": 2,
            }))
            secret_root = root / "secrets"
            secret_root.mkdir()
            for role in ("resolver", "pull"):
                path = secret_root / ("ghcr-" + role + ".json")
                path.write_text(json.dumps({"username": "operator", "token": "secret-sentinel"}))
                path.chmod(0o644)
            command = [str(ROOT / "scripts/deploy"), "install", "--configuration", str(config)]
            exposed = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertNotEqual(exposed.returncode, 0)
            self.assertIn("owner-only", exposed.stderr)
            self.assertNotIn("secret-sentinel", exposed.stdout + exposed.stderr)
            for role in ("resolver", "pull"):
                (secret_root / ("ghcr-" + role + ".json")).chmod(0o600)
            shared = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertNotEqual(shared.returncode, 0)
            self.assertIn("distinct read-only tokens", shared.stderr)
            self.assertNotIn("secret-sentinel", shared.stdout + shared.stderr)

    def test_install_rejects_unknown_configuration_without_echoing_values(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "instance.json"
            config.write_text(json.dumps({"schemaVersion": 1, "token": "secret-sentinel"}))
            result = subprocess.run(
                [str(ROOT / "scripts/deploy"), "install", "--configuration", str(config)],
                capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Unknown installation configuration fields", result.stderr)
            self.assertNotIn("secret-sentinel", result.stdout + result.stderr)

    def test_lifecycle_requires_a_completed_installation_before_host_access(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "instance.json"
            config.write_text(json.dumps({
                "schemaVersion": 1, "release": "ghcr.io/zorro909/skywright-deployment@sha256:" + "a" * 64,
                "context": "kind-skywright", "node": "skywright-control-plane",
                "stateDirectory": directory + "/state", "secretDirectory": directory + "/secrets",
                "gpuModel": "rx7800xt", "gpuMemoryBytes": 17163091968, "gpuCount": 2,
            }))
            for action in ("update", "start", "stop", "restart", "backup"):
                result = subprocess.run([str(ROOT / "scripts/deploy"), action, "--configuration", str(config)],
                                        capture_output=True, text=True, timeout=5)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("No completed installation exists", result.stderr)
