# Operator-prepopulated local Credential Bindings

This is the local delivery slice in #228 under [ADR 0025](adr/0025-centralize-managed-credentials-in-vault.md).
Vault KV v2 is the only managed secret source. The backend reads an explicit version, never `latest`, and
never copies values into application persistence. Enrollment, promotion and rotation remain in #72;
Local Run projection and release are documented in [local Credential Projections](local-credential-projections.md);
the remaining role/provider matrix stays in #73.

## Persistent Vault operation

Run a separately operated Vault server, with a persistent filesystem mounted at `/vault/file` and a
configuration such as the following. The integration test uses `hashicorp/vault:1.21.4`.

```hcl
storage "file" { path = "/vault/file" }
listener "tcp" {
  address = "127.0.0.1:8200"
  tls_disable = true
}
api_addr = "http://127.0.0.1:8200"
disable_mlock = true
```

This listener is for a same-host private control plane. Use TLS with a trusted certificate when Vault
is on another host or reached across the deployment network. Configure the JVM trust store for that
CA. The backend rejects remote HTTP endpoints, redirects, URL credentials and non-root base paths.
Do not use Vault dev mode or put `/vault/file` on ephemeral storage. With mlock disabled, disable host
swap or encrypt it. Restrict the directory to the Vault operating-system identity.

1. Start `vault server -config=/etc/vault/local.hcl`. Set `VAULT_ADDR` to the listener.
2. Initialize once with `vault operator init`, using the deployment's chosen key shares and threshold.
   Capture the output directly in the operator's secure custody system. Keep unseal shares separate
   from both the data backup and Skywright. Do not paste initialization output into logs or issues.
3. Run `vault operator unseal` interactively for enough shares. Never put shares in command arguments.
4. Authenticate interactively with `vault login`. Enable KV v2 with
   `vault secrets enable -path=skywright kv-v2`.
5. Create a distinct external identity for each row below, verify its permitted operations and denied
   broader access at the external service, and record that evidence in the non-secret manifest.
   Import each secret from a protected JSON file using `vault kv put -mount=skywright local/backend @/protected/backend.json`.
   Keep command tracing off. Delete temporary import files after the operator's secure copy is confirmed.
6. Give the backend a non-root Vault token whose policy grants `read` only on the exact
   `skywright/data/local/...` entries it needs. Do not grant write, list or metadata administration.
   Deliver the token through a read-only, mode-0600 file. The token is reread for each request so an
   operator can replace it. Vault ACLs apply to paths; the backend additionally enforces exact versions.
7. After restarting Vault against the same directory, unseal it again. While sealed, dependent
   bindings report `unavailable`. Existing projected processes do not call this resolver.

For backup, quiesce writers and seal or stop Vault, then take a consistent copy of the entire persistent
storage directory. Store it securely off-host. Restore into an empty directory with the same ownership
and server configuration, start Vault, then unseal using the original shares. Restore the matching
non-secret binding manifest and operator-managed token delivery. Readiness must verify each recorded
revision before accepting new work. Never switch to a newer version to hide a missing backup revision.
A restored Vault backup cannot undo provider-side revocation or extend an expired external identity.
Keep old versions in Vault for every recorded consumer; KV retention and deletion must be operated explicitly.

`VaultPersistenceIT` initializes a real non-dev filesystem Vault, uses a path-limited read token,
restarts sealed, unseals, destroys an exact revision, and restores the storage backup. It verifies the
application resolver before and after each transition. The single-share key and in-container backup
are test fixtures only.

## Backend configuration

Set these non-secret Spring properties, or their environment-variable equivalents:

```properties
skywright.credentials.vault.address=http://127.0.0.1:8200
skywright.credentials.vault.mount=skywright
skywright.credentials.vault.token-file=/run/skywright/vault-token
skywright.credentials.vault.bindings-file=/etc/skywright/credential-bindings.json
```

The bindings file contains an array of objects like this. `revision` is the exact KV version.
`path` is relative to the mount, without `/data/`. Use identifiers and scope descriptions here,
never tokens, signed URLs, connection strings or credential-file contents.

