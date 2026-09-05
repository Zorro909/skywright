# Local Credential Projections

This implements #229's local credential boundary under [ADR 0025](adr/0025-centralize-managed-credentials-in-vault.md).
Configure and externally validate the operator-prepopulated bindings described in
[local Vault bindings](local-vault-bindings.md) first. The typed launch boundary is available
for #231 and #56; API admission and the complete local AMD workflow remain in #232 and #235.

## Role delivery

| Consumer | Projection | Binding and access |
| --- | --- | --- |
| Backend storage operations | Existing `VaultRoleAccess` AWS provider | Exact backend binding for the registered Target Storage |
| Backend SkyPilot client | Transient `SKYPILOT_SERVICE_ACCOUNT_TOKEN` in its isolated GraalPy context | SKYPILOT/backend, exact API endpoint resource |
| SkyPilot API server | `KUBECONFIG` naming a mode-0400 JSON file | KUBERNETES/skypilot-api-server, delivered directly by Vault Agent |
| Training Process Dataset access | `SKYWRIGHT_DATASET_*` secret variables | S3/training-process, `read-only`, selected Dataset Target Storage |
| Training Process Run Store access | `SKYWRIGHT_RUN_STORE_*` secret variables | Separate S3/training-process binding, `read-write-delete`, selected output Target Storage |
| Local container runtime | Immutable Kubernetes dockerconfigjson Secret | GHCR/execution-target-pull, `read-only`, exact project repository; separate from backend-resolver |

Set `skywright.credentials.skypilot-binding` to the backend service-account binding UUID.
When Vault is configured, omission or a wrong role/resource fails the SkyPilot capability closed.
The backend resolves its own token for each call, injects it into the client context, and clears
it in a `finally` block. Each call appends a non-secret projection fact and a release fact
after the call finishes, including when the operation fails. It never uses a Training Process binding to authorize those calls.
Existing backend storage and registry consumers retain their separate bindings from #228.

The deployment fixture in `deployment/examples/local-credentials/` shows a one-shot Vault Agent
render of an exact Kubernetes KV revision. Replace its path and version with the registered
operator-prepopulated binding. Run Agent as the SkyPilot OS identity, with a path-limited Vault
token supplied separately, retaining Vault's default token-self lookup/renewal policy; mount `/run/skywright` on ephemeral storage. Start each service lifetime with an empty render destination and launch only after Agent exits successfully.
Start SkyPilot with
`SKYWRIGHT_KUBECONFIG=/run/skywright/kubeconfig`. Its launcher accepts only an owner-only,
read-only regular JSON kubeconfig containing a single static-token user and embedded CA.
No kubeconfig exec plugin, credential-file reference, writable file or symlink is accepted.
The Agent token file belongs to Agent and must not be mounted into Training Processes.
Remove the rendered file when that SkyPilot service lifetime ends.

