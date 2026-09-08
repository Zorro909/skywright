# Run log archive

The backend captures raw SkyPilot task and controller bytes into each Run's current
Run Store. The SkyPilot server and client SDK remain unchanged. A Skywright-owned
collector runs beside the server and exposes only bounded reads on private port
46582. Submission, status and control still use the SDK.

## Source qualification

This implementation supports the pinned SkyPilot 0.13.0 LOCAL Kubernetes workflow.
It reads the existing PostgreSQL tables in read-only transactions, without importing
SkyPilot or initializing its schema. Exact Run-derived task names identify the
single managed task. Ambiguous matches are unavailable.

The collector derives the pinned cluster names, verifies the database's user,
workspace and Kubernetes context, and selects exactly one head pod using
`ray-cluster-name` and `ray-node-type`. It verifies the logical cluster annotation
and pod UID. An isolated worker process enforces the request deadline across the Kubernetes
client's websocket connection and frame reads. A fixed Python program runs in the `ray-node` container through
non-TTY Kubernetes exec. That program reads the existing SQLite job database in
read-only mode and opens only regular log files below `~/sky_logs`, without following
symlinks. It returns raw file ranges as base64. No shell command, path or SQL comes
from the caller.

Task bytes come from each internal job's persisted `log_dir/run.log`, which includes
setup and training. Internal job IDs are followed in order; they are not assumed to
start at one. Subordinate setup/task files are not concatenated into this aggregate.
Controller bytes come from `sky_logs/jobs_controller/<managed-job-id>.log`. These
streams have independent positions and provenance. The archive adds no timestamps,
lines or cross-stream ordering to their bytes.

A terminal job row can precede the driver's final output. The node reader requires
the recorded driver process to be gone, or its command identity to differ, before
sealing a generation. Whole-stream completion also requires SkyPilot's later
`schedule_state=DONE` barrier and no remaining internal generation. Controller
completion uses that barrier too.

If the pod has gone, SkyPilot's retained `local_log_file` may supply raw task bytes.
That path does not record the original pod UID. When active capture already exists,
the collector refuses to splice this copy into the ordered stream and finalizes
the verified capture as partial with `SOURCE_GENERATION_UNCONFIRMED`. Matching
prefix bytes cannot establish whether the copy belongs to the same generation. An initially discovered
copy with previous recoveries records `EARLIER_GENERATIONS_UNAVAILABLE`.

## Publication and restart

Objects live below `<project>/<run>/v1/skypilot/logs/`:

| Relative key | Contents |
| --- | --- |
| `<stream>/chunks/<19-digit-offset>-<sha256>` | Immutable raw bytes |
| `<stream>/index/<19-digit-sequence>.json` | Offset, size, digest, source generation, boundaries and next capture cursor |
| `navigation/<attempt-id>.json` | Confirmed attempt boundary and preceding setup offset |
| `manifest.json` | Immutable final stream summaries and final capture checkpoint |

Each chunk precedes its index. The producer reads the exact next index before
fetching new bytes, recovering an uncertain publication without duplicate chunks.
S3 conditional creation and body/digest verification make retries idempotent.
The database stores only bounded capture metadata, not log payloads.

The managed runtime emits `RS SKYWRIGHT_ATTEMPT_V1 <JSON> US LF` after durable
Execution Attempt publication and before project code. The backend validates its
schema, Run, Project Version and attempt identity against the durable record.
Markers may span chunks. Raw marker bytes remain in the stream. Unverifiable,
duplicate, oversized or missing markers make task navigation partial. Controller
bytes are never interpreted as attempt markers. Setup failure before any attempt
remains an unlinked setup segment.

A database lease and locked publication fence coordinate backend workers. A worker
whose token was replaced cannot publish. Finalization reads any existing immutable
manifest before touching the source. Once publication is confirmed,
`RunLogCaptureStore.finalization(runId)` exposes its relative key, SHA-256 and original
publication time. This is the producer-closure contract for #237 and #53. Consumers
resolve that relative key through the Run Record's current Storage Location.
Relocation must wait for this fact; archive production cannot resume afterward.

## Bounds and incomplete capture

| Operation | Bound |
| --- | --- |
| Raw page/chunk | 1 MiB |
| Marker / collector cursor | 4 KiB each |
| Chunk index / final manifest | 64 KiB each |
| Persisted database checkpoint | 32 KiB |
| Collector request / response | 8 KiB / 2 MiB |
| Kubernetes metadata response | 256 KiB |
| Capture concurrency / queue | 2 workers / 8 queued Runs |
| Collector concurrency / backlog | 2 handlers and 2 worker processes / 4 connections |
| Worker address space / CPU | 512 MiB / 8 CPU seconds |
| Sweep / per-Run next attempt | 5 seconds; at most 16 queued candidates per sweep |
| HTTP fetch / enforced source worker deadline | 10 seconds / 8 seconds plus at most 2 seconds to reap |
| Kubernetes request | 1-second connect, 2-second read; exec loop 5 seconds |
| PostgreSQL query / lock | 1 second / 250 milliseconds |
| S3 request | 5 seconds, one attempt |
| Publication cycle | No new S3 operations after 40 seconds |
| Lease | 60 seconds |
| Terminal failed fetches | 3, persisted across restart |
| Finalization window | 5 minutes from first retained terminal observation |
| Backend shutdown | Interrupt workers immediately; no unbounded join |
| Collector shutdown | SIGTERM terminates its isolated process; pod grace 30 seconds |

