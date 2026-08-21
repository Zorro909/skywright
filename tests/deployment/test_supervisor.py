from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from deployment.skywright_deployment import cli
from tests.deployment import test_local_command


REPOSITORY = Path(__file__).resolve().parents[2]
COMMAND = REPOSITORY / "scripts" / "deploy"


def git(repository: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


class SupervisorTest(unittest.TestCase):
    def test_fetch_classifies_transport_failures_as_transient(self) -> None:
        failed = subprocess.CompletedProcess(
            ["git", "fetch"], 128, "", "fatal: unable to access origin: timed out"
        )
        with mock.patch.object(cli, "git", return_value=failed):
            with self.assertRaises(ConnectionError):
                cli.fetch_branch("main", "refs/skywright-follow/test")

    def test_fetch_classifies_missing_and_unauthorized_branches_as_fatal(self) -> None:
        for message in (
            "fatal: couldn't find remote ref refs/heads/missing",
            "fatal: Authentication failed for origin",
        ):
            with self.subTest(message=message):
                failed = subprocess.CompletedProcess(["git", "fetch"], 128, "", message)
                with mock.patch.object(cli, "git", return_value=failed):
                    with self.assertRaisesRegex(SystemExit, "cannot follow origin"):
                        cli.fetch_branch("main", "refs/skywright-follow/test")

    def test_follows_origin_in_an_owned_detached_worktree_and_cleans_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            source = temporary / "source"
            origin = temporary / "origin.git"
            source.mkdir()
            git(source, "init", "--initial-branch", "main")
            git(source, "config", "user.name", "Supervisor Test")
            git(source, "config", "user.email", "supervisor@example.invalid")
            (source / "tracked.txt").write_text("initial\n", encoding="utf-8")
            git(source, "add", "tracked.txt")
            git(source, "commit", "--message", "initial")
            git(temporary, "init", "--bare", str(origin))
            git(source, "remote", "add", "origin", str(origin))
            git(source, "push", "--set-upstream", "origin", "main")
            original_head = git(source, "rev-parse", "HEAD")
            original_worktrees = git(source, "worktree", "list", "--porcelain")

            tools = temporary / "tools"
            tools.mkdir()
            environment, _ = test_local_command.LocalCommandTest().fake_tools(tools)
            skaffold = Path(environment["SKYWRIGHT_SKAFFOLD"])
            skaffold.unlink()
            skaffold.write_text(
                """#!/usr/bin/env python3
import http.server, json, sys
if sys.argv[1] == 'version':
    print('v2.24.0')
    raise SystemExit
port = int(sys.argv[sys.argv.index('--rpc-http-port') + 1])
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        event = {'result': {'event': {'taskEvent': {'task': 'DevLoop', 'status': 'Succeeded', 'iteration': 0}}}}
        self.wfile.write((json.dumps(event) + '\\n').encode())
        self.wfile.flush()
    def log_message(self, format, *args):
        pass
server = http.server.HTTPServer(('127.0.0.1', port), Handler)
server.handle_request()
""",
                encoding="utf-8",
            )
            skaffold.chmod(0o755)
            environment["SKYWRIGHT_REPOSITORY"] = str(source)

            completed = subprocess.run(
                [
                    str(COMMAND),
                    "follow",
                    "main",
                    "--context",
                    "kind-kind-cluster",
                    "--poll-seconds",
                    "0.05",
                ],
                cwd=REPOSITORY,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                timeout=10,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertIn("following origin/main", completed.stdout)
            self.assertEqual(git(source, "branch", "--show-current"), "main")
            self.assertEqual(git(source, "rev-parse", "HEAD"), original_head)
            self.assertEqual(
                git(source, "worktree", "list", "--porcelain"), original_worktrees
            )

    def test_rejects_an_invalid_branch_before_fetching(self) -> None:
        completed = subprocess.run(
            [
                str(COMMAND),
                "follow",
                "../main",
                "--context",
                "kind-kind-cluster",
            ],
            cwd=REPOSITORY,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("invalid branch name", completed.stderr)


if __name__ == "__main__":
    unittest.main()
