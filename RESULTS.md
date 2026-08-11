# Spike result: the boundary works, but use the fallback

All six requested mechanism checks passed on GraalPy 25.2.4, including a real launch and a typed cluster handle. However, every normal Context teardown crashed the JVM with `SIGSEGV`; the successful end-to-end run could exit cleanly only by calling `Runtime.halt()`. That is not an acceptable Spring Boot lifecycle, so the recommendation remains the out-of-process Python SDK service.

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

## Internals and lifecycle failure

The spike calls `Task.to_yaml_config()` only to expose the exact mapped specification; SkyPilot documents that method as internal-facing. The status model itself documents `handle` as an internally facing `Any` field, though the SDK promises it in its return contract.

More importantly, every run that closed the native-POSIX Context crashed in glibc's `__GI___nptl_deallocate_tsd`, on both the original and matching GraalVM runtimes, after success and failure alike. The end-to-end run uses `Runtime.halt()` to bypass native teardown. That skips Spring shutdown hooks and is only acceptable in a throwaway probe.

## Decision

**Fallback.** The in-process boundary, one-Context concurrency, operation model, real launch, typed handle, and Java-record mapping all work. The normal JVM lifecycle does not. Until GraalPy can close this native-extension-heavy Context without crashing—and without the Java POSIX backend's missing socket support—the out-of-process Python SDK service is the operationally sound mechanism.
