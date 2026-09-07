# Isolated local AMD qualification environment

Read-only investigation on 2026-09-07. No cluster, container, host setting, credential or GitHub resource was changed. The commands below describe the next implementation steps; they have not been executed by this research task.

## Recommendation

Create a separately named rootless Podman kind cluster, `skywright-issue233`, with a dedicated kubeconfig file. Mount the host AMD devices into its single node, install a real AMD device plugin and a NetworkPolicy-capable CNI, then deploy the existing local-kind control plane with an additional qualification overlay for Vault, S3 storage and credential projections. Use the unchanged pinned SkyPilot 0.13.0 image. Prove device access in a GPU pod before spending time provisioning application data.

This is a viable candidate, not a proven end-to-end setup. GPU device allocation and a policy-enforcing CNI inside rootless kind remain qualification gates. Nothing observed proves that the host cannot run the workflow.

## Observed host state

These facts came from `command -v`, version commands, `podman info`, `podman ps -a`, image inspection, `kubectl config get-contexts`, `id`, `ls -l`, sysfs, `systemctl --user is-active podman.socket`, `free`, `df`, `getenforce` and `sysctl`.

| Item | Observation |
| --- | --- |
| Runtime | Rootless Podman, cgroup v2, systemd cgroup manager; user Podman socket active |
| Tools | kind 0.32.0, kubectl 1.36.3, Skaffold 2.24.0, rocminfo installed; no Helm, k3d or k3s found |
| Existing cluster | Only `kind-kind-cluster` context; its node container is stopped |
| Existing node mounts | `/var`, `/lib/modules`, `/dev/mapper`; no `/dev/kfd` or `/dev/dri` mounts |
| Devices | `/dev/kfd`, `/dev/dri/renderD128` and `renderD129` exist and are world-readable/writable; user belongs to video/render groups |
| Device identities | renderD128 vendor `0x1002`, device `0x744c`; renderD129 device `0x164e`; owner/root investigation reports RX 7900 XTX, gfx1100 |
| Resources | 61 GiB RAM, about 45 GiB available; no swap; about 2.1 TiB free on repository filesystem |
| Host restrictions | SELinux enforcing; delegated cpu/io/memory/pids controllers; inotify instances 384, watches 543973 |
| Existing node limits | PIDs limit 2048, privileged container, `unmask=all` |

Cached images include both production control-plane images, PostgreSQL, SeaweedFS 4.42, Vault 1.21.4, and `localhost/skywright-environment-profile:rocm-check`. The ROCm profile label records PyTorch 2.12.0, ROCm 7.14 and SDK 0.1.0, but its source revision is older than current work. Rebuild the project image with the SDK under test. [Pinned environment manifest](../../environment-profiles/manifest.json), [ROCm Containerfile](../../environment-profiles/rocm/Containerfile)

Restarting the stopped cluster would not add its missing AMD device mounts. A separate cluster also protects its retained control-plane data.

## Cluster and GPU gates

kind supports rootless Podman and documents cgroup v2, delegated systemd scope and possible Podman log-driver/PID issues. This host meets the observed cgroup/socket prerequisites. Use process-local configuration or a separate config file for qualification; do not append settings to the user's global container configuration. [kind rootless guide](https://kind.sigs.k8s.io/docs/user/rootless/)

Create a protected working directory and dedicated kubeconfig. A starting kind configuration is:

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  disableDefaultCNI: true
  podSubnet: 192.168.0.0/16
nodes:
  - role: control-plane
    extraMounts:
      - hostPath: /dev/kfd
        containerPath: /dev/kfd
      - hostPath: /dev/dri
        containerPath: /dev/dri
```

Check the proposed pod subnet against host routes before creating it. Do not relabel `/dev` or its device files. The exact outer-container device permissions and inner runtime behavior need the smoke test below. `extraMounts` is kind's supported host-to-node configuration mechanism. [kind configuration](https://kind.sigs.k8s.io/docs/user/configuration/)

```bash
KIND_EXPERIMENTAL_PROVIDER=podman \
  systemd-run --scope --user -p Delegate=yes \
  kind create cluster --name skywright-issue233 \
  --config /protected/issue233/kind.yaml \
  --kubeconfig /protected/issue233/kubeconfig

KUBECONFIG=/protected/issue233/kubeconfig \
  kubectl get nodes
