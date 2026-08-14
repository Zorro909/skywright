from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
QUALITY = REPOSITORY / "scripts" / "quality"


def run_quality(
    *arguments: str,
    check: bool = True,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(QUALITY), *arguments],
        cwd=REPOSITORY,
        check=check,
        capture_output=True,
        text=True,
        env=env,
    )


class PlanningTest(unittest.TestCase):
    def plan(self, *paths: str) -> dict[str, object]:
        arguments = ["plan", "--format", "json"]
        for path in paths:
            arguments.extend(("--changed-file", path))
        return json.loads(run_quality(*arguments).stdout)

    def test_root_tooling_change_fans_out_to_every_active_check(self) -> None:
        plan = self.plan("pom.xml")

        self.assertEqual(plan["categories"], ["shared-tooling"])
        self.assertEqual(
            {name: check["applicable"] for name, check in plan["checks"].items()},
            {"frontend": True, "java": True, "sdk": True, "security": True},
        )

    def test_component_and_future_component_changes_have_explicit_plans(self) -> None:
        cases = {
            "api/skywright-api/openapi.yaml": (
                ["api"],
                {"frontend": True, "java": True, "sdk": False, "security": True},
            ),
            "backend/src/main/App.java": (
                ["backend"],
                {"frontend": False, "java": True, "sdk": False, "security": True},
            ),
            "frontend/src/app/app.ts": (
                ["frontend"],
                {"frontend": True, "java": False, "sdk": False, "security": True},
            ),
            "sdk/src/skywright/__init__.py": (
                ["sdk"],
                {"frontend": False, "java": False, "sdk": True, "security": True},
            ),
            "fixtures/structural-overlay/cases.json": (
                ["fixture"],
                {"frontend": False, "java": True, "sdk": True, "security": True},
            ),
            "environment-profiles/cuda/Containerfile": (
                ["profile"],
                {"frontend": False, "java": False, "sdk": True, "security": True},
            ),
            "docs/operator-guide.md": (
                ["documentation"],
                {
                    "frontend": False,
                    "java": False,
                    "sdk": False,
                    "security": False,
                },
            ),
        }

        for path, (categories, applicability) in cases.items():
            with self.subTest(path=path):
                plan = self.plan(path)
                self.assertEqual(plan["categories"], categories)
                self.assertEqual(
                    {
                        name: check["applicable"]
                        for name, check in plan["checks"].items()
                    },
                    applicability,
                )

    def test_unknown_path_is_treated_as_shared_to_fail_safe(self) -> None:
        plan = self.plan("new-project-part/file.txt")

        self.assertEqual(plan["categories"], ["shared-tooling"])
        self.assertTrue(all(check["applicable"] for check in plan["checks"].values()))

    def test_root_markdown_is_documentation_only(self) -> None:
        plan = self.plan("README.md", "SECURITY.md")

        self.assertEqual(plan["categories"], ["documentation"])
        self.assertTrue(
            all(not check["applicable"] for check in plan["checks"].values())
        )


