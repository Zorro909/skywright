# Durable Run commands

This implements [ADR 0005](../adr/0005-keep-run-state-provenance-partitioned.md),
[ADR 0021](../adr/0021-stop-runs-when-observed-ceilings-are-reached.md) and
[ADR 0025](../adr/0025-centralize-managed-credentials-in-vault.md).

Run acceptance commits a submission command in the same transaction as its Run Definition,
Dataset lease and credential projection facts. The HTTP owner immediately starts delivery.
New submissions still require live SkyPilot admission; an unavailable source cannot accept
new work. The reconciliation worker resumes commands for already accepted Runs.

Each command has an immutable UUID, Run UUID, kind, acceptance time and evidence. Repeating
an identity with the same Run and content returns its existing receipt. Reusing an identity
for different content, or requesting another command of the same kind for that Run, conflicts.
A separate mutable delivery record holds attempts, progress, projection time and escalation
deadline. The first stop-delivery attempt is retained separately, so an observed cancellation
can satisfy the command after a lost acknowledgement or failed cooperative projection.
This records delivery progress, without claiming a Training Process cause. It contains no SkyPilot operation handle and no derived Run status.

Four workers and a queue of sixteen bound command delivery admission. A two-second sweep
selects at most sixteen due records. A sixty-second database lease admits one worker for a
command; a replacement lease prevents an old worker from recording another worker's result.
Transient failures remain due. These leases coordinate delivery, without scheduling training,
choosing targets or restarting failed project code.

## First dispatch and uncertain handoff

The #56 gate locks the Run and atomically records the first dispatch claim. Every subsequent
submission delivery rediscovers by the Run-derived SkyPilot job name. Missing, delayed or
ambiguous source visibility never grants another launch after that claim. A crash between
claiming dispatch and calling SkyPilot therefore remains uncertain until source evidence can
resolve it. An accepted Run whose first dispatch is still unclaimed can resume its original
credential projections and make that first dispatch after a backend restart.

Accepting a stop command takes the same Run lock. If no dispatch has been claimed, it records
immutable dispatch-prevention evidence. That evidence proves that this Run never started and
allows a cancelled outcome without a fabricated Training Process cause. An ordinary stop
request against a dispatched Run carries no such proof and never advances lifecycle by itself.

## Cancellation and policy stop

`POST /api/v1/runs/{runId}/cancellations` accepts `{ "requestId": "<uuid>" }` and returns
HTTP 202 with a command receipt. `GET /api/v1/runs/{runId}/commands/{commandId}` returns that
receipt and the ordinary source-backed lifecycle view. Delivery progress and observed outcome
are separate fields. A successful forced-cancellation acknowledgement still leaves the
command reconciling until the ordinary read path confirms a terminal outcome.

Cancellation grants thirty seconds from its original acceptance time before forced SkyPilot
cancellation. The worker first projects a cooperative request; unavailable storage does not
extend cancellation's deadline. The managed runtime observes it at a Safe Point using the
existing cancellation path, which creates no cancellation checkpoint. A worker attempts forced
cancellation after the deadline; source outages and bounded call admission can delay remote
execution, and remain visible as uncertainty.

The ceiling evaluator calls `RunCommands.ceilingStop` with a complete `CeilingStopDecision`:
decision identity/time, the accepted ceilings, conditions met, observed exposure, estimate
completeness and source freshness. Delivery validates the ceiling snapshot against the Run
Definition; it does not calculate exposure or decide whether a ceiling is reached. Cost
conditions require a complete estimate. #70 owns evaluation.

A policy stop receives thirty seconds from its first successful Run Store publication.
Create-only publication followed by an exact content/metadata read recovers the original S3
publication timestamp when its local acknowledgement was lost. Restart cannot renew this
grace. The existing policy-stop Safe Point path writes the final checkpoint before reporting
`policy_stopped`. Confirmed completion or failure wins over a stop request; explicit
cancellation retains its existing precedence over policy stop. Receipts distinguish an
observed stop effect from a terminal outcome where no stop was effected.

