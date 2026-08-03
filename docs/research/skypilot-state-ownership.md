# What state does SkyPilot own, and what survives controller teardown?

## Answer

SkyPilot owns **orchestration state**, not a complete durable experiment record. It records API requests, cluster handles and history, ordinary cluster-job queues, and managed-job lifecycle state. It also keeps several classes of operational logs. It does not define a durable store for model checkpoints, application artifacts, or training metrics; those must live in a volume, object store, experiment tracker, or another system with an independent lifecycle.

Whether SkyPilot state survives is deployment-dependent:

- A local API server keeps its control-plane state on that client machine. Replacing the machine or losing its SkyPilot runtime directory loses the supported control path to that state.
- A remote API server centralizes the cluster registry and, in the current default **consolidation mode**, managed-job state. With an external PostgreSQL database, API-server pods can restart or be upgraded without losing database state. The Helm deployment otherwise uses persistent storage for state; its separate PVC persists managed-job logs and submitted local files across pod restarts.
- A legacy remote jobs controller, still used automatically with a local API server, keeps managed-job state and downloaded logs on the controller VM or pod. Stopping it preserves its disk, but **tearing it down explicitly destroys all finished-job status and logs**. SkyPilot therefore refuses teardown while jobs are in progress.
- An ordinary `sky exec` / `sky launch` job queue is stored on the compute cluster's head node. It survives only as long as that head filesystem survives: stopping a VM cluster preserves its disks, while `sky down`, cluster loss, or ephemeral Kubernetes pod replacement removes the queue database and local logs.

Consequently, Skywright should not treat SkyPilot as the sole run system of record. A production remote API server with a protected database is a reasonable authority for **live orchestration**, but Skywright should own a durable run ID and run record, and should persist metrics, artifacts, checkpoints, and any required historical status outside SkyPilot. The best SkyPilot-provided correlation key is `SKYPILOT_TASK_ID`, which the managed-jobs controller deliberately holds stable across recoveries; the numeric managed-job ID is only stable inside one controller/API-server database.

## State map

| State | Physical authority | User query | Survival and retention |
|---|---|---|---|
| API operation/request | API server request database; default local path `~/.sky/api_server/requests.db` | `sky api status`, `sky api logs <request-id>` | Active requests continue if a CLI disconnects. Finished records and their logs are GC'd after **24 hours** by default. Request log files are transient across API-server startup in the default path. |
| Live cluster registry | API server `state` database (`~/.sky/state.db` with SQLite, shared configured database with PostgreSQL) | `sky status`; `-r` refreshes cloud state | Survives a client change only when the client reconnects to the same remote API server. Survives pod replacement only with persistent DB/PV. The live row is removed on `sky down`. |
| Cluster history / cost cache | `cluster_history` table in the same state database | `sky cost-report`; dashboard history | History outlives removal of the live cluster row. The default query window is 30 days, but that is a query filter, not documented row GC. It is an estimate from cached state, not cloud billing truth. |
| Cluster events and provisioning artifacts | State DB plus `~/sky_logs/sky-*` on the API server | `sky status -v`; detailed API/provision logs | Cluster events default to **30-day** GC. Per-operation log directories default to **30-day** GC, except a provision log referenced by an existing cluster. Helm says transient API/cluster logs are not on the managed-job/file-mount PVC. |
| Ordinary cluster job queue and status | Head node `~/.sky/jobs.db` | `sky queue <cluster>` | Scoped to one cluster; survives a disk-preserving stop, not teardown or lost/ephemeral head storage. There is no separate central historical queue. |
| Ordinary cluster job logs | Head node `~/sky_logs/<job-id>-<name>/...` | `sky logs <cluster> <job-id>` | Same head-filesystem lifecycle as the queue unless the application forwards logs elsewhere. |
| Managed-job definition, queue, status, timestamps, recovery count, failure reason, task metadata | `spot_jobs` database (`~/.sky/spot_jobs.db` with SQLite or configured PostgreSQL) on the current API server in consolidation mode; on the legacy jobs controller otherwise | `sky jobs queue`; dashboard; SDK | Current source has no GC for the main managed-job/status rows. Losing the backing DB loses them. Legacy controller teardown explicitly loses all finished-job status. |
| Managed-job status-transition events | `job_events` table in the managed-jobs database | Dashboard/debug APIs | Hard-coded **30-day** retention in current source. This is shorter than the apparent lifetime of the main job row. |
| Managed-job controller logs | `~/sky_logs/jobs_controller/<job-id>.log` on the API server/controller, or a configured external logging store | `sky jobs logs --controller <job-id>` | Default **7-day** GC. Legacy controller teardown loses them. In Helm consolidation mode, the managed-job PVC persists them across pod restarts; an external logging agent/reader can be the durable copy. |
| Managed-job task logs copied back after completion | `~/sky_logs/managed_jobs/job-id-<id>/...` on the API server/controller, or a configured external logging store | `sky jobs logs <job-id>` | Default **7-day** GC for local copies. External-store retention applies when a logging agent and reader are configured. Legacy controller teardown loses local copies. |
| Submitted local `workdir` / `file_mounts` | Upload staging on API server/controller; object bucket if `jobs.bucket` is configured | Used during launch/recovery, not a run-history query | A rolling update can lose ephemeral staging. Helm's persistent-storage PVC preserves it across restarts; cloud buckets, independent volumes, or Git are safer durable sources. |
| Checkpoints and output artifacts | Application-selected volume/bucket/filesystem | Application/tool specific | SkyPilot explicitly tells managed jobs to checkpoint to persistent volumes or buckets for recovery. Controller/API state is not an artifact store. |
| API/server, cluster inventory, managed-job, and GPU metrics | `/metrics` and federated Prometheus endpoints; time series are stored by the deployed/external Prometheus | Prometheus/Grafana/dashboard | Optional observability. SkyPilot exposes current series, but Prometheus owns time-series retention. These are not arbitrary application/training metrics. |

