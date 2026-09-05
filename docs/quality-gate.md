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

The planning interface accepts either a Git comparison or explicit paths:

```bash
scripts/quality plan --base origin/main
scripts/quality plan --format json --changed-file frontend/src/app/app.ts
```

Root files, wrappers, GitHub automation, and unknown top-level paths fail safe by selecting every
check. API, backend, frontend, SDK, deployment, shared-fixture (including `protocol/`), and Environment Profile
paths fan out to their affected active checks. The canonical structural-overlay corpus at
`sdk/src/skywright/_configuration_resources/corpus.json` is classified as a shared fixture despite
its SDK-owned location. API, backend, frontend, and shared-fixture changes
select both the complete-application and production-image checks. Documentation-only changes retain
the visible aggregate result without running build-heavy work.

## GitHub Actions contract

`Repository Quality / Quality Gate` is the sole stable branch-protection result. Internal jobs may
change without changing that name. The aggregate compares every observed job outcome with the
versioned plan: an applicable failure, cancellation, missing result, or unexpected skip fails;
planned irrelevant jobs may be skipped and remain visible.

Pull requests test GitHub's synthetic merge revision and retain the contributor's head revision as
diagnostic metadata for 90 days. `main` and merge-queue runs identify `github.sha`. Release
workflows must call `scripts/quality identity --event tag` with the peeled tag commit as both the
tested revision and tag commit, so another checkout cannot be described as the released source.

Superseded Repository Quality verification jobs are cancelled only for the same pull request.
The security job enters its concurrency group even when its scans are inapplicable, so a docs-only update cancels obsolete CodeQL work. Its scan steps remain conditional on the plan and source check.
The native preparation job has an independent non-cancelling queue and finishes saving reusable
wheel dependencies. The `scripts/ci-verify` supervisor checks the PR head before each expensive command and every
60 seconds while it runs, terminating obsolete process groups with bounded grace. This also stops
old work when replacement jobs are skipped or waiting for native preparation. Such a cooperative
stop records a failed old job when GitHub job concurrency has not already cancelled it; the current
revision still needs its successful aggregate. API failures are retried and fail explicitly.
The workflow record can remain active while that native preparation finishes. `main`, merge queue,
tag, and publication runs use unique concurrency identities and finish independently. Ordinary verification
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

Caches contain downloaded Maven, pnpm, uv, browser, or analysis dependencies managed by their
tools. Qualified GraalPy native dependencies are the explicit exception: their canonical identity
includes inherited runtime versions, lock and native build inputs, JDK digest and platform.
Restores verify the identity and installed package/version metadata; preparation also imports native
packages using the current Maven-resolved runtime. Prebuilt Maven packaging rejects mismatches. Keys include the runner platform, tool version supplied by the setup action, and the
owning lockfile where applicable. pnpm may restore compatible downloads by toolchain prefix and
always performs a frozen install. Browser keys use Ubuntu release, architecture and exact Playwright
version, independently of unrelated frontend dependency edits. Generated application sources, compiled application output, distributions, images, test results,
and publication inputs are never restored from a cross-run cache as build authority. CI may hand
verified backend JARs and reactor artifacts to browser/image consumers within the same workflow run
and tested revision. Consumers verify source identity, run ID, complete file inventory and SHA-256
checksums before use. Maven reactor outputs are removed before dependency caches are saved. Pull-request caches are not
used as trusted release inputs; release workflows build and verify from the exact tag commit.

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
execution. A single backend producer generates and packages the frontend and verifies the backend.
Browser and image consumers use that producer's verified artifacts; the local component commands
still build their prerequisites from source. The image consumer does not install Node or pnpm.

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

## CI ownership and measurement

The planner selects real-service integration conservatively for backend changes and all packaged
Java/Python contract families. Plan runs the quality-tool tests. SDK unit discovery excludes
installed-artifact tests and the explicitly marked real-service suite. All five supported Python
versions run unit and installed wheel/sdist scenarios. The primary lane alone runs invariant source
checks and unit coverage; native contributor verification still runs the complete checks.

Main deployment builds use the same native preparation action as quality and skip image builds
for irrelevant changes. Tag publication prepares/qualifies native dependencies directly and retains
exact-tag image tests. Native preparation has a 270-minute limit and progressive wheel caching;
dependent jobs are queued without occupying a runner until preparation succeeds. Main and tag
builds also restore and qualify exact native dependencies, with the same bounded cold-build path.

Image scanning restores databases once and retains them in the job for both report and enforcement
passes. PRs do not save database snapshots. Main saves one daily snapshot and keeps the newest two;
older cache schema entries expire normally. Reports and fixable-finding enforcement remain required.

Compare all Repository Quality jobs, including native preparation, plus the separate deployment
workflow for main commits.
Distinguish initial native-cache misses from warm runs, and record the selected checks and test
counts. The audit and historical run evidence live in
`docs/research/ci-tests-and-caching-audit-2026-09-05.md`.
