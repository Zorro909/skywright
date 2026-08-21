from __future__ import annotations

import re
import runpy
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
ACTION = (REPOSITORY / ".github/actions/setup-frontend/action.yml").read_text(
    encoding="utf-8"
)
JAVA_ACTION = (REPOSITORY / ".github/actions/setup-java/action.yml").read_text(
    encoding="utf-8"
)
PYTHON_ACTION = (
    REPOSITORY / ".github/actions/setup-python-toolchain/action.yml"
).read_text(encoding="utf-8")
WORKFLOW = (REPOSITORY / ".github/workflows/quality.yml").read_text(encoding="utf-8")
sys.path.insert(0, str(REPOSITORY / "scripts"))
try:
    QUALITY_IMPLEMENTATION = runpy.run_path(str(REPOSITORY / "scripts/quality"))
finally:
    sys.path.pop(0)


def named_step(source: str, name: str) -> str:
    match = re.search(
        rf"    - name: {re.escape(name)}\n(.*?)(?=    - name:|\Z)",
        source,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"step not found: {name}")
    return match.group(0)


def job(source: str, name: str) -> str:
    match = re.search(
        rf"^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]+:\n|\Z)",
        source,
        re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"job not found: {name}")
    return match.group(0)


class FrontendSetupContractTest(unittest.TestCase):
    def test_download_caches_are_bound_to_exact_toolchain_and_lockfile(self) -> None:
        pnpm_cache = named_step(ACTION, "Cache pnpm downloads")
        browser_cache = named_step(ACTION, "Cache Playwright Chromium")

        for cache in (pnpm_cache, browser_cache):
            self.assertRegex(cache, r"uses: actions/cache@[0-9a-f]{40}")
            self.assertIn("${{ runner.os }}", cache)
            self.assertIn("${{ steps.toolchain.outputs.node_version }}", cache)
            self.assertIn("${{ steps.toolchain.outputs.pnpm_version }}", cache)
            self.assertIn("${{ hashFiles('frontend/pnpm-lock.yaml') }}", cache)

        self.assertIn("path: ${{ steps.pnpm-store.outputs.path }}", pnpm_cache)
        self.assertIn("${{ steps.toolchain.outputs.playwright_version }}", browser_cache)
        self.assertIn("path: ~/.cache/ms-playwright", browser_cache)

    def test_browser_install_is_opt_in_bounded_and_phase_visible(self) -> None:
        self.assertRegex(
            ACTION,
            r'install-browser:\n(?:    .*\n)*?    default: "false"',
        )
        dependency_step = named_step(
            ACTION, "Install Chromium operating-system dependencies"
        )
        download_step = named_step(
            ACTION, "Install Playwright Chromium on cache miss"
        )

        self.assertIn("inputs.install-browser == 'true'", dependency_step)
        self.assertIn("timeout --verbose", dependency_step)
        self.assertIn("playwright install-deps chromium", dependency_step)
        self.assertIn(
            "steps.playwright-cache.outputs.cache-hit != 'true'", download_step
        )
        self.assertIn("timeout --verbose", download_step)
        self.assertIn("playwright install chromium", download_step)


class QualityWorkflowContractTest(unittest.TestCase):
    def test_setup_actions_own_repository_toolchain_versions(self) -> None:
        self.assertIn("quality/toolchain.json", JAVA_ACTION)
        self.assertNotIn("archive-url:", JAVA_ACTION.split("runs:", 1)[0])

        self.assertIn("frontend/package.json", ACTION)
        for input_name in ("node-version:", "pnpm-version:", "playwright-version:"):
            self.assertNotIn(input_name, ACTION.split("runs:", 1)[0])

        self.assertIn("sdk/pyproject.toml", PYTHON_ACTION)
        self.assertIn("${{ steps.toolchain.outputs.uv_version }}", PYTHON_ACTION)

    def test_quality_plan_only_exports_applicability(self) -> None:
        plan_job = job(WORKFLOW, "plan")
        for version_output in (
            "java_archive_url",
            "java_version",
            "node_version",
            "pnpm_version",
            "playwright_version",
            "uv_version",
        ):
            self.assertNotIn(version_output, plan_job)

    def test_maven_frontend_install_can_be_skipped_after_ci_setup(self) -> None:
        pom = ET.parse(REPOSITORY / "frontend/pom.xml")
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        execution = next(
            execution
            for execution in pom.findall(".//m:execution", namespace)
            if execution.findtext("m:id", namespaces=namespace)
            == "install-web-dependencies"
        )

        self.assertEqual(
            execution.findtext("m:configuration/m:skip", namespaces=namespace),
            "${skipFrontendInstall}",
        )

    def test_only_browser_verification_lanes_install_browser_dependencies(self) -> None:
        for name in ("frontend", "application"):
            with self.subTest(job=name):
                self.assertIn("install-browser: true", job(WORKFLOW, name))

        for name in ("java", "image"):
            with self.subTest(job=name):
                self.assertIn("install-browser: false", job(WORKFLOW, name))

    def test_maven_lanes_skip_frontend_tests_but_keep_their_artifact_boundaries(
        self,
    ) -> None:
        commands_for = QUALITY_IMPLEMENTATION["commands_for"]
        for check in ("java", "application", "image"):
            commands = commands_for(check, frontend_dependencies_ready=True)
            flattened_arguments = {
                argument for command in commands for argument in command
            }
            self.assertIn("-DskipFrontendInstall=true", flattened_arguments)
            self.assertIn("-DskipFrontendTests=true", flattened_arguments)
            self.assertNotIn(
                QUALITY_IMPLEMENTATION["FRONTEND_INSTALL"], commands
            )

        application_arguments = {
            argument
            for command in commands_for(
                "application", frontend_dependencies_ready=True
            )
            for argument in command
        }
        image_arguments = {
            argument
            for command in commands_for("image", frontend_dependencies_ready=True)
            for argument in command
        }
        self.assertIn("-Ppackaged-acceptance", application_arguments)
        self.assertIn("backend-deployment", image_arguments)

    def test_local_lane_installs_frontend_dependencies_exactly_once(self) -> None:
        commands_for = QUALITY_IMPLEMENTATION["commands_for"]
        frontend_install = QUALITY_IMPLEMENTATION["FRONTEND_INSTALL"]

        for check in ("java", "application", "image", "frontend"):
            with self.subTest(check=check):
                self.assertEqual(
                    commands_for(
                        check, frontend_dependencies_ready=False
                    ).count(frontend_install),
                    1,
                )

    def test_local_runner_requires_a_browser_only_at_browser_seams(self) -> None:
        self.assertEqual(
            QUALITY_IMPLEMENTATION["BROWSER_CHECKS"],
            frozenset(("application", "frontend")),
        )

    def test_ci_lanes_declare_preinstalled_frontend_dependencies(self) -> None:
        for name in ("java", "frontend", "application", "image"):
            with self.subTest(job=name):
                self.assertIn("--frontend-dependencies-ready", job(WORKFLOW, name))


if __name__ == "__main__":
    unittest.main()
