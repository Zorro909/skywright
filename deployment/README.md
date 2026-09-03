# Deployment

Skywright uses one `skaffold.yaml` with a shared Kustomize base and two explicit profiles. The
base deploys the backend and SkyPilot API server as separate Deployments. The backend reaches
SkyPilot only through the `skywright-skypilot-api-server` ClusterIP Service on port 46580. Only the
backend pod may connect to that Service, enforced by a NetworkPolicy. Only the backend receives an
Ingress or local loopback port forward. This slice does not provision kind, a NetworkPolicy-capable
CNI, production PostgreSQL, Vault, Contour, DNS, private networking, or cloud-provider credentials.

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
Skywright and SkyPilot databases and roles. An idempotent local provisioner also adds or repairs
the SkyPilot role and database when the PostgreSQL volume predates this deployment. The command
also creates
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

The system test runs the canonical deployment contract suite first, including the rendered-profile,
release-support, and operator-command checks. Skaffold then builds both production images through
their normal custom builders, which run the backend bridge and packaged-process suites. The test
waits for PostgreSQL, SkyPilot, and the backend. It checks the exact image pairing, proves another
pod cannot reach SkyPilot, invokes the packaged Orchestrator during saturation and dependency loss,
tests independent pod replacement, retained server authority, both database failure modes, ordinary
cleanup, and the expanded reset. It creates test records only in the named local control-plane state
and finishes by resetting that state.

## Operating the control plane

Local startup first creates or reuses the Namespace, database Secrets, and persistent volume
claims. Skaffold applies PostgreSQL and its SkyPilot database provisioner with the two application
Deployments. The backend init container waits for PostgreSQL. SkyPilot validates its database and
retained paths before it starts. Skaffold reports success only after PostgreSQL, the provisioner,
SkyPilot `/api/health`, and backend `/readyz` are ready.

The two application pods are independent. Use these commands for a routine replacement and wait
for each command to finish before diagnosing the result:

```bash
kubectl --context <context> --namespace skywright \
  rollout restart deployment/skywright-backend
kubectl --context <context> --namespace skywright \
  rollout status deployment/skywright-backend --timeout=10m

kubectl --context <context> --namespace skywright \
  rollout restart deployment/skywright-skypilot-api-server
kubectl --context <context> --namespace skywright \
  rollout status deployment/skywright-skypilot-api-server --timeout=10m
```

Replacing the backend must not replace SkyPilot. Replacing SkyPilot must not replace the backend.
SkyPilot reconstructs database-backed authority from its separate database and reads submitted-file
and log state from `skywright-skypilot-state`. Kubernetes gives each application 30 seconds to stop.
The backend bridge drains for at most 5 seconds, and the SkyPilot server uses a 10-second internal
grace period.

Use the following signals when diagnosing an outage:

- Backend `/livez` reports whether the Java process can serve diagnostics. Backend `/readyz` and
  `/actuator/health` also require the validated Skywright PostgreSQL connection and compatible
  Liquibase history.
- SkyPilot `/api/health` is cluster-internal. Query it with `kubectl exec` or inspect the pod probes.
  Its `version` and `version_on_disk` values must equal the `io.skywright.skypilot.version` label on
  both application images.
- SkyPilot loss does not change backend liveness or readiness. Backend logs report either the safe
  reachability diagnostic or the exact-version mismatch. Calls through the internal Orchestrator
  port return `skypilot-unavailable`. The periodic availability probe reconnects after the paired
  server returns, without a backend restart.
- `bridge-busy` means a bounded bridge lane is saturated. It is not a SkyPilot availability failure
  and does not change backend readiness.
- Skywright PostgreSQL loss keeps `/livez` up but makes `/readyz` and aggregate health return 503.
  Database-backed HTTP calls return a bounded `SKYWRIGHT_CAPABILITY_UNAVAILABLE` problem naming
  PostgreSQL. Readiness returns only after connectivity and schema compatibility both pass.
- SkyPilot database loss does not transfer authority to the backend. The server may become
  unavailable or fail affected requests. Restore the same database and role, then confirm retained
  users or operations through a supported SkyPilot API before replacing any volume.

For production, restore missing operator-owned state before applying a bundle. Use the immutable
bundle command documented below for both forward apply and rollback. Do not edit one Deployment to
combine images from different bundles. If pairing fails, apply one previously verified bundle by
digest. The command validates both image attestations and every state prerequisite before mutation.

