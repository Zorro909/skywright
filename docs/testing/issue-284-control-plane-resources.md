# Control-plane resource exhaustion

Investigation for [#284](https://github.com/Zorro909/skywright/issues/284),
2026-09-10. The incident image was built from `f4bb6616d5757716370ba1f26fde4eba80416e15`.
Current main was verified against origin at
`5fbb1401a0e7fa5c54c41e78bb0eb88d14a4732b`.

## Confirmed descriptor growth

The reproductions invoke the actual `GraalPySkyPilotClient` from the incident
image and the current packaged application. They use isolated HTTP/HTTPS peers
and the unchanged SkyPilot server and SDK. The test TLS certificate is trusted;
certificate verification is enabled. No training, cloud credentials, real
telemetry service or retained database is needed for these reproductions.

Three failing paths were found:

1. The incident build's `urllib.request` health probe leaves the response owned
   by `HTTPError` unclosed. Successful probes release their sockets, but every
   HTTP 500 adds one descriptor. Java garbage collection did not reclaim those
   descriptors. Main had already replaced this path with Requests for #248.
2. SkyPilot's usage reporting calls `requests.post()` for individual SDK
   operations. These disposable sessions leave HTTPS pool sockets awaiting
   finalization under GraalPy. Both runtime versions reproduce growth.
3. Main's `requests.get()` health probe has the same disposable-pool problem
   when a peer keeps the connection alive. Consuming and closing the response
   is insufficient to synchronously dispose of that session's idle pool.

Requests creates a temporary Session for its module-level request functions.
The pinned urllib3 pool manager clears its references to pools, while each
connection pool registers `_close_pool_connections` with `weakref.finalize`.
This makes temporary pool cleanup depend on garbage collection. Some current
runtime samples fell after collection, but they did not return to the bounded
idle baseline. CPython reference-counting behavior is not a lifecycle guarantee
for the embedded native runtime.

The third path was initially hidden by a fixture sending `Connection: close`.
Removing that header changed a green test into a deterministic failure. The
permanent regression keeps connections alive.

| Workload | Warm sockets | After workload / idle | Result |
| --- | ---: | ---: | --- |
| Incident build, 160 successful HTTP probes | 2 | 2 / 2 | Bounded |
| Incident build, 160 HTTP 500 probes | 2 | 162 / 162 | Fails, including after forced GC |
| Incident build, 30 SDK status operations with local HTTPS telemetry | 5 | 111 / 111 | Fails |
| Main runtime, 30 SDK status operations with local HTTPS telemetry | 7 | 65 / 65 | Fails |
| Incident build, 80 SDK status operations with supported telemetry opt-out | 1 | 1 / 1 | Bounded |
| Main, first 50 mixed probes against a persistent HTTP peer | 4 | 102 | Regression fails immediately |
| Fixed bridge, 200 mixed probes against that peer | 4 | 4 / 4 | Bounded |

Counts include both client and server sockets when a diagnostic peer runs in
the same JVM. The packaged SDK regression runs the peer in the parent process
and counts only the child application's descriptors. Before the fix, its first
ten status/probe pairs grew the child from 2 sockets to 26. After the fix, the
same regression passes and the telemetry peer receives zero requests, even when
the parent environment tries to enable collection.

The original 54,000 descriptors cannot all be attributed retrospectively. The
process was already stopped and the incident TCP snapshot covered only part of
its descriptors. These reproductions confirm application-reachable causes
consistent with both idle polling and outbound HTTPS growth. They do not prove
the cause of the separate AMD display timeout or account for every byte of the
incident JVM heap.

## Fix and regression coverage

The bridge now owns one reusable health-probe Session. It clears cookies before
each probe so a credential rotation does not carry a previous probe's cookies.
The existing TLS verification adjustment remains in place. Session reuse follows
the [Requests session contract](https://requests.readthedocs.io/en/latest/user/advanced/#session-objects).

The GraalPy context sets `SKYPILOT_DISABLE_USAGE_COLLECTION=true`, SkyPilot's
supported opt-out. The API server image already used this setting. The bridge
does not need telemetry to submit, observe, complete or cancel operations.

Shutdown first interrupts sockets to wake blocked reads, waits for both execution
lanes to quiesce, then explicitly closes the remaining tracked sockets before
closing the health Session and GraalPy context. A socket `shutdown()` alone does
not release its descriptor. SkyPilot source, context count, operation ownership
and execution lanes remain unchanged.

`GraalPySkyPilotResourcesIT` exercises successful responses, HTTP 500, 401 and
403, malformed JSON, read timeout and shutdown during a held read. It checks
the socket bound after each batch, after idle time and after shutdown, while
recording heap and RSS. The fixed run stayed at 4 sockets during all four
batches, then returned to the test JVM's one-socket baseline. Its heap samples
were 134–249 MiB and RSS was 670–858 MiB during the workload. The timeout's
remote endpoint remains held deliberately until shutdown.

The separate [cancellation timing fixture](issue-252-cancellation-latency.md)
uses a six-GiB worker-sizing budget to avoid disposable workers when its local
CLI server receives overlapping requests. The resource regression and image
budgets remain four GiB; cancellation deadlines are unchanged.

`PackagedSkyPilotResourcesIT` invokes the packaged `sdk-resources` qualification
against an isolated stock SkyPilot server and a trusted HTTPS telemetry peer.
It completes forty real SDK status operations and health probes, checks a
baseline plus two socket allowance at every batch and after idle cleanup, and
asserts that no telemetry HTTP request was delivered. An empty controller result
or the stock server's typed `ClusterNotUpError` is accepted, depending on server
mode; bridge failures are not accepted as successful status completion.

Run from the repository root after preparing the native environment:

```bash
JAVA_TOOL_OPTIONS='-Xmx2g -XX:ActiveProcessorCount=2' \
  mvn -B -ntp -pl backend -am \
  -DskipFrontendTests=true -DskipFrontendInstall=true \
  -Dit.test=GraalPySkyPilotResourcesIT,PackagedSkyPilotResourcesIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
python3 -m unittest discover -s tests/deployment -p 'test_resource_limits.py' -v
```

The incident-image comparisons ran in separate containers with 4 GiB memory,
no swap, two CPUs, 256 tasks and a hard limit of 1,024 descriptors. They had
loopback-only networking or shared the isolated test API server's network
namespace. The workload had at most 160 requests and an early stop at 400
sockets. The stopped qualification deployments were not used to reproduce
host exhaustion.

## SkyPilot memory is separate

The incident server image computes worker counts from visible CPUs and memory.
Evaluating its actual sizing function for 32 CPUs and 61 GiB selects 32 API
server processes, 64 long-request workers and concurrency for 111 short requests.
This calculation was inspected without starting that many workers.

With a two-CPU, 4 GiB cgroup, the isolated server's idle snapshot was
1,509,388,288 bytes of cgroup memory. The process tree's RSS sum was 1,849,612 KiB,
while proportional set size was 1,450,366 KiB. Shared pages make the RSS sum
unsuitable for adding process totals. Individual persistent Python processes
used roughly 123–158 MiB of anonymous memory. This is worker-pool overhead,
independent of the backend's descriptor leak.

The separately launched current Skywright runtime-pull and collector entry
points passed health checks at 24,551,424 and 25,690,112 bytes of cgroup memory,
including the brief diagnostic Python process. Both report their 256-descriptor
hard limit. These are idle measurements, not peak archive-capture claims.

The original pod-level 13.41 GiB measurement cannot be split into its former
containers after deletion. The isolated measurements establish excessive
automatic server sizing as a separate reproducible contributor, without
claiming that the two sidecars shared the backend leak. SkyPilot documents
[resource limits as inputs to its concurrency sizing](https://docs.skypilot.ai/en/latest/reference/api-server/api-server-tunning.html).

## Deployment bounds

The base manifests now supply these budgets. Production operators can choose
larger measured budgets in their overlay; these defaults target the existing
small local workload, not the documented multi-user team sizing.

| Container | CPU request / limit | Memory request / limit | Descriptors per process |
| --- | --- | --- | ---: |
| Backend | 2 / 2 | 4 GiB / 4 GiB | 4,096 |
| SkyPilot server | 2 / 2 | 4 GiB / 4 GiB | 1,024, inherited by children |
| Runtime-pull | 0.1 / 1 | 128 MiB / 256 MiB | 256 |
| Log collector | 0.1 / 1 | 256 MiB / 768 MiB | 256, inherited by page workers |
| Application initialization containers | 0.1 / 1 | 64 MiB / 64 MiB | Runtime default |

The backend launcher assigns 50% of container memory to the maximum Java heap
and caps direct buffers at 256 MiB. The existing 16 MiB stack setting remains;
changing it without native-stack qualification would be a separate experiment.
The 4 GiB container therefore provides 2 GiB outside the maximum Java heap for
native Python extensions, JVM metadata, compiled code, stacks, buffers and tmpfs.
This allowance is not a claim that those allocations have independent hard caps.

An additional `sdk-resources` run used the actual Ubuntu backend image, its
launcher, a 4 GiB cgroup, two CPUs, 256 tasks and no swap. Sockets were 2 after
warmup, 3 throughout forty status/probe pairs and idle time, and 1 after close.
Heap samples were 1,032 to 1,186 MiB. RSS was 2,033 to 2,363 MiB.

Native Memory Tracking at startup reported a 2,048 MiB reserved Java heap,
967 MiB committed heap, and 1,278 MiB total committed JVM memory. Metadata
accounted for 104 MiB. The 29 threads reserved 301 MiB of stacks but committed
3 MiB at that sample. NMT does not account for all native Python allocations,
so the container and RSS measurements remain necessary. Reserved virtual
address space is not physical memory consumption.

Local deployments use `Recreate` to avoid overlapping old and new application
instances during an upgrade. Startup probes allow four minutes under the CPU
limits. PostgreSQL, retained volumes, Credential Bindings and writer custody are
not replaced by these changes. Descriptor limits are a containment measure;
they do not replace the lifecycle fix or establish a host-wide aggregate limit.

## Safe operation and diagnostics

Build and qualify the fixed images before starting the stopped incident
deployment. Applying limits to an old leaking image is not the fix. Use a
separate kind cluster for reproduction, with a bounded outer Podman container
as well as the application limits. Stop a test if a backend exceeds 512 sockets,
its memory reaches 3.5 GiB, a service reports OOM events, or host open files reach
16,384. These early-stop values leave room below the incident host's 65,536 limit.

For the retained incident cluster, the host kubeconfig endpoint may be stale.
The in-container administrative kubeconfig works independently of that endpoint:

```bash
node=skywright-issue233-control-plane
kube=(podman exec "$node" kubectl --kubeconfig=/etc/kubernetes/admin.conf -n skywright)
"${kube[@]}" get deployment,pods
"${kube[@]}" exec deployment/skywright-backend -- cat /proc/1/limits
"${kube[@]}" exec deployment/skywright-backend -- jcmd 1 GC.heap_info
"${kube[@]}" exec deployment/skywright-backend -- cat /sys/fs/cgroup/memory.current
"${kube[@]}" exec deployment/skywright-backend -- cat /sys/fs/cgroup/memory.events
cat /proc/sys/fs/file-nr
free -h
```

Once the fixed images and limits are staged at zero replicas, start the server
and then the backend under the monitoring thresholds above:

```bash
"${kube[@]}" scale deployment/skywright-skypilot-api-server --replicas=1
"${kube[@]}" rollout status deployment/skywright-skypilot-api-server --timeout=4m
"${kube[@]}" scale deployment/skywright-backend --replicas=1
"${kube[@]}" rollout status deployment/skywright-backend --timeout=4m
```

Count backend descriptors without dumping credentials or process environments:

```bash
"${kube[@]}" exec deployment/skywright-backend -- sh -c \
  'ls -l /proc/1/fd | awk "/socket:/ { n++ } END { print n+0 }"'
```

Normal shutdown preserves all retained state:

```bash
"${kube[@]}" scale deployment/skywright-backend --replicas=0
"${kube[@]}" scale deployment/skywright-skypilot-api-server --replicas=0
"${kube[@]}" get pods
cat /proc/sys/fs/file-nr
```

Wait for both application pods to disappear and verify that host descriptors
return near the pre-start baseline. Do not use `reset-local-state`, delete PVCs,
or remove `/var/lib/skywright-writer/custody` as resource-exhaustion mitigation.
Stopping the control plane suspends observation and control of running work;
finish or cancel test Runs before an ordinary shutdown.

## Local workflow qualification

The fixed backend and server images were built and tested before starting the
retained cluster. The Ubuntu GraalPy environment was qualified again inside a
bounded Ubuntu container using the repository's native qualification and seal
commands. This was a local qualification, not a new CI provenance claim.

The retained Deployments received the fixed images, resource bounds and
`Recreate` strategy while replicas were zero. Existing credential projections,
databases, storage registrations and volumes were retained. A monitor sampled
host open files and each application container every ten seconds, including
OOM counters and backend sockets, with the early-stop thresholds above.

Run `2bf8db52-a9ed-417e-9385-d0c1e2e514ca` used the existing qualified private
ROCm image and CIFAR dataset on the local RX 7900 XTX. The public submission
API accepted it with HTTP 202. It completed all 12 requested steps, and progress
reported durable step 12 with checkpoint
`skywright-checkpoint:v1:12:sha256:4fff2d70918929a81795ffd4cd7f6c2dc89ee51104c963b993c0aa59ab2b4206`.
The training pod was removed after completion.

The lifecycle response reported `state=finished` and `cause=completed`, but
also retained `handoff=uncertain`, `sourceAvailability=unavailable` and an
unlatched terminal observation. The durable progress proves training and
checkpoint completion; it does not establish that every source observation
or archive finalization path succeeded. That observation gap is a remaining
qualification limitation, outside this resource-lifecycle fix.

A backend restart preserved HTTP 200 reads of the Run, its durable checkpoint,
and both archived log streams. The task archive held 7,358 bytes and the
controller archive held 15,455 bytes. Both were still staging with completion
pending; task capture reported `SOURCE_GENERATION_UNCONFIRMED`. Archive
readability is verified, but finalization is not.

Across 51 samples covering startup, training, idle time and backend replacement:

| Container | Sampled peak cgroup memory | Limit |
| --- | ---: | ---: |
| Backend | 2,806,403,072 bytes, 2.61 GiB | 4 GiB |
| SkyPilot server | 2,170,789,888 bytes, 2.02 GiB | 4 GiB |
| Runtime-pull | 70,373,376 bytes, 67.1 MiB | 256 MiB |
| Log collector | 71,139,328 bytes, 67.8 MiB | 768 MiB |

All sampled OOM/kill counters were zero. Backend sockets ranged from 12 to 22
and settled at 16 to 17 after the restart and archive reads. Host open files
started at 7,036 and peaked at 9,759. Concurrent isolated tests also contribute
to that host total. These short runs establish working resource budgets, not
multi-day or multi-user capacity.

Validation passed:

- 284 backend unit tests.
- Both new native socket regressions, including forty packaged SDK operations.
- All eight packaged TLS and held-request cancellation/shutdown tests.
- All seven backend image tests and nine SkyPilot server image tests with the
  4 GiB container limits.
- All 67 native-environment quality tests. The deployment suite ran 68 tests
  with two existing skips that require root-owned writer custody.
- The retained-cluster GPU workflow and backend-restart checks above.

No SkyPilot SDK/server source was changed. The fix retains the accepted
architecture; explicit connection ownership, a supported SDK setting and
operational resource budgets were sufficient.

After qualification, both application Deployments were scaled back to zero
and their pods disappeared. The fixed images and limits remain staged for the
next deliberate startup. The isolated diagnostic containers were removed.
PostgreSQL, Vault, storage and the original writer pod remain running, and
writer custody was not deleted. The final host snapshot showed 7,021 open
files, about 11 GiB RAM used and 50 GiB available.
