# Local Run acceptance

`POST /api/v1/runs` accepts basic local AMD submissions. The caller supplies a stable UUID `submissionId`, a registered Training Project and immutable version manifest digest, a Dataset Definition UUID, a qualified target identity, a GPU count and configuration. Optional fields select a Dataset Copy, execution storage and maximum Recovery Debt. An optional exact checkpoint seed supports the local lineage path described below. Full editable cloning and ceilings remain with #58.

Before acceptance, the backend probes SkyPilot and resolves the project, digest-pinned ROCm image, both contracts, configuration defaults, Dataset identity, qualified storage, local target and finite recovery policy. The acceptance transaction commits the immutable Run Record, Dataset lease and non-secret credential projection facts together. A failed transaction leaves no accepted Run or Training Process projection/lease from that attempt. Seed preparation has separate durable ownership before this transaction, as described below. Acceptance locks the Training Project until its artifact references commit; registry rebinding takes the same lock before scanning references and promoting a replacement, so it cannot omit a concurrent accepted Run. Registry outages return 503 with the resolver's structured details. Storage credential isolation failures return 422 before acceptance.

The API returns 202 with the Run UUID and `acceptedIntent: submit`. `handoff` separately reports `source-accepted`, `source-observed` or `uncertain`; these values are observations, not stored lifecycle states. `GET /api/v1/runs/{runId}` and submission replays fetch SkyPilot again. Source availability and evidence gaps remain separate from accepted intent.

## Replays and crash windows

The database enforces uniqueness on the built-in Principal Identity and submission UUID. A canonical digest covers the mapped input, including explicit configuration. Replaying the same input returns the same Run without repeating resolution or changing pinned inputs. Reusing its identity with different input returns 409.

After commit, the backend immediately asks `RunJobAdapter` to submit. A database transaction locks the accepted Run and records its first-dispatch claim before any remote launch. The claim must match the accepted task's canonical fingerprint. Concurrent workers cannot both receive authorization. Transient request IDs never enter the database.

A lost response, a crash after acceptance, or an expired operation can leave handoff uncertain. Replays use observation only, even while a lookup is empty. The durable command reconciler can recover the original projections and make a still-unclaimed first dispatch. Once the first-dispatch claim exists, even a missing job stays uncertain and cannot trigger another launch. See [Run commands](run-commands.md) for delivery and cancellation recovery.

A source outage before admission returns 503 without creating a Run. A late acknowledgement still consumes operation completion for retention after the HTTP wait expires. An outage after commit cannot revoke accepted intent; the response remains 202 with uncertain handoff. Clients should preserve their submission UUID across retries.

## Persistence boundaries

Run Records retain Skywright's accepted definition, task and artifact references. Registry rebinding reads those references through `TrainingProjectArtifactReferences`. The migration extends rebinding artifact kinds for separate configuration and metric contracts. Rolling that constraint back after such artifacts have been recorded requires an explicit data migration; rollback refuses incompatible rows rather than deleting history. Runtime credentials stay in the transient secret channel; only their binding revisions and usage facts are retained.

SkyPilot facts occupy separate append-only tables. Identical source payloads share one fact row. Separate observation rows preserve fetch times, including repeated observations and out-of-order arrivals, so a later conflict can be resolved by actual observation time without duplicating source payloads. Consumers must use those observation times when selecting the latest conflict. The lifecycle reducer and historical read composition remain with their owning issues.

## Deployment inputs

Configure the operator-qualified local target through `skywright.local-run`:

```yaml
skywright:
  local-run:
    identity: local/amd
    kubernetes-context: local
    gpu-model: MI300X
    maximum-gpu-count: 1
    gpu-memory-bytes: 206158430208
    cpus: "8"
    memory: "32"
```

Missing qualification reports unavailable admission. This is a declared target capability, not a promise of free capacity. The API neither selects a different target nor changes accelerator backends.

Storage defaults must be assigned for the requested local target class. Dataset and Run Store require separate qualified destinations and training-process Credential Bindings. Basic submissions have no runtime ceiling, so their credentials must be declared non-expiring; the broker checks against `Instant.MAX` instead of inventing a finite lifetime.

