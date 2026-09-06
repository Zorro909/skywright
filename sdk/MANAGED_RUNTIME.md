# Managed runtime assembly

A managed Training Project Image supplies `skywright_project.train(context)` in its
`/workspace` working directory. The library invokes it after validating the accepted
Run Definition, pinned contract bytes, Dataset identity and recovery admission. The
project registers its Checkpoint State, calls `context.start()`, and owns its training
loop. Publication checks that the callable accepts one context argument.

The local AMD task runs `python -m skywright._runtime --definition definition.json
--materials materials.json --cache-directory cache`. The definition is the generated
version 2 artifact accepted by the backend, delivered without translation. The SDK
reads the reproducibility seed, ordering policy, configuration, metric schema and
maximum Recovery Debt from that one artifact. Projects supply no Dataset, recorder,
metric or resume factories and no second seed.

The version 1 materials document carries execution facts and the bytes addressed by
the definition. Its typed Java contract is `RuntimeMaterials`:

- `runId` identifies the Run Record; `image` is its resolved ROCm repository and digest.
- `configurationContract` and `metricContract` contain the exact published artifact
  text. Their SHA-256 digests and installed library schema identities must match.
- `dataset` carries the accepted Dataset identity, version, content fingerprint,
  manifest identity and object integrity entries. The SDK recomputes the manifest
  and content identities before opening production MDS access.
- `datasetLocation` supplies the selected storage address, copy generation and lease.
  It is execution-location metadata, independent of logical Dataset ordering.
- `sourceCheckpoint` is null or the source Run identity, exact durable reference and
  source Run Store address from the Run Record's lineage. It contains no second
  configuration or seed. Once the clone publishes its own checkpoint, recovery no
  longer reads the source payload.

Each document is limited to 16 MiB. Unknown versions, missing or extra material
fields, unresolved defaults, mismatched identities and unavailable credentials stop
startup. Materials contain no credential values. Dataset and Run Store clients use
the existing isolated role projections supplied through SkyPilot's secret channel.
The Run Store projection must authorize any source checkpoint reads as well as the
new Run's writes. No ambient AWS identity is used.

The Java projector accepts one qualified local Kubernetes AMD target. It verifies
requested GPU count, memory, model and target pin, selects the accepted ROCm digest,
and uses a Run-derived job name. MapStruct checks the finite task DTO mapping in both
directions; the whole accepted definition travels in the task payload. A Run-scoped
image pull-secret reference remains separate from storage credentials. This projection
consumes backend-verified artifacts and target qualification; it does not discover
registry availability, create storage or select a cloud fallback.

Only durably finalized interruption outcome 75 is configured in
`resources.job_recovery.recover_on_exit_codes`; ordinary error restarts remain zero.
The runtime's previous-writer verifier defaults to refusal. Production proof delivery
and lifecycle reconciliation remain #56's responsibility. The CPU system fixture
injects a private verifier only after its supervisor has reaped the previous process.
A recoverable exit alone never proves that an earlier writer has stopped.

The shared fixtures in `tests/fixtures/managed-runtime` are checked against the Java
Run Definition resolver. Installed-wheel tests consume those exact bytes, then use a
disposable storage location to exercise the managed CLI, real MDS/S3 access, exact
continuation, checkpoint-seeded cloning and explicit Ordering Reset. Actual AMD GPU,
container and storage qualification remains #235.

Direct embedding retains the existing explicit private runtime document and the
`run_training_process` API. These seams do not determine managed project assembly.