### Database placement

The current database helper makes the placement rule explicit: when a PostgreSQL connection string exists, database managers share that database; otherwise each named database becomes `${SKY_RUNTIME_DIR:-$HOME}/.sky/<name>.db`. The global registry uses the name `state`, managed jobs uses `spot_jobs`, and cluster-head jobs separately open `.sky/jobs.db`. ([database engine selection](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/utils/db/db_utils.py#L606-L689), [cluster-head job DB](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/skylet/job_lib.py#L94-L150))

The global database contains both a live `clusters` table (name, serialized handle, status, owner/workspace, provider location, internal cluster hash) and a separate `cluster_history` table with usage intervals and launch metadata. Terminating a cluster deletes its live row but retains/updates history. ([global state schema](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/global_user_state.py#L91-L140), [cluster history schema](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/global_user_state.py#L175-L229), [removal behavior](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/global_user_state.py#L1480-L1515))

The managed-jobs schema records the task/job IDs, requested resources, submission/start/end times, current status, recovery count, failure reason, local log pointer, user/workspace, scheduler state, and original YAML contents. This is substantially richer than the cluster-head job table, but it is still controller-database state. ([managed-job task and job schemas](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/jobs/state.py#L59-L215))

## Teardown and machine-change matrix

| Event | Local API server + legacy controller | Remote API server, consolidation mode |
|---|---|---|
| CLI exits / laptop sleeps | Submitted API request continues only while the local API-server process remains viable; managed job itself continues under the remote controller. | Request and managed job continue on the remote server. |
| Use another client machine | No supported shared view unless the local SkyPilot runtime/database is migrated; the remote controller may still be running but the new client lacks the original global registry. | Supported: log in to the same endpoint and identity/workspace. |
| API-server process/pod restart | Local on-disk DB normally remains if the machine/runtime directory remains. | Database state remains with external PostgreSQL or the Helm persistent volume. Transient request/provision logs may not. |
| Stop legacy jobs controller | Controller disk and its database/logs are preserved; it autostops after ten idle minutes by default. | Not applicable in default consolidation mode. |
| Tear down legacy jobs controller | Finished managed-job status and all local managed-job logs are lost; teardown is blocked while jobs are active. | Not applicable. |
| Replace/delete API-server deployment | Local machine state is lost if its runtime directory is lost. | State survives only if the backing PostgreSQL database/PV and log PVC survive and are reattached. `storage.enabled=false` is documented as prone to data loss. |
| Stop compute cluster | Head disk, ordinary job queue, and logs are preserved for providers that support disk-preserving stop. | Same. |
| Tear down/lose compute cluster | Ordinary cluster-job queue and local logs disappear with the head filesystem. Managed-job summary state remains centrally, and managed jobs may have copied logs back before teardown. | Same. |

SkyPilot's official docs describe remote consolidation and legacy placement directly, including the legacy teardown loss guarantee. They also describe PostgreSQL as decoupling state from the API-server pod and the default Kubernetes persistent volume when no external database is supplied. ([managed-jobs architecture and teardown](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/examples/managed-jobs.rst#L610-L675), [API-server persistence](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/api-server/api-server-upgrade.rst#L12-L38), [managed-job log/file PVC](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/api-server/helm-values-spec.rst#L1276-L1296))

## Live queries versus history

- `sky status` reads the live cluster registry; `--refresh` reconciles it with provider state. Once a cluster is terminated, its live row is removed.
- `sky cost-report` reads `cluster_history`, including downed clusters. The default 30-day lookback is a filter. A cluster name can map to multiple launch records, while `cluster_hash` distinguishes them. The result is explicitly based on cached SkyPilot status and can be wrong for autostop, spot, or provider-console changes. ([cost history contract](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/core.py#L408-L475))
- `sky queue <cluster>` and `sky logs <cluster> <job-id>` query the live/stopped cluster head, not a central historical job store. ([cluster-job commands](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/job-queue.rst#L24-L48))
- `sky jobs queue` reads managed-job database rows, including terminal jobs, until their database is lost. The main rows are not subject to the seven-day log GC, though their detailed event stream is separately pruned after 30 days.
- `sky api status` is a request ledger, not a resource ledger. A request record can be GC'd while the cluster/job it launched keeps running. The docs explicitly make this distinction. ([request retention](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/config.rst#L315-L331))

## Identifier contract

| Identifier | Useful scope | Stability caveat |
|---|---|---|
| API request ID | One API-server operation; reattach/cancel/wait | Record and logs disappear after finished-request GC (one day by default). It is not the launched run's durable ID. |
| Cluster name | User-facing handle in one control plane | User supplied/reusable. Multiple historical launches can share it. |
| `cluster_hash` | One launch record in SkyPilot's state DB | Distinguishes name reuse, but is an internal SkyPilot history key, not a documented cross-system public run ID. |
| Cluster job ID | One head-node `jobs.db` | Autoincrement integer local to that cluster and lost with it. |
| Managed-job ID / `SKYPILOT_MANAGED_JOB_ID` | One managed-jobs database | Stable through recovery while that DB survives; can restart/reuse after controller/database recreation and is not globally unique. |
| `SKYPILOT_TASK_ID` | One managed-job task across its recoveries | Explicitly constructed once from run timestamp, task/job name, managed-job ID, and task index and re-injected on each recovery. Best built-in correlation value, but still derives from one SkyPilot control plane. ([controller implementation](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/jobs/controller.py#L265-L306)) |
| Provider instance/pod IDs | Provider diagnostics/billing | Kept inside backend handles and provider APIs; no single provider-independent stable run identifier is documented. Recoveries intentionally replace them. |

Skywright should generate its own immutable run UUID before submission, inject it into the task environment and labels/metadata, and record the returned SkyPilot request ID, managed-job ID, `SKYPILOT_TASK_ID`, cluster name/hash, and provider IDs as secondary correlations. That keeps identity stable across SkyPilot controller recreation and across managed-job recoveries.

## Retention and garbage collection

The defaults are not aligned into one historical-record promise:

- Finished API requests and their request/debug logs: **24 hours**, configurable; negative disables GC.
- API-server per-operation provisioning artifacts: **30 days**, configurable; existing-cluster provision logs are exempt while the cluster exists.
- Cluster status/debug events: **30 days**, configurable.
- Managed-job controller logs: **7 days**, configurable; negative disables GC.
- Managed-job task logs copied locally: **7 days**, configurable; external logging-store policy applies to externally forwarded logs.
- Managed-job transition events: **30 days**, hard-coded in the inspected source.
- Main managed-job rows: no deletion/retention daemon was found in current open-source source. They persist until the backing controller/API database is removed.
- Cluster-history rows: no row-retention daemon was found; `cost-report` applies a 30-day default query window.

The configurable values and their effect are documented in the official configuration reference. ([request, operation-log, and cluster-event GC](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/config.rst#L315-L385), [managed-job log GC](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/config.rst#L536-L593), [job-event daemon](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/sky/jobs/state.py#L4140-L4175))

## Metrics boundary

SkyPilot can expose Prometheus-format API health, cluster inventory, managed-job counts/statuses, and optionally federated GPU utilization/power metrics. Prometheus/Grafana is a separate deployed or externally managed storage/query layer; SkyPilot does not put those time series into the run-state database. ([exposed API metrics](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/api-server/examples/api-server-metrics-setup.rst#L60-L79), [Prometheus placement](https://github.com/skypilot-org/skypilot/blob/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4/docs/source/reference/api-server/examples/api-server-gpu-metrics-setup.rst#L144-L173))

No examined SkyPilot API provides a declared application-metric series store for values such as training loss or evaluation accuracy. Those belong in Skywright's Run Store/TensorBoard contract or an external tracker, correlated with the Skywright run UUID and `SKYPILOT_TASK_ID`.

## Operational recommendation for Skywright

1. Use a remote API server in consolidation mode, backed by externally managed PostgreSQL with backup/restore and a persistent log/file PVC. Do not depend on the legacy jobs controller for historical records.
2. Treat SkyPilot as the live scheduler/recovery authority only. Mirror lifecycle transitions into Skywright using the Skywright run UUID.
3. Store checkpoints and artifacts in an independently durable bucket/volume; store metrics in Skywright's TensorBoard-compatible Run Store or another durable metrics backend.
4. Record enough correlations for reconciliation: Skywright run UUID, SkyPilot endpoint/workspace/user, request ID, managed-job ID, `SKYPILOT_TASK_ID`, cluster name/hash, and provider resource IDs when available.
5. Configure log/request/event retention explicitly; defaults are too short for an audit trail. Export logs to an external store if they are part of the run record.
6. Reconcile live state against provider state and SkyPilot periodically. A missing/pruned API request must not be interpreted as a missing cluster or job.

## Could not establish

- No official guarantee was found that managed-job IDs remain globally unique across deletion/recreation of the jobs controller or API-server database; the schema uses database-local autoincrement integers.
- No supported migration procedure was found for moving a standalone local API server's full control state to a different client machine. The remote API server is the documented shared/multi-machine model.
- No configurable retention setting was found for the main managed-job rows or cluster-history rows. This is an absence in the inspected open-source source, not a promise that future versions will keep them forever.
- The Helm docs establish pod-restart persistence, but they do not constitute a backup/restore or disaster-recovery guarantee for the backing PostgreSQL database or persistent volume.
- No single normalized, public provider-resource identifier was found that survives managed-job recovery across AWS, GCP, Azure, Kubernetes, and the neocloud adapters.
- No SkyPilot-owned durable store was found for arbitrary application/training metrics.

## Source versions

- SkyPilot repository commit `d98316e963eac5fb7b5ae4f5c92335e19bc53ba4` (`master`, 2026-08-03), queried 2026-08-04.
- Latest stable release observed: SkyPilot `v0.13.0`, commit `b1431e52d97c22e9bb8fa8b67f162543754ddaf5` (2026-07-22). The note uses pinned current-source links because the issue asks about the current controller/API-server architecture; validate retention defaults again when pinning Skywright's SkyPilot version.
