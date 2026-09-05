CI and test audit, 2026-09-05

The largest avoidable cost is the separate main deployment build. It rebuilds the GraalPy environment even when Repository Quality can restore it in seconds. Within Repository Quality, backend verification runs up to three times for one revision, and Trivy repeatedly restores and refreshes the same large database. These are better first targets than deleting application tests.

This report proposes changes for approval. No workflow, test, cache, repository setting, or issue was changed during the investigation.

I inspected all five workflow definitions, their composite setup actions, the quality planner, Maven reactor and test configuration, component check scripts, representative test implementations, accepted ADRs, and the relevant GitHub issues. The checkout and remote main both resolved to `ab1edb1e4fc3ae03764c2d712862c20ee8875236`. Live evidence includes the latest 60 workflow runs, detailed jobs for three successful full quality runs and three main deployment runs, selected logs, all 38 active caches, repository cache and retention settings, and the most recent 100 artifact records. Timings below are observations, not promised savings. I did not trigger CI, publish anything, or run the full container/browser suite locally.

The current pipeline has these responsibilities. [Workflow definitions][workflows]

| Workflow | Trigger | Work to preserve |
| --- | --- | --- |
| Repository Quality | PR, main push, merge group | Change planning; dependency preparation; applicable Java, frontend, application, image, real-service, deployment, profile, SDK and security checks; stable Quality Gate |
| Deployment release | Every main push; `v*` tags; manual release/repair | Main Skaffold image qualification; exact-source paired image and immutable Deployment Bundle publication |
| Publish Python SDK | `sdk-v*` tags | Exact-tag release-mode distributions, installed consumers, checksums, SBOMs, attestations and immutable publication |
| Environment Profile Release | `profile-v*` tags | CUDA and ROCm qualification, coordinated publication and exact OCI artifact handoff |
| Security Governance | Daily; manual | Audit secret-scanning dismissals |

The source inventory distinguishes declared cases from runtime expansion. Parameterized tests increase executed counts; these numbers are not coverage percentages. [Backend tests][backend-tests], [image tests][image-tests], [SDK tests][sdk-tests], [frontend tests][frontend-tests], [support tests][support-tests], [project-action tests][action-tests], [profile tests][profile-tests]

| Test area | Static inventory |
| --- | --- |
| Backend | 49 classes, 25 `*Test` and 24 `*IT`; 213 annotated methods before parameter expansion; 20 IT classes tagged `real-service` |
| Backend / SkyPilot image acceptance | 6 / 3 methods |
| SDK | 12 modules; 181 test functions before parameter expansion |
| Frontend | 8 component/API spec files with 32 cases; 7 packaged Playwright cases |
| Quality / deployment tooling | 37 / 42 unittest methods |
| Project-publication action / profile release support | 32 functions / 12 unittest methods |

The following measurements establish where the time goes. Job minutes sum running job durations, excluding queue time and billing rounding. Full-gate elapsed time runs from the first job starting to the last job completing.

| Observed work | September 1 | September 3 | September 4 |
| --- | ---: | ---: | ---: |
| Full quality gate elapsed | 13m42s | 13m53s | 13m44s |
| Full quality gate summed job time | 62.1 min | 70.8 min | 63.7 min |
| Java job | 3m19s | 3m50s | 2m56s |
| Complete application job | 4m00s | 4m48s | 3m36s |
| Image job, including scans | 8m33s | 10m04s | 9m55s |
| Real-service integration job | 12m48s | 13m18s | 12m52s |
| Separate main deployment job | 131m34s | 92m35s | 80m44s |

Quality evidence: [September 1][run-sep1], [September 3][run-sep3], [September 4 PR][run-sep4]. Deployment evidence: [September 1][deployment-sep1], [September 3][deployment-sep3], [September 4][deployment-sep4]. The September 4 quality sample is a full Dependabot PR run; the deployment sample is the later main push. The latter changed only two ADR files. Its [quality run][docs-run] correctly skipped expensive checks, while deployment still spent 80m44s building images.

The cache API reported 11,714,627,186 bytes, or 10.91 GiB, across 38 entries. The configured maximum was 10 GB. This snapshot establishes storage pressure; it does not establish which caches have already been evicted. GitHub documents eviction by last access when the limit is exceeded. [Cache inventory API][cache-api], [configured limit API][cache-limit], [GitHub cache behavior][github-cache]