```

Choose and record a pinned kind node image before executing this recipe. With the default CNI disabled, nodes remain NotReady until CNI installation; do not wait for node readiness before installing it. Calico publishes a kind installation recipe using this networking setup. Its documented baseline uses Docker, so rootless Podman compatibility here still needs verification. Prefer the ordinary iptables dataplane for this experiment rather than introducing an eBPF requirement. [Calico kind quickstart](https://docs.tigera.io/calico/latest/getting-started/kubernetes/quickstart)

The existing deployment requires NetworkPolicy enforcement. Stock kind networking alone is insufficient evidence that the SkyPilot API is isolated. Keep that gate and run the repository's denied-client check once the backend is deployed. [Deployment contract](../../deployment/README.md), [SkyPilot ingress policy](../../deployment/base/skypilot-api-server-network-policy.yaml)

The AMD device plugin can run directly as a DaemonSet when the host driver already exists; a driver-installing GPU Operator and cert-manager are not necessary merely to register devices. Its manifest mounts node sysfs and the kubelet device-plugin socket and runs privileged. Pin the inspected upstream manifest revision `565b3ccdb8f48d23fd14bf128646384e03582a5a` and resolve a concrete image digest instead of its untagged image. Ensure it tolerates the single node's control-plane taint if that taint is present. [AMD plugin source](https://github.com/ROCm/k8s-device-plugin), [inspected manifest](https://github.com/ROCm/k8s-device-plugin/blob/565b3ccdb8f48d23fd14bf128646384e03582a5a/k8s-ds-amdgpu-dp.yaml)

Check node `status.capacity` and `status.allocatable` for real `amd.com/gpu` resources. The host has two AMD render nodes; verify which devices the plugin registers and which device each allocation receives. Do not label a mixed pool as RX7900XTX if it also allocates the integrated GPU. This may require the plugin's supported discovery configuration or node device exposure to select the discrete GPU.

Then run a one-off pod using the pinned ROCm profile, requesting one `amd.com/gpu`, and require all of:

- PyTorch reports a HIP build and `torch.cuda.is_available()`.
- Device name is the discrete Radeon RX 7900 XTX and memory matches its actual capacity.
- A tensor matrix multiply and backward pass run on the GPU, synchronize and produce finite values.

The cached CPU managed-project fixture does not perform GPU work and cannot establish this evidence. [Current fixture](../../sdk/tests/support/managed_project/skywright_project.py)

## SkyPilot integration without SDK modifications

The locally cached wheel declares SkyPilot 0.13.0 and source commit `b1431e52d97c22e9bb8fa8b67f162543754ddaf5`. Inspection of `sky/provision/kubernetes/utils.py` shows that `get_gpu_resource_key` detects `amd.com/gpu` from node capacity. `sky/utils/accelerator_registry.py` permits custom Kubernetes accelerator names. Thus use the truthful label `skypilot.co/accelerator=rx7900xtx` and matching Skywright target `gpu-model: RX7900XTX`; do not label the GPU as MI300. AMD nodes require manual labels. [Pinned SkyPilot GPU resource selection](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/provision/kubernetes/utils.py), [accelerator names](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/utils/accelerator_registry.py), [SkyPilot AMD setup](https://docs.skypilot.co/en/v0.10.3/reference/kubernetes/amd-gpu.html)

```bash
KUBECONFIG=/protected/issue233/kubeconfig \
  kubectl label node skywright-issue233-control-plane \
  skypilot.co/accelerator=rx7900xtx

KUBECONFIG=/protected/issue233/kubeconfig \
  scripts/deploy local --context kind-skywright-issue233
