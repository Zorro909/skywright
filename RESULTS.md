# Spike result: use the in-process GraalPy boundary

All six requested mechanism checks passed on GraalPy 25.2.4, including a real launch and a typed cluster handle. This selects the in-process arrangement from ADR 0009; the out-of-process Python SDK service remains the fallback.

The spike also found a teardown risk, but did not test the relevant first-party Spring lifecycle. Calls to the no-argument `Context.close()` crashed the JVM with `SIGSEGV`, so the recorded end-to-end run used `Runtime.halt()` after completing its checks. GraalPy's official Spring Boot example instead calls `context.close(true)` from an `@PreDestroy` method. Because that path was not tested here, the crash does not overturn the successful mechanism result. Implementation must use and verify the supported lifecycle, and investigate the native teardown only if it recurs.

## Environment

- GraalVM CE 25.2.4, JDK 25.0.4, with runtime compilation enabled
- GraalPy 25.2.4 (Python 3.12.8)
- SkyPilot client and API server 0.13.0
- Spring Boot 4.1.0
- one GraalPy Context using the native POSIX backend
- two explicit platform threads for concurrent SDK work
- a real CPython SkyPilot API server at `127.0.0.1:46580`
- a disposable, CPU-only kind 0.32.0 cluster on Podman, context `kind-skywright-graalpy-spike`

The machine's original GraalVM 25.0.2 was too old for the 25.2.4 polyglot artifacts. The final results use the matching GraalVM distribution, eliminating the earlier interpreter-only version warning.

## Criteria

### 1. Import: passed with constraints

`import sky` completed in 21.254 seconds and loaded 1,853 modules. Context construction took 409 ms. The complete exercise initialized the import and bridge functions in 22.694 seconds.

Three embedding constraints were necessary:

- the Context must supply `argv[0]`, which GraalPy embedding otherwise leaves absent and SkyPilot indexes unconditionally;
- the native POSIX backend is required because the Java backend lacks `socket.AF_UNIX`; and
- the JVM thread stack must be raised to 16 MiB. With the default JVM stack, GraalPy raised `RecursionError` during Pydantic schema generation even after Python's recursion limit was raised to 10,000.

### 2–3. One-Context concurrency on platform threads: passed

The first arrangement tested was two platform threads sharing one Context. No second Context was needed.

While thread `graalpy-platform-0` held `sky.stream_and_get()` for the launch, thread `graalpy-platform-1` submitted and awaited `sky.status()`. The status call returned in 1,491 ms while the launch stream was still held; the stream completed after 21,624 ms. Both threads reported `virtual=false`.

The concurrent status already exposed an `INIT` cluster and its typed handle. This confirms that the HTTP stream releases enough guest execution capacity for control calls and amends ADR 0009's second-Context premise: use one Context if this mechanism were ever retained.

### 4. Real launch and held Operation: passed

The Java side retained the guest `RequestId` as an `Operation`:

```text
Operation[kind=launch, requestId=c3a4f6a2-25c7-4842-a16b-e6bcc563b5ff, guestType=RequestId]
```

SkyPilot selected `k8s/kind-skywright-graalpy-spike`, created cluster `skywright-graalpy-32c`, and returned job ID 1. The operation was streamed until the job was submitted. Cleanup then awaited `sky.down()` and removed the unique cluster.

### 5. Typed status and cluster handle: passed

SkyPilot returned a concrete Pydantic `StatusResponse`, not a dictionary. GraalPy preserved that model and its raw `CloudVmRayResourceHandle`, allowing Java to traverse members directly. The final typed snapshot was:

```text
status=UP
handle.type=CloudVmRayResourceHandle
handle.clusterName=skywright-graalpy-32c
handle.clusterNameOnCloud=skywright-graalpy-32c-9fc34560
handle.launchedNodes=1
handle.launchedResources=Kubernetes(1CPU--1GB, cpus=1, mem=1)
```

This is exactly the object fidelity the earlier REST arm lost.

### 6. Java record to Task Specification: passed

