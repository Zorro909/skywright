---
status: accepted
---

# Address Run Stores through pre-registered Target Storages

A Run Store is a path in object storage that Skywright never invents. Storage is modelled in two layers: a **Target Storage** is a pre-registered, credentialed destination — an endpoint and a bucket — and a **Storage Location** is a concrete path within one. A Run Store is a Storage Location, and so is every place a Dataset lives, which retires the separate `Dataset Location` term in favour of one addressing concept shared by both. Each target class carries a default run-output Target Storage; a Run Submission may override it, and the result is resolved once at submission. `C3` is satisfied because the Run Store never sits on the compute instance at all.

## Access

The library owns an S3 client and writes over the API on every target, including the local box. SkyPilot's bucket `MOUNT` was rejected on evidence: [it is refused outright by RunPod and Vast](https://github.com/Zorro909/skywright/blob/research/issue-3-neocloud-coverage/docs/research/skypilot-neocloud-coverage.md), which are exactly the cheap spot providers this project exists to use, and `B3` requires a Training Project to run with no orchestrator present, so the library cannot depend on SkyPilot having mounted anything. A filesystem adapter for local execution was rejected as well — ADR 0003 already obliges a directly executed Training Project to reach an S3-compatible Dataset Location, so an endpoint is a precondition of running anything, and a second adapter would buy no capability while doubling the atomicity semantics and the ways a Metric View must be pointed at data. ADR 0007 already assumed this shape when it had TensorBoard read metric segments over `S3_ENDPOINT`; this decision makes it explicit rather than incidental.

Target Storage definitions — endpoint, bucket, credential reference — are deployment configuration read by both the backend and the library, so an orchestrated run and a `B3` debugging run resolve storage the same way. The database stores only the resolved reference on a run, never the credentials, which keeps secrets out of a store ADR 0004 already requires to be separable and backed up.

`K5` is served by the *option* rather than by a mandate: nothing forces a local run to depend on a cloud service, because a local-network S3-compatible server is a valid Target Storage. Skywright does not forbid pointing a local run at a cloud bucket if that is what someone wants.

## What a valid Target Storage must support

Naming the floor is what stops per-provider special-casing later, since users will register MinIO, R2, B2, Nebius and S3 against the same contract. Required: atomic `PutObject`, multipart upload, ranged `GetObject`, `ListObjectsV2`, `DeleteObject`, strong read-after-write consistency, and presigned URL generation. Deliberately not required: server-side lifecycle rules, versioning, object lock, and tagging. Skywright prunes with its own `DeleteObject` calls so that `C6` behaves identically everywhere instead of depending on whether an endpoint implements lifecycle policy — and a server-side rule would be precisely the unconfigured automatic deletion `K6` forbids. Presigning is what lets checkpoint and artifact bytes reach a human without passing through the Java backend, matching ADR 0004's rule for dataset bytes; an endpoint that cannot presign is not usable.

Datasets and run outputs use separate Target Storages. They agree on nothing that matters: datasets are immutable, long-lived, read-only to a run, and governed by ADR 0004's replica, lease and generation machinery, while run outputs are per-run, write-heavy, retention-pruned, and have no catalog. One bucket cannot express "this run may write here but only read there" as a scoped credential.

## Layout, writing, and retention

A Run Store is laid out as `<bucket>/<training-project>/<run-id>/{checkpoints,samples,artifacts,metrics}/`, extending ADR 0007's `<run-store>/<run-id>/metrics` with a project level so that one Target Storage can serve several projects under separately scoped credentials and `ListObjectsV2` stays bounded.

A checkpoint is **one object**. `CompleteMultipartUpload` is therefore the publication event, and `C5` holds structurally: a partially written checkpoint never becomes visible, so the fallback the reference-workload spike demonstrated defends against corruption rather than against torn writes. A checksum in object metadata is verified on read, and a rejected checkpoint falls back to its predecessor. Splitting model, optimizer and cursor into separate objects would make "download just the weights" cheap but would reintroduce a torn state and require a marker object written last.

Writes are staged. At a cadence safe point the Run Context snapshots Checkpoint State synchronously and uploads in the background, so the Step safe point blocks only on the snapshot and `C4` can afford a frequent cadence without paying upload stall for it. The **interruption** checkpoint is the exception and is written synchronously: Nebius gives sixty seconds of notice, Verda and RunPod give none, and there is no time to be clever. The consequence is that "checkpointed" and "durable" are different moments, and a preemption during an upload costs that checkpoint.

`C6` retention keeps the union of the newest N (default three, enabled by default), every Xth checkpoint (disabled by default, so a long run can be inspected for training progress after the fact), and the checkpoint a finished run ended on. Pruning happens only once the replacement is confirmed published, the publish-then-delete discipline ADR 0004 already applies to replica refresh. `K6` is satisfied because ADR 0002 materializes every default into the immutable Run Definition: the retention rule is a value visible on the run rather than a hidden library constant, which is what makes hundreds of automatic deletions per run legitimate without per-deletion confirmation. Retention reaches checkpoints only — Samples, Artifacts and metrics live as long as the Run Record, so the Run Store's retention is non-uniform, as ADR 0007 already conceded. Deleting a whole Run Store is a human action under `U11`.