The owner moved private-image integration from #232 to #65 on 2026-09-07. Private GHCR admission now qualifies the target-side helper and pins its namespace, then records the original pull credential revision. Delivery installs the immutable Run-owned Secret before first dispatch. An unavailable helper still refuses private admission; registry credentials stay outside the Training Process. See [private GHCR delivery](run-commands.md#private-ghcr-images).

## Validation

`LocalRunAcceptanceIT` exercises PostgreSQL uniqueness, projection rollback, concurrent workers, lost responses, both dispatch crash windows, delayed visibility, backend restart and immutable-column permissions. It also checks retained conflicts arriving out of order.

`LocalRunAssemblyIT` uses the production resolver, catalog, storage selection and credential broker with PostgreSQL and S3. Controlled registry, Vault and SkyPilot responses isolate external boundaries. It verifies the Dataset lease, pinned contract and image inputs, digest conversion and separate credential channel. It does not provision a GPU. The installed-runtime and held-SDK qualification remain in #231 and #56.

## Browser actions and accepted lineage

`GET /api/v1/local-run-target` returns operator-configured GPU capacity and a bounded submission-source observation. It reserves no capacity. The creation page uses the generated product contract and backend diagnostics. Before its first POST it stores the exact request and submission UUID in browser-local storage, serialized across tabs. Lost responses and reloads offer replay of that same request. Confirmed acceptance retains the Run link; a structured 422 rejection permits deliberate editing with a new identity. Corrupt or unavailable browser storage disables new submissions.

Run detail keeps cancellation intent, receipt delivery progress and escalation timestamps separate from the observed lifecycle. Request UUIDs survive reloads. The backend's terminal evidence determines the final outcome, including completion racing with cancellation.

`GET /api/v1/runs/{runId}/lineage` reads Skywright's accepted relationship independently of SkyPilot and Run Store availability. New unseeded acceptance writes affirmative root lineage. Older rows lacking that record return unavailable, never an inferred root. Derived records retain the predecessor UUID, exact checkpoint reference and verified seed-copy timestamp outside the Run Definition.

## Local owned checkpoint seeds

An optional `checkpointSeed` contains `predecessorRunId` and `checkpointReference`. The local path requires the identical published Project Version, Dataset, ordering inputs and resolved configuration. The resolver and an exact configuration comparison enforce this before copying. It does not implement editable clone configuration or Ordering Reset, which remain in #58.

A durable preparation reserves the child UUID under the submission identity before external byte writes. It pins the destination definition and retains a verified copy receipt. Conflicting input cannot reuse the preparation. Rollback or a lost HTTP response leaves the destination inventory owned by that preparation; replay can accept the same child. Failed preparation is explicit retained transfer work, not a compute submission queue.

The backend launches one isolated Java Transfer Worker at a time. Source and destination credentials use the `transfer-worker` role and stdin; non-secret projection, process and release events are append-only. The worker resolves the predecessor's current location, stages at most 32 MiB, validates checkpoint provenance and checksum, uploads bounded multipart parts and verifies the destination before recording completion. Request calls have 30-second limits, the child process has a 240-second lifetime limit, and its durable claim lasts 300 seconds. Failed or lost attempts may be retried after the claim expires. Abandoned multipart uploads at the exact reserved seed key are cleaned before retry.

The immutable copy lives at `<project>/<child>/seed-v1/<predecessor>/checkpoints/<19-digit-step>/<digest>.safetensors`. This is inside the child Run's stable inventory boundary and outside its ordinary `v1/` recovery journal. Original checkpoint bytes retain predecessor identity. Skywright's SDK verifies the owned copy using the child Run Store credential, checks the originating Run and Project Version, and preserves zero initial Recovery Debt. Recovery before the child's first checkpoint still reads this owned copy. Ordinary checkpoint readers continue rejecting other Runs and arbitrary object paths.

`LocalRunAssemblyIT` exercises real PostgreSQL/S3 acceptance, the production worker, durable lineage, replay, restart and SDK recovery after the source checkpoint is removed. The installed managed-runtime test restores the same continuation from an owned copy after predecessor loss. These are CPU/service tests; actual GPU UI qualification is tracked separately in #233/#235.
