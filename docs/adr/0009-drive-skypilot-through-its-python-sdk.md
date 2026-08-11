---
status: accepted
---

# Drive SkyPilot through its Python SDK, in-process under GraalPy

The backend reaches SkyPilot through SkyPilot's own Python SDK, never through the REST API server directly and never through the `sky` CLI. That SDK runs in-process inside the Java backend under GraalPy. The Skywright Python library is not involved: it stays runtime-only, and the backend reaches object storage past it with a Java S3 client of its own.

## Why not REST, and why not the CLI

SkyPilot is a client-server system. Every CLI and SDK call is HTTP to an API server, so "avoid a server-side Python process" was never an available option — one is mandatory either way. The only real question was which client speaks to it, and the SDK is not a thin wrapper over that HTTP: it builds `Task`/`Dag` payloads, drives the request-id state machine, decodes typed results, retries transient errors, and resumes interrupted log streams.

Speaking HTTP instead of Python means reimplementing all of that against a surface that is undocumented as a client contract. A JVM client must pin HTTP/1.1 or its first mutation dies on a bare uvicorn `400` while its reads keep succeeding; the operation handle arrives only in an undeclared header; every mutation declares its success schema as empty; failures answer HTTP 500 with an undeclared shape; and a cluster `handle` is a pickle that cannot be decoded outside Python. The CLI is better than REST for reads — `sky status -o json` decodes that very handle into flat named fields — but has no machine output for `launch`, `logs`, `check` or `down`, requires a task materialised to a file on disk, and reduces a mutation's result to an exit code. In Python none of these exist: the handle is a live object, the task is a `Task`, a failure is an exception.

The deciding argument is not ergonomics but ownership. Mapping a Run Definition to a SkyPilot task is Skywright domain logic — image digest per accelerator backend, Dataset Lease, Run Store location, capability-to-resource translation. Expressing it in Java over a blind YAML string splits the Run Definition's meaning across two languages with no compiler on either side of the seam.

## Where the Python runs

In-process under GraalPy, in JVM mode. Native-image is out of scope for this map: nothing in the requirements asks for fast startup or low resident memory, and admitting it would force the arrangement to be proven twice.

[Prototype #32](https://github.com/Zorro909/skywright/issues/32) established that GraalPy 25.2.4 can carry SkyPilot 0.13.0's client dependency tree: `import sky`, a real launch, a held operation, typed status and cluster-handle traversal, and a Run-Definition-shaped Java record crossing as a task all succeeded. The in-process arrangement is therefore selected. The fixed fallback remains an out-of-process Python service speaking the same SDK. It is explicitly **not** a return to REST or the CLI, because everything that disqualified them concerns speaking HTTP instead of Python and is untouched by where the Python runs. Under the fallback the service's entry point is a transport shim, not a home for domain logic.

## The seam

A Run Definition is mapped with MapStruct into a typed Java DTO tree mirroring SkyPilot's task YAML schema, and only then serialized across the polyglot boundary. The task YAML schema is the one part of SkyPilot's surface that is publicly documented, which makes it the right thing to bind to; binding to it in Java records turns an invisible contract into one a compiler checks, and gives MapStruct something to report when a Run Definition field goes unmapped. Mapping straight to an untyped map would reintroduce exactly the blindness that disqualified REST.

## Operations and concurrency

Nothing at the source is synchronous — even reads are a request-id state machine — so control actions are modelled uniformly as Orchestrator Operations. The backend's own API returns as soon as SkyPilot has accepted a request and an Orchestrator Operation exists; it never blocks for a provisioning cycle, and the outcome is read back through the ordinary read path. This is what satisfies Q4 structurally: the result is always observed from the source, never inferred from having asked. ADR 0005 requires SkyPilot to be reachable at submission, and holding an Orchestrator Operation is what proves it.

An Orchestrator Operation is never durable. SkyPilot retains requests for one day, so persisting one would store a pointer that outlives its referent, and ADR 0005 already forbids storing what SkyPilot returns. A backend restart therefore loses the progress view of an in-flight operation but never its effect: correlation is a pure function of the run identity and the derived job name, and submission is idempotent on that name.

Control calls share one long-lived GraalPy context served by a dedicated platform-thread executor. GraalPy documents native extension modules as incompatible with Java virtual threads, which a Spring Boot application would otherwise reach for.

The binding rule is that **long-held work must never be able to block a control call**. The prototype settled the mechanism: two platform threads share the one Context. While one thread held `sky.stream_and_get()` for a launch, the other completed `sky.status()` in 1,491 ms; the held stream finished after 21,624 ms. A second Context is neither required nor part of the design. This avoids the Linux-only, experimental `python.IsolateNativeModules` path and its native-extension compatibility risk.

Whether live logs cross this boundary at all is a separate decision, and ADR 0005 has already placed job logs in the Run Store, which the backend now reads directly.

## Deployment and versions

The API server is long-lived and Skywright-operated. A per-call server auto-started by the client cannot own what runs inside it: in default-mode Managed Jobs, provisioning, monitoring and preemption recovery execute in the API server, so one that dies mid-run takes R6's recovery with it.

Because the SkyPilot client is now a dependency of the backend's own build while the API server deploys separately, the two can drift apart in a way `sky` and its server never can. They are therefore built from one pinned SkyPilot version and identified by digest, as ADR 0006 already does for training images, and upgrading SkyPilot is a deliberate paired deployment. The alternative is owning a compatibility matrix for a protocol whose version guarantees are undocumented.

The proven embedding uses an external Maven-plugin-managed environment, the native POSIX backend, a 16 MiB JVM thread stack, and a generic `Context.Builder` configured with `GraalPyResources.forExternalDirectory(...)`. The resource adapter connects `${root}/venv` and `${root}/src`; it is not a separate builder type, and the older `GraalPyResources.contextBuilder(Path)` convenience method is deprecated in 25.2.4. Implementation owns the remaining host, I/O, native, thread, and polyglot access policy explicitly.

The prototype's calls to no-argument `Context.close()` crashed in native thread-local teardown. This is an implementation risk, not a failed mechanism criterion: the spike did not test the first-party Spring lifecycle, which calls `context.close(true)` from `@PreDestroy`. Implementation uses that lifecycle and verifies shutdown after quiescing its executor. Native teardown is investigated further only if the crash recurs; the out-of-process service remains the deployment fallback if it becomes an implementation blocker.

Availability follows ADR 0005 unchanged, with one addition: a failure to reach SkyPilot is a single condition regardless of where it occurred. A user can do nothing differently on learning that a bridging process rather than the API server is down, and keeping the two indistinct is what stops the GraalPy-versus-service fork from leaking out of deployment and into the domain model.

## Consequences

The backend's deployable now embeds a Python runtime and SkyPilot's client. The prototype measured a 21.254-second `import sky`, a 154 MiB executable-JAR delta, and a 223 MiB external GraalPy/SkyPilot environment. Its startup pays that import cost once, and its build is coupled to a 96-package Python dependency resolution that it did not previously have.

Control throughput is bounded by the width of that executor. This is expected to be irrelevant against an API server that does the real work, but it is a real ceiling and a real failure mode if any call that should be short turns out to block.

Native-image is foreclosed while this stands. Should it ever become a requirement, GraalPy embedding is the thing that would have to be proven under it.

Any SkyPilot behaviour reachable only from a live Python object — the cluster `handle` above all — is now available to the backend. That is a benefit, and also a temptation: reaching into SkyPilot internals is easier than it was, and Q1 still forbids holding an authoritative copy of anything a source answers for.
