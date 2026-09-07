"""Exercise the fixed collector program against real files and a SQLite source."""
import base64
from contextlib import closing
import importlib.util
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import tracemalloc
import unittest

MODULE = Path(__file__).resolve().parents[2] / "skypilot-api-server-deployment/src/main/docker/startup/archive_read.py"
spec = importlib.util.spec_from_file_location("archive_read", MODULE)
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
NAME = "skywright-38c76a5b-7cba-400e-9595-7657b194ea83"


class ArchiveReadTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        (self.home / ".sky").mkdir()
        (self.home / "sky_logs").mkdir()
        self.database = self.home / ".sky/jobs.db"
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("CREATE TABLE jobs (job_id INTEGER,job_name TEXT,run_timestamp TEXT,status TEXT,pid INTEGER,log_dir TEXT)")

    def job(self, identifier, data, *, name=NAME, status="RUNNING", pid=0):
        directory = self.home / "sky_logs" / f"{identifier}-{name}"
        directory.mkdir()
        path = directory / "run.log"
        path.write_bytes(data)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("INSERT INTO jobs VALUES (?,?,?,?,?,?)", (identifier, name, f"timestamp-{identifier}", status, pid, str(directory)))
        return path

    def test_preserves_setup_ansi_crlf_invalid_utf8_and_binary_bytes(self):
        body = b"setup\r\n\x1b[31m20%\r30%\x1b[0m\n\xff\x00training\n"
        self.job(3, body)
        before = self.database.read_bytes()
        result = reader.task_page({"taskName": NAME, "limit": 7}, home=self.home)
        captured = base64.b64decode(result["bytes"])
        while not result["endOfFile"]:
            result = reader.task_page({"taskName": NAME, "limit": 7, "cursor": result["cursor"]}, home=self.home)
            captured += base64.b64decode(result["bytes"])
        self.assertEqual(captured, body)
        self.assertEqual(self.database.read_bytes(), before)
        self.assertFalse(result["sealed"])

    def test_exact_job_name_and_ordered_internal_generations(self):
        self.job(1, b"other", name="unrelated")
        self.job(3, b"first", status="SUCCEEDED", pid=2147483647)
        self.job(8, b"second")
        first = reader.task_page({"taskName": NAME}, home=self.home)
        second = reader.task_page({"taskName": NAME, "cursor": first["cursor"]}, home=self.home)
        self.assertTrue(first["sealed"])
        self.assertEqual(base64.b64decode(first["bytes"]), b"first")
        self.assertEqual(base64.b64decode(second["bytes"]), b"second")
        self.assertNotEqual(first["generation"], second["generation"])

    def test_terminal_row_does_not_hide_driver_epilogue(self):
        script = 'import time; time.sleep(10) # SKYPILOT_JOB_ID <3>'
        process = subprocess.Popen([sys.executable, "-c", script])
        self.addCleanup(lambda: process.poll() is None and process.kill())
        path = self.job(3, b"training", status="SUCCEEDED", pid=process.pid)
        first = reader.task_page({"taskName": NAME}, home=self.home)
        self.assertFalse(first["sealed"])
        with path.open("ab") as stream:
            stream.write(b"\nepilogue\n")
        process.terminate()
        process.wait(timeout=3)
        last = reader.task_page({"taskName": NAME, "cursor": first["cursor"]}, home=self.home)
        self.assertEqual(base64.b64decode(last["bytes"]), b"\nepilogue\n")
        self.assertTrue(last["sealed"])

    def test_replacement_and_same_offset_change_are_explicit(self):
        path = self.job(3, b"first bytes")
        first = reader.task_page({"taskName": NAME, "limit": 5}, home=self.home)
        path.write_bytes(b"other bytes")
        with self.assertRaisesRegex(reader.Unavailable, "SOURCE_PREFIX_CHANGED"):
            reader.task_page({"taskName": NAME, "cursor": first["cursor"]}, home=self.home)
        path.rename(path.with_suffix(".old"))
        path.write_bytes(b"first bytes")
        with self.assertRaisesRegex(reader.Unavailable, "SOURCE_REPLACED"):
            reader.task_page({"taskName": NAME, "cursor": first["cursor"]}, home=self.home)

    def test_symlink_and_fifo_are_never_followed_or_waited_on(self):
        root = self.home / "sky_logs"
        outside = self.home / "secret"
        outside.write_bytes(b"not logs")
        (root / "link").symlink_to(outside)
        os.mkfifo(root / "pipe")
        for name in ("link", "pipe"):
            with self.assertRaises((OSError, reader.Unavailable)):
                reader.regular_page(root / name, root, 0, 10)

    def test_large_file_read_has_fixed_memory_and_response_bounds(self):
        path = self.job(3, b"header")
        with path.open("ab") as stream:
            stream.truncate(256 * 1024 * 1024)
        tracemalloc.start()
        try:
            result = reader.task_page({"taskName": NAME}, home=self.home)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(len(base64.b64decode(result["bytes"])), reader.MAX_BYTES)
        self.assertLess(peak, 8 * 1024 * 1024)
        self.assertFalse(result["endOfFile"])


if __name__ == "__main__":
    unittest.main()
