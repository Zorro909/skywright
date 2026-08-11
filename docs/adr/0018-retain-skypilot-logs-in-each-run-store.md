---
status: accepted
---

# Retain SkyPilot logs in each Run Store

Every Run retains both SkyPilot task logs and its controller log in a **Run Log Archive** inside that Run's Run Store. The archive lives until the Run Record is deleted, which also deletes the Run Store and its archive. This gives `U3`, `O5`, and `U10` a durable post-hoc source despite SkyPilot's default seven-day log retention and the loss of controller-local logs on teardown.

## Capture and addressing

The backend's existing SkyPilot reconciliation path writes the archive. It reads both SkyPilot log surfaces through the pinned SDK integration and writes raw new bytes as immutable chunks in a SkyPilot-provenance partition of the Run Store. Capture is incremental during ordinary reconciliation, so a long-running or abruptly orphaned Run does not depend on a terminal-only fetch; the exact cadence is operational tuning. Numeric SkyPilot job identifiers are correlation evidence only—the Run identity addresses the archive.

SkyPilot exposes task output as one aggregate across setup and recoveries, but the useful reading unit is an Execution Attempt. After its Execution Attempt Record is durable and before project code runs, the library emits a schema-versioned boundary marker carrying that attempt's identity. The backend accepts a marker only when it names an existing record and uses it to index the following training output to that Execution Attempt.

Output before a marker forms an ordered **Setup Log Segment**. When an Execution Attempt starts, its preceding segment is linked to it as preparation but never becomes part of the attempt; setup that fails before an attempt exists remains an unlinked Run-level segment. The controller log remains one Run-level stream. The immutable raw chunks remain the fidelity source beneath these indexes, so splitting never rewrites or discards SkyPilot output.

## Finalization, authority, and incomplete capture

SkyPilot is authoritative while a Run is active and archive capture is staging. Once SkyPilot reports the Run terminal, reconciliation makes a final fetch and publishes an immutable manifest. Publication hands post-hoc authority to the Run Log Archive even while SkyPilot temporarily retains its copy, so `Q1` never leaves two authoritative sources and completed-log reads never vary with SkyPilot's later availability.

Each stream is finalized as `complete` or `partial`. Complete means the archive contains every byte SkyPilot made available through the terminal fetch; it cannot promise bytes an abruptly lost process or node never delivered to SkyPilot. A partial entry carries a machine-readable reason such as source loss or an unverifiable boundary. Finalization retries under a bounded policy. Exhausting it publishes the partial manifest, leaves the Run's actual lifecycle unchanged, and allows Repatriation to continue rather than pinning an expensive execution Target Storage indefinitely. Repatriation waits for manifest publication and then moves the archive with the rest of the Run Store.

## Consequences

This refines ADR 0005: retained logs remain SkyPilot-provenance, but their size keeps them out of the Retained SkyPilot Fact table, and the backend is now an additional Run Store writer for this partition. It extends ADR 0008's Run Store contents and corrects ADR 0009's premature statement that ADR 0005 had already placed logs there.

SkyPilot's external logging is not the source: in the pinned version it targets only AWS CloudWatch Logs or GCP Cloud Logging, does not provide an end-to-end reader for both managed-task and consolidation-mode controller logs, and has no abrupt-loss flush guarantee. A training-process-only writer is also insufficient because it cannot capture controller output or reliably flush after abrupt death.

Live-log transport remains a separate decision. Exact ordering and recovery when deleting a Run Record and its Run Store are also downstream; this decision fixes the ownership cascade, not its deletion protocol.
