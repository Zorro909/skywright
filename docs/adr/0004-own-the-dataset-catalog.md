---
status: accepted
---

# Own the dataset catalog

Skywright owns the authoritative catalog of Dataset metadata in a durable metadata database that outlives and is separable from the replaceable backend process. Dataset payloads remain in external storage: the catalog is their source of identity, version, location, integrity, and lifecycle metadata rather than a second authoritative copy of their content. This makes registered Datasets queryable for U9 without adding a separate catalog product or scanning every storage system, and does not violate Q1 because Skywright is the original source of the catalog metadata it owns.

## Contract boundaries

- A Dataset is a stable lineage with a mutable current-version pointer used only for catalog display and lifecycle recommendations. Every Run Definition pins an exact immutable Dataset Definition instead of following that pointer.
- A Dataset Definition is an immutable content snapshot. Its public identity combines a stable Dataset identity, an optional human-assigned version label, and a mandatory content fingerprint; when no label is supplied, Skywright uses an abbreviated fingerprint. Reusing a label for different content is rejected.
- Each Dataset Catalog Record designates exactly one authoritative Dataset Location and tracks its durable Dataset Replicas and ephemeral Dataset Caches. A verified replica may be promoted when authoritative storage moves; changed content instead creates a new Dataset Definition.
- A Dataset Replica is byte-preserving, verified against the Dataset Definition's integrity manifest, and independently identified by a stable replica identity and generation. Runs lease exact replica generations in their Run Records. Last run use means the latest Dataset Lease, not a catalog read or administrative check.
- Refresh is guarded replacement. Deprecation prevents new leases, replacement waits until existing leases end, and the old generation is deleted only after its replacement has been uploaded, verified, and published. Advancing a Dataset's current version makes older replicas superseded rather than invalid. Automatic cleanup is limited to non-authoritative copies under explicit retention or cost policy.
- U9 reads Dataset identity and versions, authoritative location, replicas and caches, exact storage consumed, generation and lifecycle state, creation and last-verification times, last run use, and active lease count from the catalog.

## Dataset materialization

Skywright actively manages materialization, verification, promotion, guarded refresh, and copy deletion through storage adapters. When an authoritative location is reachable only from a local workstation or another source-side environment, a Skywright CLI or library process uploads directly to the destination and reports the verified replica to the catalog; dataset bytes never proxy through the Java backend. Transfers stage, verify the complete integrity manifest, and publish before the replaced generation is deleted.

Materialization is byte-preserving. Any operation that changes samples, encoding, sharding semantics, or other manifested content is preprocessing and creates a new Dataset Definition rather than refreshing a replica.

## Consequences

Skywright requires durable metadata persistence even though it does not own the dataset payloads. Storage adapters must support integrity verification and lifecycle reporting, while the catalog must coordinate replica leases with Run Records. The database technology, credential transport, automatic-policy calibration, and whether preprocessing is represented as a Run remain downstream decisions.
