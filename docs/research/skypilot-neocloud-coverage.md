# Neocloud coverage in SkyPilot, and each provider's spot semantics

## Answer

SkyPilot has broad neocloud *adapter* coverage, but the capabilities needed by
Skywright are uneven.  On the current stable line, only Nebius has a directly
documented compute adapter without SkyPilot's "Community Maintained" badge.
CoreWeave and Together AI are documented as existing-cluster integrations
through Kubernetes (CoreWeave also through Slurm), not as VM clouds that
SkyPilot provisions.  Lambda, RunPod, Paperspace, Vast, Fluidstack, Cudo,
Shadeform, Prime Intellect, Seeweb, and Verda are explicitly community
maintained.  Hyperbolic, Mithril, and Yotta are registered adapters in the
stable source tree but have no installation section; this note therefore calls
them **code-only / experimental** (an inference, not a label SkyPilot applies).

For Skywright's immediate requirements:

- The directly integrated, verified spot paths are **Nebius, RunPod, Vast,
  Verda, and Mithril**.  Nebius gives 60 seconds' notice; Mithril gives five
  minutes; Verda explicitly gives no warning.  RunPod and Vast document
  interruption but no advance-notice duration.
- **Prime Intellect must not yet be counted as a verified SkyPilot spot path.**
  Its catalog and feasibility code accept `use_spot`, but its launch client
  always sends `maxPrice: 0`, which the source comment defines as
  on-demand/non-spot.  This needs an upstream answer or an empirical spike.
- CoreWeave offers Spot Node Pools with a controlled window of at most seven
  minutes, but SkyPilot's Kubernetes `use_spot` selector only knows GKE and
  Karpenter spot labels, not CoreWeave's `computeClass: spot`.  A pre-created
  CoreWeave spot-only pool can still evict/reschedule workloads at the
  Kubernetes layer, but SkyPilot cannot presently request it as a priced spot
  candidate.
- SkyPilot's object-bucket `MOUNT` mode (the `D4` path that streams instead of
  fully materialising) is rejected by the adapters for RunPod, Vast,
  Shadeform, Seeweb, Verda, Hyperbolic, and Yotta.  It is accepted by Lambda,
  Nebius, Paperspace, Fluidstack, Cudo, Prime Intellect, and Mithril.  On
  Kubernetes routes it is conditional on permission to install/use SkyPilot's
  privileged FUSE proxy.
- Managed Jobs is provider-generic and can use these adapters as worker
  targets because they implement provisioning and status queries.  This is
  not a provider-by-provider certification: only the generic recovery contract
  is documented.  Several adapters cannot host the *legacy remote jobs
  controller*; that is not a worker limitation when a remote API server uses
  the default consolidation mode, but it matters to a local API-server setup.
- SkyPilot has hourly prices for every direct adapter in its own hosted CSV
  catalog, but freshness ranges from a seven/eight-hour local refresh to
  "download once, never refresh automatically."  Kubernetes routes have no
  provider catalog at all and default to `$0.00` unless the operator supplies
  rates.  These are estimates, not invoices.  Thus the catalog is useful for
  `K1` only with freshness/provenance surfaced; it is insufficient as the
  authoritative cumulative-cost source for `K2`.

## Scope and version

Evidence was checked on 2026-08-04 against:

