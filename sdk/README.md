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

## Install and build natively

Install uv 0.8.8, then work from this directory:

```bash
# Install the SDK and the locked contributor toolchain; the ML test group stays inactive.
uv sync --locked

# Run the current fast package checks.
uv run --locked pytest

# Build the universal wheel and source distribution into dist/.
uv build

# Install the wheel into a fresh external environment and verify consumer behavior.
uv run --locked pytest tests/system --wheel dist/skywright-0.1.0-py3-none-any.whl
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
