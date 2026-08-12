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

## Production artifact

Package the executable layered JAR from the repository root:

```bash
./mvnw -pl backend -am -DskipTests package
```

The artifact is `backend/target/skywright-backend-0.1.0-SNAPSHOT.jar`. It contains version, build
time, and the full source revision in `META-INF/build-info.properties`; the same non-sensitive
identity is available from `GET /actuator/info` while it is running.

The separate [backend deployment module](../backend-deployment/README.md) consumes this exact JAR
to build the production OCI image.