```json
[
  {
    "id": "00000000-0000-0000-0000-000000000001",
    "revision": 2,
    "path": "local/backend-storage",
    "kind": "S3",
    "resource": "local-run-output-storage",
    "role": "backend",
    "identity": "skywright-backend-observer",
    "scope": "run-output-bucket/projects/example/",
    "accessProfile": "observe-control-delete",
    "validatedAt": "2026-09-05T00:00:00Z",
    "validUntil": null,
    "nonExpiring": true
  }
]
```

IDs, Vault paths and identities within one external resource must be distinct. `validatedAt` records
operator verification of this exact version against the service, including required operations and
scope. The resolver checks the declaration, secret shape, exact returned version and external expiry;
it does not claim that reading KV proves provider authorization. Storage qualification and actual
registry operations still check the external service. After provider permissions change, revalidate
the evidence. An externally rejected credential is never replaced with a broader identity.

| Kind | Role | Required secret JSON fields | Operator-verified scope |
| --- | --- | --- | --- |
| S3 | backend | accessKeyId, secretAccessKey | Observation, archive, control and deletion prefixes |
| S3 | training-process | accessKeyId, secretAccessKey | Separate bindings for Dataset read and project Run Store read/write/delete |
| S3 | transfer-worker | accessKeyId, secretAccessKey | Explicit transfer source read/delete and destination read/write scope |
| S3 | metric-view | accessKeyId, secretAccessKey | Read-only access to the selected Run metric segments |
| GHCR | backend-resolver | username, token | Exact `ghcr.io/owner/project` resource, read-only packages |
| GHCR | execution-target-pull | username, token | Separate read-only identity for image pulls |
| KUBERNETES | skypilot-api-server | kubeconfig | Local cluster and namespace permissions for SkyPilot |
| SKYPILOT | backend | token | SkyPilot API service-account operations |

S3 additionally accepts a non-empty `sessionToken` only with a finite `validUntil`. Kubernetes accepts
an embedded JSON kubeconfig string with exactly one cluster, context and static token user, embedded CA
data and a server matching `resource`. It rejects exec authentication, user credential files and
multiple contexts. Resolve other kinds through `VaultBindings.resolve` for the named consuming role;
that transient handoff is not itself a Run projection. The managed projection work must preserve
ADR 0025's direct Vault-to-SkyPilot boundary and keep Vault tokens out of Training Processes.

The backend's existing storage readiness and credential interfaces use Vault when configured. Backend
storage access refuses Training Process identities. Transfer operations can resolve only their exact
Transfer Worker binding, never a backend binding. Registry readiness checks both separate bindings;
private GHCR resolution uses the registered resolver ID and exact repository, then exchanges its
credential for a repository-scoped pull token, reused only within one registry operation. No token or provider error body enters diagnostics.

`nonExpiring=true` is an operator assertion about the external identity, not a Vault property. Otherwise
supply `validUntil`, later than `validatedAt`, and choose validity sufficient for the intended Run and
recovery window. KV storage never renews provider credentials. This slice supports static operator
identities; provider renewal, dynamic issuance and hot replacement of running projections are absent.
Storage views and admission reevaluate binding readiness without changing registration revisions or
qualification evidence. Readiness is evaluated at access time, with no stale-secret fallback. Missing, invalid, expired and
unavailable states affect only the requested binding. An absent Vault configuration preserves the
existing missing-binding behavior for unconfigured deployments.

## Verification

From the repository root, with the container service available:

```bash
mvn -pl backend -am -DskipFrontendInstall=true -DskipFrontendTests=true \
  -Dtest=VaultBindingsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=VaultPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

The implementation follows Vault's [versioned read API](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2)
and [filesystem storage contract](https://developer.hashicorp.com/vault/docs/configuration/storage/filesystem).
Registry token exchange follows the [Distribution token protocol](https://distribution.github.io/distribution/spec/auth/token/).
