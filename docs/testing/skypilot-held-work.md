# Held SkyPilot work

Issue [#214](https://github.com/Zorro909/skywright/issues/214) qualifies the concurrency and shutdown boundary required by [ADR 0009](../adr/0009-drive-skypilot-through-its-python-sdk.md).

`PackagedHeldSkyPilotIT` starts the pinned real SkyPilot API server and launches `OrchestratorQualificationMain held` from the packaged application JAR in a separate JVM. An HTTP proxy holds `/api/stream` before sending response headers. The parent observes that request on the wire before allowing the child to measure cancellation and health latency. It then holds a status initiation request and fills both queues. Finally it stops the real API server and checks shutdown without releasing either held request. A zero JVM exit status is part of the assertion.

The fixture uses one active worker and one queued completion, and one active worker and two queued status calls. Additional calls must report `SATURATION` within 100 ms. An unreachable server must report `REACHABILITY`, independently of queue saturation. Active and queued calls must finish as `SHUTDOWN` when the bridge closes.

The assertions allow 2 seconds each for cancellation and health, 6 seconds for an unreachable health probe, and 5 seconds for shutdown with a 100 ms grace period. Production defaults remain eight queued calls for each control, submission and cancellation lane, four queued completions, one queued health probe, and five seconds of shutdown grace. Each lane has one platform thread.

On Linux amd64 with GraalPy 25.2.4 and SkyPilot 0.13.0, the HTTP qualification on 2026-09-05 measured:

| Operation | Milliseconds |
| --- | ---: |
| Cancellation initiation while completion was held | 934 |
| Health probe while completion was held | 12 |
| Held queue admission and saturation | less than 1 |
| Control queue admission and saturation | less than 1 |
| Health after the API server stopped | 10 |
| Shutdown with two held requests and three queued calls | 1111 |

HTTPS qualification is tracked separately in [#248](https://github.com/Zorro909/skywright/issues/248). The unchanged locked runtime lacks an SSL constant required by urllib3, so its HTTPS SDK calls fail before the concurrency scenario. This qualification covers HTTP.

These are local measurements, not service latency guarantees. The test measures each run and saves the packaged JVM output under `backend/target/service-logs/held-sdk-qualification.log`.

## Production boundary

Initialization and lifecycle bookkeeping remain serialized. SDK execution uses the existing bounded platform-thread lanes in the single shared native-capable GraalPy context. SDK imports finish before concurrent entry is admitted. SkyPilot's contextual environment carries the resolved backend authorization token for each invocation, including overlapping credential revisions. Vault projection usage continues to cover the whole invocation.

A Python audit hook tracks live sockets owned by this context, including wrappers created around existing descriptors. Shutdown closes admission, drains for the configured grace, rejects queued work, and shuts down the tracked sockets to wake native reads. The client waits up to four seconds for active invocations to leave before closing the context. Failure to quiesce raises an error instead of destroying a context still executing native code. This guard does not qualify arbitrary native extension deadlocks.

The original regression timed out on cancellation while `synchronized invoke` held the Java monitor. Removing that monitor allowed cancellation and probing to finish, but shutdown still exceeded five seconds inside a native socket read. `Context.interrupt` alone also timed out. Socket shutdown preserves the synchronous SDK's existing typed result and failure decoding.

`GraalPySkyPilotClientIT` additionally exercises catalogue, submission, duplicate submission, status, cancellation, cleanup and typed completion. It checks overlapping authorization tokens on two platform threads in the same real native context and verifies that neither token remains in the shared environment afterward.

## Reproduce

Run from the repository root after `scripts/setup-worktree`:

```sh
mvn -pl backend -am \
  -Dtest=OrchestratorContractTest,LocalProjectionBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=GraalPySkyPilotClientIT,PackagedHeldSkyPilotIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Native dependency startup is included in the test's outer deadline and excluded from the held-call latency measurements.

## Runtime references

- [GraalPy native extension embedding](https://github.com/oracle/graalpython/blob/master/docs/user/Embedding-Native-Extensions.md) describes the single-context and platform-thread constraints.
- [GraalVM Context lifecycle](https://www.graalvm.org/25.0/javadoc/sdk/org/graalvm/polyglot/Context.html) documents interruption and concurrent closure.
- [Python socket shutdown](https://docs.python.org/3/library/socket.html#socket.socket.shutdown) documents waking a connection before closure.
