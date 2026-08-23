from __future__ import annotations

import base64
import csv
import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/retag-wheel"


class RetagWheelTest(unittest.TestCase):
    def test_retags_platform_and_rebuilds_record(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "example-1.0-py3-none-linux_i686.whl"
            output = root / "wheelhouse"
            wheel_metadata = (
                "Wheel-Version: 1.0\n"
                "Generator: test\n"
                "Root-Is-Purelib: false\n"
                "Tag: py3-none-linux_i686\n"
            ).encode()
            with zipfile.ZipFile(source, "w") as wheel:
                wheel.writestr("example/native.so", b"native")
                wheel.writestr("example-1.0.dist-info/WHEEL", wheel_metadata)
                wheel.writestr("example-1.0.dist-info/RECORD", b"stale\n")

            result = subprocess.run(
                [str(SCRIPT), str(source), str(output), "linux_x86_64"],
                check=True,
                capture_output=True,
                text=True,
            )

            retagged = output / "example-1.0-py3-none-linux_x86_64.whl"
            self.assertEqual(result.stdout.strip(), str(retagged))
            with zipfile.ZipFile(retagged) as wheel:
                self.assertIn(
                    "Tag: py3-none-linux_x86_64",
                    wheel.read("example-1.0.dist-info/WHEEL").decode(),
                )
                record = list(
                    csv.reader(
                        io.StringIO(
                            wheel.read("example-1.0.dist-info/RECORD").decode()
                        )
                    )
                )
                entries = {row[0]: row[1:] for row in record}
                native = wheel.read("example/native.so")
                digest = base64.urlsafe_b64encode(hashlib.sha256(native).digest())
                expected_hash = f"sha256={digest.rstrip(b'=').decode()}"
                self.assertEqual(
                    entries["example/native.so"], [expected_hash, str(len(native))]
                )
                self.assertEqual(
                    entries["example-1.0.dist-info/RECORD"], ["", ""]
                )


if __name__ == "__main__":
    unittest.main()
