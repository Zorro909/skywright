# Vast.ai admission and qualification

Work for [#283](https://github.com/Zorro909/skywright/issues/283), checked on
2026-09-12. Both Vast.ai purchase modes remain unavailable. No paid launch or live GPU
qualification has taken place. The issue must remain open.

## Implemented behavior

The Managed Run form lists local AMD, Vast.ai on-demand and Vast.ai interruptible separately. Readiness
belongs to the selected target. Vast lists separate unknown Cost Quote, credential
projection, storage, registry, price, budget and qualification gates. These are
explicit unavailable assessments; the form does not yet validate live cloud
evidence. Its image check reads the exact assessed version and requires CUDA
for Vast or ROCm for local AMD. They cannot enable Create Run or disable an otherwise ready local target. The form identifies the exact
installed Training Project, version manifest and Dataset Definition.

Two internal target adapters share Run Definition resolution, Dataset leases,
role credentials, storage selection, durable acceptance, idempotency and first
dispatch. The existing advanced endpoint uses the same admission implementation.
On-demand fails with `VAST_LAUNCH_PRICE_UNPROVEN`; interruptible fails with
`VAST_BUDGET_UNVERIFIED`. Both checks precede acceptance and the orchestrator
availability probe. Other providers remain ineligible. No configuration switch
bypasses these guards.

The CUDA projection checks the pinned image, project contracts and Dataset using
the same runtime-material validation as AMD. The finite resource DTO carries
Vast region, instance type, allocated disk and the catalogue hourly ceiling.
The selected mode determines `use_spot`. Interruptible requires a finite numeric
`maxBidHourlyCost`, strictly between zero and USD 0.15. The official Task config
receives this number as `vast.create_instance_kwargs.price`, together with
`cancel_unavail=true`; on-demand cannot carry a bid. The bridge combines provider
and region into SkyPilot’s `infra` field. The SDK selects CUDA for either Vast
mode and storage resolution uses that mode’s existing Target Storage defaults.
A compute bid does not establish total affordability or actual-offer identity.

Cloud GHCR authentication travels through SkyPilot's secret channel. The Run
Definition, task DTO and durable records contain no registry values. The original
runtime-pull binding revision is retained for pre-dispatch restoration. The cloud
delivery shell removes all three Docker credential variables before its first
Python child, preserving the separate Dataset and Run Store credentials. Local
Kubernetes keeps its existing image-pull-secret delivery. Provider keys use the
new Vault `VAST` binding kind with the `skypilot-api-server` role and an `apiKey`
secret field. The deployment package can project an explicitly selected enrollment
into only the API-server container and append non-secret Pod projection,
validation and release receipts. This path has not yet been activated on the
existing instance. See [installation configuration](../../deployment/LOCAL_INSTALLATION.md).

## Launch-price evidence

The offline [adapter diagnostic](../research/issue-281-vast-preflight.py) was rerun
against unmodified SkyPilot 0.13.0. Its launch helper SHA-256 remains
`d2b8993b761e9805bcb14e566288455d0a8621d49fdfb66c9baf02e173f9ac17`.
With synthetic offers priced at USD 0.40/hour and USD 0.06/hour, an on-demand
request naming the cheaper offer and `cancel_unavail=true` still selects the
expensive first offer. No bid is submitted. Socket connections, DNS, subprocesses
and shell execution are prohibited inside this diagnostic.

The pinned [SkyPilot Vast helper](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/utils.py)
searches again and overwrites the supplied offer ID. Its
[cloud implementation](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/clouds/vast.py)
uses `max_hourly_cost` for catalogue filtering. That does not bound the later
creation request. The provider's [creation API](https://docs.vast.ai/api-reference/instances/create-instance)
documents `price` as an interruptible bid, not an on-demand ceiling.
[Permission constraints](https://docs.vast.ai/api-reference/permissions) can restrict
parameters, but the exact-offer constraint has not been proved here. The
[follow-up investigation](../research/issue-283-vast-launch-price-guard.md) verifies
the explicit interruptible bid and describes fresh fee assessment, conservative
reserves and independent cleanup. These do not require an atomic cap on every
storage or traffic charge. Do not rent until the actual resource and complete
budget assessment are established.

## Credential setup and trust

The owner completed the approved one-off setup wizard on 2026-09-12. The wizard
is session-local under `.scratch/issue283/`. It accepted a temporary console key
through hidden input, requested a scoped key with `misc`, `user_read`,
`instance_read` and `instance_write`, and stored the result directly in the
existing private instance’s Vault at `skywright/provider/vast/on-demand` using
compare-and-set version zero. It verified the stored value by reading it back. No billing, key administration,
machine or team permission is requested. `misc` is a provider category with
broader operations than offer search, not a claimed per-endpoint restriction.
A later read of effective rights showed provider-added baseline operations,
including SSH-key access and team creation. Deployment validation now records
and compares those effective rights rather than treating requested categories
as the complete resulting scope.

The secret-free enrollment record reports:

| Field | Observed value |
| --- | --- |
| Observed at | `2026-09-12T21:06:48.201200+00:00` |
| Vault path and revision | `skywright/provider/vast/on-demand`, revision `1` |
| Key name / provider key ID | `skywright-issue283-vast-on-demand` / `27864309` |
| Credential fingerprint | `sha256:fc77d9912abf9f05acbc1410b0f8355e440119036cd127aac3cffa7f6a4fcdcb` |
| Intended kind / resource / consumer | `VAST` / `vast` / `skypilot-api-server` |
| Intended access profile | `provision-and-cleanup` |
| Vault readback / scoped account read | verified / verified |
| Observed credit | USD `2.3402639111259873` |
| Runtime projection / paid launch | not created / none |

The owner attested that automatic billing was disabled at `21:05:32Z` and the
temporary bootstrap key was revoked at `21:07:07Z`. Those are operator
attestations, not independent API verification of billing or revocation.
A read-only probe at `21:14:25Z` retrieved revision 1 within the existing instance,
matched its fingerprint and successfully read the account and searched offers
using the scoped identity. The key was not returned to the agent or written to
another file. Credit was unchanged.

The [offer search API](https://docs.vast.ai/api-reference/search/search-offers)
returned five candidate offers using `type=ondemand`, verified and rentable,
not rented, one GPU, `dph_total < 0.15`, at least 20 GB available disk, sorted by
`dph_total`. For example, offer `36328625` reported RTX 3060 / 12 GiB in China,
`dph_base=0.04` and `dph_total=0.042222222222222223` USD/hour. This was a candidate
search: the query did not allocate disk or verify CUDA, image, network, project,
or Dataset compatibility. The returned storage and traffic rates were captured
in the session-local `provider-readiness.json`; no total affordability or actual
rental price proof is inferred from those values.

That enrollment and read-access evidence is not provisioning qualification. The
deployment now defines the binding and append-only projection observations,
checks the fingerprint, provider key ID and complete effective permissions, and
omits the provider binding when validation fails. It records projection before
external validation and preserves local service access on provider failure.
The actual API-server projection still requires live verification. Existing Dataset, Run Store, backend and
GHCR role credentials stay separate.

As recorded in [ADR 0025](../adr/0025-centralize-managed-credentials-in-vault.md),
the official adapter both mounts `~/.config/vastai/vast_api_key` and writes
`~/.vast_api_key` through its startup command. Project code with root access and
the rental host must be trusted with this provider identity. Those copies must be
accounted for during cleanup and revocation. No real remote copies have been
created by this work.

A no-spend policy experiment at `2026-09-12T21:59:42Z` created a temporary
read-only key and requested nonexistent ask `0`. Vast returned HTTP 401, so the
helper could not identify a creation permission or prove any constraints. The
helper verified diagnostic-key deletion; the owner confirmed temporary-key
cleanup at `22:00:15Z`. The enrolled Vault key was unchanged. A subsequent
nonexistent-ask request using the enrolled key reached lookup and returned HTTP
404 with `error=ask_not_found`. Neither probe attempted a real rental.

The installed demonstration manifest currently contains only a ROCm image.
A CUDA publication is required before its Vast image readiness can pass.

## Remaining qualification

Before paid work, verify current credit and billing settings, fresh eligible
resources and a launch-time guard for the resource actually rented. Include
allocated disk, image and Dataset downloads, Run Store uploads, controller costs,
a bounded runtime and deletion deadline in the total affordability check.
[Vast billing](https://docs.vast.ai/guides/reference/billing) charges storage while
instances are stopped and may charge a saved card for negative balances. The
reported account balance alone is not a spending limit.

Private GHCR digest pull, NVIDIA training, portable verified Dataset reads, Run
Store writes, committed progress, output inspection, terminal evidence,
cancellation, task/controller logs, usage and price capture, and deletion of all
chargeable resources still require a live managed Run. Existing local storage
addresses cannot be assumed reachable from a cloud rental. No source inspection
or synthetic test satisfies these gates.

## Reproduction

From the repository root:

```sh
uv run --no-project --python 3.12 --with skypilot==0.13.0 docs/research/issue-281-vast-preflight.py
mvn -DskipFrontendTests=true -DskipFrontendInstall=true -Dtest=LocalRuntimeProjectionTest,LocalCredentialProjectionsTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ManagedRunFormIT -Dfailsafe.failIfNoSpecifiedTests=false -pl backend -am verify
pnpm --dir frontend run verify
```

The focused backend run passed nine unit tests and four HTTP system tests.
Frontend verification passed formatting, lint, typechecking, unit/browser tests
and the production build. SDK verification passed formatting, lint, strict
typechecking, generated-contract checks and 469 unit tests. The HTTP test verifies that blocked Vast submission
creates no Run and makes no launch call. The GUI test selects unavailable Vast
while local is ready, then switches back to local. The full backend suite excluding `real-service` also passed (287 unit tests and
43 integration tests). The local assembly and local acceptance real-service
checks passed. The final focused backend verification passed nine unit tests,
four HTTP tests and the real SkyPilot API-server integration test, including
Vast resource construction with pinned SkyPilot 0.13.0 and transient registry
secrets. The delivery test executes the generated shell with a child-process
probe: registry variables are absent and Dataset access remains present.

The installed SDK system test passed both the local and cloud CUDA definitions
through the existing CLI, real S3-compatible storage and a private CPU test seam.
It verifies Dataset reads, committed outputs and terminal/recovery behavior; it
does not qualify NVIDIA hardware or a cloud rental.

```sh
mvn -DskipFrontendTests=true -DskipFrontendInstall=true -Dtest=LocalRuntimeProjectionTest,LocalCredentialProjectionsTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GraalPySkyPilotClientIT,ManagedRunFormIT -Dfailsafe.failIfNoSpecifiedTests=false -pl backend -am verify
sdk/scripts/check
uv run --project sdk --locked --extra dataset --group ml-test pytest sdk/tests/integration/dataset/test_managed_runtime_system.py -q
```

Standards review found no documented-standard violations. Its two design
concerns were addressed by using a shared registry-delivery predicate and an
explicit local adapter accessor. Specification review found and prompted the
registry environment fix, then confirmed it on follow-up. It still identifies
incomplete runtime provider projection, live joined readiness and live
qualification; these remain explicit gaps rather than completed criteria.


## Interruptible extension verification

The extended Run Definition projection tests exercise both modes, preserve the
accepted definition, check the numeric bid and execute registry-secret isolation.
The HTTP form exposes three choices and rejects both unqualified Vast modes
without a Run or launch, including when SkyPilot is offline. The GUI test selects
each blocked Vast mode and returns to a ready local target. The native SkyPilot
0.13.0 test verifies the bid survives Task YAML serialization.

These checks passed: six projection tests, four HTTP tests and the native
SkyPilot integration test; full frontend verification; 470 SDK unit tests,
format/lint/type/contract checks; and the installed SDK test through real S3 for
local, on-demand CUDA and interruptible CUDA definitions using its private CPU
seam. Deployment tests passed 24 cases with two existing skips. The provider
server image built and passed its image integration check with the official
Vast SDK installed. Live NVIDIA, provider projection and rental cleanup remain
unverified.
