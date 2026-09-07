"""Probe the installed SkyPilot decoder without a live controller.

Run with the packaged GraalPy environment from the repository root:
  .graalpy/resources/venv/bin/python docs/research/support/issue62_log_sdk_probe.py
"""

import json

import sky
from sky.utils.rich_utils import decode_rich_status


class Response:
    def __init__(self, chunks):
        self.chunks = chunks

    def iter_content(self, chunk_size=None):
        yield from self.chunks

    def close(self):
        pass


def main():
    cases = {
        "crlf": [b"setup\r\ntrain\r\n"],
        "ansi_and_carriage_return": [b"\x1b[31m10%\r20%\x1b[0m\n"],
        "invalid_utf8": [b"prefix\xffsuffix\n"],
        "split_utf8": [b"prefix\xe2", b"\x82\xacsuffix\n"],
    }
    results = []
    for name, chunks in cases.items():
        original = b"".join(chunks)
        decoded = "".join(
            part for part in decode_rich_status(Response(chunks)) if part is not None
        ).encode("utf-8")
        results.append({
            "case": name,
            "input_hex": original.hex(),
            "output_hex": decoded.hex(),
            "byte_identical": decoded == original,
        })
    print(json.dumps({"skypilot": sky.__version__, "results": results}, indent=2))
    assert not results[0]["byte_identical"]
    assert results[1]["byte_identical"]
    assert not results[2]["byte_identical"]
    assert results[3]["byte_identical"]


if __name__ == "__main__":
    main()
