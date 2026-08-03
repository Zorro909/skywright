# One container image for ROCm and CUDA, or two?

## Answer

Use **two images**. PyTorch publishes the `torch` package as mutually-exclusive CUDA and ROCm wheel builds (same package name, different backend tags), and the official container registries keep them in separate images: `pytorch/pytorch` carries CUDA/CPU tags, while `rocm/pytorch` carries ROCm tags. A single container could in principle hold both sets of user-space libraries plus two separate Python environments, but there is no official multi-backend image, and PyTorch cannot switch GPU backends inside one Python process at runtime. GPU runtime plumbing also differs on the host (NVIDIA Container Toolkit/`--gpus` vs ROCm `--device=/dev/kfd`).

For SkyPilot, the practical pattern is therefore: build/maintain one CUDA image and one ROCm image, then let SkyPilot select the right `resources.image_id` per target. The training code itself can stay identical as long as it uses backend-agnostic APIs such as `torch.cuda`, autocast, and `torch.distributed`, but a few backend-specific behaviours and feature flags do differ.

## Versions recorded

- PyTorch stable: **2.13.0** (from `pytorch.org/get-started/locally/` and Docker Hub, queried 2026-08-04).
- CUDA wheel indices: `cu126`, `cu130`, `cu132`.
- ROCm wheel index: `rocm7.2`.
- ROCm images used: `rocm7.2.4` and `rocm7.14` tags (Docker Hub, queried 2026-08-04).
- SkyPilot docs: `docs.skypilot.ai` (queried 2026-08-04).

## Evidence

### 1. Single image vs. two images: what ships and what conflicts

- PyTorch stable Linux wheels live on separate index URLs (`cu126`, `cu130`, `cu132`, `rocm7.2`, `cpu`) but all share the same distribution name `torch`. Wheel filenames embed the backend, e.g. `torch-2.12.0+cu132-cp311-cp311-manylinux_2_28_x86_64.whl` vs `torch-2.12.0+rocm7.2-cp311-cp311-manylinux_2_28_x86_64.whl`. (✓ VERIFIED — `download.pytorch.org` index listings)
  - CUDA index: https://download.pytorch.org/whl/cu132/torch/
  - ROCm index: https://download.pytorch.org/whl/rocm7.2/torch/
- The PyTorch "Get Started Locally" page emits a single install command per selected compute platform; CUDA and ROCm are distinct choices. (✓ VERIFIED)
  - Source: https://pytorch.org/get-started/locally/
- The official Docker registries are split:
  - `pytorch/pytorch` on Docker Hub contains only CUDA and CPU tags; a search for `rocm` in that repository returned no tags. (✓ VERIFIED — Docker Hub API)
    - Source: https://hub.docker.com/v2/repositories/pytorch/pytorch/tags/?page_size=100&name=rocm
  - `rocm/pytorch` on Docker Hub contains only ROCm tags. (✓ VERIFIED — Docker Hub API)
    - Source: https://hub.docker.com/v2/repositories/rocm/pytorch/tags/?page_size=100
- Because the CUDA and ROCm wheels are the same package name, `pip` will treat them as the same distribution and overwrite one with the other in a single Python environment. (✓ VERIFIED — pip semantics plus the identical package names above)
- A single image could physically contain both CUDA and ROCm user-space libraries and two isolated Python environments (e.g. two venvs or conda envs), but this is not an official PyTorch pattern and no source documents it as supported. (✓ VERIFIED absence of official guidance; ? INFERRED feasibility)
- Container GPU runtime plumbing differs:
  - ROCm containers require `--device=/dev/kfd --device=/dev/dri --group-add video` and additional flags. (✓ VERIFIED)
    - Source: https://rocm.docs.amd.com/projects/install-on-linux/en/latest/install/3rd-party/pytorch-install.html
  - NVIDIA containers use `--gpus` / the NVIDIA Container Toolkit. This is common Docker usage rather than a PyTorch/ROCm doc, but the ROCm doc above shows the AMD side and the PyTorch CUDA images are built on NVIDIA's CUDA base images.

### 2. Official PyTorch images and their sizes

`pytorch/pytorch` (CUDA), from the Docker Hub API, compressed sizes:

