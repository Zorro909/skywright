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

## Training Project Version discovery

`ProjectVersionRegistry` is the pull-side OCI boundary. `TrainingProjectVersions` enumerates and
resolves immutable version-artifact digests for a trusted project binding, verifies every declared
image and contract directly against registry authority, and
recompiles the configuration and metric contracts with the backend's trusted schemas. It returns a
`ProjectVersionAssessment` with stable reasons instead of narrowing an incomplete backend map or
using stale artifacts. Registry transport and credentials remain adapters so target/registry
qualification can exercise them without weakening the domain verifier.

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

The SkyPilot bridge has separate bounded queues for short control calls and held operation work.
`skywright.skypilot.bridge.control-queue-capacity` and `held-queue-capacity` must be positive. The
defaults are 8 and 4. `shutdown-grace` defaults to 5 seconds, and
`availability-probe-interval` defaults to 30 seconds. These are deployment settings, not Run
Configuration. A full queue returns `bridge-busy`; client, authentication, reachability, and
version failures return `skypilot-unavailable` with a safe cause category.
Set `skywright.skypilot.bridge.api-server-endpoint` to the separately operated, version-paired
SkyPilot API server. The client never starts an API server inside the backend process.

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

The artifact is `backend/target/skywright-backend-0.1.0-SNAPSHOT.jar`. Maven also creates the locked
GraalPy and SkyPilot 0.13.0 environment at `backend/target/graalpy-resources`; production packaging
places it beside the executable. Runtime startup never installs Python packages. The JAR contains version, build
time, and the full source revision in `META-INF/build-info.properties`; the same non-sensitive
identity is available from `GET /actuator/info` while it is running.

The separate [backend deployment module](../backend-deployment/README.md) consumes this exact JAR
to build the production OCI image.

## PostgreSQL persistence

PostgreSQL 18 is the only supported metadata database. Tests consume the repository pin
`postgresql.container.image` from the root Maven model; deployment work must consume that same
property rather than copying a tag or digest. A database provisioner creates the database, a
`skywright` schema owned by the migration role, and a separate runtime role. Liquibase then owns
all objects inside that schema, including its history tables. Neither role uses `public` for
Skywright objects.

Supply the two externally managed credentials through these environment variables:

- `SKYWRIGHT_DATABASE_MIGRATION_URL`, `SKYWRIGHT_DATABASE_MIGRATION_USERNAME`, and
  `SKYWRIGHT_DATABASE_MIGRATION_PASSWORD` identify the schema-owner role used during startup and
  through short-lived, read-only schema-compatibility checks; it is never the application pool.
- `SKYWRIGHT_DATABASE_RUNTIME_URL`, `SKYWRIGHT_DATABASE_RUNTIME_USERNAME`, and
  `SKYWRIGHT_DATABASE_RUNTIME_PASSWORD` identify the restricted application role used by
  Hibernate after migration.

The runtime pool bounds connection acquisition to two seconds, and PostgreSQL JDBC URLs must bound
connection and socket establishment to five seconds (for
example, `?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true`). Missing configuration,
unreachable PostgreSQL, Liquibase validation or checksum failure, migration failure, and Hibernate
mapping mismatch all stop startup. At runtime, `/livez` remains independent of PostgreSQL while
`/readyz` and `/actuator/health` include database connectivity, Liquibase history and checksum
validation through a short-lived migration connection, and Hibernate schema validation. They
recover only after connectivity and schema compatibility return.
Database-backed request failures map to a `SKYWRIGHT_CAPABILITY_UNAVAILABLE` problem that names
PostgreSQL without exposing driver diagnostics. Only failures with a recognized transient network,
deadlock, serialization, or transient-resource cause set `retryable` to `true`.

Persistence changes follow these conventions:

- Add immutable, issue-numbered changesets through
  `db/changelog/db.changelog-master.yaml`; prefer one declarative change type per changeset.
  Grant the runtime role only the DML privileges each application table actually requires; it has
  no default access to Liquibase history or future tables.
  Essential PostgreSQL SQL that Liquibase Community cannot express needs a separate reviewed undo
  file registered as its rollback and an update/rollback/update PostgreSQL test.
- Keep generated HTTP DTOs, domain objects, and JPA entities separate, with explicit mappings.
  Application code uses Spring Data repositories, JPQL, or Criteria API only—never JDBC, native
  queries, or handwritten application SQL.
- Application services own one `@Transactional` boundary per use case. Repository default
  transactions and Open EntityManager in View are disabled. Use optimistic versions for mutable
  entities and database constraints for relational invariants.
- Store application UUIDv4 identities as PostgreSQL `uuid`, instants as UTC `timestamptz(6)`, and
  JSON documents as `jsonb` only when the whole value is canonically JSON. Relational identity,
  relationships, invariants, ordering, and routine reporting remain typed columns.
- Keep Skywright-originated records and Retained SkyPilot Facts in separate provenance-owned
  tables. Their only permitted cross-provenance relationship is the Skywright-owned Run identity.

The persistent local database resource and its narrowly scoped reset command belong to the
Skaffold environment in issue #82; it must preserve this image pin, role split, schema contract,
and changelog rather than create another database topology.

This foundation is the initial persistence release, so there is no published database-bearing
application release to use as a preceding-release baseline; its cross-version qualification is
recorded as not applicable. PostgreSQL qualification executes the complete changelog as update,
rollback, and update on the pinned engine. Later consumer releases add their preceding release
artifact and representative domain operations to this compatibility seam.
