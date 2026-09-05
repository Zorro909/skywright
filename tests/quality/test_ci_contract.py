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
ENVIRONMENT_POM = REPOSITORY / "graalpy-environment/pom.xml"
ENVIRONMENT_LOCK = REPOSITORY / "graalpy-environment/graalpy.lock"
BUILD_CONSTRAINTS = REPOSITORY / "graalpy-environment/build-constraints.txt"
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
    def test_download_caches_are_bound_to_owning_inputs(self) -> None:
        pnpm_cache = named_step(ACTION, "Cache pnpm downloads")
        browser_cache = named_step(ACTION, "Cache Playwright Chromium")
        self.assertIn("hashFiles('frontend/pnpm-lock.yaml')", pnpm_cache)
        self.assertIn("restore-keys:", pnpm_cache)
        self.assertIn("runner.arch", pnpm_cache)
        self.assertIn("playwright_version", browser_cache)
        self.assertIn("ubuntu24", browser_cache)
        self.assertIn("runner.arch", browser_cache)
        self.assertNotIn("pnpm-lock.yaml", browser_cache)
        self.assertNotIn("node_modules", ACTION)

    def test_browser_install_is_opt_in_bounded_and_phase_visible(self) -> None:
        self.assertRegex(
            ACTION,
            r'install-browser:\n(?:    .*\n)*?    default: "false"',
        )
        dependency_step = named_step(
            ACTION, "Install Chromium operating-system dependencies"
        )
        download_step = named_step(ACTION, "Install Playwright Chromium on cache miss")

        self.assertIn("inputs.install-browser == 'true'", dependency_step)
        self.assertIn("timeout --verbose", dependency_step)
        self.assertIn("playwright install-deps chromium", dependency_step)
        self.assertIn(
            "steps.playwright-cache.outputs.cache-hit != 'true'", download_step
        )
        self.assertIn("timeout --verbose", download_step)
        self.assertIn("playwright install chromium", download_step)


