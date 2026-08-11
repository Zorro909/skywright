---
status: accepted
---

# Centralize managed credentials in Vault and project them by role

Skywright uses a locally operated HashiCorp Vault as the single Credential Authority for its managed control-plane deployment. Provider, storage, registry, and service credentials remain distinct, but Vault is their sole managed source of truth. Skywright stores only non-secret Credential Bindings and Credential Projection Records; secret values never enter a Run Definition or the Skywright database. This keeps local operation independent of a hosted secret service while avoiding the cryptography, master-key lifecycle, audit, backup, and recovery obligations of making Skywright a secret manager.

## Bindings and enrollment

A Credential Binding is deployment configuration associating one external resource, consuming role, and access profile with a Vault entry. Bindings are typed: their expected value shape and validation are fixed by the provider, Target Storage, registry, or service they name. They expose non-secret readiness, identity, scope, validation, expiry, rotation, and usage metadata.

Where Vault and the provider support dynamic issuance, the backend requests a credential from Vault. Otherwise an operator creates the identity at the external provider once and fills the missing binding through Skywright's UI. The UI is a constrained enrollment surface over existing bindings, not a general Vault browser: it accepts a token, key, or credential file once and never reads it back.

Operator-supplied credentials are staged as candidate Vault versions. The backend validates their shape and required operations against the external system before promoting them. A failed candidate never replaces the active version. Enrollment requests and validation errors are structurally secret-free, and Skywright persists no submitted value. The initial deployment's private reachability boundary remains the authority to perform this full-control action; Vault records the secret-management audit under that deployment's identity.

## Projection boundary

A Credential Projection is a role-scoped runtime copy of a binding's secret material. Vault projects provider credentials directly into the SkyPilot API server and projects the backend's SkyPilot service-account token and backend-owned credentials into the backend. For processes that the backend launches — Training Processes, Transfer Workers, and Metric Views — the backend is the projection broker: it may transiently retrieve the exact consumer credential and inject it, but never persists or uses it as its own credential. A separate broker would add another privileged service without isolating a backend that already controls every such launch.

Remote Training Processes never receive a Vault identity or token. Their scalar S3 credentials arrive through SkyPilot's secret channel as environment variables. Structured credentials consumed by managed roles arrive as read-only temporary files with only their paths exposed through environment variables. Private-registry credentials are supplied to the target's container runtime for the pull and are not retained inside the Training Process. Secret values are not part of the Run Definition or the Run Definition-derived Orchestrator Task Specification.

Direct execution under `B3` is deliberately outside the managed Credential Authority. A shell, IDE, or ordinary dotenv tooling loads local `.env` values and credential-file paths into the process environment. The Python library consumes the same documented environment and file contract in both modes; it knows nothing about Vault, Kubernetes, or SkyPilot.

## Isolation floor

Credentials are distinct by external resource, Training Project where applicable, consuming role, and access profile even when two profiles happen to carry the same permissions. The portable floor uses reusable identities rather than requiring every supported provider to issue credentials per Run:

- Only the SkyPilot API server receives cloud-provider provisioning credentials, one provider identity per configured provider. Only the backend receives the SkyPilot API-server service-account token.
- A Training Process receives read-only access to its selected Dataset storage and read/write/delete access required for checkpoints and outputs inside its Training Project's Run Store prefix.
- The backend receives only the Target Storage operations and prefixes required for observation, Run Log Archive capture, control delivery, and deletion coordination.
- A Transfer Worker receives explicit source-read, conditional source-delete, and destination-read/write permissions for its transfer.
- A Metric View receives read-only access to the selected Run's Metric Segments.
- The backend's GHCR resolver and an execution target's private-image pull use separate read-only registry identities. Public Environment Profiles require none.

More narrowly scoped or per-Run dynamic credentials are used when a provider supports them, but they are not a portability requirement. A provider that cannot meet this reusable role-isolation floor cannot be First-class. The backend's transient ability to project another role's secret does not authorize it to use that external identity for its own work.

## Lifetime, rotation, and revocation

A managed Run receives one fixed Credential Projection when it is launched. SkyPilot retains that projection for Managed Jobs recovery, so a recovered Execution Attempt does not contact Vault and does not silently acquire a newer credential. This keeps Vault out of the running Training Process's availability path.

New Runs receive the current active binding revision. Normal rotation validates and promotes a new revision while leaving the previous external credential valid until every Run using its projection is terminal and every dependent operation has released it. Emergency revocation is immediate and explicitly lists the active consumers it may break; security takes precedence over preserving them.

For every projection, Skywright writes an immutable Credential Projection Record containing the binding and non-secret revision identities, consumer role, Run or operation identity, and projection and release times. It contains no value, Vault token, lease secret, or authentication header. This is Skywright-originated evidence of where it sent a credential, not a second secret audit or authority; Vault remains authoritative for secret versions, issuance, and revocation.

## Redaction and trust

Credential enrollment endpoints, control-plane logs, launch requests, exceptions, and UI responses omit secret-bearing fields and values by construction. Secrets are never placed in command arguments or interpolated task text.

A Training Project is trusted with its own projection and the Training Contract forbids disclosing it. Skywright adds no output filter: project code can transform or exfiltrate any value visible in its process, so filtering cannot provide a security guarantee. ADR 0018's Run Log Archive therefore remains the raw output SkyPilot observed. A credential found in training output is compromised and must be rotated or revoked.

## Failure behavior

Credential failure is capability-specific rather than global:

- Before target selection, a missing, invalid, or expired binding makes only its target or capability ineligible and exposes the reason. A provider-pinned submission fails before provisioning; an already selected target is never silently replaced.
- Vault unavailability blocks enrollment, rotation, and new projections. It does not stop a Run whose projected external credentials remain valid, and no stale or broader credential is substituted.
- Backend, Transfer Worker, and Metric View credential failures make only the dependent capability or operation unavailable or retryable.
- A Training Process credential rejection is a terminal Skywright failure. Its fixed projection is never hot-swapped; after the binding is repaired, continuation is a checkpoint-seeded clone with a new projection.
- Emergency revocation may deliberately cause active Training Processes, image-pull recovery, transfers, views, or control capabilities to fail.

## Consequences

Vault becomes a required local control-plane dependency for new managed work, and its provisioning, unseal, persistence, backup, and recovery must be operated separately from Skywright. Each neocloud still requires one-time provider-side identity creation when it offers no dynamically issuable credential. Neither obligation expands this architecture effort into provisioning Vault or provider accounts.

The backend becomes trusted to handle every runtime credential transiently while remaining unable to recover one from Skywright persistence. Role separation is enforced at the external systems and recorded by Credential Projection Records, rather than being inferred from where Vault happens to store the values.
