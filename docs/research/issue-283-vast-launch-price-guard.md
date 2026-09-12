# Vast launch price guard for issue #283

Research date: 2026-09-12. Scope includes the owner's conditional extension to
interruptible instances when on-demand cannot enforce the required price.
No provider credentials were read and no paid API was called for this research.

The supported interruptible bid field solves the per-machine active-rental price
problem. Total affordability needs fresh storage and traffic allowances and a
bounded runtime with independent cleanup. The owner did not require atomic
provider-enforced ceilings on every charge component. On-demand still has no
proved actual-rental cap through the unchanged pinned adapter.

## On-demand alternatives examined

| Mechanism | Finding |
| --- | --- |
| `resources.max_hourly_cost` | Filters SkyPilot's catalog candidates. The Vast provisioner searches again and selects its first result without a price filter. |
| Pass the approved offer ID | `launch()` overwrites `create_instance_kwargs.id` with the first result's ID. |
| `create_instance_kwargs.price` | Vast defines this as an interruptible bid, not an on-demand ceiling. |
| Restrict the provider key to an offer ID | Parameter constraints exist, but the documentation does not promise that an unaccepted offer ID has immutable prices. This cannot establish the price of the eventual rental. |
| Restrict the provider key by `dph_total` | Constraints apply to endpoint parameters. The create endpoint exposes no `dph_total` or maximum on-demand rate parameter. No documented server-side contract-price constraint was found. |
| `extra_filters` or a template | Template documentation calls these search filters and describes merging. It does not promise an atomic price check when accepting an offer. The examined released SDK rejects direct `extra_filters` on `create_instance`. |
| Set `cancel_unavail=true` | Prevents waiting for an unavailable instance. It does not constrain its price. |

The catalog and offer-selection findings come from the pinned
[Vast cloud adapter](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/clouds/vast.py)
and [provision helper](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/utils.py).
The existing [offline diagnostic](issue-281-vast-preflight.py) reproduces an
expensive first offer replacing the caller's cheaper ID.

