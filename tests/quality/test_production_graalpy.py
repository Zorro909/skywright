import runpy
from pathlib import Path
import unittest
from unittest.mock import patch

import test_graalpy_environment as fixtures

environment = fixtures.environment

production = runpy.run_path(str(Path(__file__).resolve().parents[2] / 'scripts/verify-production-graalpy'))


class ProductionEnvironmentTest(unittest.TestCase):
    setUp = fixtures.EnvironmentIdentityTest.setUp
    invoke = fixtures.EnvironmentIdentityTest.invoke
    seal = fixtures.EnvironmentIdentityTest.seal

    def seal_for(self, platform, openssl='OpenSSL 3.0.13 30 Jan 2024'):
        with patch.object(environment, 'build_platform', return_value=platform), \
                patch.object(environment, 'native_inputs', return_value={'openssl': openssl}):
            self.seal()

    @staticmethod
    def ubuntu():
        return {'system': 'Linux', 'machine': 'x86_64',
                'distribution': {'ID': 'ubuntu', 'VERSION_ID': '24.04'},
                'libc': ['glibc', '2.39']}

    def test_selects_ubuntu_resources_on_a_different_packaging_host(self):
        self.seal_for(self.ubuntu())
        with patch.object(environment, 'build_platform', return_value={'distribution': 'fedora'}):
            record = production['verify'](self.root, self.resources, self.versions)
        self.assertEqual(self.ubuntu(), record['identity']['platform'])

    def test_rejects_other_distribution_architecture_and_libc(self):
        for replacement in ({'distribution': {'ID': 'fedora', 'VERSION_ID': '44'}},
                            {'machine': 'aarch64'}, {'libc': ['glibc', '2.40']}):
            with self.subTest(replacement=replacement):
                self.seal_for({**self.ubuntu(), **replacement})
                with self.assertRaisesRegex(ValueError, 'Ubuntu 24.04 amd64'):
                    production['verify'](self.root, self.resources, self.versions)

    def test_rejects_newer_openssl_even_on_ubuntu(self):
        self.seal_for(self.ubuntu(), 'OpenSSL 3.3.0')
        with self.assertRaisesRegex(ValueError, 'OpenSSL 3.0 ABI'):
            production['verify'](self.root, self.resources, self.versions)

    def test_selection_still_rejects_changed_native_payload_and_source_inputs(self):
        self.seal_for(self.ubuntu())
        (self.library / 'package.so').write_bytes(b'unqualified native code')
        with self.assertRaisesRegex(ValueError, 'payload differs'):
            production['verify'](self.root, self.resources, self.versions)
        self.seal_for(self.ubuntu())
        dockerfile = self.root / 'backend-deployment/src/main/docker/Dockerfile'
        dockerfile.write_text(dockerfile.read_text() + '\nRUN changed-runtime\n')
        with self.assertRaisesRegex(ValueError, 'effective build inputs'):
            production['verify'](self.root, self.resources, self.versions)


if __name__ == '__main__':
    unittest.main()
