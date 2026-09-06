# Quality selection and SDK discovery

Issue #219 was reproduced against `4fe81cc99f80ac3b9f4278d1803a2c80b94bdded`.
The GraalPy client and Python bridge already selected integration by that revision.
The Dataset-copy and Target Storage S3 adapters still omitted it. All three SDK
schema families omitted their Java consumers. Default pytest collection selected
301 cases, including seven Run Store service cases, but omitted MDS decoding.
The contributor check selected an explicit list of 294 unit cases.

Backend changes now always select integration. Every file under the packaged
configuration, metric and Run Definition resource directories selects Java, SDK,
integration, application, image, Environment Profile and security checks. Table
regressions cover the four original adapters, new backend paths and future resource
filenames. A Maven resource-table check verifies every packaged SDK family has
consumer coverage. The Plan CI job executes the quality-tool regression suite.

SDK discovery now defaults to unit tests throughout `tests/`. Service modules use
`integration`, with `dataset` for the optional Dataset stack. The installed suite
uses the `tests/system/` directory and its `system` marker. Artifact requirements
are checked when fixtures execute, so collecting the unit suite needs no wheel.
A regression creates new modules and verifies discovery and suite separation.

## Local verification

- 40 quality planner/gate tests. The added regressions reproduced 17 failing cases
  before the selection fix.
- 324 SDK unit tests, including 29 MDS decoding cases and a discovery regression.
- Seven Run Store and seven Dataset real-service tests, selected by markers.
- 20 installed-artifact cases against a direct wheel and a wheel rebuilt from the
  source distribution.
- The backend JAR contains byte-identical packaged resources: seven configuration,
  five metric and five Run Definition files. `backend/pom.xml` copies these families
  without filtering and excludes bytecode caches.

Commands are `python -m unittest discover -s tests/quality`, `sdk/scripts/check`,
`sdk/scripts/integration`, and the build/check-distributions plus installed-suite
commands in `sdk/README.md`. Backend real-service checks use
`mvn -pl backend -am -DskipFrontendInstall=true -DskipFrontendTests=true
-Dgroups=real-service verify`.

## CI comparison

The recorded historic runs both selected application, image, integration, Java,
Environment Profile, SDK and security checks. They restored exact packaged GraalPy
and Maven caches. The implementing PR also selects deployment and frontend because
it changes the shared quality tooling and workflow. These are different workloads;
elapsed differences are not evidence of a speed improvement or regression.

| Run | Elapsed | Summed job time |
| --- | ---: | ---: |
| [Historic PR #254](https://github.com/Zorro909/skywright/actions/runs/34008278541) | 18m 22s | 67m 38s |
| [Historic main after #254](https://github.com/Zorro909/skywright/actions/runs/34009095746) | 18m 45s | 73m 53s |

Elapsed time runs from the workflow start to its last job completion. Summed job
time adds each executed job's duration, including setup and report upload; it is
not billed time and does not count jobs skipped by the plan. Historic selections,
per-job timings and observed cache hit/miss keys are in `quality-selection.json`.
The implementing PR's completed-run comparison is recorded with the acceptance
evidence on [issue #219](https://github.com/Zorro909/skywright/issues/219).
