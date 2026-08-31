from __future__ import annotations

import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


class SkyPilotServerImageContractTest(unittest.TestCase):
    def test_server_image_is_a_dedicated_module_using_the_shared_lock(self) -> None:
        parent = ET.parse(REPOSITORY / "pom.xml")
        modules = {
            module.text
            for module in parent.findall(".//m:modules/m:module", NAMESPACE)
        }
        self.assertIn("skypilot-api-server-deployment", modules)

        deployment = ET.parse(REPOSITORY / "skypilot-api-server-deployment/pom.xml")
        lock_source = deployment.findtext(
            ".//m:skypilot.lock.source", namespaces=NAMESPACE
        )
        self.assertEqual(
            lock_source,
            "${maven.multiModuleProjectDirectory}/graalpy-environment/graalpy.lock",
        )

    def test_build_rejects_a_server_version_absent_from_the_shared_lock(self) -> None:
        completed = subprocess.run(
            [
                str(REPOSITORY / "mvnw"),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                "skypilot-api-server-deployment",
                "-Dskypilot.version=99.99.99",
                "validate",
            ],
            cwd=REPOSITORY,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            "shared GraalPy lock does not contain skypilot==99.99.99",
            completed.stdout,
        )

    def test_version_comparison_treats_metacharacters_literally(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "graalpy.lock"
            lock.write_text("skypilot==0+13x0\n", encoding="utf-8")
            completed = subprocess.run(
                [
                    str(REPOSITORY / "mvnw"),
                    "--batch-mode",
                    "--no-transfer-progress",
                    "-pl",
                    "skypilot-api-server-deployment",
                    f"-Dskypilot.lock.source={lock}",
                    "validate",
                ],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
            )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            "shared GraalPy lock does not contain skypilot==0.13.0",
            completed.stdout,
        )


if __name__ == "__main__":
    unittest.main()
