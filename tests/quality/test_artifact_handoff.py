from __future__ import annotations

import hashlib
import json
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
NATIVE = runpy.run_path(str(ROOT / "scripts/graalpy-environment"))
BACKEND = runpy.run_path(str(ROOT / "scripts/ci-backend"))


class NativeIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in (*NATIVE["INPUTS"], "pom.xml", "quality/toolchain.json"):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())

    def test_inherited_runtime_versions_invalidate_but_application_source_does_not(
        self,
    ):
        identify = NATIVE["identity"]
        original = identify(self.root)
        (self.root / "Application.java").write_text("different application source")
        self.assertEqual(original, identify(self.root))
        for name in ("graalpy.version", "skypilot.version"):
            pom = self.root / "pom.xml"
            before = pom.read_text()
            pom.write_text(before.replace(f"<{name}>", f"<{name}>changed-"))
            self.assertNotEqual(original, identify(self.root))
            pom.write_text(before)

    def test_matching_identity_cannot_hide_wrong_installed_runtime_or_packages(self):
        expected = NATIVE["identity"](self.root)
        resources = self.root / ".graalpy/resources"
        (resources / "venv").mkdir(parents=True)
        (resources / "identity.json").write_text(json.dumps(expected))
        (resources / "venv/contents").write_text(
            f"version={expected['runtime']['graalpy.version']}\n"
        )
        lock = (self.root / "graalpy-environment/graalpy.lock").read_text()
        (resources / "venv/installed.txt").write_text(lock)
        self.assertEqual(expected, NATIVE["verify"](self.root))
        (resources / "venv/contents").write_text("version=wrong\n")
        with self.assertRaisesRegex(ValueError, "runtime version"):
            NATIVE["verify"](self.root)
        (resources / "venv/contents").write_text(
            f"version={expected['runtime']['graalpy.version']}\n"
        )
        (resources / "venv/installed.txt").write_text(lock + "\nunexpected==1.0\n")
        with self.assertRaisesRegex(ValueError, "packages differ"):
            NATIVE["verify"](self.root)


class BackendHandoffTest(unittest.TestCase):
    def test_wrong_revision_run_missing_and_corrupted_artifacts_fail_before_consumption(
        self,
    ):
        validate = BACKEND["validate"]
        identity = {"revision": "tested-merge", "run_id": "123"}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            files = {}
            for suffix in (".jar", "-tests.jar"):
                name = f"backend/skywright-backend-0.1.0-SNAPSHOT{suffix}"
                path = directory / name
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(b"verified artifact")
                files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
            manifest = {"source": identity.copy(), "files": files}
            manifest_path = directory / "manifest.json"
            manifest_path.write_text(json.dumps(manifest))
            with patch.dict(validate.__globals__, source_identity=lambda: identity):
                validate(directory)
                for field in ("revision", "run_id"):
                    manifest["source"][field] = "other"
                    manifest_path.write_text(json.dumps(manifest))
                    with self.assertRaisesRegex(
                        ValueError, "different revision or workflow run"
                    ):
                        validate(directory)
                    manifest["source"][field] = identity[field]
                manifest_path.write_text(json.dumps(manifest))
                path.write_bytes(b"corrupt")
                with self.assertRaisesRegex(ValueError, "checksum"):
                    validate(directory)
                path.unlink()
                with self.assertRaisesRegex(ValueError, "incomplete"):
                    validate(directory)
