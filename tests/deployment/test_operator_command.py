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
ROLLBACK_REF = "ghcr.io/zorro909/skywright-deployment@sha256:" + "d" * 64


class OperatorCommandTest(unittest.TestCase):
    def test_rejects_attestation_and_checksum_failures_before_mutation(self) -> None:
        scenarios = {
            "bundle-attestation": BUNDLE_REF,
            "backend-attestation": test_release_support.IMAGE,
            "skypilot-attestation": test_release_support.SKYPILOT_IMAGE,
            "checksum": "",
        }
        for scenario, failed_subject in scenarios.items():
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
failed_subject = os.environ.get('FAIL_SUBJECT')
if name == 'gh' and failed_subject and failed_subject in ' '.join(sys.argv):
    raise SystemExit(1)
if name == 'oras':
    if sys.argv[1] == 'version': print('Version: 1.3.3')
    else:
        destination = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
        for source in pathlib.Path(os.environ['BUNDLE_SOURCE']).iterdir():
            shutil.copyfile(source, destination / source.name)
elif name == 'skaffold' and sys.argv[1] == 'version':
    print('v2.24.0')
elif name == 'skaffold' and sys.argv[1] == 'apply' and os.environ.get('FAIL_APPLY') == 'true':
    raise SystemExit(1)
