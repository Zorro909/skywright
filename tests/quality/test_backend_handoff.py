"""Verify the producer/consumer handoff against files and actual Git revisions."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPOSITORY = Path(__file__).resolve().parents[2]
loader = importlib.machinery.SourceFileLoader('ci_backend', str(REPOSITORY / 'scripts/ci-backend'))
spec = importlib.util.spec_from_loader(loader.name, loader)
backend = importlib.util.module_from_spec(spec)
loader.exec_module(backend)


class BackendHandoffTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.root = root / 'source'
        self.root.mkdir()
        for module in backend.MODULES:
            destination = self.root / module / 'pom.xml'
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / module / 'pom.xml', destination)
        self.patch_root = patch.object(backend, 'ROOT', self.root)
        self.patch_root.start()
        self.addCleanup(self.patch_root.stop)
        self.patch_environment = patch.dict(os.environ, {'GITHUB_RUN_ID': '100', 'GITHUB_RUN_ATTEMPT': '1'})
        self.patch_environment.start()
        self.addCleanup(self.patch_environment.stop)
        self.git('init', '--quiet')
        self.git('add', '.')
        self.git('-c', 'user.name=CI test', '-c', 'user.email=ci@example.invalid',
                 'commit', '--quiet', '-m', 'Tested revision')
        self.repository = root / 'producer-maven'
        self.consumer = root / 'consumer-maven'
        self.handoff = root / 'handoff'
        for name in backend.artifact_paths():
            path = self.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(f'Produced at this revision: {name}'.encode())
        backend.stage(self.handoff, self.repository, '1')

    def git(self, *arguments):
        subprocess.run(['git', *arguments], cwd=self.root, check=True, capture_output=True)

    def test_consumer_retry_receives_exact_artifacts_from_the_successful_producer_attempt(self):
        os.environ['GITHUB_RUN_ATTEMPT'] = '2'
        backend.receive(self.handoff, self.consumer, '1')
        for name in backend.artifact_paths():
            self.assertEqual((self.consumer / name).read_bytes(), (self.repository / name).read_bytes())
        jars = sorted((self.root / 'backend/target').glob('*.jar'))
        self.assertEqual(len(jars), 2)
        self.assertTrue(any(path.name.endswith('-tests.jar') for path in jars))

    def test_foreign_run_attempt_and_revision_are_rejected_before_consumption(self):
        for environment, attempt in [({'GITHUB_RUN_ID': '101'}, '1'), ({}, '2')]:
            with self.subTest(environment=environment, attempt=attempt), patch.dict(os.environ, environment):
                with self.assertRaisesRegex(ValueError, 'different revision, run or producer attempt'):
                    backend.receive(self.handoff, self.consumer, attempt)
                self.assertFalse(self.consumer.exists())
        self.git('-c', 'user.name=CI test', '-c', 'user.email=ci@example.invalid',
                 'commit', '--quiet', '--allow-empty', '-m', 'Another revision')
        with self.assertRaisesRegex(ValueError, 'different revision, run or producer attempt'):
            backend.receive(self.handoff, self.consumer, '1')
        self.assertFalse(self.consumer.exists())

    def test_changed_missing_and_extra_artifacts_fail_before_any_installation(self):
        for mutation in ('changed', 'missing', 'extra', 'symlink'):
            with self.subTest(mutation=mutation):
                copy = self.handoff.with_name(f'handoff-{mutation}')
                shutil.copytree(self.handoff, copy)
                path = copy / sorted(backend.artifact_paths())[0]
                if mutation == 'changed':
                    path.write_bytes(b'changed bytes')
                elif mutation == 'missing':
                    path.unlink()
                elif mutation == 'extra':
                    (copy / 'unexpected.jar').write_bytes(b'extra executable')
                else:
                    path.unlink()
                    path.symlink_to(self.repository / sorted(backend.artifact_paths())[0])
                with self.assertRaises(ValueError):
                    backend.receive(copy, self.consumer, '1')
                self.assertFalse(self.consumer.exists())

    def test_empty_handoff_and_invalid_attempt_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'required reactor artifacts'):
            manifest = self.handoff / 'manifest.json'
            value = json.loads(manifest.read_text())
            value['files'] = {}
            manifest.write_text(json.dumps(value))
            backend.receive(self.handoff, self.consumer, '1')
        with self.assertRaisesRegex(ValueError, 'positive workflow run'):
            backend.source_identity('')

    def test_failed_packaging_host_native_probe_prevents_image_builds(self):
        binary = self.root / 'bin'
        binary.mkdir()
        command_log = self.root / 'commands.log'
        mvn = binary / 'mvn'
        mvn.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$MAVEN_COMMAND_LOG"\nexit 73\n')
        mvn.chmod(0o755)
        with (
            patch.object(Path, 'home', return_value=self.consumer),
            patch.object(sys, 'argv', ['ci-backend', 'image', str(self.handoff), '--producer-attempt', '1']),
            patch.dict(os.environ, {'PATH': f'{binary}:{os.environ["PATH"]}',
                                    'MAVEN_COMMAND_LOG': str(command_log)}),
            self.assertRaises(subprocess.CalledProcessError) as failure,
        ):
            backend.main()
        self.assertEqual(failure.exception.returncode, 73)
        commands = command_log.read_text().splitlines()
        self.assertEqual(len(commands), 1)
        self.assertIn('-Dgraalpy.environment.prebuilt=true -pl graalpy-environment process-resources', commands[0])
