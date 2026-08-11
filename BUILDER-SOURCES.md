# GraalPy embedding API and lifecycle provenance

## Answer

The spike's `GraalPyResources.contextBuilder(resources)` call came from GraalPy's
first-party embedding guidance, not from a requirement that GraalPy use a
different builder type. The official JVM-developer documentation says that the
build plugins install Python resources, but Java must configure its context to
access them; it describes `GraalPyResources` as providing a preconfigured
GraalVM context and lists `contextBuilder(Path)` for an external resource
directory. The official Spring Boot guide likewise constructs its shared
context through `GraalPyResources.contextBuilder()`.

Sources:

- [GraalPy JVM documentation: Embedding Build Tools](https://graalpy.org/jvm-developers/docs/#embedding-build-tools)
- [GraalPy JVM documentation: External Directory](https://graalpy.org/jvm-developers/docs/#external-directory)
- [Official GraalPy Spring Boot example](https://github.com/graalvm/graal-languages-demos/blob/main/graalpy/graalpy-spring-boot-guide/src/main/java/com/example/demo/GraalPyContext.java#L19-L23)

For this spike, the Maven plugin puts SkyPilot in
`target/graalpy-resources/venv`, because the POM configures
`<externalDirectory>target/graalpy-resources</externalDirectory>`. A bare
`Context.newBuilder("python")` knows that Python is the permitted guest
language, but does not point Python at that plugin-managed virtual environment.

## What `GraalPyResources` adds

The 25.2.4 source documents the external-directory resource adapter as applying
host-filesystem access and these Python settings to an existing
`Context.Builder`:

- `python.ForceImportSite=true`, so packages from the virtual environment are
  importable;
- `python.Executable=${root}/venv/bin/python` (or the Windows equivalent);
- `python.PythonPath=${root}/src`;
- `python.InputFilePath=${root}/src`.

It also records the layout convention: `${root}/venv` contains third-party
packages and `${root}/src` contains application Python sources. See the
[25.2.4-matching `GraalPyResources` source and option list](https://github.com/oracle/graalpy-extensions/blob/3b98454ceed9e26669cc8fc8fb3c70f0dfea1206/org.graalvm.python.embedding/src/main/java/org/graalvm/python/embedding/GraalPyResources.java#L273-L357)
and its [resource-layout documentation](https://github.com/oracle/graalpy-extensions/blob/3b98454ceed9e26669cc8fc8fb3c70f0dfea1206/org.graalvm.python.embedding/src/main/java/org/graalvm/python/embedding/GraalPyResources.java#L100-L185).

The convenience method still used by the spike additionally starts from a
generic `Context.newBuilder()` and supplies broad embedding defaults such as
host, thread, native, polyglot, and I/O access, plus the Python POSIX-backend
and no-bytecode options. The new adapter deliberately applies only the
external-resource I/O and Python path configuration; callers must choose their
remaining access policy explicitly. The deprecated implementation is visible
in the [same first-party source](https://github.com/oracle/graalpy-extensions/blob/3b98454ceed9e26669cc8fc8fb3c70f0dfea1206/org.graalvm.python.embedding/src/main/java/org/graalvm/python/embedding/GraalPyResources.java#L699-L745),
and the source artifact includes Oracle's
[complete migration template](https://github.com/oracle/graalpy-extensions/blob/3b98454ceed9e26669cc8fc8fb3c70f0dfea1206/org.graalvm.python.embedding/src/main/java/org/graalvm/python/embedding/GraalPyResourcesMigrationSnippets.java#L85-L99).

## Version relevance: 25.2.4

The published documentation and Spring example currently show 25.0.x-era APIs.
In the exact `org.graalvm.python:python-embedding:25.2.4` source,
`contextBuilder(Path)` still exists but is deprecated since 25.1.0. Its stated
replacement is:

```java
Context.newBuilder()
    .apply(GraalPyResources.forExternalDirectory(resources))
```

The upstream deprecation and replacement are explicit at
[`GraalPyResources.java` lines 682–700](https://github.com/oracle/graalpy-extensions/blob/3b98454ceed9e26669cc8fc8fb3c70f0dfea1206/org.graalvm.python.embedding/src/main/java/org/graalvm/python/embedding/GraalPyResources.java#L682-L700),
and the preferred composition pattern appears in the
[`forExternalDirectory` example](https://github.com/oracle/graalpy-extensions/blob/3b98454ceed9e26669cc8fc8fb3c70f0dfea1206/org.graalvm.python.embedding/src/main/java/org/graalvm/python/embedding/GraalPyResources.java#L287-L304).
The commit-pinned source above is byte-for-byte identical to the class in the
[published 25.2.4 source artifact](https://repo1.maven.org/maven2/org/graalvm/python/python-embedding/25.2.4/python-embedding-25.2.4-sources.jar).

## Is the generic builder valid?

Yes. `GraalPyResources.contextBuilder(...)` itself returns
`org.graalvm.polyglot.Context.Builder` and internally starts with
`Context.newBuilder()`. The general Polyglot API documents
`Context.newBuilder(String...)` as the normal way to construct a context and
states that its arguments merely restrict the permitted installed languages.
See the [Oracle GraalVM 25 `Context` API](https://docs.oracle.com/en/graalvm/jdk/25/sdk/org/graalvm/polyglot/Context.html#newBuilder(java.lang.String...)).

Therefore the current, precise choice is not “GraalPy builder versus generic
builder.” It is a generic builder **plus** the GraalPy resource adapter. A bare
generic builder is valid for Python code and packages already otherwise
available, as the [official manual-setup example](https://graalpy.org/jvm-developers/docs/#manual-setup)
shows, but it does not by itself connect the Maven-plugin-generated external
virtual environment. The spike should migrate to the 25.2.4 form above and
then configure its Python arguments, native POSIX backend, and required access
policy on that generic builder.

## Spring lifecycle and the untested close path

The spike tested the no-argument `Context.close()` and observed a JVM
`SIGSEGV` in glibc's `__GI___nptl_deallocate_tsd` for the native-POSIX,
SkyPilot-loaded Context. It did not test `Context.close(true)`. The successful
end-to-end evidence run used `Runtime.halt()` only after all mechanism checks
and cleanup had completed, in order to preserve the result from the already
observed no-argument-close crash.

The [first-party GraalPy Spring Boot example](https://github.com/graalvm/graal-languages-demos/blob/3dc30b050fe95c295a08dfd8ea19add539886fce/graalpy/graalpy-spring-boot-guide/src/main/java/com/example/demo/GraalPyContext.java#L31-L35)
owns one shared Context as a Spring component and calls `context.close(true)`
from an `@PreDestroy` method. The
[GraalVM 25 `Context.close(boolean)` API](https://docs.oracle.com/en/graalvm/jdk/25/sdk/org/graalvm/polyglot/Context.html#close(boolean))
states that `true` permits closing while guest execution is still running by
cancelling it; the no-argument overload instead rejects concurrent execution.
Both overloads perform Context teardown, so the official example does not
prove that `close(true)` avoids this particular native crash. It does establish
that the relevant Spring lifecycle path was left untested by the spike.

The evidence therefore supports these narrower conclusions:

- all six mechanism criteria passed, so the prototype selects the in-process
  arrangement;
- the no-argument close crash is a recorded implementation risk, not a failed
  success criterion;
- implementation should use `@PreDestroy` with `context.close(true)` and test
  shutdown after its platform-thread executor has been quiesced; and
- the native teardown needs further diagnosis only if the crash recurs on that
  implementation path.
