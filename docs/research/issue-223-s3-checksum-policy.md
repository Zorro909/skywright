# Issue 223 S3 checksum policy

Primary-source investigation for [#223](https://github.com/Zorro909/skywright/issues/223).
The SDK lock and installed environment both contain **boto3 1.43.73 and botocore
1.43.73**. The findings below apply to that version; no provider or SDK source
was modified. Domain transitions remain outside the proposed transport policy.

## Python compatibility mapping

Map the registered request policy directly:

| Registered value | Public `botocore.config.Config` value |
| --- | --- |
| `checksumCalculation: when-required` | `request_checksum_calculation="when_required"` |
| `checksumCalculation: when-supported` | `request_checksum_calculation="when_supported"` |
| `pathStyleAccess: true` | `s3={"addressing_style": "path"}` |
| `pathStyleAccess: false` | `s3={"addressing_style": "virtual"}` |

Use the repository's `when-required` default when the registered option is absent;
do not inherit a developer's AWS configuration implicitly. Reject unsupported
registered values before transfer. Botocore has no documented S3
`chunkedEncoding` toggle. `payload_signing_enabled` controls SigV4 body hashing,
and is **not** a substitute for disabling HTTP/aws-chunked checksum trailers.
[Config documentation](https://docs.aws.amazon.com/botocore/latest/reference/config.html),
[pinned configuration source](https://github.com/boto/botocore/blob/1.43.73/botocore/config.py)

The existing uploader already supplies a precomputed `ChecksumSHA256` and
`ContentLength` for `PutObject` and each `UploadPart`. Botocore checks for an
explicit checksum header before resolving automatic checksum calculation. That
early return prevents the trailer wrapper for both registered calculation
policies. Without an explicit checksum, supported streaming HTTPS operations
can instead select a trailer, which replaces Content-Length and adds
Transfer-Encoding, Content-Encoding and trailer headers.
[Checksum resolution and trailer source](https://github.com/boto/botocore/blob/1.43.73/botocore/httpchecksum.py#L316)

Recommendation: retain precomputed headers and known-length, seekable bodies for
all upload requests. This honors `chunkedEncoding: disabled` without private
hooks or weakening `when-supported`. Treat `enabled` as permission to use
chunking, not a requirement to force it for these already-hashed requests. Do
not pass a fictitious `chunked_encoding_enabled` boto option. Keep the full
registered option map available and validate both enum values; explain this
transport choice in the uploader seam.

An offline probe exercised the installed serializer and checksum-resolution
functions for PutObject/UploadPart × HTTP/HTTPS × required/supported: all eight
retained Content-Length and explicit SHA-256 with no chunk/trailer headers.
It used synthetic credentials and made no network requests. This checks request
construction, not provider qualification. Add actual wire/provider regressions
to protect the behavior when upgrading botocore.

## Trustworthy object evidence

Use a checksum-header fast path only when the response explicitly declares
`ChecksumType: FULL_OBJECT`, its SHA-256 is valid base64 encoding of exactly 32
bytes, and the recorded object size matches. A well-formed full-object checksum
that disagrees with the expected digest is a rejection, not permission to accept
another metadata assertion. Missing type/checksum, malformed values and
`COMPOSITE` are unsuitable evidence and require streamed verification.
[HeadObject response](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html),
[checksum type definition](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Checksum.html)

SHA-256 multipart upload checksums are composite checksums built from part
checksums. Do not strip a multipart suffix or treat a matching-looking composite
value as the whole-object digest. Keep `CreateMultipartUpload`'s SHA256 algorithm
and per-part checksums; do not force `ChecksumType=FULL_OBJECT` onto SHA256
multipart uploads. AWS documents full-object multipart upload checksums for CRC
algorithms, while SHA256 uses composite. This limitation concerns uploads;
separate at-rest checksum services have different capabilities.
[S3 upload integrity](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)

Writer metadata such as `skywright-sha256` and ETags cannot replace verified
bytes. Existing domain-required metadata checks can remain, but cannot establish
content integrity by themselves. For fallback, read the whole object through a
fixed-size buffer, count the bytes, hash SHA-256 and require the expected size
and digest. Permit at most expected size plus one byte to detect excess data;
bound requests/retries and wall-clock work, and close or abort the body on every
exit. Pin the GET to the HEAD version or use `IfMatch` when available to avoid
combining observations from different objects.
[GetObject conditions and version selection](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)

## Avoid accidental validation of unsuitable GET headers

Botocore 1.43.73's automatic response checker skips a checksum containing a
hyphen, but does not consult `ChecksumType`. A provider's unsuitable composite
header without the conventional suffix can therefore trigger a false
full-stream comparison before application verification completes.
[Response checker](https://github.com/boto/botocore/blob/1.43.73/botocore/httpchecksum.py#L568)

For the explicit streamed fallback, configure
`response_checksum_validation="when_required"` and omit `ChecksumMode` on the
GET, then perform the bounded size/SHA-256 check in application code. HEAD may
still request `ChecksumMode="ENABLED"` to obtain fast-path evidence. Response
validation is a separate option from the registered **request** checksum
calculation policy; setting both policies to `when-required` indiscriminately
would silently discard the registered request choice. Apply the same distinction
to the Java verifier so an automatic response wrapper cannot reinterpret an
unsuitable header as whole-object evidence.
[Response configuration](https://docs.aws.amazon.com/botocore/latest/reference/config.html)

Real-provider coverage should include single-part full-object evidence, SHA256
multipart/composite evidence, absent or unusable checksum headers, both registered
request policies and disabled chunking. Keep negative size/digest cases, including
correct writer metadata with wrong bytes, and verify refresh/promotion consume
the same transport policy without sharing their catalogue state transitions.

## Implemented policy and provider regression

Java Dataset Copy verification and isolated publication verification share the
bounded `TransferObjects.verify` byte path. It consumes at most the expected size
plus one byte, hashes SHA-256 with a 1 MiB buffer, checks declared length and aborts
the body on every exit. It does not require checksum headers or temporary files.
Qualification, copy operations and publication workers use the same registered
request-checksum default and mapping; the worker job retains the selected value.
Catalog promotion and refresh transitions remain in the Dataset catalog.

Promotion now returns HTTP 202 with a durable `PROMOTE` operation in `VERIFYING`.
A separate local Transfer Worker receives only the immutable job metadata and
its `transfer-worker` credential projection. The parent clears the child environment,
records PID and process start time before sending credentials through stdin, and
accepts only a bounded receipt for that exact worker attempt. The catalogue switches
authority and completes the operation in one short transaction after checking the
captured catalogue revision and candidate generation. Concurrent edits require a
fresh verification; cancelled or superseded work cannot publish authority.

The maintenance dispatcher admits one worker at a time without a queue. Status,
lease admission and cancellation use separate short transactions. Cancellation
interrupts the dispatcher on its next maintenance tick and terminates the child.
Durable projection records remain open until the child has exited. Startup recovery
checks PID and start time, terminates surviving old workers, and releases their
projections before redispatch. A child watchdog also exits when its parent dies.
Refresh staging, verification and deletion use this same process boundary.

`skywright.dataset-catalog.worker-timeout` defaults to `PT1H`, is configurable from
one millisecond through `PT24H`, and bounds each worker phase including acquisition
and consumption of S3 response bodies. The child watchdog covers a trickling body
even if an HTTP timeout keeps resetting. Storage calls also have a 30-second call
budget and read-idle timeout. A deadline leaves a durable retryable failure and
preserves the current authority. This setting does not change Dataset Publication's
existing verification-runtime policy.

The migration adds `dataset_copy_worker_projection` and the API gains the `promote`
operation kind. Older servers cannot deserialize retained `PROMOTE` operations;
a software downgrade requires restoring the pre-upgrade database snapshot. Stop
workers before rolling back the schema.

The Python uploader maps registered request policy explicitly, keeps precomputed
checksum headers, and streams uncertain retry evidence. Its full-object fast
path requires an explicit type and valid 32-byte SHA-256. Streamed retry reads use
If-Match when an ETag is available and enforce expected length plus one byte.

`DatasetChecksumApiIT` runs actual qualification, publication facts, promotion,
refresh staging/verification and mismatch rejection against SeaweedFS and
PostgreSQL. With the original `S3DatasetCopyStorage` from `caf2f6b`, it fails at
promotion with `DATASET_COPY_MANIFEST_MISMATCH`. With the fix, all three provider
cases pass:

| Object | Observed SHA-256 header evidence | Verification result |
| --- | --- | --- |
| Single PUT, 4 KiB | SHA-256 present, checksum type absent | Streamed bytes accepted |
| Two-part upload, 5 MiB plus 37 bytes | `COMPOSITE`, checksum ends in `-2` | Streamed bytes accepted |
| PUT without SHA-256, 4 KiB | SHA-256 and checksum type absent | Streamed bytes accepted |

Every case completes refresh to generation 2. Same-size corrupt content and
short content with claimed expected metadata both fail promotion without changing
authority. The failed operation and its failure evidence advance the catalogue revision. The dedicated SDK wire tests execute actual boto3 HTTP
requests for both registered checksum policies and both chunking permissions.
Java transport coverage exercises request policy and a deliberately unsuitable
GET checksum header through the actual S3 client. These controlled HTTP tests
complement the real provider test; they are not additional provider qualifications.
