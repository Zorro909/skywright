"""Requires a prepared environment; exercises the real prebuilt Maven lifecycle."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]


class PrebuiltEnvironmentTest(unittest.TestCase):
    def test_relocated_environment_ignores_stale_launcher_and_rejects_mismatched_sdk(self):
        with tempfile.TemporaryDirectory(prefix='skywright-relocated-') as directory:
            resources = Path(directory) / 'resources'
            shutil.copytree(REPOSITORY / '.graalpy/resources', resources, symlinks=True)
            launcher = resources / 'venv/bin/python'
            launcher.unlink()
            launcher.write_text('#!/bin/sh\nexit 91\n')
            launcher.chmod(0o755)
            config = resources / 'venv/pyvenv.cfg'
            config.write_text('\n'.join(
                'executable = /missing/old/graalpy.sh' if line.startswith('executable =') else line
                for line in config.read_text().splitlines()) + '\n')
            packages = next((resources / 'venv/lib').glob('python*/site-packages'))
            planted = packages / 'sky/__pycache__/unqualified.pyc'
            planted.parent.mkdir(parents=True, exist_ok=True)
            planted.write_bytes(b'unqualified executable bytecode')
            command = ['mvn', '--batch-mode', '--no-transfer-progress',
                       '-Dgraalpy.environment.prebuilt=true', f'-Dgraalpy.external.directory={resources}',
                       '-pl', 'graalpy-environment']
            result = subprocess.run([*command, 'process-resources'], cwd=REPOSITORY,
                                    capture_output=True, text=True, timeout=300)
            self.assertEqual(result.returncode, 0, (result.stdout + result.stderr)[-4000:])
            self.assertFalse(planted.exists())
            self.assertEqual(launcher.read_text(), '#!/bin/sh\nexit 91\n')
            self.assertNotIn('graalpy-maven-plugin:', result.stdout)
            result = subprocess.run([*command, 'validate', '-Dskypilot.version=99.0.0'], cwd=REPOSITORY,
                                    capture_output=True, text=True, timeout=120)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Packaged SkyPilot requirement differs from the effective version', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
