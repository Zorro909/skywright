# Deployment

Skywright uses one `skaffold.yaml` with a shared Kustomize base and two explicit profiles. The
base deploys the backend and SkyPilot API server as separate Deployments. The backend reaches
SkyPilot only through the `skywright-skypilot-api-server` ClusterIP Service on port 46580. Only the
backend receives an Ingress or local loopback port forward. This slice does not provision kind,
production PostgreSQL, Vault, Contour, DNS, private networking, or cloud-provider credentials.

## Local kind

Install Skaffold 2.24.0, kubectl, kind, and rootless Podman. Start the Docker-compatible rootless
Podman socket and select an existing Podman-backed kind context. The workflow refuses another
Skaffold version, Docker-backed clusters, non-kind contexts, non-amd64 hosts, and clusters without
one default dynamic StorageClass.

```bash
scripts/deploy local --context kind-kind-cluster
```

The command creates the `skywright` Namespace if needed. It generates the backend database Secret
`skywright-local-database` and the separate SkyPilot database Secret
`skywright-local-skypilot-database` once. The pinned PostgreSQL workload initializes distinct
Skywright and SkyPilot databases and roles. The command also creates
`skywright-postgresql-data` and `skywright-skypilot-state` PVCs through the cluster's default
StorageClass. The latter holds SkyPilot logs and submitted-file staging under
`/var/lib/skypilot`. All four resources survive normal Skaffold cleanup and redeployment.

The backend is available at `http://127.0.0.1:8080` only while the command runs. The SkyPilot API
server stays cluster-internal and is not forwarded to the workstation.

Reset the retained local control-plane state with:

```bash
scripts/deploy reset-local-state \
  --context kind-kind-cluster \
  --confirm 'reset skywright local control-plane state'
```

The old database-only confirmation is rejected. The reset command verifies the kind context,
local Namespace marker, exact resource names, and ownership labels. It deletes only
`skywright-postgresql-data`, `skywright-skypilot-state`, `skywright-local-database`, and
`skywright-local-skypilot-database`. It never deletes the cluster, Namespace, or unrelated
resources.

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

Create the `skywright` Namespace, a `contour` IngressClass, and private DNS for
`skywright.internal` before applying a release. Production also requires these operator-owned
database and state resources:

- `skywright-production-database` Secret with `migrationUrl`, `migrationUsername`,
  `migrationPassword`, `runtimeUrl`, `runtimeUsername`, and `runtimePassword`
- `skywright-production-skypilot-database` Secret with the `connectionUri` key for a separate
  SkyPilot database and role on the operator-managed PostgreSQL service
- `skywright-skypilot-state` PVC for SkyPilot logs and submitted-file staging

The production preflight checks the two Secrets' exact key sets and the PVC before Skaffold can
apply anything. It never prints Secret values. Kustomize references the SkyPilot Secret and PVC but
does not render, create, adopt, mutate, or delete them. Keep them across application release,
rollback, and removal. Do not put secret values in the repository or a Deployment Bundle.
Production deploys no database workload or storage resource.

Anyone who can reach `http://skywright.internal` receives full Skywright control through the
built-in Principal Identity. Plain HTTP is intentional only inside the operator-controlled private
network. It is not an authentication boundary.

The SkyPilot server currently runs without API authentication and receives no provider, registry,
storage, or Kubernetes launch credential. This child does not yet authorize managed launches.
Issues #72 and #73 own the Vault-backed credential and authentication path required before real
managed launches are enabled.

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

Published bundles, backend images, and SkyPilot API-server images are immutable rollback inputs and
must remain in GHCR indefinitely. Repository automation does not delete published release
artifacts. Failed, untagged intermediates may be removed after 30 days only after proving no
published bundle references them.
