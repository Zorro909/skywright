# Repository quality gate

`scripts/quality` is the versioned Linux definition of repository verification. `scripts/quality
run` executes the complete current plan. `scripts/quality run application` qualifies the canonical
API through the independently verified Angular artifact and the packaged Spring/browser boundary;
`scripts/quality run image` builds and exercises the paired production backend and SkyPilot API
server images. `scripts/quality run deployment` verifies the deployment commands, rendered profiles,
supervisor, bundle, and release workflow. The `java`, `frontend`, and `sdk` selectors provide
focused iteration. Every check delegates to the Maven, pnpm, and SDK `uv` interfaces documented by
its owning project part. The command prints all active checks,
including deliberately inapplicable work, and fails explicitly when an exact prerequisite is
missing or has drifted.

The separate `deployment/scripts/system-test --context <kind-context>` command runs the real
rootless-Podman kind workflow. It requires and mutates an existing local development cluster, so the
ordinary GitHub-hosted pull-request lane does not invoke it. Missing Skaffold, Podman, kind,
kubectl, an active matching context, or the expected cluster is a failure rather than a skip.

The `integration` selector runs only the real-service suites: PostgreSQL 18 behavior at the running
backend boundary, Python Run Store recording through `run_training_process`, and Java Run Store
access through its module interface. Test processes start the repository-pinned PostgreSQL and
SeaweedFS images through a `docker` CLI connected to a Docker-API-compatible daemon. Docker Engine
and Podman sockets satisfying that contract are supported; a missing CLI or unreachable daemon is
a failed prerequisite. The complete local plan includes this selector.

CI runs the Java and SDK real-service suites in separate jobs. The SDK suite
starts after planning because it needs neither the backend build nor GraalPy.
The `Real-service integration` result requires both jobs to succeed whenever
integration applies. The complete local selector still runs both suites.

The planning interface accepts either a Git comparison or explicit paths:

```bash
scripts/quality plan --base origin/main
scripts/quality plan --format json --changed-file frontend/src/app/app.ts
```

Root files, wrappers, GitHub automation, and unknown top-level paths select every
check. All backend changes, including Java adapters and Python bridge resources,
select real-service integration. Narrower backend rules require explicit impact
coverage before replacing this default.

Configuration, metric and Run Definition resources under the SDK are shared
contracts. Changes to any file in those packaged resource directories select Java,
SDK, integration, application, image, Environment Profile and security checks.
Planner regressions inspect `backend/pom.xml` so a newly packaged SDK resource
family must have consumer coverage. Other API, frontend, deployment and fixture
paths retain their component plans. Documentation-only changes retain the visible
aggregate result without build jobs.

The Plan job runs `python3 -m unittest discover -s tests/quality -v` before using the
planner. A broken planner or gate regression therefore fails the required workflow.

## GitHub Actions contract

`Repository Quality / Quality Gate` is the sole stable branch-protection result. Internal jobs may
change without changing that name. The aggregate compares every observed job outcome with the
versioned plan: an applicable failure, cancellation, missing result, or unexpected skip fails;
planned irrelevant jobs may be skipped and remain visible.

Pull requests test GitHub's synthetic merge revision and retain the contributor's head revision as
diagnostic metadata for 90 days. `main` and merge-queue runs identify `github.sha`. Release
workflows must call `scripts/quality identity --event tag` with the peeled tag commit as both the
tested revision and tag commit, so another checkout cannot be described as the released source.

The current quality workflow queues runs for the same pull request without cancelling an active
native build, so it can save expensive compiled wheels. Independent cancellation of superseded
verification remains tracked in #225. Main and merge-queue quality runs use unique concurrency
identities; release workflows serialize their own branch or tag. Ordinary verification
uses no repository, publication, infrastructure, or cloud credentials and remains runnable for fork
pull requests.

Protect `main` with the stable aggregate result, strict/up-to-date status checks (or the merge
queue), no force pushes, and no deletion. Do not configure a required human review count. These
repository settings are applied only after the workflow exists on `main`; changing the required
result name requires updating this policy and branch protection together.

## Dependencies, caches, and reports

Third-party actions must use a full commit SHA with a release comment. Dependabot opens separate
weekly grouped pull requests for GitHub Actions, Maven, Python/uv, and pnpm; they pass through the
normal gate and are never automatically merged.

Caches contain downloaded Maven, pnpm, uv, browser, or analysis dependencies managed by
their tools. The approved #224 exception also permits qualified GraalPy native dependencies. Keys include the runner platform, tool version supplied by the setup action, and the
owning lockfile where applicable. Generated sources, compiled output, distributions, images, test
results, and application publication inputs are never restored as build authority. Pull-request caches are not
used as trusted release inputs; release workflows build and verify from the exact tag commit.

