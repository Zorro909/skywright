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
Its project identity is `35a633f3-8342-4061-a8df-36b1af07d438`; its project repository
is `ghcr.io/zorro909/skywright-cifar10-qualification`. The public qualification
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

## Remaining release evidence

- Verify that the project package is private, that anonymous access fails, and
  that the actual target runtime pulls its pinned image using the recorded binding.
- Submit the real project through the UI and inspect committed progress, confirmed
  checkpoints, Samples, Artifacts and archived setup/runtime logs.
- Exercise cooperative interruption, exact continuation, abrupt loss, bounded
  Recovery Debt and cancellation with no orphaned compute.
- Restart the backend around ambiguous submission/control delivery and inspect
  one logical Run/effect with truthful source availability.
- Record checkpoint and training I/O budgets, occupied-capacity behavior, bad image
  credentials, checkpoint corruption and dependency outages at their owning seams.
- Keep successful supervised-fixture recovery separate from production managed
  recovery, and retain failure when the previous writer is uncertain.

The production recovery gap is explicit in #56 and `sdk/RECOVERY.md`.
`RunJobAdapter.previousWriter` returns uncertainty and `ManagedRuntime.run` defaults
to `uncertain_previous_writer`. A trusted local proof mechanism or an explicit
change to #235's successful-recovery acceptance criterion is needed before this
record can claim that requirement has passed. ADR 0016's fail-closed rule remains
in force.

The broader cloud qualification remains in #57. No cloud provider or purchase mode
has been selected for the next target.
