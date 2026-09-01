from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
COMMAND = REPOSITORY / "scripts" / "deploy"


class LocalCommandTest(unittest.TestCase):
    def fake_tools(self, directory: Path) -> tuple[dict[str, str], Path]:
        log = directory / "commands.jsonl"
        tool = directory / "tool"
        tool.write_text(
            """#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
with pathlib.Path(os.environ['COMMAND_LOG']).open('a') as output:
    output.write(json.dumps({'name': name, 'args': sys.argv[1:], 'stdin': sys.stdin.read() if ('-f' in sys.argv or '--filename' in sys.argv) and '-' in sys.argv else '', 'docker_host': os.environ.get('DOCKER_HOST'), 'kind_provider': os.environ.get('KIND_EXPERIMENTAL_PROVIDER')}) + '\\n')
if name == 'skaffold' and sys.argv[1] == 'version':
    print('v2.24.0')
elif name == 'kubectl' and sys.argv[1:3] == ['config', 'current-context']:
    print('kind-kind-cluster')
elif name == 'kubectl' and 'storageclass' in sys.argv:
    print(json.dumps({'items': [{'metadata': {'name': 'standard', 'annotations': {'storageclass.kubernetes.io/is-default-class': 'true'}}}]}))
elif name == 'kubectl' and 'namespace' in sys.argv and 'jsonpath=' in ' '.join(sys.argv):
    print(os.environ.get('LOCAL_NAMESPACE_MARKER', 'true'))
elif name == 'kubectl' and 'skywright.io/local-retained-data=true' in sys.argv:
    print('persistentvolumeclaim/skywright-postgresql-data')
    print('persistentvolumeclaim/skywright-skypilot-state')
    print('secret/skywright-local-database')
    print('secret/skywright-local-skypilot-database')
elif name == 'kubectl' and 'get' in sys.argv and 'secret' in sys.argv:
    secret = sys.argv[sys.argv.index('secret') + 1]
    existing = {
        'skywright-local-database': 'EXISTING_SECRET',
        'skywright-local-skypilot-database': 'EXISTING_SKYPILOT_SECRET',
    }
    if os.environ.get(existing.get(secret, '')) != 'true':
        raise SystemExit(1)
elif name == 'kind':
    print('kind-cluster')
elif name == 'podman' and 'info' in sys.argv:
    print(json.dumps({'host': {'security': {'rootless': True}, 'arch': 'amd64'}}))
elif name == 'podman' and 'inspect' in sys.argv:
    print(json.dumps([{'Config': {'Labels': {'io.x-k8s.kind.role': 'control-plane'}}}]))
""",
            encoding="utf-8",
        )
        tool.chmod(0o755)
        environment = os.environ | {
            "COMMAND_LOG": str(log),
            "XDG_RUNTIME_DIR": str(directory),
        }
        binaries = directory / "bin"
        binaries.mkdir()
        for name in ("skaffold", "kubectl", "kind", "podman"):
            link = binaries / name
            link.symlink_to(tool)
            environment[f"SKYWRIGHT_{name.upper()}"] = str(link)
        socket = directory / "podman" / "podman.sock"
        socket.parent.mkdir()
        socket.touch()
        return environment, log

    def test_local_bootstraps_retained_data_and_runs_skaffold_on_loopback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment, log = self.fake_tools(Path(directory))

            completed = subprocess.run(
                [str(COMMAND), "local", "--context", "kind-kind-cluster"],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            applies = [call for call in calls if call["name"] == "kubectl" and "apply" in call["args"]]
            self.assertEqual(len(applies), 5)
            self.assertIn("skywright-local-database", applies[1]["stdin"])
            self.assertNotIn("change-me", applies[1]["stdin"])
            self.assertIn("skywright-local-skypilot-database", applies[2]["stdin"])
            self.assertIn("connectionUri", applies[2]["stdin"])
            self.assertIn('skywright.io/local-retained-data: "true"', applies[2]["stdin"])
            self.assertIn("skywright-postgresql-data", applies[3]["stdin"])
            self.assertIn("skywright-skypilot-state", applies[4]["stdin"])
            self.assertIn('skywright.io/local-retained-data: "true"', applies[4]["stdin"])
            skaffold = calls[-1]
            self.assertEqual(skaffold["name"], "skaffold")
            self.assertIn("local-kind", skaffold["args"])
            self.assertEqual(
                skaffold["docker_host"],
                "unix://" + str(Path(directory) / "podman" / "podman.sock"),
            )
            self.assertEqual(skaffold["kind_provider"], "podman")

    def test_local_reuses_existing_database_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment, log = self.fake_tools(Path(directory))
            environment["EXISTING_SECRET"] = "true"
            environment["EXISTING_SKYPILOT_SECRET"] = "true"

            completed = subprocess.run(
                [str(COMMAND), "local", "--context", "kind-kind-cluster"],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            applied = "\n".join(
                call["stdin"] for call in calls if call["name"] == "kubectl"
            )
            self.assertNotIn("kind: Secret", applied)

    def test_reset_deletes_only_the_expanded_owned_retained_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment, log = self.fake_tools(Path(directory))

            completed = subprocess.run(
                [
                    str(COMMAND),
                    "reset-local-state",
                    "--context",
                    "kind-kind-cluster",
                    "--confirm",
                    "reset skywright local control-plane state",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            deletion = next(call for call in calls if "delete" in call["args"])
            self.assertIn("persistentvolumeclaim/skywright-postgresql-data", deletion["args"])
            self.assertIn("persistentvolumeclaim/skywright-skypilot-state", deletion["args"])
            self.assertIn("secret/skywright-local-database", deletion["args"])
            self.assertIn("secret/skywright-local-skypilot-database", deletion["args"])
            self.assertNotIn("namespace", deletion["args"])

    def test_reset_refuses_an_unmarked_namespace_before_deletion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment, log = self.fake_tools(Path(directory))
            environment["LOCAL_NAMESPACE_MARKER"] = "false"

            completed = subprocess.run(
                [
                    str(COMMAND),
                    "reset-local-state",
                    "--context",
                    "kind-kind-cluster",
                    "--confirm",
                    "reset skywright local control-plane state",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("not marked as a local environment", completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertFalse(any("delete" in call["args"] for call in calls))

    def test_reset_rejects_the_wrong_confirmation_before_deletion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment, log = self.fake_tools(Path(directory))

            completed = subprocess.run(
                [
                    str(COMMAND),
                    "reset-local-state",
                    "--context",
                    "kind-kind-cluster",
                    "--confirm",
                    "reset",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("confirmation did not match", completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertFalse(any("delete" in call["args"] for call in calls))

    def test_old_database_confirmation_cannot_delete_expanded_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment, log = self.fake_tools(Path(directory))

            completed = subprocess.run(
                [
                    str(COMMAND),
                    "reset-local-state",
                    "--context",
                    "kind-kind-cluster",
                    "--confirm",
                    "reset skywright local database",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("confirmation did not match", completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertFalse(any("delete" in call["args"] for call in calls))

    def test_reset_refuses_a_production_context_before_deletion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment, log = self.fake_tools(Path(directory))

            completed = subprocess.run(
                [
                    str(COMMAND),
                    "reset-local-state",
                    "--context",
                    "production-context",
                    "--confirm",
                    "reset skywright local control-plane state",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("requires a kind context", completed.stderr)
            if log.exists():
                calls = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertFalse(any("delete" in call["args"] for call in calls))


if __name__ == "__main__":
    unittest.main()
