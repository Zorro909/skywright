# PROTOTYPE — Spring Boot → GraalPy → SkyPilot

Throwaway spike for the decision in [Spike: drive SkyPilot from Spring Boot via GraalPy](https://github.com/Zorro909/skywright/issues/32). It must not merge into `main`.

The first gate installs SkyPilot 0.13.0 into GraalPy 25.2.4 and evaluates `import sky` from a Spring Boot 4.1.0 application on a platform thread. It fails, so the spike stops there; see [RESULTS.md](RESULTS.md).

Use GraalVM CE 25.2.4 (JDK 25.0.4), Maven, C/C++ build tools, GNU patch, Rust, and PostgreSQL client headers (`pg_config`). Then run:

```bash
mvn clean spring-boot:run \
  -Dspring-boot.run.jvmArguments=-Dgraalpy.posix=native
```

The Python dependency graph is checked into `graalpy.lock`; `constraints.txt` records the two compatibility pins needed to generate it. The managed environment is deliberately external at `target/graalpy-resources`, so compiled extensions remain ordinary filesystem files.

GraalPy's default Java POSIX backend fails earlier because it does not expose `socket.AF_UNIX`. Reproduce that path with:

```bash
mvn spring-boot:run
```

Both runs are expected to fail. Native mode prints a structured `IMPORT_RESULT`, then currently exits 134 because the JVM crashes while tearing down GraalPy's native extensions.
