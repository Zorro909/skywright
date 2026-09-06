# Runtime history and request measurements

[Issue #216](https://github.com/Zorro909/skywright/issues/216) keeps production memory bounded after publication. The Run Store owns Metric Observation, Artifact and Sample history. `TrainingProcessResult` contains outcome, attempt, termination report and final checkpoint identity. Tests that need a complete in-memory history supply an explicit recording double.

## Measurement delivery

Python `RunStoreRecorder` and `RunStoreReader`, and Java `S3RunStoreObjectStore`, retain at most 256 recent operation measurements by default. Callers can select another positive capacity. A locked deque replaces the Python lifetime list and Java `CopyOnWriteArrayList`; insertion does not copy the retained history.

`measurements` is a diagnostic snapshot. Python `drain_measurements()` and Java `drainMeasurements()` atomically transfer a bounded immutable batch. Each producer has a UUID. Python `sequence` and Java `requestNumber` increase across drains, so a producer plus its sequence identifies an observation even when another client or recovered process starts numbering at one. Python `request_number` retains its existing meaning as the retry-attempt number within one operation.

Records preserve Run identity, storage provenance, operation, bytes, direction, timestamp and success/failure. On overflow, the oldest records leave the buffer and one gap records their inclusive sequence range and earliest/latest timestamps. This is missing usage, never zero usage. The buffer keeps no growing list of gap objects. After a drain, a subsequent overflow starts a new range.

Storage does not wait for a diagnostic consumer. This follows [ADR 0017](../adr/0017-derive-run-cost-estimates-from-attributed-usage.md), which requires accounting gaps to remain visible without stopping the Run. The buffer does not persist accounting facts in the Run Store. [#68](https://github.com/Zorro909/skywright/issues/68) must consume these batches, preserve gap evidence, provide durable delivery and deduplicate producer/sequence identities. A drained batch remains the consumer's responsibility until its handoff succeeds. In-process drains do not survive a crash. An empty drain can repeat the previous watermark.

These are adapter operation measurements, not invoice charges. Local presigning remains distinguishable by operation name. Python retries produce separate identified records; the Java adapter measures SDK invocations and does not establish the number of transparent provider retries.

## Long-run qualification

The standalone `sdk/tests/support/runtime_history_scenario.py` runs the actual Training Process Boundary, context, background System Metric sampler, metric segment writer, checkpoint publisher and S3 recorder against the pinned SeaweedFS service. Its synthetic Dataset commits 3,000 Steps. Each Step publishes one loss, two Step System Metrics, one background memory observation, a 64 KiB Artifact and a 64 KiB Sample. The fixture releases one sampling permit per Step and reads process RSS, making background publication deterministic. Only the final checkpoint is requested.

After training, an independent consumer reads and checks every output's bytes and digest, every metric's Step, each loss value, final Progress Record and checkpoint state. All 6,000 outputs, 12,000 observations and the counter checkpoint were readable in both baseline and fixed runs. The run produced 188 Metric Segments; its Artifact and Sample payloads total 375 MiB.

The observer keeps only 64 weak references for metrics and 64 for output records. It does not retain their payloads. RSS is sampled after garbage collection at Step 512 and every 256 Steps thereafter, through Step 2816. History verification occurs after those measurements. The budgets are less than 32 MiB RSS growth above the first warmed sample, at most four live observed metrics, zero live published output records, and at most 256 retained operation diagnostics.

The first measurement drain is deliberately delayed until Step 512. Later drains occur every 32 Steps, and a final drain follows termination. Each batch is written to a temporary test trace, checking every sequence against either a retained record or the explicit missing range. The fixed run delivered 5,862 measurements and reported 898 missing identities, covering all 6,760 measured operations. The run continued and its persisted training history remained complete despite that deliberate accounting gap.

Measurements on Linux amd64, Python 3.12.11 and boto3 1.43.73 on 2026-09-06:

| Observation | Baseline `053d3e7` | Fixed `ed23258` |
| --- | ---: | ---: |
| RSS growth after warm-up | 291.02 MiB | 0 MiB |
| Live observed metric objects | 64 of 64 tracked | 1 |
| Live observed output records | 64 of 64 tracked | 0 |
| Maximum measurements seen before a drain | 6,760 | 256 |
| Persisted outputs verified | 6,000 | 6,000 |
| Persisted observations verified | 12,000 | 12,000 |

The baseline completes persistence verification and then fails the memory-budget assertion. These sampled workload results do not define an arbitrary-payload memory limit. A pending output or Step still needs its own working memory, and metric segment size remains controlled by the existing Run Configuration.

Java's real S3 test warms up with 512 reads and measures another 4,096 reads in four batches. All bodies match the persisted object. The buffer stays at 256 entries and reports the other 3,840 identities as a gap. With OpenJDK 25.0.4 / GraalVM CE 25.2.4, heap usage after each batch was 27,975,544 / 28,169,504 / 28,335,704 / 28,487,968 bytes, a 512,424-byte increase. Batch times were 894 / 848 / 750 / 698 ms. A unit test also exercises 40,000 concurrent insertions and verifies sequence conservation. This heap check does not measure native network buffers.

Exact source identities, platform/image versions and raw samples are in [runtime-history.json](runtime-history.json).

## Reproduce

Run from the repository root after `scripts/setup-worktree`. The smaller CI scenario exercises the same path:

```sh
uv run --project sdk --locked --group ml-test pytest -m integration \
  sdk/tests/integration/test_run_store_system.py -k runtime_history
```

Run the representative workload with an automatically cleaned-up real service:

```sh
uv run --project sdk --locked --group ml-test python - <<'PY'
import subprocess
import sys
from pathlib import Path
sys.path.insert(0, str(Path.cwd() / "sdk/tests"))
from integration.test_run_store_system import seaweedfs
with seaweedfs() as (endpoint, client):
    client.create_bucket(Bucket="runtime-history")
    subprocess.run([
        sys.executable, "sdk/tests/support/runtime_history_scenario.py",
        "--endpoint", endpoint, "--bucket", "runtime-history", "--expect-bounded",
    ], check=True)
PY
```

The runner can load baseline SDK source through `PYTHONPATH`; it does not require the new drain API merely to run the persistence and memory comparison. Its credentials belong to the disposable local fixture.

```sh
mvn -pl backend -am \
  -Dtest=RunStoreMeasurementsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=RunStoreS3IT -Dfailsafe.failIfNoSpecifiedTests=false \
  -DskipFrontendInstall=true -DskipFrontendTests=true verify
```
