from __future__ import annotations

import ast
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SYSTEM_TEST = REPOSITORY / "deployment" / "scripts" / "system-test"


class ControlPlaneSystemTestContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SYSTEM_TEST.read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.source)
        cls.functions = {
            node.name for node in cls.tree.body if isinstance(node, ast.FunctionDef)
        }

    def test_qualifies_the_real_control_plane_contracts(self) -> None:
        self.assertTrue(SYSTEM_TEST.stat().st_mode & 0o111)
        self.assertTrue(
            {
                "assert_version_pair",
                "assert_private_exposure",
                "assert_bridge_saturation",
                "assert_independent_restarts",
                "assert_skypilot_reachability_loss",
                "assert_version_mismatch",
                "assert_skypilot_database_loss",
                "assert_backend_database_behavior",
                "assert_normal_cleanup",
            }.issubset(self.functions)
        )
        self.assertIn('"run", "deployment"', self.source)
        self.assertIn("SKYWRIGHT_CAPABILITY_UNAVAILABLE", self.source)
        self.assertIn("SkyPilot capability available", self.source)
        self.assertIn("VERSION_MISMATCH", self.source)
        self.assertIn("REACHABILITY", self.source)
        self.assertIn("OrchestratorQualificationMain", self.source)
        self.assertIn('"bridge-busy"', self.source)
        self.assertIn('"skypilot-unavailable"', self.source)
        self.assertIn("skywright-system-test-network-probe", self.source)

    def test_uses_only_the_public_local_command_and_named_local_reset(self) -> None:
        self.assertIn('"local", "--context"', self.source)
        self.assertIn("reset skywright local control-plane state", self.source)
        self.assertNotIn("kind create cluster", self.source)
        self.assertNotIn("kind delete cluster", self.source)
        self.assertNotIn("delete namespace", self.source)


if __name__ == "__main__":
    unittest.main()
