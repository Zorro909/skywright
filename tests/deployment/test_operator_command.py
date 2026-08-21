from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.deployment import test_release_support


REPOSITORY = Path(__file__).resolve().parents[2]
COMMAND = REPOSITORY / "scripts" / "deploy"
BUNDLE_REF = "ghcr.io/zorro909/skywright-deployment@sha256:" + "c" * 64


class OperatorCommandTest(unittest.TestCase):
    def test_rejects_attestation_and_checksum_failures_before_mutation(self) -> None:
        for scenario in ("attestation", "checksum"):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                temporary = Path(directory)
                bundle = test_release_support.ReleaseSupportTest().build_bundle(temporary)
                if scenario == "checksum":
                    (bundle / "release.yaml").write_text("tampered\n", encoding="utf-8")
                log = temporary / "commands.jsonl"
                tool = temporary / "tool"
                tool.write_text(
                    """#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
name = pathlib.Path(sys.argv[0]).name
with pathlib.Path(os.environ['COMMAND_LOG']).open('a') as output:
    output.write(json.dumps({'name': name, 'args': sys.argv[1:]}) + '\\n')
if name == 'gh' and os.environ.get('FAIL_ATTESTATION') == 'true':
    raise SystemExit(1)
if name == 'oras':
    if sys.argv[1] == 'version': print('Version: 1.3.3')
    else:
        destination = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
        for source in pathlib.Path(os.environ['BUNDLE_SOURCE']).iterdir():
            shutil.copyfile(source, destination / source.name)
elif name == 'skaffold' and sys.argv[1] == 'version':
    print('v2.24.0')
""",
                    encoding="utf-8",
                )
                tool.chmod(0o755)
                environment = os.environ | {
                    "COMMAND_LOG": str(log),
                    "BUNDLE_SOURCE": str(bundle),
                    "FAIL_ATTESTATION": str(scenario == "attestation").lower(),
                }
                for name in ("oras", "gh", "skaffold", "kubectl"):
                    link = temporary / name
                    link.symlink_to(tool)
                    environment[f"SKYWRIGHT_{name.upper()}"] = str(link)

                completed = subprocess.run(
                    [
                        str(COMMAND),
                        "apply",
                        BUNDLE_REF,
                        "--context",
                        "production-context",
                    ],
                    cwd=REPOSITORY,
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                )

                self.assertNotEqual(completed.returncode, 0)
                calls = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertFalse(any(call["name"] == "kubectl" for call in calls))
                self.assertFalse(
                    any(call["name"] == "skaffold" and "apply" in call["args"] for call in calls)
                )

    def test_verifies_preflights_applies_and_records_the_exact_bundle_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            bundle = test_release_support.ReleaseSupportTest().build_bundle(temporary)
            log = temporary / "commands.jsonl"
            tool = temporary / "tool"
            tool.write_text(
                """#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
name = pathlib.Path(sys.argv[0]).name
with pathlib.Path(os.environ['COMMAND_LOG']).open('a') as output:
    output.write(json.dumps({'name': name, 'args': sys.argv[1:]}) + '\\n')
if name == 'oras':
    if sys.argv[1] == 'version': print('Version: 1.3.3')
    else:
        destination = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
        for source in pathlib.Path(os.environ['BUNDLE_SOURCE']).iterdir():
            shutil.copyfile(source, destination / source.name)
elif name == 'skaffold' and sys.argv[1] == 'version':
    print('v2.24.0')
elif name == 'kubectl' and 'namespace' in sys.argv:
    print(json.dumps({'metadata': {'name': 'skywright'}}))
elif name == 'kubectl' and 'secret' in sys.argv:
    keys = ['migrationUrl', 'migrationUsername', 'migrationPassword', 'runtimeUrl', 'runtimeUsername', 'runtimePassword']
    print(json.dumps({'data': {key: 'eA==' for key in keys}}))
elif name == 'kubectl' and 'ingressclass' in sys.argv:
    print(json.dumps({'metadata': {'name': 'contour'}}))
elif name == 'kubectl' and 'jsonpath=' in ' '.join(sys.argv):
    print('ghcr.io/zorro909/skywright-deployment@sha256:' + 'd' * 64)
""",
                encoding="utf-8",
            )
            tool.chmod(0o755)
            environment = os.environ | {
                "COMMAND_LOG": str(log),
                "BUNDLE_SOURCE": str(bundle),
            }
            for name in ("oras", "gh", "skaffold", "kubectl"):
                link = temporary / name
                link.symlink_to(tool)
                environment[f"SKYWRIGHT_{name.upper()}"] = str(link)

            completed = subprocess.run(
                [
                    str(COMMAND),
                    "apply",
                    BUNDLE_REF,
                    "--context",
                    "production-context",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            mutation = next(
                index
                for index, call in enumerate(calls)
                if call["name"] == "skaffold" and "apply" in call["args"]
            )
            for resource in ("namespace", "secret", "ingressclass"):
                self.assertTrue(
                    any(
                        call["name"] == "kubectl" and resource in call["args"]
                        for call in calls[:mutation]
                    )
                )
            self.assertIn("currently deployed bundle:", completed.stdout)
            annotation = calls[-1]
            self.assertEqual(annotation["name"], "kubectl")
            self.assertIn(f"skywright.io/deployment-bundle={BUNDLE_REF}", annotation["args"])


if __name__ == "__main__":
    unittest.main()
