from __future__ import annotations

import os
import re
import runpy
import subprocess
import sys
import textwrap
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
WHEEL_RECIPE = (REPOSITORY / "scripts/prepare-graalpy-wheels").read_text()
PREPARATION_ACTION = (REPOSITORY / ".github/actions/prepare-graalpy/action.yml").read_text()
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
    def test_download_caches_follow_their_actual_compatibility_inputs(self) -> None:
        pnpm_cache = named_step(ACTION, "Cache pnpm downloads")
        browser_cache = named_step(ACTION, "Cache Playwright Chromium")

        for cache in (pnpm_cache, browser_cache):
            self.assertRegex(cache, r"uses: actions/cache@[0-9a-f]{40}")
            self.assertIn("${{ runner.os }}", cache)
            self.assertIn("${{ runner.arch }}", cache)
        self.assertIn("${{ steps.toolchain.outputs.node_version }}", pnpm_cache)
        self.assertIn("${{ steps.toolchain.outputs.pnpm_version }}", pnpm_cache)
        self.assertIn("${{ hashFiles('frontend/pnpm-lock.yaml') }}", pnpm_cache)
        self.assertIn("restore-keys:", pnpm_cache)
        self.assertNotIn("hashFiles", browser_cache)
        self.assertNotIn("node_version", browser_cache)
        self.assertNotIn("pnpm_version", browser_cache)
        self.assertNotIn("restore-keys:", browser_cache)
        self.assertIn("ubuntu-24.04", browser_cache)

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

    def test_maven_download_fallback_is_per_lane_and_excludes_application_artifacts(self):
        cache = named_step(JAVA_ACTION, "Cache Maven downloads")
        self.assertEqual(cache.count("${{ github.job }}"), 2)
        self.assertIn("restore-keys:", cache)
        self.assertIn("**/pom.xml", cache)
        self.assertIn("!~/.m2/repository/de/zorro909/skywright", cache)
        self.assertNotIn("cache: maven", JAVA_ACTION)

    def test_packaged_graalpy_environment_cache_is_exact(self) -> None:
        environment_cache = named_step(
            JAVA_ACTION, "Restore exact packaged GraalPy environment"
        )

        self.assertRegex(
            environment_cache, r"uses: actions/cache/restore@[0-9a-f]{40}"
        )
        self.assertIn("inputs.graalpy-resources == 'true'", environment_cache)
        self.assertIn("${{ runner.os }}", environment_cache)
        self.assertIn("${{ runner.arch }}", environment_cache)
        self.assertIn("${{ steps.environment.outputs.identity }}", environment_cache)
        self.assertIn("graalpy-env-v4", environment_cache)
        resolution = named_step(JAVA_ACTION, "Resolve effective GraalPy environment identity")
        self.assertIn("scripts/graalpy-environment identity", resolution)
        self.assertIn(".graalpy/resources", environment_cache)
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
            "numpy==2.3.2",
            "urllib3==2.7.0",
            "pandas==2.2.3",
            "psutil==5.9.8",
            "uvloop==0.22.1",
            "watchfiles==1.2.0",
        }

        self.assertTrue(expected.issubset(packages))
        lock = ENVIRONMENT_LOCK.read_text(encoding="utf-8")
        for package in expected:
            self.assertIn(f"{package}\n", lock)
        self.assertEqual(
            BUILD_CONSTRAINTS.read_text(encoding="utf-8"), "numpy==2.3.2\n"
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
        self.assertEqual(
            wheel_packages, {"${graalpy.wheel.package}", "numpy==2.3.2"}
        )


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

        for name in ("java",):
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
        self.assertIn("skypilot-api-server-deployment", image_arguments)

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

    def test_ci_consumers_use_the_verified_backend_instead_of_rebuilding_it(self):
        self.assertIn("scripts/ci-backend build", job(WORKFLOW, "java"))
        self.assertIn("--frontend-dependencies-ready", job(WORKFLOW, "frontend"))
        for name in ("application", "image"):
            consumer = job(WORKFLOW, name)
            self.assertIn("needs: [plan, graalpy, java]", consumer)
            self.assertIn(f"scripts/ci-backend {name}", consumer)
            self.assertIn("verified-backend-${{ needs.java.outputs.producer-attempt }}", consumer)
            self.assertIn('--producer-attempt "$BACKEND_PRODUCER_ATTEMPT"', consumer)
            self.assertNotIn(f"scripts/quality run {name}", consumer)
        self.assertNotIn("uses: ./.github/actions/setup-frontend", job(WORKFLOW, "image"))

    def test_graalpy_environment_is_built_once_before_maven_fanout(self) -> None:
        preparation = job(WORKFLOW, "graalpy")
        self.assertIn("needs: plan", preparation)
        self.assertIn("timeout-minutes: 270", preparation)
        self.assertIn("uses: ./.github/actions/prepare-graalpy", preparation)
        self.assertIn("scripts/graalpy-environment stamp", preparation)
        self.assertIn('"$GITHUB_RUN_ID"', preparation)
        self.assertIn('"$GITHUB_SHA"', preparation)
        self.assertIn("artifact-provenance.json", preparation)
        self.assertIn("producer-attempt: ${{ steps.archive.outputs.producer-attempt }}", preparation)
        self.assertIn("tar --zstd", preparation)
        self.assertIn("actions/upload-artifact@", preparation)
        self.assertIn("compression-level: 0", preparation)
        for expected in ("PIP_CACHE_DIR=", "PIP_CONSTRAINT=", "PIP_FIND_LINKS=",
                         "150m", "100m", "scripts/retag-wheel", "linux_x86_64",
                         "-Dgraalpy.wheel.package=pandas==2.2.3"):
            self.assertIn(expected, PREPARATION_ACTION + WHEEL_RECIPE)
        validation = named_step(PREPARATION_ACTION, "Validate actual packaged runtime and native imports")
        self.assertIn("steps.java.outputs.graalpy-cache-hit == 'true'", validation)
        self.assertIn("verify --expected .graalpy/expected-environment.json", validation)
        self.assertIn("-Dgraalpy.environment.prebuilt=true", validation)
        self.assertIn("process-resources", validation)
        self.assertNotIn("venv/bin/python", validation)
        progressive = named_step(PREPARATION_ACTION, "Restore progressive GraalPy pip cache")
        self.assertEqual(progressive.count("steps.java.outputs.graalpy-wheel-identity"), 2)
        self.assertNotIn("github.run_id", progressive)
        self.assertIn("-complete", progressive)
        self.assertIn("-partial-", progressive)
        self.assertNotIn("if:", progressive)
        checkpoint = PREPARATION_ACTION.index("Checkpoint pandas wheels")
        self.assertLess(checkpoint, PREPARATION_ACTION.index("Build packaged GraalPy environment"))
        complete = named_step(PREPARATION_ACTION, "Save complete GraalPy wheel cache")
        self.assertIn("steps.build-graalpy.outcome == 'success'", complete)
        self.assertIn("steps.smoke-graalpy.outcome == 'success'", complete)
        rust = PREPARATION_ACTION.index("Set up pinned native Rust compiler")
        self.assertLess(rust, PREPARATION_ACTION.index("Set up GraalVM Community and packaged GraalPy cache"))
        saved = named_step(PREPARATION_ACTION, "Save exact packaged GraalPy environment")
        self.assertIn("steps.smoke-graalpy.outcome == 'success'", saved)
        release = (REPOSITORY / ".github/workflows/deployment-release.yml").read_text()
        self.assertIn("uses: ./.github/actions/prepare-graalpy", release)
        self.assertIn("-Dgraalpy.environment.prebuilt=true", release)

        concurrency = WORKFLOW.split("permissions:", 1)[0]
        self.assertIn("cancel-in-progress: false", concurrency)

        for name in ("java", "integration-java", "application", "image"):
            with self.subTest(job=name):
                consumer = job(WORKFLOW, name)
                self.assertIn("needs: [plan, graalpy, java]" if name in ("application", "image") else "needs: [plan, graalpy]", consumer)
                self.assertIn("actions/download-artifact@", consumer)
                self.assertIn("graalpy-resources.tar.zst", consumer)
                self.assertIn("tar --zstd", consumer)
                self.assertIn(
                    "scripts/graalpy-environment provenance", consumer
                )
                self.assertNotIn(
                    "test -x .graalpy/resources/venv/bin/python", consumer
                )
                self.assertIn("-Dgraalpy.environment.prebuilt=true", consumer)
                self.assertNotIn("require-graalpy-cache", consumer)
                self.assertIn("GRAALPY_PRODUCER_ATTEMPT: ${{ needs.graalpy.outputs.producer-attempt }}", consumer)
                self.assertIn('--run-attempt "$GRAALPY_PRODUCER_ATTEMPT"', consumer)

    def test_integration_gate_requires_both_lanes_and_explicit_applicability(self):
        gate = named_step(job(WORKFLOW, "integration"), "Require every applicable integration lane")
        command = textwrap.dedent(gate.split("run: |\n", 1)[1])
        outcomes = ("success", "failure", "skipped", "cancelled", "")
        for plan in ("success", "failure"):
            for applicable in ("true", "false", "", "unknown"):
                for java in outcomes:
                    for sdk in outcomes:
                        with self.subTest(plan=plan, applicable=applicable, java=java, sdk=sdk):
                            result = subprocess.run(
                                ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", command],
                                env={**os.environ, "PLAN_RESULT": plan, "APPLICABLE": applicable,
                                     "JAVA_RESULT": java, "SDK_RESULT": sdk},
                                capture_output=True, text=True,
                            )
                            expected = plan == "success" and (
                                applicable == "false" or applicable == "true" and java == sdk == "success"
                            )
                            self.assertEqual(result.returncode == 0, expected, result.stderr)


if __name__ == "__main__":
    unittest.main()
