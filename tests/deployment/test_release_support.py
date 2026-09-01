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
SOURCE_COMMIT = "a" * 40
SKYPILOT_VERSION = "0.13.0"


def rewrite_as_schema_v1(bundle: Path) -> None:
    manifest = bundle / "release.yaml"
    manifest.write_text(
        "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n"
        "  name: skywright-backend\nspec:\n  template:\n    spec:\n"
        f"      containers:\n        - image: {IMAGE}\n",
        encoding="utf-8",
    )
    artifacts = bundle / "build-artifacts.json"
    artifacts.write_text(
        json.dumps({"builds": [{"imageName": "skywright-backend", "tag": IMAGE}]}),
        encoding="utf-8",
    )
    metadata_path = bundle / "release-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    metadata["schemaVersion"] = 1
    metadata.pop("skypilotImage")
    metadata.pop("skypilotVersion")
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
                    "schemaVersion": 3,
                    "sourceRevision": SOURCE_COMMIT,
                    "skypilotVersion": SKYPILOT_VERSION,
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
                SOURCE_COMMIT,
                "--backend-image",
                IMAGE,
                "--skypilot-image",
                SKYPILOT_IMAGE,
                "--skypilot-version",
                SKYPILOT_VERSION,
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
            self.assertEqual(metadata["schemaVersion"], 3)
            self.assertEqual(metadata["backendImage"], IMAGE)
            self.assertEqual(metadata["skypilotImage"], SKYPILOT_IMAGE)
            self.assertEqual(metadata["skypilotVersion"], SKYPILOT_VERSION)
            artifacts = json.loads(
                (bundle / "build-artifacts.json").read_text(encoding="utf-8")
            )
            self.assertEqual(artifacts["sourceRevision"], SOURCE_COMMIT)
            self.assertEqual(artifacts["skypilotVersion"], SKYPILOT_VERSION)
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
            rewrite_as_schema_v1(bundle)

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

    def test_shared_pin_and_finalized_artifacts_record_one_pair(self) -> None:
        completed = subprocess.run(
            [
                str(SUPPORT),
                "shared-skypilot-version",
                "--pom",
                "pom.xml",
                "--lock",
                "graalpy-environment/graalpy.lock",
            ],
            cwd=REPOSITORY,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.stdout.strip(), SKYPILOT_VERSION)

        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            mismatched_lock = temporary / "graalpy.lock"
            mismatched_lock.write_text("skypilot==0.12.0\n", encoding="utf-8")
            mismatch = subprocess.run(
                [
                    str(SUPPORT),
                    "shared-skypilot-version",
                    "--pom",
                    "pom.xml",
                    "--lock",
                    str(mismatched_lock),
                ],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(mismatch.returncode, 0)
            self.assertIn("shared GraalPy lock", mismatch.stderr)

            raw = temporary / "raw.json"
            final = temporary / "final.json"
            raw.write_text(
                json.dumps(
                    {
                        "builds": [
                            {
                                "imageName": "skywright-skypilot-api-server",
                                "tag": SKYPILOT_IMAGE,
                            },
                            {"imageName": "skywright-backend", "tag": IMAGE},
                        ]
                    }
                ),
                encoding="utf-8",
            )
            subprocess.run(
                [
                    str(SUPPORT),
                    "finalize-build-artifacts",
                    "--input",
                    str(raw),
                    "--output",
                    str(final),
                    "--source-commit",
                    SOURCE_COMMIT,
                    "--skypilot-version",
                    SKYPILOT_VERSION,
                ],
                cwd=REPOSITORY,
                check=True,
            )
            finalized = json.loads(final.read_text(encoding="utf-8"))
            self.assertEqual(finalized["schemaVersion"], 3)
            self.assertEqual(finalized["sourceRevision"], SOURCE_COMMIT)
            self.assertEqual(finalized["skypilotVersion"], SKYPILOT_VERSION)
            self.assertEqual(
                [build["imageName"] for build in finalized["builds"]],
                ["skywright-backend", "skywright-skypilot-api-server"],
            )

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
bundle_digest = 'sha256:' + 'e' * 64
state = pathlib.Path(os.environ['ORAS_STATE'])
if sys.argv[1] == 'resolve' and '--oci-layout' in sys.argv:
    print(bundle_digest)
elif sys.argv[1] == 'resolve':
    values = json.loads(state.read_text()) if state.exists() else {}
    target = sys.argv[-1]
    if target not in values: raise SystemExit(1)
    print(values[target])
elif sys.argv[1] == 'cp':
    values = json.loads(state.read_text()) if state.exists() else {}
    source, target = sys.argv[-2:]
    values[target] = bundle_digest if '--from-oci-layout' in sys.argv else source.split('@', 1)[1]
    state.write_text(json.dumps(values, sort_keys=True))
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

            bundle_target = "ghcr.io/zorro909/skywright-deployment:v1.2.3"
            backend_target = "ghcr.io/zorro909/skywright-backend:v1.2.3"
            skypilot_target = (
                "ghcr.io/zorro909/skywright-skypilot-api-server:v1.2.3"
            )
            state.write_text(json.dumps({backend_target: "sha256:" + "b" * 64}))

            def publish() -> subprocess.CompletedProcess[str]:
                return subprocess.run(
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

            completed = publish()

            self.assertEqual(completed.returncode, 0, completed.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            copies = [call for call in calls if call[0] == "cp"]
            self.assertEqual(len(copies), 2)
            self.assertNotIn(backend_target, [call[-1] for call in copies])
            published = json.loads(state.read_text())
            self.assertEqual(published[bundle_target], "sha256:" + "e" * 64)
            self.assertEqual(published[backend_target], "sha256:" + "b" * 64)
            self.assertEqual(published[skypilot_target], "sha256:" + "c" * 64)
            push = next(call for call in calls if call[0] == "push")
            self.assertIn("application/vnd.skywright.deployment.bundle.v1", push)
            self.assertIn(
                "release.yaml:application/vnd.skywright.deployment.manifest.v1+yaml",
                push,
            )
            self.assertIn(
                "build-artifacts.json:application/vnd.skywright.skaffold.build-artifacts.v3+json",
                push,
            )
            self.assertIn(
                "release-metadata.json:application/vnd.skywright.deployment.metadata.v3+json",
                push,
            )

            repeated = publish()
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            repeated_calls = [
                json.loads(line) for line in log.read_text().splitlines()
            ]
            self.assertEqual(
                sum(call[0] == "cp" for call in repeated_calls), 2
            )

            for target in (bundle_target, backend_target, skypilot_target):
                with self.subTest(target=target):
                    state.write_text(json.dumps({target: "sha256:" + "f" * 64}))
                    copies_before = sum(
                        call[0] == "cp"
                        for call in [json.loads(line) for line in log.read_text().splitlines()]
                    )
                    collision = publish()
                    self.assertNotEqual(collision.returncode, 0)
                    self.assertIn("conflicting content", collision.stderr)
                    copies_after = sum(
                        call[0] == "cp"
                        for call in [json.loads(line) for line in log.read_text().splitlines()]
                    )
                    self.assertEqual(copies_after, copies_before)


if __name__ == "__main__":
    unittest.main()
