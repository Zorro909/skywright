# Vast launch price guard for issue #283

Research date: 2026-09-12. Scope includes the owner's conditional extension to
interruptible instances when on-demand cannot enforce the required price.
No provider credentials were read and no paid API was called for this research.

The supported interruptible bid field solves the per-machine active-rental price
problem. It does not yet prove the complete budget gate. Storage, traffic, and
replacement rentals remain separate obligations. On-demand still has no proved
actual-rental cap through the unchanged pinned adapter.

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
later unfiltered SkyPilot search. A cheap observed offer and a bounded bid leave
storage and traffic of the actual selected offer unproved.
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
placement. Before spending, establish actual selected-offer storage and traffic
bounds, an independent cleanup deadline, and a replacement policy that cannot
create unbudgeted rentals. A fresh quote alone does not discharge those gates.
If provider constraints or an upstream change supply these guarantees, verify
that rejected requests cannot create a contract before attempting a paid run.
The target must remain unavailable while any gate is unproved.