- SkyPilot stable **v0.13.0** (released 2026-07-22) and current master commit
  [`d98316e`](https://github.com/skypilot-org/skypilot/tree/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4)
  (2026-08-03).  `latest` documentation is a developer preview, so master-only
  behavior is identified where relevant.
- First-party provider documentation current on 2026-08-04.

The table includes GPU/neocloud integrations that SkyPilot documents, plus
the three registered code-only GPU-cloud adapters.  It excludes hyperscalers
and general-purpose clouds.  Crusoe appears in SkyPilot's homepage provider
logo/list but has neither a direct cloud adapter nor a setup section in the
examined source; it is an unresolved marketing/integration boundary, not a
verified SkyPilot compute target.  Together AI is summarized separately
because it is a kubeconfig bridge rather than a provider adapter.

Legend: **V** = verified directly in first-party docs/source; **I** = inference
from those sources; **?** = unresolved.  “Catalog TTL” is the maximum age at
which a stock, unmodified local catalog is re-downloaded; it does not prove
that the upstream hosted CSV was regenerated recently.

## Provider comparison

| Provider | SkyPilot route / maturity | Spot through SkyPilot; provider semantics | SkyPilot price catalog | `D4` object-bucket `MOUNT` | Managed Jobs target / legacy controller host | Compute credentials |
|---|---|---|---|---|---|---|
| **CoreWeave** | **V** existing CKS Kubernetes or SUNK Slurm; no community badge; not a direct VM adapter | **? not selectable as CoreWeave spot by `use_spot`**. Provider Spot Node Pools may be reclaimed; warning/cordon at T=0, drain at T=2m, removal by T=7m. **V** | No provider catalog. Kubernetes defaults to `$0.00`; manual `kubernetes.pricing`. **V** | **Conditional V**: Kubernetes FUSE proxy/RBAC; CAIOS/S3 credentials separate | Kubernetes Managed Jobs/recovery **V**; K8s can host controller **V** | CKS kubeconfig; SUNK SSH config/key. CAIOS uses a separate S3-style key pair. **V** |
| **Together AI** | **V** existing Kubernetes cluster/kubeconfig; no direct adapter | **?** SkyPilot has no Together spot selector or semantics; depends on supplied cluster | Same manual Kubernetes pricing as CoreWeave **V** | **Conditional V**: Kubernetes FUSE proxy/RBAC | Kubernetes Managed Jobs/recovery **V**; K8s can host controller **V** | kubeconfig obtained for a Together GPU cluster **V** |
| **Nebius** | Direct adapter; documented without community badge (**core-maintained I**) | Preemptible VM; capacity can be reclaimed. `SIGTERM` **60s** before stop, then `SIGKILL`; attached-volume data preserved, dynamic IP lost; SkyPilot sets priority 1 and `on_preemption=STOP`. **V** | Static + tenant-personal `vms.csv`; on-demand and spot; **7h TTL**, 5m retry after personal-pricing failure. **V** | **Yes V**; native Nebius S3-compatible storage also documented | Target/recovery **V generic**; legacy controller host **yes**; HA controller host no | IAM access-token + tenant-ID files, or service-account `credentials.json`; object store additionally needs S3 access/secret keys. **V** |
| **RunPod** | Direct; **Community Maintained V** | Interruptible/spot Pod, launched with catalog price as `bidPerGpu`; may be stopped at any time to free resources. No notice duration found. **V / ? notice** | `vms.csv`, on-demand + spot; **7h TTL**, but source says upstream has no set update schedule. **V** | **No V** (`COPY` required); SkyPilot separately supports RunPod Network Volumes, which are not object storage | Target/recovery **V generic**; legacy controller host **yes**; HA controller host no | RunPod API key written by `runpod config` (`~/.runpod/config.toml`). **V** |
| **Lambda Cloud** | Direct; **Community Maintained V** | **No**; adapter explicitly rejects spot. **V** | On-demand `vms.csv`; **7h TTL**. **V** | **Yes V** by adapter feature contract; storage credentials still separate | Target/recovery **V generic**; legacy controller host **no** | API key in `~/.lambda_cloud/lambda_keys`. **V** |
| **Paperspace** | Direct; **Community Maintained V** | **No**; adapter explicitly rejects spot. **V** | On-demand `vms.csv`; **no automatic TTL**. **V** | **Yes V** by adapter feature contract | Target/recovery **V generic**; legacy controller host **yes**; HA controller host no | API key in `~/.paperspace/config.json`. **V** |
| **Vast.ai** | Direct; **Community Maintained V** | Interruptible bid instance. On-demand always outranks bids; highest interruptible bid runs, others pause and auto-resume; disk data is preserved while paused. SkyPilot uses the live offer's minimum bid unless `vast.create_instance_kwargs.price` overrides it. No notice duration found. **V / ? notice** | On-demand + `min_bid` spot in `vms.csv`; **no automatic TTL**. Provisioning queries live offers, so launch availability/bid is fresher than planning price. **V** | **No V** | Target/recovery **V generic**; legacy controller host **yes**; HA controller host no | API key in `~/.config/vastai/vast_api_key`. **V** |
| **Fluidstack** | Direct; **Community Maintained V** | **No**; adapter rejects spot. **V** | On-demand `vms.csv`; **no automatic TTL**. **V** | **Yes V** by adapter feature contract | Target/recovery **V generic**; legacy controller host **no** | API key in `~/.fluidstack/api_key`. **V** |
| **Cudo Compute** | Direct; **Community Maintained V** | **No**; adapter says Cudo API does not implement spot. **V** | On-demand `vms.csv`; **7h TTL**. **V** | **Yes V** by adapter feature contract | Target/recovery **V generic**; legacy controller host **no** (no autostop) | `cudoctl init` stores API key, project, and billing account in `~/.config/cudo/cudo.yml`. **V** |
| **Shadeform** | Direct marketplace; **Community Maintained V** | **No** in adapter, regardless of whether an upstream marketplace provider has spot. **V** | Minimal hosted static `vms.csv`; source says dynamic API fetching is not implemented; **no automatic TTL**. **V** | **No V** | Target/recovery **V generic**; legacy controller host **no** | API key in `~/.shadeform/api_key`. **V** |
| **Prime Intellect** | Direct marketplace; **Community Maintained V** | Provider offers spot, interruptible when underlying capacity is needed, with no notice duration documented. SkyPilot accepts `use_spot` in catalog selection, but launch sends `maxPrice: 0` (commented as on-demand/non-spot); therefore **? not a verified SkyPilot spot launch**. | On-demand + spot-shaped `vms.csv`; **no automatic TTL**. **V** | **Yes V** by adapter feature contract | Target/recovery **V generic, but spot unverified**; legacy controller host **yes** | `prime login` creates `~/.prime/config.json` with API key; optional team ID. **V** |
| **Seeweb** | Direct; **Community Maintained V** | **No**; adapter explicitly rejects spot. **V** | On-demand `vms.csv`; **8h TTL**. **V** | **No V** | Target/recovery **V generic**; legacy controller host **no** | API token in `~/.seeweb_cloud/seeweb_keys`. **V** |
| **Verda** | Direct; **Community Maintained V** | Spot can be evicted at any point **without warning**, even seconds after creation. SkyPilot requests `contract=SPOT`. Volume retention is provider-configurable, but the SkyPilot launch adapter does not expose that control. **V** | On-demand + spot `vms.csv`; **7h TTL**, upstream update schedule unspecified. **V** | **No V** (`COPY` required) | Target/recovery **V generic**; legacy controller host **no** | OAuth client ID/secret in `~/.verda/config.json` or `VERDA_CLIENT_ID` / `VERDA_CLIENT_SECRET`. **V** |
| **Hyperbolic** | Registered stable adapter but absent from install docs: **code-only/experimental I** | **No**; adapter rejects spot. **V source** | On-demand `vms.csv`; **no automatic TTL**. **V** | **No V** | Target/recovery **I generic, no documented E2E support**; legacy controller host **no** | Bearer API key in `~/.hyperbolic/api_key`. **V source** |
| **Mithril** | Registered stable adapter but absent from install docs: **code-only/experimental I** | Blind second-price spot auction evaluated every 2m; preemption when price exceeds limit; **5-minute notice**, boot/persistent disks kept, ephemeral lost, bid reopens. SkyPilot always bids a hard-coded **`$32.00` limit** (TODO says make configurable). **V** | Spot `vms.csv`; **7h TTL**. **V** | **Yes V** by adapter feature contract | Target/recovery **I generic, no documented E2E support**; legacy controller host **no** | `MITHRIL_API_KEY` + `MITHRIL_PROJECT`, or active profile in `~/.config/mithril/config.yaml` (XDG-aware). **V source** |
| **Yotta** | Registered stable adapter but absent from install docs: **code-only/experimental I** | **No**; adapter rejects spot. **V source** | On-demand `vms.csv`; **no automatic TTL**. **V** | **No V** (`COPY` required) | Target/recovery **I generic, no documented E2E support**; legacy controller host **no** | `orgId` and `apikey` in `~/.yotta/credentials`. **V source** |

### Why the Managed Jobs column is qualified

SkyPilot documents Managed Jobs as the provider-independent lifecycle wrapper:
it provisions a temporary cluster, monitors node/preemption/GPU failures,
searches for resources again, relaunches, and cleans up.  By default, recovery
restarts the application from scratch; the application must checkpoint to
durable storage to resume progress.  No official per-provider Managed Jobs
certification matrix was found.  The direct adapters above all expose the
provision/status interface used by that generic path, so they are viable worker
targets at the abstraction level, but the community/code-only integrations
remain an operational-risk tier until tested.

`HOST_CONTROLLERS` means “this cloud can run the legacy remote jobs/serve
controller”; it does **not** mean “this cloud can be a Managed Jobs worker.”
With a remote API server, current SkyPilot defaults to consolidation mode and
manages jobs in the API server.  With a local API server, SkyPilot normally
launches a remote jobs controller, so at least one enabled cloud that can host
controllers (or an explicitly deployed remote API server) is required.

## Spot semantics and recovery consequences

1. **Nebius has the cleanest direct signal for checkpoint policy.**  A process
   gets `SIGTERM` 60 seconds before stop.  Skywright can install a handler or
   SkyPilot preemption hook, but a checkpoint must fit inside that budget.
   Nebius preserves attached volumes, yet a cross-region/cloud Managed Jobs
   recovery still needs object storage or another portable durable store.
2. **Mithril's five-minute state is observable.**  Its API reports
   `Preempting`; SkyPilot deliberately maps `STATUS_PREEMPTING` to `UP` during
   the notice period and `STATUS_PREEMPTED` to `STOPPED`.  That is the strongest
   code-only candidate for graceful checkpointing, but the fixed `$32` maximum
   bid and undocumented SkyPilot setup make it unsafe to adopt without a spike.
3. **CoreWeave provides the longest documented window, but below SkyPilot's
   provider abstraction.**  Kubernetes events and labels expose pending and
   in-progress preemption; Pods receive normal `preStop`/`SIGTERM` processing.
   SkyPilot can run preemption hooks in Kubernetes Pods, yet current spot-label
   discovery cannot request CoreWeave Spot Node Pools.
4. **RunPod and Vast require failure-first design.**  Neither first-party doc
   examined promises an advance signal.  Vast pauses and may later resume the
   same interruptible instance, whereas Managed Jobs normally treats lost
   capacity as a reason to provision a replacement.  Durable checkpoints must
   therefore be external to the instance even though Vast preserves local data
   while paused.
5. **Verda promises no warning.**  Periodic/asynchronous durable checkpointing,
   not a termination hook, must carry correctness.
6. **Prime Intellect inherits marketplace variability.**  The API exposes an
   `isSpot` property per upstream offering, but provider-specific notice and
   storage behavior are not normalized, and the current SkyPilot launch path is
   internally inconsistent.  Treat it as unsupported for the architecture
   decision until verified.

## Price catalog, `K1`, and `K2`

SkyPilot's [`common.read_catalog`](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/catalog/common.py#L195-L301)
downloads schema-v8 CSV files from the separate
[`skypilot-catalog`](https://github.com/skypilot-org/skypilot-catalog/tree/master/catalogs/v8)
repository on first use.  A provider module may set a pull frequency.  A
locally modified CSV is deliberately never overwritten.  Critically, the TTL
only refreshes from the hosted repository; freshness also depends on that
repository's provider-specific fetch workflow.  Several adapters pass no TTL,
so a downloaded file remains indefinitely until manually deleted or replaced.

Nebius is the exception with an account-aware path: by default it queries
Nebius projects and the billing estimator for tenant-specific on-demand/spot
prices, caches the result for seven hours, and falls back to static or stale
data on failure.  Vast queries live offers during provisioning, but planning
still starts from the hosted catalog.  Shadeform explicitly says its planned
dynamic API fetching is not implemented.

SkyPilot displays the selected hourly catalog price before launch, so it can
seed `K1`.  However, [`sky cost-report`](https://docs.skypilot.co/en/latest/reference/cli.html#sky-cost-report)
is explicitly experimental: estimated cost is hourly price times locally
cached uptime, and the docs warn it may be inaccurate for spot/autostop and
resources changed in the provider console.  It is cluster-oriented rather than
a provider invoice, and marketplace bid prices can change.  Therefore:

- **`K1`:** use the SkyPilot estimate as a quote with `catalog_observed_at`,
  source/provider, spot/on-demand flag, and an “estimate” label.  A strict cost
  ceiling needs a live-provider validation close to launch for providers whose
  price/availability is dynamic.
- **`K2`:** persist Skywright's own per-attempt resource, catalog price, and
  timing records for immediate UX, but reconcile against provider billing APIs
  or exports when authoritative cumulative spend is required.  SkyPilot's
  catalog/cost report alone cannot meet that requirement honestly.

## Credentials implications

Compute credentials are primarily long-lived API-key files.  SkyPilot's cloud
classes return these files as credential mounts for controllers; a remote API
server deployment must supply the same files/secrets.  Nebius is more complex
(short-lived user IAM token versus long-lived service-account JSON plus tenant
identity), CoreWeave/Together use kubeconfig, and Verda/Mithril can use
environment variables.  Managed Jobs documentation recommends long-lived
credentials to avoid controller expiry and leaked resources.

Object storage is a second credential plane.  A compute API key is generally
not sufficient to mount a dataset bucket.  For a portable `D4` design,
Skywright should expect an S3/GCS/R2/CAIOS/Nebius-storage credential alongside
the compute credential, even on adapters whose feature contract allows
`MOUNT`.  Nebius and CoreWeave explicitly document separate S3-compatible key
pairs.  This keeps the credentials-transport question in the map open; this
ticket constrains its inputs but does not decide transport.

## Primary sources

### SkyPilot

- [Installation and provider maturity/credentials (pinned source)](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/getting-started/installation.rst)
- [Managed Jobs lifecycle, recovery, and controller modes](https://docs.skypilot.co/en/latest/examples/managed-jobs.html)
- [Cloud buckets: `MOUNT`, `COPY`, `MOUNT_CACHED`](https://docs.skypilot.co/en/latest/reference/storage.html)
- [Kubernetes FUSE permissions](https://docs.skypilot.co/en/latest/cloud-setup/cloud-permissions/kubernetes.html)
- [Kubernetes pricing defaults](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/config.rst#L2022-L2064)
- [Cloud capability enum](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/cloud.py#L33-L63)
- Direct adapters: [Nebius](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/nebius.py), [RunPod](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/runpod.py), [Lambda](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/lambda_cloud.py), [Paperspace](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/paperspace.py), [Vast](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/vast.py), [Fluidstack](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/fluidstack.py), [Cudo](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/cudo.py), [Shadeform](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/shadeform.py), [Prime Intellect](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/primeintellect.py), [Seeweb](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/seeweb.py), [Verda](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/verda.py), [Hyperbolic](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/hyperbolic.py), [Mithril](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/mithril.py), [Yotta](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/yotta.py)
- Spot launch details: [RunPod bid](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/clouds/runpod.py#L217-L240), [Vast live minimum bid](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/provision/vast/utils.py#L178-L183), [Nebius preemptible STOP policy](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/provision/nebius/utils.py#L670-L682), [Verda `SPOT` contract](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/provision/verda/instance.py#L132-L152), [Prime Intellect `maxPrice: 0`](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/provision/primeintellect/utils.py#L253-L268), [Mithril fixed bid](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/provision/mithril/utils.py#L543-L607)
- [Kubernetes spot-label map](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/provision/kubernetes/utils.py#L4295-L4354)
- Catalog modules and their TTLs: [Lambda](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/catalog/lambda_catalog.py), [RunPod](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/catalog/runpod_catalog.py), [Nebius](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/catalog/nebius_catalog.py), [Vast](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/catalog/vast_catalog.py), [Verda](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/catalog/verda_catalog.py), [Mithril](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/catalog/mithril_catalog.py)

### Providers

- CoreWeave: [Spot Node Pools and seven-minute process](https://docs.coreweave.com/platform/capacity-plans/spot-node-pools)
- Nebius: [Preemptible VMs and 60-second signal](https://docs.nebius.com/compute/virtual-machines/preemptible)
- RunPod: [Pod API interruptible semantics](https://docs.runpod.io/api-reference/pods/POST/pods), [storage persistence](https://docs.runpod.io/pods/storage/types)
- Vast: [instance types and bidding priority](https://docs.vast.ai/guides/instances/choosing/instance-types), [bid API](https://docs.vast.ai/api-reference/creating-instances-with-api)
- Prime Intellect: [spot definition](https://docs.primeintellect.ai/faq), [availability API `isSpot`](https://docs.primeintellect.ai/api-reference/availability/get-gpu-availability)
- Verda: [spot without warning](https://docs.verda.com/cpu-and-gpu-instances/set-up-a-gpu-instance/), [spot eviction storage policy](https://docs.verda.com/welcome-to-verda/release-notes/verda-api-changes/#2026-02-03-spot-instance-volume-policy)
- Mithril: [spot auction, five-minute preemption, and storage behavior](https://docs.mithril.ai/compute-and-storage/spot-bids), [spot API](https://docs.mithril.ai/compute-api/compute-api-reference/spot)

## Could not establish

- A first-party, provider-by-provider SkyPilot Managed Jobs test/certification
  matrix.  The “target/recovery” result follows the generic Managed Jobs
  contract plus adapter provisioning/status support.
- Any promised advance notice for RunPod, Vast, or Prime Intellect spot
  interruption.  Their official docs describe interruptibility but do not
  specify a notice interval.
- Whether Prime Intellect's current SkyPilot adapter can successfully launch a
  spot offering despite the `maxPrice: 0` payload/comment mismatch.
- Why Hyperbolic, Mithril, and Yotta are registered in stable source but omitted
  from installation and homepage support lists; “experimental” is an inference
  from that documentation status.
- A direct SkyPilot compute path for Crusoe, despite its appearance in the
  current homepage list.
- Upstream regeneration SLAs for every hosted CSV.  A client-side TTL is not an
  upstream freshness guarantee, and adapters with no TTL can remain stale
  indefinitely.
- Whether SkyPilot's generic Managed Jobs recovery has been exercised against
  each marketplace's less conventional state transition (especially Vast
  pause/resume and Mithril's reopening bid) without duplicate allocation or
  delayed cleanup.
