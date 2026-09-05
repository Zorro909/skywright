# MosaicML Streaming MDS reader

Source: https://github.com/mosaicml/streaming/tree/adeeeebe8f5d42cb429c05bd4e62df2176ef20ab
Version: 0.13.0. Copyright 2022-2024 MosaicML Streaming authors.
License: Apache-2.0, reproduced in LICENSE. Upstream ships no NOTICE file.

Skywright distributes the MDS reader and codecs with these changes:

- Imports refer to this private package instead of the top-level streaming package.
- Only bytes_to_int is included from util.py.
- The Pickle codec, pickle import and pkl registration are removed. The upstream
  unsafe-encoding gate remains, and Skywright validates all input encodings before
  calling the reader.

The upstream reader logic and remaining codecs are unchanged. Original source
SHA-256 values are in provenance.json. Keep upstream formatting to make updates
reviewable. The integration corpus was generated with the unmodified upstream
MDSWriter; its provenance and hashes live in sdk/tests/fixtures/mds-reader.

This packaging avoids importing MosaicML's model-loading and cloud-provider
modules. Its 0.13 distribution requires transformers below version 5, which cannot
resolve the patched releases for GHSA-29pf-2h5f-8g72, GHSA-fgcw-684q-jj6r and
GHSA-xrqw-3rrv-vx5w. No Transformers code is included here.
