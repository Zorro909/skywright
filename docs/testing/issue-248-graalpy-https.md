# GraalPy HTTPS qualification

Issue [#248](https://github.com/Zorro909/skywright/issues/248) covers the real
SkyPilot SDK's HTTPS transport and shutdown while a TLS read remains held.
The backend retains ADR 0009's single in-process native GraalPy context and
two bounded platform-thread lanes. SkyPilot server and SDK source are unchanged.

## Runtime and dependency selection

GraalPy 25.2.4 with urllib3 2.7.0 reproduces the reported failure before any
network operation. GraalPy lacks `ssl.VERIFY_X509_PARTIAL_CHAIN`; urllib3's
combined SSL import consequently leaves its `SSLContext` unset. Calling
`urllib3.util.ssl_.create_urllib3_context()` raises `TypeError`.

The candidate is GraalPy **25.3.4.1**, Python **3.13.14**, with urllib3
**2.7.0** and unchanged SkyPilot **0.13.0**. The released runtime now supplies
the SSL constant. Its context factory uses `CERT_REQUIRED` and hostname
verification. `VerifyEnvironment.java` checks both before sealing an environment.
This avoids a dependency downgrade or a replacement TLS adapter.

The corresponding JDK is GraalVM CE **25.3.4.1+1.1**, OpenJDK **25.0.4.1**.
`quality/toolchain.json` pins the official archive and its SHA-256; the deployment
Dockerfile pins the matching image digest. A clean SDKMAN setup on 2026-09-09
found that the listed candidate's download broker failed. `scripts/setup-worktree`
therefore falls back to that same verified archive on Linux amd64. This path was
run with a separate SDKMAN directory lacking the candidate and completed setup.
The pinned container's Java properties and installation path were also checked.

GraalPy's Python implementation tuple omits the fourth hotfix component.
The environment observation records the exact version checked against the
Maven-resolved engine and separately records the three-component implementation
version. It still verifies the runtime, installed distributions and their paths.

NumPy moves from **2.2.4** to **2.3.2** in the package inputs, wheel primer and
build constraint. A source build of 2.2.4 with GCC 16.1.1 failed with
`attribute 'target' argument 'evex512' is unknown`. This matches the upstream
compiler compatibility fix shipped in 2.3.2. GraalPy's bundled NumPy patch
supports this version. Its post-release patch URL returned 404 during the build,
so the installer used the runtime's bundled patches.

The environment lock must be regenerated for the new Python runtime. The
existing environment identity includes the effective GraalPy version, package
inputs, lock, build constraints, toolchain and native platform inputs. Those
changes invalidate the previous environment and wheel caches without a cache
namespace change. The CI pandas wheel lookup now selects the Python 3.13 /
GraalPy 25.3 ABI tag. A cache from the old runtime must not be reused as a prebuilt
environment. The system test using Maven's effective versions passed and
confirmed runtime-property invalidation while unrelated edits preserve reuse.

## Packaged qualification

`PackagedHeldSkyPilotIT` runs both existing HTTP scenarios and their HTTPS
counterparts. Each launches the packaged backend in a fresh JVM and reaches
the real pinned SkyPilot API server through a local proxy. For HTTPS, a temporary
CA signs a server certificate with the loopback IP in its subject alternative
names. The child receives this CA through `SSL_CERT_FILE` and
`REQUESTS_CA_BUNDLE`. Verification remains enabled.

The proxy observes the SDK's `/api/stream` request after the TLS handshake, then
holds its response headers. The child measures cancellation, health, queue
saturation and shutdown while its SDK stream waits in TLS socket I/O. The second
scenario also holds a status call. The parent does not release either held
request until after the child exits. The original latency and queue bounds remain
unchanged.

`PackagedSkyPilotTlsIT` uses the same packaged SDK with an unrelated trusted CA,
then with a correctly trusted certificate for the wrong hostname. It invokes
status directly, avoiding the standard-library health probe. Both cases must
report `REACHABILITY`, exit cleanly and deliver zero HTTP requests to the proxy.
The trusted held-work cases establish that the same transport can reach the API.

Run the command in [Held SkyPilot work](skypilot-held-work.md), including
`PackagedSkyPilotTlsIT`. Logs are written to `backend/target/service-logs/`.
This qualification uses native dependencies built for the host. Production image
native ABI qualification remains tracked in #250, and cold cancellation latency
remains tracked in #252.

## Primary references

- [GraalVM CE 25.3.4.1 release](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.3.4.1)
- [Published GraalPy runtime versions](https://repo.maven.apache.org/maven2/org/graalvm/python/python-language/maven-metadata.xml)
- [GraalPy 25.3.4.1 runtime sources](https://repo.maven.apache.org/maven2/org/graalvm/python/python-language/25.3.4.1/python-language-25.3.4.1-sources.jar), `SSLModuleBuiltins.java`
- [urllib3 releases](https://github.com/urllib3/urllib3/releases)
- [NumPy GCC 16 build failure and 2.3.2 fix](https://github.com/numpy/numpy/issues/29130)
