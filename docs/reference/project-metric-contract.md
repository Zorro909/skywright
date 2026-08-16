# Project Metric Contract

A Training Project Version publishes one Project Metric Contract before it can run. The artifact
declares every project-owned metric that its code may observe and pins the exact Skywright Metric
Schema that supplies the unit registry, reserved naming rules, and library-owned System Metrics.
Project CI and the backend compile the same artifact independently.

## Authoring and publication

Author the contract as JSON, then publish its canonical content-addressed form:

```bash
skywright-metrics publish project-metrics.json published/project-metrics.json
```

Success exits with status `0` and prints the contract digest and pinned schema identity. Invalid
input exits with status `2`, prints stable JSON errors, and makes the Training Project Version not
runnable. `skywright-metrics validate project-metrics.json` performs the same checks without
writing an artifact.

The published file is canonical UTF-8 JSON: object keys are sorted, insignificant whitespace is
removed, array order is retained, and its identity is `sha256` over those exact bytes. Retain the
artifact at its digest for as long as a Run that pins it remains interpretable.

## Artifact format

```json
{
  "contractVersion": 1,
  "skywrightSchema": {
    "version": "0.1.0",
    "digest": "sha256:f66e2663c0927ef689238fffbd4381ecada58e44500eaa7cbd3f40801c6e2595"
  },
  "definitions": [
    {
      "name": "train/loss",
      "numericKind": "real",
      "unit": "dimensionless",
      "recordingBasis": "step",
      "comparison": "minimize",
      "stepReduction": "mean",
      "bounds": {"minimum": 0},
      "displayName": "Training loss",
      "description": "Loss over one committed Step."
    }
  ]
}
```

Every definition requires:

- `name`: a unique lowercase slash-separated canonical name, used unchanged as the TensorBoard
  tag. The entire `skywright/` namespace is reserved.
- `numericKind`: `real` or `integer`.
- `unit`: one entry from the pinned schema's controlled registry: `dimensionless`, `ratio`,
  `count`, `bytes`, `seconds`, or `items_per_second` in schema `0.1.0`.
- `recordingBasis`: `step`. Project contracts cannot declare wall-time metrics.
- `comparison`: `minimize`, `maximize`, or `none`. This describes favorable series movement and
  does not select a run-level summary or ranking.
- `stepReduction`: `mean`, `sum`, `min`, `max`, or `last`. `mean` is valid only for real metrics.

`bounds.minimum` and `bounds.maximum` are optional finite numeric constraints on the reduced Step
value. `displayName` and `description` are optional presentation metadata. Definitions permit
observations but do not impose presence or cadence.

## Runtime composition and comparison

Runtime infrastructure constructs `ProjectMetricContract` from the exact artifact and expected
digest. When the Run Context starts, the resolver composes it with the pinned Skywright Metric
Schema and exposes the resulting immutable `context.metric_catalog`. The catalog is derived and is
not copied into the Run Definition or Run Store. A Run Submission cannot add or alter definitions.

Project metric series are comparable only when they belong to the same stable Training Project
identity, use the same canonical name, and match in numeric kind, unit, Recording Basis,
comparison, bounds, and Step Reduction. Display name and description may evolve without breaking
comparability. Use `skywright.metrics.project_metrics_comparable` to apply this rule.
