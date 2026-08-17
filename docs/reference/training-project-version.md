# Training Project Version publication

A Training Project commits one `skywright-project.json` definition. The repository's reusable
GitHub Action accepts publication only from a clean CI checkout whose reported source revision
equals `HEAD`.

```json
{
  "definitionVersion": 1,
  "projectIdentity": "stable-project",
  "registryRepository": "ghcr.io/example/stable-project",
  "configurationContract": "project-configuration.json",
  "metricContract": "project-metrics.json",
  "dependencyLock": "requirements.lock",
  "smokeCommand": ["python", "-m", "project", "--smoke"],
  "backends": {
    "cuda": {
      "environmentProfile": "ghcr.io/zorro909/skywright-environment:1.0.0-cuda@sha256:..."
    },
    "rocm": {
      "environmentProfile": "ghcr.io/zorro909/skywright-environment:1.0.0-rocm@sha256:..."
    }
  }
}
```

The dependency lock is a fully hashed pip requirements file. It must contain every transitive
project dependency and must not contain `skywright`; the selected Environment Profile is the sole
supplier of that library. Every profile reference is digest-pinned. A project can declare either
or both of the initial `cuda` and `rocm` backends, but CI never silently drops one.

Downstream Training Projects invoke the Action from a workflow with permission to push their
configured registry repository. Pin the Action to an immutable Skywright commit (the placeholder
below must be replaced with a full commit SHA):

```yaml
name: Publish Training Project Version

on:
  push:
    branches: [main]

permissions:
  contents: read
  packages: write

jobs:
  publish:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803
        with:
          fetch-depth: 0
      - id: project-version
        uses: Zorro909/skywright/.github/actions/publish-training-project@<full-commit-sha>
        with:
          definition: skywright-project.json
          registry-username: ${{ github.actor }}
          registry-password: ${{ secrets.GITHUB_TOKEN }}
```

The Action is the complete publication interface. Docker builds, registry authentication, smoke
checks, and OCI writes are implementation details inside the Action artifact; the installed
`skywright` Python SDK exposes no project-image publishing command or transport code. The Action
uses the exact SDK contract compilers shipped at its pinned revision in an isolated CI environment.

The publisher builds each thin image from the selected profile, installs only the hashed project
lock, and verifies that the profile's installed Skywright library exposes the exact configuration
and metric schema identities used by the Action's contract compilers. It then runs the declared
smoke command, pushes every image, and resolves each immutable digest. It
then publishes canonical configuration and metric artifacts at deterministic tags derived from
each image digest. The version artifact is tagged from its own content digest and carries the
`<commit>-<pipeline>` provenance label; it is the final write. Therefore an
interrupted build may leave unreachable intermediates but never exposes a partial version as
runnable. An existing deterministic tag with different content is an immutable-publication
collision and fails.

The version artifact records stable project identity, commit and pipeline provenance, the complete
declared backend set, all image and Environment Profile digests, both contract content identities,
their exact Skywright schema identities, and each OCI contract artifact digest. The backend pulls
that artifact using a Skywright-owned binding of project identity to registry repository; the
artifact cannot authenticate its own project identity. It pulls every referenced piece from the
registry, checks image availability, recomputes contract identities, and independently compiles
both contracts. Missing/pruned artifacts,
unsupported schemas, incomplete backend maps, incompatible requested backends, and registry
unavailability remain distinct not-runnable reasons. Registry discovery returns an age-bearing
list pairing each provenance label with its content-addressed manifest digest for display without
pulling its contracts or images. Selection returns a separately age-bearing verification of only
the resolved OCI manifest digest rather than a mutable provenance tag.

Registry retention must preserve every digest referenced by an undeleted Run Record. Skywright
verifies availability but cannot enforce that external policy.
