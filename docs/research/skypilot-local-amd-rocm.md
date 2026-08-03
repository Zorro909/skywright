# Is a local AMD/ROCm box a first-class SkyPilot target?

## Short answer

**Yes through a self-managed Kubernetes cluster, but not through every mechanism that SkyPilot calls “local” or “existing machines.”** In current stable SkyPilot, an on-prem Kubernetes context with the AMD GPU Operator, an `amd.com/gpu` allocatable resource, and a `skypilot.co/accelerator` node label is a supported target. The documented example requests `MI300`, and the task can pin the exact local context with `resources.infra: k8s/<context>`.

The alternatives do not provide the same result:

- The old `Local` cloud is no longer registered in SkyPilot.
- `sky local up` creates a one-node kind cluster explicitly documented as having no GPU support.
- SSH Node Pools are current and first-class for existing machines, but the v0.13.0 bootstrap detects only `nvidia-smi` and installs only NVIDIA's GPU Operator. The docs likewise tell GPU users to verify `nvidia-smi`. Therefore an AMD box is **not a documented/supported turnkey SSH Node Pool GPU path**. An administrator could instead configure Kubernetes and AMD support directly, which returns to the supported Kubernetes path.

SkyPilot v0.13.0 can express GPU count and, in source, exact/minimum VRAM plus an optional manufacturer (for example `amd:192GB:1`). This is less general than requirement T3: the VRAM syntax expands through a static catalog of device names, it is not documented in the v0.13.0 YAML reference, and throughput is not a resource dimension. A custom local AMD label will only match a capability-shaped request if the expansion produces that same model name.

The `setup` and `run` portions of one task can be shared between ROCm and CUDA. The resource envelope cannot be fully target-blind: ROCm and CUDA need different runtime images, and SkyPilot's AMD example explicitly selects a ROCm image. `resources.any_of` or `resources.ordered` can put target-specific `infra`, `image_id`, and accelerator overrides into one task document, but those differences remain visible in the definition (or must be injected by Skywright).

Finally, capacity failure is explicit **only when the search space is pinned**. With `infra: k8s/<local-context>`, an unschedulable pod times out and the launch ends in `ResourcesUnavailableError`; it cannot silently jump to a cloud excluded from the resource candidates. With `infra: k8s`, multiple allowed contexts are tried, and with broader/multiple candidates SkyPilot deliberately auto-fails over. T6 therefore requires Skywright to construct a local-only candidate set when the local target is requested.

## Version and evidence scope

