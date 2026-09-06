# Skywright SDK release notes

## Next

- Gate production recovery using immutable publication history and progress-decayed
  debt. Refuse uncertain previous writers, incomplete history and terminal Runs;
  preserve evidence after payload retention and publish idempotent exhaustion
  records. Expose recovery refusal separately from attempt termination reports.


- Normalizes confirmed missing S3 objects as `RunStoreMissingObjectError`, a subtype of
  `RunStoreIntegrityError`, across reads, publication and retention. Recovery falls back
  only for missing objects or recognized content/container corruption. Permission loss,
  timeouts, unavailable history and incompatible state fail closed.

- Streams checkpoint recovery to one size-limited staged file and decodes tensor leaves
  directly from disk. `RunStoreReader` accepts `staging_directory` and `max_checkpoint_bytes`,
  defaulting to 64 GiB. Download streams close and staging is deleted on success or failure.
- `presign_download()` now returns a `DownloadLink` with `.url`, expected size and SHA-256,
  storage identity and `verification="not-recorded"`. It uses HEAD without reading content.
  `download(key, destination, max_bytes=...)` verifies a staged output before replacing its
  destination. Raw URL consumers must verify the link's expected size and digest themselves.

- Releases published output and Metric Observation history from the production context and
  background sampler. `TrainingProcessResult` no longer contains `metric_observations`,
  `artifacts` or `samples`; read persisted Run Store history or use a test recorder.
- Bounds S3 request diagnostics to 256 records by default, with configurable capacity and
  atomic `drain_measurements()` batches. Producer/sequence identities distinguish retries and
  new clients. Overflow reports missing identity ranges and timestamp bounds without stopping
  storage operations; accounting consumers must preserve these gaps as unknown usage.

- Bounds checkpoint capture to one owned copy and releases confirmed, superseded and failed
  publication payloads. `TrainingProcessResult.final_checkpoint` now returns a
  `CheckpointConfirmation` with only the Step and durable reference; load full state from the
  Run Store. Internal serialization and reference attachment no longer clone tensor payloads.

- Adds MosaicML MDS Dataset reads with a verified, bounded S3 cache, canonical item
  ordinals and cumulative I/O statistics. The `dataset` extra uses a pinned upstream
  MosaicML Streaming development revision with patched Transformers dependencies.
  Direct execution uses role-specific environment or protected file credentials.

- Adds the Training Process Boundary and typed Run Context authoring interface, including
  deterministic runtime setup, one-context process ownership, Checkpoint State and resume,
  batch-issued Dataset Cursor commits, runtime Metric Catalog composition, atomic durable
  publication ports, Run outputs, cooperative stop handling with shutdown grace, and structured
  outcomes.
- Enables direct Python execution and managed `skywright-runtime MODULE:CALLABLE --definition ...`
  execution over the same Training Project entry point.
- Adds Project Configuration Contract compilation and deterministic Run Configuration resolution,
  the `skywright-config` project-CI command, and the shared Java/Python conformance corpus.
- Adds Project Metric Contract publication through the `skywright-metrics` command.
- Persists committed project and System Metrics as attempt-scoped TensorBoard segments in the Run
  Store, with periodic prefix-safe publication, recovery purge markers, canonical configuration
  export, and a validated current Progress Record.

## 0.1.0

- Introduces the independently versioned, dependency-free `skywright` runtime distribution for
  Python 3.10 through 3.14 on Linux.
- Defines the typed package-root version surface and the `skywright-runtime` operational bootstrap.
- Freezes the source revision into wheel and source-distribution builds and qualifies both clean
  consumer installation paths.
