# Skywright Configuration Schema changelog

Every library property addition, removal, rename, ownership transfer, type or constraint change,
default change, or semantic change must be recorded here. Breaking entries include migration
guidance. Published schema bytes are immutable; a change always creates a new schema version and
digest. The backend retains an explicit trusted copy of every supported historical identity and
reports an unlisted identity as not runnable.

## 0.4.0

- Added `dataset.ordering.version`, fixed to `feistel-sha256-v1`. Configuration
  resolution now materializes the version alongside policy and seed. Checkpoints
  retain these inputs for exact continuation diagnostics and explicit Ordering Reset.

Migration: rebuild Training Project contracts against this schema identity. Newly
resolved configurations receive the version default. Older checkpoints with matching
fingerprints can continue exactly; reset requires checkpointed ordering inputs.

## 0.3.0

- Corrected `checkpoint.cadence` to describe absolute-Step snapshot scheduling and
  asynchronous durability lag. Its type, default, constraints, and resume compatibility are
  unchanged.

Migration: no Run Configuration value changes. Training Project contracts must pin the new schema
identity when built against this SDK version.

## 0.2.0

- Added nullable `checkpoint.keepEveryNth`, defaulting to `null`, so retention can protect
  Checkpoints whose Step is divisible by an explicit positive interval.

Migration: existing submissions resolve the new property to `null`; resume may change it without
changing Dataset ordering identity.

## 0.1.0

- Introduced `reproducibility.seed`, `dataset.ordering.policy`, `checkpoint.cadence`,
  `checkpoint.retention`, `metrics.flushInterval`, `metrics.segmentRoll`, and
  `metrics.systemSamplingInterval`.
- Established Draft 2020-12, closed-world composition, content-digest pinning, and the version 1
  Project Configuration Contract artifact.

Migration: initial version; no prior configuration schema exists.
