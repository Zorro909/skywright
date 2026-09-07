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

## Owner decision, 2026-09-07

The owner rejected direct modifications to the SkyPilot server or client SDK.
The proposed paired raw-log patch is withdrawn. Both dependencies must remain
unchanged, and runtime monkey-patching is not an alternative to that constraint.
Issue #62 and ADR 0009 record the decision.

The byte-fidelity, bounded capture and honest terminal-finalization requirements
remain canonical. A supported unmodified-SDK path still needs qualification;
these findings do not authorize relaxing the archive contract or bypassing the
SDK through another transport.
