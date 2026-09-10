"""Verify launchers impose actual process limits and rendered profiles reserve memory."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_rendered_profiles import LOCAL, PRODUCTION, render, resource

ROOT = Path(__file__).resolve().parents[2]


class ResourceLimitsTest(unittest.TestCase):
    def test_backend_launcher_limits_the_execed_process(self):
        with tempfile.TemporaryDirectory() as directory:
            java = Path(directory) / "java"
            java.write_text("#!/usr/bin/env python3\nimport json, resource, sys\n"
                            "print(json.dumps([resource.getrlimit(resource.RLIMIT_NOFILE), sys.argv[1:]]))\n")
            java.chmod(0o755)
            environment = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"])
            environment.pop("SKYWRIGHT_NOFILE_LIMIT", None)
            result = subprocess.run(["sh", str(ROOT / "backend-deployment/src/main/docker/start"),
                                     "argument with spaces"], env=environment, check=True,
                                    text=True, capture_output=True)
            self.assertEqual(json.loads(result.stdout), [[4096, 4096], [
                "-XX:MaxRAMPercentage=50", "-XX:MaxDirectMemorySize=256m", "argument with spaces"]])
            environment["SKYWRIGHT_NOFILE_LIMIT"] = "invalid"
            failed = subprocess.run(["sh", str(ROOT / "backend-deployment/src/main/docker/start")],
                                    env=environment, capture_output=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertEqual(failed.stdout, b"")

    def test_server_sets_limit_before_database_validation(self):
        script = ROOT / "skypilot-api-server-deployment/src/main/docker/startup/launch.py"
        result = subprocess.run(["python3", "-c", "\n".join([
            "import json, os, resource, runpy, sys",
            "os.environ.pop('SKYPILOT_DB_CONNECTION_URI', None)",
            "os.environ.pop('SKYWRIGHT_KUBECONFIG', None)",
            "try: runpy.run_path(sys.argv[1], run_name='__main__')",
            "except SystemExit as failure: assert failure.code == 78",
            "print(json.dumps(resource.getrlimit(resource.RLIMIT_NOFILE)))",
        ]), str(script)], check=True, capture_output=True, text=True)
        self.assertEqual(json.loads(result.stdout), [1024, 1024])

    def test_profiles_bound_all_application_containers(self):
        for profile in (LOCAL, PRODUCTION):
            with self.subTest(profile=profile):
                manifest = render(profile)
                backend = resource(manifest, "Deployment", "skywright-backend")
                server = resource(manifest, "Deployment", "skywright-skypilot-api-server")
                self.assertEqual(backend.count("memory: 4Gi"), 2)
                self.assertEqual(server.count("memory: 4Gi"), 2)
                self.assertIn("memory: 768Mi", server)
                self.assertIn("memory: 256Mi", server)
                self.assertEqual(server.count("limits:"), 5)
                self.assertEqual(server.count("requests:"), 5)
                if profile == LOCAL:
                    self.assertIn("type: Recreate", backend)
                    self.assertIn("type: Recreate", server)


if __name__ == "__main__":
    unittest.main()
