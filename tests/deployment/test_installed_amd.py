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
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.environ.get("SKYWRIGHT_FRESH_INSTALL_CONFIGURATION"), "requires fresh AMD installation")
class FreshAmdInstallationTest(unittest.TestCase):
    def test_install_from_uninstalled_state_reaches_joined_readiness(self):
        configuration = os.environ["SKYWRIGHT_FRESH_INSTALL_CONFIGURATION"]
        settings = json.loads(Path(configuration).read_text())
        installed = Path(settings["stateDirectory"]) / "installed.json"
        self.assertFalse(installed.exists(), "Fresh qualification requires an uninstalled state directory")
        for action in ("install", "preflight"):
            result = subprocess.run([str(ROOT / "scripts/deploy"), action, "--configuration", configuration],
                                    capture_output=True, text=True, timeout=3600)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            if action == "preflight":
                report = json.loads(result.stdout)
                self.assertTrue(report["ready"], report)
                self.assertEqual(report["gpuCount"], 2)
        self.assertEqual(json.loads(installed.read_text())["release"], settings["release"])
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen("http://127.0.0.1:8080/api/v1/managed-run-form", timeout=5) as response:
                    if json.load(response)["ready"]:
                        return
            except OSError:
                pass
            time.sleep(2)
        self.fail("Private GUI access and Managed Run readiness did not become available")


@unittest.skipUnless(os.environ.get("SKYWRIGHT_HOST_CONFIGURATION"), "requires idle AMD qualification host")
class IdleAmdHostTest(unittest.TestCase):
    def test_preflight_recognizes_idle_host_including_runtime_suspended_gpus(self):
        completed = subprocess.run(
            [str(ROOT / "scripts/deploy"), "preflight", "--configuration",
             os.environ["SKYWRIGHT_HOST_CONFIGURATION"]],
            capture_output=True, text=True, timeout=60)
        self.assertIn(completed.returncode, (0, 1), completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["checks"]["hostGpu"]["status"], "ready", report)


@unittest.skipUnless(os.environ.get("SKYWRIGHT_INSTALL_CONFIGURATION") and
                     os.environ.get("SKYWRIGHT_UPDATE_CONFIGURATION"), "requires the dedicated AMD qualification host")
class InstalledAmdTest(unittest.TestCase):
    def command(self, action, configuration):
        completed = subprocess.run([str(ROOT / "scripts/deploy"), action, "--configuration", configuration],
                                   capture_output=True, text=True, timeout=3600)
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        return completed.stdout

    def api(self, path, value=None, timeout=30):
        request = urllib.request.Request("http://127.0.0.1:8080" + path,
            data=None if value is None else json.dumps(value).encode(),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=timeout) as response:
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

    def preflight(self, configuration):
        deadline = time.monotonic() + 120
        while True:
            result = subprocess.run([str(ROOT / "scripts/deploy"), "preflight", "--configuration", configuration],
                                    capture_output=True, text=True,
                                    timeout=max(0.1, min(60, deadline - time.monotonic())))
            self.assertIn(result.returncode, (0, 1), result.stderr)
            report = json.loads(result.stdout)
            if report["ready"]:
                self.assertEqual(result.returncode, 0, result.stderr)
                return report
            self.assertLess(time.monotonic(), deadline, report)
            print("Waiting for installed preflight readiness", flush=True)
            time.sleep(max(0, min(5, deadline - time.monotonic())))

    def smoke(self):
        intent = {"submissionId": str(uuid.uuid4()), "workload": "demonstration", "target": "local/amd"}
        admission_deadline = time.monotonic() + 120
        while True:
            try:
                accepted = self.api("/api/v1/managed-runs", intent,
                                    timeout=max(0.1, min(30, admission_deadline - time.monotonic())))
                break
            except urllib.error.HTTPError as error:
                problem = json.load(error)
                if (error.code != 503 or problem.get("errorCode") != "SKYWRIGHT_RUN_ADMISSION_UNAVAILABLE"
                        or problem.get("retryable") is not True or time.monotonic() >= admission_deadline):
                    raise
                print("Retrying the same Managed Run submission after retryable admission unavailability", flush=True)
                time.sleep(max(0, min(5, admission_deadline - time.monotonic())))
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
        preflight = self.preflight(fresh)
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
        self.assertTrue(self.preflight(update)["ready"])


