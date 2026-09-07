# SkyPilot log capture constraints for #62

Inspected the installed SkyPilot 0.13.0 source on 2026-09-07, on branch
`issue-62-durable-run-log-archive`, based on `a5aa98a`.

## Required behavior

Issue #62 and ADRs 0018/0019 require separate ordered task and controller byte
streams, bounded incremental capture, and a terminal fetch whose success can
support an honest complete-or-partial manifest. Normal terminal status alone
does not prove archive completeness.

## Evidence from the pinned SDK

`sky.jobs.tail_logs(preload_content=False)` delegates to
`sky.utils.rich_utils.decode_rich_status`. That decoder changes CRLF to LF and
replaces invalid UTF-8. It also buffers incomplete lines, so a project that
writes a long sequence without a newline can grow its buffer indefinitely.

The [installed-SDK probe](support/issue62_log_sdk_probe.py) produced:

| Input | Result |
| --- | --- |
| `setup\r\ntrain\r\n` | `setup\ntrain\n` |
| ANSI color and carriage-return progress | Byte-identical in this fixture |
| `prefix\xffsuffix\n` | `prefix\xef\xbf\xbdsuffix\n` |
| UTF-8 character split between network chunks | Byte-identical in this fixture |

The probe printed all four results and satisfied its assertions. Its standalone
GraalPy process then aborted during native thread teardown in
`__GI___nptl_deallocate_tsd`, exit 134. This is decoder evidence, not a successful
packaged-runtime qualification. The local report is
`/tmp/issue62-hs_err_pid1987379.log`.

`sky.jobs.download_logs()` fetches a ZIP into a temporary file. In
`sky.client.common.download_logs_from_api_server`, extraction calls
`member_file.read()` without a size before writing each member. This path can
preserve file bytes, but neither its temporary storage nor its per-file memory
use is bounded independently of log size. It downloads whole histories again.

`sky.jobs.download_logs_streaming()` drains one response in a daemon thread and
downloads a second response to a growing file. It obtains that second response
through `/api/stream?format=plain&compress=gz`. The server's
`sky.server.stream_utils.log_streamer` decodes UTF-8 and buffers incomplete lines.
The client ignores dispatch-drain exceptions and returns a local path without
checking the dispatch operation's result. A successful file return alone is
therefore insufficient evidence for a complete terminal fetch. A bounded FIFO
sink could constrain the local file, but would not fix those other properties.

These findings come from the installed package, not an unpinned upstream branch.
No production code or acceptance criteria have been changed for #62.

## Proposed dependency decision

Add a small, versioned patch to the paired SkyPilot client and API-server build
for bounded raw log reads. Keep Java calling the patched Python SDK through
GraalPy, and include the patch identity in both packaged artifacts and their
cache identities. Do not add Java calls to SkyPilot HTTP endpoints.

The patched SDK contract would identify one exact managed job and one stream,
accept an absolute source byte offset and a maximum response size, and return
raw bytes with source identity, the observed end offset and fetch outcome.
Changed/truncated sources and unavailable final bytes must be explicit.
Server reads must use binary files and bounded buffers. Initial limits would be
1 MiB per fetch and a 5-second fetch deadline, with archive finalization allowed
three failed fetch attempts across backend restart. Productive page capture
would not consume that failure budget.

The archive itself would retain immutable chunks addressed by Run identity,
validate attempt markers against durable SDK records, and publish a single
immutable complete-or-partial manifest after terminal reconciliation. Repatriation
would consume the manifest's finalization evidence through the current Storage
Location. Live transport and actual relocation remain in their existing issues.

This preserves the requested byte-fidelity contract but adds ownership of a
SkyPilot patch to the existing paired-deployment responsibility. That ownership
decision remains open; this note is a proposal, not an accepted ADR decision.
