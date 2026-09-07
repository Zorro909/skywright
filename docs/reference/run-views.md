# Run overview and detail reads

The overview reads `/api/v1/runs` in pages of at most ten Runs and follows the
server's cursor. Search, lifecycle filtering and ordering apply to the displayed
page and are labelled accordingly. First/next navigation replaces the page;
refresh failures retain the preceding response as historical information.

`/runs/{runId}` displays the accepted identity, immutable definition and the
backend's lifecycle observation. It never derives lifecycle from control intent,
progress, a source status or an absent report. Every observation carries its
read time and age. Source availability, last-seen lifecycle and retained-fact
conflicts remain separate from the observed lifecycle. Terminal-latched evidence
is readable during SkyPilot outages. An invalid optional lifecycle response does
not discard the accepted identity.

Execution Attempt counts come from verified Run Store history. SkyPilot recovery
counts and setup/wait observations come only from a complete, uniquely correlated
live response. Runtime is explicitly an execution span from the source's start to
its end or this read; it includes recovery waits, and is neither active compute
nor billed duration. Missing source fields are unavailable, not zero. Retained
source timestamps remain inspectable in the detail evidence even when there is
no current runtime observation.

Progress has a separate read and refresh operation,
`GET /api/v1/runs/{runId}/progress`. It resolves the current registered Run Store
location and reads only `v1/progress.json`, capped at 16 KiB. S3 calls have a
five-second request deadline. The read validates the content digest, record
schema, Run identity and checkpoint/Step consistency. Missing, invalid and
unavailable are distinct outcomes; none implies Step zero. The record's own
write time is displayed. Committed Step and Durable Safe Point remain separate;
no percentage appears without a positive target Step. A failed refresh can
retain the previous record only as last-seen information.

List requests have a 70-second browser timeout, detail requests 40 seconds, and
progress requests ten seconds. Navigation aborts obsolete requests; completion
of an obsolete request cannot overwrite the new route. Browser lifetime owns no
control-plane work. Refresh is explicit and does not trigger control actions.

Log, Metric View and Run cost readers are not yet available in these views.
Each section states that limitation independently. Preservation and Attention
Item sections likewise state that their readers are unavailable; they do not
claim an empty inbox or successful preservation. Their owning features still
need to supply the corresponding source-backed reads.