GraalPy preparation is shared by quality and deployment workflows through
`.github/actions/prepare-graalpy`. Its exact cache identity includes Maven-resolved inherited
GraalPy and SkyPilot versions, the package lock, constraints, native compiler versions and flags,
OS/libc/architecture, the pinned JDK, preparation code and production Dockerfile. It does not hash
unrelated application sources. CI selects the exact Rust compiler from
`graalpy-environment/rust-toolchain-version` before resolving native inputs. Hosted runner changes
to other native compilers or system libraries still invalidate the environment.

Wheel downloads and packaged environments have separate identities. The wheel identity retains
exact effective runtime/SDK versions, lockfile, constraints, JDK, native compiler versions/flags,
platform, environment POM, and the `scripts/prepare-graalpy-wheels` and `scripts/retag-wheel`
recipes. Workflow, import-probe, identity-verification code, and production Dockerfile edits
require a fresh environment install and qualification but can reuse those compiled wheels.
Keep wheel build options in the hashed recipe; bump the wheel schema when its compatibility
rules change. There is no fallback across different wheel identities.

Preparation restores the completed wheel cache even on a packaged-environment hit, keeping it
in active use. A successful preparation saves one immutable completed cache per wheel identity.
Before that exists, partial caches use a digest of their contents and survive a later build
failure. Pandas is checkpointed immediately after preparation, before the full locked install.
Only pip's built-wheel directory and the local wheelhouse are saved, excluding HTTP responses.
The completed cache is saved only after the actual environment build and import check pass.

Maven download keys include the job name, POMs, and Maven configuration. A missing exact key
falls back only within that job and runner platform, then Maven resolves the current POMs.
This prevents the small native producer dependency set from taking the immutable cache key
needed by backend jobs. Locally installed Skywright artifacts are excluded. Wrapper archives
use a separate key. Pnpm downloads retain exact Node/pnpm and lockfile keys with fallback
within the same toolchain. Chromium keys use Ubuntu 24.04, architecture, and the pinned
Playwright version; unrelated frontend lockfile edits do not invalidate the browser.

Trivy restores its vulnerability and Java databases once per job. The shared setup refreshes
both databases through Trivy before scanning; updates remain enabled. Only main pushes save
a daily database entry, and cached image-analysis results are excluded. All existing SARIF,
SBOM, and vulnerability-policy scans remain required.

Main deployment qualification uses the quality planner before installing prerequisites.
It builds when image or deployment inputs changed; documentation-only pushes skip native
preparation and image builds. Tag publication continues to qualify its exact release source.