A nested Java `RunDefinition`/`ResourceDefinition` record tree crossed the polyglot boundary as a host object. Python read its typed accessors, constructed `sky.Resources` and `sky.Task`, and submitted this server-side specification:

```json
{"file_mounts": {}, "name": "graalpy-boundary", "num_nodes": 1, "resources": {"cpus": "1", "disk_size": 256, "infra": "kubernetes/kind-skywright-graalpy-spike", "memory": "1"}, "run": "python -c \"print('graalpy boundary reached')\"", "volumes": {}}
```

## Build and artifact cost

SkyPilot's complete dependency graph installed, including its native extensions, but required:

- Pandas 2.2.3 because Pandas 3.x failed its Cython build probe;
- Setuptools 80.9.0 because uvloop 0.22.1 imports `pkg_resources` while preparing its wheel;
- C and C++ compilers, Rust, GNU patch, and PostgreSQL client headers plus `pg_config`;
- compiling GraalPy-specific wheels for the native dependency set; and
- tolerating stale GraalPy patch hunks for setproctitle 1.3.7 and greenlet 3.5.5, although both wheels eventually built.

The locked 96-package graph is in `graalpy.lock`.

The final executable JAR is 170,142,168 bytes versus 9,104,336 bytes for an otherwise equivalent Spring Boot 4.1.0 baseline: a 161,037,832-byte (154 MiB) JAR delta. The external GraalPy/SkyPilot environment is 233,341,216 bytes (223 MiB), for a combined 394,379,048-byte (377 MiB) delta.

## Internals and lifecycle qualification

The spike calls `Task.to_yaml_config()` only to expose the exact mapped specification; SkyPilot documents that method as internal-facing. The status model itself documents `handle` as an internally facing `Any` field, though the SDK promises it in its return contract.

Every run that called the no-argument `Context.close()` on the native-POSIX Context crashed in glibc's `__GI___nptl_deallocate_tsd`, on both the original and matching GraalVM runtimes, after success and failure alike. The recorded end-to-end run therefore used `Runtime.halt()` after the exercise completed. That bypass is acceptable only in this throwaway probe and is not the lifecycle recommendation.

The spike did **not** test `Context.close(true)`. GraalVM documents that overload as closing the Context while cancelling any execution that is still running, and GraalPy's first-party Spring Boot example invokes it from `@PreDestroy`. The implementation should follow that pattern and verify shutdown under its real executor lifecycle before treating the observed no-argument-close crash as a persistent defect. See [BUILDER-SOURCES.md](BUILDER-SOURCES.md#spring-lifecycle-and-the-untested-close-path) for the primary sources and the precise evidence boundary.

## Embedding API qualification

The spike source uses `GraalPyResources.contextBuilder(resources)`, following the published embedding guidance. In the exact 25.2.4 API that convenience method still returns the generic `Context.Builder`, but it has been deprecated since 25.1.0. Production implementation should use the current composition form:

```java
Context.newBuilder()
    .apply(GraalPyResources.forExternalDirectory(resources))
```

`GraalPyResources` is not a separate builder abstraction. It attaches the Maven-plugin-managed external virtual environment and source directory to the generic builder. A bare `Context.newBuilder("python")` is valid, but would not by itself point GraalPy at `target/graalpy-resources/venv`. The source trail and configured options are recorded in [BUILDER-SOURCES.md](BUILDER-SOURCES.md).

Unlike the deprecated convenience builder, `forExternalDirectory(...)` deliberately applies only resource-related I/O and Python path options. The implementation must configure its required host, thread, native, polyglot, and broader I/O access explicitly on the generic builder rather than inheriting the convenience method's broad defaults.

## Decision

**In-process.** The boundary, one-Context concurrency on platform threads, operation model, real launch, typed handle, and Java-record mapping all work. Use one GraalPy Context with the native POSIX backend and a dedicated platform-thread executor. Use the current external-resource builder composition and close the Context with `close(true)` from Spring lifecycle management, verifying teardown during implementation. The out-of-process Python SDK service remains the fixed fallback if an implementation blocker resurfaces.
