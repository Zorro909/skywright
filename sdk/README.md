# Skywright Python SDK

The `skywright` distribution is Skywright's pure-Python runtime SDK for Training Projects. It is
separate from the backend's SkyPilot bridge and does not implement unfinished Training Contract
behavior.

## Supported environments

The SDK requires Python 3.10 or later. Linux is the initial supported runtime platform for both
managed and direct execution. The universal wheel does not artificially prevent installation on
other operating systems, but those environments are not yet qualified as supported runtimes.

The SDK's only runtime dependency is the JSON Schema engine used by Training Project CI to compile
Project Configuration Contracts. It does not install PyTorch or select a CPU, CUDA, or ROCm build.
Environment Profiles supply the compatible PyTorch stack for managed
execution. Direct-execution developers own that choice in their project environment and should use
PyTorch's installation guidance for their accelerator backend.

## Public API and compatibility

The package root is the complete Training Project authoring API. Only names imported from
`skywright` and listed in `skywright.__all__` are public:

- `skywright.version` is the typed SDK version string.
- `skywright.__version__` is its conventional alias.
- `skywright.configuration` compiles Project Configuration Contracts and resolves submissions for
  Training Project CI using the same conformance contract as the backend.
- `skywright.metrics` publishes and compiles content-addressed Project Metric Contracts, composes
  their immutable Metric Catalogs, and applies the project-metric comparability rule.
- `run_training_process` is the direct-execution Training Process Boundary; the installed
  `skywright-runtime` command drives the same boundary for managed execution.
- `RunContext` is the project-owned loop's interface for resolved configuration, Dataset access,
  explicit accelerator access, Checkpoint State registration and resume, metric observations and
  Step commits, Samples, Artifacts, cancellation, and interruption.
- `MetricDefinition`, `Accelerator`, `CheckpointSnapshot`, and the other exported records describe
  the version-pinned runtime inputs and process evidence. `TrainingContractViolation` identifies
  project misuse with a stable rule, problem, and corrective guidance.

Modules and names beginning with an underscore, including the operational launcher and generated
build information, are private implementation details. They may move or change without a
compatibility promise. No unfinished Training Contract types are exposed as placeholders.

