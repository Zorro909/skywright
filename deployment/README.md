# Deployment

Skywright uses one `skaffold.yaml` with a shared Kustomize base and two explicit profiles. The
current slice deploys the backend and PostgreSQL boundary only. It does not provision kind,
production PostgreSQL, Vault, Contour, DNS, or private networking.

## Local kind

Install Skaffold 2.24.0, kubectl, kind, and rootless Podman. Start the Docker-compatible rootless
Podman socket and select an existing Podman-backed kind context. The workflow refuses another
Skaffold version, Docker-backed clusters, non-kind contexts, non-amd64 hosts, and clusters without
one default dynamic StorageClass.

```bash
scripts/deploy local --context kind-kind-cluster
```

The command creates the `skywright` Namespace if needed. It generates database credentials once
and creates a PVC without choosing a StorageClass. Both resources survive normal Skaffold cleanup.
The backend is available at `http://127.0.0.1:8080` only while the command runs.

Reset only the retained local database resources with:

```bash
scripts/deploy reset-local-database \
  --context kind-kind-cluster \
  --confirm 'reset skywright local database'
```

The reset command verifies the kind context and ownership labels, then deletes only
`skywright-postgresql-data` and `skywright-local-database`. It never deletes the cluster,
Namespace, or unrelated resources.

To follow the exact head of one branch from the fixed `origin` remote:

```bash
scripts/deploy follow main --context kind-kind-cluster
```

The supervisor owns a detached temporary worktree, accepts and reports force pushes, and removes
the worktree after Skaffold cleanup. Add `--keep-worktree` to retain it for diagnosis.

The real integration check requires the same existing cluster and intentionally fails when a
prerequisite is absent:

```bash
deployment/scripts/system-test --context kind-kind-cluster
```

## Production

Create the `skywright` Namespace, a `contour` IngressClass, private DNS for
`skywright.internal`, and the `skywright-production-database` Secret before applying a release.
Vault remains the source of the Secret values. It must contain these keys:

- `migrationUrl`, `migrationUsername`, and `migrationPassword`
- `runtimeUrl`, `runtimeUsername`, and `runtimePassword`

Do not put secret values in the repository or a Deployment Bundle. Production deploys no database
workload or storage.

Anyone who can reach `http://skywright.internal` receives full Skywright control through the
built-in Principal Identity. Plain HTTP is intentional only inside the operator-controlled private
network. It is not an authentication boundary.

CI publishes release bundles to `ghcr.io/zorro909/skywright-deployment`. Apply or roll back by
passing an immutable digest through the same command:

```bash
scripts/deploy apply \
  ghcr.io/zorro909/skywright-deployment@sha256:<digest> \
  --context <production-context>
```

The command verifies the GitHub attestation and payload checksums before reading production
prerequisites or mutating Kubernetes. It reports the prior bundle digest, waits for Skaffold status
checking, then records the successfully applied digest on the backend Deployment. Prereleases need
the explicit `--allow-prerelease` option.

Published bundles and backend images are immutable rollback inputs and must remain in GHCR
indefinitely. Repository automation does not delete published release artifacts. Failed, untagged
intermediates may be removed after 30 days only after proving no published bundle references them.
