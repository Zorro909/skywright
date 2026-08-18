from __future__ import annotations

import re
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
ACTION = (REPOSITORY / ".github/actions/setup-frontend/action.yml").read_text(
    encoding="utf-8"
)
WORKFLOW = (REPOSITORY / ".github/workflows/quality.yml").read_text(encoding="utf-8")


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
            self.assertIn("${{ inputs.node-version }}", cache)
            self.assertIn("${{ inputs.pnpm-version }}", cache)
            self.assertIn("${{ hashFiles('frontend/pnpm-lock.yaml') }}", cache)

        self.assertIn("path: ${{ steps.pnpm-store.outputs.path }}", pnpm_cache)
        self.assertIn("${{ inputs.playwright-version }}", browser_cache)
        self.assertIn("path: ~/.cache/ms-playwright", browser_cache)

    def test_browser_install_is_opt_in_bounded_and_phase_visible(self) -> None:
        self.assertRegex(
            ACTION,
            r"install-browser:\n(?:    .*\n)*?    default: 'false'",
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
        quality_source = (REPOSITORY / "scripts/quality").read_text(encoding="utf-8")
        for check in ("java", "application", "image"):
            command_block = re.search(
                rf'    "{check}": \((.*?)\n    \),', quality_source, re.DOTALL
            )
            self.assertIsNotNone(command_block, check)
            self.assertIn('"-DskipFrontendTests=true"', command_block.group(1))

        self.assertIn('"-Ppackaged-acceptance"', quality_source)
        self.assertIn('"backend-deployment"', quality_source)

    def test_local_runner_requires_a_browser_only_at_browser_seams(self) -> None:
        quality_source = (REPOSITORY / "scripts/quality").read_text(encoding="utf-8")

        self.assertIn(
            'BROWSER_CHECKS = frozenset(("application", "frontend"))',
            quality_source,
        )
        self.assertNotIn(
            'check in {"application", "image", "java", "frontend"}',
            quality_source,
        )


if __name__ == "__main__":
    unittest.main()
