# Run Store read qualification

Issue [#217](https://github.com/Zorro909/skywright/issues/217) implements the accepted
[ADR 0008 metadata-only download decision](../adr/0008-address-run-stores-through-pre-registered-target-storages.md).
Qualification ran on 2026-09-06 against implementation
`a108c670d393b820db37ee21a636f35ed76447fe`. The baseline was
`0657469b2f9e4b6906b65ff7bbc325c2aa354415`. Raw results are in
[run-store-reads.json](run-store-reads.json).

## Interface and budgets

Java `RunStoreAccess.listOutputs(kind, limit, continuation)` returns one page in provider
key order, with a limit of 1..1000. Request Artifact and Sample pages separately. Pass
continuation tokens back with the same Run, resolved location, output kind and limit.
Pagination is not a snapshot across concurrent writes. Each page lists keys and sizes,
then uses HEAD for protocol metadata. It never fetches output bodies or constructs a
lifetime collection. Callers that accumulate pages must budget for their own collection.

`RunStoreObjectStore` separates `list`, `head` and caller-owned `open` streams. Its retained
`get` convenience method accepts only control objects of at most 16 MiB. Direct stream
consumers call `content.accept()` after their integrity checks pass, then close the content. Larger consumers
use streaming. `resolveCheckpoint` returns `RunStoreObjectMetadata`; it validates the
reference and expected checksum without decoding or loading State.

Java and Python download links include the exact key, expected size and SHA-256. Java
returns `RunStoreDownloadLink`; Python returns `DownloadLink`, with the URL in `.url`.
Descriptors explicitly report `NOT_RECORDED` / `not-recorded`: protocol-v1 objects contain
writer-declared integrity metadata, but no independent recorded verification result.
HEAD checks canonical identity, expected metadata and the current resolved location.
A link is not evidence of a new full-body verification. Raw external consumers must
compare received bytes with its expected size and SHA-256.

The caller supplies the authorized Run and its resolved current Target Storage, including
current credentials. The readers reject keys from another Run; the Java S3 adapter also
rejects a protocol/location scope mismatch before any provider call. These modules do not
introduce an HTTP endpoint or replace application authorization. Run Store Inspector wiring
is tracked by dependent issue #235. Output bytes do not proxy through a backend HTTP
request; `stageDownload` is for local or worker consumption.

Java `stageDownload(key, directory, maxBytes)` verifies one private temporary file before
returning a caller-owned `VerifiedRunStoreObject`. Close it to delete the file. Python
`download(key, destination, max_bytes=...)` verifies a temporary sibling before atomically
replacing the destination. Invalid content preserves the previous destination. Both require
a positive caller-selected disk limit and reject objects larger than available disk space.
They hash chunks of at most 1 MiB and reject truncation, excess bytes and digest mismatch.
Responses close and staging is removed on failure.

Python checkpoint recovery uses one staged file of size N, defaulting to a 64 GiB object
limit configurable with `max_checkpoint_bytes`. `staging_directory` controls its placement.
The decoder checks the complete file digest, validates the Safetensors container, then reads
each tensor directly into its decoded allocation. It never reads or slices a full payload
buffer. Snapshot construction keeps its defensive copy, so numeric recovery requires at
most two decoded payloads at that point. Bytes values are immutable and can share ownership.
The qualified host budget is `2 * decoded numeric payload + 64 MiB`, above the initialized
Python/ML runtime. The test uses two contiguous float32 model/optimizer arrays. This is a
measured budget for that workload, not an RSS guarantee for every portable value tree.
Structural metadata allocates Python objects separately; encoded headers are capped at
32 MiB, tensor entries at one million and tree depth at 64. NumPy object dtypes, invalid
shapes and inconsistent offsets are rejected. Concurrent reads each consume a separate
budget. Disk-space checks do not reserve capacity against unrelated writers.

## Results

| Workload | Baseline | Bounded implementation | Acceptance budget |
| --- | ---: | ---: | ---: |
| Recovery payload | 128 MiB | 128 MiB | Same state |
| Additional peak RSS | 636.68 MiB | 246.63 MiB | 320 MiB |
| Peak staged disk | 134,218,616 bytes | 134,218,616 bytes | One object |
| Full recovered state comparison | Pass | Pass | Required |

Recovery runs in separate processes against the same pinned SeaweedFS service. A sampler
reads process RSS and staged file sizes every 2 ms; it includes NumPy/PyTorch native memory.
The initialized runtime is sampled before recovery. Public defensive state access and
full value comparisons happen after the measured read, so those caller-requested copies
are outside the recovery measurement. The fixed run completed in 0.220 seconds versus
0.434 seconds on baseline; elapsed time is diagnostic, not a performance guarantee.

Java listed and signed 100 real 4 MiB outputs, representing 400 MiB of stored payload:

- Four LIST calls and 200 HEAD calls, split equally between listing and signing.
- 100 local presign operations, zero GET calls and zero payload bytes transferred.
- 134,440 bytes of retained heap growth after page release and garbage collection.
- An actual streamed download consumed and verified 4,194,304 bytes.
- Same-size corruption remained signable with `NOT_RECORDED` evidence, then failed
  consumption and left no staged file. Malformed size metadata failed before signing.

Counts come from the production adapter's measured SDK invocations against real SeaweedFS.
Local presigning is not an HTTP request. These measurements do not claim to count hidden
AWS SDK wire retries. The isolated service completed these calls successfully. Heap samples
measure retained memory, while the no-GET assertion and stream chunk limit independently
check the paths that previously allocated payload bodies.

The existing 4,096-read qualification still reports 256 retained diagnostics and an explicit
3,840-record gap. All 168 backend unit tests, 287 SDK unit tests, seven Python real-S3 tests,
Java Run Store and Target Storage qualification tests passed. SDK formatting, lint, strict
Pyright and public type completeness passed. Python's system test also runs a 32 MiB fresh
process recovery and rejects corrupt content, a malformed container with a correct digest,
and malformed S3 metadata. The initial regression test failed on an unbounded `Body.read()`.

Environment: Linux 7.1.5-201.fc44 x86_64, glibc 2.43, Python 3.12.11, NumPy 2.4.6,
boto3 1.43.73, Java 25.0.4 GraalVM CE 25.2.4+7.1. SeaweedFS uses the repository's pinned
4.42 image and digest in `sdk/tests/integration/test_run_store_system.py`.

## Reproduction

```sh
sdk/scripts/check
uv run --project sdk --locked --group ml-test pytest -m integration sdk/tests/integration/test_run_store_system.py
mvn -pl backend -am -DskipFrontendInstall=true -DskipFrontendTests=true \
  -Dtest=RunStoreAccessTest,RunStoreGoldenCorpusTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=RunStoreS3IT,TargetStorageQualificationIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Run the 128 MiB recovery with an automatically cleaned-up service:

```sh
uv run --project sdk --locked --group ml-test python - <<'PY'
import subprocess
import sys
from pathlib import Path
sys.path.insert(0, str(Path.cwd() / "sdk/tests"))
from integration.test_run_store_system import seaweedfs
with seaweedfs() as (endpoint, client):
    client.create_bucket(Bucket="recovery-bounds")
    command = [sys.executable, "sdk/tests/support/recovery_read_scenario.py"]
    arguments = ["--endpoint", endpoint, "--bucket", "recovery-bounds"]
    subprocess.run([*command, "prepare", *arguments], check=True)
    subprocess.run([*command, "recover", "--expect-bounded", *arguments], check=True)
PY
```

For the baseline, set `PYTHONPATH` to its exported `sdk/src` in the recovery subprocess and
omit `--expect-bounded`. The runner uses the shared public checkpoint interface in both versions.

## Review follow-up

GET measurements now finalize when the response closes. They count bytes consumed by the
caller, rather than the advertised object length. Budget rejection and cancellation before
reading report zero bytes and failure; truncation, excess content and checksum failure
report the consumed bytes and failure. Successful validation and response closure produce
one successful record. These are application-consumed bytes, not a claim about provider
billing or bytes buffered by the HTTP transport. Python recorder conflict/progress reads
also close their response owners, preserving their existing integrity checks. Immutable-write
retry reconciliation uses one bounded verification GET and its metadata, removing the
second unconsumed GET and the full-payload comparison.

Seven Python regression cases cover these outcomes and bounded immutable-write retries. Three additional Java tests feed malformed
LIST responses through the actual S3 client and count the HTTP requests. Oversized pages,
missing continuation tokens and repeated tokens each produce exactly one failed measurement.
The successful LIST measurement is recorded only after page validation. Real S3 coverage
also checks that a same-size corrupt Java download records its consumed bytes as a failure.
