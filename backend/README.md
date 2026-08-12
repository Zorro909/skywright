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
