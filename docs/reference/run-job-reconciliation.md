# Run job reconciliation

`RunJobAdapter` consumes the typed `Orchestrator` port. The Run acceptance and command-delivery owners supply the immutable task, credentials, a durable `LaunchDispatchGate`, and a `RetainedSkyPilotFacts` sink. This module supplies no in-memory production substitute for either durable port. The database schema and lifecycle reducer remain with their owning milestones; #65 implements command delivery.

## Dispatch and rediscovery

The job name is `skywright-<Run UUID>`. Before invoking submission, the gate atomically commits a first-dispatch claim keyed by Run UUID and a canonical task digest. Only that first caller may launch. Different task content for the same Run is a conflict. Credentials are delivered separately and do not enter the digest.

A duplicate delivery observes the deterministic name through a fresh SkyPilot operation. It never calls launch. This remains true after a lost response, backend restart, expired request ID, API-server outage, or an empty lookup. SkyPilot's name lookup is not atomic deduplication. The low-level client's existing request/name reuse is only an optimization within an authorized dispatch, not the correctness mechanism.

There is an unavoidable gap between committing a dispatch claim and sending the remote request. A crash there can leave a Run unlaunched. The adapter exposes missing evidence and sacrifices automatic retry rather than risk two writers. An empty queue is insufficient evidence to release the claim. Command delivery must not expire that claim or use transient Orchestrator Operation IDs as durable evidence.

Submission returns when the source accepts an operation, before provisioning finishes. Operation completion exposes source failures and never selects replacement resources or launches another job. Source capacity and eligibility errors remain explicit. Unknown source errors retain their source category without an invented cause.

## Evidence

Submission completion requires Run correlation and durably retains source operation failures before acknowledging retention, including failures before a job row exists. The one authorized first dispatch supplies the stable event identity. Neither a transient request ID nor poll time enters that key. An expired request's client error is retained as an operation observation with unknown job outcome, not invented terminal job evidence. Retention failure remains explicit.

Status reads request at most 1,000 source records. A truncated response is incomplete, not proof of absence or uniqueness. Multiple jobs with the same name are ambiguous. Missing source identifiers and recovery counts remain explicit gaps.

The adapter emits source submission, execution-start, infrastructure, latest recovery and termination facts. Incomplete or ambiguous lookups still retain facts from each returned record while preserving their availability qualification. Keys use the Run, fact kind, source job/task identifiers, source run timestamp and source event timestamp. Poll time records when evidence was observed and never supplies event identity. The sink acknowledges durable retention; it must insert absent keys and retain conflicting source payloads with their observation times as ADR 0005 requires. A failed sink produces `RETENTION_UNAVAILABLE`.

SkyPilot job IDs are database-scoped. The SDK does not expose a durable database epoch, so every observation carries that limitation. Missing task identifiers, source run timestamps or event timestamps prevent emission of the corresponding fact. A recovery count greater than one cannot reconstruct unobserved earlier recoveries. Source cluster health and recovery count do not establish preemption cause.

`RunStoreAccess` reads bounded Execution Attempt records with digest, owning Run and attempt identity checks. Correlation links a verified attempt to the Run's job name. It does not equate an SDK attempt UUID with a SkyPilot recovery generation. Live job status is returned from the source, not persisted as a second lifecycle authority.

Cancellation and cleanup completion acknowledge that the source request completed. They do not prove the worker stopped or that cleanup won a race. Cleanup requires an observed source cluster name. Subsequent reconciliation reports what SkyPilot currently knows. `previousWriter` exposes uncertainty because neither a missing job nor a terminal controller status proves termination of the exact SDK writer or revocation of its storage access. The recovery-admission verifier from #52 therefore continues to fail closed unless its owner supplies stronger evidence.

## Qualification

`RunJobAdapterTest` exercises concurrent in-flight delivery, lost responses, both sides of the dispatch crash gap, delayed visibility, restart rediscovery of active and terminal jobs, expired requests, ambiguous and incomplete results, recovery and application-failure facts, source loss, retention failure and truthful control acknowledgements. `RunStoreAccessTest` verifies bounded attempt reads and corruption/identity rejection.

`GraalPySkyPilotClientIT` checks decoding with pinned SDK record types and an expired request against the real API server. `PackagedHeldSkyPilotIT` retains the #214 qualification for held SDK calls, bounded saturation, cancellation, probes, API-server loss and shutdown. These tests do not provision paid cloud capacity or claim to prove a real worker's termination.
