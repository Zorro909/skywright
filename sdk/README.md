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

# Run the current fast package checks.
uv run --locked pytest tests/test_package.py

# Build the universal wheel and source distribution into dist/.
uv build

# Install the wheel into a fresh external environment and verify consumer behavior.
uv run --locked pytest tests/system --wheel-dir dist
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

## Build through the repository reactor

The Maven project-part delegates lock validation, tests, and artifact construction to the native uv
commands. From the repository root, with the repository's required Java and Maven toolchain:

```bash
# Run the SDK checks without building unrelated backend project parts.
./mvnw -pl sdk -am test

# Build the SDK wheel and source distribution under sdk/target/dist/.
./mvnw -pl sdk -am package

# Build the artifacts, then verify the installed wheel in a fresh consumer environment.
./mvnw -pl sdk -am verify
```
