# Can Skywright reuse TensorBoard for a declared, typed metric contract?

## Answer

**Reuse TensorBoard as the metric event format, writer backend, and visualization/read ecosystem; do not use it as Skywright's metric schema or enforcement authority.**

The PyTorch `SummaryWriter` and its `add_scalar` API are a good implementation target for emitting accepted Skywright metrics. TensorBoard's event and `Summary` protobufs are also the right wire/storage format. However, none of TensorBoard's public metric-related APIs defines or enforces the complete contract Skywright needs: a predeclared name, semantic numeric type, unit, training-time reduction/aggregation, and comparison direction, with rejection of undeclared metrics.

Skywright should therefore own a small `MetricSpec`/registry and a validating `Run Context` API. Once a value passes that validation, the implementation can delegate it to `torch.utils.tensorboard.SummaryWriter.add_scalar`. The Run Context should be the only project-facing metric writer so that projects cannot bypass undeclared-metric rejection through a raw writer.

Suggested division of responsibility:

| Concern | Owner | TensorBoard reuse |
|---|---|---|
| Metric declaration and common predefined metrics | Skywright | Optionally export display metadata to HParams |
| Reject undeclared names and invalid values | Skywright Run Context | None; `add_scalar` accepts any tag |
| Semantic value type, unit, reduction, comparison direction | Skywright `MetricSpec` | None; these fields are absent from TensorBoard's metric schema |
| Step/wall-time scalar event encoding | TensorBoard/PyTorch | `SummaryWriter.add_scalar` and event protobufs |
| Local visualization and tag organization | TensorBoard | Native Scalars/Time Series UI |
| Reading metric series | TensorBoard-compatible reader | Data Provider is useful as a model, but its API is explicitly experimental |

Do **not** make `tensorboard.plugins.hparams.api.Metric` Skywright's public metric type. It can describe a tag/group, display name, description, and training-vs-validation dataset, but it has no unit, numeric type, reduction policy, or optimization direction. It also does not gate scalar writes.

## Evidence

### 1. PyTorch's TensorBoard writer is a sink, not a schema

