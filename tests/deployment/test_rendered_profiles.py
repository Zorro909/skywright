from __future__ import annotations

import re
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


def resource(manifest: str, kind: str, name: str) -> str:
    for document in re.split(r"\n---\n", manifest):
        if f"kind: {kind}\n" in document and re.search(
            rf"(?m)^  name: {re.escape(name)}$", document
        ):
            return document
    raise AssertionError(f"rendered manifest has no {kind} named {name}")


class RenderedProfilesTest(unittest.TestCase):
    def test_documentation_names_local_retention_and_production_prerequisites(
        self,
    ) -> None:
        documentation = (REPOSITORY / "deployment" / "README.md").read_text(
            encoding="utf-8"
        )

        self.assertIn("skywright-local-skypilot-database", documentation)
        self.assertIn("skywright-skypilot-state", documentation)
        self.assertIn("skywright-production-skypilot-database", documentation)
        self.assertIn("operator-owned", documentation)
        self.assertIn("reset skywright local control-plane state", documentation)
        self.assertIn("does not yet authorize managed launches", documentation)

    def test_profiles_render_private_independently_restartable_skypilot_server(self) -> None:
        for profile in (LOCAL, PRODUCTION):
            with self.subTest(profile=profile.name):
                manifest = render(profile)
                backend = resource(manifest, "Deployment", "skywright-backend")
                server = resource(
                    manifest, "Deployment", "skywright-skypilot-api-server"
                )
                service = resource(
                    manifest, "Service", "skywright-skypilot-api-server"
                )

                self.assertIn("image: skywright-skypilot-api-server", server)
                self.assertIn("app.kubernetes.io/name: skywright-skypilot-api-server", server)
                self.assertNotIn("app.kubernetes.io/name: skywright-backend", server)
                self.assertNotIn("name: skypilot-api-server", backend)
                self.assertIn(
                    "value: http://skywright-skypilot-api-server:46580", backend
                )
                self.assertNotIn("SKYPILOT_DB_CONNECTION_URI", backend)
                self.assertNotIn("SKYWRIGHT_DATABASE_MIGRATION_", server)
                self.assertNotIn("SKYWRIGHT_DATABASE_RUNTIME_", server)
                self.assertIn("type: ClusterIP", service)
                self.assertIn("port: 46580", service)
                self.assertIn("targetPort: http", service)
                self.assertEqual(server.count("path: /api/health"), 3)
                self.assertIn("startupProbe:", server)
                self.assertIn("livenessProbe:", server)
                self.assertIn("readinessProbe:", server)
                self.assertIn("runAsNonRoot: true", server)
                self.assertIn("runAsUser: 10002", server)
                self.assertIn("runAsGroup: 10002", server)
                self.assertIn("fsGroup: 10002", server)
                self.assertIn("fsGroupChangePolicy: OnRootMismatch", server)
                self.assertIn("type: RuntimeDefault", server)
                self.assertIn("automountServiceAccountToken: false", server)
                self.assertIn("allowPrivilegeEscalation: false", server)
                self.assertIn("readOnlyRootFilesystem: true", server)
                self.assertRegex(server, r"drop:\n\s+- ALL")
                self.assertIn("terminationGracePeriodSeconds: 30", server)
                self.assertIn("mountPath: /tmp", server)
                self.assertIn("mountPath: /var/lib/skypilot", server)
                self.assertIn("claimName: skywright-skypilot-state", server)
                self.assertIn("medium: Memory", server)
                self.assertNotIn("resources:", server)
                self.assertNotIn("hostPort:", server)
                self.assertNotIn("nodePort:", server)
                self.assertNotIn("--enable-basic-auth", server)
                self.assertNotIn("SKYPILOT_AUTH_USER_HEADER", server)
                self.assertNotIn("serviceAccountName:", server)
                self.assertNotRegex(
                    server,
                    r"(?m)^\s*- name: (?:AWS|AZURE|GCP|GOOGLE|KUBECONFIG|REGISTRY|STORAGE)_",
                )

    def test_local_profile_has_the_backend_and_retained_postgresql_boundary(self) -> None:
        manifest = render(LOCAL)

        self.assertIn("image: skywright-backend", manifest)
        self.assertIn("imagePullPolicy: IfNotPresent", manifest)
        self.assertIn("image: postgres:18.1-bookworm@sha256:cc9f4143", manifest)
        self.assertIn("claimName: skywright-postgresql-data", manifest)
        self.assertIn("name: skywright-local-skypilot-database", manifest)
        self.assertIn("SKYPILOT_DATABASE_PASSWORD", manifest)
        self.assertIn("CREATE ROLE skypilot LOGIN PASSWORD", manifest)
        self.assertIn("CREATE DATABASE skypilot OWNER skypilot", manifest)
        self.assertNotIn("kind: Namespace", manifest)
        self.assertNotIn("kind: Ingress", manifest)
        self.assertIn("SPRING_PROFILES_ACTIVE", manifest)

    def test_production_profile_has_the_locked_down_backend_and_private_ingress(self) -> None:
        manifest = render(PRODUCTION)

        self.assertIn("host: skywright.internal", manifest)
        self.assertIn("ingressClassName: contour", manifest)
        self.assertNotIn("image: postgres:", manifest)
        self.assertNotIn("kind: PersistentVolumeClaim", manifest)
        self.assertNotIn("kind: Secret", manifest)
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
        self.assertIn("name: skywright-production-skypilot-database", manifest)

        ingress = resource(manifest, "Ingress", "skywright-backend")
        self.assertNotIn("skywright-skypilot-api-server", ingress)


if __name__ == "__main__":
    unittest.main()
