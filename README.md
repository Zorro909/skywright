# Skywright

Skywright is built as a Maven reactor. The repository root is its packaging-only aggregator and
shared build parent; `backend` is the single Spring Boot backend module.

## Required toolchain

Builds require all of the following exact versions:

- GraalVM Community 25.2.4 (`GraalVM CE 25.2.4+7.1`), based on OpenJDK `25.0.4+7`
- Maven Wrapper 3.3.4, which downloads Maven 3.9.16

Download the matching GraalVM Community archive from the
[GraalVM 25.2.4 release](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.2.4),
verify its published SHA-256 checksum, extract it, and make it the JDK used to launch the wrapper:

```bash
export JAVA_HOME=/absolute/path/to/graalvm-community-25.2.4+7.1
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./mvnw --version
```

`java -version` must identify GraalVM Community 25.2.4 and Java 25.0.4. `./mvnw --version`
must identify Maven 3.9.16 and the same JDK. The build validates the Maven version, Java feature
and patch level, GraalVM release, vendor, and OpenJDK build before creating module artifacts.
Maven Toolchains then selects that JDK for compilation and tests. JDK discovery recognizes the
active `JAVA_HOME`; an explicit `~/.m2/toolchains.xml` is not required.

On Windows, set `JAVA_HOME`, add `%JAVA_HOME%\bin` to `PATH`, and use `mvnw.cmd` in place of
`./mvnw` in the commands below.

## Build and test

From the repository root:

```bash
# Compile, run unit tests with Surefire, run *IT acceptance tests with Failsafe, and package all modules
./mvnw verify

# Verify only the backend and the parent projects it needs
./mvnw -pl backend -am verify

# Run the backend's fast unit-test convention (*Test)
./mvnw -pl backend test

# Run one backend integration test through Failsafe
./mvnw -pl backend -am -Dit.test=LivenessIT verify

# Build the executable backend JAR without running tests
./mvnw -pl backend -am -DskipTests package
```

The executable artifact is `backend/target/skywright-backend-0.1.0-SNAPSHOT.jar`.

## Product HTTP boundary

The canonical OpenAPI 3.1 contract is
`backend/src/main/resources/static/openapi/skywright-api.yaml`. It is the design source for all
product HTTP operations under `/api/v1`; it intentionally contains no product path until a real
feature endpoint is introduced. The normal Maven build validates this document and uses the pinned
OpenAPI Generator to create Spring Boot 4/Jackson 3 server interfaces and boundary DTOs under
`backend/target/generated-sources/openapi`. This generated directory is disposable build output.

Handwritten HTTP adapters implement the generated interfaces. They must map explicitly between
generated boundary DTOs and internal domain or persistence types; generated DTOs do not become
internal models.

The executable application packages the canonical bytes and serves them read-only at
`GET /openapi/skywright-api.yaml`. Swagger UI is not enabled. Operational Actuator endpoints remain
outside the product contract.

Every HTTP response carries an `X-Correlation-ID`. An incoming identifier is retained when it is 1
to 64 characters, starts with an ASCII letter or digit, and otherwise contains only ASCII letters,
digits, `.`, `_`, `:`, or `-`; a UUID is generated otherwise. The effective value is request-scoped
for diagnostics only. It is neither a Principal Identity nor an idempotency key.

HTTP failures use `application/problem+json` with the RFC 9457 fields plus `errorCode`,
`correlationId`, and `fieldViolations`. Failure details are safe boundary messages and never expose
internal exception messages or stack traces.

## Run locally

The backend has no database, SkyPilot, GraalPy, object-storage, Vault, or other feature-service
prerequisite. Start it directly with:

```bash
./mvnw -pl backend spring-boot:run
```

The main application port defaults to `8080`. Its operational HTTP surface is:

- `GET /livez` — process liveness; it has no external dependency checks
- `GET /readyz` — whether the HTTP application is accepting traffic
- `GET /actuator/health` — the underlying health endpoint containing the two probe groups
- `GET /actuator/info` — non-sensitive artifact and build-version information

Other Actuator endpoints are unavailable. The backend does not enable global virtual threads or
native-image.

After packaging, the same application can be started with:

```bash
java -jar backend/target/skywright-backend-0.1.0-SNAPSHOT.jar
```

## Remote debugging

Start the local application with JDWP listening on port 5005, then attach a Java debugger to
`localhost:5005`:

```bash
./mvnw -pl backend spring-boot:run \
  -Dspring-boot.run.jvmArguments='-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005'
```
