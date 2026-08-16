# Run Configuration library properties

Generated from the content-addressed Skywright Configuration Schema. Do not edit by hand.

| Property | Type | Constraints/default | Unit | Resume compatibility | Description |
| --- | --- | --- | --- | --- | --- |
| `reproducibility.seed` | `"integer"` | default `0`; minimum `0`; maximum `9223372036854775807` | integer seed | must-match | Seeds library-managed deterministic random number generators before project code runs. |
| `dataset.ordering.policy` | `"string"` | default `"deterministic-shuffle"`; enum `["deterministic-shuffle"]` | policy name | must-match | Selects the library-owned Dataset Item Sequence ordering policy. |
| `checkpoint.cadence` | `"integer"` | default `100`; minimum `1` | Steps | may-change | Maximum completed Steps between periodic durable checkpoints. |
| `checkpoint.retention` | `"integer"` | default `3`; minimum `1` | checkpoints | may-change | Minimum number of newest periodic checkpoints retained by the library. |
| `metrics.flushInterval` | `"number"` | default `10`; exclusiveMinimum `0` | seconds | may-change | Maximum wall-clock interval between flushing pending metric events. |
| `metrics.segmentRoll` | `"integer"` | default `1000`; minimum `1` | events | may-change | Maximum metric events written before rolling a TensorBoard event segment. |
| `metrics.systemSamplingInterval` | `"number"` | default `10`; exclusiveMinimum `0` | seconds | may-change | Wall-clock interval between library-owned system metric observations. |
