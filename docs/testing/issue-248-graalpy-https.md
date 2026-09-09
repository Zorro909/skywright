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

The selected runtime is GraalPy **25.3.4.1**, Python **3.13.14**, with urllib3
**2.7.0** and unchanged SkyPilot **0.13.0**. The released runtime now supplies
the SSL constant. `VerifyEnvironment.java` checks that the context factory
constructs a context with `CERT_REQUIRED` and hostname verification enabled.
Network rejection tests are also necessary: these settings alone did not prove
that the runtime actually checked the peer's name.

An isolated comparison found that GraalPy's JSSE transport rejected an unrelated
CA but accepted a trusted certificate for the wrong hostname at an IP endpoint.
CPython rejected both with the same certificates and urllib3 version.
`SSLContextBuiltins.java` only sets JSSE's endpoint identification algorithm when
the server hostname is not an IP address.

The bridge marks urllib3's native hostname-checking capability as unreliable by
setting `urllib3.util.ssl_.HAS_NEVER_CHECK_COMMON_NAME` to `False` before loading
Requests or SkyPilot. With this flag, urllib3 performs its own certificate name
check immediately after the verified TLS handshake and before sending HTTP
headers. It accepts neither an unmatched IP nor an unmatched DNS name. Chain
verification remains `CERT_REQUIRED`. The flag selects urllib3's existing name
verifier; it does not accept unverified peers. This is a compatibility adjustment
to the pinned urllib3 behavior, covered by packaged rejection tests.

Health probes use the same configured Requests transport, including when an HTTP
endpoint redirects to HTTPS. They still avoid importing SkyPilot. This matters
because a probe may carry authorization, and a separate standard-library request
would retain GraalPy's unchecked IP-hostname path.

The isolated adjusted transport accepted the trusted peer and rejected both the
unrelated CA and the wrong hostname. The production health-probe code also
passed those cases with a synthetic authorization header. The documented
PyOpenSSL adapter was also investigated, but hit a GraalPy `NotImplementedError: latin1`; it is not a selected
dependency. With GraalPy 25.2.4 it also left the incomplete SSL imports unresolved.

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

uvloop moves from **0.19.0** to **0.22.1** after the old source build failed because
its setup code imports the removed `pkg_resources` module. watchfiles moves from
**0.21.0** to **1.2.0** to use its current Python support instead of the old
GraalPy patch's PyO3 0.20.3 fork. pandas stays at **2.2.3**, and psutil at **5.9.8**.
For the local pandas wheel preparation, `pip wheel -Ccompile-args=-j4` bounds
parallel compilation on this 32-thread host. It changes parallelism, not compiler
optimization or ABI options. The host also lacked `pg_config`, required to
build the existing psycopg2-binary 2.9.12 pin for the new GraalPy ABI. A
checksum-verified PostgreSQL 18.4 source build supplied client headers and build
tools in the isolated local cache; it did not start a database or change the
running qualification deployment. Rust 1.95.0 was selected for this worktree
after orjson 3.11.9 rejected the host default 1.94.0 compiler. Builds receive
`RUSTUP_TOOLCHAIN=1.95.0` so pip's temporary build directories use that compiler
too. The orjson dependency pin is unchanged.

The Maven plugin regenerated the full 101-package environment lock for the new
Python runtime. Only NumPy, uvloop and watchfiles changed package versions. The
existing environment identity includes the effective GraalPy version, package
inputs, lock, build constraints, toolchain and native platform inputs. Those
changes invalidate the previous environment and wheel caches without a cache
namespace change. The CI pandas wheel lookup now selects the Python 3.13 /
GraalPy 25.3 ABI tag. A cache from the old runtime must not be reused as a prebuilt
environment. The system test using Maven's effective versions passed and
confirmed runtime-property invalidation while unrelated edits preserve reuse.

## Packaged qualification

`PackagedHeldSkyPilotIT` runs both existing HTTP scenarios and their HTTPS
counterparts. The held HTTPS case uses an IP endpoint, and the saturated
short-call HTTPS case uses a DNS endpoint. Each launches the packaged backend
in a fresh JVM and reaches the real pinned SkyPilot API server through a local proxy. For HTTPS, a temporary
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
then with a correctly trusted certificate for the wrong hostname, for both IP
and DNS endpoints. It invokes
status directly, then independently checks health. Both operations must report
`REACHABILITY`, exit cleanly and deliver zero HTTP requests to the proxy.
The trusted held-work cases establish that the same transport can reach the API.

Run the command in [Held SkyPilot work](skypilot-held-work.md), including
`PackagedSkyPilotTlsIT`. Logs are written to `backend/target/service-logs/`.
This qualification uses native dependencies built for the host. Production image
native ABI qualification remains tracked in #250, and cold cancellation latency
remains tracked in #252.

## Primary references

- [GraalVM CE 25.3.4.1 release](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.3.4.1)
- [Published GraalPy runtime versions](https://repo.maven.apache.org/maven2/org/graalvm/python/python-language/maven-metadata.xml)
- [GraalPy 25.3.4.1 runtime sources](https://repo.maven.apache.org/maven2/org/graalvm/python/python-language/25.3.4.1/python-language-25.3.4.1-sources.jar), `SSLModuleBuiltins.java` and `SSLContextBuiltins.java`
- [urllib3 releases](https://github.com/urllib3/urllib3/releases)
- [urllib3 2.7.0 hostname verification](https://github.com/urllib3/urllib3/blob/2.7.0/src/urllib3/connection.py), `_ssl_wrap_socket_and_match_hostname`
- [NumPy GCC 16 build failure and 2.3.2 fix](https://github.com/numpy/numpy/issues/29130)
- [uvloop 0.22.1 release](https://github.com/MagicStack/uvloop/releases/tag/v0.22.1)
- [watchfiles 1.2.0 release](https://github.com/samuelcolvin/watchfiles/releases/tag/v1.2.0)
- [PostgreSQL 18.4 source and checksums](https://ftp.postgresql.org/pub/source/v18.4/)
- [Meson Python build settings](https://mesonbuild.com/meson-python/how-to-guides/config-settings.html)