- PyTorch officially exposes `torch.utils.tensorboard.SummaryWriter`; its documentation describes `add_scalar(tag, scalar_value, global_step, walltime, new_style, double_precision)` and identifies `tag` simply as the data identifier. There is no declaration/registry parameter and no unit, reduction, or direction parameter. ([PyTorch TensorBoard documentation](https://docs.pytorch.org/docs/stable/tensorboard.html), [implementation](https://github.com/pytorch/pytorch/blob/62885a375afb5fee9898ec8e5c9e41ec37ec1c01/torch/utils/tensorboard/writer.py#L347-L386))
- The implementation constructs a scalar `Summary` for every supplied tag and writes it. There is no lookup against a prior experiment declaration, so an arbitrary new tag creates a new time series rather than being rejected. ([PyTorch `add_scalar` implementation](https://github.com/pytorch/pytorch/blob/62885a375afb5fee9898ec8e5c9e41ec37ec1c01/torch/utils/tensorboard/writer.py#L347-L386))
- PyTorch converts an accepted scalar to a Python `float`. Old-style summaries use the protobuf `simple_value` float field; new-style summaries use a rank-zero `DT_FLOAT` tensor by default or `DT_DOUBLE` when `double_precision=True`. This is an encoding choice, not a declared semantic type such as count, ratio, duration, or integer. ([PyTorch scalar encoder](https://github.com/pytorch/pytorch/blob/62885a375afb5fee9898ec8e5c9e41ec37ec1c01/torch/utils/tensorboard/summary.py#L354-L394))
- PyTorch directly depends on the separately installed `tensorboard` Python package for this integration and currently checks only that its version is at least 1.15. The official PyTorch docs instruct users to install TensorBoard, and the adapter does not require training code to use TensorFlow. ([PyTorch adapter initialization](https://github.com/pytorch/pytorch/blob/62885a375afb5fee9898ec8e5c9e41ec37ec1c01/torch/utils/tensorboard/__init__.py#L1-L18), [PyTorch TensorBoard documentation](https://docs.pytorch.org/docs/stable/tensorboard.html))

**Implication:** Skywright can safely build its PyTorch-facing implementation around `SummaryWriter`, but must wrap it to enforce a stronger contract.

### 2. TensorBoard's core protobufs provide extensible event metadata, not the required metric schema

- A TensorBoard `Summary.Value` has a string `tag`, optional `SummaryMetadata`, and one encoded value such as `simple_value` or a `TensorProto`. This is a flexible visualization/event envelope. ([TensorBoard `summary.proto`](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/compat/proto/summary.proto#L73-L132))
- Generic `SummaryMetadata` contains plugin identity and opaque plugin-owned bytes, a display name, a Markdown description, and a broad `DataClass`. It has no unit, semantic numeric type, reduction, or comparison-direction field. ([`SummaryMetadata`](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/compat/proto/summary.proto#L22-L64))
- `DATA_CLASS_SCALAR` constrains an associated tensor to rank zero and `DT_FLOAT`; it distinguishes the storage shape/class, not a domain-level metric type. ([`DataClass` definition](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/compat/proto/summary.proto#L43-L64))
- `plugin_data.content` is extensible, but it belongs to the named plugin. The official scalar plugin's payload currently contains only a schema-version integer. Repurposing the scalar plugin's opaque bytes for a Skywright schema would create a private convention rather than reuse a standard TensorBoard metric type. ([scalar plugin schema](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/plugins/scalar/plugin_data.proto#L15-L27))

**Implication:** the protobuf format is worth reusing, but Skywright's catalog should be a separate, library-owned schema. If the catalog is embedded into TensorBoard events later, it should use an explicitly Skywright-owned plugin payload or a separate catalog summary/artifact, not undocumented fields under the scalar plugin name.

### 3. HParams offers a partial declaration, but it is optional and display-oriented

- TensorBoard's HParams API can write a top-level experiment configuration before models are trained. Its official tutorial explicitly says this setup is optional and describes it as a way to enable filtering and specify which metrics are displayed. ([official HParams tutorial](https://www.tensorflow.org/tensorboard/hyperparameter_tuning_with_hparams), [configuration implementation](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/plugins/hparams/summary_v2.py#L113-L178))
- `hp.Metric` has only `tag`, optional run-directory `group`, display name, description, and dataset type (`TRAINING` or `VALIDATION`). It models every metric as a real-valued scalar series. ([HParams `Metric`](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/plugins/hparams/summary_v2.py#L545-L598), [`MetricInfo` protobuf](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/plugins/hparams/api.proto#L119-L172))
- HParams does define aggregation choices (`AVG`, `MEDIAN`, `MIN`, `MAX`) and ascending/descending sort order, but these belong to a `ListSessionGroupsRequest`: they tell a particular read/UI request how to aggregate sessions and sort results. They are not persistent per-metric reduction or “higher/lower is better” declarations. ([HParams request schema](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/plugins/hparams/api.proto#L275-L400))
- PyTorch's `SummaryWriter.add_hparams` generates an HParams experiment record from the keys in one supplied `metric_dict` and then writes each value through `add_scalar`. Its API does not accept the richer HParams display metadata and does not establish a global gate for later `add_scalar` calls. ([PyTorch `add_hparams`](https://github.com/pytorch/pytorch/blob/62885a375afb5fee9898ec8e5c9e41ec37ec1c01/torch/utils/tensorboard/writer.py#L300-L345), [PyTorch HParams protobuf construction](https://github.com/pytorch/pytorch/blob/62885a375afb5fee9898ec8e5c9e41ec37ec1c01/torch/utils/tensorboard/summary.py#L168-L349))

**Implication:** Skywright may translate part of its own `MetricSpec` catalog into HParams metadata for the dashboard, but HParams cannot be the canonical schema and does not provide undeclared-metric enforcement.

### 4. TensorBoard's read model is useful, but does not recover the missing semantics

- TensorBoard's `DataProvider` defines read-side methods such as `list_scalars`, `read_scalars`, and `read_last_scalars`, keyed by experiment, plugin, run, and tag. Scalar data is exposed as step, wall time, and a floating-point value. ([Data Provider overview](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/data/provider.py#L15-L105), [scalar read methods](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/data/provider.py#L185-L290), [scalar result types](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/data/provider.py#L821-L966))
- `ScalarTimeSeries` metadata includes max step/time, plugin bytes, description, display name, and optionally a latest value. It has no unit, semantic type, reduction, or comparison direction. ([`ScalarTimeSeries`](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/data/provider.py#L821-L935))
- The source labels `DataProvider` an “Experimental framework” and says its APIs are under development and subject to change. It is primarily an interface implemented by TensorBoard data backends, not a stable project-facing write contract. ([Data Provider stability notice](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/tensorboard/data/provider.py#L15-L40))
- TensorBoard's official README also documents direct event-file reading through `summary_iterator`; this is a lower-level export path rather than a declared schema. ([TensorBoard README, “How can I export data?”](https://github.com/tensorflow/tensorboard/blob/0ac8bfca54de6678c074944bb1e97918904a5f49/README.md#how-can-i-export-data-from-tensorboard))

**Implication:** keep TensorBoard event compatibility so Skywright can read or export series with existing tooling, but persist the Skywright metric catalog alongside each run because it cannot be reconstructed from scalar events alone.

## Recommended contract shape

The minimum library-owned declaration is conceptually:

```python
MetricSpec(
    name="train/loss",
    value_type=MetricValueType.FLOAT,
    unit="loss",                  # controlled vocabulary or explicit dimensionless
    reduction=MetricReduction.MEAN,
    comparison=MetricComparison.MINIMIZE,
    display_name="Training loss",
    description="...",
)
```

The exact field names are a Skywright design decision, but their behavior should be:

1. At Run Context creation, merge library-predefined and project-defined specs and reject duplicate/conflicting declarations.
2. At `record(name, value, step)`, reject an undeclared name, incompatible/non-scalar value, invalid step, and any policy-invalid value before touching the TensorBoard writer.
3. Emit the accepted point with `SummaryWriter.add_scalar`, using the canonical metric name as the TensorBoard tag.
4. Persist the complete metric catalog as run metadata in the Run Store. This is the authoritative schema used for cross-run comparison and later validation.
5. Optionally emit the compatible subset (`tag`, group, display name, description, training/validation role) as HParams experiment metadata. Treat this as a dashboard adapter, not the source of truth.
6. Do not expose the raw `SummaryWriter` from the project-facing Run Context. If advanced direct TensorBoard logging is ever needed, separate it from governed metrics so it cannot masquerade as a declared metric.

This is not a reinvention of metric storage or visualization. Skywright owns only the policy TensorBoard intentionally does not model, while delegating event writing and display to TensorBoard.

## Could not establish

- No official TensorBoard or PyTorch source examined defines a standard metric **unit** vocabulary or dimensional-analysis type.
- No official source defines a persistent per-metric training reduction/aggregation policy. HParams aggregation is read-request-level aggregation across sessions.
- No official source defines persistent “minimize/maximize” semantics for a metric. HParams ascending/descending order is a request/UI sort choice.
- No official source provides a mode in which `SummaryWriter` rejects scalar tags missing from an HParams experiment configuration.
- The current Data Provider interface is explicitly unstable, so it should not become a hard public dependency without version pinning or an adapter boundary.

## Source versions

- TensorBoard repository commit `0ac8bfca54de6678c074944bb1e97918904a5f49` (`master`, queried 2026-08-04).
- PyTorch repository commit `62885a375afb5fee9898ec8e5c9e41ec37ec1c01` (`main`, queried 2026-08-04).
- Official TensorFlow/TensorBoard and PyTorch documentation queried 2026-08-04.
