# CIFAR-10 training project

This example trains a six-layer convolutional image classifier on CIFAR-10. It
reads image bytes and labels from the pinned Dataset, performs forward and
backward passes, and checkpoints the model and SGD momentum. Its 307,498 trainable
parameters keep checkpoint storage practical for local qualification. It is a
workflow example, with no claim of benchmark accuracy or numerical equivalence
after recovery.

The project publishes `train/loss` and `train/accuracy` at each committed Step.
Step 1 and every `project.outputEvery` Steps publish a PNG grid and a JSON Artifact
with the corresponding labels, predictions and Dataset Item ordinals. Runtime logs
record each committed Step's ordinals and next cursor so continuation can be checked
against the deterministic Dataset Item Sequence.

The runtime currently issues single-item batches. The project accumulates those
items into `project.batchSize` images and commits the final issued batch after the
optimizer update. It commits a smaller final batch at an epoch boundary, preserving
every Dataset Item. Registration and `context.start()` precede all Dataset reads
and training work. Project numbers are explicitly converted from the resolved
configuration's exact numeric representation.

## Prepare and publish the Dataset

Run these commands from the Skywright repository root. Preprocessing is a separate
source-side operation, as required by ADR 0010. Use a filesystem with at least
500 MiB free for the download and uncompressed corpus.

```sh
mkdir -p /tmp/skywright-cifar10
curl --fail --location --continue-at - \
  https://www.cs.toronto.edu/~kriz/cifar-10-binary.tar.gz \
  --output /tmp/skywright-cifar10/cifar-10-binary.tar.gz
uv run --project sdk --locked --extra dataset python \
  examples/cifar10/prepare_dataset.py \
  /tmp/skywright-cifar10/cifar-10-binary.tar.gz \
  /tmp/skywright-cifar10/mds \
  > /tmp/skywright-cifar10/source.json
```

The converter verifies the original archive checksum before opening its five
training members. It writes all 50,000 training images as uncompressed MDS with
SHA-256 checksums and a 96 MiB shard limit. At least one shard must exceed 64 MiB.
It reads named archive members without extracting archive paths. The source
receipt stays outside the corpus, which may contain only its index and referenced
shards. An existing output directory is refused; use a new directory after a failed
conversion.

The format and checksum come from the [original CIFAR-10 publication](https://www.cs.toronto.edu/~kriz/cifar.html).
Dataset credit: Alex Krizhevsky, Vinod Nair and Geoffrey Hinton. See Alex
Krizhevsky's *Learning Multiple Layers of Features from Tiny Images*, 2009.
The test split is unused, and the recorded accuracy is training-batch accuracy.

Publish from a source environment that can reach both the control plane and the
registered storage endpoint. Supply that environment's own storage credential
through its protected AWS credential configuration. The publication CLI does not
fetch a backend credential or accept secret material in Run Configuration.

```sh
uv run --project sdk --locked skywright-datasets publish \
  /tmp/skywright-cifar10/mds \
  --control-plane "$CIFAR_CONTROL_PLANE" \
  --target-storage "$CIFAR_DATASET_STORAGE_ID" \
  --version-label cifar10-train-binary-v1 \
  > /tmp/skywright-cifar10/publication.json
```

Retain the source receipt, immutable Dataset Definition and publication result.
The backend's 64 MiB `/tmp` budget stays in force. Dataset Publication verifies
shard responses with bounded buffers; its source-side corpus and the training
Dataset Cache have separate storage budgets.

## Publish the project

Use `skywright_project.py`, `configuration.json`, `metrics.json` and
`requirements.lock` as a Training Project source directory. The lock supplies the
Dataset dependencies from the pinned SDK lock; the ROCm Environment Profile owns
PyTorch and torchvision. Publish with the repository's
`publish-training-project` action and an exact ROCm profile digest. Its smoke
command is `python -m skywright_project`, which imports the MDS reader and performs
a CPU forward pass without requiring a GPU on the publication worker.

Use a private GHCR project package for #235 and retain the complete version
manifest, image digest, profile digest and SDK source revision. Public source code
and a public profile do not qualify a private project-image pull. Register the
private registry binding before submission; verify unauthenticated rejection as
well as an authenticated pull by the actual target container runtime.

## Submit and inspect

Select the immutable project version and Dataset Definition in the UI, then choose
the discovered local AMD accelerator and a separate Run Store. Defaults are 2,000
Steps, 64 images per Step, learning rate 0.02 and an output every 100 Steps. A short
qualification can override the Step count and output cadence without changing the
published project. These are training Steps, not completed epochs.

Observe committed progress and confirmed checkpoint references separately. Inspect
the Samples and Artifacts through the Run Store, and the archived setup/runtime
logs through their source-backed views. Record unavailable owning UI features
explicitly. A finished short check demonstrates the workflow; it does not establish
classifier convergence.

Recovery requires proof that the previous writer stopped or lost write authority,
in addition to a readable checkpoint. The current production runtime defaults to
unavailable recovery when that proof is absent. A cooperative signal alone is not
proof. Successful supervised-fixture recovery must not be presented as successful
production managed recovery.

## Automated example checks

```sh
uv run --project sdk --locked skywright-config validate examples/cifar10/configuration.json
uv run --project sdk --locked skywright-metrics validate examples/cifar10/metrics.json
uv run --project sdk --locked --extra dataset python examples/cifar10/skywright_project.py
uv run --project sdk --locked --group ml-test pytest sdk/tests/unit/test_cifar10_example.py
```

The Run Context test trains actual CPU batches, checks partial-epoch commits and
the next epoch's ordinals, and checks committed metrics and outputs. It uses small
generated images. Real data, GPU, storage, interruption and cleanup evidence belong
to the separate #235 deployment qualification.
