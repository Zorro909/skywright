# Local AMD workflow qualification

This is the ongoing evidence record for [#235](https://github.com/Zorro909/skywright/issues/235).
Qualification is incomplete. The checks below do not establish successful managed
recovery or a qualified private GPU image pull.

## Isolated deployment

The retained #233 environment uses rootless Podman and a dedicated kind cluster,
`skywright-issue233`. All Kubernetes commands explicitly select its kubeconfig.
The local node advertises `amd.com/gpu: 1` and
`skypilot.co/accelerator: rx7900xtx`. The host's GPU is the RX 7900 XTX qualified in
#233; this check does not establish an RX 7800 XT resource shape. Kubernetes is
v1.36.1 with containerd 2.3.1. SeaweedFS 4.42 serves the separate Dataset and Run
Store buckets.

On 2026-09-08 the backend was upgraded to merged source
`9b0957e09eb884122f01d4246dacbb8cb961dc88`. Its local image is
`skywright-backend:issue235-9b0957e`, image ID
`sha256:4f0707cb9d8ad366db4bc3abf15c4c00d182223afde508842056b7545865df5e`.
The replacement passed readiness and retained the 67,108,864-byte `/tmp` mount.
SkyPilot remains the unchanged 0.13.0 implementation in the previously qualified
tini package, `skywright-skypilot-api-server:issue233-tini-33c114d`.

The backend build used a detached checkout and `scripts/setup-worktree` in Ubuntu,
matching the deployment's pinned base image. The new prebuilt GraalPy validation
refused Ubuntu-built native dependencies on the Fedora host because its OpenSSL
lacked `EVP_sm4_cfb128`. The Ubuntu build passed the validation and produced the
application image. No validation or SkyPilot SDK code was bypassed or patched.

## Real project and Dataset

The executable source is [the CIFAR-10 example](../../examples/cifar10/README.md).
Its enrolled project identity is `6b4c790f-d8cd-4bdf-898c-deaf074861e9`; its project repository
is `ghcr.io/zorro909/skywright-cifar10-private-qualification`. The public qualification
source mirror is commit `b40e12c` in `Zorro909/skywright-ui-qualification`.

The ROCm Environment Profile was built from SDK source
`9b0957e09eb884122f01d4246dacbb8cb961dc88`. Publication
[34281355832](https://github.com/Zorro909/skywright-ui-qualification/actions/runs/34281355832)
passed and produced
`ghcr.io/zorro909/skywright-ui-qualification-profile@sha256:b5a03cc56eda4cbfc8c6673ca1f3fe01c077613be01511783b3065b3843b6bf8`.
This profile build and CPU smoke do not establish GPU execution of the new project.

The converter verified the original CIFAR-10 binary archive MD5,
`c32a1d4ab5d03f1284b67883e8d87530`. Its observed SHA-256 was
`c4a38c50a1bc5f3a1c5537f2155ab9d68f9f25eb1ed8d9ddda3db29a59bca1dd`.
It wrote all 50,000 training images into two uncompressed MDS shards and one index.
The largest shard is 100,660,482 bytes, exceeding the former 64 MiB temporary limit.

Publication ran through the source-side CLI against the actual application and
storage, using a temporary CPU pod with a scoped Dataset transfer credential. The
pod used the previously qualified project image for Python dependencies and the
current SDK source for the publication CLI. This is source-side publication
evidence, not a training-image identity claim.

| Recorded fact | Value |
| --- | --- |
| Target Storage | `c4bbd11c-d9ad-4eb4-b416-e8c35fc2065e` |
| Publication | `9ad1b22d-1ad2-498b-b550-5990c64057a3` |
| Dataset | `5f9474b3-eef0-4074-bc4b-0184b348094e` |
| Dataset Definition | `0a676a3a-1c29-479f-a471-609480c4ba98` |
| Copy | `e8a374fb-c1d9-4438-b6a2-b62ca6578184` |
| Version label | `cifar10-train-binary-v1` |
| Content fingerprint | `sha256:e976807a277906fca257c7714c0ecc40829330aa1b8c8bed6fe2ada74918a0ba` |
| Manifest identity | `sha256:acaf4d6e11d446a56adfa2c63a4cd8bd1f2450368d02182c814db4e71b76041a` |
| Verified content | 3 objects, 154,801,331 bytes |
| Publication created | 2026-09-08 21:43:18.890063 UTC |
| Publication completed | 2026-09-08 21:43:24.191844 UTC |

The publication committed with no failure or unavailable source. Completion took
5.30 seconds from the recorded creation time. This excludes the source download,
conversion and copying the corpus to the source pod. `/tmp` capacity was checked
before publication; this run did not sample its peak usage during verification.

The first project publication built and passed its CPU smoke, but failed the
private-visibility check. Its version artifact,
`sha256:041b3c4ff665b4057a3a4a57ca7ee9b96463ca7ebfb64191072ec1845b9fbdfb`
in `ghcr.io/zorro909/skywright-cifar10-qualification`, was anonymously readable
with HTTP 200. That artifact is excluded from private-pull evidence. A separate
private publisher, `Zorro909/skywright-private-qualification`, was prepared with
the same example and a new package name.

Private publication [34287240891](https://github.com/Zorro909/skywright-private-qualification/actions/runs/34287240891)
passed, including its private-visibility assertion. It produced image
`sha256:2faf6fabc08334b66800043a29062c68be243488ff982a9f12007a0ede396d2e`
and version artifact
`sha256:4a0ad41cc6e60b103afcf81feeffa8b18197f777573f5e353c4801427cf74093`.
This first version still carries the example's unenrolled placeholder identity,
`35a633f3-8342-4061-a8df-36b1af07d438`, and is not a runnable version of the enrolled
project. The next publication must use its actual ID and writer-proof runtime.

On 2026-09-09, two distinct same-account PATs passed `read:packages`-only scope
validation and authenticated private package reads. They were enrolled at Vault
revision 1 and activated as resolver binding
`efb0b771-301b-50b0-be90-672395601995` and target-pull binding
`945feeeb-5024-58e9-ba57-9958977101db`. Application registry readiness is `ready`.
ADR 0025 records their role separation and shared account-access limitation.

The target pulled the private digest above with the pinned target-pull binding and
`imagePullPolicy: Always`. Its CPU model/import smoke exited zero and the observed
image ID matched the published digest. A second pull with deliberately invalid
credentials returned `ImagePullBackOff` without starting the container, even with
the valid image cached. Both temporary Pods and their pull Secrets were removed.
These checks exercise the operator pull helper; the application-managed GPU Run
must still establish automatic pull delivery.

The temporary source pod and its credential Secret were deleted after publication
and read checks. No GPU training pod was created during these source checks.

## Dataset read budget

A source-pod check read 64 spread-out ordinals twice through the actual
`MdsDatasetAccess`, validating each image's byte count, label and source index.
The pod had a two-CPU limit and a 3 GiB memory limit. The rounds took 11.92 and
11.85 seconds. There were three network requests totaling 154,801,331 bytes, 126
cache hits, three misses, no eviction and no corrupt cache entries. Peak accounted
cache storage was 154,801,331 bytes within the default 256 MiB limit. Process peak
RSS was 831,000 KiB, including imported ML dependencies.

Warm cache hits still hash the full shard before decoding each item. The observed
throughput is approximately 5.4 images per second on this pod. It is a Dataset
reader measurement, not a GPU training throughput measurement. The example makes
no claim of a faster warm-cache CPU path.

An additional read used invalid access and secret keys with a new cache directory
against the actual qualification storage. Admission failed with the redacted
`DatasetReadError: Dataset storage read failed`; no Dataset Item was returned.

## CPU and storage recovery checks

The following command passed all ten cases in 139.95 seconds on 2026-09-08:

```sh
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
  uv run --project sdk --locked --extra dataset --group ml-test pytest \
  sdk/tests/integration/test_recovery_process.py \
  sdk/tests/integration/dataset/test_managed_runtime_system.py
```

These tests build and import an installed SDK wheel and use real SeaweedFS
storage. They cover refusal while a previous writer is suspended, recovery after
the supervisor reaps it, abrupt upload loss and bounded debt, missing/corrupt
checkpoint fallback, denied and timed-out storage, failed interruption-report
publication, project state validation at `context.start()`, clone seed recovery
and exact Dataset continuation with explicit Ordering Reset.

Successful recovery in these fixtures uses a private supervisor seam. The tests
do not establish a production Kubernetes previous-writer authority. Separately,
the two new CPU example checks passed, as did its configuration/metric contract
validation, MDS import/forward-pass smoke, formatting, lint and test-file typing.

## Production writer authority checks

The optional [local writer authority](../../deployment/examples/local-writer/README.md)
now registers the real SDK writer before attempt publication. It authenticates the
peer process and Run against trusted container and Pod metadata. Retained proofs
require the exact exited container and an empty or removed recursive cgroup;
Kubernetes phase and deletion alone cannot authorize recovery.

The CPU/container qualification passed on 2026-09-09 for fixture Run
`25650718-acbc-40a9-a6f1-9c0f9e579c0f` and attempt
`c3ab35d5-8f36-4b07-b930-04e424c184e8`. A detached writer survived its parent,
was suspended, and was refused recovery for 60.09 seconds. It resumed and wrote
again after refusal. Only termination of the whole container produced proof.
The old Pod still showed `Running` at that observation. The same proof survived
an authority restart and then deletion of the old Pod. Temporary fixture Pods
and their ConfigMap were removed.

The enrolled node UID is `00615a23-468e-4275-b259-74de64fc6e98`; the authority ID is
`e38e9ad5-d6ee-4a84-bee2-b1daa686df00`. The daemon uses explicit `SYS_ADMIN`,
`SYS_PTRACE` and `DAC_READ_SEARCH` capabilities with all others dropped. A fully
privileged Pod was incompatible with this rootless kind node's stale USB device
mapping; the explicit capabilities passed the complete container scenario without
changing host devices. The daemon remains a trusted node service.

The unchanged SkyPilot 0.13 SDK accepted the projected Run label, read-only Unix
socket mount, private image pull Secret, training namespace and `EAGER_NEXT_REGION`
recovery strategy in an actual `Task` round trip. This check did not launch a GPU
Run. SDK runtime tests passed 84 cases, deployment contracts passed 59 cases with
one root-only skip, and all four custody/mount tests passed in a root container.

## Remaining release evidence

- Submit the real project through the UI with its corrected project identity and
  writer-proof SDK image; inspect committed progress, confirmed checkpoints,
  Samples, Artifacts and archived setup/runtime logs.
- Exercise cooperative interruption, exact continuation, abrupt loss, bounded
  Recovery Debt and cancellation with no orphaned compute.
- Restart the backend around ambiguous submission/control delivery and inspect
  one logical Run/effect with truthful source availability.
- Record checkpoint and training I/O budgets, occupied-capacity behavior,
  checkpoint corruption and dependency outages at their owning seams.
- Keep successful supervised-fixture and node-container checks separate from
  production managed GPU recovery, and retain refusal when the writer is uncertain.

The default managed runtime still refuses previous-writer uncertainty. The optional
local authority implements the owner-approved extension recorded in #235 and ADR
0016; actual managed GPU recovery remains unqualified until the checks above pass.
A changed boot, missing runtime evidence before proof or unavailable authority still
refuses recovery before project entry. The implementation does not infer physical
node destruction or provide storage credential fencing.

The broader cloud qualification remains in #57. No cloud provider or purchase mode
has been selected for the next target.