Vast documents request-parameter constraints with `eq`, `lte`, and `gte`. It does
not describe constraints evaluated against the resulting rental's price or
usage. The enrolled categories grant instance management without billing or key
administration. They do not themselves impose a spend limit.
[Permissions reference](https://docs.vast.ai/api-reference/permissions)

An on-demand rental accepts an ask ID, with disk capacity and optional
interruptible `price`. Its schema has no maximum on-demand rate. The relevant
`extra_filters` wording only establishes merge precedence.
[Create instance](https://docs.vast.ai/api-reference/instances/create-instance)

Hosts can change offers. Vast promises that existing rental contracts keep their
original pricing; it does not extend that promise to IDs returned by an earlier
search. Treating an unaccepted ask ID as a frozen price would therefore be an
assumption.
[Hosting overview](https://docs.vast.ai/host/hosting-overview)

Templates contain default machine search filters. Neither that description nor
the documented merge rules establishes rejection of an over-limit price during
contract creation.
[Template API guide](https://docs.vast.ai/api-reference/creating-and-using-templates-with-api)

## Supported interruptible implementation path

Use a finite internal numeric bid field and project it through the public task
YAML configuration. Do not expose an arbitrary provider-kwargs map in the
Managed Run request.

```yaml
resources:
  infra: vast/US
  instance_type: 1x-RTX_3060-16384
  use_spot: true
  cpus: '4'
  memory: '16'
  disk_size: 20
  max_hourly_cost: 0.149
config:
  vast:
    create_instance_kwargs:
      price: 0.04
      cancel_unavail: true
```

These values illustrate projection only. They are not a current offer, an
approved workload size, or a budget proof. The bid must be finite, positive, and
strictly less than USD 0.15 per machine-hour. Reserve room for storage if the
hourly requirement includes storage.

`sky.Task.from_yaml_config()` carries task `config` into the resources' cluster
configuration. It survives task serialization. The public `sky.jobs.launch()`
accepts the task; it has no `config_overrides` argument.
[Task implementation](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/task.py),
[managed jobs SDK](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/client/sdk.py)

The pinned allowlist includes `vast.create_instance_kwargs`. The adapter reads
that override and the generated provider configuration carries it into launch.
[Task override allowlist](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/skylet/constants.py),
[Vast provider template](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/templates/vast-ray.yml.j2)

SkyPilot preserves an explicit numeric `price` even when it selects another
offer. Without it, `use_spot=true` copies the selected offer's `min_bid`, which
can exceed the owner's ceiling. `bid_price` is an alias normalized to `price`.
The official [configuration reference](https://docs.skypilot.ai/en/latest/reference/config.html#vast-create-instance-kwargs)
also documents this override. Setting a bid changes the purchase mode, so the
issue, ADRs, Run Definition, form, and readiness must identify it as
interruptible before enabling it.

A no-network diagnostic ran with `skypilot==0.13.0`, `vastai-sdk==1.5.5`, and its
`vastai==1.5.5` dependency. It exercised task parsing, serialization, deployment
variables, the unchanged provision helper, and the real SDK's HTTP-body builder.
Only catalog metadata and provider responses/transport were synthetic. Results:

```json
{
  "bid_survives_task_roundtrip": true,
  "synthetic_selected_offer": {"id": 101, "min_bid": 0.20, "dph_total": 0.40},
  "synthetic_http_request": {
    "path": "/asks/101/",
    "price": 0.04,
    "disk": 20,
    "cancel_unavail": true
  },
  "extra_filters": "TypeError: unsupported extra_filters keyword",
  "network_and_subprocesses": "prohibited"
}
```

This proves request propagation, not provider enforcement or a live rental.
The released SDK passes `price` to the create endpoint and rejects unknown
arguments at its API helper. `vastai-sdk` now delegates to the unified Vast
package. The exact SDK version must be pinned in deployment verification.
[Published package metadata](https://pypi.org/project/vastai-sdk/1.5.5/),
[official SDK create implementation](https://github.com/vast-ai/vast-cli/blob/1c6f8b61d3929a7ae423f89a3a0a53e4e9be02bc/vastai/api/instances.py)

## SDK compatibility and lifetime controls

The same offline diagnostic passed with `vastai-sdk==1.0.0` and `vastai==1.0.0`.
This is the earliest examined unified release with the `client.api_key` shape
that the pinned SkyPilot helper requires. The released `0.2.5`, `0.2.6`, and
`0.6.0` wheels use `self.api_key` without `self.client`. A successful dependency
resolution to a legacy SDK does not prove adapter compatibility.
[Legacy wheel metadata](https://pypi.org/project/vastai-sdk/0.2.5/),
[unified release metadata](https://pypi.org/project/vastai/1.0.0/)

The shared GraalPy lock's `psutil==5.9.8` conflicts with modern SDK requirements.
`vastai==1.0.0` requires `psutil>=6,<7`; `1.5.5` requires `psutil>=7,<8`. Both
retain the PDF-related `borb` dependency. Use a separately pinned CPython server
environment if the existing architecture permits it, or qualify a change to the
shared lock. Do not remove declared dependencies merely because launch does not
appear to use them.
[1.0.0 dependency metadata](https://pypi.org/pypi/vastai/1.0.0/json),
[1.5.5 dependency metadata](https://pypi.org/pypi/vastai/1.5.5/json)

Neither `duration` nor `end_date` is a supported create parameter in the examined
SDK or documented create schema. The `1.0.0` diagnostic confirmed both fail with
`TypeError` before HTTP. Host listing expiry is controlled by the host and does
not provide a caller-defined rental deadline. A runtime `timeout` command starts
after provisioning and does not delete the rental's storage.
[Host listing schema](https://docs.vast.ai/api-reference/machines/list-machine),
[SDK create implementation](https://github.com/vast-ai/vast-cli/blob/1c6f8b61d3929a7ae423f89a3a0a53e4e9be02bc/vastai/api/instances.py)

## Remaining budget and recovery obligations

Vast bills active compute, allocated storage, and bandwidth separately. Storage
continues while an instance is stopped; traffic is billed per byte. A fixed bid
therefore does not fix total hourly charges. A zero balance is also not a hard
budget boundary: storage can continue into a negative balance, and a saved card
may cover that balance. An autobilling-disabled attestation must not be recorded
as a provider-enforced spend ceiling.
[Billing reference](https://docs.vast.ai/guides/reference/billing)

Fresh offer evidence needs the exact requested disk allocation, storage rate,
input/output traffic rates, and bounded transfer amounts, including image pulls
and setup. Search supports cost fields, but that alone does not constrain the
later unfiltered SkyPilot search. An offer constraint can bind the actual rental to the assessed resource. Without
that constraint, the preflight must account for the other resources that the
unchanged search can select; assessing an unrelated cheap offer is insufficient.
[Offer search schema](https://docs.vast.ai/api-reference/search/search-offers)

The controller can run on the existing Kubernetes instance using the installed
SkyPilot configuration:

```yaml
jobs:
  controller:
    resources:
      infra: kubernetes/EXISTING_CONTEXT
```

This is not a task override. Generate it from the existing deployment authority.
Verify the actual controller handle too: the pinned implementation reuses an
existing controller's resources before considering a changed location. Without
an explicit controller location, it considers the job's clouds, which can add a
second paid rental.
[Controller resource selection](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/controller_utils.py)

Managed Jobs has two built-in recovery strategies, `FAILOVER` and
`EAGER_NEXT_REGION`. Both can relaunch after preemption; the default is
`EAGER_NEXT_REGION`, whose recovery loop retries indefinitely. The
`max_restarts_on_errors` field limits user-code error restarts, not preemption
replacements. No built-in total-rental count or preemption-retry ceiling was
found. Lowercase `none` becomes an absent strategy and ultimately selects the
default during Managed Jobs admission; uppercase `NONE` is not registered. A custom recovery plugin is an
extension point, but is additional implementation and qualification work. A
global runtime budget could cover sequential recovery without disabling it, if
cleanup prevents overlapping rentals and the budget includes repeated transfers.
[Recovery strategy implementation](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/recovery_strategy.py),
[Resources parsing](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/resources.py),
[Managed Jobs default filling](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/dag_utils.py)

The controller invokes recovery after observing a failed or stopped cluster.
Its pre-recovery hook is best effort and hook failure does not veto recovery.
An external observer that cancels after detecting preemption cannot prove that
it wins the race against the first replacement rental.
[Managed job controller](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/controller.py)

## What would remove the paid-launch blocker

For on-demand, obtain a provider-supported atomic ceiling over the accepted
contract, accessible through the pinned official SDK, or an official adapter
path that binds an immutable priced offer. None of the examined documentation
proves that path today.

For interruptible, retain the proved numeric bid projection and local controller
placement. Fresh assessed fees, conservative allowances and an independent
watchdog can support qualification without an atomic cap on every charge. The
remaining concrete work is to bind or otherwise assess the actual selected
resource, verify the watchdog and cleanup behavior, and reserve enough credit
for plausible recovery and teardown. Ordinary residual network failure risk does
not make every cloud launch impossible. Readiness should describe the evidence
actually missing, rather than claim that total provider spending must have a
mathematically absolute ceiling.


## Reassessment against the owner's actual budget requirement

The hard pricing condition is a configured bid below USD 0.15 for the actual
machine. For an hourly display that includes allocated storage, also require
`bid + assessed_disk_hourly < 0.15`, with enough headroom for the fresh fee quote.
The separate affordability condition can use a conservative estimate. Requiring
atomic storage and traffic caps would be stronger than the owner's wording.
This distinction is a technical interpretation of issue #283, not a new scope
decision.

A practical demonstration preflight should do the following:

1. Read the current credit and billing settings, and record only the required
   non-secret evidence. Confirm the account's no-top-up settings before dispatch.
2. Resolve the exact CUDA image digest and its compressed layers, Dataset size,
   output allowance, requested disk allocation and runtime needs. Include setup
   downloads and a complete extra image/Dataset transfer allowance for recovery.
3. Search with the actual pinned adapter's resource query and identify the ask,
   country, GPU count/model/memory, CUDA capability, available disk and separate
   fee rates. The proposed KR/RTX 3060/40 GB choice still needs workload and live
   offer verification. No particular ask is selected by this research.
4. Bind a constrained key to that assessed ask when the provider's constraint
   syntax has been verified. Re-read the offer immediately before dispatch. A
   changed ask or missing quote makes admission unavailable; an ordinary small
   fee change is handled by re-estimation and headroom.
5. Persist watchdog ownership and the deadline before sending the Managed Job.
   A local supervisor should survive the interactive agent, use short polling,
   and cancel on deadline, budget threshold, unexpected resource, or recovery
   beyond the demonstration's allowance. Confirm the watchdog is running.
6. Reserve cleanup time and credit. After cancellation or completion, verify
   provider-side destruction of every demonstration rental and chargeable
   storage item, then record usage and the resulting balance. Keep polling if
   the provider has not yet confirmed deletion.

For one active GPU, a useful admission calculation is:

```text
planned_cost = bid * allowed_active_hours
             + allocated_disk_hourly * allowed_rental_hours
             + inbound_allowance_gb * inbound_price_per_gb
             + outbound_allowance_gb * outbound_price_per_gb
             + replacement_transfer_allowance
             + cleanup_and_uncertainty_reserve

require planned_cost <= current_available_credit - untouched_credit_reserve
```

Runtime begins at dispatch, including image pulls and provisioning. The runtime
command's timeout alone cannot cover those phases. A 15-minute demonstration
and a separate 10-minute cleanup allowance are plausible starting values, not
approved measurements. Determine transfer allowances from the actual image and
Dataset before accepting them. Leave a substantial fraction of the roughly
USD 2.34 untouched instead of planning to exhaust the balance.

The pinned implementation polls job status every 15 seconds and task startup
every 5 seconds. Transient status-fetch handling has a 60-second timeout. These
are implementation timings, not a supported maximum-rental-count setting.
Recovery launch loops remain unbounded until cancelled. A short local watchdog
can still enforce a practical elapsed-time policy, with credit reserved for a
replacement that wins the cancellation race. Avoid presenting the normal polling
interval as a guaranteed maximum cleanup latency.
[Managed job timings](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/utils.py)

## Exact-ask constrained key, pending provider validation

The public permissions documentation demonstrates exact-ID constraints for
showing, rebooting and destroying existing instances. It does not name the
create-instance route identifier. The current official frontend uses route
identifiers such as `api.machines` and method-specific rights, and its advanced
key editor accepts a JSON policy. Neither source verifies
`api.instance.create_instance` as the create operation. Do not install that guess
as though it were proven.
[SDK permissions examples](https://docs.vast.ai/sdk/python/permissions),
[official frontend bundle](https://cloud.vast.ai/assets/index-DIZgi27Q.js)

The finite policy candidate is the existing `misc`, `user_read` and
`instance_read` access, plus a verified create operation constrained by the ask
ID, disk allocation and explicit numeric bid; `api.instance.destroy` must remain
available for cleanup. This is not yet an executable policy because the create
operation identifier is unresolved. Do not constrain destroy to an ask ID: its
ID is the subsequently allocated rental contract. Resume/start/stop operations
need their own verified permissions if the selected lifecycle invokes them.

A bootstrap wizard can validate the syntax without renting:

1. Create a temporary diagnostic key with read-only rights. Submit a complete
   create body to a confirmed nonexistent ask and retain only the sanitized
   permission-denial details. The response may identify the route name. Delete
   this diagnostic key after the probe.
2. Create another diagnostic key that permits only a confirmed nonexistent ask
   ID for the candidate create operation. Use the same complete valid body for
   the allowed nonexistent ID and a disallowed nonexistent ID.
3. Require different outcomes: the permitted ID reaches the endpoint and reports
   `no_such_ask`; the disallowed ID reports permission or constraint denial.
   Identical permission failures show only that the key denies everything.
   An invalid-body error does not prove that the allowlist works either.
4. Verify the effective rights through the current-user response, including any
   provider-added baseline rights. Then create the actual ask-bound key through
   the approved hidden-input Vault flow and repeat the disallowed-ID probe.
   Preserve separate cleanup access and revoke the temporary bootstrap key.

Use IDs verified absent from the marketplace, never a real unapproved offer for
a rejection test. A key bound to a real ask does not limit how often that same
ask can be rented, so the watchdog and replacement allowance remain necessary.

### No-spend diagnostic observations

The owner ran the temporary-key wizard on September 12, 2026. Its read-only key
received HTTP 401 when submitting the complete create payload to ask 0. The
first helper classified only HTTP 403 as a permission denial and did not retain
sanitized error details, so that result cannot establish the operation name or
the constraint behavior. Cleanup was verified and the temporary keys were
removed at 22:00:15 UTC. The original Vault key was unchanged. The local evidence
is `.scratch/issue283/skywright-283-policy-probe-6bb1e65d51d0.json`.

The official authentication documentation explicitly allows either HTTP 401 or
403 for insufficient scope and recommends checking a key through
`GET /users/current/`. A status alone cannot distinguish insufficient scope from
an invalid or expired credential.
[Vast authentication](https://docs.vast.ai/api-reference/authentication)

A separate protected check with the enrolled instance-management key verified
the current-user response and matching key ID, then sent the same complete
payload to ask 0. It returned HTTP 404 with the exact error code
`ask_not_found`. This establishes that ask 0 reaches the lookup with an allowed
key and this body. It did not rent an instance or change the enrolled key.
The local evidence is `.scratch/issue283/enrolled-policy-zero.json`. That live
error spelling supplements the documented `no_such_ask` spelling; generic 400
validation failures still do not establish that the create permission passed.

The revised helper verifies each temporary key through current-user reads with
matching key IDs before a probe and after any HTTP 401/403. It records known
error enums, permission and invalid-key indicators, and `api.*` operation
identifiers from error fields even when classification fails. Nested error
objects are inspected, unknown strings are discarded, and known key values are
redacted before extracting identifiers. HTTP 401/403 counts as scope denial
only with successful identity checks and an explicit denial indicator. Initial
HTTP 401 authentication failures permit at most three current-user attempts.
This tolerates possible creation delay without asserting a documented provider
propagation guarantee. A mismatched identity, HTTP 403, or a failed identity
check after a probe is not retried.

The public permission reference, SDK sources and current frontend do not expose
a create-operation discovery endpoint. A protected denied read of the host
machines endpoint with the existing key returned HTTP 401 and an explicit
permission-denial message, but no operation identifier or error enum. The local
evidence is `.scratch/issue283/denial-shape.json`. Another run that required a
route name from the denial would risk wasting a human credential setup step.
[Permission categories and constraints](https://docs.vast.ai/api-reference/permissions)

The revised no-spend experiment therefore uses a finite fallback if the create
denial supplies no single operation identifier. These are explicitly hypotheses,
not asserted provider API names:

| Hypothesis | Basis in primary sources |
| --- | --- |
| `api.instance.create` | Documented `api.instance.show`, `destroy` and `reboot` operation naming |
| `api.instance.create_instance` | That documented namespace and the official SDK's `create_instance` method |
| `api.asks` | Documented `PUT /asks/{id}/` path and the frontend's `api.instances` and `api.machines` route names |

Each candidate gets an ID-only key. It must allow ask 0 to reach the missing-ask
lookup, deny ask -1 by authorization, and then allow ask 0 again. Matching
effective rights are required first. Only a successful differential establishes
the route and exact-ID constraint. A key that denies both IDs proves neither.
If all three candidates fail, the result stays inconclusive. The experiment
never expands its candidate list or submits to a positive ask.

For a proven route, one further key tests the combined ID, bid and disk
constraints. Those extra constraints are optional findings. Failure does not
invalidate an independently proven ID constraint; the explicit numeric bid
already travels through the unchanged SDK. The evidence reports the ID-only
proof separately from the combined result and retains only the policy actually
verified. There are at most five diagnostic keys, including the initial
read-only key, and all are removed by the cleanup block. The enrolled Vault key
is not replaced by this experiment.
