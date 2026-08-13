# Skywright web application

The web application is a client-rendered Angular project and a Maven reactor project-part. Its
production build contains static browser resources only: there is no server rendering, service
worker, ZoneJS runtime, or production Node process. Maven packages the optimized output as the
versioned `de.zorro909.skywright:skywright-web` JAR, and the Spring backend consumes that exact JAR
as a runtime dependency.

## Required tools

Native commands require exactly Node 26.7.0 and pnpm 11.21.0. The checked-in `.nvmrc` is an optional
hint; nvm and Corepack are not required. Install the versions using any tool you prefer, then check
them before installing dependencies:

```bash
node --version
pnpm --version
pnpm run preflight
pnpm install --frozen-lockfile
```

For packaged browser acceptance, install the pinned Playwright Chromium once:

```bash
pnpm exec playwright install chromium
```

## Native workflow

Run these commands from `frontend/`:

```bash
pnpm start          # Angular development server
pnpm generate:api   # regenerate disposable TypeScript API boundary types
pnpm format         # apply Prettier formatting
pnpm format:check   # verify formatting
pnpm lint           # Angular ESLint correctness and template accessibility
pnpm typecheck      # strict TypeScript and Angular template checking
pnpm test           # fast Vitest tests
pnpm verify         # preflight, formatting, linting, type checking, and fast tests
pnpm build          # optimized, content-hashed production resources in dist/skywright-web/
```

The development server is for contributor feedback only. The supported application entry point is
the packaged Spring process. Native start, verification, and build commands regenerate the
TypeScript boundary under `target/generated-sources/openapi`; do not edit or commit that output.
The Maven workflow instead unpacks the canonical `skywright-api` reactor artifact before running
the same pinned generator, so handwritten frontend adapters are strictly checked against the
packaged contract.

## Reactor workflow

Run these commands from the repository root after configuring the repository's required GraalVM
and Maven toolchain:

```bash
./mvnw -pl frontend -am verify     # combined verification
./mvnw -pl frontend -am package    # optimized frontend JAR
./mvnw -pl backend -am package
./mvnw -pl frontend -am -Ppackaged-acceptance verify
```

Use the native commands above for individual formatting, linting, type-checking, and test steps.
The Maven lifecycle commands are intentionally used for reactor verification so their
generate-resources phase always unpacks the API artifact and generates the TypeScript boundary
before compilation.

The final two commands build the frontend JAR, embed it in the executable Spring JAR, then start
that packaged application and drive the shell in Chromium. The frontend artifact is written to
`frontend/target/skywright-web-0.1.0-SNAPSHOT.jar`; the packaged entry point is
`backend/target/skywright-backend-0.1.0-SNAPSHOT.jar`.
