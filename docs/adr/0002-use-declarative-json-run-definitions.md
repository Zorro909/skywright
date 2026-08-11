---
status: accepted
---

# Use declarative JSON run definitions

Skywright represents a run with an immutable, fully resolved JSON Run Definition rather than a Python-owned object or executable configuration. A Run Submission names an exact Training Project Version and supplies partial configuration overrides and target constraints; Skywright resolves every default and validates the result before creating the Run Definition. The version's project-owned schema contribution and defaults document form its Project Configuration Contract, pin the exact Skywright Configuration Schema they extend, and may not be paired with code from another version.

## Contract boundaries

- OpenAPI specifies the HTTP API. Generated Python and Java types are boundary DTOs, not persistence entities or core domain objects.
- The persistence contract has an insulated JSON Schema and independently generated internal types. An explicit mapping separates API DTOs from the stored model.
- A Run Record wraps a Run Definition with lifecycle state and resolved infrastructure. Cloning copies the definition into a fresh record; changing configuration, Training Project Version, or requested target creates a new definition and record.
- JSON is the only serialization format. There is no parallel YAML representation or handwritten Python or Java contract model.

## Considered options

A Python object as the primary definition was rejected because the Java backend and UI could neither construct nor inspect it without executing project code. Reusing generated OpenAPI DTOs as the persistence model was rejected because API evolution and internal storage needs change for different reasons. OpenAPI was also rejected for project-specific configuration schemas: JSON Schema expresses those schema-only contracts directly, while OpenAPI remains the API-boundary specification.

## Consequences

Each Training Project Version must carry a version-bound project schema contribution, defaults document, Defaults Completion Witness, and the Skywright library version and configuration-schema digest against which CI validated them. The backend independently composes and validates the same whole-document schema. JSON Schema's `default` annotation is not relied upon to mutate instances; Skywright deterministically combines library defaults, version defaults and a submission's overrides, validates the resolved configuration, and persists all resulting values explicitly. [ADR 0012](0012-compose-one-owned-configuration-tree.md) defines ownership and precedence; [ADR 0013](0013-resolve-configuration-with-structural-overlays.md) defines exact merge, `null`, completeness and conformance semantics. The exact mechanism used to pin a Training Project Version remains a downstream decision.
