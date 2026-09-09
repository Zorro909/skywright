"""Check that the initialized authority is serving its local protocol."""

import json
import socket


def check(path="/var/lib/skywright-writer/socket/authority.sock"):
    with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as connection:
        connection.settimeout(1)
        connection.connect(path)
        connection.sendall(b'{"operation":"health"}')
        data, _, flags, _ = connection.recvmsg(256)
        if flags & socket.MSG_TRUNC or json.loads(data) != {"status": "ready"}:
            raise RuntimeError("Authority did not acknowledge readiness")


if __name__ == "__main__":
    check()
