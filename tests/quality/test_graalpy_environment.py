from __future__ import annotations

import contextlib
import io
import py_compile
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / 'scripts'))
from quality_support import graalpy_environment as environment


class EnvironmentTestCase(unittest.TestCase):
    """Shared sealed-environment fixture without test cases of its own."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in (*environment.INPUTS, 'quality/toolchain.json'):
            destination = self.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / name, destination)
        self.versions = {'graalpy': '25.3.4.1', 'skypilot': '0.13.0'}
        self.resources = self.root / 'resources'
        self.library = self.resources / 'venv/lib/python3.13/site-packages'
        self.library.mkdir(parents=True)
        (self.library / 'package.py').write_text('qualified dependency')
        (self.resources / 'venv/installed.txt').write_text('skypilot==0.13.0\n')
        (self.resources / 'venv/contents').write_text('version=25.3.4.1\ninput_packages=skypilot==0.13.0\n')
        self.observed = {'implementation': 'graalpy', **self.versions,
                         'packages': environment.locked_packages(self.root)}
        self.observation = self.root / 'observation.json'
        environment.write_document(self.observation, self.observed)

    def invoke(self, command, *args):
        with contextlib.redirect_stdout(io.StringIO()):
            return environment.main([command, '--root', str(self.root), '--resources', str(self.resources),
                                     '--graalpy-version', self.versions['graalpy'],
                                     '--skypilot-version', self.versions['skypilot'], *args])

    def seal(self):
        self.invoke('seal', '--observation', str(self.observation))


class EnvironmentIdentityTest(EnvironmentTestCase):
    def test_effective_versions_platform_constraints_and_native_inputs_invalidate(self):
        baseline = environment.identity(self.root, self.versions, target={'os': 'one'}, native={'cc': 'one'})
        for name in self.versions:
            with self.subTest(version=name):
                changed = {**self.versions, name: '99.0.0'}
                self.assertNotEqual(baseline, environment.identity(self.root, changed, target={'os': 'one'}, native={'cc': 'one'}))
        self.assertNotEqual(baseline, environment.identity(self.root, self.versions, target={'os': 'two'}, native={'cc': 'one'}))
        self.assertNotEqual(baseline, environment.identity(self.root, self.versions, target={'os': 'one'}, native={'cc': 'two'}))
        path = self.root / 'graalpy-environment/build-constraints.txt'
        path.write_text(path.read_text() + '\nsetuptools==1\n')
        self.assertNotEqual(baseline, environment.identity(self.root, self.versions, target={'os': 'one'}, native={'cc': 'one'}))

    def test_unrelated_application_edit_reuses_identity(self):
        before = environment.identity(self.root, self.versions)
        (self.root / 'Application.java').write_text('changed application')
        self.assertEqual(before, environment.identity(self.root, self.versions))

    def test_workflow_and_probe_edits_requalify_environment_but_reuse_wheels(self):
        before = environment.identity(self.root, self.versions, target={'os': 'one'}, native={'cc': 'one'})
        wheels = environment.wheel_identity(before)
        for name in ('.github/actions/prepare-graalpy/action.yml',
                     'graalpy-environment/VerifyEnvironment.java',
                     'backend-deployment/src/main/docker/Dockerfile'):
            with self.subTest(input=name):
                path = self.root / name
                path.write_text(path.read_text() + '\n# qualification-only edit\n')
                after = environment.identity(self.root, self.versions, target={'os': 'one'}, native={'cc': 'one'})
                self.assertNotEqual(before, after)
                self.assertEqual(wheels, environment.wheel_identity(after))

    def test_wheels_are_isolated_by_runtime_lock_recipe_platform_and_native_inputs(self):
        before = environment.identity(self.root, self.versions, target={'os': 'one'}, native={'cc': 'one'})
        wheels = environment.wheel_identity(before)
        for field, value in (('versions', {**self.versions, 'graalpy': '99.0.0'}),
                             ('versions', {**self.versions, 'skypilot': '99.0.0'}),
                             ('platform', {'os': 'two'}), ('nativeBuild', {'cc': 'two'}),
                             ('nativeBuild', {'cc': 'one', 'flags': {'CFLAGS': '-march=native'}}),
                             ('toolchain', {'java_version': '99'})):
            with self.subTest(field=field, value=value):
                self.assertNotEqual(wheels, environment.wheel_identity({**before, field: value}))
        for name in environment.WHEEL_INPUTS:
            with self.subTest(input=name):
                changed = {**before, 'inputs': {**before['inputs'], name: 'changed'}}
                self.assertNotEqual(wheels, environment.wheel_identity(changed))

    def test_identity_command_emits_both_cache_identities(self):
        output = self.root / 'github-output'
        self.invoke('identity', '--github-output', str(output))
        values = dict(line.split('=', 1) for line in output.read_text().splitlines())
        expected = environment.identity(self.root, self.versions)
        self.assertEqual(values['identity'], environment.digest(environment.canonical(expected)))
        self.assertEqual(values['wheel-identity'], environment.digest(environment.canonical(environment.wheel_identity(expected))))

    def test_sealed_environment_rejects_changed_effective_versions_before_probe(self):
        self.seal()
        self.invoke('verify')
        for name in self.versions:
            with self.subTest(version=name), self.assertRaisesRegex(ValueError, 'effective version'):
                environment.verify_record(self.root, self.resources, {**self.versions, name: '99.0.0'})

    def test_tampered_dependency_and_missing_record_are_rejected(self):
        with self.assertRaises(FileNotFoundError):
            self.invoke('verify')
        self.seal()
        (self.library / 'package.py').write_text('unqualified dependency')
        with self.assertRaisesRegex(ValueError, 'payload differs'):
            self.invoke('verify')

    def test_generated_and_planted_bytecode_is_removed_before_verification(self):
        source = self.library / 'package.py'
        source.write_text('value = 1')
        self.seal()
        generated = Path(py_compile.compile(str(source), doraise=True))
        planted = self.library / 'package.pyc'
        planted.write_bytes(b'unqualified executable bytecode')
        self.invoke('verify')
        self.assertFalse(generated.exists())
        self.assertFalse(planted.exists())

    def test_bytecode_cleanup_does_not_follow_external_symlink(self):
        outside = self.root / 'outside'
        outside.mkdir()
        sentinel = outside / 'keep.pyc'
        sentinel.write_bytes(b'outside environment')
        (self.library / '__pycache__').symlink_to(outside, target_is_directory=True)
        self.seal()
        self.assertTrue(sentinel.exists())
        self.assertFalse((self.library / '__pycache__').exists())

    def test_changed_native_binary_is_rejected(self):
        native = self.library / 'package.so'
        native.write_bytes(b'qualified native code')
        self.seal()
        native.write_bytes(b'changed native code')
        with self.assertRaisesRegex(ValueError, 'payload differs'):
            self.invoke('verify')

    def test_payload_rejects_external_links(self):
        (self.library / 'external.py').symlink_to(self.observation)
        with self.assertRaisesRegex(ValueError, 'external or directory symlink'):
            self.seal()

    def test_fresh_observation_rejects_wrong_runtime_or_package(self):
        self.seal()
        for key in ('graalpy', 'skypilot'):
            with self.subTest(key=key):
                environment.write_document(self.observation, {**self.observed, key: '99.0.0'})
                with self.assertRaisesRegex(ValueError, 'Actual'):
                    self.invoke('verify', '--observation', str(self.observation))
        environment.write_document(self.observation, {**self.observed, 'packages': {}})
        with self.assertRaisesRegex(ValueError, 'Installed package differs'):
            self.invoke('verify', '--observation', str(self.observation))
        self.observation.unlink()
        with self.assertRaises(FileNotFoundError):
            self.invoke('verify', '--observation', str(self.observation))

    def test_exact_restore_rejects_other_native_platform(self):
        self.seal()
        expected = environment.identity(self.root, self.versions, target={'os': 'other'})
        with self.assertRaisesRegex(ValueError, 'effective build inputs'):
            environment.verify_record(self.root, self.resources, self.versions, expected)

    def test_same_run_provenance_rejects_foreign_run_commit_or_payload(self):
        self.seal()
        output = self.root / 'artifact-provenance.json'
        def provenance(command, run='10', source='commit-a', attempt='1'):
            return self.invoke(command, '--run-id', run, '--run-attempt', attempt, '--source', source, '--output', str(output))
        with patch.object(environment.subprocess, 'check_output', return_value='commit-a\n'):
            provenance('stamp')
            provenance('provenance')
            with patch.dict(environment.os.environ, {'GITHUB_RUN_ATTEMPT': '2'}):
                provenance('provenance', attempt='1')
            with self.assertRaisesRegex(ValueError, 'not from this run'):
                provenance('provenance', attempt='2')
            with self.assertRaisesRegex(ValueError, 'positive producer attempt'):
                provenance('provenance', attempt='')
            with self.assertRaisesRegex(ValueError, 'not from this run'):
                provenance('provenance', run='11')
            with self.assertRaisesRegex(ValueError, 'checked-out commit'):
                provenance('provenance', source='commit-b')
            (self.library / 'package.py').write_text('new dependency')
            self.seal()
            with self.assertRaisesRegex(ValueError, 'not from this run'):
                provenance('provenance')



if __name__ == '__main__':
    unittest.main()