The SkyPilot NetworkPolicy admits TCP port 46580 only from pods labelled as the backend. The Service
has no Ingress, NodePort, host port, or loopback forward, but it currently has no API authentication.
The cluster must use a CNI that enforces Kubernetes NetworkPolicy. Neither application receives
provider or registry credentials yet. Issues #72 and #73 own those credential and authentication
paths.

## Deferred dependency loss

This release qualifies only dependencies that now exist in the deployed control plane. It does not
add placeholder endpoints, a fake Run Lifecycle State, or cancellation based on lost visibility.
The remaining dependency-loss rows stay with their owning work:

- Run Store loss starts with [#41](https://github.com/Zorro909/skywright/issues/41). Its consumers
  retain their own failure tests, including checkpoint publication in
  [#46](https://github.com/Zorro909/skywright/issues/46), metric persistence in
  [#60](https://github.com/Zorro909/skywright/issues/60), repatriation in
  [#53](https://github.com/Zorro909/skywright/issues/53), Metric Views in
  [#61](https://github.com/Zorro909/skywright/issues/61), and policy enforcement in
  [#70](https://github.com/Zorro909/skywright/issues/70).
- Provider and registry loss belongs to
  [#57](https://github.com/Zorro909/skywright/issues/57).
- Transfer Worker loss belongs to [#53](https://github.com/Zorro909/skywright/issues/53).
- Metric View loss belongs to [#61](https://github.com/Zorro909/skywright/issues/61).
- Runtime or Cost Ceiling unenforceability belongs to
  [#70](https://github.com/Zorro909/skywright/issues/70).

The whole AMD host is one accepted failure domain. Losing it removes local execution and every
co-located control-plane capability, including the backend, SkyPilot, Transfer Workers, and Metric
Views. Existing cloud compute may continue, but Skywright cannot promise to stop, observe, recover,
or ceiling-enforce it while the host is absent.

## Production

Create the `skywright` Namespace, a `contour` IngressClass, and private DNS for
`skywright.internal` before applying a release. Production also requires these operator-owned
database and state resources:

- `skywright-production-database` Secret with `migrationUrl`, `migrationUsername`,
  `migrationPassword`, `runtimeUrl`, `runtimeUsername`, and `runtimePassword`
- `skywright-production-skypilot-database` Secret with the `connectionUri` key for a separate
  SkyPilot database and role on the operator-managed PostgreSQL service
- `skywright-skypilot-state` PVC for SkyPilot logs and submitted-file staging

The Skywright migration role owns the `skywright` schema and needs `CONNECT` and `CREATE` on its
database so Liquibase can install the pinned `btree_gist` extension. The runtime role needs only
`CONNECT` on the database plus its schema and object privileges. PostgreSQL must make the extension
available to the migration role before apply.

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

CI builds the backend and SkyPilot API server from one commit and the `skypilot.version` pin in the
root Maven project. Each release bundle records that commit, the exact SkyPilot version, and both
image digests. CI also creates separate attestations for the bundle and each image. Publication
uses collision-checked version aliases in all three GHCR repositories. An interrupted publication
can fill a missing alias on retry, but it cannot replace different content already recorded for
that release version.

Apply or roll back by passing an immutable bundle digest through the same command:

```bash
scripts/deploy apply \
  ghcr.io/zorro909/skywright-deployment@sha256:<digest> \
  --context <production-context>
```

The command verifies the bundle attestation, both image attestations, image identities, and payload
checksums before reading production prerequisites or mutating Kubernetes. It reports the prior
bundle digest, applies the supplied `release.yaml` without rebuilding or rendering, and waits for
Skaffold status checking. Only then does it record the selected digest on both Deployments. A
failed apply leaves the prior records alone. Reapplying a bundle is idempotent, and rollback uses
the same path. Prereleases need the explicit `--allow-prerelease` option.

Retained schema-v1 bundles contain only the backend image. They may be applied only before the
SkyPilot Deployment exists, and the operator records their digest only on the backend. Once the
SkyPilot Deployment exists, the operator rejects schema v1 before apply. Retained schema-v2 bundles
remain paired rollback inputs. Schema v3 requires the current manifest layout, including the exact
SkyPilot image in both its database-wait init container and server container.

Published bundles, backend images, and SkyPilot API-server images are immutable rollback inputs and
must remain in GHCR indefinitely. Repository automation does not delete published release
artifacts. Failed, untagged intermediates may be removed after 30 days only after proving no
published bundle references them.
