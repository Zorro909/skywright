"""Exercise wheel reuse through the actual preparation command and wheel retagger."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

REPOSITORY = Path(__file__).resolve().parents[2]


class PrepareWheelsTest(unittest.TestCase):
    def test_reuses_wheelhouse_and_recovers_checkpoint_without_invoking_maven(self):
        for location in ('wheelhouse', 'wheels/pip-origin'):
            with self.subTest(location=location), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / location / 'pandas-2.2.3-graalpy313-graalpy253_313_native-linux_x86_64.whl'
                source.parent.mkdir(parents=True)
                with zipfile.ZipFile(source, 'w') as wheel:
                    wheel.writestr('pandas-2.2.3.dist-info/WHEEL',
                                   'Wheel-Version: 1.0\nTag: graalpy313-graalpy253_313_native-linux_x86_64\n')
                    wheel.writestr('pandas-2.2.3.dist-info/RECORD', '')
                    wheel.writestr('pandas/native.so', b'cached native dependency')
                binary = root / 'bin'
                binary.mkdir()
                # Any attempted compilation fails this warm-path check immediately.
                (binary / 'mvn').write_text('#!/bin/sh\nexit 73\n')
                (binary / 'mvn').chmod(0o755)
                result = subprocess.run(
                    ['scripts/prepare-graalpy-wheels'], cwd=REPOSITORY,
                    env={**os.environ, 'PATH': f'{binary}:{os.environ["PATH"]}',
                         'PIP_CACHE_DIR': str(root), 'PIP_FIND_LINKS': str(root / 'wheelhouse'),
                         'PIP_CONSTRAINT': str(REPOSITORY / 'graalpy-environment/build-constraints.txt')},
                    capture_output=True, text=True, timeout=10,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                with zipfile.ZipFile(root / 'wheelhouse' / source.name) as wheel:
                    self.assertEqual(wheel.read('pandas/native.so'), b'cached native dependency')
