# Issue 233 qualification environment recovery

The initial read-only investigation and subsequent recovery on 2026-09-08
used only the explicit kubeconfig for isolated cluster `skywright-issue233`.
The final section records the recovery performed. Earlier setup and the managed
GPU qualification are recorded in the
[qualification record](issue-233-local-gpu-qualification.md).

## Retained and missing state

The live Vault status reports version 1.21.4, initialized, sealed, Shamir shares
1/1, and filesystem storage. Its configuration has no auto-unseal mechanism.
No unseal custody artifact was found in the persistent qualification cache,
repository, or Secrets in `skywright`, `skywright-training` and
`skywright-qualification`. The former custody file was under the lost `/tmp`
directory. Existing scoped Vault tokens cannot replace the missing unseal key:
Vault needs the share threshold to decrypt its stored root key.
[Vault seal documentation](https://developer.hashicorp.com/vault/docs/concepts/seal)

The four existing PVCs remain bound: `qualification-vault`,
`qualification-storage`, `skywright-postgresql-data` and
`skywright-skypilot-state`. PostgreSQL and object storage are running. No training
pods remain. These are observations from the isolated cluster's Kubernetes API
and read-only PostgreSQL queries, not an assertion of complete data integrity.

Kubernetes still holds storage IAM credentials, database credentials, the
provisioner's service-account token and CA, Vault TLS material, and the old
backend/Agent Vault tokens. It also retains the complete non-secret binding
manifest and deployment configurations. Protected snapshots and reconstructed
import documents are saved with mode 0600 under
`/tmp/skywright-issue233/secrets`, now backed by persistent session storage:

- `recovered-vault-s3-values.json`: eight original storage identities, recovered
  from `qualification-storage-iam`, keyed by their existing Vault paths.
- `recovered-vault-kubernetes-value.json`: one-context embedded JSON kubeconfig
  using the retained `skywright-provisioner-token`, internal API endpoint and CA.
- `qualification-bindings-resource.json`: all ten binding UUIDs, paths, roles,
  identities, scopes and recorded revisions.
- `qualification-vault-resource.json`, `skywright-backend-resource.json` and
  `skywright-skypilot-api-server-resource.json`: pre-recovery deployment snapshots.
- Individual Secret snapshots named after their Kubernetes resources.

No secret values are included in this note. The SkyPilot admin password and
backend bearer were not found in retained Secrets or ReplicaSet environment
history. The backend service account remains `sa-2e926798f661d5d8`, named
`skywright_backend`. Its retained database row contains a token hash, not the
recoverable bearer. The stock implementation deliberately returns the bearer
once and stores its hash.
[SkyPilot token implementation](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/users/token_service.py)

## Minimal recovery proposal

Create a separate Vault PVC and point only the qualification Vault deployment at
it, retaining the old sealed PVC untouched. Reuse the existing TLS configuration.
Initialize and unseal the new Vault, saving its custody directly to protected
persistent storage, then enable `skywright` KV v2. Reimport the eight unchanged
storage credentials and reconstructed Kubernetes identity. Issue new exact-path
backend and Agent Vault tokens and replace their delivery Secrets. This rebuilds
credential delivery without replacing the PostgreSQL, Dataset, Run Store or
SkyPilot state PVCs. The required Vault import shapes and path-limited policies
are specified in [local Vault bindings](../local-vault-bindings.md).

Recover SkyPilot administration through its existing startup configuration:
temporarily supply `SKYPILOT_INITIAL_BASIC_AUTH` from a protected Secret using a
new username and `username:bcrypt-hash` value. The stock startup calls
`permission_service.initialize()`; full initialization invokes
`_maybe_initialize_basic_auth_user()` even when other admins already exist.
Only an existing ID derived from that same username prevents insertion. The
helper stores the supplied password field unchanged and assigns the new user
the admin role, while authentication checks it with `crypt_ctx.verify`.
Use the stock hashing context to produce the bcrypt value.
[Startup](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L3577),
[bootstrap helper](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/users/permission.py#L184)

Authenticate through the pod's non-loopback API address and call the supported
`POST /users/service-account-tokens/rotate` endpoint for the existing backend
token ID, with `expires_in_days: 0`. Rotation preserves its service-account
identity and roles; it avoids moving existing jobs into a new account. Capture
the response directly to a protected file. Remove the temporary bootstrap
delivery after controlled administrative recovery.
[Rotation handler](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/users/server.py#L1001)

The replacement bearer must not masquerade as the lost credential revision.
For `local/skypilot-backend`, reserve an explicitly unavailable version 1 in the
fresh KV store (for example, write an empty object and soft-delete that version),
then publish the new token as version 2. Update manifest binding
`f89e9b32-dd67-441c-b486-ccb4f4cb8180` to revision 2 and new validation evidence.
Keep its existing path, service-account identity and backend selection UUID.
Check returned version numbers instead of assuming them.
[KV versioned write/delete API](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2)

There is no implemented enrollment/rotation HTTP API for this manifest; #72
owns that feature. The operator edits `qualification-bindings` ConfigMap's
`credential-bindings.json`. `CredentialBinding.revision` is the exact Vault KV
version. It is separate from Target Storage registration/configuration revisions
and immutable historical projection records. The backend selection environment
variable `SKYWRIGHT_CREDENTIALS_SKYPILOT_BINDING` stays the same UUID.
`VaultConfiguration` loads the manifest at startup, and
`BackendSkyPilotAuthorization` selects its recorded revision for each operation;
restart the backend after updating the manifest. No database rows need editing.
[Binding guide](../local-vault-bindings.md),
[authorization implementation](../../backend/src/main/java/de/zorro909/skywright/backend/credential/BackendSkyPilotAuthorization.java),
[manifest loading](../../backend/src/main/java/de/zorro909/skywright/backend/credential/VaultConfiguration.java)

Finally verify TLS, exact-version reads, denied wider access, retained Dataset
and Run records, complete authenticated SkyPilot requests, and actual managed
GPU progress. Existing failed or claimed submissions must not be replayed to
hide the interruption. No SkyPilot server/client SDK source modification is
needed for this recovery.

## Recovery performed

The main agent created `qualification-vault-recovered` and retained the old sealed
Vault PVC. The new Vault imports the nine recovered credential documents at
version 1 and issues separate, path-limited delivery tokens. Each token can
inspect and renew itself. Reads outside each role's credential paths return 403.

The supported SkyPilot bootstrap and token rotation preserved the existing
service-account identity and returned the two retained managed jobs. The new
backend bearer is version 2; version 1 is explicitly deleted and returns 404.
The binding manifest selects revision 2. The temporary bootstrap environment
and its delivery Secret were removed after rotation, and both services restarted.
The existing Dataset catalogue entry and earlier Run records remain readable.

Custody and new diagnostics now live under the protected persistent session
directory, with `/tmp/skywright-issue233` only a symlink. The committed tini image
passed all eight image checks and is deployed. Live inspection found no zombie
children parented by PID 1. A subsequent UI Run executed a real GPU Training Step
before a decimal delay error in the synthetic project; that project correction
is being published. A subsequent zero-delay Run passed the GPU checkpoint and
cancellation check recorded in the qualification report.
