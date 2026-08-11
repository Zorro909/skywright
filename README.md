# PROTOTYPE — Spring Boot → GraalPy → SkyPilot

Throwaway spike for [issue #32](https://github.com/Zorro909/skywright/issues/32). It must not merge into `main`. See [RESULTS.md](RESULTS.md) for the evidence and decision.

Use GraalVM CE 25.2.4 (JDK 25.0.4), Maven, C/C++ build tools, GNU patch, Rust, and PostgreSQL client headers (`pg_config`). The Python dependency graph is checked into `graalpy.lock`; `constraints.txt` records the compatibility pins used to generate it.

## Import gate

The successful import requires GraalPy's native POSIX backend, a larger JVM stack, and the teardown workaround:

```bash
mvn clean spring-boot:run \
  '-Dspring-boot.run.jvmArguments=-Dgraalpy.posix=native -Dspike.halt=true -Xss16m'
```

The default Java POSIX backend fails because it does not expose `socket.AF_UNIX`. Without `-Xss16m`, the native backend raises `RecursionError` while Pydantic builds FastAPI's schemas. Without `-Dspike.halt=true`, closing the Context crashes the JVM in glibc thread-local teardown.

## Full mechanism exercise

Start a real SkyPilot 0.13.0 API server separately and configure a usable provider. This example targets the disposable kind context used for the recorded run:

```bash
SKYPILOT_API_SERVER_ENDPOINT=http://127.0.0.1:46580 \
mvn spring-boot:run \
  '-Dspring-boot.run.jvmArguments=-Dgraalpy.posix=native -Dspike.mode=exercise -Dspike.infra=k8s/kind-skywright-graalpy-spike -Dspike.cluster=skywright-graalpy-32 -Dspike.halt=true -Xss16m'
```

The exercise creates a real cluster, submits a job, tests a held stream against a concurrent status call on one Context, traverses the typed cluster handle from Java, and downs the named cluster in `finally`.

The managed Python environment is external at `target/graalpy-resources`, so compiled extensions remain ordinary filesystem files.
