---
status: accepted
---

# Compose version-pinned metric contracts at runtime

Every project metric is declared before execution in a canonical JSON Project Metric Contract published with its Training Project Version. That contract names its content digest and the exact versioned, content-addressed Skywright Metric Schema it extends; the schema supplies Skywright's library-owned definitions and Metric Unit registry. Project CI validates the contract, and the backend independently validates the same artifact and composition before considering the version runnable. A Run Submission can neither add nor alter definitions.

## Definition and ownership

A Metric Definition has these semantic fields:

- a canonical lowercase, slash-separated `name`, used unchanged as its TensorBoard tag;
- `numericKind`, either real or integer;
- a required `unit` from the versioned Skywright registry, including an explicit dimensionless unit;
- `recordingBasis`, either Step or wall time;
- `comparison`, one of minimize, maximize or none;
- optional numeric bounds; and
- `stepReduction`, one of mean, sum, min, max or last, required for a Step basis and forbidden for wall time.

Display name and description are optional presentation fields, not semantic fields. Metric names are unique in the composed catalog. The entire `skywright/` namespace is reserved; a Project Metric Contract may declare only Step-based names outside it. System Metrics are ordinary library-owned Metric Definitions under `skywright/system/`, with either basis. `mean` is valid only for real metrics; other Step Reductions accept real or integer metrics. Definitions permit observations but impose no presence or cadence requirement.

The Project Metric Contract and Skywright Metric Schema compose deterministically into an immutable Metric Catalog when a Run Context starts. The catalog is not another stored artifact: the Run Definition pins the Training Project Version and exact Skywright Metric Schema identity, while that version pins the original Project Metric Contract. Neither the Run Definition nor the Run Store contains a resolved catalog copy. TensorBoard events are therefore the determined source of metric values but not of their semantics; interpreting a detached Run Store requires the pinned project and Skywright contract artifacts to remain available. This deliberately rejects the research note's recommendation to copy the catalog beside each run in exchange for avoiding another redundant artifact.

## Runtime enforcement

The Run Context is the only project-facing metric writer and accepts no caller-supplied Step number. Each project observation belongs to the next Step to commit. At commit, all observations for each name are reduced to one TensorBoard scalar; observations belonging to an uncommitted Step are discarded, and returning normally with pending observations is itself a contract violation.

Every observation must name a declared metric and be a finite scalar of its declared numeric kind. Booleans are not numeric observations; integers must remain exactly representable by TensorBoard's numeric encoding. Optional bounds apply to the reduced value. An undeclared name, invalid value, wrong basis, or other project misuse is rejected immediately and latches the Execution Attempt as a `contract_violation`, so project code cannot recover validity merely by catching the local exception. Accepted observations alone reach TensorBoard. An invalid Project Metric Contract makes its Training Project Version not runnable; an invalid library-generated System Metric or unrecoverable catalog or metric-writer failure is a `skywright_failure` instead.

## Comparability

Project metrics are comparable only within one Training Project identity, under the same canonical name, when every semantic field of their definitions matches exactly. Display name and description may change without breaking comparability. Library-owned System Metrics remain comparable across projects while their semantic definitions match. Comparison direction describes favorable movement along a series; it does not silently choose the last, best or any other run-level summary, and automated ranking would require a separate explicit contract.

## Consequences

TensorBoard remains an event format, writer and visualization surface rather than an enforcement authority. The version-pinned contracts must remain addressable for as long as retained runs need semantic interpretation. A metric directory can expose its configuration through ADR 0007's HParams export, but its units, reductions, bounds and comparison meanings cannot be reconstructed from its event files alone.
