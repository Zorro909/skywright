# Training Project Version publication

A Training Project commits one `skywright-project.json` definition. Validation may run anywhere;
publication is accepted only from a clean CI checkout whose reported source revision equals
`HEAD`.

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

Run `skywright-project validate skywright-project.json` for the contract-only check. In CI, provide
standard GitHub Actions provenance (`GITHUB_SHA`, `GITHUB_RUN_ID`, and `GITHUB_RUN_ATTEMPT`) or the
generic `CI_COMMIT_SHA` and `CI_PIPELINE_ID`, authenticate the configured registry with
`SKYWRIGHT_REGISTRY_USERNAME` and `SKYWRIGHT_REGISTRY_PASSWORD`, then run:

```bash
skywright-project publish skywright-project.json
```

The publisher builds each thin image from the selected profile, installs only the hashed project
lock, runs the declared smoke command, pushes every image, and resolves each immutable digest. It
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
unavailability remain distinct not-runnable reasons. Registry discovery enumerates the
content-addressed version artifacts for display, but selection and verification always use the
resolved OCI manifest digest rather than a mutable provenance tag.

Registry retention must preserve every digest referenced by an undeleted Run Record. Skywright
verifies availability but cannot enforce that external policy.
