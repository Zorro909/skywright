# Skywright SDK release notes

## Next

- Adds optional MosaicML MDS Dataset reads with a verified, bounded S3 cache,
  canonical item ordinals and cumulative I/O statistics. Direct execution supports
  role-specific environment or protected file credentials on Python 3.10 through
  3.13 with the `dataset` extra.

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
