# CI cache analysis, 2026-09-09

The dominant cost is GraalPy dependency preparation. The completed main
[deployment run](https://github.com/Zorro909/skywright/actions/runs/34375939500)
took 2h 36m 59s. Preparation occupied 2h 24m 13s, including a 1h 45m pandas
primer and a 34m 36s locked environment build. The deployment build and tests
after preparation took 11m 34s. These are historical measurements, before this
change.

The corresponding main
[quality run](https://github.com/Zorro909/skywright/actions/runs/34375939621)
finished successfully in 3h 5m 2s, with 4h 13m 26s of summed non-skipped job time.
Its native preparation job took 2h 25m 1s. Quality selected Java, integration,
frontend, application, production images, profiles, SDK and security checks.
The two workflows independently built the same native environment on their
simultaneous misses.

The earlier [13m 17s main run](https://github.com/Zorro909/skywright/actions/runs/34311963482)
selected SDK, profiles and security only. It skipped GraalPy and is not evidence
of faster native preparation. For a measured exact environment hit with fresh
imports, [#224](https://github.com/Zorro909/skywright/issues/224) records 4m 24s
under the previous runtime. That issue also records Rust 1.98.1 versus 1.98.0
as the sole identity difference between two cold retry attempts.

## Reproduced causes and changes

| Cache or workflow | Finding | Change |
| --- | --- | --- |
| GraalPy wheels | Adding a comment to the preparation action changes the wheel key, because it uses the full environment identity. | A separate wheel identity excludes qualification and orchestration changes while retaining exact native, runtime, lock and recipe inputs. |
| Native Rust | Hosted runner defaults changed between retries. | Select Rust 1.98.0 explicitly before computing identity or compiling wheels. |
| Partial wheels | Keys include run ID and attempt; the first checkpoint happens after the full environment build. | Checkpoint pandas immediately, name partial caches by content, and save a stable completed entry after successful qualification. |
| Wheel retention | Exact environment hits never access wheel caches. | Restore wheels on environment hits too, so normal usage keeps these expensive dependencies active. |
| Maven | The pinned setup-java action has no fallback; all Java jobs compete to save the same immutable key. Recent native-producer caches contain about 80 MB versus about 262 MB in a fuller cache. | Separate job keys, compatible download fallback, and a separate wrapper cache. Exclude locally installed Skywright artifacts. |
| Frontend | Chromium depends on Node, pnpm, and every lockfile change; pnpm has no download fallback. | Key Chromium by OS image, architecture and Playwright version. Add pnpm fallback within the exact toolchain. |
| Trivy | Each scan invokes setup and restores the complete cache again. Four cache entries occupied about 4.0 GB during the audit. | Set up once per job; retain only databases; write daily database entries from main only. |
| Backend verification | Java, application and image jobs each verify the backend reactor. | The Java producer verifies once and publishes a checksummed handoff bound to the revision, run and producer attempt. Browser and image checks consume it. |
| SDK static checks | Every compatibility lane repeats formatting, typing, generated contracts, API checks and coverage. | Run these once on Python 3.14; retain unit and installed-distribution tests on all five interpreters. |
| Main deployment | Every main push installs prerequisites and builds images, including documentation changes. | Apply the existing quality planner before setup and build steps. |

The cache API initially reported 64 entries using 11,169,078,533 bytes. This is
an inventory snapshot, not proof that a particular entry was evicted. Reducing
duplicate large entries and per-run keys addresses cache pressure without
changing the repository's storage settings or deleting existing caches.

The implementation follows GitHub's documented
[immutable cache and fallback rules](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching),
Playwright's [version-based browser cache guidance](https://playwright.dev/docs/ci#caching-browsers),
and rustup's [explicit toolchain override](https://rust-lang.github.io/rustup/overrides.html).
Trivy [refreshes its databases](https://trivy.dev/docs/latest/configuration/db/)
before scans; database updates are not disabled to improve timings.

## Validation and measurement limits

The regression suite exercises the real identity function and CLI. A workflow
or probe edit must change the environment identity while preserving the wheel
identity. Runtime, SDK, lock, build recipe, native compiler, flags and platform
changes must still invalidate wheels. Existing payload-tampering, installed
version and same-run provenance rejection tests remain in place.

The preparation command also runs against real wheel archives to prove that
both a prepared wheelhouse and an interrupted pip-cache checkpoint bypass
Maven compilation. Maven inheritance and command-line override checks run
against the real Maven executable. Workflow and composite-action expressions
are checked with actionlint, alongside the quality and deployment suites.

Hosted validation and measured comparisons are recorded in
[PR #279](https://github.com/Zorro909/skywright/pull/279). The new wheel namespace
requires an initial fill; old packaged environments also need qualification
under the changed preparation inputs. Fully cold builds and deliberate native
runtime, lock, compiler or platform changes can still be expensive. Distinguish
a cold run, an exact environment hit, and an environment miss with a wheel hit
when comparing elapsed time. Compare selected checks, cache state, elapsed time
and summed job time separately.

Cross-workflow cold-build deduplication and independent cancellation of superseded
PR verification remain part of
[#225](https://github.com/Zorro909/skywright/issues/225). The uv caches are already small and pruned;
retaining large downloaded ML wheels needs timing and storage measurements
before changing that policy. The production native ABI qualification added by
[#250](https://github.com/Zorro909/skywright/issues/250) remains required in the
image consumer, including its real SDK invocation.


## Backend artifact reuse

The Java job verifies and installs the backend reactor once. Browser acceptance
uses its executable and test fixture directly, and image qualification runs the
two deployment modules without `-am`. The handoff contains only the expected
reactor POMs and JARs. Consumers validate every checksum and the exact file
inventory before copying any files, and reject different revisions, runs or
producer attempts. Consumer-only retries explicitly refer to the retained
successful producer attempt. Tests exercise these checks against real Git
revisions and files, including altered, missing, extra and linked artifacts.

Real-service integration remains independent because it runs a different tagged
suite. Frontend verification retains its production build, which checks size
budgets absent from the Maven output configuration. Image SARIF/SBOM reporting
and policy enforcement remain separate required checks, sharing scanner setup
and databases within their job. Release workflows retain qualification of the
exact tag being published.