- Investigated on **2026-08-04**.
- Current stable release inspected: [SkyPilot v0.13.0](https://github.com/skypilot-org/skypilot/releases/tag/v0.13.0), released 2026-07-22, source commit [`b1431e52`](https://github.com/skypilot-org/skypilot/tree/b1431e52d97c22e9bb8fa8b67f162543754ddaf5).
- Accelerator catalog inspected at first-party commit [`9b636612`](https://github.com/skypilot-org/skypilot-catalog/commit/9b6366125868b55cc1615d9c6496e210d246593b), committed 2026-08-03.
- “Verified” below means directly stated by current official documentation or implemented/tested in those first-party source snapshots. “Inference” identifies consequences not promised verbatim by SkyPilot.

## 1. Ways to attach local hardware

### On-prem Kubernetes: current, supported, and AMD-capable

SkyPilot's Kubernetes setup guide explicitly supports on-prem clusters built with kubeadm, RKE2, K3s, or other distributions. For AMD GPUs, the cluster must expose `amd.com/gpu`, run the AMD GPU Operator, and label each GPU node with a type. The dedicated AMD guide demonstrates:

```text
amd.com/gpu: 8
skypilot.co/accelerator=mi300
resources:
  infra: k8s/<context>
  accelerators: MI300:1
```

It then shows `sky gpus list --infra kubernetes` reporting `MI300` quantities and utilization. This is the strongest first-party evidence that AMD is a supported local target rather than merely an accidental Kubernetes passthrough. (✓ VERIFIED: [AMD GPUs on Kubernetes](https://docs.skypilot.co/en/latest/reference/kubernetes/amd-gpu.html), [Kubernetes cluster setup](https://docs.skypilot.co/en/latest/reference/kubernetes/kubernetes-setup.html))

Kubernetes support consumes an existing cluster; SkyPilot does not add physical nodes. A task can pin the exact context using `resources.infra: k8s/<context-name>`. (✓ VERIFIED: [Kubernetes getting started FAQ](https://docs.skypilot.co/en/latest/reference/kubernetes/kubernetes-getting-started.html), [YAML `resources.infra`](https://docs.skypilot.co/en/latest/reference/yaml-spec.html#resources-infra))

### SSH Node Pools: current, but the turnkey GPU bootstrap is NVIDIA-only

SSH Node Pools are a current first-class infrastructure type. `sky ssh up` accepts existing Debian-based machines reachable by SSH, installs a K3s cluster on them, and exposes the pool as `ssh/<pool-name>`. The API-server host needs SkyPilot and `kubectl`; remote nodes need SSH access, peer access on port 6443, a Debian-based OS, and must not already belong to Kubernetes. (✓ VERIFIED: [Deploy SkyPilot on existing machines](https://docs.skypilot.co/en/latest/reservations/existing-machines.html), [`deploy.py` K3s bootstrap](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/ssh_node_pools/deploy/deploy.py#L478-L550))

However, v0.13.0's bootstrap checks for GPUs solely by invoking `nvidia-smi`, and when a GPU is detected it installs the NVIDIA GPU Operator and waits for `nvidia.com/gpu`. There is no parallel ROCm/AMD detection or AMD operator installation in this path. The SSH guide's own GPU prerequisite also says to verify with `nvidia-smi`. (✓ VERIFIED: [`check_gpu()`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/ssh_node_pools/deploy/utils.py#L141-L175), [NVIDIA operator bootstrap](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/ssh_node_pools/deploy/deploy.py#L918-L946), [SSH Node Pool prerequisites](https://docs.skypilot.co/en/latest/reservations/existing-machines.html#details-prerequisites))

**Inference:** an AMD host may be usable only after manually supplying equivalent K3s/Kubernetes AMD plumbing, but SkyPilot does not document that as an SSH Node Pool workflow. The supported, lower-risk description is “on-prem Kubernetes target,” not “turnkey AMD SSH Node Pool.”

### `sky local up`: current command, unsuitable for the GPU box

`sky local up` is current, but it creates a one-node kind cluster inside Docker for local development. Its documentation explicitly says kind in this configuration does not support GPUs and is not recommended for production. (✓ VERIFIED: [local Kubernetes deployment](https://docs.skypilot.co/en/latest/reference/kubernetes/kubernetes-deployment.html#deploying-locally-on-your-laptop))

### Legacy `Local` cloud: removed, not a target to build on

The v0.13.0 cloud registry excludes `local`; its compatibility comment says the Local cloud has been removed from the registry so old persisted state does not break lookups. No current YAML `infra` schema offers `local` as a cloud. (✓ VERIFIED: [cloud registry source](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/utils/registry.py#L120-L128), [current infra schema](https://docs.skypilot.co/en/latest/reference/yaml-spec.html#resources-infra))

## 2. AMD names and capability-shaped resources

### Device names on Kubernetes

SkyPilot schedules Kubernetes GPUs using the node's type label. For AMD, administrators manually set `skypilot.co/accelerator=<lowercase-name>`; the official example uses `mi300`, which is requested as `MI300`. Thus the local name is administrator-defined rather than a ROCm capability identifier. The Kubernetes catalog discovers names and requestable quantities from the cluster. (✓ VERIFIED: [GPU labels](https://docs.skypilot.co/en/latest/reference/kubernetes/kubernetes-setup.html#setting-up-gpu-labels), [AMD example](https://docs.skypilot.co/en/latest/reference/kubernetes/amd-gpu.html#launch-a-cluster-with-skypilot))

The global v8 catalog currently names the AMD devices `MI300X` (192 GiB), `Radeon MI25` (16 GiB), and `Radeon Pro V520` (8 GiB); only `MI300X` is presently listed as a RunPod offering. This catalog is not an exhaustive whitelist for custom Kubernetes labels, but it is the input used for VRAM/manufacturer expansion. (✓ VERIFIED: [metadata.csv](https://github.com/skypilot-org/skypilot-catalog/blob/9b6366125868b55cc1615d9c6496e210d246593b/catalogs/v8/common/metadata.csv), [accelerators.csv](https://github.com/skypilot-org/skypilot-catalog/blob/9b6366125868b55cc1615d9c6496e210d246593b/catalogs/v8/common/accelerators.csv))

### Count, VRAM, and manufacturer

GPU count is a documented resource property: `<name>:<count>`. In v0.13.0 source, the same `accelerators` string parser also accepts:

- exact memory: `192GB` or `192GB:1`;
- minimum memory: `24GB+`;
- manufacturer plus memory and optional count: `amd:192GB:1`.

The parser expands these expressions to concrete names through `get_devices_by_memory()`. First-party optimizer tests cover exact/minimum memory, count, unit conversion, and manufacturer filtering. (✓ VERIFIED: [`Resources._parse_accelerators_from_str`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/resources.py#L2394-L2446), [`get_devices_by_memory`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/utils/accelerator_registry.py#L50-L74), [optimizer tests](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/tests/test_optimizer_dryruns.py#L860-L938))

Important limits:

- The v0.13.0 YAML reference documents only device-name/count forms, not the VRAM/manufacturer syntax. This makes the latter implemented and tested but not a stable documented contract. (✓ VERIFIED: [YAML accelerators reference](https://docs.skypilot.co/en/latest/reference/yaml-spec.html#resources-accelerators))
- Expansion is static-catalog-based. `amd:192GB:1` expands to `MI300X:1`; it will not match a local node labeled `mi300` unless the label/name and expanded model agree. (✓ VERIFIED mechanism; consequence is an inference.)
- SkyPilot has CPU count, host memory, accelerator count/type, and max hourly cost fields, but no minimum-throughput resource field. (✓ VERIFIED absence from the [v0.13.0 YAML resource schema](https://docs.skypilot.co/en/latest/reference/yaml-spec.html#resources))

Therefore T3 is only partially expressible natively: GPU count yes; VRAM yes with catalog/name caveats; minimum throughput no.

## 3. One definition across local ROCm and cloud CUDA

SkyPilot's task abstraction keeps `setup` and `run` independent from the selected resource. It also supports candidate-level overrides: fields outside `resources.any_of`/`ordered` become defaults and fields inside a candidate override them. Candidate resources can therefore select a local context plus ROCm image or a cloud plus CUDA image in the same task YAML. (✓ VERIFIED: [`resources.any_of` and `resources.ordered`](https://docs.skypilot.co/en/latest/reference/yaml-spec.html#resources-any-of))

For example, the mechanism supports this shape (illustrative, not claimed as a tested SkyPilot example):

```yaml
resources:
  accelerators: 24GB+
  any_of:
    - infra: k8s/home-rocm
      image_id: docker:<project-rocm-image>
    - infra: runpod
      image_id: docker:<project-cuda-image>

setup: <shared setup>
run: <shared training command>
```

SkyPilot's own AMD examples set `image_id: docker:rocm/pytorch-training:...`, while Docker images are selected through `resources.image_id`. SkyPilot does not document automatic substitution between ROCm and CUDA images or backend settings. (✓ VERIFIED: [AMD smoke-test YAML](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/examples/amd/amd_smoke_test.yaml), [Docker containers](https://docs.skypilot.co/en/latest/examples/docker-containers.html))

**Inference for T1/T4:** identical training code and one overall task document are achievable, but a target-neutral resource definition is not. The local/cloud distinction leaks at least into `infra`, `image_id`, and commonly accelerator candidates. Skywright would need to own the mapping from a target parameter to those resource overrides if its public run definition must remain target-independent.

## 4. Local installation and K5

The documented fully local stack is:

1. A SkyPilot client/API server. For individual use, SkyPilot automatically starts an API server on the local machine; persistent control-plane state is under `~/.sky/` by default.
2. An existing on-prem Kubernetes cluster and kubeconfig reachable by that API server.
3. On the GPU node: supported AMD/ROCm host drivers and the AMD GPU Operator/device plugin so Kubernetes publishes `amd.com/gpu`.
4. A `skypilot.co/accelerator=<model>` node label.
5. A Debian-based ROCm runtime image accessible to the cluster.

(✓ VERIFIED: [local API server](https://docs.skypilot.co/en/latest/reference/api-server/api-server.html#local-api-server-individual-users), [Kubernetes setup](https://docs.skypilot.co/en/latest/reference/kubernetes/kubernetes-setup.html), [AMD setup](https://docs.skypilot.co/en/latest/reference/kubernetes/amd-gpu.html), [Docker images](https://docs.skypilot.co/en/latest/examples/docker-containers.html))

SkyPilot and the Kubernetes target can therefore run without a cloud compute provider. Managed Jobs can also disable cloud-bucket intermediates via `jobs.force_disable_cloud_bucket: true`, in which case files traverse the jobs controller instead. (✓ VERIFIED: [advanced configuration](https://docs.skypilot.co/en/latest/reference/config.html#jobs-force-disable-cloud-bucket))

**Inference for K5:** this architecture has no inherent recurring SkyPilot or cloud-compute charge; the user still bears hardware, electricity, networking, and administration costs. “No runtime cloud dependency” is achievable only if every workload dependency is also local: images must already be cached or served from an available registry, code/data must not point at cloud storage, and optional external services/telemetry must not be required. The official setup commands themselves download K3s/Helm charts/images, so the sources do not establish a fully air-gapped installation or operation guarantee.

## 5. Occupied capacity, failure, and fallback

For Kubernetes, SkyPilot waits for submitted pods to schedule. The default `kubernetes.provision_timeout` is 10 seconds; after the timeout, the provisioner raises a resource-unavailable failure and may try the next candidate. Source constructs an explicit message such as “Failed to acquire resources ... in context ...” and ultimately raises `ResourcesUnavailableError`. (✓ VERIFIED: [`kubernetes.provision_timeout`](https://docs.skypilot.co/en/latest/reference/config.html#kubernetes-provision-timeout), [Kubernetes provisioning source](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/provision/kubernetes/instance.py#L1728-L1745), [failure construction](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/backends/cloud_vm_ray_backend.py#L970-L1001))

Fallback behavior depends on the declared search space:

- `infra: k8s/home-rocm` fixes the Kubernetes context. With no cloud candidate in the definition, local exhaustion becomes an explicit terminal `ResourcesUnavailableError`. (✓ VERIFIED mechanism; terminal consequence follows from the fixed candidate set.)
- `infra: k8s` can fail over through the contexts permitted by `kubernetes.allowed_contexts`, in configured order. (✓ VERIFIED: [multiple Kubernetes clusters](https://docs.skypilot.co/en/latest/reference/kubernetes/multi-kubernetes.html#failover-across-multiple-kubernetes-clusters))
- `any_of`, `ordered`, multiple accelerator choices, or an unrestricted cloud search space deliberately enable fallback. SkyPilot's general auto-failover is on by default when provisioning new compute. (✓ VERIFIED: [Provisioning Compute](https://docs.skypilot.co/en/latest/examples/auto-failover.html))
- SSH Node Pools behave similarly: `ssh/<pool>` selects one pool, while bare `ssh` lets SkyPilot choose another pool with available resources. If a selected pool lacks capacity, the explicit error says the SSH Node Pool may not have enough resources. (✓ VERIFIED: [multiple SSH Node Pools](https://docs.skypilot.co/en/latest/reservations/existing-machines.html#using-multiple-ssh-node-pools), [failure source](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/backends/cloud_vm_ray_backend.py#L980-L992))

Thus T6 is implementable, but it is not SkyPilot's broad default policy: Skywright must pin the requested target/context and omit alternative candidates when fallback is forbidden.

## Could not establish

- Whether SkyPilot maintainers consider AMD on manually modified SSH Node Pool K3s officially supported. Current source is NVIDIA-only and no first-party AMD SSH guide was found.
- Whether the undocumented VRAM/manufacturer syntax is intended as a stable public contract in v0.13.x. It is implemented and tested, but absent from the YAML reference.
- Whether the actual home GPU's administrator label appears in SkyPilot's static VRAM metadata. The ticket does not identify the device model or desired VRAM, and arbitrary Kubernetes labels do not add metadata entries.
- A first-party guarantee of fully offline/air-gapped runtime. A self-hosted data/control path is supported, but installation, image pulls, catalog refreshes, and user workloads may make outbound requests.
- Exact occupied-target latency in the intended deployment. The documented default scheduling timeout is 10 seconds, but cluster configuration and unschedulable-reason handling can change observed timing.
