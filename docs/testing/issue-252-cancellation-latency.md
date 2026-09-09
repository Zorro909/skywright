# Packaged cancellation latency

Issue [#252](https://github.com/Zorro909/skywright/issues/252) separates first-call
cost from the held-work regression required by [ADR 0009](../adr/0009-drive-skypilot-through-its-python-sdk.md).
The qualification keeps the existing two-second cancellation and probe limits,
100-ms queue-admission limit and five-second shutdown limit. It performs exactly
one cancellation before holding completion, within the existing ten-second
startup allowance. Cancellation timing ends when the SDK accepts the request;
it does not measure the time until a job reaches its terminal state. No deadline
or deadline scope is replaced.

## Reproduction and attribution

The original GraalPy 25.2.4 failure and one-core reproduction are recorded in
[the issue](https://github.com/Zorro909/skywright/issues/252#issuecomment-5555965528).
The current runtime also exceeds two seconds on its first cancellation:
GraalPy 25.3.4.1, SkyPilot 0.13.0 and GraalVM CE 25.3.4.1 with OpenJDK 25.0.4.1.
In successful [CI run 34378147639](https://github.com/Zorro909/skywright/actions/runs/34378147639),
the HTTP scenarios recorded first calls of 2,601/2,813 ms and held-work calls
of 1,927/1,745 ms. The first calls would exceed the original cold deadline.

On 2026-09-09, restricting the complete Maven, packaged JVM, proxy and real-server
process tree to CPUs 0 and 1 reproduced that crossing locally. The host was
Fedora 44 amd64 on an AMD Ryzen 9 7950X, with matching Fedora native resources.
All four scenarios completed with responses still held and JVM exit code 0.

| Measurement, milliseconds | HTTP held | HTTP held + control | HTTPS held, IP | HTTPS held + control, DNS |
| --- | ---: | ---: | ---: | ---: |
| First cancellation, total | 2,439 | 2,358 | 1,329 | 1,316 |
| First cancellation, client-thread CPU | 1,125 | 1,081 | 393 | 433 |
| First cancellation, upstream requests | 251 | 170 | 375 | 477 |
| Cancellation with completion held, total | 1,603 | 1,510 | 1,099 | 930 |
| Held cancellation, client-thread CPU | 729 | 687 | 160 | 181 |
| Held cancellation, upstream requests | 161 | 143 | 719 | 495 |
| Probe with completion held | 29 | 58 | 130 | 237 |
| Shutdown | 1,143 | 941 | 1,588 | 1,666 |

Both HTTP first calls exceeded two seconds before any completion was held.
Orchestrator dispatch added only 1/4 ms to their measured client calls. The
status lookup and cancellation requests accounted for 251/170 ms of upstream
time, so server waits do not explain the deadline crossings. First-call client
CPU work exceeded the later calls by 396/394 ms. The extra cost is chiefly on
the client side, including CPU work and runtime scheduling, rather than a held
completion preventing a control call from entering.

Thread CPU time and upstream wall time are diagnostic measurements, not a
complete additive profile. The remaining client-side wall time includes
scheduling, other JVM work and transport overhead; this qualification does not
attribute it to a particular GraalPy or JIT method. The warm HTTPS case also
shows that upstream waits vary independently of client CPU cost.

Repeating all four cases with CPUs 0 through 3 passed. First cancellations took
1,732/1,854/1,265/1,419 ms, and held cancellations took 1,323/1,356/628/624 ms
in the same scenario order. Probes took 27/32/82/106 ms and shutdown took
1,142/1,202/1,108/1,019 ms. Every packaged JVM exited with code 0.

## Qualification contract

`CancellationTimingClient` measures the real call on the serialized control
thread. `HeldSkyPilotProxy` records each cancellation's actual upstream method,
path, request state, status and elapsed time. Requests still waiting upstream
remain in failure evidence as `PENDING`; transport failures are `FAILED` and
interrupted requests are `INTERRUPTED`. Status 0 means no response was received.
The SDK still initiates and completes its normal
status lookup before submitting cancellation. Neither SDK nor server source is
modified, and the fixture does not return a synthetic SDK result.

The parent marks each measurement phase before allowing the child to proceed.
It requires exactly one `POST /jobs/cancel` in the startup phase and exactly one
while completion remains held. Startup measurements are printed immediately,
so a later timeout does not discard them. CPU accounting is initialized before
the startup operations. Observers do not initiate SDK calls or warm an extra
cancellation path.

The held-call timer still covers admission, client work and all remote waits.
There are no retries, latency subtraction or adaptive warm-up loops. The parent
observes the held response before starting that timer and keeps it held until
the packaged JVM exits. Serializing cancellation behind that response still
exhausts the two-second deadline. Both scenarios also retain probe, saturation,
unreachable-server and shutdown assertions.

The timing fixture requires at least two available logical CPUs. With the
current runtime, a one-core process-tree run exhausted the existing 120-second
startup wait in all four cases before any cancellation measurement was emitted.
The fixture now rejects that capacity before starting the server, with an
explicit prerequisite failure. This requirement applies to the timing fixture;
deployment resource sizing remains a separate concern.

## Evidence and commands

Each successful test prints `Packaged SDK evidence` with the platform, JVM,
available processor count, SDK version and client timings. `Cancellation wire
evidence` contains the upstream timings and process exit code. The same wire
evidence is saved beside each child log as `*.log.requests.json`, including
partial evidence when a test fails. The integration job retains both its
Failsafe reports and `backend/target/service-logs` artifacts.
If forced cleanup cannot terminate the child within its existing wait, the
wire evidence records `still-running` instead of discarding the diagnostics.

Run from the repository root after `scripts/setup-worktree`. Build the paired
JAR and test fixture before invoking the focused integration test:

```bash
mvn -B -ntp -pl backend -am package -DskipTests
qualification_cpus=$(python3 -c 'import os; print(",".join(map(str, sorted(os.sched_getaffinity(0))[:2])))')
taskset -c "$qualification_cpus" mvn -B -ntp -pl backend \
  -Dit.test=PackagedHeldSkyPilotIT \
  jacoco:prepare-agent failsafe:integration-test failsafe:verify
```

Selecting four available CPUs repeats the same qualification at higher
capacity. The normal real-service CI job runs the same four HTTP/HTTPS cases
and records its actual processor count; it does not infer latency from the
runner label. A two-second cold-cancellation service guarantee remains outside
this qualification.
