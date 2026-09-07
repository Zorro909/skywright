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

## Unmodified-SDK follow-up

A second source review found no supported binary-range or bounded binary-sink
log API in the pinned SDK. `tail_offset` skips lines from the end of the current
file. Even `tail=1` permits an arbitrarily large line, so it cannot supply a
stable byte cursor or a memory bound.

The download path has a further fidelity limit. Controller download uses rsync,
but task download runs `ManagedJobCodeGen.stream_logs(follow=False)` and saves
its text output. That source can hide content before SkyPilot's start marker
and decode task files before the downloader receives them.

SkyPilot has supported plugin hooks for server routes and node logging agents.
A plugin route serving API-server files alone is insufficient: consolidation-mode
controller logs are local, while active task logs live on execution clusters.
The controller downloads task snapshots during terminal cleanup, after recording
terminal status. Those snapshots do not provide incremental active capture or
independent proof of successful final capture.

The installed source locations supporting these findings are relative to
`.graalpy/resources/venv/lib/python3.12/site-packages/sky/`:

| Source | Relevant behavior |
| --- | --- |
| `jobs/client/sdk.py:473`, `:622`, `:747` | The three managed-log SDK paths and their parameters |
| `utils/rich_utils.py:325` | Text decoding, unfinished-line buffering and CRLF rewriting |
| `skylet/log_lib.py:500` | Reverse line-tail accumulation without a byte cap |
| `client/common.py:67` | Whole ZIP download and unbounded member extraction |
| `backends/cloud_vm_ray_backend.py:5167` | Controller rsync versus task text-stream download |
| `server/plugins.py:39` | Supported server plugin contexts and route registration |
| `logs/agent.py:13` | Supported node logging-agent hook |
| `jobs/controller.py:838` | Terminal state precedes best-effort task-log download |
| `jobs/controller.py:2395` | Per-job consolidation-mode controller file |
| `jobs/utils.py:1892` | Active task logs read from the execution cluster |

The owner approved a separate Skywright-owned, read-only collector on 2026-09-07.
ADR 0009 and ADR 0018 now record that exception. The implementation and its
qualification are described in [Run log archive](../reference/run-log-archive.md).
It leaves the SkyPilot distribution unchanged and uses bounded file reads through
Kubernetes exec for active task logs and a read-only controller volume for retained
logs. Unconfirmed source generations produce partial archives.

The pinned source revealed two further constraints. `clusters.node_names` stores
Kubernetes node hostnames, so the collector must find the head pod using the exact
cluster/head labels and verify its logical cluster annotation. Importing SkyPilot's
otherwise pure naming helper initializes `.sky/locks`; the collector instead owns
the small deterministic naming protocol and tests equivalence against the packaged
SDK. Terminal `local_log_file` paths do not retain pod UID, so matching prefixes do
not establish generation identity. Copies of uncertain origin remain separate
source generations with an explicit partial reason.
