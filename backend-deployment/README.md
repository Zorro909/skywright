# Skywright backend deployment

This module is the independently buildable deployment boundary for the backend. Its Maven package
phase consumes the executable JAR from `backend`, constructs `skywright-backend:0.1.0-SNAPSHOT`,
and copies that same artifact into the image. Docker-compatible tooling is required.
Verification also requires `uv` to run an isolated, version-paired SkyPilot API server,
and `kubectl` on `PATH` to render the production overlay; it
does not require a cluster or Kubernetes credentials. `scripts/setup-worktree` reuses
an installed client or installs a checksum-verified Linux client into `~/.local/bin`.
Include that directory on `PATH` after initial setup.

## Build

From the repository root, build the backend JAR and production image:

```bash
./mvnw -pl backend-deployment -am package
```

The normal Maven verification lifecycle builds the same image during its package phase, then starts
that image and verifies its operator-visible runtime contract:

```bash
./mvnw -pl backend-deployment -am verify
```

The verification starts the repository-pinned PostgreSQL image and covers non-root execution,
health and application/source identity, the exact
served OpenAPI bytes, structured safe console output, sanitized invalid-configuration failure, and
bounded graceful termination. It also imports the actual SkyPilot SDK in the production image,
initiates `sky.jobs.queue_v2`, and decodes the isolated stock API server's typed
`ClusterNotUpError` through `sky.stream_and_get`. The process must exit successfully within
120 seconds with the production user, read-only filesystem and 64 MiB temporary-storage limit.
No compute is launched. Health probes alone do not qualify native SDK compatibility.
It deliberately does not inspect Dockerfile instructions, image
layers, private JVM details, or internal filesystem layout. CI additionally retains a complete high
and critical vulnerability report and rejects fixable findings at those severities under the
repository security policy; it does not publish the image.

For a Podman-compatible daemon, expose its Docker API:

```bash
DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock" \
./mvnw -pl backend-deployment -am package
```

The initial deployment is Linux amd64. The Dockerfile copies GraalVM CE 25.3.4.1 / OpenJDK 25.0.4.1
from its immutable official image into a digest-pinned Ubuntu 24.04 runtime. Native dependencies
must be built on Ubuntu 24.04 amd64 with glibc 2.39 and Ubuntu's OpenSSL 3.0 ABI.
`prepare-package` verifies the environment's sealed build provenance, effective Maven versions,
native payload hash and source inputs before copying it into the image. Fedora-built wheels
are rejected even when they import successfully on Fedora. The image retains
the complete JDK rather than using `jlink`, packages the locked SkyPilot 0.13.0 GraalPy environment
beside the application, and starts the JVM with the required 16 MiB thread stack.

### Build on another Linux amd64 host

Select the native environment produced by the repository's Ubuntu 24.04 CI job. The packaging
host does not rebuild or import these wheels. Choose a successful Repository Quality run whose native
inputs match the checkout; unrelated application edits may reuse that environment.

```bash
gh run download <run-id> --repo Zorro909/skywright --name graalpy-resources --dir /tmp/skywright-native-artifact
mkdir -p .graalpy/production
tar --zstd -xf /tmp/skywright-native-artifact/graalpy-resources.tar.zst -C .graalpy/production
scripts/verify-production-graalpy --resources .graalpy/production/resources
./mvnw -pl backend-deployment -am verify \
  -Dgraalpy.production.directory="$PWD/.graalpy/production/resources"
```

`graalpy.production.directory` selects only the image's dependencies. The reactor still builds
and tests the backend against its local `graalpy.external.directory`. Ubuntu wheels can also
fail to import on Fedora, so using one resource directory for both purposes is unsupported.
On Ubuntu, the production setting defaults to the local external directory, preserving the
normal build command. The production
image uses only its distribution packages for system libraries. Do not copy developer-host
libraries into it or disable TLS verification to make a wheel load.

Native cache identity includes the lock, effective GraalPy and SkyPilot versions, build constraints,
preparation scripts, exact Java toolchain, Dockerfile, build distribution/libc, compiler, Rust,
OpenSSL and compiler flags. Any change to those inputs requires a newly sealed environment;
payload changes also invalidate it. Local selection preserves the recorded build host while
checking current source inputs. CI additionally checks the producer run, attempt and tested
commit before handing the artifact to consumers. Production selection policy is checked anew
on every package build, and every image verification repeats the real SDK call in that image.
Cached imports on the build host are insufficient evidence for the production runtime.