""",
                    encoding="utf-8",
                )
                tool.chmod(0o755)
                environment = os.environ | {
                    "COMMAND_LOG": str(log),
                    "BUNDLE_SOURCE": str(bundle),
                    "FAIL_SUBJECT": failed_subject,
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
elif name == 'skaffold' and sys.argv[1] == 'apply' and os.environ.get('FAIL_APPLY') == 'true':
    raise SystemExit(1)
elif name == 'kubectl' and 'namespace' in sys.argv:
    print(json.dumps({'metadata': {'name': 'skywright'}}))
elif name == 'kubectl' and 'secret' in sys.argv:
    if 'skywright-production-skypilot-database' in sys.argv:
        if os.environ.get('MISSING_SKYPILOT_SECRET') == 'true':
            raise SystemExit(1)
        keys = ['connectionUri']
    else:
        keys = ['migrationUrl', 'migrationUsername', 'migrationPassword', 'runtimeUrl', 'runtimeUsername', 'runtimePassword']
    print(json.dumps({'data': {key: 'eA==' for key in keys}}))
elif name == 'kubectl' and 'persistentvolumeclaim' in sys.argv:
    if os.environ.get('MISSING_SKYPILOT_VOLUME') == 'true':
        raise SystemExit(1)
    print(json.dumps({'metadata': {'name': 'skywright-skypilot-state'}}))
elif name == 'kubectl' and 'ingressclass' in sys.argv:
    print(json.dumps({'metadata': {'name': 'contour'}}))
elif name == 'kubectl' and 'deployment' in sys.argv and '--output' in sys.argv and sys.argv[sys.argv.index('--output') + 1] == 'name':
    if os.environ.get('MISSING_SKYPILOT_DEPLOYMENT') != 'true':
        print('deployment.apps/skywright-skypilot-api-server')
elif name == 'kubectl' and 'jsonpath=' in ' '.join(sys.argv):
    if os.environ.get('MISSING_SKYPILOT_DEPLOYMENT') != 'true' or 'skywright-skypilot-api-server' not in sys.argv:
        digest = 'c' if os.environ.get('MISMATCHED_RECORDS') == 'true' and 'skywright-skypilot-api-server' in sys.argv else 'd'
        print('ghcr.io/zorro909/skywright-deployment@sha256:' + digest * 64)
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
            attestations = [
                call
                for call in calls
                if call["name"] == "gh" and "attestation" in call["args"]
            ]
            self.assertEqual(len(attestations), 3)
            attested_subjects = [call["args"][2] for call in attestations]
            self.assertIn(f"oci://{BUNDLE_REF}", attested_subjects)
            self.assertIn(
                f"oci://{test_release_support.IMAGE}", attested_subjects
            )
            self.assertIn(
                f"oci://{test_release_support.SKYPILOT_IMAGE}", attested_subjects
            )
            mutation = next(
                index
                for index, call in enumerate(calls)
                if call["name"] == "skaffold" and "apply" in call["args"]
            )
            for resource in ("namespace", "secret", "persistentvolumeclaim", "ingressclass"):
                self.assertTrue(
                    any(
                        call["name"] == "kubectl" and resource in call["args"]
                        for call in calls[:mutation]
                    )
                )
            preflight_arguments = [
                argument
                for call in calls[:mutation]
                if call["name"] == "kubectl"
                for argument in call["args"]
            ]
            self.assertIn("skywright-production-database", preflight_arguments)
            self.assertIn(
                "skywright-production-skypilot-database", preflight_arguments
            )
            self.assertIn("skywright-skypilot-state", preflight_arguments)
            self.assertIn("currently deployed bundle:", completed.stdout)
            annotation = calls[-1]
            self.assertEqual(annotation["name"], "kubectl")
            self.assertIn("deployment/skywright-backend", annotation["args"])
            self.assertIn(
                "deployment/skywright-skypilot-api-server", annotation["args"]
            )
            self.assertIn(f"skywright.io/deployment-bundle={BUNDLE_REF}", annotation["args"])
            self.assertFalse(
                any(
                    call["name"] == "skaffold"
                    and ({"build", "render"} & set(call["args"]))
                    for call in calls
                )
            )

            repeated = subprocess.run(
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
            rollback = subprocess.run(
                [
                    str(COMMAND),
                    "apply",
                    ROLLBACK_REF,
                    "--context",
                    "production-context",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            self.assertEqual(rollback.returncode, 0, rollback.stderr)
            replay_calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(
                sum(
                    call["name"] == "skaffold" and "apply" in call["args"]
                    for call in replay_calls
                ),
                3,
            )

            log.unlink()
            failed_apply = subprocess.run(
                [
                    str(COMMAND),
                    "apply",
                    BUNDLE_REF,
                    "--context",
                    "production-context",
                ],
                cwd=REPOSITORY,
                env=environment | {"FAIL_APPLY": "true"},
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(failed_apply.returncode, 0)
            failed_apply_calls = [
                json.loads(line) for line in log.read_text().splitlines()
            ]
            self.assertFalse(
                any(
                    call["name"] == "kubectl" and "annotate" in call["args"]
                    for call in failed_apply_calls
                )
            )

            log.unlink()
            mismatched = subprocess.run(
                [
                    str(COMMAND),
                    "apply",
                    BUNDLE_REF,
                    "--context",
                    "production-context",
                ],
                cwd=REPOSITORY,
                env=environment | {"MISMATCHED_RECORDS": "true"},
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(mismatched.returncode, 0)
            self.assertIn("record different bundle digests", mismatched.stderr)
            mismatch_calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertFalse(
                any(
                    call["name"] == "skaffold" and "apply" in call["args"]
                    for call in mismatch_calls
                )
            )

            for variable, diagnostic in (
                (
                    "MISSING_SKYPILOT_SECRET",
                    "production SkyPilot database Secret does not exist",
                ),
                (
                    "MISSING_SKYPILOT_VOLUME",
                    "SkyPilot retained-state persistent volume claim does not exist",
                ),
            ):
                with self.subTest(variable=variable):
                    log.unlink()
                    failed_environment = environment | {
                        variable: "true",
                        "SENSITIVE_DATABASE_VALUE": "must-not-appear",
                    }
                    failed = subprocess.run(
                        [
                            str(COMMAND),
                            "apply",
                            BUNDLE_REF,
                            "--context",
                            "production-context",
                        ],
                        cwd=REPOSITORY,
                        env=failed_environment,
                        check=False,
                        capture_output=True,
                        text=True,
                    )

                    self.assertNotEqual(failed.returncode, 0)
                    self.assertIn(diagnostic, failed.stderr)
                    self.assertNotIn("must-not-appear", failed.stderr)
                    failed_calls = [
                        json.loads(line) for line in log.read_text().splitlines()
                    ]
                    self.assertFalse(
                        any(
                            call["name"] == "skaffold" and "apply" in call["args"]
                            for call in failed_calls
                        )
                    )

            test_release_support.rewrite_as_schema_v1(bundle)
            log.unlink()
            legacy_rollback = subprocess.run(
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
            self.assertEqual(legacy_rollback.returncode, 0, legacy_rollback.stderr)
            legacy_calls = [json.loads(line) for line in log.read_text().splitlines()]
            legacy_annotation = next(
                call
                for call in legacy_calls
                if call["name"] == "kubectl" and "annotate" in call["args"]
            )
            self.assertIn("deployment/skywright-backend", legacy_annotation["args"])
            self.assertIn(
                "deployment/skywright-skypilot-api-server",
                legacy_annotation["args"],
            )

            log.unlink()
            legacy_without_server = subprocess.run(
                [
                    str(COMMAND),
                    "apply",
                    BUNDLE_REF,
                    "--context",
                    "production-context",
                ],
                cwd=REPOSITORY,
                env=environment | {"MISSING_SKYPILOT_DEPLOYMENT": "true"},
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(
                legacy_without_server.returncode, 0, legacy_without_server.stderr
            )
            no_server_calls = [
                json.loads(line) for line in log.read_text().splitlines()
            ]
            no_server_annotation = next(
                call
                for call in no_server_calls
                if call["name"] == "kubectl" and "annotate" in call["args"]
            )
            self.assertIn(
                "deployment/skywright-backend", no_server_annotation["args"]
            )
            self.assertNotIn(
                "deployment/skywright-skypilot-api-server",
                no_server_annotation["args"],
            )


if __name__ == "__main__":
    unittest.main()
