---
status: accepted
---

# Keep dataset preprocessing outside Skywright

Skywright does not execute or orchestrate dataset preprocessing in its first version. A Run cannot produce a Dataset Definition, and there is no preprocessing Run kind. `D7` instead begins with an already storage-ready local corpus and is satisfied by **Dataset Publication**: one source-side CLI operation that copies the corpus to remote storage, verifies its complete integrity manifest there, and atomically publishes the resulting immutable Dataset Definition to the catalog. This keeps preprocessing under the Training Project's ownership and avoids weakening the single Run contract with output, recovery, storage, and lifecycle exceptions for a small initial benefit.

## Publication contract

- The caller either names an existing Dataset or omits that reference so Skywright creates a new Dataset identity. Skywright never infers semantic lineage.
- The caller may supply a human version label; the content fingerprint is always mandatory. Publishing the same Dataset identity, label, and fingerprint again is idempotent, while reusing a label for different content is rejected.
- The verified remote Storage Location becomes the Dataset Definition's authority. The local source remains untouched and is not registered as a Dataset Replica.
- Nothing becomes visible as a Dataset until every staged byte and the complete manifest have been verified and the catalog commit succeeds. Failed publication leaves no Dataset Definition.
- A new Dataset's first definition becomes preferred. Publication into an existing Dataset must explicitly choose whether to advance its mutable preferred-definition pointer.
- The first version exposes publication as a CLI command. Its Python implementation is internal rather than a separate public preprocessing or publication API.

## Consequences

Skywright records no preprocessing recipe, transformation lineage, intermediate state, metrics, checkpoints, or recovery history. Converting, cleaning, augmenting, encoding, or sharding content happens before Dataset Publication and remains the caller's responsibility. Supporting a Run that publishes a Dataset Definition later would be a new architectural decision rather than an alternate spelling of this workflow.
