# Effective GraalPy environment validation

Investigated 2026-09-08 for [#224](https://github.com/Zorro909/skywright/issues/224), against base `e8ac4e5`, GraalPy 25.2.4 and SkyPilot 0.13.0. The recommendation below concerns cache identity and build-host validation. [#250](https://github.com/Zorro909/skywright/issues/250) retains production native-library ABI qualification.

## Launch the restored environment through the embedding API

Use a small Java source-file probe launched by `exec-maven-plugin:exec`, with `java` and its `<classpath/>` argument. Maven supplies the current module dependencies. The existing environment module already declares the Python runtime and embedding library at `${graalpy.version}`. This requires no cached launcher, pip invocation or native package rebuild. Use an external child process, because the probe must not terminate Maven. [Exec plugin documentation](https://www.mojohaus.org/exec-maven-plugin/examples/example-exec-for-java-programs.html)

Build the context with `GraalPyResources.forExternalDirectory(resources)`, as `GraalPySkyPilotClient` does. In the exact 25.2.4 implementation, this enables site-package discovery and sets `python.Executable` to the external `venv/bin/python`. That path identifies the environment; the API does not execute the shell launcher. Set a nonempty Python argument vector, native access, thread creation and the same native POSIX mode needed for SDK imports. Disable bytecode writes for validation. [Published embedding source, `GraalPyResources.applyExternalDirectoryConfig`](https://repo.maven.apache.org/maven2/org/graalvm/python/python-embedding/25.2.4/python-embedding-25.2.4-sources.jar)

Do not rerun the environment installation goal solely to regenerate a launcher. The pinned plugin exposes `process-graalpy-resources` and `lock-packages`, with no launcher-only goal. `VFSUtils.checkVenvLauncher` deletes an existing venv if `pyvenv.cfg` names a missing or different launcher. `ensureVenv` also replaces a mismatched runtime environment. Launcher regeneration is private implementation detail, and the documented `graalpy.vfs.venvLauncher` override does not bypass that check. [Plugin source](https://repo.maven.apache.org/maven2/org/graalvm/python/graalpy-maven-plugin/25.2.4/graalpy-maven-plugin-25.2.4-sources.jar), [embedding-tools source](https://repo.maven.apache.org/maven2/org/graalvm/python/python-embedding-tools/25.2.4/python-embedding-tools-25.2.4-sources.jar)

## Validate observations, then retain provenance

The probe should report `sys.implementation.name` and its complete version tuple. On the installed runtime these are `graalpy` and `[25, 2, 4, "final", 0]`. `Engine.getVersion()` reports `25.2.4`; the Python language version reports `3.12.8`. Comparing the latter to `${graalpy.version}` would be wrong. The current website mentions `__graalpython__.version`, but that attribute is absent in the pinned runtime. [`sys.implementation` contract](https://docs.python.org/3.12/library/sys.html#sys.implementation)

Query installed distribution metadata with `importlib.metadata`, normalize package names and compare against the lock and effective SDK requirement. Metadata queries establish installed versions, not working imports. Retain the existing imports of `aiohttp`, `cryptography`, `numpy`, `pandas`, `psutil`, `sky`, `uvloop` and `watchfiles`, and check `sky.__version__` too. Confirm discovered distributions and imported modules belong to the intended venv so a host package cannot satisfy validation. [`importlib.metadata` documentation](https://docs.python.org/3.12/library/importlib.metadata.html)

Recommended build sequence:

1. Resolve the effective Maven properties and construct the canonical platform/native-input identity before choosing the cache key.
2. Restore the exact identity or build dependencies on a miss. Compare the restored identity and plugin `contents` metadata with the expected versions before executing packages.
3. Run the probe on both paths. A missing, oversized, malformed or mismatched observation, failed import, timeout or nonzero child exit fails qualification.
4. Write fresh same-run provenance only after successful validation, binding the canonical identity, observed versions, archive digest, commit and run/attempt. Pass that artifact to downstream jobs and validate before prebuilt packaging. Share this preparation with deployment builds.

These steps implement #224's accepted audit direction. The cross-run cache contains reusable dependencies; it must not supply an old success report as current-run build authority. Maven validation must remain active when `graalpy.environment.prebuilt=true` skips installation. Place it after environment generation and before packaging.

## Local evidence and shutdown boundary

A source-file probe used a freshly Maven-resolved classpath and a temporary external venv. Its `pyvenv.cfg` pointed to `/missing/old/graalpy.sh`; its `bin/python` deliberately exited 91; its `lib` directory reused the installed package directory through a symlink. The probe reported the temporary prefix and expected runtime/SDK versions, then imported every smoke package. This demonstrates package discovery without executing the old launcher. It does not qualify copying native libraries to another operating-system image.

The normal-close experiment printed `IMPORTS_OK`, `CLOSING` and `CLOSED`, then failed during JVM teardown with SIGSEGV in `__GI___nptl_deallocate_tsd`. An earlier probe without Python arguments hit an import error and also aborted during cleanup. Set the argument vector and preserve import failures. These observations do not establish the cause of the teardown failure.

For the standalone import/version check, preserve the existing smoke's explicit immediate-exit scope: finish every assertion, close the bounded observation file, flush diagnostic output, then call `Runtime.halt(0)` in the child. Failure paths terminate nonzero. Never treat a receipt as success after a crash or timeout. This bypasses context/JVM finalization and therefore provides no shutdown qualification. Java documents that `halt` skips shutdown hooks and resource cleanup. [Runtime contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Runtime.html#halt(int))

A second probe used that explicit halt path. It asserted the runtime version, installed SkyPilot version and imported `sky.__version__`, imported all smoke packages, wrote its receipt and exited zero. Neither probe invoked package installation or modified SDK source.

[#247](https://github.com/Zorro909/skywright/issues/247) currently records the separate CPython checkpoint-cancellation abort. Do not claim it already covers this GraalPy teardown finding or that this probe resolves it.

The teardown finding is tracked separately in [#271](https://github.com/Zorro909/skywright/issues/271).