| Cache family | Entries | Total MiB |
| --- | ---: | ---: |
| Trivy databases | 8 | 7,664.6 |
| Maven and Maven Wrapper | 7 | 1,196.6 |
| Playwright browsers | 3 | 806.8 |
| Progressive GraalPy wheels | 4 | 415.2 |
| GraalVM archive | 1 | 329.2 |
| Packaged GraalPy environments | 3 | 284.9 |
| pnpm downloads | 3 | 237.1 |
| CodeQL | 3 | 195.2 |
| Trivy binary | 1 | 41.7 |
| uv | 5 | 0.6 |

I recommend the following implementation order. Effort describes relative scope, not a delivery estimate.

| Order | Change | Existing issue | Effort | Expected benefit |
| --- | --- | --- | --- | --- |
| 1 | Fix test selection/discovery and execute quality-tool tests | #219 | Small to medium | Prevent optimizations from hiding missing coverage |
| 2 | Correct GraalPy identity and reuse preparation in deployment | #224, extend #225 | Medium | Remove the observed 73-minute environment build on an exact cache hit; skip irrelevant main builds |
| 3 | Restore Trivy once and bound database cache growth | Extend #225 or separate follow-up | Small to medium | Remove repeated large restores and database refreshes; recover cache capacity |
| 4 | Give backend verification one owner and share same-run build outputs | #225 | Medium | Remove one complete Java job and a further repeated backend verification |
| 5 | Separate native preparation from cancellable PR checks | #225 | Medium | Stop spending runners on superseded PR revisions |
| 6 | Split SDK invariant checks from runtime compatibility tests | #225 | Small to medium | Remove repeated static checks and coverage output while retaining all supported runtimes |
| 7 | Improve measured test fixture costs and smaller caches | Follow-up | Medium | Reduce the actual PR critical path after the above changes |

