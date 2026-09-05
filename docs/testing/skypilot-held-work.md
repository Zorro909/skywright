# Held SkyPilot work

Issue [#214](https://github.com/Zorro909/skywright/issues/214) qualifies the concurrency and shutdown boundary required by [ADR 0009](../adr/0009-drive-skypilot-through-its-python-sdk.md).

`PackagedHeldSkyPilotIT` starts the pinned real SkyPilot API server and launches `OrchestratorQualificationMain held` from the packaged application JAR in a separate JVM. An HTTP proxy holds `/api/stream` before sending response headers. The parent observes that request on the wire before allowing the child to measure cancellation and health latency. A second fresh JVM also holds a status initiation request and fills both queues. Each scenario stops the real API server and checks shutdown without releasing its held requests. The first scenario probes the unreachable server; the second keeps the shared short-call lane saturated until shutdown. A zero JVM exit status is part of the assertion.

The fixture uses one active worker and one queued completion, and one active worker and two queued status calls. Additional calls, including catalogue-price requests, must report `SATURATION` within 100 ms. Catalogue lookups share the held-work lane with completion reads, so pricing traffic cannot create extra context workers or bypass queue limits. An unreachable server must report `REACHABILITY`, independently of queue saturation. Active and queued calls must finish as `SHUTDOWN` when the bridge closes.

The assertions allow 2 seconds each for cancellation and health, 6 seconds for an unreachable health probe, and 5 seconds for shutdown with a 100 ms grace period. Production defaults are eight queued short calls, four queued held calls and five seconds of shutdown grace. Status, submission, cancellation, cleanup and health share one platform thread. Completion and catalogue access share a second platform thread. A stalled short call delays other short calls, as selected in the maintainer decision on #214. A rejected refresh returns the cached availability without starting another worker.

On Linux amd64 with GraalPy 25.2.4 and SkyPilot 0.13.0, the restored two-lane implementation measured the following on 2026-09-05:

| Operation | Completion held | Completion and short call held |
| --- | ---: | ---: |
| Cancellation initiation before holding the short call | 1193 ms | 1312 ms |
| Health before holding the short call | 9 ms | 9 ms |
| Held queue admission and saturation | less than 1 ms | less than 1 ms |
| Catalogue saturation | 1 ms | 1 ms |
| Short-call queue saturation | not saturated | less than 1 ms |
| Health after the API server stopped | 15 ms | not attempted: shared queue full |
| Shutdown | 1342 ms | 1103 ms |

HTTPS qualification is tracked separately in [#248](https://github.com/Zorro909/skywright/issues/248). The unchanged locked runtime lacks an SSL constant required by urllib3, so its HTTPS SDK calls fail before the concurrency scenario. This qualification covers HTTP. Cross-distribution native-wheel compatibility in the production image is tracked in [#250](https://github.com/Zorro909/skywright/issues/250). In particular, Fedora-built cryptography requires an OpenSSL ABI absent from the Ubuntu runtime. The packaged-JAR test uses native resources built for its host; a successful standard-library health probe does not establish native SDK compatibility.

These are local measurements, not service latency guarantees. The test measures each run and saves the packaged JVM output under `backend/target/service-logs/{held,held-control}-sdk-qualification.log`.

## Production boundary

Initialization and lifecycle bookkeeping remain serialized. SDK execution uses two bounded platform-thread lanes in the single shared native-capable GraalPy context. SDK imports serialize on their own lock and happen on the first SDK operation. The initial health probe only needs the standard library. SkyPilot's contextual environment carries the resolved backend authorization token for each invocation, including overlapping credential revisions. Vault projection usage continues to cover the whole invocation.

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
