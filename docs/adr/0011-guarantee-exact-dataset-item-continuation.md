---
status: accepted
---

# Guarantee exact Dataset Item continuation

Skywright interprets `D5` as an exact logical-order guarantee: each epoch is a deterministic permutation containing every Dataset Item once, and recovery continues the same flattened sequence without skips, duplicates, or reordering. This guarantee is expressed in stable Dataset Item identities, not identical decoded tensors, augmentations, model state, or floating-point results.

## Ordering state and commit boundary

A Dataset Item is identified within its immutable Dataset Definition by canonical ordinal. Library-owned Checkpoint State carries a Dataset Cursor — global epoch, item offset, and epoch-local Step count — plus a fingerprint of the Dataset Definition, seed, ordering policy, and policy version; the checkpoint also restores global Step normally. Storage Location, cache state, loader-worker count, accelerator count, and batch grouping are deliberately absent because they may change retrieval and grouping but never the logical sequence.

The cursor advances only when a Step completes. Prefetched Dataset Items and items used by an interrupted Step are therefore replayed, and a Step may contain any number of batches or items but may not span a Dataset epoch boundary. The initial policy does not support dropping items, replacement, weighting, or curricula; those require future explicit, versioned ordering policies.

## Continuation and reset

Recovery of the same Run and a checkpoint-seeded Run both continue exactly when the ordering fingerprint matches. A fresh Run with the same Training Project Version and ordering inputs reproduces the same Dataset Item Sequence from the beginning. Other Run Configuration and target choices may change, as ADR 0008 permits, but a changed seed or ordering policy is invalid for a checkpoint-seeded Run.

A changed Dataset Definition is permitted only through an explicit **Ordering Reset** mode carried by the Run Submission and persisted in the Run Definition; an unconfirmed mismatch is rejected with the differing input identified. Ordering Reset restores global epoch, global Step, RNG, and all other Checkpoint State normally, resets item offset and epoch-local Step count to zero, and begins a deterministic sequence over the new Dataset Definition at the restored global epoch. It makes no sequence-continuity claim across that boundary; partial checkpoint restoration is a separate future concern.
