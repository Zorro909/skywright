---
status: accepted
---

# Tail the Run Log Archive for live logs

A Live Log View reads only the staging Run Log Archive; it never holds a stream from SkyPilot. SkyPilot remains authoritative while the Run is active, the backend incrementally reconciles its output into the archive, and publication of the terminal manifest hands authority to that archive as fixed by ADR 0018. This gives running and completed logs one read path without making the staging copy authoritative.

## Demand-driven reconciliation

The browser follows the archive through a server-sent events session with the backend. Active sessions create an ephemeral, per-Run demand signal: the first viewer raises log-reconciliation frequency, concurrent viewers share that work, and reconciliation returns to its ordinary archival cadence after the last viewer disconnects and a grace period expires. No viewer state is persisted, and losing the backend loses only the live view rather than any captured bytes.

Reconciliation polls SkyPilot; it does not hold a SkyPilot log stream. Log work is bounded and subordinate to control operations, preserving ADR 0009's rule that long-held work cannot block the shared control path. Exact polling frequency and disconnect grace are operational tuning.

## Stream and cursor semantics

Task output and controller output remain separate ordered terminal-byte streams because SkyPilot supplies no trustworthy common ordering between them. The task stream preserves its complete chronology while the Run Log Archive's indexes make Setup Log Segments and Execution Attempts individually navigable. Skywright does not invent log records, timestamps, or line boundaries: the UI interprets ANSI control sequences and carriage-return rewrites with a terminal renderer.

Each stream has an Archive Cursor: a stable absolute byte position independent of storage chunks and of the Run Store's current Storage Location. Server-sent events carry byte ranges, so reconnect replay is harmless and the client resumes from its last cursor. A new view defaults to a bounded tail and pages older bytes on demand; the exact initial tail size is operational tuning. The cursor remains valid across chunk rollover, backend restart, Repatriation, and terminal-manifest publication.

## Preemption, availability, and completion

Preemption does not end a Live Log View. Already captured bytes remain visible, and following resumes on the same task stream as later Setup Log Segments and Execution Attempts arrive. When SkyPilot cannot be reached, the view keeps captured bytes visible together with the last successful reconciliation time and an explicit live-source-unavailable condition. When the Run Store cannot be reached, logs are unavailable; the backend never falls back to a direct SkyPilot stream.

Once the terminal manifest is published, the live session reports each stream as complete or partial and ends. This is the same authority handoff used for every post-hoc read, so completed-log behavior does not depend on whether SkyPilot still retains its copy.

## Consequences

Live-log latency is bounded by demand-adjusted reconciliation and object-storage visibility rather than by a direct connection to the training instance. Viewing logs increases SkyPilot polling and Run Store traffic, but multiple viewers of one Run are coalesced. A source outage can leave an explicitly stale but useful view; it can never silently switch the user to a second read path with different parsing or loss behavior.
