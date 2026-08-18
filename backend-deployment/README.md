# Skywright backend deployment

This module is the independently buildable deployment boundary for the backend. Its Maven package
phase consumes the executable JAR from `backend`, constructs `skywright-backend:0.1.0-SNAPSHOT`,
and copies that same artifact into the image. Docker-compatible tooling is required.

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
bounded graceful termination. It deliberately does not inspect Dockerfile instructions, image
layers, private JVM details, or internal filesystem layout. CI additionally retains a complete high
and critical vulnerability report and rejects fixable findings at those severities under the
repository security policy; it does not publish the image.

For a Podman-compatible daemon, expose its Docker API:

```bash
DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock" \
./mvnw -pl backend-deployment -am package
```

The initial deployment is Linux amd64. The Dockerfile starts from the immutable amd64 manifest of
the official GraalVM Community image for GraalVM CE 25.2.4 / OpenJDK 25.0.4. It retains the complete
JDK rather than using `jlink`, preserving JVMCI and the future in-process GraalPy facilities.

## Run the image

Provision the `skywright` database and schema with separate migration and runtime roles as described
in [`backend/README.md`](../backend/README.md), then supply deployment configuration only at runtime.
Replace `<database-host>` below with a hostname or address reachable from the backend container.
The root filesystem can remain read-only; `/tmp`
is the only documented writable location. `JAVA_TOOL_OPTIONS` injects JVM settings without
replacing the image entry point:

```bash
docker run --rm \
  --name skywright-backend \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --env SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=production \
  --env SKYWRIGHT_DATABASE_MIGRATION_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_MIGRATION_USERNAME=skywright_migrator \
  --env SKYWRIGHT_DATABASE_MIGRATION_PASSWORD='<migration-password>' \
  --env SKYWRIGHT_DATABASE_RUNTIME_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_RUNTIME_USERNAME=skywright_runtime \
  --env SKYWRIGHT_DATABASE_RUNTIME_PASSWORD='<runtime-password>' \
  --env JAVA_TOOL_OPTIONS=-Xss2m \
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
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --env SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=local \
  --env SKYWRIGHT_DATABASE_MIGRATION_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_MIGRATION_USERNAME=skywright_migrator \
  --env SKYWRIGHT_DATABASE_MIGRATION_PASSWORD='<migration-password>' \
  --env SKYWRIGHT_DATABASE_RUNTIME_URL='jdbc:postgresql://<database-host>:5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true' \
  --env SKYWRIGHT_DATABASE_RUNTIME_USERNAME=skywright_runtime \
  --env SKYWRIGHT_DATABASE_RUNTIME_PASSWORD='<runtime-password>' \
  --env 'JAVA_TOOL_OPTIONS=-Xss2m -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005' \
  --publish 127.0.0.1:8080:8080 \
  --publish 127.0.0.1:5005:5005 \
  skywright-backend:0.1.0-SNAPSHOT
```

Repository CI orchestration, release automation, signing, attestations, retention, and policy are
deliberately delegated to [issue #78](https://github.com/Zorro909/skywright/issues/78).
