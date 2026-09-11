"""Opt-in system test of the public installer and Managed Run HTTP boundaries.

Run on the dedicated supported host with SKYWRIGHT_INSTALL_CONFIGURATION and
SKYWRIGHT_UPDATE_CONFIGURATION pointing to two increasing signed release inputs.
The test submits two short GPU Runs and requires idle GPUs.
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import time
import unittest
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.environ.get("SKYWRIGHT_INSTALL_CONFIGURATION") and
                     os.environ.get("SKYWRIGHT_UPDATE_CONFIGURATION"), "requires the dedicated AMD qualification host")
class InstalledAmdTest(unittest.TestCase):
    def command(self, action, configuration):
        completed = subprocess.run([str(ROOT / "scripts/deploy"), action, "--configuration", configuration],
                                   capture_output=True, text=True, timeout=3600)
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        return completed.stdout

    def api(self, path, value=None):
        request = urllib.request.Request("http://127.0.0.1:8080" + path,
            data=None if value is None else json.dumps(value).encode(),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def wait_ready(self, configuration):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                form = self.api("/api/v1/managed-run-form")
                if form["ready"]:
                    return
            except OSError:
                pass
            time.sleep(5)
        self.fail("Installed admission did not become ready within two minutes")

    def smoke(self):
        intent = {"submissionId": str(uuid.uuid4()), "workload": "demonstration", "target": "local/amd"}
        accepted = self.api("/api/v1/managed-runs", intent)
        identity = accepted["runId"]
        self.assertEqual(self.api("/api/v1/managed-runs", intent)["runId"], identity)
        terminal = False
        try:
            deadline = time.monotonic() + 900
            while time.monotonic() < deadline:
                run = self.api("/api/v1/runs/" + identity)
                lifecycle = run.get("lifecycle", {})
                if lifecycle.get("terminalLatched"):
                    terminal = True
                    self.assertEqual(lifecycle["state"], "finished", lifecycle)
                    break
                time.sleep(5)
            self.assertTrue(terminal, "Demonstration did not finish within fifteen minutes")
            outputs = self.api("/api/v1/runs/" + identity + "/outputs?kind=artifact")
            self.assertTrue(outputs["items"], "Finished demonstration has no committed Artifact")
            artifact = outputs["items"][0]
            self.assertTrue(artifact["downloadUrl"].startswith("/api/v1/runs/" + identity + "/output-content?key="))
            with urllib.request.urlopen("http://127.0.0.1:8080" + artifact["downloadUrl"], timeout=30) as response:
                content = response.read(8 * 1024**2 + 1)
            self.assertEqual(len(content), artifact["sizeBytes"])
            self.assertEqual(hashlib.sha256(content).hexdigest(), artifact["sha256"])
            return identity
        finally:
            if not terminal:
                self.api("/api/v1/runs/" + identity + "/cancellations", {"requestId": str(uuid.uuid4())})

    def test_fresh_install_and_retained_restart_update_stop_start(self):
        fresh = os.environ["SKYWRIGHT_INSTALL_CONFIGURATION"]
        update = os.environ["SKYWRIGHT_UPDATE_CONFIGURATION"]
        self.command("install", fresh)
        self.wait_ready(fresh)
        preflight = json.loads(self.command("preflight", fresh))
        self.assertEqual(preflight["gpuCount"], 2)
        self.assertTrue(preflight["ready"])
        first = self.smoke()
        self.command("restart", fresh)
        self.wait_ready(fresh)
        self.assertEqual(self.api("/api/v1/runs/" + first)["lifecycle"]["state"], "finished")
        self.command("update", update)
        self.wait_ready(update)
        self.assertEqual(self.api("/api/v1/runs/" + first)["lifecycle"]["state"], "finished")
        second = self.smoke()
        self.command("stop", update)
        self.command("start", update)
        self.wait_ready(update)
        self.assertEqual(self.api("/api/v1/runs/" + second)["lifecycle"]["state"], "finished")
        self.assertTrue(json.loads(self.command("preflight", update))["ready"])