@unittest.skipUnless(os.environ.get("SKYWRIGHT_CHECKPOINT_FAILURE_CONFIGURATION"),
                     "requires retained checkpoint failure qualification")
class CheckpointFailureTest(unittest.TestCase):
    def test_failed_checkpoint_restores_the_retained_control_plane(self):
        import shutil
        import tempfile

        configuration = os.environ["SKYWRIGHT_CHECKPOINT_FAILURE_CONFIGURATION"]
        settings = json.loads(Path(configuration).read_text())
        state = Path(settings["stateDirectory"])
        installed = (state / "installed.json").read_bytes()
        previous_backups = set((state / "backups").glob("*"))
        docker = shutil.which("docker")
        self.assertIsNotNone(docker)
        case = InstalledAmdTest()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                wrapper = Path(temporary) / "docker"
                wrapper.write_text("#!/usr/bin/env python3\nimport os,sys\n"
                                   "if sys.argv[1:2] == ['cp']: raise SystemExit(73)\n"
                                   "os.execv(" + repr(docker) + ", [" + repr(docker) + ", *sys.argv[1:]])\n")
                wrapper.chmod(0o700)
                result = subprocess.run([str(ROOT / "scripts/deploy"), "backup", "--configuration", configuration],
                                        env=os.environ | {"PATH": temporary + os.pathsep + os.environ["PATH"]},
                                        capture_output=True, text=True, timeout=900)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Checkpoint copy failed", result.stdout + result.stderr)
            self.assertEqual((state / "installed.json").read_bytes(), installed)
            created = set((state / "backups").glob("*")) - previous_backups
            self.assertEqual(len(created), 1)
            self.assertFalse((created.pop() / "checkpoint.json").exists())
            self.assertFalse((state / "pending-update.json").exists())
            case.wait_ready(configuration)
            self.assertTrue(case.preflight(configuration)["ready"])
        finally:
            case.command("start", configuration)

    def test_interrupted_quiescence_restores_services_without_restarting_node(self):
        configuration = os.environ["SKYWRIGHT_CHECKPOINT_FAILURE_CONFIGURATION"]
        settings = json.loads(Path(configuration).read_text())
        state = Path(settings["stateDirectory"])
        kubectl = [str(state / "tools/kubectl"), "--context", settings["context"], "--request-timeout=10s"]
        pods = json.loads(subprocess.check_output(kubectl + ["get", "pods", "-n", "skywright", "-l",
            "app.kubernetes.io/name=skywright-backend", "-o", "json"]))["items"]
        self.assertEqual(len(pods), 1)
        pod = pods[0]["metadata"]["name"]
        finalizer = "qualification.skywright.io/checkpoint-pause"
        finalizers = pods[0]["metadata"].get("finalizers", [])
        started = subprocess.check_output(["docker", "inspect", settings["node"], "--format", "{{.State.StartedAt}}"])
        case = InstalledAmdTest()
        try:
            subprocess.run(kubectl + ["patch", "pod", pod, "-n", "skywright", "--type=merge", "-p",
                json.dumps({"metadata": {"finalizers": finalizers + [finalizer]}})], check=True, capture_output=True)
            result = subprocess.run([str(ROOT / "scripts/deploy"), "backup", "--configuration", configuration],
                                    capture_output=True, text=True, timeout=600)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(subprocess.check_output(["docker", "inspect", settings["node"], "--format",
                                                     "{{.State.StartedAt}}"]), started)
            case.wait_ready(configuration)
            self.assertTrue(case.preflight(configuration)["ready"])
        finally:
            observed = subprocess.run(kubectl + ["get", "pod", pod, "-n", "skywright", "-o", "json"],
                                      capture_output=True, text=True)
            if observed.returncode == 0:
                retained = [value for value in json.loads(observed.stdout)["metadata"].get("finalizers", [])
                            if value != finalizer]
                subprocess.run(kubectl + ["patch", "pod", pod, "-n", "skywright", "--type=merge", "-p",
                    json.dumps({"metadata": {"finalizers": retained}})], check=True, capture_output=True)
            case.command("start", configuration)
