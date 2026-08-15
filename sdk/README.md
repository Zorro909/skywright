# Skywright Python SDK

The `skywright` distribution is Skywright's pure-Python runtime SDK for Training Projects. It is
separate from the backend's SkyPilot bridge and does not implement unfinished Training Contract
behavior.

## Supported environments

The SDK requires Python 3.10 or later. Linux is the initial supported runtime platform for both
managed and direct execution. The universal wheel does not artificially prevent installation on
other operating systems, but those environments are not yet qualified as supported runtimes.

The SDK has no runtime dependencies. In particular, it does not install PyTorch or select a CPU,
CUDA, or ROCm build. Environment Profiles supply the compatible PyTorch stack for managed
execution. Direct-execution developers own that choice in their project environment and should use
PyTorch's installation guidance for their accelerator backend.

## Public API and compatibility

The package root is the complete Training Project authoring API. Only names imported from
`skywright` and listed in `skywright.__all__` are public:

- `skywright.version` is the typed SDK version string.
- `skywright.__version__` is its conventional alias.

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

## Operational runtime command

Installing the SDK registers one private operational bootstrap for Environment Profiles:

```bash
skywright-runtime --help
skywright-runtime --version
```

This command is infrastructure-facing and is not a Training Project authoring API. Help and version
load only the Python standard library and installed Skywright package metadata; they do not import
or locate PyTorch, SkyPilot, the backend, Vault, Kubernetes, or an Environment Profile. The version
diagnostic prints the canonical installed SDK Semantic Version and the separately frozen source
revision.

Issue #47 will provide the Training Process Boundary that eventually executes a Training Project.
Until that boundary exists, invoking `skywright-runtime` for training exits non-zero with a clear
limitation diagnostic. It does not create or imply a Run, Execution Attempt, Run Context, Execution
Termination Report, or Training Process Outcome. PyTorch discovery belongs only to future runtime
behavior that actually needs it.

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