## The Run Store moves

The Run Definition holds the **execution** Target Storage: immutable, resolved at submission, and what the training process is handed. The Run Record holds the Run Store's **current** Storage Location: Skywright-originated, mutable, initialized to the execution location and updated when a transfer completes. Every reader of a finished run resolves through the Run Record — the Metric View's logdir, presigned download links, `O5` diagnosis — so the Run Definition stays a faithful record of where the run *wrote* while the Run Record answers where its output *is*.

Repatriation is enabled by default and expressed as a destination Target Storage plus a flag, globally defaulted and overridable per Run, materialized into the Run Definition like any other default. It is therefore a no-op when the execution Target Storage already is the destination, which exempts local runs by arithmetic rather than by a carve-out and covers a deliberately cloud-targeted local run without a special rule. It triggers on terminal states only — finished, failed and aborted, never interrupted, which would move a Run Store out from under its own resume. Failed runs repatriate too, because `O5` and `U10` want diagnosis context long after the cloud store stops being worth paying for.

The order is copy, verify, then delete, and never any other order; per-object checksum verification before any deletion is what makes an automatic release of data-loss-potential resources satisfy `K6` rather than violate it. If the destination is unreachable, full, or fails verification, bounded retries are followed by leaving the run in its cloud Target Storage with the Run Record still pointing there, surfaced as a pending repatriation a human can retrigger. The run stays fully usable; it simply keeps costing cloud storage. Treating repatriation as mandatory and marking such a run degraded would make the home store a hard dependency of every cloud run — the coupling `K5` was written against, merely inverted.

A running Metric View is restarted at the new location once migration completes, and its URL resolves against the Run Record rather than an explicit Storage Location, so a human watching a run is not dropped by the move. ADR 0007 already established that an instance holds no state beyond a logdir pointer.

## The Transfer Worker

Copying between two Storage Locations, verifying against checksums, publishing, and optionally deleting the source is one protocol serving three jobs: repatriation, seeding a resumed run, and ADR 0004's dataset materialization. It is therefore one **Transfer Worker** role rather than three implementations that could drift in their integrity guarantees. What varies is where it runs — the backend host by default, source-side for a workstation-originated dataset upload, since a backend-hosted worker cannot read a workstation's disk — and never the training instance, which must release an expensive accelerator rather than stay alive pushing bytes. Dispatching workers onto other hosts is a separate mechanism outside this map; without it, workers run on the backend host only.

Because the worker is a distinct role that merely defaults to the backend's host, run-output bytes do not travel through the backend's request path, and ADR 0004's rule that bytes never proxy through the Java backend survives intact.

## Retry is a clone

Terminal is terminal. ADR 0005 derives Run Lifecycle State per read, so a Run Record able to leave a terminal state would make that derivation depend on history. Resuming a failed or aborted run is therefore a **clone that names a seed checkpoint**, not a reopening: a new Run Record with a new Run Store, seeded with that one checkpoint object.

The Training Project Version must be identical, because Checkpoint State is registered by project code and no other version has a guaranteed-compatible state shape; the library compares registered keys against the checkpoint's on load and fails early in the manner `B4` requires, rather than half-restoring. Run Configuration and target may differ — retrying a spot failure on on-demand is the obvious case, and a checkpoint crossing ROCm and CUDA is `T4`'s explicit promise. Skywright cannot check whether a changed hyperparameter makes the resume *semantically* sensible, and does not pretend to.

The new Run Record records its predecessor and the exact checkpoint it was seeded from. That lineage is what lets `O6` walk a retry chain back into one training history and `U1` present it as one effort; without it, the split across Run Records would be permanent loss at the seam. Only the checkpoint is seeded: the predecessor's Run Store stays intact and authoritative for its own metrics, samples and artifacts, so copying them forward would manufacture the second authoritative copy `Q1` forbids. The Transfer Worker resolves the seed through the predecessor's Run Record, so whether that run was already repatriated is invisible to the caller.

## Consequences

Run outputs live in two homes and every reader must reach both. A run's metric history splits across Run Records at each retry, so `O6` comparison of a retried effort means following lineage rather than opening one run — the price of keeping terminal states terminal and lifecycle derivable.

Repatriation pays bucket egress once per cloud run, and a resumed run pays a checkpoint round trip. This makes a zero-egress endpoint materially cheaper, but that is a choice about which Target Storage gets registered, not an architectural property — Skywright sees a generic S3-compatible endpoint either way.

Cloud storage can accumulate quietly when repatriation keeps failing and nobody watches the pending list, so cost visibility has to cover pending repatriations and not only running instances. More generally, several outcomes here need a human promptly — a failed repatriation above all — and no attention or notification surface exists yet.

Credentials for a Target Storage now have a named thing to attach to, but how they reach a running training process, a Transfer Worker, and a spun-up Metric View is still open and still waits on the bridge decision.
