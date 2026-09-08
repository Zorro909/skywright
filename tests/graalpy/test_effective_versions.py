"""System checks run after Java setup, using Maven's actual inheritance resolution."""
from pathlib import Path
import os
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / 'scripts'))
from quality_support import graalpy_environment as environment


class MavenEnvironmentIdentityTest(unittest.TestCase):
    def test_parent_runtime_properties_and_cli_override_invalidate_but_unrelated_edits_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('pom.xml', *environment.INPUTS, 'quality/toolchain.json'):
                destination = root / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(REPOSITORY / name, destination)
            parent = root / 'pom.xml'
            original = parent.read_text()
            versions = environment.effective_versions(root)
            baseline = environment.identity(root, versions)
            for name, version in versions.items():
                with self.subTest(property=name):
                    parent.write_text(original.replace(f'<{name}.version>{version}</{name}.version>',
                                                       f'<{name}.version>99.0.0</{name}.version>'))
                    effective = environment.effective_versions(root)
                    self.assertEqual(effective[name], '99.0.0')
                    self.assertNotEqual(environment.identity(root, effective), baseline)
            parent.write_text(original.replace('</properties>', '<unrelated.source>changed</unrelated.source></properties>'))
            (root / 'unrelated.java').write_text('unrelated application edit')
            self.assertEqual(environment.identity(root, environment.effective_versions(root)), baseline)
            with patch.dict(os.environ, {'MAVEN_ARGS': '-Dskypilot.version=98.0.0'}):
                self.assertEqual(environment.effective_versions(root)['skypilot'], '98.0.0')


if __name__ == '__main__':
    unittest.main()