class JavaSetupContractTest(unittest.TestCase):
    def test_archive_cache_is_exact_and_download_is_resilient(self) -> None:
        archive_cache = named_step(JAVA_ACTION, "Cache GraalVM Community archive")
        download = named_step(
            JAVA_ACTION, "Download exact GraalVM Community archive on cache miss"
        )
        verification = named_step(JAVA_ACTION, "Verify GraalVM Community archive")

        self.assertRegex(archive_cache, r"uses: actions/cache@[0-9a-f]{40}")
        self.assertIn("${{ runner.os }}", archive_cache)
        self.assertIn("${{ runner.arch }}", archive_cache)
        self.assertIn(
            "${{ steps.toolchain.outputs.java_archive_sha256 }}", archive_cache
        )
        self.assertIn("${{ runner.temp }}/graalvm-community.tar.gz", archive_cache)
        self.assertIn("steps.graalvm-cache.outputs.cache-hit != 'true'", download)
        self.assertIn("--retry-all-errors", download)
        self.assertIn("sha256sum --check --strict", verification)
        self.assertNotIn("if:", verification)

    def test_packaged_graalpy_environment_cache_is_exact(self) -> None:
        environment_cache = named_step(
            JAVA_ACTION, "Restore exact packaged GraalPy environment"
        )
        self.assertIn("actions/cache/restore@", environment_cache)
        self.assertIn("steps.environment.outputs.key", environment_cache)
        self.assertIn("scripts/graalpy-environment identity", JAVA_ACTION)
        self.assertNotIn("restore-keys:", environment_cache)

    def test_graalpy_inputs_pin_qualified_native_dependencies(self) -> None:
        pom = ET.parse(ENVIRONMENT_POM)
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        packages = {
            package.text
            for package in pom.findall(
                ".//m:plugin[m:artifactId='graalpy-maven-plugin']"
                "/m:configuration/m:packages/m:package",
                namespace,
            )
        }
        expected = {
            "numpy==2.2.4",
            "pandas==2.2.3",
            "psutil==5.9.8",
            "uvloop==0.19.0",
            "watchfiles==0.21.0",
        }

        self.assertTrue(expected.issubset(packages))
        lock = ENVIRONMENT_LOCK.read_text(encoding="utf-8")
        for package in expected:
            self.assertIn(f"{package}\n", lock)
        self.assertEqual(
            BUILD_CONSTRAINTS.read_text(encoding="utf-8"), "numpy==2.2.4\n"
        )

    def test_graalpy_environment_is_a_dedicated_reactor_module(self) -> None:
        parent = ET.parse(REPOSITORY / "pom.xml")
        backend = ET.parse(REPOSITORY / "backend/pom.xml")
        environment = ET.parse(ENVIRONMENT_POM)
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}

        modules = {
            module.text for module in parent.findall(".//m:modules/m:module", namespace)
        }
        backend_dependencies = {
            dependency.text
            for dependency in backend.findall(
                ".//m:dependencies/m:dependency/m:artifactId", namespace
            )
        }
        self.assertIn("graalpy-environment", modules)
        self.assertIn("skywright-graalpy-environment", backend_dependencies)
        self.assertEqual(
            parent.findtext(".//m:graalpy.external.directory", namespaces=namespace),
            "${maven.multiModuleProjectDirectory}/.graalpy/resources",
        )
        self.assertIsNone(
            backend.find(".//m:plugin[m:artifactId='graalpy-maven-plugin']", namespace)
        )
        self.assertIsNotNone(
            environment.find(
                ".//m:plugin[m:artifactId='graalpy-maven-plugin']", namespace
            )
        )
        self.assertEqual(
            environment.findtext(
                ".//m:plugin[m:artifactId='graalpy-maven-plugin']"
                "/m:configuration/m:externalDirectory",
                namespaces=namespace,
            ),
            "${graalpy.external.directory}",
        )
        self.assertEqual(
            environment.findtext(
                ".//m:profile[m:id='build-graalpy-environment']"
                "/m:activation/m:property/m:name",
                namespaces=namespace,
            ),
            "!graalpy.environment.prebuilt",
        )
        self.assertEqual(
            environment.findtext(
                ".//m:profile[m:id='prime-graalpy-wheel-cache']"
                "/m:activation/m:property/m:name",
                namespaces=namespace,
            ),
            "graalpy.wheel.package",
        )
        wheel_packages = {
            package.text
            for package in environment.findall(
                ".//m:profile[m:id='prime-graalpy-wheel-cache']"
                "//m:configuration/m:packages/m:package",
                namespace,
            )
        }
        self.assertEqual(wheel_packages, {"${graalpy.wheel.package}", "numpy==2.2.4"})


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
            self.assertIn("install-browser: true", job(WORKFLOW, name))
        self.assertIn("install-browser: false", job(WORKFLOW, "java"))
        self.assertNotIn("setup-frontend", job(WORKFLOW, "image"))

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
            self.assertNotIn(QUALITY_IMPLEMENTATION["FRONTEND_INSTALL"], commands)

        application_arguments = {
            argument
            for command in commands_for("application", frontend_dependencies_ready=True)
            for argument in command
        }
        image_arguments = {
            argument
            for command in commands_for("image", frontend_dependencies_ready=True)
            for argument in command
        }
        self.assertIn("-Ppackaged-acceptance", application_arguments)
        self.assertIn("backend-deployment", image_arguments)
        self.assertIn("skypilot-api-server-deployment", image_arguments)

    def test_local_lane_installs_frontend_dependencies_exactly_once(self) -> None:
        commands_for = QUALITY_IMPLEMENTATION["commands_for"]
        frontend_install = QUALITY_IMPLEMENTATION["FRONTEND_INSTALL"]

        for check in ("java", "application", "image", "frontend"):
            with self.subTest(check=check):
                self.assertEqual(
                    commands_for(check, frontend_dependencies_ready=False).count(
                        frontend_install
                    ),
                    1,
                )

    def test_local_runner_requires_a_browser_only_at_browser_seams(self) -> None:
        self.assertEqual(
            QUALITY_IMPLEMENTATION["BROWSER_CHECKS"],
            frozenset(("application", "frontend")),
        )

    def test_ci_consumers_require_the_verified_backend_producer(self) -> None:
        self.assertIn("scripts/ci-backend build", job(WORKFLOW, "java"))
        for name in ("application", "image"):
            consumer = job(WORKFLOW, name)
            self.assertIn("needs: [plan, graalpy, java]", consumer)
            self.assertIn("name: verified-backend", consumer)
            self.assertIn(f"scripts/ci-backend {name}", consumer)

    def test_native_preparation_survives_cancellable_verification(self) -> None:
        preparation = (
            REPOSITORY / ".github/actions/prepare-graalpy/action.yml"
        ).read_text()
        native = job(WORKFLOW, "graalpy")
        self.assertIn("cancel-in-progress: false", native)
        self.assertIn("timeout-minutes: 270", native)
        self.assertIn("prepare-graalpy", native)
        for name in (
            "java",
            "application",
            "image",
            "frontend",
            "integration",
            "sdk-artifacts",
            "sdk-compatibility",
        ):
            self.assertIn(
                "cancel-in-progress: ${{ github.event_name == 'pull_request' }}",
                job(WORKFLOW, name),
            )
            self.assertIn("Reject superseded PR source", job(WORKFLOW, name))
        self.assertIn("150m", preparation)
        self.assertIn("100m", preparation)
        self.assertIn("scripts/graalpy-environment qualify", preparation)
        self.assertIn("always()", preparation)
        self.assertIn("actions/cache/save@", preparation)


if __name__ == "__main__":
    unittest.main()
