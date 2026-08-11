# SUCCESSFUL PROTOTYPE — Spring Boot → GraalPy → SkyPilot

Throwaway spike for [issue #32](https://github.com/Zorro909/skywright/issues/32). All six mechanism criteria passed, selecting the in-process GraalPy arrangement. It must not merge into `main`. See [RESULTS.md](RESULTS.md) for the evidence, qualifications, and decision.

Use GraalVM CE 25.2.4 (JDK 25.0.4), Maven, C/C++ build tools, GNU patch, Rust, and PostgreSQL client headers (`pg_config`). The Python dependency graph is checked into `graalpy.lock`; `constraints.txt` records the compatibility pins used to generate it.

## Import gate

The recorded import run requires GraalPy's native POSIX backend, a larger JVM stack, and the spike's teardown bypass:

```bash
mvn clean spring-boot:run \
  '-Dspring-boot.run.jvmArguments=-Dgraalpy.posix=native -Dspike.halt=true -Xss16m'
```

The default Java POSIX backend fails because it does not expose `socket.AF_UNIX`. Without `-Xss16m`, the native backend raises `RecursionError` while Pydantic builds FastAPI's schemas. In this throwaway code, omitting `-Dspike.halt=true` reaches the no-argument `Context.close()` path, which crashed in glibc thread-local teardown during the spike.

That bypass is evidence-capture machinery, not the implementation lifecycle. The spike did not test `Context.close(true)`, which GraalPy's official Spring Boot example invokes from `@PreDestroy`. The successful mechanism decision therefore stands; implementation should use and verify that lifecycle path, investigating teardown further only if the crash recurs.

## Full mechanism exercise

Start a real SkyPilot 0.13.0 API server separately and configure a usable provider. This example targets the disposable kind context used for the recorded run:

```bash
SKYPILOT_API_SERVER_ENDPOINT=http://127.0.0.1:46580 \
mvn spring-boot:run \
  '-Dspring-boot.run.jvmArguments=-Dgraalpy.posix=native -Dspike.mode=exercise -Dspike.infra=k8s/kind-skywright-graalpy-spike -Dspike.cluster=skywright-graalpy-32 -Dspike.halt=true -Xss16m'
```

The exercise creates a real cluster, submits a job, tests a held stream against a concurrent status call on one Context, traverses the typed cluster handle from Java, and downs the named cluster in `finally`.

The managed Python environment is external at `target/graalpy-resources`, so compiled extensions remain ordinary filesystem files.

## Implementation notes

The spike followed older published examples and calls the deprecated `GraalPyResources.contextBuilder(resources)`. With GraalPy 25.2.4, use the generic builder plus the external-resource adapter:

```java
Context.newBuilder()
    .apply(GraalPyResources.forExternalDirectory(resources))
```

`GraalPyResources` configures the generic `Context.Builder` to use the Maven-plugin-managed virtual environment; it is not a separate builder type. The adapter intentionally supplies only the external-resource I/O and Python path settings, so implementation must add its required access policy explicitly. See [BUILDER-SOURCES.md](BUILDER-SOURCES.md) for version-pinned sources and the teardown evidence boundary.
