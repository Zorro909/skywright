---
status: accepted
---

# Delete terminal Runs through monotonic, auditable operations

Deleting a Run crosses Skywright's database and one or more external Storage Locations, so it cannot be an atomic delete. Skywright represents it as one durable **Run Deletion Operation** that fences the Run, advances monotonically through partial failure, and becomes a permanent minimal **Run Deletion Receipt** only after every known external object is absent and the Run's database state has been removed. This deliberately rejects both database-first deletion, which can orphan objects, and rollback, which cannot restore content already deleted.

## Eligibility and ownership

Only a Run whose derived Run Lifecycle State is terminal — `finished`, `failed`, or `cancelled` — is eligible. Deleting a `waiting`, `running`, or `interrupted` Run is rejected: the user must issue a separate Cancellation Request and wait until `cancelled` is authoritatively observed before confirming deletion. Deletion is not a seventh lifecycle state and never doubles as cancellation.

One explicit confirmation creates the sole Run Deletion Operation for that Run. Repeating the request returns the existing operation or receipt. The operation immediately fences the Run against new readers, writers, Metric Views, Repatriations, and transfers; normal use never continues beside destructive progress.

The ownership cascade removes every fact exclusively owned by or attributable to the Run:

- the Run Record and its Run Definition;
- every object in its Run Store, including checkpoints, samples, artifacts, metrics, progress, attempt and termination records, recovery-exhaustion evidence, and the Run Log Archive;
- every known source, destination, and staging location created by Repatriation or another Run-owned transfer;
- Retained SkyPilot Facts, submission facts, Metered Usage and Run-specific rate bindings, ended Dataset Leases, and every other Run-indexed database record.

Shared Training Projects and Versions, Dataset Definitions and catalog state, Target Storages, Price Sources, and shared rates do not belong to the Run and survive. Deletion never cascades into a descendant Run. A descendant owns its already-published seed checkpoint and retains the predecessor's Run identity, which thereafter resolves to the receipt rather than to a Run Record.

## Quiescence

No object deletion begins until every possible writer has stopped and every protected outbound reader has completed or failed. The stateless Metric View stops immediately. Run-owned preservation work whose result is about to be deleted — log reconciliation and finalization, Repatriation, and their retries — is cancelled and brought to a safe stop. A transfer producing state owned by another Run, such as a seed-checkpoint copy, is instead allowed to drain because cancelling it would damage the descendant; failure of that transfer releases the dependency without making deletion responsible for repairing the descendant.

Quiescence freezes a complete deletion inventory: database partitions, the current Run Store, every known transfer location, committed objects, staging prefixes, and tracked multipart uploads. New work cannot enlarge that boundary after the fence.

## Monotonic deletion protocol

The operation proceeds in this order:

1. Persist the operation and fence the Run.
2. Quiesce concurrent activity and freeze the deletion inventory.
3. Abort incomplete multipart uploads, delete external objects from every inventoried location, and use strongly consistent listing to verify each relevant prefix is empty.
4. In one database transaction, remove every Run-attributed record and replace the Run Record and operation with the Run Deletion Receipt.

Destructive progress is never rolled back. Each step is idempotent, and a partial failure keeps the operation addressable with its inventory, completed work, remaining work, and last error. Bounded automatic retries eventually yield to an explicit attention-and-retry state; they never manufacture success. An unavailable Storage Location or unknown object count can leave an accepted operation pending, but an unknown ownership boundary prevents acceptance because Skywright would not know what it had promised to remove.

A receipt is published only after all known external content is confirmed absent and the database transaction succeeds. It permanently retains only the Run identity, operation identity, requesting principal, request and completion timestamps, deletion-protocol version, and successful outcome. It retains no Run Definition, terminal outcome, project configuration, Storage Location, object count, cost, log, failure history, or other operational metadata.

This protocol extends ADR 0008's Target Storage compatibility floor: Skywright must be able to abort every incomplete multipart upload it creates as well as delete committed objects and verify their absence. Upload identities therefore remain known until completion or abort.

## Confirmation contract

Before the operation is accepted, `U11` requires the user to see the exact Run and terminal state; every category of data that will disappear; known Storage Locations and best-known object counts and sizes; background work that will stop or drain; descendants that survive with only a deleted-predecessor receipt; and the fact that the action is irreversible. Unknown or unreachable facts are shown as such rather than as zero. The user gives one explicit destructive confirmation; the presentation mechanics remain outside this architecture decision.

## Consequences

A failed deletion can leave a fenced, unusable Run with only part of its content remaining, but it never loses the address or inventory needed to continue cleanup. Loss of storage credentials can therefore keep an operation incomplete indefinitely and must surface for attention. Conversely, a completed receipt proves that Skywright has no known Run-owned content left while preserving the minimum identity needed for audit, idempotency, and lineage.