The SDK has its own [Semantic Version](https://semver.org/), independent of the Maven reactor
version. While the SDK is pre-1.0, a breaking change to the declared public API requires a new minor
version and release notes. Patch releases remain backward compatible with the public API of their
minor release.

SDK releases are tagged `sdk-v<version>`, for example `sdk-v0.1.0`. Only the strict stable
`sdk-vMAJOR.MINOR.PATCH` form is accepted; prerelease tags are not supported. The compatibility
check compares the declared public API against the latest such tag. Before the first SDK release
tag exists, it prints an explicit skip; it never falls back to an unrelated repository tag.

## Project Configuration Contract CI

`skywright-config validate project-contract.json` compiles a Training Project Version's
configuration artifact against the exact Skywright Configuration Schema identity it pins. It
checks exclusive property ownership, Draft 2020-12 and vocabulary constraints, bundled immutable
references, defaults and the Defaults Completion Witness. Exit 0 means the version is runnable;
exit 2 emits stable JSON failures and means it must not be published as runnable.

`skywright-config resolve project-contract.json submission.json` runs the same structural overlay
and complete-instance validation used by the backend, and emits the fully materialized Run
Configuration as JSON. The committed conformance corpus is packaged beside the schema and is
consumed independently by Python and Java tests.

## Project Metric Contract CI

`skywright-metrics publish project-metrics.json published-metrics.json` validates a Training
Project Version's declared metrics against the exact Skywright Metric Schema it pins and writes
the canonical content-addressed artifact. `skywright-metrics validate project-metrics.json`
performs the same runnable/not-runnable check without publishing. See
[`docs/reference/project-metric-contract.md`](../docs/reference/project-metric-contract.md) for
the artifact format, controlled units, semantic rules, and comparison behavior.

## Training Project entry point

A Training Project is a callable receiving one `RunContext`. Runtime setup happens before the
callable runs; the project then constructs and registers every resumable object, calls `start()` to
restore any checkpoint, and retains ownership of its loop:

```python
from collections.abc import Mapping

from skywright import (
    DatasetBatch,
    DatasetCursor,
    MetricCatalog,
    MetricDefinition,
    RunContext,
    run_training_process,
)
from skywright.metrics import MetricSchema


class Counter:
    def __init__(self) -> None:
        self.value = 0

    def state_dict(self) -> Mapping[str, object]:
        return {"value": self.value}

    def load_state_dict(self, state: Mapping[str, object]) -> None:
        self.value = int(state["value"])


def train(context: RunContext) -> None:
    counter = Counter()
    context.register_checkpoint_state("counter", counter)
    context.start()

    for batch in context.dataset.batches(context.dataset_cursor):
        counter.value += 1
        context.observe("train/items", 1)
        context.commit_step(batch)


class LocalDataset:
    ordering_fingerprint = "sha256:local-ordering"

    def batches(self, cursor: DatasetCursor):
        yield DatasetBatch(
            ("item-0",),
            DatasetCursor(
                item_offset=1,
                epoch_step=1,
                ordering_fingerprint=self.ordering_fingerprint,
            ),
        )


class LocalRecorder:
    def publish_attempt(self, attempt):
        pass

    def publish_step(
        self, step, dataset_cursor, observations, durable_step, durable_reference
    ):
        pass

    def publish_wall_time(self, observation):
        pass

    def publish_artifact(self, artifact):
        pass

    def publish_sample(self, sample):
        pass

    def publish_report(self, report):
        pass

    def publish_checkpoint(self, checkpoint):
        return f"local-checkpoint:{checkpoint.step}"


class LocalMetricContracts:
    def compose(self, project_version, schema_identity):
        return MetricCatalog(
            project_identity=project_version,
            project_contract_digest="sha256:project-contract",
            skywright_schema_identity=schema_identity,
            skywright_schema_digest="sha256:skywright-schema",
            units=frozenset((*MetricSchema.units(), "count")),
            project_definitions=(
                MetricDefinition(
                    name="train/items",
                    numeric_kind="integer",
                    unit="count",
                    comparison="maximize",
                    step_reduction="sum",
                ),
            ),
            system_definitions=MetricSchema.definitions(),
        )


result = run_training_process(
    train,
    run_id="local-smoke",
    project_version="example@abc123",
    configuration={"batch_size": 1},
    dataset=LocalDataset(),
    metric_contracts=LocalMetricContracts(),
    skywright_metric_schema="skywright-metrics@1",
    recorder=LocalRecorder(),
    seed=7,
)
assert result.outcome.value == "completed"
```

Every invocation requires a fresh process or notebook kernel. The first context-construction
attempt claims the process permanently, including failed construction. Python, NumPy, and PyTorch
RNGs and deterministic numerical behavior are library-owned; NumPy and PyTorch are configured when
the Environment Profile supplies them, without becoming SDK runtime dependencies. The first
`SIGINT` or `SIGTERM` requests interruption at the next Step and starts the configured shutdown
grace; grace expiry or a repeated signal forces immediate termination. Cancellation wins when both
requests are present.

Checkpoint, metric-event, Sample, Artifact, and Dataset transport stays behind explicit protocols.
A `DatasetAccess` implementation supplies batches and their next cursor; the Run Context accepts
only a context-issued batch. `commit_step()` advances its cursor while atomically publishing the
Step's project metrics, throughput, data-loading wait, and progress.
The Run Context also sends cgroup memory observations through the recorder at
`metrics.systemSamplingInterval`. A `TrainingProcessRecorder` synchronously confirms attempt,
checkpoint, metric/progress, output, and termination-report publication. Completion and
recoverable interruption are returned only after a checkpoint reference and the final report are
durable. The production Run Store recorder writes accepted observations to attempt-scoped
TensorBoard event segments and maintains the Run's current Progress Record.

## Read a pinned Dataset

Dataset reads use the external MosaicML Streaming package. Its published 0.13 release
restricts Transformers to versions below the security fixes. This implementation pins
upstream development version 0.14.0.dev0 at commit
[`d99bf9c7`](https://github.com/mosaicml/streaming/commit/d99bf9c7cdf2dd4ed62a4960ed35270b93337a5f)
and requires Transformers 5.10 or newer. The repository's uv lockfile selects this
revision with the `dataset` extra. No upstream source is copied into the SDK.

Until MosaicML publishes that version, installing Dataset support outside this
repository requires supplying the pinned source alongside the SDK extra:

```sh
pip install 'skywright[dataset]' \
  'mosaicml-streaming @ git+https://github.com/mosaicml/streaming.git@d99bf9c7cdf2dd4ed62a4960ed35270b93337a5f'
```

The base SDK installs from PyPI normally. Dataset support pins torchvision 0.27,
which selects PyTorch 2.12. The repository tests use their CPU wheels; Environment
Profile assembly and GPU qualification remain in #231 and #235.

Runtime assembly creates `skywright.dataset.MdsDatasetAccess` from a pinned
`DatasetDefinition` and a selected `StorageLocation`, then passes it to
`run_training_process(dataset=...)`. A definition contains the published manifest's
`DatasetObject` entries, manifest identity and content fingerprint. Convert the
catalog's base64 `checksumSha256` values to `sha256:` plus lowercase hexadecimal
when constructing these entries. Construction
recomputes both identities and rejects changed metadata. Managed assembly obtains
these inputs from `DatasetCatalog.selectForRun`, which atomically leases the selected
authority or explicit verified replica. The catalog persists the Run identity, lease,
copy and generation; generation history retains its exact location. Retrying selection
returns that lease even after deprecation, while new Runs cannot lease a deprecated
copy. Terminal lease release continues through the catalog's existing lifecycle.

The Training Project uses only `context.dataset.batches(context.dataset_cursor)`.
Each batch contains `DatasetItem` values with `definition_id`, `ordinal` and `payload`.
The ordinal is the zero-based position across the pinned root index's shards and each
shard's items. MosaicML's `MDSReader` decodes the columns into `payload`; project
transformations do not affect identity. Runtime consumers can also read an exact
ordinal with `read_item()`.

```python
from pathlib import Path
from skywright.dataset import DatasetCacheLimits, MdsDatasetAccess

# definition and location come from runtime assembly, outside Training Project code.
with MdsDatasetAccess(
    definition,
    location,
    cache_directory=Path("/var/cache/skywright/dataset"),
    limits=DatasetCacheLimits(byte_limit=256 * 1024 * 1024, file_limit=128),
    seed=7,
    batch_size=16,
) as dataset:
    # Supply dataset to the Training Process Boundary here.
    item = dataset.read_item(0)
```

Dataset credentials use `SKYWRIGHT_DATASET_ACCESS_KEY_ID`,
`SKYWRIGHT_DATASET_SECRET_ACCESS_KEY` and optional `SKYWRIGHT_DATASET_SESSION_TOKEN`,
or the protected JSON file named by `SKYWRIGHT_DATASET_CREDENTIAL_FILE`. The reader
never falls back to ambient AWS or Run Store credentials. The client fixes these
values at construction. Credentials are separate from the definition and location.

Each reader exclusively owns a dedicated cache directory until `close()` or context
manager exit. A process lock prevents concurrent owners and a thread lock serializes
reads. The default byte budget is 256 MiB, including the cached index, shards,
in-progress download and compressed-shard decoding scratch. The 128-file limit
bounds retained entries and eviction work per read; the lock and at most one scratch
file are additional. A shard that cannot fit together with its decoding scratch fails
before downloading. The index also has a 16 MiB metadata cap. Downloads use one
request at a time, no retries, 1 MiB read chunks, 30-second connect/read timeouts and
a 30-second elapsed deadline checked between reads. A blocked socket can extend the
elapsed deadline by at most its configured read timeout.

The cache verifies SHA-256 and byte count against the manifest on every reuse.
Corrupt local entries are discarded and rebuilt; wrong remote content fails before
payload decoding. Startup removes incomplete downloads and decoding scratch and
trims the retained inventory to the configured budget. Content-addressed entries can
survive a same-host restart or location change. Loss of the directory only requires
new downloads. Compressed shards remain compressed in the cache and use bounded
scratch during each decode.

`statistics` returns cumulative S3 request/read-byte counts, cache hits, misses,
evictions, rejected local entries, current cache bytes and peak reserved cache bytes.
It retains no per-request history. Counts reset for each reader and local verification
reads are excluded from S3 read bytes. Managed cache reporting can publish the byte
count through the existing catalog cache-report contract.

The initial `deterministic-shuffle` ordering uses six balanced SHA-256 Feistel rounds
with cycle walking, versioned as `feistel-sha256-v1`. Its fingerprint includes the
Dataset Definition identity, content fingerprint, seed and policy version. Epochs
use separate keys; location, cache and batch size are absent. `ordinal_at()` exposes
the sequence input to #45 without materializing an epoch in memory. Batches end at
epoch boundaries. The Run Context counts committed Steps, so a Step can accumulate
multiple batches. #45 owns exact continuation across regrouping and recovery.

`tests/test_dataset_access_system.py` reads frozen corpora generated by the original
MosaicML MDSWriter through S3 against pinned SeaweedFS. Fixture provenance records
the generator version and content hashes. It checks hits, eviction, restart, location change,
cache loss, digest rejection and direct Training Process execution with file
credentials. `scripts/integration` runs this suite with the SDK and writes I/O
evidence under `target/dataset-read-measurements/`.

## Publish a Dataset

The installed `skywright-datasets publish` command qualifies a complete MosaicML Streaming MDS v2
corpus before it contacts the control plane. Qualification streams shard offsets and payloads with
bounded memory and rejects a shard configuration region larger than 1 MiB; this is an explicit
metadata limit, not corpus truncation. Use `--concurrency` to bound simultaneous file validation and
transfer work.

```bash
skywright-datasets publish ./corpus \
  --control-plane https://skywright.example \
  --target-storage 00000000-0000-0000-0000-000000000001 \
  --concurrency 4
```

To publish another definition into an existing Dataset, first read its current `revision` and
`preferredDefinitionId` from `GET /api/v1/datasets/{datasetId}`. Then name that revision and choose
whether this definition should become preferred:

```bash
skywright-datasets publish ./corpus \
  --control-plane https://skywright.example \
  --target-storage 00000000-0000-0000-0000-000000000001 \
  --dataset 00000000-0000-0000-0000-000000000002 \
  --expected-dataset-revision 3 \
  --advance-preferred
```

Use `--keep-preferred` instead to preserve the current pointer. If the revision becomes stale after
upload, read the Dataset again and repeat the command with `--resume`, the existing publication ID,
the current revision, and a new explicit advance or keep decision. The command keeps the allocated
Dataset Definition, Dataset Copy, and Storage Location identities.

The command has stable exit classes for automation: `0` means success, `2` means invalid local or
request input, `3` means a conflict, `69` means a required dependency is unavailable, and `75`
means the direct transfer can be retried. Error output remains an RFC 9457 problem document whose
`errorCode` identifies the exact failure.

## Operational runtime command

Installing the SDK registers one private operational bootstrap for Environment Profiles:

```bash
skywright-runtime --help
skywright-runtime --version
skywright-runtime my_project:train --definition run.json
```

This command is infrastructure-facing rather than a second authoring interface. Its JSON definition
names `run_id`, `project_version`, resolved `configuration`, `dataset_factory` and
`recorder_factory` references, an optional `resume_factory`, `metric_contract_factory`, the pinned
`skywright_metric_schema`, `seed`, and the explicit `accelerator`. Adapter factories use
`MODULE:CALLABLE` form and belong in runtime infrastructure modules that do not import the Training
Project. The project entry point itself is
imported inside the deterministic process boundary. The command emits the Execution Termination
Report as JSON. Exit 0 is completion, 75 is safely finalized recoverable interruption, 64 is
terminal cancellation, and 1 is terminal failure. Help and version load no runtime services and
report the installed SDK version and frozen source revision separately.

## Install and build natively

Install uv 0.8.8, then work from this directory:

```bash
# Install the SDK and the locked contributor toolchain; the ML test group stays inactive.
uv sync --locked

# Run the fast contributor checks: formatting, linting, strict typing,
# installed-package type completeness, public behavior, and API compatibility.
scripts/check

# Run normal release-preparation verification. This validates the lock, runs the
# fast checks, validates artifact metadata and deterministic content, rebuilds a
# wheel in isolation from the source distribution, and tests both installed paths.
scripts/verify

# Build a wheel directly from the checkout and a source distribution into dist/.
scripts/build-distributions dist

# Re-run the installed-consumer harness against an existing artifact pair.
uv run --locked pytest tests/system -m system --artifact-dir dist
```

The separate `ml-test` dependency group locks a CPU-only PyTorch and NumPy stack for future ML
integration tests. It is intentionally excluded from ordinary bootstrap work. Activate it only
when working on a check that explicitly needs that stack:

```bash
uv sync --locked --group ml-test
```

Install the built wheel into a consumer environment with ordinary Python tooling:

```bash
python -m pip install dist/skywright-0.1.0-py3-none-any.whl
python -c 'import skywright; from importlib.metadata import version; print(version("skywright"))'
```

The distribution and import package are both named `skywright`. Package metadata is the canonical
source of version `0.1.0`; `skywright.__version__` mirrors that installed metadata. The package also
ships `py.typed` so consumers can recognize its inline typing contract.

### Build identity

Every wheel and source distribution contains timestamp-free build information with the package
version and source revision. Ordinary local builds use the honest revision `unknown` unless one is
supplied. Release automation must opt into release mode and provide an explicit immutable source
revision, normally the full commit ID:

```bash
SKYWRIGHT_BUILD_MODE=release \
SKYWRIGHT_SOURCE_REVISION=<full-commit-id> \
uv build
```

`SKYWRIGHT_BUILD_MODE=release` without `SKYWRIGHT_SOURCE_REVISION` fails the build. The source
distribution freezes both identity fields; rebuilding a wheel from that source distribution keeps
the same version and revision even when the original repository and build environment are absent.
No build timestamp is recorded, so rebuilding does not manufacture a different source identity.

### Inspect and rebuild artifacts

Validate the package metadata and rendered long description, then inspect the archive file sets:

```bash
uv run --locked twine check dist/*
unzip -l dist/skywright-0.1.0-py3-none-any.whl
tar -tzf dist/skywright-0.1.0.tar.gz
```

To reproduce the second distribution path manually, build a wheel from the source archive. uv uses
an isolated PEP 517 build environment by default:

```bash
mkdir -p dist/from-sdist
uv build dist/skywright-0.1.0.tar.gz --wheel --out-dir dist/from-sdist
```

The installed-consumer harness performs this rebuild outside the repository checkout and compares
the direct and source-derived wheels by file content, package metadata, embedded build identity,
and the same installed behavior. Compressed archive bytes are deliberately not compared.

## Build through the repository reactor

The Maven project-part invokes the same `scripts/check` contributor workflow during its test phase,
then delegates artifact construction and installed-artifact system tests to uv. From the repository
root, with the repository's required Java and Maven toolchain:

```bash
# Run the SDK checks without building unrelated backend project parts.
./mvnw -pl sdk -am test

# Build the SDK wheel and source distribution under sdk/target/dist/.
./mvnw -pl sdk -am package

# Build both artifact paths, then verify each in a fresh consumer environment.
./mvnw -pl sdk -am verify
```

## Release a version

Release preparation is a source change. Set the exact version in `pyproject.toml`, add the matching
`## MAJOR.MINOR.PATCH` section to `CHANGELOG.md`, pass the normal quality gate, and merge that commit
to `main`. Create `sdk-vMAJOR.MINOR.PATCH` at that exact commit and push the tag. The release workflow
rejects any tag that is not reachable from `main`, does not match the committed package version and
release notes, or does not resolve to the checked-out commit.

The protected `sdk-release` environment must require a maintainer approval, prevent self-approval,
and allow deployments only from protected `sdk-v*` tags. PyPI must configure a Trusted Publisher
for repository `Zorro909/skywright`, workflow `sdk-release.yml`, environment `sdk-release`, and the
unchanged project name `skywright`. The repository tag ruleset must reject force-updates and
deletion of `sdk-v*` tags. These settings are deliberately external prerequisites: the workflow
cannot weaken or create its own protection boundary.

The protected job checks the lock and all contributor checks, builds the wheel and source
distribution once in release mode, validates both clean installed-consumer paths, and creates a
manifest before publication. Collision checks compare the verified SHA-256 identities with PyPI
and any matching GitHub Release. An identical rerun skips already published destinations; a changed
file fails. A missing or unauthorized PyPI `skywright` project fails at Trusted Publishing—the
workflow never chooses another package name.

PyPI receives those exact distributions through short-lived OIDC Trusted Publishing, with PEP 740
publish attestations enabled. The matching GitHub Release receives the same files, `SHA256SUMS`,
SPDX JSON SBOMs, build-provenance and SBOM attestation bundles, and verification guidance. Published
files, tags, checksums, SBOMs, attestations, and GitHub Releases are permanent records: do not delete,
replace, or apply an expiration policy to them. Routine CI distributions remain diagnostic artifacts
with seven-day retention and are never inputs to the release workflow.

### Exact Dataset Item continuation

`MdsDatasetAccess` pins `DatasetOrdering`, including the Dataset Definition, content
fingerprint, seed, policy and `feistel-sha256-v1` algorithm version. Configuration
resolution materializes `reproducibility.seed`, `dataset.ordering.policy` and
`dataset.ordering.version`. The Training Process checks these against Dataset access
before calling the project. Checkpoints retain the ordering inputs and Dataset Cursor.
Every new checkpoint also records the library seed, policy and version independently
of the adapter, so injected adapters with opaque fingerprints cannot change those inputs
on recovery or cloning.

A Step may combine batches from one epoch. Commit its final issued batch after all its
work succeeds. The cursor then advances to the next uncommitted item. Retrieving items
does not commit them. Recovery discards uncommitted work and replays from the confirmed
checkpoint, including prefetched items. Final batches are never dropped or padded;
committing the last item advances to offset zero of the next epoch. Empty Datasets
are rejected when opening access.

`loader_workers` selects zero to 64 bounded retrieval workers. A reader submits at most
one batch at a time and reassembles results in sequence order. Its cache lock serializes
shard access; worker count is not a promise of decoding throughput. For single-node
accelerator execution, `batch.partition_items(count)` returns contiguous slices in
accelerator-index order. Concatenating those slices reconstructs the batch. The owning
Training Process commits the original global batch only after every slice has finished.
Empty slices are allowed. Multi-node execution is outside this contract.

The direct embedding boundary accepts `source_run_id` for an explicitly checkpoint-seeded
Run. The checkpoint must belong to that source and use the same Training Project Version.
Changing seed, ordering policy or policy version is rejected. A changed Dataset Definition
also requires `ordering_reset=True`. Reset preserves global epoch, global Step, RNG and
project state, but resets item offset and epoch-local Step count. It is invalid for
same-Run recovery or an unchanged Dataset Definition. The seed checkpoint stays immutable;
reset affects the new Run Context cursor. A cross-Run seed is provenance, and is not
advertised as a Durable Safe Point in the destination Run Store before that Run confirms
its own checkpoint. Checkpoints predating explicit
ordering metadata permit exact fingerprint continuation, but cannot perform a reset.
Managed runtime integration is tracked in #231; checkpoint transfer and clone UI remain
in their respective issues.

The guarantee covers the flattened committed Dataset Item identities. Worker count,
cache loss, Storage Location, batch grouping and single-node accelerator count can change.
Decoded tensors, augmentation and numerical training results need not match. RNG states
for surviving accelerator indices restore normally; additional devices retain the
library-established seed.
