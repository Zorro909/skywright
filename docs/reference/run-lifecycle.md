# Run lifecycle reads

GET `/api/v1/runs/{id}`, GET `/api/v1/runs`, submission replays and internal
`RunLifecycleReads` callers use the same read-through derivation. Each observation
fetches SkyPilot through the Run Job adapter, retains covered source facts, and
reads Execution Attempt, Termination and Recovery Exhaustion records from the
Run Store. No lifecycle column or persistent interpreted status exists.

## Evidence and precedence

The accepted Run Definition supplies the project identity, pinned version and
recovery policy used to validate process evidence. Its accepted submission is the
initial waiting intent. A failed submission operation remains visible source
evidence: the operation's failure alone does not prove the managed job's outcome.

The reducer applies these rules in order:

| Evidence | Lifecycle |
| --- | --- |
| Unavailable or invalid Run Store evidence | No authoritative state |
| Valid Recovery Exhaustion Record | failed, without an invented process cause |
| Latest report: completed | finished |
| Latest report: cancelled or policy_stopped | cancelled |
| Latest report: contract_violation, training_project_failure or skywright_failure | failed |
| One complete live SkyPilot job: PENDING, SUBMITTED or STARTING | waiting |
| Live RUNNING | running; interrupted if the latest admitted attempt has already finalized interruption |
| Live RECOVERING | interrupted |
| Live CANCELLING | running if execution started, otherwise waiting |
| Live SUCCEEDED, CANCELLED or FAILED family | finished, cancelled or failed respectively |
| SkyPilot missing/unreachable with a retained terminal outcome | Derive from that immutable terminal fact |
| SkyPilot missing, no admitted process and no terminal fact | waiting, with uncertain handoff disclosed |
| In-flight SkyPilot unreachable, incomplete, ambiguous or unknown status | No authoritative state |

A durable terminal process report can precede SkyPilot's exit observation.
Cancellation and ceiling-stop requests alone never advance lifecycle. A report
from an earlier attempt cannot override a later admission: the hash-linked
recovery journal supplies attempt order, independent of UUID and fetch time.
A missing report never proves preemption, cancellation or a project failure.
A process cause and an orchestrator outcome remain separate facts.

`RunControlDecisions` is the read seam for the durable decisions owned by #65.
It currently returns no decisions when no control implementation is installed.
The response carries those decisions beside lifecycle; the reducer does not
turn a request into proof that execution stopped.

## Provenance, conflict and freshness

Skywright's accepted intent, current storage pointer, SkyPilot fact payloads and
SkyPilot observations occupy separate tables. The adapter retains only its
covered facts; it does not store a second mutable job-status snapshot.

A source event's natural identity is Run + kind + source event identity.
Distinct payload digests preserve conflicting versions of that event, while
observation rows preserve repeated sightings of the same payload and whether
each lookup was complete and matched exactly one job. Legacy observations lack
that qualification and cannot latch retention until refreshed. The latest
observation selects a retained variant. Equal observation timestamps break ties
by preferring an unqualified sighting conservatively, then canonical JSON payload
with sorted keys and source event identity.
Every currently returned live fact wins over its retained variants. Conflicting
alternatives remain available on the side channel.

`sourceAvailability`, `processAvailability`, evidence gaps and conflicts are
separate from the six lifecycle values. `skyPilotReadAt` and `runStoreReadAt`
mark the start of each source read; `fetchedAt` marks completion of the combined
observation. These timestamps expose the read interval without treating the
later S3 read as a fresh SkyPilot observation.

If an in-flight read cannot produce an authoritative state, `lastSeen` can show
the previous state, observation time and age. This optional display cache holds
at most 1,024 Runs and is lost on restart. It never supplies authoritative state.
Normal reads always fetch live; #236 remains a proposal.

## Run Store and retention

The reader checks object kind, schema, size, digest, Run/version identity, journal
links, admission debt, previous-writer evidence, checkpoint references and report
finalization requirements. It rereads the journal head to reject a changing
history. A nonempty namespace without a journal head is invalid. Storage access
errors are not mistaken for an absent object.

The journal retains publication evidence after checkpoint payload pruning.
Lifecycle reads therefore download control records, never checkpoint tensors.
The current Run Store location is a separate database reference, initially set
atomically with acceptance, including the complete pinned storage descriptor.
Later endpoint/configuration promotions do not redirect existing Runs.
Reads follow the current location and continue to work when an already
qualified storage is deactivated for new placements. A referenced storage cannot
be deleted. Upgrade preserves historical orphaned pointers with their original
descriptor. Their missing registration remains an explicit read failure. The
foreign key enforces new writes without rejecting those existing orphans.
The verified move protocol and pointer switch belong to #53.

A dedicated daily reconciler scans accepted Run IDs in bounded pages, rebuilding
its work from durable facts on every sweep and after restart. It fetches Runs
whose SkyPilot terminal outcome has not been retained, including processes that
already wrote a terminal report. A terminal outcome completes the SkyPilot
retention obligation only when the
latest retained observation was complete and unambiguous. A later nonterminal or
ambiguous observation prevents that latch. The reconciler uses the same
conflict-selection rules as lifecycle reads. Each covered fetch also captures
intermediate
recovery and infrastructure facts. The first sweep starts one minute after
startup; the subsequent 24-hour delay follows ADR 0005's retention horizon.
It does not implement freshness polling. Operators must retain controllers until
their terminal outcomes are captured: no timed sweep can anticipate teardown.

Run Store reads allow five seconds per storage request and a 30-second overall
operation budget, 64 MiB of control-record bytes, and a journal of at most
16 MiB/100,000 records. Retained SkyPilot reads cap payloads at 16 MiB of
characters and 100,000 variants. Exceeding a bound reports unavailable/invalid
evidence rather than silently deriving from a truncated history.

List reads accept 1–50 Runs and use an exclusive UUID cursor. Between Runs they
stop after 30 seconds or 2 MiB of selected source-fact characters, returning a
continuation cursor; an already-started Run finishes within its own bounds.
A list page can therefore contain fewer items than requested without skipping
Runs. The same per-Run integrity and live-read rules apply to every item.

## Verification

`RunLifecycleDerivationTest` covers the six-state truth table, races, conflicting
observations, requests, missing reports and live-source loss.
`RunStoreLifecycleTest` rejects malformed, oversized, foreign or corrupt
evidence. `RunLifecycleIT` executes the actual Python runtime against S3 and
derives its records in Java with PostgreSQL retention, recovery, exhaustion,
payload pruning, restart, configuration promotion, location changes and source outage.
`GraalPySkyPilotClientIT` passes real packaged SDK status decoding through the
Run Job adapter into the reducer.
