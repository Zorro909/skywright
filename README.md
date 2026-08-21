# Skywright

Skywright is built as a Maven reactor. The repository root is its packaging-only aggregator and
shared build parent; `api/skywright-api` publishes the reusable product contract,
[`frontend`](frontend/README.md) publishes the optimized browser application as classpath resources,
`backend` is the Spring Boot application module, and `backend-deployment` packages that application
as its production OCI artifact. The sibling [`sdk`](sdk/README.md) project-part is the independently
buildable pure-Python runtime SDK for Training Projects.

## Required toolchain

The repository pins every build tool in a file consumed by its native version manager:

| Tool | Version | Source of truth |
| --- | --- | --- |
| GraalVM Community | SDKMAN `25.2.4-graalce` (`GraalVM CE 25.2.4+7.1`, OpenJDK `25.0.4+7`) | `.sdkmanrc`, with the verified CI archive in `quality/toolchain.json` |
| Maven | 3.9.16 | `.sdkmanrc` and `.mvn/wrapper/maven-wrapper.properties` |
| Node | 26.7.0 | `.nvmrc` and `frontend/package.json` |
| pnpm | 11.21.0 | `frontend/package.json` |
| Playwright | 1.62.1 | `frontend/package.json` |
| uv | 0.8.8 | `sdk/pyproject.toml` |
| Contributor Python | 3.14 | `scripts/setup-worktree` and the primary CI lanes |

Install [SDKMAN](https://sdkman.io/install) and [NVM](https://github.com/nvm-sh/nvm), then have
the worktree creator run the repository setup command after checkout:

```bash
scripts/setup-worktree
```

SDKMAN and NVM installations, the pnpm content-addressed store, uv-managed Python, and the
Playwright browser cache are user-level and shared by all worktrees. Only checked-out dependencies
such as `frontend/node_modules` remain worktree-local. Re-running setup is safe and reuses installed
versions. It does not install Playwright's operating-system packages; install those once when your
host lacks them.

Open a new shell in the repository root after initial setup, or activate the versions immediately:

```bash
sdk env
nvm use
java -version
mvn --version
node --version
pnpm --version
uv --version
```

`java -version` must identify GraalVM Community 25.2.4 and Java 25.0.4. `mvn --version` must
identify Maven 3.9.16 and the same JDK. The build validates the exact Maven, Java, GraalVM, vendor,
and OpenJDK runtime versions before creating module artifacts. Maven Toolchains selects that active
JDK for compilation and tests; an explicit `~/.m2/toolchains.xml` is not required.

The checked-in Maven Wrapper remains available for CI and environments where SDKMAN is unavailable.
On Windows, use `mvnw.cmd` in place of `mvn` when Maven 3.9.16 is not installed directly.

## Build and test

From the repository root. Full-reactor `package` and `verify` also build the production image, so
they require a Docker-compatible daemon:

```bash
# Run the complete current repository quality plan (recommended before a pull request)
scripts/quality run

# Run one or more focused component checks through their supported native commands
scripts/quality run java
scripts/quality run frontend sdk

# Compile, run unit tests with Surefire, run *IT acceptance tests with Failsafe, and package all modules
mvn verify

# Verify only the backend and the parent projects it needs
mvn -pl backend -am verify

# Run the backend's fast unit-test convention (*Test)
mvn -pl backend -am test

# Run one backend integration test through Failsafe
mvn -pl backend -am -Dit.test=LivenessIT verify

# Build the executable backend JAR without running tests
mvn -pl backend -am -DskipTests package

# Verify the frontend and the packaged Spring/Chromium acceptance seam
mvn -pl backend -am package
mvn -pl frontend -am -Ppackaged-acceptance verify

# Build the executable JAR and production OCI image
mvn -pl backend-deployment -am package

# Build the executable JAR and production OCI image through verification
mvn -pl backend-deployment -am verify

# Test the Python SDK through its delegated native uv workflow
mvn -pl sdk -am test

# Build the SDK wheel and source distribution through the reactor
mvn -pl sdk -am package

# Run the complete SDK verification, including both installed distribution paths
mvn -pl sdk -am verify
```

The quality command prints every check as applicable or inapplicable and validates exact local
prerequisites before invoking Maven, pnpm, or the SDK's `uv` workflow. GitHub-managed dependency
review, source analysis, and repository secret scanning are always reported as inapplicable to a
local run; they remain required by the aggregate CI gate for relevant changes. See the
[quality-gate policy](docs/quality-gate.md) for change planning, CI identity, retention, caches,
security findings, and branch protection.

The executable artifact is `backend/target/skywright-backend-0.1.0-SNAPSHOT.jar`. It serves the web
application at `/`; direct application routes such as `/about` use the same packaged entry point.

The SDK wheel and source distribution are written to `sdk/target/dist/`. See the
[SDK contributor guide](sdk/README.md) for the native install/build commands, supported Python and
platform policy, operational `skywright-runtime` seam, release identity inputs, and PyTorch
ownership.

## HTTP APIs

- [Skywright product API](api/skywright-api/README.md) — product operations under `/api/v1`.

## Run locally

The backend has no database, SkyPilot, GraalPy, object-storage, Vault, or other feature-service
prerequisite. It does require a non-secret deployment environment identifier. Start it directly
with readable local logs using:

```bash
export SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=local
export SPRING_PROFILES_ACTIVE=local
mvn -pl backend spring-boot:run
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
mvn -pl backend-deployment -am package
```

See the [backend deployment guide](backend-deployment/README.md)
for exact standalone image construction, external configuration, read-only-root operation, JVM
option injection, build-identity inspection, graceful termination, and local container debugging
commands.

To build and exercise that exact Maven image through its production runtime boundary, run
`scripts/quality run image`. CI scans the same local image but does not publish it.

## Run or deploy Skywright

The versioned deployment entry point uses Skaffold 2.24.0 with an existing rootless-Podman kind
cluster for local work:

```bash
scripts/deploy local --context kind-kind-cluster
```

Local reset, branch following, production prerequisites, verified Deployment Bundle apply and
rollback, and the real Podman-kind system check are documented in
[`deployment/README.md`](deployment/README.md).

## Remote debugging

Start the local application with JDWP listening on port 5005, then attach a Java debugger to
`localhost:5005`:

```bash
mvn -pl backend spring-boot:run \
  -Dspring-boot.run.jvmArguments='-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005' \
  -Dspring-boot.run.arguments='--skywright.deployment.environment=local'
```
