# Skywright backend deployment

This module is the independently buildable deployment boundary for the backend. Its Maven package
phase consumes the executable JAR from `backend`, constructs `skywright-backend:0.1.0-SNAPSHOT`,
and copies that same artifact into the image. Docker-compatible tooling is required.

## Build and verify

From the repository root, build the backend JAR and production image:

```bash
./mvnw -pl backend-deployment -am package
```

Run the operator-facing image smoke test as part of Maven verification:

```bash
./mvnw -pl backend-deployment -am verify
```

For a Podman-compatible daemon, expose its Docker API and select the matching CLI used by the smoke
test:

```bash
DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock" \
./mvnw -pl backend-deployment -am verify \
  -Dbackend.container.runtime=podman
```

The smoke test is deployment verification, not backend application code. It starts the Maven-built
production image through its operator-visible boundaries and proves:

- the image contains the exact Maven-built layered JAR and its OCI/application identity agrees;
- UID/GID `10001:10001`, GraalVM Community 25.2.4, the exec-form entry point, read-only-root
  operation, and `JAVA_TOOL_OPTIONS=-Xss2m`;
- `/livez`, `/readyz`, `/actuator/info`, and `/openapi/skywright-api.yaml` through the published
  application port;
- JSON-only application standard output, sanitized invalid-configuration failure before readiness,
  and bounded graceful SIGTERM with readiness withdrawal, refused new work, and completion of
  in-flight HTTP work.

The initial deployment is Linux amd64. The Dockerfile starts from the immutable amd64 manifest of
the official GraalVM Community image for GraalVM CE 25.2.4 / OpenJDK 25.0.4. It retains the complete
JDK rather than using `jlink`, preserving JVMCI and the future in-process GraalPy facilities.

## Run the image

Supply deployment configuration only at runtime. The root filesystem can remain read-only; `/tmp`
is the only documented writable location. `JAVA_TOOL_OPTIONS` injects JVM settings without
replacing the image entry point:

```bash
docker run --rm \
  --name skywright-backend \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --env SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=production \
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
  --env 'JAVA_TOOL_OPTIONS=-Xss2m -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005' \
  --publish 127.0.0.1:8080:8080 \
  --publish 127.0.0.1:5005:5005 \
  skywright-backend:0.1.0-SNAPSHOT
```

Repository CI orchestration, release automation, signing, attestations, retention, and policy are
deliberately delegated to [issue #78](https://github.com/Zorro909/skywright/issues/78).
