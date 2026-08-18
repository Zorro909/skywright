from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import ModuleType

import tomllib

REPOSITORY = Path(__file__).resolve().parents[2]


def load_release_support() -> ModuleType:
    path = REPOSITORY / "environment-profiles" / "release_support.py"
    specification = importlib.util.spec_from_file_location(
        "profile_release_support", path
    )
    assert specification is not None and specification.loader is not None
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


class ReleaseValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.release_support = load_release_support()

    def repository(
        self, version: str = "0.1.0"
    ) -> tuple[tempfile.TemporaryDirectory[str], Path, str]:
        temporary = tempfile.TemporaryDirectory()
        repository = Path(temporary.name)
        subprocess.run(
            ("git", "init", "--initial-branch=main"),
            cwd=repository,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ("git", "config", "user.email", "test@example.com"),
            cwd=repository,
            check=True,
        )
        subprocess.run(
            ("git", "config", "user.name", "Test"), cwd=repository, check=True
        )
        profiles = repository / "environment-profiles"
        profiles.mkdir()
        (profiles / "VERSION").write_text(f"{version}\n", encoding="utf-8")
        (profiles / "CHANGELOG.md").write_text(
            f"# Environment Profile releases\n\n## {version}\n\nInitial release.\n",
            encoding="utf-8",
        )
        subprocess.run(("git", "add", "."), cwd=repository, check=True)
        subprocess.run(
            ("git", "commit", "-m", "release input"),
            cwd=repository,
            check=True,
            capture_output=True,
        )
        revision = subprocess.run(
            ("git", "rev-parse", "HEAD"),
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        subprocess.run(
            ("git", "tag", f"profile-v{version}"), cwd=repository, check=True
        )
        return temporary, repository, revision

    def test_stable_tag_reachable_from_main_matches_committed_inputs(self) -> None:
        temporary, repository, revision = self.repository()
        self.addCleanup(temporary.cleanup)

        release = self.release_support.validate_release(
            repository,
            "profile-v0.1.0",
            "main",
            revision,
        )

        self.assertEqual(release.version, "0.1.0")
        self.assertEqual(release.source_revision, revision)
        self.assertEqual(release.release_name, "Environment Profiles 0.1.0")

    def test_prerelease_and_workflow_source_mutation_are_rejected(self) -> None:
        temporary, repository, revision = self.repository()
        self.addCleanup(temporary.cleanup)

        with self.assertRaisesRegex(self.release_support.ReleaseError, "strict stable"):
            self.release_support.validate_release(
                repository, "profile-v0.1.0-rc.1", "main", revision
            )

        (repository / "environment-profiles" / "VERSION").write_text(
            "9.9.9\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(
            self.release_support.ReleaseError, "working tree differs"
        ):
            self.release_support.validate_release(
                repository, "profile-v0.1.0", "main", revision
            )

    def test_tag_must_be_reachable_from_main_and_agree_with_version_and_notes(
        self,
    ) -> None:
        temporary, repository, _revision = self.repository("1.2.3")
        self.addCleanup(temporary.cleanup)
        subprocess.run(
            ("git", "checkout", "--orphan", "other"),
            cwd=repository,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ("git", "rm", "-rf", "."), cwd=repository, check=True, capture_output=True
        )
        (repository / "other.txt").write_text("other\n", encoding="utf-8")
        subprocess.run(("git", "add", "."), cwd=repository, check=True)
        subprocess.run(
            ("git", "commit", "-m", "other"),
            cwd=repository,
            check=True,
            capture_output=True,
        )
        subprocess.run(("git", "tag", "profile-v9.0.0"), cwd=repository, check=True)

        with self.assertRaisesRegex(self.release_support.ReleaseError, "not reachable"):
            self.release_support.validate_release(
                repository, "profile-v9.0.0", "main", None
            )

        subprocess.run(
            ("git", "checkout", "main"), cwd=repository, check=True, capture_output=True
        )
        with self.assertRaisesRegex(
            self.release_support.ReleaseError, "does not match"
        ):
            self.release_support.validate_release(
                repository, "profile-v1.2.3", "main", "profile-v9.0.0"
            )


class ReleaseEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.release_support = load_release_support()
        self.release = self.release_support.ValidatedRelease(
            source_revision="a" * 40,
            tag="profile-v0.1.0",
            version="0.1.0",
            release_name="Environment Profiles 0.1.0",
        )
        self.definitions = {
            "profileDefinitionVersion": 1,
            "architecture": "linux/amd64",
            "pythonCompatibility": ">=3.10,<3.15",
            "sdk": "0.1.0",
            "pytorch": "2.12.0",
            "profiles": {
                "cuda": {
                    "acceleratorBackend": "cuda",
                    "acceleratorRuntime": "13.0",
                    "baseImage": "cuda@sha256:" + "1" * 64,
                    "containerfile": "environment-profiles/cuda/Containerfile",
                },
                "rocm": {
                    "acceleratorBackend": "rocm",
                    "acceleratorRuntime": "7.14",
                    "baseImage": "rocm@sha256:" + "2" * 64,
                    "containerfile": "environment-profiles/rocm/Containerfile",
                },
            },
        }

    def evidence(self, backend: str) -> dict[str, str]:
        return {
            "architecture": "linux/amd64",
            "backend": backend,
            "digest": "sha256:" + ("3" if backend == "cuda" else "4") * 64,
            "image": f"ghcr.io/zorro909/skywright-environment:0.1.0-{backend}",
            "provenance": f"{backend}.provenance.intoto.jsonl",
            "provenance_sha256": "5" * 64,
            "pytorch": "2.12.0",
            "runtime_version": "0.1.0",
            "sbom": f"{backend}.spdx.json",
            "sbom_attestation": f"{backend}.sbom.intoto.jsonl",
            "sbom_attestation_sha256": "7" * 64,
            "sbom_sha256": "6" * 64,
            "sdk": "0.1.0",
            "source_revision": "a" * 40,
        }

    def workflow(self) -> dict[str, str]:
        return {
            "name": "Environment Profile Release",
            "path": ".github/workflows/environment-profile-release.yml",
            "revision": "a" * 40,
            "run_id": "123",
            "run_attempt": "2",
        }

    def test_manifest_binds_both_exact_images_and_supply_chain_facts(self) -> None:
        manifest = self.release_support.create_manifest(
            self.release,
            self.definitions,
            {backend: self.evidence(backend) for backend in ("cuda", "rocm")},
            workflow=self.workflow(),
        )

        self.assertEqual(
            manifest["schema"],
            "https://skywright.dev/schemas/environment-profile-release/v1",
        )
        self.assertEqual(set(manifest["profiles"]), {"cuda", "rocm"})
        self.assertEqual(
            manifest["profiles"]["cuda"]["image"]["digest"], "sha256:" + "3" * 64
        )
        self.assertEqual(
            manifest["profiles"]["rocm"]["compatibility"]["accelerator_runtime"], "7.14"
        )
        self.assertEqual(
            manifest["profiles"]["cuda"]["supply_chain"]["sbom"]["format"],
            "SPDX-2.3-json",
        )

    def test_manifest_rejects_missing_backend_or_inconsistent_runtime_facts(
        self,
    ) -> None:
        with self.assertRaisesRegex(
            self.release_support.ReleaseError, "exactly cuda and rocm"
        ):
            self.release_support.create_manifest(
                self.release,
                self.definitions,
                {"cuda": self.evidence("cuda")},
                workflow=self.workflow(),
            )

        evidence = self.evidence("cuda")
        evidence["pytorch"] = "9.9.9"
        with self.assertRaisesRegex(self.release_support.ReleaseError, "PyTorch"):
            self.release_support.create_manifest(
                self.release,
                self.definitions,
                {"cuda": evidence, "rocm": self.evidence("rocm")},
                workflow=self.workflow(),
            )

    def test_exact_handoff_rejects_missing_extra_and_modified_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            (directory / "cuda-image.tar").write_bytes(b"cuda")
            digest = hashlib.sha256(b"cuda").hexdigest()
            checksums = directory / "SHA256SUMS"
            checksums.write_text(f"{digest}  cuda-image.tar\n", encoding="utf-8")
            self.release_support.verify_handoff(
                directory, checksums, {"cuda-image.tar"}
            )

            (directory / "cuda-image.tar").write_bytes(b"changed")
            with self.assertRaisesRegex(self.release_support.ReleaseError, "checksum"):
                self.release_support.verify_handoff(
                    directory, checksums, {"cuda-image.tar"}
                )

            (directory / "extra.txt").write_text("extra", encoding="utf-8")
            with self.assertRaisesRegex(
                self.release_support.ReleaseError, "unexpected"
            ):
                self.release_support.verify_handoff(
                    directory, checksums, {"cuda-image.tar", "extra.txt"}
                )

    def test_publication_is_idempotent_but_rejects_collisions(self) -> None:
        self.assertEqual(
            self.release_support.publication_action("sha256:" + "1" * 64, None),
            self.release_support.PublicationAction.PUBLISH,
        )
        self.assertEqual(
            self.release_support.publication_action(
                "sha256:" + "1" * 64, "sha256:" + "1" * 64
            ),
            self.release_support.PublicationAction.SKIP,
        )
        with self.assertRaisesRegex(
            self.release_support.ReleaseError, "conflicting content"
        ):
            self.release_support.publication_action(
                "sha256:" + "1" * 64, "sha256:" + "2" * 64
            )

        self.assertEqual(
            self.release_support.publication_plan(
                {"cuda": "sha256:" + "1" * 64, "rocm": "sha256:" + "2" * 64},
                {"cuda": "sha256:" + "1" * 64},
            ),
            {"cuda": "skip", "rocm": "publish"},
        )

    def test_accelerator_free_runtime_facts_match_declared_compatibility(self) -> None:
        inspect = {
            "Architecture": "amd64",
            "Config": {
                "Labels": {
                    "org.opencontainers.image.revision": "a" * 40,
                    "org.skywright.accelerator-backend": "cuda",
                    "org.skywright.accelerator-runtime": "13.0",
                    "org.skywright.architecture": "linux/amd64",
                    "org.skywright.base-image": self.definitions["profiles"]["cuda"][
                        "baseImage"
                    ],
                    "org.skywright.pytorch": "2.12.0",
                    "org.skywright.sdk": "0.1.0",
                }
            },
        }
        facts = {
            "accelerator_available": False,
            "architecture": "x86_64",
            "pytorch": "2.12.0",
            "sdk": "0.1.0",
            "source_revision": "a" * 40,
            "runtime_command_version": "0.1.0",
        }

        evidence = self.release_support.qualify_image(
            self.release, self.definitions, "cuda", inspect, facts
        )

        self.assertEqual(evidence["backend"], "cuda")
        self.assertEqual(evidence["runtime_version"], "0.1.0")

    def test_runtime_qualification_rejects_live_accelerator(self) -> None:
        inspect = {
            "Architecture": "amd64",
            "Config": {
                "Labels": {
                    "org.opencontainers.image.revision": "a" * 40,
                    "org.skywright.accelerator-backend": "rocm",
                    "org.skywright.accelerator-runtime": "7.14",
                    "org.skywright.architecture": "linux/amd64",
                    "org.skywright.base-image": self.definitions["profiles"]["rocm"][
                        "baseImage"
                    ],
                    "org.skywright.pytorch": "2.12.0",
                    "org.skywright.sdk": "0.1.0",
                }
            },
        }
        facts = {
            "accelerator_available": True,
            "architecture": "x86_64",
            "pytorch": "2.12.0",
            "sdk": "0.1.0",
            "source_revision": "a" * 40,
            "runtime_command_version": "0.1.0",
        }

        with self.assertRaisesRegex(
            self.release_support.ReleaseError, "without accelerator hardware"
        ):
            self.release_support.qualify_image(
                self.release, self.definitions, "rocm", inspect, facts
            )


class RepositoryProfileContractTest(unittest.TestCase):
    def test_committed_profile_and_sdk_versions_agree(self) -> None:
        definitions = json.loads(
            (REPOSITORY / "environment-profiles/manifest.json").read_text(
                encoding="utf-8"
            )
        )
        with (REPOSITORY / "sdk/pyproject.toml").open("rb") as source:
            sdk_version = tomllib.load(source)["project"]["version"]

        self.assertEqual(definitions["sdk"], sdk_version)

    def test_containerfiles_expose_the_declared_supported_facts(self) -> None:
        definitions = json.loads(
            (REPOSITORY / "environment-profiles/manifest.json").read_text(
                encoding="utf-8"
            )
        )

        for backend, profile in definitions["profiles"].items():
            with self.subTest(backend=backend):
                content = (REPOSITORY / profile["containerfile"]).read_text(
                    encoding="utf-8"
                )
                self.assertIn(f"FROM {profile['baseImage']}", content)
                self.assertIn(
                    f'LABEL org.skywright.accelerator-runtime="{profile["acceleratorRuntime"]}"',
                    content,
                )
                self.assertIn("RUN skywright-runtime --help", content)

    def test_release_workflow_has_one_strict_immutable_publication_surface(
        self,
    ) -> None:
        workflow = (
            REPOSITORY / ".github/workflows/environment-profile-release.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("environment: environment-profile-release", workflow)
        self.assertIn("tags:\n      - 'profile-v*'", workflow)
        self.assertNotIn("workflow_dispatch", workflow)
        self.assertNotIn(":latest", workflow)
        self.assertNotIn("manifest create", workflow)
        self.assertIn("retention-days: 30", workflow)
        self.assertIn("environment-profile-release-manifest.json", workflow)
        self.assertIn('skopeo copy "docker://$image" "oci-archive:$archive:$image"', workflow)
        self.assertIn("Discoverable release is incomplete; refusing in-place repair", workflow)
        self.assertIn("Require both images to be publicly digest-readable", workflow)


if __name__ == "__main__":
    unittest.main()
