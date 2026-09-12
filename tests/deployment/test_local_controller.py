"""Preserve controller mode while checking optional provider compatibility."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import types
import unittest
from unittest.mock import Mock, patch

from deployment.skywright_deployment import local_control


class LocalControllerTest(unittest.TestCase):
    def test_configuration_pins_local_resources_without_overriding_existing_mode(self):
        for provider in (None, {"revision": 1}):
            with self.subTest(provider=provider):
                settings = {"context": "local", "vastProvider": provider}
                kube = Mock()
                with patch("deployment.skywright_deployment.local_vast.template", return_value=""):
                    local_control.configuration(kube, [], {}, settings)
                value = kube.apply.call_args.args[0]
                controller = json.loads(value["data"]["skypilot.yaml"])["jobs"]["controller"]
                self.assertEqual(controller, {"resources": {"infra": "kubernetes/local", "cpus": "2", "memory": "4"}})

    def verify(self, *, provider, effective, marker, override=False, resources=None):
        settings = {"context": "local", "vastProvider": {} if provider else None}
        expected = {"infra": "kubernetes/local", "cpus": "2", "memory": "4"}
        config = types.SimpleNamespace(get_nested=lambda *args: expected if resources is None else resources)
        jobs = types.SimpleNamespace(utils=types.SimpleNamespace(is_consolidation_mode=lambda: effective))
        modules = {"sky": types.SimpleNamespace(skypilot_config=config), "sky.jobs": jobs}
        kube = Mock()

        def run(*arguments, data, **options):
            self.assertEqual(arguments[0], "exec")
            code = 0
            argv = list(arguments[arguments.index("python") + 1:])
            with patch.dict(sys.modules, modules), patch.object(sys, "argv", argv), \
                    patch.dict(os.environ, {"IS_SKYPILOT_JOB_CONTROLLER": "1"} if override else {}, clear=True), \
                    patch.object(Path, "exists", return_value=marker), patch("logging.disable"), \
                    contextlib.redirect_stderr(io.StringIO()):
                try:
                    exec(compile(data, "controller-verification", "exec"), {})
                except SystemExit as failure:
                    code = failure.code
            return subprocess.CompletedProcess(arguments, code, b"", b"")

        kube.run.side_effect = run
        local_control.verify_controller(kube, settings)
        self.assertEqual(kube.run.call_count, 1)
        kube.apply.assert_not_called()

    def test_local_only_installation_keeps_separate_controller_mode(self):
        self.verify(provider=False, effective=False, marker=False)

    def test_selected_provider_accepts_existing_effective_consolidation(self):
        self.verify(provider=True, effective=True, marker=True)

    def test_selected_provider_rejects_incompatible_or_ambiguous_effective_mode(self):
        for options in ({"effective": False, "marker": False},
                        {"effective": True, "marker": False},
                        {"effective": True, "marker": True, "override": True}):
            with self.subTest(options=options), self.assertRaisesRegex(SystemExit, "Verify the retained"):
                self.verify(provider=True, **options)

    def test_local_resource_placement_remains_required_for_both_paths(self):
        for provider in (False, True):
            with self.subTest(provider=provider), self.assertRaisesRegex(SystemExit, "Verify the retained"):
                self.verify(provider=provider, effective=True, marker=True, resources={"infra": "vast"})


if __name__ == "__main__":
    unittest.main()
