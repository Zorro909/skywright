---
status: accepted
---

# Use one backend-neutral dataset path

Skywright exposes one Dataset access contract through the Run Context instead of separate local-filesystem and remote-streaming paths. The first implementation uses MosaicML Streaming with S3-compatible object storage on every target, including local Kubernetes; S3 compatibility is a protocol boundary and does not require AWS. This keeps project code and correctness behavior portable while leaving storage implementations replaceable.

## Contract boundaries

- A Run Definition references a Dataset Definition version, not a selected physical location. Skywright selects a Dataset Location for each execution and records it in the Run Record.
- A Dataset Cache is bounded, reusable after a same-host restart, non-authoritative, and disposable. Losing it may reduce performance but cannot change correctness because Skywright can rebuild it from a durable Dataset Location.
- Sample identity, ordering, and resume position are defined above storage access and caching. Changing endpoints, cache state, or a future access backend cannot change the logical sample sequence.
- Local-filesystem and other access backends may be added later as transparent performance optimizations. They are not required by the initial architecture and must not introduce a second Training Project-facing contract.

## Consequences

Local Kubernetes and directly executed Training Projects need access to an S3-compatible Dataset Location. The initial implementation does not need a local-filesystem fast path. The exact strength of sample-order recovery under D5 remains a downstream decision.