class AggregationTest(unittest.TestCase):
    def aggregate(
        self, plan: dict[str, object], *results: str
    ) -> subprocess.CompletedProcess[str]:
        return run_quality(
            "aggregate",
            "--plan",
            json.dumps(plan, separators=(",", ":")),
            *(argument for result in results for argument in ("--result", result)),
            check=False,
        )

    def test_deliberate_irrelevance_is_visible_and_successful(self) -> None:
        plan = json.loads(
            run_quality(
                "plan", "--format", "json", "--changed-file", "docs/guide.md"
            ).stdout
        )

        completed = self.aggregate(
            plan,
            "java=skipped",
            "frontend=skipped",
            "sdk=skipped",
            "security=skipped",
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("INAPPLICABLE java", completed.stdout)

    def test_every_non_success_outcome_fails_an_applicable_check(self) -> None:
        plan = json.loads(
            run_quality(
                "plan", "--format", "json", "--changed-file", "backend/App.java"
            ).stdout
        )

        for outcome in ("failure", "cancelled", "skipped"):
            with self.subTest(outcome=outcome):
                completed = self.aggregate(
                    plan,
                    f"java={outcome}",
                    "frontend=skipped",
                    "sdk=skipped",
                    "security=success",
                )
                self.assertNotEqual(completed.returncode, 0)
                self.assertIn(f"FAILED java ({outcome})", completed.stdout)


class LocalRunnerTest(unittest.TestCase):
    def test_missing_frontend_tools_fail_explicitly_without_hiding_other_checks(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            completed = run_quality(
                "run",
                "frontend",
                "--dry-run",
                check=False,
                env={**os.environ, "PATH": temporary_directory},
            )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("INAPPLICABLE java", completed.stdout)
        self.assertIn(
            "FAILED frontend: required executable 'node' is unavailable",
            completed.stdout,
        )
        self.assertIn("INAPPLICABLE sdk", completed.stdout)

    def test_wrong_uv_version_fails_before_the_native_sdk_command_runs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            fake_uv = Path(temporary_directory) / "uv"
            fake_uv.write_text("#!/bin/sh\necho 'uv 9.9.9'\n", encoding="utf-8")
            fake_uv.chmod(0o755)
            completed = run_quality(
                "run",
                "sdk",
                "--dry-run",
                check=False,
                env={**os.environ, "PATH": temporary_directory},
            )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("requires uv 0.8.8; received uv 9.9.9", completed.stdout)


class SourceIdentityTest(unittest.TestCase):
    def test_pull_request_identity_uses_merge_revision_and_keeps_head_metadata(
        self,
    ) -> None:
        completed = run_quality(
            "identity",
            "--event",
            "pull_request",
            "--tested-revision",
            "merge-sha",
            "--head-revision",
            "head-sha",
            "--format",
            "json",
        )

        self.assertEqual(
            json.loads(completed.stdout),
            {
                "event": "pull_request",
                "head_revision": "head-sha",
                "tested_revision": "merge-sha",
            },
        )

    def test_tag_identity_requires_and_records_the_tag_commit(self) -> None:
        completed = run_quality(
            "identity",
            "--event",
            "tag",
            "--tested-revision",
            "tag-commit",
            "--tag-commit",
            "tag-commit",
            "--format",
            "json",
        )
        self.assertEqual(json.loads(completed.stdout)["tested_revision"], "tag-commit")

        mismatch = run_quality(
            "identity",
            "--event",
            "tag",
            "--tested-revision",
            "other-commit",
            "--tag-commit",
            "tag-commit",
            check=False,
        )
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn("tag commit", mismatch.stderr)


class SecurityPolicyTest(unittest.TestCase):
    def write_policy(self, directory: str, suppression: dict[str, object]) -> Path:
        policy = Path(directory) / "suppressions.json"
        policy.write_text(
            json.dumps({"version": 1, "suppressions": [suppression]}),
            encoding="utf-8",
        )
        return policy

    def test_suppression_requires_narrow_scope_issue_and_near_expiry(self) -> None:
        valid = {
            "id": "GHSA-cfgh-2345-6789",
            "manifest": "frontend/pnpm-lock.yaml",
            "package_url": "pkg:npm/example",
            "issue": "https://github.com/Zorro909/skywright/issues/123",
            "expires": (
                datetime.now(tz=timezone.utc).date() + timedelta(days=30)
            ).isoformat(),
            "reason": "Waiting for an upstream compatible fix.",
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            policy = self.write_policy(temporary_directory, valid)
            completed = run_quality("security-policy", "--policy", str(policy))
            self.assertIn("1 valid suppression", completed.stdout)

            invalid_cases = {
                "package_url": {**valid, "package_url": "*"},
                "expires": {**valid, "expires": "2000-01-01"},
                "issue": {**valid, "issue": "https://example.com/123"},
            }
            for expected_error, invalid in invalid_cases.items():
                with self.subTest(expected_error=expected_error):
                    policy = self.write_policy(temporary_directory, invalid)
                    completed = run_quality(
                        "security-policy", "--policy", str(policy), check=False
                    )
                    self.assertNotEqual(completed.returncode, 0)
                    self.assertIn(expected_error, completed.stderr)

    def test_fixable_high_dependency_fails_but_unfixable_finding_is_visible(
        self,
    ) -> None:
        changes = [
            {
                "manifest": "frontend/pnpm-lock.yaml",
                "ecosystem": "npm",
                "name": "example",
                "package_url": "pkg:npm/example@1.0.0",
                "vulnerabilities": [
                    {"severity": "high", "advisory_ghsa_id": "GHSA-cfgh-2345-6789"},
                    {"severity": "critical", "advisory_ghsa_id": "GHSA-wwww-2345-6789"},
                ],
            }
        ]
        advisories = {
            "GHSA-cfgh-2345-6789": {
                "vulnerabilities": [
                    {
                        "package": {"ecosystem": "npm", "name": "example"},
                        "first_patched_version": {"identifier": "1.0.1"},
                    }
                ]
            },
            "GHSA-wwww-2345-6789": {
                "vulnerabilities": [
                    {
                        "package": {"ecosystem": "npm", "name": "example"},
                        "first_patched_version": None,
                    }
                ]
            },
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            advisory_file = Path(temporary_directory) / "advisories.json"
            advisory_file.write_text(json.dumps(advisories), encoding="utf-8")
            completed = run_quality(
                "dependency-policy",
                "--changes",
                json.dumps(changes),
                "--advisories",
                str(advisory_file),
                check=False,
            )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("BLOCKED GHSA-cfgh-2345-6789", completed.stdout)
        self.assertIn(
            "VISIBLE GHSA-wwww-2345-6789 (no available fix)", completed.stdout
        )


if __name__ == "__main__":
    unittest.main()
