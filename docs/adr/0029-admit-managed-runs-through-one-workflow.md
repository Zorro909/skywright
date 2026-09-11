---
status: accepted
---

# Admit managed runs through one workflow

Skywright presents one Managed Run admission module to the control-plane HTTP adapter and GUI. Its small interface supplies a joined, read-only admission form and accepts a Run Submission. The implementation hides Training Project Version and Dataset resolution, target readiness, storage selection, Credential Projection, Run Definition resolution, Orchestrator Task Specification projection, durable acceptance and initial handoff. This replaces the local-only admission path instead of adding a parallel cloud path.

The prototype has two target adapters behind this module: local AMD Kubernetes and Vast.ai on-demand. Their target and image-pull behaviors differ, while Run identity, configuration, storage, credential, idempotency and lifecycle rules remain shared. Skywright does not add a provider plugin system, generic provider option maps or separate configuration authority. A later target earns another adapter only when its qualified behavior differs from the existing adapters.

The primary GUI workflow offers one installed demonstration Training Project Version and Dataset Definition, the qualified target choices, and joined readiness evidence. Its caller chooses a workload and target, then submits, observes, cancels when applicable, and inspects or downloads an output. Raw identities, storage overrides, Recovery Debt tuning and other expert controls stay outside this first workflow.

Vault remains the sole Credential Authority. The deployment package installs or verifies Vault, provisions the required non-secret Credential Bindings, and accepts secret material only through documented non-logging inputs. Existing durable acceptance, first-dispatch authority and lifecycle derivation remain authoritative inside the new module rather than being replaced.
