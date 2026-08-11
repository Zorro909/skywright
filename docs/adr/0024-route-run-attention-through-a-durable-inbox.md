---
status: accepted
---

# Route Run attention through a durable inbox

Skywright must tell a user both that a Run reached a terminal outcome and that non-lifecycle work around it needs intervention, without confusing a message with the condition it describes. Skywright therefore owns a durable **Attention Inbox** in its database. Immutable **Run Notices** record occurrences worth learning about, durable **Attention Items** represent unresolved condition episodes, and optional push delivery is only a non-authoritative projection of those records. This rejects channel-first notification: losing email, chat, or a webhook can lose immediacy but never the canonical attention history.

## Notices and actionable conditions

A Run Notice records a typed observation, occurrence and observation times, and a reference to its authoritative evidence. It does not copy that source into a new authority. Every newly observed terminal transition—`finished`, `failed`, or `cancelled`—creates one Notice. `failed` also opens an Attention Item; `finished` and `cancelled` are informational unless a separate actionable condition exists. Recovery exhaustion is a failed-Run cause rather than a duplicate item, and an effected ceiling stop remains the expected `cancelled` outcome rather than an incident of its own.

Every Attention Item names one typed condition episode. Reconciliation reobserves and updates that item instead of multiplying it. When a condition clears and later recurs, the recurrence opens a linked new item rather than reopening history. The initial actionable catalogue is:

- a failed Run;
- Repatriation that exhausted automatic retries, leaving its Run Store in the execution Target Storage;
- a terminal Run Log Archive finalized as partial;
- a Run Deletion Operation that exhausted retries while the Run remains fenced and partly removed;
- a configured Runtime or Cost Ceiling that remains unenforceable while its active Run remains exposed;
- persistent loss of the SkyPilot control path for an active cloud Run, leaving execution unmanaged;
- persistent Run Store unavailability when it blocks durable writes, Policy Stop delivery, or post-hoc access;
- conflicting Retained SkyPilot Facts that materially impair one Run's lifecycle or failure interpretation; and
- persistent unavailability of a retained Run's pinned metric-contract artifacts, preventing semantic interpretation of its metrics.

Ordinary retry and freshness windows elapse before a persistent condition opens an item; their exact durations are operational tuning. Brief source loss remains **Capability Availability**, not attention. Also excluded are an incomplete Run Cost Estimate when no ceiling depends on it, accepted bounded metric-tail loss, a missing Execution Termination Report already represented by terminal failure, resumable Transfer Worker loss, pre-Run validation failures, and Attention Delivery failure itself.

An item resolves automatically when its underlying recoverable condition clears. Automatic resolution emits an informational Run Notice. A permanent condition—the failed Run, partial log archive, or retained-fact conflict—requires an explicit **Attention Disposition** of remediated, accepted, or no action. An **Attention Acknowledgement** is per Principal Identity and means only that the item was seen: it stops that identity's reminders but neither resolves nor changes the condition. Informational Notices carry no semantic acknowledgement.

Notices are append-only observations rather than frozen claims about derived Run Lifecycle State. If later evidence or a corrected interpretation invalidates an earlier Notice, Skywright appends a corrective Notice referencing it and resolves the associated item when appropriate. It never rewrites history that may already have been delivered or acknowledged.

## Inbox, routing, and delivery

The first deployment's built-in Principal Identity receives the complete Attention Inbox. A future access layer may select additional recipients without changing the model; each recipient owns its inbox projection, acknowledgement state, and mutable **Attention Routing Policy**. Routing is independent of Run Definitions. Skywright evaluates the policy when it records an occurrence, resolving named Attention Channels and any unacknowledged-item reminder behavior into durable delivery intentions. Later policy changes affect later occurrences, not work already created. With no configured channel, the inbox alone remains complete.

Attention Channels are outbound-only. They accept a minimal envelope containing the Run identity and name, occurrence or condition type, relevant timestamps, a non-sensitive summary, and a link to the authoritative inbox. Logs, stack traces, Run Configuration, presigned URLs, credentials, and artifact content do not leave through attention delivery. A channel never supplies acknowledgement, disposition, retry, or Run-control commands and never speaks as a Principal Identity.

For each observation, one database transaction idempotently records or finds its Run Notice, opens or updates its Attention Item, and writes the routing-derived **Attention Deliveries**. Only committed deliveries enter the outbound worker. Stable authoritative evidence identities make reconciliation replay idempotent even when Run Store reads cannot join the database transaction. If persistence fails, Skywright pushes nothing and retries reconciliation later; a push can therefore never exist without its canonical inbox record.

Delivery is at least once under a stable identity, not exactly once. Success means only that the channel accepted the envelope, never that a human received or read it, and downstream duplicates remain possible. Transient failures retry with bounded backoff; exhaustion leaves a durable failed outcome available for manual retry and visible in the inbox, but creates no recursive Notice or Attention Item. Individual transport attempts and provider responses are bounded operational history, while the delivery intention and aggregate outcome remain durable.

Opening an item triggers its configured deliveries. Unchanged reobservation triggers none. A policy may remind while the recipient has not acknowledged the item; acknowledgement or resolution stops those reminders. Related deliveries are causally ordered per Principal Identity, Attention Channel, and Attention Item: opening precedes reminders and reminders precede automatic resolution, with later work waiting only until its predecessor succeeds or exhausts retries. Once a later occurrence has been delivered, manual retry of its superseded predecessor is refused. Channels and unrelated items proceed independently.

## Availability, retention, and deletion

No inbox or push capability exists while the Spring backend or its database is unavailable, and Skywright cannot reliably announce its own total outage from inside that outage. External control-plane monitoring remains deployment work. After recovery, reconciliation creates missed records from surviving durable evidence, preserving separate occurrence and observation times. If the outage outlived every authoritative source and retained copy, Skywright exposes the evidence gap and never manufactures the missing history.

Run Notices, Attention Items, acknowledgements, dispositions, delivery intentions, and aggregate outcomes are Skywright-originated database state retained for the Run Record's lifetime. Successful Run deletion is the deliberate exception to normal resolution: ADR 0022 requires the deletion transaction to remove every Run-indexed record and leave only the minimal Run Deletion Receipt. An exhausted deletion therefore keeps its Attention Item while intervention remains possible, but successful completion removes it and creates no durable or guaranteed completion Notice or push.