A restored environment must match that identity and a digest of its installed dependency files.
Preparation discards generated interpreter bytecode before validation and packaging, retaining
source and native binaries in the digest. This lets ordinary imports regenerate caches without
invalidating unchanged dependencies or allowing an ignored bytecode file to reach the probe.
Every preparation and prebuilt Maven build then launches the current Maven-resolved GraalPy runtime,
checks installed versions against the lock, and imports the native smoke packages. Validation uses
the embedding API so a relocated environment does not execute its cached absolute-path launcher or
rerun installation. The standalone probe closes its context before writing its receipt and exits
normally. Its bounded child-process exit also checks native teardown; a written receipt cannot
override a crash or timeout. Linux extensions remain mapped until process exit to preserve Rust
thread-local destructors, as qualified in [#271](testing/issue-271-native-teardown.md).
Production native ABI and backend executor/socket shutdown have separate system tests.

The quality producer stamps the qualified dependency artifact with the workflow run ID and checked-out
commit. Consumers also compare the artifact's producer attempt with the successful producer job's
output. A consumer-only retry can therefore use the original successful producer, while a different
producer attempt is rejected. Consumers reject foreign-run or foreign-source provenance before prebuilt packaging.
The dependency cache is reusable across runs; application outputs and their verification still come
from the current run. The Java job runs the backend reactor through `install`, which includes its
unit and non-real-service acceptance tests, then publishes only that reactor's installed JARs and
POMs. `scripts/ci-backend` records and validates their exact inventory, SHA-256 digests, tested Git
revision, workflow run and successful producer attempt. Browser and image jobs receive that
handoff and fail before consuming any files if its identity or contents differ. A consumer-only
retry uses the original successful producer attempt; it cannot accept another producer's output.
These artifacts are never restored through a cross-run dependency cache. The image job runs only
the deployment modules, while browser acceptance launches the verified executable and test fixture.
Integration retains its separate real-service tests and build. Frontend changes select the Java
producer because browser and image qualification require its packaged application.

SDK static checks, generated-contract checks, public API checks and coverage run on the primary
Python 3.14 lane. `sdk/scripts/test-unit` runs the discovered unit suite on every compatibility
interpreter without repeating coverage instrumentation. Installed wheel and source-distribution
tests still run on Python 3.10 through 3.14. `sdk/scripts/check` remains the complete local and
release check; it invokes the same unit command with coverage enabled. Release builds run the same dependency validation from their exact source.
Prebuilt environments created before this record existed must be prepared again. A packaging host
may consume dependencies qualified on another host; the recorded native platform remains visible,
and the fresh import probe must succeed on the packaging host. Image ABI qualification remains #250.

The expensive pull-request lanes have the following elapsed-time budgets on GitHub-hosted
`ubuntu-24.04` runners. A warm run restores the exact Maven, pnpm, and Playwright keys from the base
branch; a cold run starts without those entries. Measure from job start through report upload, and
record a representative warm and cold run in the implementing issue whenever the toolchain or these
budgets change.

| Lane | Warm cache | Cold cache |
| --- | ---: | ---: |
| Java and backend | 15 minutes | 25 minutes |
| Real-service integration | 15 minutes | 25 minutes |
| Frontend | 12 minutes | 25 minutes |
| Complete application | 20 minutes | 35 minutes |
| Production control-plane images and scan | 25 minutes | 40 minutes |
| Deployment contracts | 5 minutes | 10 minutes |

Chromium operating-system dependency setup and a cold Chromium download are separately named steps;
each has a hard 10-minute limit and emits its phase before invoking Playwright. Exceeding either
limit is a failed job, not an indefinitely stalled setup. The pnpm store caches downloads only, and
the Playwright cache contains only browser downloads; `node_modules`, generated sources, compiled
resources, reports, and images remain uncached build outputs. The setup action installs the locked
frontend dependencies once; CI passes that fact to `scripts/quality`, and Maven skips its own install
execution while still generating and packaging a fresh frontend artifact.

Machine-readable test, coverage, security, and failure diagnostics are uploaded with `always()`
when their job ran. Test, coverage, security, and diagnostic reports are retained for 90 days.
Ordinary pull-request artifacts are retained for 7 days and successful `main` artifacts for 30
days; published release evidence is retained indefinitely. Coverage is reported without a numeric
threshold. Required tests do not use blanket retries; a narrow temporary quarantine needs an open
issue, a named test, an owner, and an expiry.

## Security finding policy

GitHub dependency review rejects newly introduced high or critical dependency vulnerabilities.
CodeQL analyzes the repository's supported Java/Kotlin, JavaScript/TypeScript, and Python sources.
GitHub secret scanning and push protection remain enabled for the repository. A fixable high or
critical finding blocks integration; a finding without an available fix remains visible and must
have a tracking issue. Lower findings remain visible for risk-based prioritization.

A suppression is exceptional and must be:

- scoped to one finding identifier and the narrowest affected path or dependency;
- linked to an open repository issue containing the risk decision and remediation owner;
- assigned an expiry no more than 90 days away; and
- removed immediately when the finding is fixed or the scope disappears.

Do not use wildcard, repository-wide, unbounded, or undocumented suppressions. A suppression's
expiry is a failing condition, not an automatic extension. Reviewers must reject changes that mute
the scanner outside this policy. Dependency-review exceptions live in
`security/suppressions.json`; `scripts/quality security-policy` rejects an invalid identifier,
wildcard scope, missing repository issue, expired exception, or expiry beyond 90 days. The CI
dependency policy applies a valid exception only to its exact manifest, package URL, and advisory.
Each entry records the exact risk owner and decision; CI resolves its linked issue, requires the
issue to remain open, and requires its body to contain both pieces of evidence.
CodeQL and secret-scanning dismissals must carry the same evidence in their GitHub alert record;
repository-wide source exclusions are not permitted. Their comments must use `Issue:`, `Owner:`,
`Decision:`, `Expires:`, and `Scope:` lines; scope must equal the CodeQL path or secret alert
locations URL, and the linked open issue must contain the owner and decision. Trusted CI audits
every dismissed CodeQL alert after analysis. GitHub does not grant Actions' `GITHUB_TOKEN` access
to secret-scanning alerts, so the daily `Security Governance` workflow audits every non-remediation
secret resolution with `scripts/quality github-dismissal-policy --scanner secret-scanning`. Its
`SECURITY_AUDIT_TOKEN` is a dedicated GitHub App installation token or fine-grained token with
read-only secret-scanning-alert and issue permissions; it has no publication or infrastructure
access and is not available to ordinary verification jobs. A missing credential fails the
scheduled control visibly. Fork pull requests need no permission to read base-repository alerts.

The production backend image is scanned twice with pinned Trivy tooling. The retained SARIF records
all high and critical findings, including findings without a fix; a second enforcement scan rejects
every fixable high or critical finding. The image scan accepts no scanner-local ignore file, wildcard
exclusion, or hidden baseline. If an image suppression is ever required, this policy and the
versioned suppression validator must first gain an exact finding-and-package scope with the same
open-issue, owner, decision, and 90-day expiry evidence. This prevents an ad hoc image exception from
bypassing repository governance.