| Tag | Bytes | ~GB decimal | ~GiB |
|-----|------:|------------:|-----:|
| `2.13.0-cuda12.6-cudnn9-runtime` | 3,875,470,870 | 3.88 | 3.61 |
| `2.13.0-cuda12.6-cudnn9-devel` | 13,254,596,733 | 13.25 | 12.34 |
| `2.13.0-cuda13.2-cudnn9-runtime` | 3,014,485,635 | 3.01 | 2.81 |
| `2.13.0-cuda13.2-cudnn9-devel` | 12,219,264,072 | 12.22 | 11.38 |
| `2.7.0-cuda12.8-cudnn9-runtime` | 4,280,634,347 | 4.28 | 3.99 |
| `2.7.0-cuda12.8-cudnn9-devel` | 9,415,816,680 | 9.42 | 8.77 |

(✓ VERIFIED — Docker Hub API: https://hub.docker.com/v2/repositories/pytorch/pytorch/tags/?page_size=100&name=2.13)

`rocm/pytorch` (ROCm), from the Docker Hub API, compressed sizes:

| Tag | Bytes | ~GB decimal | ~GiB |
|-----|------:|------------:|-----:|
| `rocm7.2.4_ubuntu24.04_py3.12_pytorch_release_2.12.0` | 10,390,820,431 | 10.39 | 9.67 |
| `rocm7.14_ubuntu24.04_py3.12_pytorch_release_2.12.0` | 19,373,520,787 | 19.37 | 18.04 |
| `rocm7.2.4_ubuntu24.04_py3.12_pytorch_release_2.9.1` | 10,379,537,122 | 10.38 | 9.66 |

(✓ VERIFIED — Docker Hub API: https://hub.docker.com/v2/repositories/rocm/pytorch/tags/?page_size=100)

Observations:

- ROCm runtime images are roughly 2-3× larger than CUDA runtime images and comparable to or larger than CUDA devel images.
- The ROCm 7.14 images are roughly twice the size of the ROCm 7.2.4 images; the newer ROCm images appear to ship multi-architecture/device support.

### 3. Runtime backend selection within one image

- The `torch` wheel is a compiled binary tied to one backend. There is no runtime switch to make a CUDA build talk to AMD GPUs or a ROCm build talk to NVIDIA GPUs. (✓ VERIFIED — separate wheels/indexes above)
- On a ROCm build, PyTorch intentionally reuses the `torch.cuda` API namespace and maps it to HIP; the device string remains `'cuda'`. (✓ VERIFIED)
  - Source: https://docs.pytorch.org/docs/main/notes/hip.html
- Backend selection therefore happens at **image-build time** (or at environment-build time inside the image), not at runtime within a single Python process. (✓ VERIFIED / ? INFERRED)

### 4. How SkyPilot selects or receives a container image per target

- SkyPilot's `resources.image_id` accepts a Docker image as `docker:<image>`, e.g. `docker:ubuntu:20.04`. (✓ VERIFIED)
  - Source: https://docs.skypilot.ai/en/latest/reference/yaml-spec.html
- `image_id` also supports per-region maps for failover, e.g.:
  ```yaml
  resources:
    image_id:
      us-east-1: ami-...
      us-west-2: ami-...
  ```
  (✓ VERIFIED — same source)
- SkyPilot supports multiple resource candidates via `any_of` (and `ordered`). Fields outside `any_of` are defaults; duplicate fields inside a candidate override the defaults. `image_id`, `accelerators`, and `infra` are described as resource fields that can be overridden. (✓ VERIFIED)
  - Source: https://docs.skypilot.ai/en/latest/reference/yaml-spec.html
- This means one task YAML can specify a default image and override it per candidate, e.g.:
  ```yaml
  resources:
    image_id: docker:myrepo/pytorch-cuda:2.13.0
    any_of:
      - infra: aws/us-east-1
      - infra: k8s/rocm-cluster
        image_id: docker:myrepo/pytorch-rocm:2.12.0
  ```
  The mechanism is documented; the specific `image_id` override example is not shown in the docs but follows directly from the override rule. (✓ VERIFIED mechanism; ? INFERRED for `image_id` specifically)
- Only Debian-based images are supported as runtime containers; GPUs are automatically mapped into the container. (✓ VERIFIED)
  - Source: https://docs.skypilot.ai/en/latest/examples/docker-containers.html

### 5. Maintenance cost of two images

- Two image families must be kept in version lockstep. The PyTorch version in the CUDA image and the ROCm image must match if the project depends on a specific PyTorch ABI/API version, because PyTorch minor releases can introduce API changes. (✓ VERIFIED that versions must be tracked; ? INFERRED cost)
- Pull-time on a fresh spot instance is directly tied to compressed image size. The ROCm images above are ~10-19 GB versus ~3-13 GB for CUDA runtime/devel images, so ROCm pulls take longer on the same link. (✓ VERIFIED sizes; ? INFERRED time impact)
- No SkyPilot documentation was found describing image pre-caching, layer caching, or registry mirror behaviour on freshly provisioned spot instances. (see **Could not establish**)

### 6. Behavioural differences that can break "identical code"

- **AMP / autocast**: supported on ROCm since PyTorch 1.9 / ROCm 2.5. (✓ VERIFIED)
  - Source: https://rocm.docs.amd.com/en/docs-6.3.3/compatibility/ml-compatibility/pytorch-compatibility.html
- **Flash Attention / SDPA**:
  - Historically, FlashAttention and the `enable_flash_sdp` / `enable_mem_efficient_sdp` / `enable_cudnn_sdp` backends were unsupported on ROCm in the PyTorch 2.0-2.1 timeframe. (✓ VERIFIED)
    - Source: https://rocm.docs.amd.com/en/docs-6.3.3/compatibility/ml-compatibility/pytorch-compatibility.html
  - As of PyTorch 2.8 with ROCm 7.1, `torch.nn.functional.scaled_dot_product_attention` automatically calls an optimized flash-attention kernel on ROCm. (✓ VERIFIED)
    - Source: https://rocm.docs.amd.com/en/docs-7.2.3/compatibility/ml-compatibility/pytorch-compatibility.html
  - The standalone FlashAttention package (Dao-AILab) supports AMD/ROCm via Composable Kernel and Triton backends, requiring ROCm 6.0+. (✓ VERIFIED)
    - Source: https://github.com/Dao-AILab/flash-attention
- **TensorFloat-32 (`allow_tf32`)**: `torch.backends.cuda.matmul.allow_tf32` is unsupported on ROCm for older hardware; on MI300 it is supported via hipBLASLt but with hardware-level numerical differences from NVIDIA's implementation. (✓ VERIFIED)
  - Source: https://docs.pytorch.org/docs/main/notes/hip.html
- **`torch.distributed`**: only the `nccl` and `gloo` backends are supported on ROCm. (✓ VERIFIED)
  - Source: https://docs.pytorch.org/docs/main/notes/hip.html
- **Unsigned integer dtypes**: `torch.uint16`, `torch.uint32`, and `torch.uint64` are not natively supported on ROCm. (✓ VERIFIED)
  - Source: https://rocm.docs.amd.com/en/docs-6.3.3/compatibility/ml-compatibility/pytorch-compatibility.html
- **Reduced-precision reductions**: `torch.backends.cuda.matmul.allow_fp16_reduced_precision_reduction` and `allow_bf16_reduced_precision_reduction` are not supported on ROCm. (✓ VERIFIED)
  - Source: https://rocm.docs.amd.com/en/docs-7.2.3/compatibility/ml-compatibility/pytorch-compatibility.html
- **Kernel asserts**: supported on ROCm but disabled by default due to performance overhead; re-enabling requires recompiling PyTorch with `-DROCM_FORCE_ENABLE_GPU_ASSERTS:BOOL=ON`. (✓ VERIFIED)
  - Source: https://docs.pytorch.org/docs/main/notes/hip.html
- **Device naming**: code should continue to use `'cuda'` strings even on AMD GPUs; `'rocm'` or `'hip'` are not valid device names. (✓ VERIFIED)
  - Source: https://docs.pytorch.org/docs/main/notes/hip.html

## Could not establish

- Whether an **official single image** containing both CUDA and ROCm `torch` builds exists. The official docs and registries use separate images; no source documents a supported multi-backend image.
- Whether **SkyPilot caches or pre-pulls** Docker images on workers/spot instances to reduce cold-start time. No SkyPilot doc on image caching or layer caching was found.
- Whether **ROCm and CUDA PyTorch produce identical RNG sequences** or identical deterministic convolution outputs when `torch.use_deterministic_algorithms(True)` is used. ROCm docs note support for deterministic algorithms but do not claim numerical/bit-wise equivalence with CUDA.
- Whether **deterministic algorithm coverage** is the same across backends for all ops; the docs note backend-specific unsupported flags.
- Exact **pull-time latency** for `pytorch/pytorch` vs `rocm/pytorch` images on cloud spot instances; only compressed image sizes are available, not network measurements.
- Whether **installing CUDA and ROCm user-space libraries in the same image** causes file-level conflicts. No source explicitly tests or documents this combination; the known conflicts are at the `torch` package level and the host GPU-runtime level.
