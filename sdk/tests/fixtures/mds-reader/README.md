# MDS reader fixtures

These corpora were generated with the unmodified MosaicML Streaming 0.13.0
MDSWriter at commit `adeeeebe8f5d42cb429c05bd4e62df2176ef20ab`.

Each directory contains 24 items, written in ordinal order. Item `i` has
`number = i` and `text = f"item-{i:02}-" + "x" * 24`. Writer columns are
`{"number": "int", "text": "str"}`, hashes are `["sha256"]`, and `size_limit`
is 512 bytes. The `raw` directory uses no compression; the other directory
names are the compression values passed to the writer.

`provenance.json` records SHA-256 hashes for the original generated files.
Tests verify these hashes before using the fixtures. Keep the original bytes
when changing the reader so writer and reader changes cannot hide each other's
incompatibilities.
