from __future__ import annotations

import os
import runpy
import signal
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SUPERVISE = runpy.run_path(str(ROOT / "scripts/ci-verify"))["supervise"]


class VerificationLifetimeTest(unittest.TestCase):
    def test_current_command_keeps_its_exit_status(self):
        self.assertEqual(
            SUPERVISE([sys.executable, "-c", "raise SystemExit(7)"], lambda: True), 7
        )

    def test_stale_source_never_starts_the_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "started"
            command = [
                sys.executable,
                "-c",
                f"from pathlib import Path; Path({str(output)!r}).touch()",
            ]
            self.assertEqual(SUPERVISE(command, lambda: False), 130)
            self.assertFalse(output.exists())

    def test_new_head_stops_the_command_and_a_spawned_child(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "heartbeat"
            pidfile = Path(temporary) / "child-pid"
            child = (
                "import pathlib,time; p=pathlib.Path("
                + repr(str(output))
                + ");\nwhile True: p.write_text(str(time.monotonic())); time.sleep(.01)"
            )
            parent = f"import subprocess,sys,time,pathlib; p=subprocess.Popen([sys.executable,'-c',{child!r}]); pathlib.Path({str(pidfile)!r}).write_text(str(p.pid)); time.sleep(60)"
            calls = 0

            def check():
                nonlocal calls
                calls += 1
                return not output.exists()

            try:
                result = SUPERVISE(
                    [sys.executable, "-c", parent], check, interval=0.05, grace=0.05
                )
                self.assertEqual(result, 130)
                self.assertGreater(calls, 1)
                heartbeat = output.read_text()
                time.sleep(0.1)
                self.assertEqual(output.read_text(), heartbeat)
            finally:
                if pidfile.exists():
                    try:
                        os.kill(int(pidfile.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
