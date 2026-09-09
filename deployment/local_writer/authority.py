"""Bounded Unix protocol for the passive local writer authority."""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import select
import socket
import stat
import struct
import time
import traceback
import uuid
from pathlib import Path

from .common import LIMIT, Pending, Uncertain, canonical, identifier
from .custody import Custody
from .node import Node


class Authority:
    def __init__(self, custody, node):
        self.custody, self.node = custody, node

    def observe(self, key):
        self.node.validate_custody(self.custody.registrations[key])
        if key not in self.custody.proofs:
            self.custody.prove(key, self.node.stopped(self.custody.registrations[key]))
        return self.custody.response(key)

    def handle(self, connection):
        connection.settimeout(1)
        pid, _, _ = struct.unpack(
            "3i", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12)
        )
        peer_fd = struct.unpack("i", connection.getsockopt(socket.SOL_SOCKET, 77, 4))[
            0
        ]  # SO_PEERPIDFD, Linux >= 6.5
        try:
            data, _, flags, _ = connection.recvmsg(LIMIT)
            if flags & socket.MSG_TRUNC or select.select([peer_fd], [], [], 0)[0]:
                raise Uncertain()
            request = json.loads(data)
            if not isinstance(request, dict) or set(request) != {
                "operation",
                "run_id",
                "attempt_id",
            }:
                raise Uncertain()
            run_id, attempt_id = (
                identifier(request["run_id"]),
                identifier(request["attempt_id"]),
            )
            operation = request["operation"]
            if operation not in ("register", "verify"):
                raise Uncertain()
            peer = self.node.peer(pid, run_id)
            if select.select([peer_fd], [], [], 0)[0]:
                raise Uncertain()
            if operation == "register":
                record = dict(
                    schema=1,
                    owner=self.custody.owner,
                    run_id=run_id,
                    attempt_id=attempt_id,
                    registered_at=time.time_ns(),
                    **peer,
                )
                self.custody.register(record)
                return {"status": "registered"}
            key = self.custody.key(run_id, attempt_id)
            if key not in self.custody.registrations:
                raise Uncertain()
            return self.observe(key)
        finally:
            os.close(peer_fd)

    def serve(self, path):
        path.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        parent = path.parent.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(parent.st_mode)
            or parent.st_uid != 0
            or parent.st_mode & 0o022
        ):
            raise Uncertain()
        path.unlink(missing_ok=True)
        with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as server:
            server.bind(str(path))
            os.chmod(path, 0o666)
            server.listen(16)
            server.settimeout(0.25)
            observation_index = 0
            while True:
                try:
                    connection, _ = server.accept()
                except TimeoutError:
                    connection = None
                if connection is not None:
                    with connection:
                        try:
                            response = self.handle(connection)
                        except Pending:
                            response = {"status": "pending"}
                        except Exception as failure:  # noqa: BLE001 - provider values never enter diagnostics
                            response = {"status": "uncertain"}
                            frame = traceback.extract_tb(failure.__traceback__)[-1]
                            print(
                                json.dumps(
                                    {
                                        "event": "writer-authority-unavailable",
                                        "exception": type(failure).__name__,
                                        "source": Path(frame.filename).name,
                                        "line": frame.lineno,
                                    }
                                ),
                                flush=True,
                            )
                        with contextlib.suppress(OSError):
                            connection.sendall(canonical(response))
                # Keep death evidence before container GC; each registration is immutable.
                pending = [
                    key
                    for key in self.custody.registrations
                    if key not in self.custody.proofs
                ]
                if pending:
                    key = pending[observation_index % len(pending)]
                    observation_index += 1
                    with contextlib.suppress(Exception):
                        self.observe(key)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--state", type=Path, default=Path("/var/lib/skywright-writer/custody")
    )
    parser.add_argument(
        "--socket",
        type=Path,
        default=Path("/var/lib/skywright-writer/socket/authority.sock"),
    )
    parser.add_argument("--node-name", required=True)
    parser.add_argument("--node-uid", required=True)
    parser.add_argument("--namespace", default="skywright-training")
    parser.add_argument("--ancillary-render-device")
    parser.add_argument("--initialize", action="store_true")
    arguments = parser.parse_args()
    identifier(arguments.node_uid)
    if arguments.initialize:
        arguments.state.mkdir(mode=0o700, parents=True, exist_ok=True)
        target = arguments.state / "authority.json"
        if not target.exists():
            if list(arguments.state.iterdir()):
                raise Uncertain()
            with target.open("xb") as output:
                os.chmod(target, 0o600)
                output.write(
                    canonical(
                        {
                            "schema": 1,
                            "authority_id": str(uuid.uuid4()),
                            "node_uid": arguments.node_uid,
                        }
                    )
                )
                output.flush()
                os.fsync(output.fileno())
            for directory in (arguments.state, arguments.state.parent):
                descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
        return
    custody = Custody(arguments.state, arguments.node_uid)
    try:
        node = Node(
            arguments.node_name,
            arguments.node_uid,
            arguments.namespace,
            arguments.ancillary_render_device,
        )
        Authority(custody, node).serve(arguments.socket)
    finally:
        custody.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:  # noqa: BLE001 - fail closed without exposing runtime inspection data
        raise SystemExit(
            "Local writer authority refused startup; custody or qualified node requirements were not met"
        ) from None
