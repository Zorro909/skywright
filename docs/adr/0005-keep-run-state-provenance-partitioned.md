---
status: accepted
---

# Keep run state provenance-partitioned and derive lifecycle at read time

Skywright keeps a durable Run Record in its own database while SkyPilot remains the live source of everything it actually owns. Q1 forbids an authoritative second copy of data for which a source exists; it does not forbid Skywright originating data. Run state is therefore sorted by provenance rather than assigned a single owner, and a storage unit holds exactly one provenance: Skywright-originated facts and SkyPilot-sourced facts never share a table and are joined only by the Skywright-owned run identity. The lifecycle states R3 names are never stored at all — they are derived at read time from raw facts.

The database is the durable truth for what Skywright originates. The backend writes nothing to a filesystem of its own; the Run Store is written by the training process through the Run Context, and the backend holds only references into it. Q6 scopes to the backend service, which is stateless on disk and replaceable at any time; database durability is a deployment concern.

## Retention extension

SkyPilot purges data by retention policy rather than by ceasing to be a source: managed-job logs at 7 days, job events at 30, and finished-job status on controller teardown. For a narrow covered set, Skywright extends the lifetime of that data in an append-only table that remains SkyPilot-provenance. On every covered fetch, Skywright queries SkyPilot, appends what is new, and supplements the response with stored rows SkyPilot has already purged. Callers see one merged answer and never branch on where a row came from.

A fact is covered only if a named requirement reads it after the retention window, it is immutable once written, and no other durable source already holds it. Under that test the covered set is the terminal outcome and lifecycle timestamps (R3 beyond the controller's life, U7, O6), the actually-resolved infrastructure (O6, U8, and the basis for K2), recovery and preemption events (R6, O5), and failure context (O5, U10). Job logs are excluded as bulky and belonging in the Run Store; cost history is excluded because the price catalog cannot authoritatively satisfy K2. Both are separate decisions rather than silent inclusions here.

- Every covered fact is stored in one uniform event shape with the natural key of run identity, fact kind, and the source's own event identity — never Skywright's fetch time, which would duplicate on every poll.
- Rows are insert-if-absent. The merge path never updates or deletes; deletion is only ever intentional and explicit.
- The source wins while it answers. Stored rows supplement only what SkyPilot no longer returns, so the stored copy is never authoritative while a source exists.
- A conflicting payload for a key already held yields one authoritative value — the latest by Skywright's fetch time, since SkyPilot offers no revision ordering — with superseded and conflicting rows exposed on a separate channel. Consumers are never required to handle conflict and are always able to surface it.
- Appends happen on every covered fetch, not only at terminal, because recovery events expire while a long run is still in flight.

Liveness comes from reads: every user-facing read and every internal function that needs run state fetches SkyPilot live. A background reconciler exists only so that a run nobody looks at does not fall out of a retention window, which makes its cadence a function of the shortest covered window rather than of freshness. Polling daily is generous against a 7-to-30-day horizon; polling every minute would be waste. The reconciler's poll set is every run not yet terminal-latched, derived from the database, so it rebuilds itself after any backend restart.

## Deriving lifecycle state

The retained table holds what SkyPilot actually said. R3's five states — waiting, running, interrupted, finished, failed — are a pure function over those raw facts and the Run Definition, computed per read and never persisted. A derived state is neither SkyPilot-sourced nor Skywright-originated, so storing it would put an interpretation into a table that holds source facts. Because SkyPilot classifies from cluster health rather than cause, the mapping will be wrong somewhere; deriving means a corrected mapping corrects every historical run at once.

R6's behaviour belongs to SkyPilot, not to Skywright: automatic resume on preemption is SkyPilot's recovery logic, so Skywright needs the distinction only to report it. SkyPilot's own recovery behaviour is therefore the classifier — a recovered run derives as interrupted, a terminal run that was not recovered derives as failed. That inference holds only while user-error restarts stay off, so max_restarts_on_errors remains 0 and recover_on_exit_codes remains unset; the restart policy lives in the Run Definition and the derivation consults it rather than assuming it.

SkyPilot cannot supply a cause, since one operation exit code covers every failed terminal status. The Run Termination Report supplies it: the training process writes its own terminal record to the Run Store, which is durable by construction, has no retention window, and needs no channel back to the backend. Q1 forbids copying it into the database, so the derivation reads through to it.

## Correlation, submission, and availability

The SkyPilot job is named from the run identity fixed at Run Record creation, and nothing SkyPilot returns is ever stored. Managed-job IDs are database-scoped and renumber when a controller is rebuilt; a derived name does not. Correlation is a pure function of a Skywright-originated fact, submission is idempotent on that name, and a job whose name does not parse as a run identity is not Skywright's — which lets the reconciler ignore foreign jobs on a shared controller without bookkeeping.

Skywright operates no queue of its own. A Run Record is submitted to SkyPilot at creation and every real wait is SkyPilot's, consistent with excluding a scheduler beside SkyPilot from scope. The only Skywright-originated lifecycle fact is the submission attempt and its time, which is what the derivation reads before SkyPilot knows the job. A capacity shortfall surfaces as an explicit SkyPilot failure rather than a run parked in waiting, which is what T6 requires.

Availability is orthogonal to lifecycle state rather than a sixth value, so consumers never switch over a mix of what a run is doing and whether it could be asked. A terminal-latched run answers completely from the retained table with SkyPilot entirely down, and immutable facts have no staleness to disclose under Q2. Only in-flight runs degrade: their authoritative answer is source-unavailable with no lifecycle state, satisfying Q3, while the last seen value and its age travel on the same side channel used for conflicts, so U12 keeps the interface usable. Because no derived state is stored, there is no local state a control action could advance, and Q4 is satisfied structurally: cancellation forwards to SkyPilot and the result is read back.

## Consequences

Losing the database loses run history. The Run Store still holds checkpoints and artifacts, but those bytes are indexed only by the database; this is mitigated operationally rather than architecturally.

Historical interpretation is not frozen. A run that reported failed may report interrupted after a mapping fix, which is correct but means cross-run comparison under O6 rests on interpretation rather than on a stored verdict.

A hard crash that prevents the training process from writing a Run Termination Report — an out-of-memory kill, a segmentation fault, a fatal accelerator error — is indistinguishable from preemption when it also takes down the node, so a genuinely broken run is recovered repeatedly. SkyPilot documents no overall attempt cap for infrastructure recovery, so only a Skywright-imposed limit closes this, which remains a downstream decision.

Controller teardown is an event rather than a clock, so no reconciler cadence anticipates it; not tearing down a controller with unlatched runs is operational discipline. Submission requires SkyPilot to be reachable, so a run can never be created as queued for later. Database technology, poll cadence, log and cost persistence, and resume-attempt limits remain downstream decisions.
