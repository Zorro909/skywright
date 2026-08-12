# Skywright backend

The backend depends on the reusable `skywright-api` contract artifact. Its normal build extracts
and validates that dependency, then uses the pinned OpenAPI Generator to create Spring Boot 4 and
Jackson 3 server interfaces and boundary DTOs in `target/generated-sources/openapi`.

From the repository root, verify generation and the packaged application with:

```bash
./mvnw -pl backend -am verify
```

Handwritten HTTP adapters implement generated interfaces and map explicitly between generated
boundary DTOs and internal domain or persistence types. The executable serves the exact contract
at `GET /openapi/skywright-api.yaml`; Swagger UI is disabled and Actuator endpoints are excluded
from the product contract.

Every response carries an `X-Correlation-ID`. A supplied value is retained when it has 1–64
characters, starts with an ASCII letter or digit, and otherwise contains only ASCII letters,
digits, `.`, `_`, `:`, or `-`; a UUID is generated otherwise. The effective value is diagnostic
request context, not a Principal Identity or idempotency key.

HTTP failures use `application/problem+json` with the RFC 9457 fields plus `errorCode`,
`correlationId`, and `fieldViolations`. Details never expose exception messages or stack traces.

## Deployment configuration

Set the required, non-secret `skywright.deployment.environment` to a lowercase identifier such as
`production`. Spring applies its normal precedence across `application.properties`, the
`SKYWRIGHT_DEPLOYMENT_ENVIRONMENT` environment variable, the
`-Dskywright.deployment.environment` system property, and the
`--skywright.deployment.environment` command-line option.

The immutable validated configuration rejects missing, invalid, and unknown deployment properties
before readiness. Diagnostics omit supplied values, generated metadata ships in the executable,
and Actuator does not expose configuration. This process setting is not Run Configuration and must
never contain credentials or make Skywright a Credential Authority.

## Logging

The production default writes Elastic Common Schema JSON to standard output, one event per line,
and configures no application log file. Each event includes a UTC `@timestamp`, `log.level`,
`service.name`, known `service.version`, `log.logger`, `process.thread.name`, and `message`.
Exceptions add structured `error.type` and bounded stack-frame details without rendering the
exception message in the request event.

Each completed HTTP request adds `http.request.method`, the matched `http.route` template,
`http.response.status_code`, and nanosecond `event.duration`. When present, the effective
`correlationId` from the API boundary is included. Request and response bodies, query values, and
headers are never logged; the route template is used instead of the requested path.

For readable contributor output, explicitly activate the `local` profile. It changes only the
console rendering; log levels and application behavior remain unchanged:

```bash
SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=local \
SPRING_PROFILES_ACTIVE=local \
./mvnw -pl backend spring-boot:run
```

## Production artifact and image

Package the executable layered JAR from the repository root:

```bash
./mvnw -pl backend -am -DskipTests package
```

The artifact is `backend/target/skywright-backend-0.1.0-SNAPSHOT.jar`. It contains version, build
time, and the full source revision in `META-INF/build-info.properties`; the same non-sensitive
identity is available from `GET /actuator/info` while it is running.

The initial deployment is Linux amd64. Its Dockerfile starts from the immutable amd64 manifest of
the official GraalVM Community image for GraalVM CE 25.2.4 / OpenJDK 25.0.4. It retains the complete
JDK rather than using `jlink`, preserving the runtime's JVMCI and future polyglot facilities.
Construct the OCI image from the already-packaged JAR with Docker-compatible tooling:

```bash
SKYWRIGHT_VERSION=0.1.0-SNAPSHOT
SKYWRIGHT_REVISION="$(git rev-parse HEAD)"
SKYWRIGHT_BUILD_CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

docker build \
  --file backend/src/main/docker/Dockerfile \
  --tag "skywright-backend:${SKYWRIGHT_VERSION}" \
  --build-arg "JAR_FILE=target/skywright-backend-${SKYWRIGHT_VERSION}.jar" \
  --build-arg "APPLICATION_VERSION=${SKYWRIGHT_VERSION}" \
  --build-arg "SOURCE_REVISION=${SKYWRIGHT_REVISION}" \
  --build-arg "BUILD_CREATED=${SKYWRIGHT_BUILD_CREATED}" \
  backend
```

The image stores that identity in standard OCI labels as well. Inspect it without starting the
application:

```bash
docker image inspect skywright-backend:0.1.0-SNAPSHOT \
  --format '{{json .Config.Labels}}'
```

Supply deployment configuration only when starting a container. The root filesystem can remain
read-only; `/tmp` is the only documented writable runtime location and should be an explicit
temporary mount. `JAVA_TOOL_OPTIONS` injects ordinary JVM settings without replacing the exec-form
entry point, including the larger stack required by the later in-process GraalPy bridge:

```bash
docker run --rm \
  --name skywright-backend \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --env SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=production \
  --env JAVA_TOOL_OPTIONS=-Xss2m \
  --publish 8080:8080 \
  skywright-backend:0.1.0-SNAPSHOT
```

The image contains no deployment-specific setting or secret, runs as fixed UID/GID `10001:10001`,
and writes application logs only to standard output. Stop it with `docker stop --time 20
skywright-backend`; Docker sends SIGTERM directly to the JVM, readiness is withdrawn, and Spring
allows up to 20 seconds for in-flight HTTP work before exit.

Build the production image and run the complete operator-facing smoke verification with:

```bash
./mvnw -pl backend -am -Pcontainer-smoke verify
```

The command requires a working `docker` CLI. A Podman-compatible local runtime can be selected
without changing the test:

```bash
./mvnw -pl backend -am -Pcontainer-smoke \
  -Dbackend.container.runtime=podman verify
```

The smoke test uses a read-only root, starts the production entry point, validates the non-root and
GraalVM identities, injects `-Xss2m`, calls `/livez`, `/readyz`, `/actuator/info`, and
`/openapi/skywright-api.yaml`, validates structured stdout logging, and sends normal SIGTERM. It
also proves invalid required external configuration exits with a sanitized diagnostic.

For local container debugging, bind JDWP only to loopback and retain the same runtime constraints:

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