1. Fix selection and discovery before reducing verification.

   The backend category does not generally select real-service integration. A separate prefix allowlist misses affected adapters and shared contracts. For example, orchestration, target-storage, pricing and training-project source edits can bypass their real-service suites. Issue [#219][issue219] already records specific GraalPy, S3 and packaged-schema omissions and requires conservative backend selection until narrower rules have explicit coverage. The SDK also names individual test files in both its check script and pytest defaults, omitting `test_mds_decoding.py`. [Planner][planner], [SDK check script][sdk-check], [pytest configuration][sdk-project]

   The 37 tests under `tests/quality` are also absent from the workflow and component command graph. They include useful execution tests of planning, aggregation and security policy. Run them in a cheap tooling job before relying on planner changes. Replace SDK filename lists with discovered suites and explicit separation of unit, real-service and installed-artifact tests, bringing the omitted 29 MDS encoding cases into the gate. Be careful with `sdk/tests/system/conftest.py`, which requires an artifact argument and applies system markers; simply changing discovery to `pytest tests` is insufficient. [Quality tests][quality-tests], [MDS cases][mds-tests], [system fixtures][sdk-system-fixtures]

   Acceptance evidence should include planner cases for every consumer family in #219, failure/cancellation/unexpected-skip aggregation cases, collected MDS tests, and proof that adding a new intended unit-test module needs no filename-list edit. This may initially increase CI work. That increase repairs missing checks and should be recorded separately from optimization savings.

2. Reuse GraalPy preparation in the main and release image paths, with a complete identity.

   `qualify-main` calls `setup-java` without `graalpy-resources: true`, then runs Skaffold. Its custom backend builder executes `clean verify`; the workflow supplies neither a prepared environment nor `-Dgraalpy.environment.prebuilt=true`. The tag publication job follows the same missing-image build path. Repository Quality already has an exact environment restore, bounded cold preparation, progressive native wheel saves and same-run artifact handoff. [Deployment workflow][deployment-workflow], [backend builder][backend-builder], [quality preparation][quality-workflow], [environment Maven profile][graalpy-pom]

   In the September 4 deployment log, the Maven reactor explicitly reports `Skywright GraalPy environment ... SUCCESS [01:13 h]`. The complete job took 80m44s. Successful full quality runs prepared or restored the environment in 21–36 seconds. This supports saving roughly the 73-minute environment phase on that deployment run if exact reuse works; it does not predict a cold build improvement. [Deployment log][deployment-sep4], [quality preparation job][graalpy-job]

   First implement [#224][issue224]. The exact key currently hashes the child environment POM, lock, constraints, JDK archive digest and backend Dockerfile, but omits inherited `graalpy.version` and `skypilot.version` from the root POM. Derive a canonical identity from effective runtime versions, native build inputs and platform. Store and validate it with the environment. A cache hit currently checks `installed.txt` and exits before import smoke tests; require a bounded compatibility check on restored environments too. Do not use broad fallback restores for the assembled environment. [Java setup action][java-setup], [root POM][root-pom], [quality preparation][quality-workflow]

   Then factor preparation into a reusable path consumed by quality and deployment. On a miss, it must retain the current timeout and progressive-wheel behavior; on a hit, validate and set prebuilt mode before Maven. Preserve exact source qualification and release provenance. Add change planning inside the main deployment workflow so a documentation-only push avoids image builds. Keep tag qualification explicit. A later consolidation can make main image qualification part of one quality execution, but first reconcile the Skaffold-specific build contract with #201 rather than silently dropping it. [Paired deployment criteria][issue201]

   Acceptance evidence: a warm documentation push runs no image build; a relevant warm main build imports the exact prepared environment and runs both image acceptance suites; changing either inherited runtime version invalidates preparation; a cold run still completes full preparation; mismatched environment identity fails before packaging.

3. Stop repeatedly restoring Trivy databases within one job.

   The image workflow invokes the Trivy action four times, once to report and once to enforce policy for each image. The pinned action restores its database on every invocation using a date-based key. In the September 3 image log, each invocation restored roughly 958 MiB from `cache-trivy-2026-09-01`; subsequent invocations downloaded database updates again. Restoring the older archive over the just-updated directory defeats same-job reuse. Eight date/ref database copies account for 7.48 GiB, about 69% of all cache bytes. [Image scan steps][quality-workflow], [pinned Trivy action][trivy-action], [image job log][image-job], [cache inventory][cache-api]

   Install Trivy and restore its databases once per job. Reuse that directory for both image scans and save once. Disable the composite action's internal cache on subsequent calls, or own setup/cache explicitly for the job. Keep database refresh enabled at the start. The first implementation can retain two scans per image to minimize policy risk. A follow-up can scan each image once to JSON and derive SARIF plus blocking output through `trivy convert`, checking behavior against the pinned CLI. Preserve all high/critical findings in reports and block the same fixable findings. Preserve existing scanner coverage. [Trivy report conversion][trivy-convert]

   Bound retained database snapshots and avoid every PR saving a full daily copy. A main-branch warmer with PR restore-only access is one option; it must still refresh an absent or stale database in the PR job and never turn a cache miss into a skipped scan. GitHub caches are immutable and have ref-scoped visibility, so a main cache can serve PRs while a PR merge-ref cache cannot serve main. [GitHub cache behavior][github-cache]

   Acceptance evidence: one database restore per image job, no repeated refresh caused by re-extraction, equivalent report/gate results for fixable and unfixed findings, and a measured reduction in cache bytes. Do not delete active native caches as a blanket cleanup. Reclaim redundant scanner entries only after the replacement policy is working.

4. Execute backend verification once per tested revision and run.

   `scripts/quality` defines the same `-pl backend -am verify` command for `java` and the first half of `application`. The first image command uses `-pl backend-deployment -am verify`; that module depends on backend and therefore repeats its tests. The integration command selects `real-service`, so that separate suite is intentional. Frontend tests are already skipped in these Maven commands through the active `skip-frontend-tests` profile; there is repeated frontend generation/build work, not repeated execution of the entire frontend suite. Application also rebuilds the frontend in its second Maven invocation. [Quality commands][planner], [deployment dependency][backend-deployment-pom], [frontend Maven profiles][frontend-pom]

   Start with one CI owner for backend verification, while retaining `scripts/quality run java` as a focused local command. Have application/browser and image acceptance consume its JAR, test-fixture JAR, required reactor artifacts and source identity from the same run. Make their Maven entry points explicitly avoid rerunning upstream verification while still executing their own acceptance tests. A global `-DskipTests` is unsafe here because it can suppress the image acceptance tests as well. Define the complete artifact handoff and required upstream success before changing skip flags.

   Removing the standalone duplicate job saves its observed 2m56s–3m50s of runner time. Removing the image path's repeated backend work saves additional time, which needs measurement after the handoff. These savings are not additive reductions in PR elapsed time: the real-service job currently determines the gate's roughly 14-minute completion time. Moving builds behind a shared producer also introduces dependencies, so compare both total job minutes and elapsed gate time.

   Preserve the stable aggregate name and failure semantics. Test missing, wrong-revision and incomplete handoff rejection, and compare executed test IDs before and after. Use artifacts within the current run, never a broad `target/` cache or a prior PR's successful test result. Issue [#225][issue225] already requires same-run/same-revision reuse and sequencing after selection fixes.

5. Make PR verification cancellable without discarding long native preparation.

   Quality currently groups runs by PR number but sets `cancel-in-progress: false` for the whole workflow because canceled native builds cannot reliably save progressive wheels. This prevents an active obsolete PR run from stopping. It contradicts the cancellation behavior still documented in the quality policy and required by #116. [Workflow concurrency][quality-workflow], [quality policy][quality-policy], [original quality criteria][issue116]

   Separate expensive native preparation by effective environment identity from ordinary PR verification. Preparation needs its own bounded execution and cache publication path that survives cancellation of a consuming PR run; making it merely another job under a canceled workflow does not achieve that. Restore cancellation only for superseded PR verification. Main, merge-group and publication behavior must remain explicit. Define a cold-input path for PR changes to environment inputs, including forks, without adding publication credentials or promoting PR cache content into release authority.

   Acceptance evidence: a second PR push cancels the obsolete verification jobs, the newest revision passes the full selected gate, native preparation remains bounded and reusable, and trusted release runs consume only qualified trusted inputs. Keep a documented full cold qualification path. This is a workflow restructuring, not a one-line boolean fix. [#225][issue225]

6. Remove repeated SDK contributor checks, retaining runtime coverage.

   The primary lane builds distributions once and hands them to Python 3.10–3.13 jobs. Each compatibility job then calls the entire `sdk/scripts/check`, repeating Ruff formatting/linting, fixed-target Pyright, generated-reference checks, API compatibility checks, unit tests and coverage output. Python 3.14 runs the same checks through primary verification. The matrix is meaningful compatibility coverage; the invariant contributor checks need only one owner. [SDK jobs][quality-workflow], [SDK check script][sdk-check], [Python 3.10 type target][sdk-project]

   Split invariant checks from runtime tests while preserving the existing complete local `check` and `verify` commands. Run invariant checks and full coverage once on the primary lane. Run runtime tests and both installed distribution paths on every supported interpreter, with JUnit output per lane. Preserve interpreter-sensitive typing-consumer tests and explicitly configure any type checks retained per interpreter. Do not reduce the five-version matrix or drop source-distribution installation. [SDK release criteria][issue118], [installed-consumer criteria][issue102]

   This is a modest optimization. In the sampled Python 3.10 job, 219 source tests took 174.56 seconds and 20 installed-artifact tests took 75.21 seconds; the entire verification step took 265 seconds. Most of that lane is real test work. The primary artifact build and the release-mode rebuild are also intentional: #118 requires publication of outputs verified in the release run, not recovered PR distributions. [SDK job log][sdk-job], [release criteria][issue118]

7. Tune test setup and smaller caches after measuring them.

   Real-service integration is the current full-gate critical path. In the September 3 run, `DatasetPublicationApiIT` took 220.7 seconds, `GraalPySkyPilotClientIT` 131.6 seconds and `DatabaseStartupIT` 121.5 seconds. These exercise different behavior and are profiling targets, not deletion candidates. Record fixture startup, readiness, scenario and teardown time before choosing parallelism or sharing. Preserve fresh state for restart, migration, outage and recovery scenarios. [Integration job log][integration-job]

   A smaller candidate is sharing a read-only fixture for `LivenessIT`, `ReadinessIT` and `ManagementExposureIT`, which each create a database and backend process for one test. Their measured test bodies totaled only about 3.3 seconds in that sample, so this is lower priority than the three slow classes. The PostgreSQL container is already shared; `DatabaseStartupIT` pauses it, so blanket parallel test execution would introduce interference. `StructuredLoggingIT` and eight database-backed methods in `DeploymentConfigurationIT` currently lack `real-service` tags and run in repeated non-real-service verification. Move those ten methods to the correct owner only after fixing integration selection. Retain the JAR-only configuration check separately. [Acceptance tests][acceptance-tests], [shared PostgreSQL fixture][postgres-fixture]

   The five uv cache entries total only about 0.6 MiB. The sampled Python 3.10 job reports a successful 132,898-byte restore, then downloads Ruff, Pyright and a 182.9 MiB Torch wheel. The setup action defaults to pruning. A full wheel cache could reduce downloads, but the observed Torch download took about three seconds; blindly disabling pruning across five interpreters may cost more cache transfer and storage than it saves. Benchmark the current pruned cache against a bounded wheel cache with an explicit key version. Decouple the project-action lock from SDK-only keys if measurements justify separate cache ownership. [Python setup][python-setup], [pinned setup-uv defaults][setup-uv], [SDK job log][sdk-job], [uv caching guidance][uv-cache]

   pnpm has exact lockfile keys with no fallback. Add a fallback scoped to OS/architecture, Node and pnpm versions for downloaded packages, followed by the existing frozen install. The Playwright key also contains the whole frontend lockfile, Node and pnpm versions; unrelated dependency updates currently produce another roughly 269 MiB browser copy. If browser caching remains beneficial, use runner image/architecture and resolved Playwright browser identity. Keep OS dependencies installed separately and retain their timeout. Playwright itself recommends measuring this because browser restore can cost about as much as downloading. Updating this identity requires reconciling the existing lockfile/browser key wording in #137. [Frontend setup][frontend-setup], [Playwright guidance][playwright-cache], [frontend CI criteria][issue137]

   Maven downloads and the JDK archive already have working caches. Reduce duplicate setup consumers before replacing them. Environment Profiles and the Docker-Maven image builders have no configured remote layer-cache import/export. Profile checks took 4m56s–7m52s and build CUDA then ROCm sequentially. Consider splitting the two profiles if latency warrants the extra jobs. Pilot remote layer caching only after freeing scanner cache capacity: large accelerator bases could otherwise worsen eviction. BuildKit export requires an appropriate builder and an explicit scope per image; it is not an option that can simply be added to the current Maven build command. Check revision-label invalidation and preserve source identity in the final image. [Profile check][profile-check], [Docker cache backend][docker-cache], [image POM][backend-deployment-pom]

There are tests worth simplifying, but no evidence supports mass removal. Keep unit tests of runtime state transitions, source-versus-installed SDK checks, both distribution paths, mocked frontend tests plus packaged Spring/browser acceptance, Java plus Python S3 clients, image-specific lifecycle checks, and release collision/attestation/rollback tests. These exercise different failure boundaries even where assertions overlap. The GraalPy tar handoff is also useful: it preserves filesystem metadata, uses no extra upload compression, and isolates preparation from consumers. Optimize that transfer only if its measured cost becomes material. [Application acceptance criteria][issue117], [SDK criteria][issue102], [paired release criteria][issue201], [quality workflow][quality-workflow]

Literal workflow and Dockerfile assertions in `tests/quality/test_ci_contract.py`, `tests/deployment/test_release_workflow.py` and `test_skypilot_server_image.py` deserve a targeted cleanup. Replace implementation spelling assertions with observable planning, rendered configuration, command failure or actual image behavior where that behavior is already tested. Retain contracts for security permissions, exact identity, failed-job diagnostics and publication ordering. The cheap deployment suite took about 25 seconds locally; the dormant quality suite took about three seconds. Their cost does not explain the hour-long main build. [Quality tests][quality-tests], [deployment tests][deployment-tests]

Artifact retention is a lower-priority storage question. The API returned 2,859 artifact records, but only the latest 100 were sampled for size, so there is no defensible repository-wide storage total in this audit. In that sample, application, Java, integration and image report artifacts were roughly 1.7–2.8 MiB each; SDK distributions were about 0.2 MiB. The roughly 95 MiB GraalPy handoff already expires after one day. Eliminate duplicate report production alongside duplicate verification first. Preserve the current 90-day diagnostics policy, 7/30-day ordinary artifact policy and indefinite published evidence unless the relevant issue and policy are explicitly revised. Cache storage and workflow artifact storage are separate mechanisms. [Artifact API][artifact-api], [retention policy][quality-policy]

Security Governance failed on all five daily runs visible in the 60-run sample. The latest failed immediately at the missing `SECURITY_AUDIT_TOKEN` check. This is noise from a broken credential, not an unnecessary test. Restore the credential under [#226][issue226] and retain the audit. No credential setup was attempted. [Latest governance run][governance-run]

Local verification during this investigation passed all 37 quality-tool unittests in 3.257 seconds and all 42 deployment unittests in 25.383 seconds. These were read-only test executions. Existing GitHub logs establish the full-suite timings above; no complete local or cold-cache qualification is claimed.

For implementation, I recommend approving selection/discovery repairs, complete GraalPy identity plus deployment reuse, and single-restore Trivy caching first. Then restructure duplicate verification and PR cancellation under #225. Record the accepted plan in the existing issues before implementation where it changes recorded requirements, particularly cache authority, browser key identity or the main Skaffold contract. The policy currently bans generated build outputs as cache authority while CI already caches an assembled GraalPy environment; reconcile that explicitly with #224 and same-run artifact reuse. No accepted ADR requires duplicate jobs or a specific cache implementation. SDK version identity, packaged frontend delivery and immutable paired deployment provenance remain constraints.

Compare before/after runs using the same selected suites. Record cold and warm results, total job minutes, gate elapsed time, per-test collection, native cache identity/hit/miss, database restores and bytes, and failure-report availability. Acceptance should include a docs-only change, backend change, shared-contract change, native-input change and superseded PR push. Do not claim the optimizations succeeded merely because fewer checks ran.

[workflows]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/.github/workflows
[quality-workflow]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/.github/workflows/quality.yml
[deployment-workflow]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/.github/workflows/deployment-release.yml#L22
[planner]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/scripts/quality#L67
[sdk-check]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/sdk/scripts/check
[sdk-project]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/sdk/pyproject.toml#L95
[quality-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/tests/quality
[deployment-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/tests/deployment
[sdk-system-fixtures]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/sdk/tests/system/conftest.py
[backend-builder]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/deployment/scripts/build-backend-image#L33
[graalpy-pom]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/graalpy-environment/pom.xml#L34
[java-setup]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/.github/actions/setup-java/action.yml#L60
[root-pom]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/pom.xml#L37
[backend-deployment-pom]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/backend-deployment/pom.xml
[frontend-pom]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/frontend/pom.xml#L133
[quality-policy]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/docs/quality-gate.md
[acceptance-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/backend/src/test/java/de/zorro909/skywright/backend/acceptance
[python-setup]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/.github/actions/setup-python-toolchain/action.yml#L30
[frontend-setup]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/.github/actions/setup-frontend/action.yml#L35
[profile-check]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/environment-profiles/scripts/check
[backend-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/backend/src/test
[image-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/backend-deployment/src/test
[sdk-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/sdk/tests
[frontend-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/frontend
[support-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/tests
[action-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/.github/actions/publish-training-project/tests
[profile-tests]: https://github.com/Zorro909/skywright/tree/ab1edb1e4fc3ae03764c2d712862c20ee8875236/environment-profiles/tests
[mds-tests]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/sdk/tests/test_mds_decoding.py#L28
[postgres-fixture]: https://github.com/Zorro909/skywright/blob/ab1edb1e4fc3ae03764c2d712862c20ee8875236/backend/src/test/java/de/zorro909/skywright/backend/acceptance/PostgreSqlFixture.java#L16
[run-sep1]: https://github.com/Zorro909/skywright/actions/runs/33528325509
[run-sep3]: https://github.com/Zorro909/skywright/actions/runs/33804238989
[run-sep4]: https://github.com/Zorro909/skywright/actions/runs/33841785156
[deployment-sep1]: https://github.com/Zorro909/skywright/actions/runs/33528325364
[deployment-sep3]: https://github.com/Zorro909/skywright/actions/runs/33804238975
[deployment-sep4]: https://github.com/Zorro909/skywright/actions/runs/33924469288
[docs-run]: https://github.com/Zorro909/skywright/actions/runs/33924469542
[graalpy-job]: https://github.com/Zorro909/skywright/actions/runs/33804238989/job/100810795104
[image-job]: https://github.com/Zorro909/skywright/actions/runs/33804238989/job/100810915080
[sdk-job]: https://github.com/Zorro909/skywright/actions/runs/33804238989/job/100812304949
[integration-job]: https://github.com/Zorro909/skywright/actions/runs/33804238989/job/100810914997
[governance-run]: https://github.com/Zorro909/skywright/actions/runs/33956945083
[cache-api]: https://api.github.com/repos/Zorro909/skywright/actions/caches?per_page=100
[cache-limit]: https://api.github.com/repos/Zorro909/skywright/actions/cache/storage-limit
[artifact-api]: https://api.github.com/repos/Zorro909/skywright/actions/artifacts?per_page=100
[issue219]: https://github.com/Zorro909/skywright/issues/219
[issue224]: https://github.com/Zorro909/skywright/issues/224
[issue225]: https://github.com/Zorro909/skywright/issues/225
[issue226]: https://github.com/Zorro909/skywright/issues/226
[issue116]: https://github.com/Zorro909/skywright/issues/116
[issue117]: https://github.com/Zorro909/skywright/issues/117
[issue118]: https://github.com/Zorro909/skywright/issues/118
[issue102]: https://github.com/Zorro909/skywright/issues/102
[issue137]: https://github.com/Zorro909/skywright/issues/137
[issue201]: https://github.com/Zorro909/skywright/issues/201
[github-cache]: https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching
[trivy-action]: https://github.com/aquasecurity/trivy-action/blob/ed142fd0673e97e23eac54620cfb913e5ce36c25/action.yaml#L125
[trivy-convert]: https://trivy.dev/docs/latest/configuration/reporting/#converting
[setup-uv]: https://github.com/astral-sh/setup-uv/blob/37802adc94f370d6bfd71619e3f0bf239e1f3b78/action.yml#L59
[uv-cache]: https://docs.astral.sh/uv/guides/integration/github/#caching
[playwright-cache]: https://playwright.dev/docs/ci#caching-browsers
[docker-cache]: https://docs.docker.com/build/cache/backends/gha/

Implementation update, 2026-09-05

The approved fixes are being implemented on `t3code/audit-ci-tests-caching`. Verification cancellation
uses independent job concurrency, while the GraalPy preparation job finishes and saves native wheels.
This keeps dependent work queued without occupying an idle waiting runner. Application and image
jobs consume a checksum-verified backend handoff from the same run and tested revision. Main
Skaffold qualification uses the shared preparation action and skips documentation-only changes.

Local validation passed 41 quality-tool tests, 42 deployment tests and 248 SDK unit cases, including
29 newly discovered MDS cases. Backend reactor packaging and unit tests passed with native
preparation bypassed for that compile-only check; no local native or image qualification is claimed.
The original `mvn ... test` attempt failed because the reactor's API unpacking requires packaging;
`mvn ... package` passed. The workflow definitions also pass actionlint.

Historic full-quality comparisons use the same event and selected suites where possible:

| Baseline run | Native state | Workflow elapsed, including scheduling | Summed job time |
| --- | --- | ---: | ---: |
| PR #192, [33841785156][run-sep4] | Warm | 13m48s | 63m39s |
| main, [33804238989][run-sep3] | Warm | 13m57s | 70m45s |
| main, [33515002899](https://github.com/Zorro909/skywright/actions/runs/33515002899) | Native environment and wheel cache miss | 101m24s | 150m56s |
| PR #192, [33515546605](https://github.com/Zorro909/skywright/actions/runs/33515546605) | Native environment and wheel cache miss | 112m59s | 161m41s |

These elapsed figures include scheduling, unlike the first table's first-job-to-last-job interval.
Cold-native runs still had some warm tool downloads. The new cache schema initially misses the
assembled environment and may reuse compatible progressive wheels; compare it separately from an
exact warm restore. Post-change measurements will be added after the PR jobs complete.
