# Embedded native teardown

Issue [#271](https://github.com/Zorro909/skywright/issues/271) originally recorded
an import probe crashing after `Context.close()` under GraalPy 25.2.4. This
investigation uses the repository's current, unchanged pins, GraalPy and GraalVM
CE 25.3.4.1 with SkyPilot 0.13.0, at base `c5d1edb`. It does not rerun the old
25.2.4 distribution.

## Reproduction and cause

On 2026-09-11, the full smoke import followed by normal context close and JVM
exit reproduced on both Fedora 44 and the Ubuntu 24.04 production image. Ubuntu
used the existing `issue250-ubuntu` image and its shipped JDK, JAR and qualified
Ubuntu native environment. No Fedora native libraries entered that container.
Both processes printed `CLOSED`, then failed with SIGSEGV in native thread-local
cleanup. The process exit status, rather than the successful close marker or a
written receipt, detects the failure.

The smallest Fedora reproduction needs only `import watchfiles`. An empty
context exits zero. Separate imports of NumPy, pandas, uvloop, orjson, aiohttp,
psutil and bcrypt also exit zero. Importing `cryptography` alone does not load
its Rust extension; importing `cryptography.hazmat.bindings._rust` does, and
reproduces the crash. `import sky` also reproduces without importing watchfiles.

A `/proc/self/maps` snapshot immediately before context close maps the failing
instruction address to a library that context close subsequently unloads.
Resolving the address through that library's ELF load segments identifies:

| Import | Unloaded library | Callback |
| --- | --- | --- |
| `watchfiles` | `watchfiles/_rust_notify...so` | `std::sys::thread_local::guard::key::enable::run` |
| `sky` | `rpds/rpds...so` | `std::sys::thread_local::guard::key::enable::run` |

This is a lifetime mismatch between host-thread cleanup and native-library
unloading. GraalPy's `PythonContext.finalizeContext()` calls
`NativeContext.close()`, which calls `dlclose` for its loaded libraries. Rust's
thread-local guard registers a pthread-key destructor that can still be called
when the importing host thread exits, after the context has closed. The pinned
[GraalPy source archive](https://repo.maven.apache.org/maven2/org/graalvm/python/python-language/25.3.4.1/python-language-25.3.4.1-sources.jar)
and [Rust guard source](https://doc.rust-lang.org/src/std/sys/thread_local/guard/key.rs.html)
show those two mechanisms. [Rust issue 91979](https://github.com/rust-lang/rust/issues/91979)
records the same class of unload-before-thread-exit failure, although its
original environment and Rust version differ from this reproduction.

Importing on a separate platform thread and joining it before context close
also removes the minimal watchfiles crash. This distinguishes thread/library
lifetime from a package import failure. Thread ordering alone is not the chosen
mitigation: callers and shutdown hooks can touch native extensions on other
host threads.

## Supported mitigation

On Linux, the build probe adds `RTLD_NODELETE` to the
interpreter's existing `dlopen` flags before loading third-party extensions.
This preserves the existing symbol visibility and binding flags. GraalPy
implements `sys.setdlopenflags` but omits `os.RTLD_NODELETE`, so the code uses the
[Linux loader value `0x1000`](https://raw.githubusercontent.com/bminor/glibc/master/bits/dlfcn.h) explicitly. The
[Python interface](https://docs.python.org/3.13/library/sys.html#sys.setdlopenflags)
selects extension loader flags; the
[Linux loader contract](https://man7.org/linux/man-pages/man3/dlopen.3.html)
defines `RTLD_NODELETE` as retaining a library across `dlclose`.

Native extension code and static data stay mapped until this disposable probe
exits. It does not make repeated native contexts safe. Python context
finalization and JVM shutdown hooks still run. SkyPilot server/client sources
and package/runtime versions are unchanged.

The real environment probe now closes its context before writing its receipt
and returns from `main`. Both Maven executions already impose a 180-second
child-process timeout and require a zero exit status before sealing or accepting
an observation. `Runtime.halt(0)` no longer hides native teardown failures.
A native crash after receipt creation still fails Maven.

## Regression boundaries

`PackagedSkyPilotShutdownIT` exercises two actual entry points in child JVMs:

- The environment verifier must return to its caller, run a registered JVM
  shutdown hook, write its observation and exit zero within 120 seconds.
  The old successful `Runtime.halt(0)` path fails this test.
- The packaged backend must complete a real SDK status request against an
  isolated stock SkyPilot server, close the client on the importing host
  thread, and exit zero within 120 seconds. Assertions inside the importing
  JVM cannot catch a later native thread-exit crash.

Existing held-request and production-image tests additionally cover executor,
socket and SIGTERM shutdown. Production normal termination remains covered by
`ProductionImageIT.productionProcessTerminatesWithinTheDocumentedStopWindow`.
The existing packaged backend passes its new regression without changing its
loader flags. Its `Context.close(true)` cancellation path also passes the
minimal watchfiles reproduction, while ordinary `Context.close()` fails. The
backend first closes bridge resources and uses cancellation for the remaining
context teardown; GraalPy skips Python shutdown hooks on that path. The tested
backend shutdown path is therefore not affected by this finding. The process
model from [ADR 0009](../adr/0009-drive-skypilot-through-its-python-sdk.md) remains
in-process, with no reason from this evidence to select its fallback.

Run the new regression from the repository root:

```bash
mvn -B -ntp -pl backend -am verify \
  -Dgraalpy.environment.prebuilt=true \
  -Dit.test=PackagedSkyPilotShutdownIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The environment must first be built or sealed with the current verifier; an
old environment identity is deliberately rejected. See
[the native ABI qualification](issue-250-native-runtime-abi.md) for selecting
Ubuntu resources when packaging from Fedora.

## Verification evidence

The regression run against the original verifier failed with one assertion
failure: neither `PROBE_RETURNED` nor `SHUTDOWN_HOOK` appeared despite exit code
zero. The unmitigated ordinary-close reproductions instead exited nonzero after
`CLOSED`, with SIGSEGV. Retaining native libraries made the full smoke import,
ordinary close and process exit succeed on both Fedora and Ubuntu.

The unchanged packaged SDK status path and both minimal cancellation-close
probes, `watchfiles` and `sky`, exited zero. The build-tool unit suite passed all
67 tests, and the existing backend unit suite passed all 284 tests during the
initial packaged shutdown qualification.
