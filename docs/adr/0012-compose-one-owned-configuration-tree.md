---
status: accepted
---

# Compose one configuration tree with exclusive property ownership

Run Configuration is one immutable semantic JSON tree rather than parallel library and project namespaces. A property is library-owned when Skywright interprets it to provide or enforce the Training Contract, and project-owned only when its meaning belongs entirely to the Training Project. Ownership controls who defines meaning and validation, not where a property sits or who may read its resolved value; this keeps related settings together without letting project contracts silently fork library semantics.

## Scope and initial library properties

Run Configuration governs behavior inside the training process. Requested target capabilities, execution storage, cost controls, repatriation and other orchestration or backend choices remain separate fields of the Run Definition even though Skywright owns them.

The initial library-owned paths are:

- `reproducibility.seed`
- `dataset.ordering.policy`
- `checkpoint.cadence`
- `checkpoint.retention`
- `metrics.flushInterval`
- `metrics.segmentRoll`
- `metrics.systemSamplingInterval`

This is deliberately the smallest set required by accepted decisions. Learning rate, batch size, model and optimizer choices, augmentations and stopping criteria remain project-owned. A future property becomes library-owned only when Skywright begins interpreting it under the rule above; broad usefulness alone is insufficient. The inner shapes of checkpoint policy remain a separate decision; [ADR 0013](0013-resolve-configuration-with-structural-overlays.md) fixes merge, array and `null` semantics.

## Schema composition and values

The Skywright Configuration Schema owns and documents the library paths. A Project Configuration Contract contributes a JSON Schema fragment for project-owned properties at their semantic paths and a defaults document that may choose both project- and library-owned values. A Run Submission may override either kind of value. Every resolved value is visible to Training Project code through the same immutable Run Configuration, so a project may, for example, reuse `reproducibility.seed` for its own randomness after Skywright has established determinism.

Choosing a value does not transfer definition ownership. A project may add a sibling beneath an object shared with Skywright, but it may not restate or constrain a library-owned property, change the type of a shared parent, or apply an object-wide constraint that changes which library properties are valid. Every property path therefore has exactly one definition owner. Project compilation and CI must compose the schemas and reject a collision with its JSON Pointer and both owners; the backend independently repeats the check and treats a conflicting Training Project Version as not runnable.

Default precedence is library, then Training Project Version, then Run Submission. [ADR 0013](0013-resolve-configuration-with-structural-overlays.md) defines the structural overlay that applies that precedence and the Defaults Completion Witness that validates an incomplete baseline without weakening the complete-instance schema.

## Versioning and documentation

The Skywright Configuration Schema uses the Skywright library release as its human-readable version and has an exact content digest. A Project Configuration Contract records both; project CI composes against that exact schema, publishes the project fragment, defaults and schema identity, and the backend recomposes against its trusted copy of the same schema. A missing, unsupported or digest-mismatched schema makes the Training Project Version explicitly not runnable. The composed whole-document schema is derived rather than authored by the project.

The schema is the authoritative catalogue for library-owned properties. Every property must document its description, type, constraints, unit where applicable, default and resume-compatibility effect; library-user reference documentation is generated from it. Every addition, removal, rename, ownership change, type or constraint change, default change, or semantic change also receives a Skywright changelog entry, with migration guidance when breaking.

## Consequences

Configuration remains readable by domain rather than ownership while collisions fail before paid execution. Training Project Versions stay coupled to the library contract they were built and validated against, so backend upgrades must either retain support for historical schema versions or report affected versions as not runnable. Generated property reference and mandatory changelog entries make that compatibility boundary visible to library users.
