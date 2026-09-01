from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
BUILDER = REPOSITORY / "deployment" / "scripts" / "build-backend-image"
SKYPILOT_BUILDER = (
    REPOSITORY / "deployment" / "scripts" / "build-skypilot-api-server-image"
)


class ImageBuilderTest(unittest.TestCase):
    def test_builds_and_tests_the_exact_requested_image_before_pushing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            log = temporary / "commands.log"
            fake_maven = temporary / "mvnw"
            fake_maven.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$COMMAND_LOG\"\n",
                encoding="utf-8",
            )
            fake_maven.chmod(0o755)
            environment = os.environ | {
                "COMMAND_LOG": str(log),
                "IMAGE": "registry.example/skywright@sha256:" + "a" * 64,
                "PLATFORMS": "linux/amd64",
                "PUSH_IMAGE": "true",
                "SKYWRIGHT_MAVEN": str(fake_maven),
            }

            completed = subprocess.run(
                [str(BUILDER)],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            commands = log.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(commands), 2)
            self.assertIn("clean verify", commands[0])
            self.assertIn(
                "-Dbackend.container.image=" + environment["IMAGE"], commands[0]
            )
            self.assertIn("docker-maven-plugin:0.49.0:push", commands[1])

    def test_rejects_an_unsupported_platform_before_running_maven(self) -> None:
        environment = os.environ | {
            "IMAGE": "skywright-backend:test",
            "PLATFORMS": "linux/arm64",
            "PUSH_IMAGE": "false",
            "SKYWRIGHT_MAVEN": "/does/not/exist",
        }

        completed = subprocess.run(
            [str(BUILDER)],
            cwd=REPOSITORY,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("unsupported build platform", completed.stderr)

    def test_does_not_push_when_skaffold_requests_a_local_image(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            log = temporary / "commands.log"
            fake_maven = temporary / "mvnw"
            fake_maven.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$COMMAND_LOG\"\n",
                encoding="utf-8",
            )
            fake_maven.chmod(0o755)
            environment = os.environ | {
                "COMMAND_LOG": str(log),
                "IMAGE": "skywright-backend:test",
                "PLATFORMS": "linux/amd64",
                "PUSH_IMAGE": "false",
                "SKYWRIGHT_MAVEN": str(fake_maven),
            }

            completed = subprocess.run(
                [str(BUILDER)],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(len(log.read_text(encoding="utf-8").splitlines()), 1)

    def test_rejects_missing_skaffold_inputs_before_running_maven(self) -> None:
        environment = os.environ | {
            "PLATFORMS": "linux/amd64",
            "PUSH_IMAGE": "false",
            "SKYWRIGHT_MAVEN": "/does/not/exist",
        }
        environment.pop("IMAGE", None)

        completed = subprocess.run(
            [str(BUILDER)],
            cwd=REPOSITORY,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("did not provide IMAGE", completed.stderr)

    def test_propagates_a_maven_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fake_maven = Path(directory) / "mvnw"
            fake_maven.write_text("#!/bin/sh\nexit 17\n", encoding="utf-8")
            fake_maven.chmod(0o755)
            environment = os.environ | {
                "IMAGE": "skywright-backend:test",
                "PLATFORMS": "linux/amd64",
                "PUSH_IMAGE": "false",
                "SKYWRIGHT_MAVEN": str(fake_maven),
            }

            completed = subprocess.run(
                [str(BUILDER)],
                cwd=REPOSITORY,
                env=environment,
                check=False,
            )

            self.assertEqual(completed.returncode, 17)

    def test_builds_and_tests_the_skypilot_server_image_as_its_own_artifact(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            log = temporary / "commands.log"
            fake_maven = temporary / "mvnw"
            fake_maven.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$COMMAND_LOG\"\n",
                encoding="utf-8",
            )
            fake_maven.chmod(0o755)
            environment = os.environ | {
                "COMMAND_LOG": str(log),
                "IMAGE": "skywright-skypilot-api-server:test",
                "PLATFORMS": "linux/amd64",
                "PUSH_IMAGE": "false",
                "SKYWRIGHT_MAVEN": str(fake_maven),
            }

            completed = subprocess.run(
                [str(SKYPILOT_BUILDER)],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            command = log.read_text(encoding="utf-8")
            self.assertIn("clean verify", command)
            self.assertIn("-pl skypilot-api-server-deployment", command)
            self.assertIn(
                "-Dskypilot.container.image=skywright-skypilot-api-server:test",
                command,
            )


if __name__ == "__main__":
    unittest.main()
