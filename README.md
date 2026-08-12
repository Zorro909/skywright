# Skywright

Skywright is built as a Maven reactor. The repository root is its packaging-only aggregator and
shared build parent; `api/skywright-api` publishes the reusable product contract, `backend` is the
Spring Boot application module, and `backend-deployment` packages that application as its production
OCI artifact.

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

From the repository root. Full-reactor `package` and `verify` also build the production image, so
they require a Docker-compatible daemon:

```bash
# Compile, run unit tests with Surefire, run *IT acceptance tests with Failsafe, and package all modules
./mvnw verify

# Verify only the backend and the parent projects it needs
./mvnw -pl backend -am verify

# Run the backend's fast unit-test convention (*Test)
./mvnw -pl backend -am test

# Run one backend integration test through Failsafe
./mvnw -pl backend -am -Dit.test=LivenessIT verify

# Build the executable backend JAR without running tests
./mvnw -pl backend -am -DskipTests package

# Build the executable JAR and production OCI image
./mvnw -pl backend-deployment -am package

# Build the executable JAR and production OCI image through verification
./mvnw -pl backend-deployment -am verify
```

The executable artifact is `backend/target/skywright-backend-0.1.0-SNAPSHOT.jar`.

## HTTP APIs

- [Skywright product API](api/skywright-api/README.md) — product operations under `/api/v1`.

## Run locally

The backend has no database, SkyPilot, GraalPy, object-storage, Vault, or other feature-service
prerequisite. It does require a non-secret deployment environment identifier. Start it directly
with readable local logs using:

```bash
export SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=local
export SPRING_PROFILES_ACTIVE=local
./mvnw -pl backend spring-boot:run
```

See the [backend deployment configuration and logging guide](backend/README.md) for configuration
sources, strict validation, production JSON fields, and safe request-logging limits.

The main application port defaults to `8080`. Its operational HTTP surface is:

- `GET /livez` — process liveness; it has no external dependency checks
- `GET /readyz` — whether the HTTP application is accepting traffic
- `GET /actuator/health` — the underlying health endpoint containing the two probe groups
- `GET /actuator/info` — non-sensitive artifact and build-version information

Other Actuator endpoints are unavailable. The backend does not enable global virtual threads or
native-image.

After packaging, the same application can be started with:

```bash
java -jar backend/target/skywright-backend-0.1.0-SNAPSHOT.jar \
  --skywright.deployment.environment=production
```

## Build the production image

The `backend-deployment` module packages that executable layered JAR into a Linux amd64 OCI image
based on the immutable GraalVM Community 25.2.4 runtime manifest. Its normal Maven package phase
builds the image:

```bash
./mvnw -pl backend-deployment -am package
```

See the [backend deployment guide](backend-deployment/README.md)
for exact standalone image construction, external configuration, read-only-root operation, JVM
option injection, build-identity inspection, graceful termination, and local container debugging
commands.

## Remote debugging

Start the local application with JDWP listening on port 5005, then attach a Java debugger to
`localhost:5005`:

```bash
./mvnw -pl backend spring-boot:run \
  -Dspring-boot.run.jvmArguments='-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005' \
  -Dspring-boot.run.arguments='--skywright.deployment.environment=local'
```