Ubuntu 24.04 CI is the qualified native build host. Fedora is supported as a Linux amd64
packaging host by selecting that artifact, as qualified in
[`issue-250-native-runtime-abi.md`](../docs/testing/issue-250-native-runtime-abi.md).
Other distributions and architectures need their own qualification before being listed here.

## Run the image

Provision the `skywright` database and schema with separate migration and runtime roles as described
in [`backend/README.md`](../backend/README.md), then supply deployment configuration only at runtime.
Replace `<database-host>` below with a hostname or address reachable from the backend container.
Replace `<skypilot-host>` with the separately operated, version-paired SkyPilot API server.
The root filesystem can remain read-only; a bounded `/tmp` is the only documented writable
location. It must permit execution because Graal installs its pinned native runtime helper there.
`JAVA_TOOL_OPTIONS` injects JVM settings without replacing the image entry point:

```bash
docker run --rm \
  --name skywright-backend \
  --read-only \
  --tmpfs /tmp:rw,exec,nosuid,size=64m \
  --env SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=production \
  --env SKYWRIGHT_DEPLOYMENT_REPORTING_CURRENCY=EUR \
  --env SKYWRIGHT_DATABASE_MIGRATION_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_MIGRATION_USERNAME=skywright_migrator \
  --env SKYWRIGHT_DATABASE_MIGRATION_PASSWORD='<migration-password>' \
  --env SKYWRIGHT_DATABASE_RUNTIME_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_RUNTIME_USERNAME=skywright_runtime \
  --env SKYWRIGHT_DATABASE_RUNTIME_PASSWORD='<runtime-password>' \
  --env SKYWRIGHT_SKYPILOT_BRIDGE_API_SERVER_ENDPOINT='http://<skypilot-host>:46580' \
  --publish 127.0.0.1:8080:8080 \
  skywright-backend:0.1.0-SNAPSHOT
```

The image contains no deployment-specific setting or secret and writes application logs only to
standard output. The example binds to loopback; expose it remotely only through the
operator-controlled private network path. Stop it with `docker stop --time 30 skywright-backend`.
Docker sends SIGTERM directly to the JVM; readiness is withdrawn and Spring allows up to 20 seconds
for in-flight work, leaving the remaining container timeout for JVM exit.

Inspect the non-sensitive OCI build identity without starting the application:

```bash
docker image inspect skywright-backend:0.1.0-SNAPSHOT \
  --format '{{json .Config.Labels}}'
```

## Local container debugging

Bind both application and JDWP ports only to loopback:

```bash
docker run --rm \
  --name skywright-backend-debug \
  --read-only \
  --tmpfs /tmp:rw,exec,nosuid,size=64m \
  --env SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=local \
  --env SKYWRIGHT_DEPLOYMENT_REPORTING_CURRENCY=EUR \
  --env SKYWRIGHT_DATABASE_MIGRATION_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_MIGRATION_USERNAME=skywright_migrator \
  --env SKYWRIGHT_DATABASE_MIGRATION_PASSWORD='<migration-password>' \
  --env SKYWRIGHT_DATABASE_RUNTIME_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_RUNTIME_USERNAME=skywright_runtime \
  --env SKYWRIGHT_DATABASE_RUNTIME_PASSWORD='<runtime-password>' \
  --env SKYWRIGHT_SKYPILOT_BRIDGE_API_SERVER_ENDPOINT='http://<skypilot-host>:46580' \
  --env 'JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005' \
  --publish 127.0.0.1:8080:8080 \
  --publish 127.0.0.1:5005:5005 \
  skywright-backend:0.1.0-SNAPSHOT
```

Repository CI orchestration, release automation, signing, attestations, retention, and policy are
deliberately delegated to [issue #78](https://github.com/Zorro909/skywright/issues/78).

Dataset Publication verification uses the production pod's 64 MiB temporary-storage
budget. See [the object and concurrency limits and qualification evidence](../docs/testing/dataset-publication-storage.md).