```

The second command is the existing control-plane entry point. It creates distinct Skywright/SkyPilot database roles, persistent state and the loopback browser endpoint. It does not provision GPU support, Vault, S3, registry publications or target credentials. Its context preflight reads the active context from the supplied kubeconfig, so using a dedicated file preserves the user's normal kubeconfig. [Deployment CLI](../../deployment/skywright_deployment/cli.py), [local-kind overlay](../../deployment/overlays/local-kind/kustomization.yaml)

## Application prerequisites beyond the base deployment

Prepare a qualification overlay around the existing base and local database manifests. It must supply these real services and configurations:

1. A persistent non-dev Vault with KV v2 and a path-limited backend token file. Follow the existing initialization/unseal and binding manifest format. Generate and retain secrets only in protected local files; no credential values belong in repository manifests or command output. The production resolver requires HTTPS except for literal localhost addresses, so a separate `http://vault.skywright.svc:8200` Service is rejected. Use HTTPS with backend trust configured, or a genuine loopback connection. [Vault contract](../local-vault-bindings.md), [connection validation](../../backend/src/main/java/de/zorro909/skywright/backend/credential/VaultBindings.java)
2. S3-compatible storage, with separate Dataset and Run Store buckets and separate identities for backend, Dataset reader, training output writer and Transfer Worker. SeaweedFS 4.42 is cached and used in repository tests, but configure actual scoped IAM permissions and pass storage qualification through the public API. Anonymous or shared administrator credentials would bypass the production boundary. [Storage ADR](../adr/0008-address-run-stores-through-pre-registered-target-storages.md), [assembly integration fixture](../../backend/src/test/java/de/zorro909/skywright/backend/acceptance/LocalRunAssemblyIT.java)
3. A restricted Kubernetes service account for the training namespace, rendered as the existing single-user static-token JSON kubeconfig through Vault Agent. Use an API address reachable from the SkyPilot pod, such as the cluster-internal Kubernetes service with the correct embedded CA. Mount the mode-0400, uid-10002-owned projection into the SkyPilot server, runtime-pull helper and log collector, setting `SKYWRIGHT_KUBECONFIG` in each. The base overlay does not wire these mounts automatically. [Projection contract](../local-credential-projections.md), [server image contract](../../skypilot-api-server-deployment/README.md)
4. Backend Vault configuration, the exact SkyPilot service-account binding, and `skywright.local-run` target properties using the qualified context, real GPU memory and a finite GPU count. Storage defaults must be assigned for the local target class. [Local acceptance configuration](../reference/local-run-acceptance.md)
5. A real published GHCR Training Project Version containing the fixed `skywright_project.train(context)` GPU workload and current Skywright SDK. Production registration and registry authorization accept canonical `ghcr.io/...` repositories only; a local registry/preloaded image cannot replace that contract. Use an existing suitable publication or obtain authorization to publish the qualification project. Separate backend registry resolution and private target-pull bindings are required if private. [Project registration](../../backend/src/main/java/de/zorro909/skywright/backend/trainingproject/TrainingProjects.java), [registry authorization](../../backend/src/main/java/de/zorro909/skywright/backend/trainingproject/VaultRegistryAuthorization.java), [runtime entry point](../adr/0020-bind-one-run-context-to-each-training-process.md)
6. A small real Dataset Publication in the qualified Dataset bucket, created through the supported SDK/control-plane publication path, followed by Run creation and cancellation through the actual browser UI. Retain the accepted Run UUID, authoritative lifecycle observations, GPU execution evidence, final checkpoint reference and cancellation receipt. Seeded qualification additionally proves the child runs after predecessor storage access is removed.

## What is and is not proven

The immediate missing prerequisites are a running isolated cluster, AMD device registration, an enforced CNI, the additional service/projection overlay, and a published GPU project/version. Required basic deployment tools and several large images are already available. No host-level blocker is proven by read-only inspection.

SELinux, rootless device allocation, discovery of the integrated AMD GPU, and Calico inside rootless kind remain concrete tests, not established failures. Run the GPU and network-policy gates first. If either fails, capture the exact admission/runtime error before considering a host setting change or an alternative cluster runtime.

## Private GHCR publication and credential findings

Read-only checks found the active `gh` account is Zorro909 with `gist`, `read:org`, `repo` and `workflow` scopes. `gh api 'user/packages?package_type=container'` returns HTTP 403 with an explicit `read:packages` requirement. Standard Docker and Podman auth files were absent, and `GH_TOKEN`, `GITHUB_TOKEN`, `CR_PAT`, `REGISTRY_AUTH_FILE` and `DOCKER_CONFIG` were not set. Token values were not read or printed.

No suitable existing published project was verified. The visible training repository checked has no root `skywright-project.json`. `gh release list` for Skywright and the environment-profile release workflow history returned no entries. An anonymous Skopeo inspection of `ghcr.io/zorro909/skywright-environment:0.1.0-rocm` returned 403. That response does not distinguish a missing package from an inaccessible private package. Private package discovery remains blocked by the missing package scope.

