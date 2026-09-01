from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SUPPORT = REPOSITORY / "deployment" / "scripts" / "release-support"
IMAGE = "ghcr.io/zorro909/skywright-backend@sha256:" + "b" * 64
SKYPILOT_IMAGE = (
    "ghcr.io/zorro909/skywright-skypilot-api-server@sha256:" + "c" * 64
)


class ReleaseSupportTest(unittest.TestCase):
    def build_bundle(self, directory: Path, version: str = "v1.2.3") -> Path:
        release = directory / "release.yaml"
        artifacts = directory / "build-artifacts.json"
        output = directory / "bundle"
        release.write_text(
            "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: skywright-backend\n"
            f"spec:\n  template:\n    spec:\n      containers:\n        - image: {IMAGE}\n"
            "---\napiVersion: apps/v1\nkind: Deployment\nmetadata:\n"
            "  name: skywright-skypilot-api-server\n"
            "spec:\n  template:\n    spec:\n      containers:\n"
            f"        - image: {SKYPILOT_IMAGE}\n",
            encoding="utf-8",
        )
        artifacts.write_text(
            json.dumps(
                {
                    "builds": [
                        {"imageName": "skywright-backend", "tag": IMAGE},
                        {
                            "imageName": "skywright-skypilot-api-server",
                            "tag": SKYPILOT_IMAGE,
                        },
                    ]
                }
            ),
            encoding="utf-8",
        )
        subprocess.run(
            [
                str(SUPPORT),
                "build-bundle",
                "--release-yaml",
                str(release),
                "--build-artifacts",
                str(artifacts),
                "--output",
                str(output),
                "--version",
                version,
                "--source-commit",
                "a" * 40,
                "--backend-image",
                IMAGE,
                "--skypilot-image",
                SKYPILOT_IMAGE,
                "--workflow",
                "deployment-release.yml",
                "--run-id",
                "1234",
            ],
            cwd=REPOSITORY,
            check=True,
        )
        return output

    def test_builds_the_exact_versioned_bundle_layout_and_checksums(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = self.build_bundle(Path(directory))

            self.assertEqual(
                {path.name for path in bundle.iterdir()},
                {
                    "release.yaml",
                    "build-artifacts.json",
                    "release-metadata.json",
                    "SHA256SUMS",
                },
            )
            metadata = json.loads(
                (bundle / "release-metadata.json").read_text(encoding="utf-8")
            )
            self.assertEqual(metadata["schemaVersion"], 2)
            self.assertEqual(metadata["backendImage"], IMAGE)
            self.assertEqual(metadata["skypilotImage"], SKYPILOT_IMAGE)
            self.assertEqual(metadata["profile"], "production")
            self.assertEqual(metadata["hostname"], "skywright.internal")
            self.assertFalse(metadata["prerelease"])
            checksums = (bundle / "SHA256SUMS").read_text(encoding="utf-8")
            self.assertNotIn("SHA256SUMS", checksums)
            for filename in (
                "release.yaml",
                "build-artifacts.json",
                "release-metadata.json",
            ):
                self.assertIn(filename, checksums)

    def test_verification_rejects_changed_payload_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = self.build_bundle(Path(directory), "v1.2.3-rc.1")
            (bundle / "release.yaml").write_text("changed\n", encoding="utf-8")

            completed = subprocess.run(
                [str(SUPPORT), "verify-bundle", "--directory", str(bundle)],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("checksum", completed.stderr.lower())

    def test_verification_accepts_a_retained_schema_v1_rollback_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = self.build_bundle(Path(directory))
            manifest = bundle / "release.yaml"
            manifest.write_text(
                "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n"
                "  name: skywright-backend\nspec:\n  template:\n    spec:\n"
                f"      containers:\n        - image: {IMAGE}\n",
                encoding="utf-8",
            )
            artifacts = bundle / "build-artifacts.json"
            artifacts.write_text(
                json.dumps(
                    {"builds": [{"imageName": "skywright-backend", "tag": IMAGE}]}
                ),
                encoding="utf-8",
            )
            metadata_path = bundle / "release-metadata.json"
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            metadata["schemaVersion"] = 1
            metadata.pop("skypilotImage")
            metadata_path.write_text(
                json.dumps(metadata, sort_keys=True, indent=2) + "\n",
                encoding="utf-8",
            )
            checksums = bundle / "SHA256SUMS"
            checksums.write_text(
                "".join(
                    subprocess.run(
                        ["sha256sum", str(bundle / filename)],
                        check=True,
                        capture_output=True,
                        text=True,
                    ).stdout.split()[0]
                    + f"  {filename}\n"
                    for filename in (
                        "release.yaml",
                        "build-artifacts.json",
                        "release-metadata.json",
                    )
                ),
                encoding="utf-8",
            )

            completed = subprocess.run(
                [str(SUPPORT), "verify-bundle", "--directory", str(bundle)],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_verification_rejects_a_manifest_with_an_extra_image_reference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = self.build_bundle(Path(directory))
            manifest = bundle / "release.yaml"
            manifest.write_text(
                manifest.read_text(encoding="utf-8")
                + "---\napiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - image: example.invalid/other:latest\n",
                encoding="utf-8",
            )
            checksums = bundle / "SHA256SUMS"
            lines = checksums.read_text(encoding="utf-8").splitlines()
            lines[0] = subprocess.run(
                ["sha256sum", str(manifest)],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.split()[0] + "  release.yaml"
            checksums.write_text("\n".join(lines) + "\n", encoding="utf-8")

            completed = subprocess.run(
                [str(SUPPORT), "verify-bundle", "--directory", str(bundle)],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("exactly the release control-plane images", completed.stderr)

    def test_verification_rejects_invalid_release_provenance_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = self.build_bundle(Path(directory))
            metadata_path = bundle / "release-metadata.json"
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            metadata["workflow"] = "untrusted.yml"
            metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
            checksums = bundle / "SHA256SUMS"
            lines = checksums.read_text(encoding="utf-8").splitlines()
            checksum = subprocess.run(
                ["sha256sum", str(metadata_path)],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.split()[0]
            lines[2] = checksum + "  release-metadata.json"
            checksums.write_text("\n".join(lines) + "\n", encoding="utf-8")

            completed = subprocess.run(
                [str(SUPPORT), "verify-bundle", "--directory", str(bundle)],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("deployment release workflow", completed.stderr)

    def test_rejects_noncanonical_semver_tags(self) -> None:
        for version in ("1.2.3", "v01.2.3", "v1.2", "v1.2.3+build"):
            with self.subTest(version=version):
                completed = subprocess.run(
                    [str(SUPPORT), "classify-version", version],
                    cwd=REPOSITORY,
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(completed.returncode, 0)

    def test_publication_recovers_missing_content_and_rejects_a_collision(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            bundle = self.build_bundle(temporary)
            fake_oras = temporary / "oras"
            fake_oras.write_text(
                """#!/usr/bin/env python3
import json, os, pathlib, sys
log = pathlib.Path(os.environ['ORAS_LOG'])
with log.open('a') as output: output.write(json.dumps(sys.argv[1:]) + '\\n')
expected = 'sha256:' + 'e' * 64
state = pathlib.Path(os.environ['ORAS_STATE'])
if sys.argv[1] == 'resolve' and '--oci-layout' in sys.argv:
    print(expected)
elif sys.argv[1] == 'resolve':
    if state.exists(): print(state.read_text())
    elif os.environ.get('EXISTING_DIGEST'): print(os.environ['EXISTING_DIGEST'])
    else: raise SystemExit(1)
elif sys.argv[1] == 'cp':
    state.write_text(expected)
""",
                encoding="utf-8",
            )
            fake_oras.chmod(0o755)
            log = temporary / "oras.log"
            state = temporary / "registry-state"
            environment = os.environ | {
                "SKYWRIGHT_ORAS": str(fake_oras),
                "ORAS_LOG": str(log),
                "ORAS_STATE": str(state),
            }

            completed = subprocess.run(
                [
                    str(SUPPORT),
                    "publish-bundle",
                    "--directory",
                    str(bundle),
                    "--repository",
                    "ghcr.io/zorro909/skywright-deployment",
                    "--version",
                    "v1.2.3",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertTrue(any(call[0] == "cp" for call in calls))
            push = next(call for call in calls if call[0] == "push")
            self.assertIn("application/vnd.skywright.deployment.bundle.v1", push)
            self.assertIn(
                "release.yaml:application/vnd.skywright.deployment.manifest.v1+yaml",
                push,
            )

            repeated = subprocess.run(
                [
                    str(SUPPORT),
                    "publish-bundle",
                    "--directory",
                    str(bundle),
                    "--repository",
                    "ghcr.io/zorro909/skywright-deployment",
                    "--version",
                    "v1.2.3",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            repeated_calls = [
                json.loads(line) for line in log.read_text().splitlines()
            ]
            self.assertEqual(
                sum(call[0] == "cp" for call in repeated_calls), 1
            )

            state.unlink()
            environment["EXISTING_DIGEST"] = "sha256:" + "f" * 64
            collision = subprocess.run(
                [
                    str(SUPPORT),
                    "publish-bundle",
                    "--directory",
                    str(bundle),
                    "--repository",
                    "ghcr.io/zorro909/skywright-deployment",
                    "--version",
                    "v1.2.3",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(collision.returncode, 0)
            self.assertIn("conflicting content", collision.stderr)


if __name__ == "__main__":
    unittest.main()
