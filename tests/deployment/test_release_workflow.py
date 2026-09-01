from __future__ import annotations

import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]


class ReleaseWorkflowTest(unittest.TestCase):
    def test_release_builds_renders_publishes_and_attests_one_commit(self) -> None:
        workflow = (
            REPOSITORY / ".github" / "workflows" / "deployment-release.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("attestations: write", workflow)
        self.assertIn("packages: write", workflow)
        self.assertIn("Build main deployment image", workflow)
        self.assertIn("branches:\n      - main", workflow)
        self.assertIn("skaffold build", workflow)
        self.assertIn("skaffold render", workflow)
        self.assertIn("release-support build-bundle", workflow)
        self.assertIn("release-support publish-bundle", workflow)
        self.assertEqual(workflow.count("uses: actions/attest@"), 3)
        self.assertIn("subject-name: ghcr.io/zorro909/skywright-deployment", workflow)
        self.assertIn("subject-name: ghcr.io/zorro909/skywright-backend", workflow)
        self.assertIn(
            "subject-name: ghcr.io/zorro909/skywright-skypilot-api-server",
            workflow,
        )
        self.assertIn("git merge-base --is-ancestor", workflow)
        self.assertIn("scripts/quality identity", workflow)
        self.assertIn("gh run list", workflow)
        self.assertIn("workflow quality.yml", workflow)


if __name__ == "__main__":
    unittest.main()