The supported publishing interface is `.github/actions/publish-training-project`, pinned to a full Skywright commit. Its private Python implementation performs Docker image builds and OCI Distribution requests itself. ORAS is unnecessary for Training Project publication; local Skopeo and Buildah are installed, while ORAS was not found. The Action requires a real clean CI checkout and checks its reported source revision against `HEAD`. Do not simulate CI by setting provenance environment variables locally. [Action](../../.github/actions/publish-training-project/action.yml), [publication contract](../reference/training-project-version.md), [CI validation](../../.github/actions/publish-training-project/src/skywright_project_action/cli.py)

The smallest new project is a clean dedicated checkout containing:

- `skywright_project.py` with `train(context)`, actual GPU forward/backward work, registered checkpoint state, `context.start()` and a cooperative loop long enough to exercise cancellation.
- A project configuration contract and metric contract compiled against the Action's pinned SDK schema identities.
- A fully hashed dependency lock, possibly empty when the Environment Profile already supplies everything. The lock must not install Skywright.
- `skywright-project.json` with only the `rocm` backend, a real digest-pinned Environment Profile and a smoke command that can pass on a CPU CI runner. GPU execution is checked later in the qualified local pod.

The Action runs its smoke and fixed-entrypoint checks using plain `docker run`, without GPU allocation. Making the publication smoke require a GPU would fail ordinary GitHub-hosted publication. Keep import/schema/entrypoint validation in CI and require actual GPU work in the local Run. [Image builder](../../.github/actions/publish-training-project/src/skywright_project_action/oci.py)

Use a reviewed workflow in the qualification project's repository with these explicit permissions and Action inputs:

```yaml
permissions:
  contents: read
  packages: write
jobs:
  publish:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
        with:
          fetch-depth: 0
      - id: version
        uses: Zorro909/skywright/.github/actions/publish-training-project@FULL_REVIEWED_COMMIT_SHA
        with:
          definition: skywright-project.json
          registry-username: ${{ github.actor }}
          registry-password: ${{ secrets.GITHUB_TOKEN }}
```

Replace the placeholder with the exact source under qualification. The repository's default workflow permission is read, so `packages: write` must be explicit. A workflow can publish using its short-lived `GITHUB_TOKEN` without upgrading the local gh OAuth token. This still needs reviewed authorization for the new repository/workflow/package and its visibility. [GitHub Container registry authentication](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)

The project needs a reachable Environment Profile containing the current SDK. The cached profile is old, and no matching published profile was verified. The existing official release workflow is triggered by `profile-v*` tags and validates release/main authority. Do not create an official release tag merely to work around qualification. Resolve an authorized CI-built qualification profile by digest, or use an existing suitable published profile after authenticating. A local-only profile image cannot be pulled by a GitHub-hosted build. [Environment release workflow](../../.github/workflows/environment-profile-release.yml), [profile release policy](../../environment-profiles/release_support.py)

The Action's final `artifact-digest` is the OCI manifest digest the backend selects. Its separate `manifest-digest` identifies the canonical version document body; do not substitute one for the other. It publishes image-derived immutable contract artifacts and writes the complete version last. [Publication sequence](../../.github/actions/publish-training-project/src/skywright_project_action/publication.py)

Publishing does not supply ongoing private runtime access. Provision these separate consuming roles in exact Vault KV revisions:

| Use | Credential contract |
| --- | --- |
| CI publication | Workflow `GITHUB_TOKEN`, explicit `packages: write`, access to the private Environment Profile if needed |
| Backend version resolution | GHCR `backend-resolver`, exact `ghcr.io/owner/project`, read-only package identity |
| Kubernetes image pulls | Separate GHCR `execution-target-pull`, same exact repository, read-only identity; projected into immutable Run-owned pull Secrets |

Outside Actions, GitHub documents classic PAT authentication with `read:packages` for private downloads. Vault binding metadata must describe actual distinct resource identities and paths; the resolver rejects two bindings with the same resource and identity. A short-lived publishing token must not be reused as a non-expiring runtime binding. The current local Run path requests `Instant.MAX`, so runtime credentials must honestly meet its declared non-expiring contract. [GitHub authentication](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry), [Vault binding validation](../../backend/src/main/java/de/zorro909/skywright/backend/credential/VaultBindings.java), [projection validity](../../backend/src/main/java/de/zorro909/skywright/backend/credential/LocalCredentialProjections.java)

No existing eligible private GHCR reader credentials were found by the bounded inspection. Package publication may use CI authority, but private local execution remains blocked until the operator supplies suitable reader identities or identifies an already provisioned exact binding. Do not request tokens in chat; use the protected local/Vault delivery contract.
