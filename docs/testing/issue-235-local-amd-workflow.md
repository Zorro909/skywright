# Local AMD workflow qualification

This is the ongoing evidence record for [#235](https://github.com/Zorro909/skywright/issues/235).
Qualification remains incomplete until the corrected automatic-retention runtime
passes its GPU check. Managed cooperative and abrupt recovery, private pulls and
cancellation passed with the prior SDK revision as recorded below.

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

The writer-proof backend built from `f4bb6616d5757716370ba1f26fde4eba80416e15`
and was deployed with the authority option enabled. Its image is
`skywright-backend:issue235-f4bb661`, ID
`sha256:51b2dd840e9f3fb7bb4f0966e924338ede01f2d967017a44acd2fb94c4244016`.
The three Java projection tests, actual GraalPy/SkyPilot integration and real-S3
local application assembly passed. The isolated builder required a shared
Java temporary directory so its sibling S3 container could mount the fixture's
configuration file. Application and SkyPilot source were not changed for that
builder requirement.

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
The writer-proof profile subsequently passed publication
[34291651686](https://github.com/Zorro909/skywright-ui-qualification/actions/runs/34291651686)
from SDK source `f4bb6616d5757716370ba1f26fde4eba80416e15`, producing digest
`sha256:c18cafe3e3446207a84125628bd06a6e6c86c12e0aecf59c533d9bc080f0d65f`.
The corrected private project publication uses that profile. These profile builds
and CPU smokes do not establish GPU execution of the new project.

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
project. The corrected publication below uses its actual ID and writer-proof runtime.

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
subsequently established automatic pull delivery as recorded below.

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
`6479ec64-45b0-4081-ac0b-973fb187cd28` and attempt
`172af036-e439-4281-b721-bbd2e8538dff`. A detached writer survived its parent,
was suspended, and was refused recovery for 60.10 seconds. It resumed and wrote
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
Run. SDK runtime tests passed 84 cases, deployment contracts passed 61 cases with
two root-only skips, and all seven custody/mount tests passed in a root container.

## Managed private image and initial refusal

Private publication [34292057836](https://github.com/Zorro909/skywright-private-qualification/actions/runs/34292057836)
passed from project source `1243cb7ec62198c20bc4e8089d027aabe75c7eab`, producing
image `sha256:762dfc3e07f8a39fe8fbb6d0f178ee095561c3ecf70cfa126c7718b633a03612`
and version artifact
`sha256:e22a955424a9785b883922e9579c22b35b75112db32b86d2a45132ef974a7f60`.
The backend assessed this enrolled project version as runnable with no failures.
Anonymous access was refused with HTTP 401.

UI Run `b20f2c39-d38e-4a67-ae0a-1e2f1f61c350` was accepted at
2026-09-09 00:05:03.540021 UTC with submission
`faf61b73-4ef5-4360-8448-1134651c1d4b`. The browser deliberately lost the accepted
response. After a backend restart, replay returned the same Run and submission.
The unchanged SkyPilot status source reported one matching job, ID 5. While a
separate qualification Pod held the GPU, the controller archived insufficient
`amd.com/gpu` scheduling evidence and retained the job as pending. Releasing that
reservation allowed the managed Pod to pull the exact private image above using
its automatically projected pull Secret. The Pod requested four CPUs, 8 GB of
memory and one GPU.

Project entry then failed with `RECOVERY_AUTHORITY_UNAVAILABLE`: the node authority
had not enrolled the retained target's ancillary AMD render device. The Run Store
contained only 27 SkyPilot log objects, with no Execution Attempt, progress record
or checkpoint. SkyPilot reported `FAILED` and removed the training Pod. This Run
is negative admission and private-pull evidence; it did not train.

The authority now supports an explicitly enrolled ancillary render device,
disabled by default. This target enrolls `/dev/dri/renderD129`, already required
by its #233 ROCm discovery configuration. Enrollment and peer checks require the
exact character device, DRM major/minor numbers, AMD vendor and mount path.
Arbitrary host paths remain refused. The authority also accepts only the runtime's
exact sandbox hostname and resolver files as writable on a writable project root.
Both changes passed independent specification and standards reviews. The updated
authority was deployed before submitting the next Run.

## First successful training and packaging failures

Run `5cc29ad3-2de4-442e-a842-707ba94fe9ea`, submission
`92611ba1-078f-4d1a-b099-2a51f59a84ed`, repeated the UI lost-response/backend-restart
check and retained one SkyPilot job, ID 6. It trained actual CIFAR batches on
`AMD Radeon RX 7900 XTX`, device `cuda:0`, HIP `7.14.60850`, with 307,498 parameters.
The Run Store reader verified model and optimizer checkpoint state, random-number
and Dataset ordering state, 256×256 PNG Samples, prediction JSON Artifacts and
TensorBoard loss, accuracy, throughput and data-loading-wait metrics.

SIGTERM to the exact Training Process produced an interrupted report and confirmed
checkpoint at Step 21, Dataset Item offset 336. The node authority recorded the
old container's exit and removed recursive cgroup. SkyPilot replaced the container;
attempt `8d14920e-706b-457a-ad05-0204a8b3006f` seeded from the exact Step 21 reference
`skywright-checkpoint:v1:21:sha256:44f47efe5237382884468b9302e198c35ba48a28e6431ec611eed17cc7b46180`.
Its archived `training-started` event confirmed that Step and cursor. The next
Step consumed exactly the 16 ordinals at offsets 336–351 under the accepted
ordering fingerprint. Training subsequently passed Step 133.

This exposed a collector naming bug. The pinned SDK's
`JOBS_CLUSTER_NAME_PREFIX_LENGTH` is 25, whereas the collector used 30 from a stale
upstream comment. The correction passed 100 actual-SDK name comparisons, two
PostgreSQL/Kubernetes protocol integration tests and eight archive-reader tests.
The deployed collector then archived 34,847 bytes of task output, including the
recovered entry and Dataset ordinals. Earlier task-generation loss remains marked
`EARLIER_GENERATIONS_UNAVAILABLE`; the archive is not presented as complete.

Deleting the second training Pod with zero grace and an exact UID precondition
left committed Step 133 and checkpoint Step 132, reference
`skywright-checkpoint:v1:132:sha256:ff7d40663965f3becd8cf1a2aa99bc1083c0cdde42ca5dffa608d63be49b546c`.
The controller then failed because its missing-Pod exception handler imports
`grpc`, absent from the server image. SkyPilot recorded `FAILED_CONTROLLER` and
cleaned up compute. This does not qualify successful abrupt-loss recovery.

The server packaging now includes hash-pinned
[grpcio 1.83.1](https://pypi.org/project/grpcio/1.83.1/), a dependency declared by the
pinned SkyPilot Kubernetes extra. Its CPython 3.12 Linux amd64 and arm64 wheels
are separate from the shared GraalPy environment. A regression executes the
installed SDK's missing-Pod handler; it reproduces the missing import in the old
image and returns a transient status observation in the corrected image.
SkyPilot source remains unchanged. A fresh Run must repeat abrupt recovery and
cancellation with the corrected packaging.

## Successful managed recovery and cancellation

Run `ff38a09d-6b48-4b25-bd57-45094439b539`, submission
`f8fa10e3-9f05-415d-93a1-8967ac48c6da`, was accepted at
2026-09-09 00:38:55.008286 UTC. Its lost acceptance response and backend restart
again replayed to the same identities. The unchanged SDK returned a complete
status observation containing exactly one matching job, ID 7. This Run used the
same immutable private project image and SDK revision `f4bb661` with the corrected
server packaging and collector.

The successful Run used the following observed container-runtime image identities.
These are manifest digests, distinct from the backend build configuration ID
recorded earlier. The server and collector were rebuilt as local overlays of the
qualified tini image; only Skywright collector code and the pinned CPython
dependency changed.

| Container | Source revision | Observed image digest |
| --- | --- | --- |
| `storage` | `pinned deployment image` | `sha256:f7cbc8bdbbf60a1aaba7d61784a3bdff3ec1e0657f6ad0b26d5b6ab2cd9d0dc6` |
| `vault` | `pinned deployment image` | `sha256:4e33b126a59c0c333b76fb4e894722462659a6bec7c48c9ee8cea56fccfd2569` |
| `authority` | `117c58d` | `sha256:07865d4b905bcbcfff6e24fd85dfed3124f8a035c2005a06d6845751a0805fba` |
| `backend` | `f4bb661` | `sha256:e260ee3d6225f17786bb4a87bc53a53c164bf9183d20dc99a11902df9f49abb0` |
| `postgresql` | `pinned deployment image` | `sha256:cc9f4143a8d2fa8cf3749d0cb4d26ecf2d53a77a2ac807e9ebd67ae22426221a` |
| `log-collector` | `881bd13` | `sha256:ef58af1061025eba2f680180ede5b23f6edeff9f193338d96353fd4d1d4ac8b8` |
| `runtime-pull` | `33c114d` | `sha256:f4236aed1f3e7a47885d2ca2fb38de5d345925b10ac9f98719b5d2d6279d73ba` |
| `skypilot-api-server` | `71f1602` | `sha256:2e909a47e686d9de95c6762864dd69e2dccaffec1d9b2daa8d9fd190f3bee6d5` |

Cooperative SIGTERM produced a confirmed checkpoint at Step 16. Its successor
attempt `1ade5c87-dbec-4bef-9f62-67af427e9bfc` restored that exact reference and
Dataset Item offset 256. The first recovered Step consumed the expected 16 Items
at offsets 256–271. Recovery Debt was captured at 1 against maximum 3, then at 0
after new checkpoint progress.

Deleting that successor Pod with zero grace and an exact UID precondition left
committed Step 31 and checkpoint Step 30. Attempt
`677d98a2-eae1-4018-a8bf-a3dd8367868e` recovered from
`skywright-checkpoint:v1:30:sha256:c0a8d8a25921fb2b03ed0dc003259e8c62813e91223778b6c438f41215a761f4`.
It restored offset 480 and replayed exactly the 16 uncheckpointed Items in Step 31.
Debt again increased to 1 and decayed to 0. Both predecessor proofs matched the
exact registered container and were durably observed before the successor's
registration. API deletion alone was not used as proof.

Cancellation Request `31c2429c-e8a8-4074-ac45-1bf7536a48f6` was accepted at
2026-09-09 00:45:30.609977 UTC. After dropping its response and restarting the
backend, replay preserved its ID, acceptance time and escalation deadline. The
response recorded `effect-observed`; the UI reached a terminal state supported by
source evidence. No training Pods remained. The authority also retained the final
attempt's exact-container death proof: container
`56c973835183cd649c2958023545c67c88bf528949837d4d732b4347435cb303`
exited at 00:46:02.566925608 UTC and its recursive cgroup was removed. The proof's
registration digest matched that cancelled attempt. The latest checkpoint was Step 57.
The finalized controller archive retained 43,954 bytes and reported complete.
The task archive retained 30,010 bytes, including all three training-entry events,
and honestly reported partial with `SOURCE_GENERATION_LOST` after abrupt loss.

| Measured quantity | Observation |
| --- | --- |
| Checkpoint payload | 2,506,560 bytes each, model and SGD momentum included |
| Dataset cache sampled before SIGTERM | 154,801,331 bytes; largest file 100,660,482 bytes |
| Configured Dataset cache ceiling | 268,435,456 bytes |
| Training throughput, 59 committed-Step observations | Median 5.02 Items/s; range 1.34–5.89 |
| Data-loading wait per Step | Median 3.16 seconds; range 2.71–3.78 |
| Sampled cgroup memory, 106 observations | Maximum 5,703,725,060 bytes under the 8 GB Pod request |
| Run Store inventory after cancellation | 50,995,339 bytes, including 20 retained checkpoint payloads |

These short-run measurements do not establish classifier convergence or sustained
GPU saturation. Data loading dominates the small model's Step time.

## Automatic-retention correction and remaining check

The measurements exposed missing integration of the existing retention algorithm:
configured newest-three retention did not run automatically. This contradicts
#41 and ADR 0008. The Training Process now binds the accepted policy before attempt
publication; the checkpoint worker invokes verified pruning after confirmation.
It preserves newest-N, every-Nth Steps and the confirmed final reference. The same
cancellable S3 gateway and shutdown wait cover pruning. A pruning failure leaves
excess data and preserves the confirmed point.

The correction passed 145 unit tests, an installed-SDK managed workflow against
real S3 in 49.19 seconds, and three focused worker/retention tests including
cancellation during pruning. Type checking, formatting and lint passed. The
installed workflow verifies automatic retention, recovery and cloning with a
protected seed. The updated ROCm profile is being published from SDK source
`ff026fbcf9ce21fe5779df1a4d30688dee2322ce`; its actual private GPU Run remains the
final release check. The earlier inventories above retain their observed counts.

A changed boot, missing runtime evidence before proof or unavailable authority
still refuses recovery before project entry. The local authority does not infer
physical node destruction or provide storage credential fencing. The default
managed runtime continues to refuse previous-writer uncertainty.

The broader cloud qualification remains in #57. No cloud provider or purchase mode
has been selected for the next target.