## Run Store protocol

The backend's own Run Store credential writes one immutable object per stop kind:

- `<project>/<run>/v1/control/cancellation.json`
- `<project>/<run>/v1/control/policy-stop.json`

Each object has schema version 1, Run UUID, pinned Project Version digest, command UUID, kind
and acceptance time. It carries the usual schema, kind, byte-size and SHA-256 metadata and is
published with `If-None-Match: *`. A conflicting existing document fails explicitly.

The managed runtime validates these objects before opening an Execution Attempt or importing
project code. Unavailable or invalid reads refuse admission. A verified pre-existing request
writes immutable `control/startup-refusal.json` and refuses a new Attempt. This refusal alone
does not establish that an earlier writer stopped; lifecycle reads still require terminal
source evidence or an independently finalized process report.

During training, one owned worker polls with one-second connection/read timeouts and no SDK
retries. Safe Point callbacks read memory only. Validated intent stays latched through later
read failures or disappearance. The observer validates bounded size, digest, schema, identity
and timestamp; its requests use the Training Process's existing Run Store credential slot.
The backend uses bounded five-second operations for projection and forced control admission.

## Private GHCR images

Private admission uses the #229 broker and the target-side `runtime_pull.py` helper. The
helper runs in the SkyPilot pod as the SkyPilot OS identity, with the same separately rendered
mode-0400 `SKYWRIGHT_KUBECONFIG` projection. Configure that mount and environment variable on
both the `skypilot-api-server` and `runtime-pull` containers. The kubeconfig must identify one
static-token context, user and cluster with an embedded CA; no ambient in-cluster fallback is
used. Its namespace (or `default`) is pinned in the accepted task and passed as SkyPilot's
Kubernetes namespace override.

The base deployment exposes helper port 46581 only to backend pods through the existing
NetworkPolicy. `skywright.runtime-pull.endpoint` configures this private service endpoint.
An absent helper or unqualified context refuses private admission; public admission does not
need a pull projection. The helper's `/health` endpoint reports process health, while namespace
qualification is checked during private admission.

The broker records the exact binding ID/revision at acceptance and renders a temporary,
owner-only Docker config. Before the first dispatch, the backend sends that material through
the helper channel. The helper creates immutable `skywright-pull-<run>` in the pinned namespace,
with Run ownership and binding identity annotations. An existing Secret must match ownership,
type, immutability and binding revision; repeated installation also checks exact payload.
Neither delivery failures nor lost acknowledgements authorize replacement. A recovered worker
can confirm an already installed Secret without resolving newer registry credentials. If no
Secret exists, it restores the originally recorded revision using the immutable non-secret
binding metadata captured at acceptance. Normal rotation does not require the old revision
to remain configured as current. Vault still checks the exact version, deletion/revocation,
value shape and original expiry; a missing or revoked original never falls back to the new
revision. Legacy projection rows without a metadata snapshot can restore only when their
exact revision remains configured.

The task stores only the Secret name and namespace. Registry material never enters the task
payload, ordinary environment or Training Process. Helper concurrency is capped at four, with
a bounded socket backlog, request body limit and provider timeouts. Responses and logs omit
provider exceptions and credentials. The existing operator release command remains responsible
for deleting a Secret only when every dependent use has ended; losing source visibility is not
release evidence.

Migration 0015 adds the non-secret binding snapshot to new projection records, backfills
submission commands for existing Runs, adds command delivery and
dispatch-prevention tables, and grants runtime updates only on the delivery table. Rollback
removes these tables; quiesce delivery before rolling back to software without this protocol.

Tests: `RunCommandsIT`, `LocalRunAssemblyIT`, `RunStoreLifecycleTest`,
`RunLifecycleDerivationTest`, `GraalPySkyPilotClientIT`, the helper HTTP tests and the installed
managed-runtime system test cover command recovery, first-dispatch prevention, immutable pull
delivery, cancellation without a checkpoint, policy-stop finalization and refused restart.