This local Agent fixture uses token-file authentication for a one-shot render. For an operated
long-running deployment, configure the deployment's Vault machine-auth method, such as AppRole,
instead. HashiCorp documents [token-file authentication](https://developer.hashicorp.com/vault/docs/agent-and-proxy/autoauth/methods/token_file)
and [template delivery](https://developer.hashicorp.com/vault/docs/agent-and-proxy/agent/template).

## New Run and recovery

`LocalCredentialProjections.training(runId, dataset, runStore, requiredUntil)` resolves the
current registered revisions for a new Run. Each selection supplies its exact binding ID,
resource and access profile. Dataset and Run Store bindings must declare different external identities as well as distinct
binding IDs and resources. `requiredUntil`
is the caller's latest supported end of execution plus recovery; finite credentials must
expire strictly after that instant. Use `Instant.MAX` for an unbounded Run; it requires operator-declared non-expiring
external credentials. The caller must not turn an unknown lifetime into an arbitrary short
validity window.

The service commits only non-secret binding IDs, revisions, roles, slots and timestamps.
The database's unique Run/slot key rejects a second projection, including after a backend
restart. A failed projection rolls back its facts. A successful projection returns a transient
`TrainingCredentials`, which is supplied separately to `Orchestrator.submit(task, credentials)`.
Use try-with-resources after awaiting the submission handoff to clear the broker's references.
Its diagnostic representation and transport errors contain no values. Java strings cannot
be reliably overwritten in memory; clearing references is cleanup, not a memory-erasure claim.

The bridge passes these values to `sky.Task(secrets=...)`; the Run Definition and
`OrchestratorTaskSpecification` contain no credential values. The task's ordinary environment
cannot carry scalar storage credentials. SkyPilot retains the secret channel for Managed
Jobs recovery. Recovery must rediscover that retained job, without invoking the broker again.
A lost or rejected projection fails explicitly. Repair requires a new Run and new projection.
Do not retry a failed handoff by changing revisions under the same Run ID.

`LocalProjectionFacts.forConsumer(runId)` reads durable usage evidence. Call `release(runId)`
only after the Run and every dependent use are finished, SkyPilot no longer needs the retained
projection, and its runtime pull Secret has been removed. Release appends an immutable fact;
repeating it preserves the original timestamp. Neither closing the broker's temporary handle
nor losing contact with SkyPilot proves that the Run released its credentials.

## Local private-image pull

`LocalCredentialProjections.runtimePull` creates a mode-0400 Docker configuration in a private
mode-0700 temporary directory. The broker hands that file to the local SkyPilot/target-side
operator boundary. On that side run:

```bash
deployment/scripts/local-runtime-pull install --context <local-context> \
  --namespace <training-namespace> --run-id <run-uuid> --credential-file <protected-config.json>
```

The helper sends the Secret on kubectl stdin, captures provider output, and uses `create`
with `immutable: true`. It never replaces an existing Run's secret. Close the temporary
`RuntimePullProjection` after the handoff, including when installation fails. Temporary
files live on a private ephemeral mount; after a broker crash the mount's lifecycle removes
them. Transaction rollback also removes a prepared file.

Set `OrchestratorTaskSpecification.runtimePullSecret` to `skywright-pull-<run-uuid>`.
The bridge supplies only this reference through SkyPilot's Kubernetes pod configuration.
The Secret belongs in the same namespace as the training pods. Never mount it as a volume
or expose it as Training Process environment. The runtime uses it for the pull and retains
it for recovery. After all consumers end, call the helper's `release` action, then append
the Run release fact. The helper needs the SkyPilot target-side Kubernetes role's Secret
create/delete permissions; the backend must not borrow that provisioning identity.

## Direct execution and SDK consumption

The SDK has no Vault, Kubernetes or SkyPilot dependency. Ordinary shell/IDE dotenv tooling
can supply the same slots. `skywright.credentials.s3_credentials("dataset")` returns explicit
boto3 arguments for the Dataset client; #230 consumes this contract. Construct a Run Store
`TargetStorage` with `credential_slot="run_store"` to use the Run Store slot. That mode rejects
an AWS profile and never falls back to ambient AWS credentials or instance metadata.
The pre-existing direct profile/provider-chain mode remains available when no slot is selected.

Each slot contains `ACCESS_KEY_ID`, `SECRET_ACCESS_KEY` and optional `SESSION_TOKEN`, prefixed
with `SKYWRIGHT_DATASET_` or `SKYWRIGHT_RUN_STORE_`. Alternatively, set the slot's
`CREDENTIAL_FILE` variable to a mode-0400, owner-owned JSON file with those unprefixed fields.
Environment values and a file cannot be mixed within one slot. Files must be regular files,
not symlinks, with no group/other permissions. The direct executor owns their cleanup.
For example, a protected `run-store.json` has this shape:

```json
{"ACCESS_KEY_ID":"replace-locally","SECRET_ACCESS_KEY":"replace-locally"}
```

Clients capture the projection when constructed. Changing environment or a file does not
refresh an existing client. S3 authentication/authorization rejection is a typed,
redacted `CredentialProjectionError` without automatic retry or credential replacement.
When it occurs inside the library's Training Process boundary, the existing library failure
path reports a terminal Skywright failure. External expiry and revocation can break a Run,
recovery image pull, or an individual backend capability even while Vault remains healthy.
KV retention does not renew an external credential. Keep the external identities valid until
their recorded consumers release them; emergency revocation intentionally overrides that.

## Verification

`LocalCredentialProjectionsTest` covers exact role/resource selection, revision retention,
new Run revision selection, binding loss, redaction and temporary-file cleanup.
`LocalProjectionFactsIT` checks database persistence across restart, replacement rejection
and immutable release. `RuntimePullProjectionIT` exercises an authenticated local Distribution
registry through the container runtime, including unauthenticated rejection and no registry
environment in the launched process. Only the GHCR hostname is replaced for that fixture.
`GraalPySkyPilotClientIT` round-trips the actual pinned SkyPilot Task secret channel.
The SDK's real SeaweedFS test verifies Dataset read-only isolation, separate Run Store writes,
fixed client credentials, rejection and no ambient-identity fallback. Deployment tests execute
the actual launcher and runtime-pull helper with valid and failing inputs.

A disposable kind cluster also verified immutable pull Secret creation, duplicate rejection and
release with the real helper. The cluster was removed after the check.

Full GPU execution and recovery qualification through the UI remains #235. Provider enrollment,
remaining cloud targets, Transfer Worker/Metric View projections and full rotation/revocation
consumer enumeration remain #72/#73.

`VaultPersistenceIT` also runs the supplied Vault Agent configuration against real Vault,
verifies that it renders revision 2 while revision 3 exists, checks mode 0400, and confirms
that Agent output omits the projected value and token.
