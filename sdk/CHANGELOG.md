# Skywright SDK release notes

## Next

- Adds the Training Process Boundary and typed Run Context authoring interface, including
  deterministic runtime setup, one-context process ownership, Checkpoint State and resume,
  Step-scoped metric enforcement, Run outputs, cooperative stop handling, and structured outcomes.
- Enables direct Python execution and managed `skywright-runtime MODULE:CALLABLE --definition ...`
  execution over the same Training Project entry point.

## 0.1.0

- Introduces the independently versioned, dependency-free `skywright` runtime distribution for
  Python 3.10 through 3.14 on Linux.
- Defines the typed package-root version surface and the `skywright-runtime` operational bootstrap.
- Freezes the source revision into wheel and source-distribution builds and qualifies both clean
  consumer installation paths.
