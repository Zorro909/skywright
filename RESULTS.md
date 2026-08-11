# Spike result: use the out-of-process fallback

`import sky` does not complete under GraalPy 25.2.4. Criterion 1 is negative, so criteria 2–6 were not attempted, as the ticket requires.

## Canonical run

The final run used:

- GraalVM CE 25.2.4, JDK 25.0.4, with runtime compilation enabled
- GraalPy 25.2.4 (Python 3.12.8)
- SkyPilot 0.13.0
- Spring Boot 4.1.0
- GraalPy's native POSIX backend on the Spring Boot main platform thread (`virtual=false`)

After supplying the guest `argv[0]` that an embedded Context lacks and raising `sys.setrecursionlimit()` from 1,000 to 10,000, the import reached 1,571 loaded modules. It still threw `RecursionError: maximum recursion depth exceeded` while FastAPI imported Pydantic and Pydantic generated its OpenAPI model schemas. On the final clean run, the Python import took 17.426 seconds; Context construction took 407 ms and the whole evaluation took 18.989 seconds.

Closing the Context then crashed the JVM with `SIGSEGV` in glibc's `__GI___nptl_deallocate_tsd`. The same teardown crash occurred on every failed import in both POSIX modes.

This was rerun on the matching GraalVM CE 25.2.4 distribution because the machine's original GraalVM 25.0.2 was too old for the 25.2.4 polyglot artifacts. The supported runtime removed the interpreter-only version warning but reproduced both the recursion failure and teardown crash.

The default Java POSIX backend fails earlier: SkyPilot's dependency graph imports code that expects `socket.AF_UNIX`, which that backend does not expose. Native POSIX is itself flagged by GraalPy as not fully supported for embedding.

No individual distribution failed to install. The terminal import failure is the interaction between GraalPy's execution stack and Pydantic's recursive schema generation. The nearest distributions on the failing path are FastAPI 0.141.1, Pydantic 2.13.4, and pydantic-core 2.46.4.

## Installation and maintenance cost

SkyPilot's complete dependency graph did install, including its native extensions, but only after:

- pinning Pandas 2.2.3 because Pandas 3.0.2 failed its Cython build probe;
- pinning Setuptools 80.9.0 because uvloop 0.22.1 imports `pkg_resources` while preparing its wheel;
- providing C and C++ compilers, Rust, GNU patch, and PostgreSQL client headers plus `pg_config`;
- compiling GraalPy-specific wheels for the native dependency set; and
- tolerating stale GraalPy patch hunks for setproctitle 1.3.7 and greenlet 3.5.5, although both wheels eventually built.

The locked graph is in `graalpy.lock`. Nothing reached into SkyPilot internals; the only embedding accommodations were the documented Context arguments/POSIX options and Python's public recursion-limit API.

## Size

The executable Spring Boot JAR is 170,128,811 bytes (163 MiB). An otherwise equivalent Spring Boot 4.1.0 baseline JAR is 9,104,336 bytes (8.7 MiB), so GraalPy adds 161,024,475 bytes (154 MiB) to the JAR. The external GraalPy/SkyPilot environment is another 233,341,216 bytes (223 MiB).

Combined, the in-process arrangement adds 394,365,691 bytes (377 MiB) over the Spring Boot baseline before application code or model artifacts.

## Decision

**Fallback.** Use the fixed out-of-process Python SDK service. The in-process mechanism fails its first gate and also exhibits a repeatable native teardown crash; its concurrency, launch, handle-decoding, and DTO-boundary criteria therefore do not earn further prototype effort.
