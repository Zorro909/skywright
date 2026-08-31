---
status: accepted
---

# Keep the control plane always on, initially beside the AMD target

Skywright's control responsibilities outlive every browser session. The Spring backend and the version-paired SkyPilot API server therefore form one continuously operating control-plane deployment while remaining separate services: SkyPilot must stay alive for Managed Jobs provisioning, monitoring, and preemption recovery, while the backend must stay alive for retained-fact reconciliation, Run Log Archive capture, Runtime and Cost Ceiling evaluation, and delivery of durable control decisions. They are upgraded as a pinned pair, but either process may restart independently and reconstruct its work from durable sources. An on-demand backend was rejected because it would silently suspend those guarantees whenever nobody had the UI open.

The first deployment co-locates both services on the AMD training host and is deliberately non-HA. Losing or starving that host can therefore remove local execution and every control-plane capability together, and can leave existing cloud compute running without SkyPilot management, recovery, observation, or ceiling enforcement. This coupling is accepted for the first version. No CPU or memory reservation protects the control plane from training workloads yet; numerical sizing, resource isolation, backups, and HA remain operational work rather than architecture fixed here.

The SkyPilot API server keeps its authoritative control state in a separate database and role on the operator-managed PostgreSQL service. A retained volume holds SkyPilot-managed logs and submitted-file staging that are not database state. Production treats both as pre-existing operator-owned resources that survive application release, rollback, and removal; the local deployment creates equivalent resources and retains them across ordinary cleanup. This lets the SkyPilot service restart independently without making its container filesystem authoritative while preserving the accepted whole-host failure domain.

## Access boundary

The UI and backend are reachable remotely only through an operator-controlled private network path. Skywright performs no authentication or authorization of its own: anyone who can reach the backend has full access, including control and confirmed destructive actions. Every request is attributed to one built-in **Principal Identity**, which records the deployment-wide authority honestly but cannot identify an individual human. A later access layer may supply distinct Principal Identities without changing action-record shapes.

This trust boundary is separate from `Q6`. `Q6` continues to mean that the backend process is replaceable without information loss because durable state lives in its database, Run Stores, SkyPilot, and other named sources; it does not promise an authentication boundary.

## Co-located auxiliary roles

Each **Metric View** is an isolated, ephemeral per-Run TensorBoard process or container on the AMD host. Only the backend proxy reaches it; no TensorBoard port is exposed to the private network. It consumes no GPU, starts on access, stops on idle, and is recreated transparently after loss because its Metric Segments remain in the Run Store.

The **Transfer Worker** is a distinct background role on the same host, not part of an HTTP request and not a byte path through the Java backend. It has bounded concurrency, records enough durable progress to resume idempotently after process or host restart, and shares the host's network link with training and control traffic. The first version accepts that bandwidth and lifetime with no transfer-throughput guarantee. Dispatch to another host remains outside this architecture map.

## Capability-specific availability

Skywright fails open by capability when a dependency is lost: execution continues wherever the underlying system can continue, affected capabilities report explicit unavailability, and neither stale observations nor loss of visibility invent a Run Lifecycle State. Loss of control-plane visibility never causes cancellation by itself.

The database is the exception because the useful control-plane application depends on its durable authority and must establish schema compatibility before serving traffic. An unreachable or unvalidated database therefore fails backend startup. If a validated database disappears at runtime, the backend process remains live for diagnostics and automatic reconnection, but becomes unready, reports its aggregate health as down, and bounds affected requests with an explicit service-unavailable response. Readiness recovers only after connectivity and schema compatibility have been re-established. This replaces the backend bootstrap's earlier external-dependency-free readiness convention now that durable persistence is part of the application.

- Losing the browser or private access path removes interactive observation and control only; background duties continue.
- Losing the Spring backend removes its UI/API, reconciliation, new log capture, ceiling evaluation and delivery, Metric View proxy and launches, while SkyPilot-managed execution and recovery and training-process Run Store writes may continue. An already-running Transfer Worker may continue independently.
- Losing the SkyPilot API server or its bridge removes provisioning, recovery, control actions, authoritative active-Run state, and any ceiling evaluation that needs fresh orchestration facts. Durable database and Run Store reads remain available.
- Losing the whole AMD host additionally stops local execution and every co-located Metric View and Transfer Worker. Existing cloud compute may continue temporarily, but without the home control plane it is unmanaged and unobserved and has no SkyPilot recovery guarantee.
- Losing the database makes the backend globally unready and removes the Run index and definitions, cost derivation, durable decisions, and every dependent read or control. SkyPilot execution and training-process writes may continue; the backend never reconstructs authority from stale data.
- Losing a Run Store removes metrics, progress, retained logs, Metric Views, Policy Stop delivery, and Run-output operations for that Run. SkyPilot lifecycle observation and controls that do not require the store may remain available, and live logs never fall back to a direct SkyPilot stream.
- Losing a provider, storage endpoint, or outbound network path removes only the capabilities that depend on that source, each naming the unavailable source rather than collapsing into a global health value.
- Losing a Metric View removes only that visualization; it is recreated from durable Metric Segments on demand.
- Losing a Transfer Worker leaves the authoritative source Storage Location usable and the transfer pending for idempotent resumption.

A Runtime Ceiling or Cost Ceiling is explicitly unenforceable whenever its required observation, decision persistence, or delivery capability is unavailable. Skywright neither treats missing input as zero nor converts a best-effort stop trigger into a fail-closed cancellation.

## Consequences

This topology keeps local Runs free of a cloud control-plane dependency and makes browser lifetime irrelevant, at the cost of one large first-version failure domain. The private network is the only access control, so accidental exposure is equivalent to granting full control. Co-located Metric Views and transfers are cheap to operate but can contend with training, and host loss interrupts their work. These risks are explicit rather than disguised as availability guarantees the first deployment does not provide.
