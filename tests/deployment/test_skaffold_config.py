from __future__ import annotations

import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]


class SkaffoldConfigTest(unittest.TestCase):
    def test_declares_the_two_control_plane_artifacts_and_two_explicit_profiles(self) -> None:
        config = (REPOSITORY / "skaffold.yaml").read_text(encoding="utf-8")

        self.assertIn("apiVersion: skaffold/v4beta14", config)
        self.assertEqual(config.count("- image: skywright-backend"), 1)
        self.assertEqual(
            config.count("- image: skywright-skypilot-api-server"), 1
        )
        self.assertIn("buildCommand: deployment/scripts/build-backend-image", config)
        self.assertIn(
            "buildCommand: deployment/scripts/build-skypilot-api-server-image",
            config,
        )
        self.assertIn("- name: local-kind", config)
        self.assertIn("- name: production", config)
        self.assertIn("statusCheck: true", config)
        self.assertIn("address: 127.0.0.1", config)
        self.assertNotIn("resourceName: skywright-skypilot-api-server", config)
        self.assertIn('"**/target/**"', config)
        self.assertIn('"**/node_modules/**"', config)


if __name__ == "__main__":
    unittest.main()
