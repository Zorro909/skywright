from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
LOCAL = REPOSITORY / "deployment" / "overlays" / "local-kind"
PRODUCTION = REPOSITORY / "deployment" / "overlays" / "production"


def render(path: Path) -> str:
    return subprocess.run(
        ["kubectl", "kustomize", str(path)],
        cwd=REPOSITORY,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


class RenderedProfilesTest(unittest.TestCase):
    def test_local_profile_has_the_backend_and_retained_postgresql_boundary(self) -> None:
        manifest = render(LOCAL)

        self.assertIn("image: skywright-backend", manifest)
        self.assertIn("imagePullPolicy: IfNotPresent", manifest)
        self.assertIn("image: postgres:18.1-bookworm@sha256:cc9f4143", manifest)
        self.assertIn("claimName: skywright-postgresql-data", manifest)
        self.assertNotIn("kind: Namespace", manifest)
        self.assertNotIn("kind: Ingress", manifest)
        self.assertIn("SPRING_PROFILES_ACTIVE", manifest)

    def test_production_profile_has_the_locked_down_backend_and_private_ingress(self) -> None:
        manifest = render(PRODUCTION)

        self.assertIn("host: skywright.internal", manifest)
        self.assertIn("ingressClassName: contour", manifest)
        self.assertNotIn("image: postgres:", manifest)
        self.assertNotIn("kind: PersistentVolumeClaim", manifest)
        self.assertNotIn("kind: Namespace", manifest)
        self.assertIn("runAsUser: 10001", manifest)
        self.assertIn("runAsGroup: 10001", manifest)
        self.assertIn("readOnlyRootFilesystem: true", manifest)
        self.assertIn("allowPrivilegeEscalation: false", manifest)
        self.assertIn("type: RuntimeDefault", manifest)
        self.assertIn("automountServiceAccountToken: false", manifest)
        self.assertIn("medium: Memory", manifest)
        self.assertIn("sizeLimit: 64Mi", manifest)
        self.assertIn("path: /livez", manifest)
        self.assertIn("path: /readyz", manifest)
        self.assertIn("terminationGracePeriodSeconds: 30", manifest)
        self.assertNotIn("resources:", manifest)
        self.assertIn("name: skywright-production-database", manifest)


if __name__ == "__main__":
    unittest.main()