Productive capture does not consume failed-fetch retries. Empty terminal reads
without writer closure do. Active source outages leave the cursor retryable.
Exhaustion finalizes a partial stream with the source's machine-readable reason.
The finalization deadline also bounds productive backlogs and marker verification.
Storage failures leave publication unconfirmed and retryable; they cannot fabricate
a manifest or authorize relocation.

Partial reasons include `SOURCE_GENERATION_LOST`, `SOURCE_REPLACED`,
`SOURCE_TRUNCATED`, `SOURCE_PREFIX_CHANGED`, `UNVERIFIABLE_BOUNDARY`,
`WRITER_UNCONFIRMED`, `COLLECTOR_UNAVAILABLE`, `FINALIZATION_DEADLINE` and
`MARKER_VERIFICATION_DEADLINE`. The first recorded loss remains sticky. Complete
means the available generations and writer closure were verified, not merely that
compute became terminal. Finalization never changes the Run's lifecycle.

## Deployment and verification

Mount the same protected, mode-0400 `SKYWRIGHT_KUBECONFIG` projection on the
`skypilot-api-server`, `runtime-pull` and `log-collector` containers. It must identify
the accepted LOCAL context and namespace using its static token. The collector's
controller-state PVC mount is read-only. Its HTTP service is reachable only from
the backend under the existing NetworkPolicy. Configure
`skywright.log-collector.endpoint`; the provided overlays set it to the private
service. `skywright.run-log-capture.enabled=false` disables scheduling for fixtures.

Tests cover actual SQLite/files, PostgreSQL and S3 publication, restart after a
lost cursor acknowledgement, setup failure, recovery boundaries, source loss,
terminal retries, immutable authority handoff and raw binary/ANSI/CRLF fidelity.
The packaged image tests qualify read-only state access, equivalence with the
pinned SDK's cluster naming and the actual Kubernetes TLS/exec client against a
controlled API executing the fixed reader, including stalled websocket handshakes
and incomplete frames. These are source-protocol tests. The
real LOCAL GPU workflow remains #235.


## Bounded archive viewer

Issue #234 adds archive-backed reads under `/api/v1/run-logs/{runId}`:

- `GET /{stream}` defaults to a bounded tail. `cursor` reads forward from an
  absolute byte position; `before` reads the preceding bounded window. They are
  mutually exclusive. Task and controller positions are independent decimal
  strings, including positions larger than JavaScript's safe integer range.
- `GET /{stream}/follow` sends `archive` SSE events containing the same JSON page
  and base64 raw bytes. An event ID is its `nextCursor`. The browser reconnects
  explicitly from its renderer-acknowledged cursor, not the last dispatched SSE
  event. Duplicate and overlapping ranges are clipped before terminal parsing;
  gaps stop rendering without advancing the cursor.
- `GET /navigation` pages setup and confirmed attempt boundaries from the
  immutable indexes. Its opaque metadata page token is separate from the absolute
  task-byte cursor carried by each segment. Empty pages can have a next token.

Every read resolves the Run Record's current location and read credentials. The
immutable terminal manifest, when present, determines completion. Otherwise the
persisted producer checkpoint supplies a staging high-water mark. Source-read
failures and the last successful source fetch remain explicit and survive backend
restart. A staging stream never becomes complete just because compute ended.
Missing or invalid archive objects make that read unavailable; the viewer does
not fall back to SkyPilot. Reading an alternate location is tested after the
original objects are removed. Actual Repatriation remains in #53/#64/#86.

| Resource | Bound |
| --- | --- |
| Returned raw page | 64 KiB, at most four immutable chunks |
| Raw verification buffer | One 1 MiB chunk at a time |
| Index / manifest read | 64 KiB |
| Index search | At most 63 probes per binary search; fixed S3 cycle deadline still applies |
| Archive read admission | Eight concurrent reads, no admission queue |
| Live viewers | 16 per backend instance |
| Follow archive workers / queue | Four workers / 16 jobs, at most one job per viewer |
| Pending SSE frame | One frame of at most 128 KiB per viewer |
| Pending-output deadline | Five seconds, checked by an independent 250 ms timer |
| SSE session lifetime | 30 seconds, followed by explicit cursor replay |
| Follow read cadence | At least 250 ms after data; two seconds after an empty page |
| Browser event assembly | 128 KiB; one awaited renderer write |
| Browser terminal retention | 256 KiB raw input and 1,000 scrollback rows |
| Browser render / request deadline | Five seconds / 45 seconds |
| Navigation page | One index, at most 128 boundaries |

Servlet nonblocking output checks readiness before writing and flushing. A slow
client stops further archive reads while its frame is pending. Expiry cancels
production, drops that view's pending frame and releases admission. Servlet
completion can still drain container/TCP buffers asynchronously; five seconds is
not a promised TCP-reset deadline. Captured objects are never deleted by viewer
backpressure, and reconnect replays from the last rendered byte.

Run details open the viewer on demand. A maintained xterm.js 6.0.0 terminal receives
raw `Uint8Array` writes, preserving split UTF-8 and ANSI parsing across pages.
Input and log-driven link activation are disabled. Paging and setup/attempt
navigation replace the displayed window and pause following; they never prepend
older bytes into the live parser. Retention rollover replaces the terminal, which
also bounds combining-mark state that row limits alone cannot bound. An arbitrary
tail/window boundary lacks prior terminal state, and the UI states that limitation.
The source archive retains every captured byte regardless of display retention.

This local slice retains the ordinary producer reconciliation cadence. Adaptive
viewer demand and real relocation integration remain in #64/#86. Primary-source
renderer and Servlet behavior is recorded in
[the streaming research note](../research/issue-234-terminal-streaming.md).
