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

Deployment configuration is operational input to the backend process. It is separate from a
Training Project's immutable Run Configuration and never makes Skywright a Credential Authority.
The required `skywright.deployment.environment` setting is a non-secret lowercase identifier of
1–32 ASCII letters, digits, or hyphens, beginning with a letter.

Use any ordinary Spring configuration source. Later sources override earlier ones, including:

- configuration files: `skywright.deployment.environment=production`
- environment variables: `SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=production`
- JVM system properties: `-Dskywright.deployment.environment=production`
- command-line arguments: `--skywright.deployment.environment=production`

The setting binds to an immutable typed configuration record. Bean Validation rejects missing or
invalid identifiers, and strict binding rejects unknown properties beneath
`skywright.deployment`. Startup fails before readiness in all three cases. Failure diagnostics name
the property and validation rule but omit supplied values. The generated configuration metadata is
packaged in the executable for editor and operator tooling.

Do not place credentials, tokens, connection strings, or other secrets in this configuration. No
committed setting is secret or specific to one deployment. Actuator exposes build information and
health only; it does not expose configuration values.

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
