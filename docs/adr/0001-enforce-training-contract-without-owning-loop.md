---
status: accepted
---

# Enforce the training contract without owning the training loop

Skywright is a contract-enforcing toolkit, not a training framework. A Training Project owns its training control flow, while an explicit library-provided Run Context owns and validates the portable concerns required by configuration, datasets, checkpoint/resume, metrics, persistence, and target-specific runtime selection. This preserves unusual single-node training designs and direct local debugging without allowing each project to invent incompatible operational contracts.

## Contract boundary

- Run Configuration is one semantic tree with exclusively owned properties and is validated before execution; [ADR 0012](0012-compose-one-owned-configuration-tree.md) defines its library/project boundary and composition contract.
- Durable Datasets use MosaicML Streaming; their payload semantics remain project-owned. Live-generated experience is not a Dataset and is outside the initial scope, though it can be persisted for inspection or later reuse.
- Multi-node execution and compatibility are not design goals.
- Every run has a mandatory Run Store. Its target may vary between local and external execution, but its project-facing contract does not.
- The Run Store exposes separate operations for validated checkpoints, library-understood Samples in common media types, and arbitrary Artifacts.
- Projects register all standard and project-specific Checkpoint State before training. The library owns serialization, restoration, atomic publication, and retention.
- Metrics are declared before a run and undeclared metrics are rejected. A version-bound project contract and the exact Skywright metric schema compose the validating catalog at runtime, while accepted event encoding and visualization delegate to TensorBoard; [ADR 0015](0015-compose-version-pinned-metric-contracts.md) defines the contract and deliberately persists no catalog copy. [Research confirms that TensorBoard is a suitable event sink but does not provide the required enforceable metric schema](../research/tensorboard-metric-contract.md).
- A project-owned loop must report each completed Step through the Run Context. This safe point lets the library flush metrics, apply checkpoint cadence, persist progress, and honor interruption without owning the loop.
- A library-owned Environment Profile selects CUDA- or ROCm-compatible dependencies from target capabilities. Training Projects remain backend-agnostic; an explicit override is reserved for testing and diagnostics.

## Considered options

A framework-owned loop was rejected because it would constrain diffusion, adversarial, RL, and other unusual control flows beyond what checkpointing and observability require. An unconstrained toolkit was rejected because it could not guarantee complete checkpoints, comparable metrics, portable datasets, or early contract failures.

## Consequences

The same project script runs directly under a debugger or through orchestration; the execution environment changes configuration and resolved infrastructure, not project control flow. Downstream decisions still choose the concrete Run Store backends, TensorBoard-compatible metric persistence, dataset access details, and whether CUDA and ROCm use one image or separate images.
