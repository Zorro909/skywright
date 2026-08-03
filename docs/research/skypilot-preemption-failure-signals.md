# How SkyPilot signals preemption versus a real failure

## Answer

SkyPilot does **not** expose a reliable, provider-independent public signal that means "this interruption was a spot preemption, not any other failure." Managed Jobs instead makes an operational distinction:

- if the temporary cluster is no longer `UP`, SkyPilot treats it as "preempted or failed" and starts infrastructure recovery;
- if the cluster is still `UP` and the job has failed, SkyPilot treats it as a user-program/setup/driver failure and, by default, does not restart it.

That boundary gives the desired result for the ordinary case—an instance disappears versus Python exits nonzero—but it is intentionally broader than preemption. It groups spot preemption with node loss, GPU/hardware faults, an unhealthy runtime, and some status-fetch failures. It also cannot detect a semantic training failure such as a NaN unless the training program detects it and exits nonzero (preferably with an application-owned exit code).

The durable evidence is split across the managed-job record, job events, task logs, and controller logs. `RECOVERING` alone is not proof of preemption, and SkyPilot's process exit code is not useful for that distinction: all managed-job failure statuses collapse to exit code `100`.

## Version scope

- Primary baseline: **SkyPilot v0.13.0**, commit [`b1431e52`](https://github.com/skypilot-org/skypilot/tree/b1431e52d97c22e9bb8fa8b67f162543754ddaf5), released 2026-07-22. This was the latest non-RC release found on 2026-08-04.
- Cross-check: **v0.13.1rc1**, commit [`0de34afe`](https://github.com/skypilot-org/skypilot/tree/0de34afe2b1cb6ffdde57585818e2aa680a475eb), released 2026-07-24, and `master` commit [`d98316e9`](https://github.com/skypilot-org/skypilot/tree/d98316e963eac5fb7b5ae4f5c92335e19bc53ba4) from 2026-08-03.
- The release-candidate/master delta matters: those revisions add an internal `RecoverySource` that distinguishes failure-driven recovery from controller emergency/restart recovery. `FAILURE` still combines preemption, node failure, and retryable user-code failure, and the public job-events read path does not return the stored `recovery_source` field. It therefore does not solve exact preemption classification. ([RC enum](https://github.com/skypilot-org/skypilot/blob/0de34afe2b1cb6ffdde57585818e2aa680a475eb/sky/jobs/state.py#L731-L755), [RC event write/read paths](https://github.com/skypilot-org/skypilot/blob/0de34afe2b1cb6ffdde57585818e2aa680a475eb/sky/jobs/state.py#L3955-L4047))

## What a caller can observe

### Managed-job status and record

`sky jobs queue`, `sky.jobs.queue_v2()`, and the API return a `ManagedJobStatus`. The relevant states are:

- `RECOVERING`: SkyPilot is relaunching/failing over the cluster;
- `FAILED`: the user's program failed;
- `FAILED_SETUP`: setup failed, including a deterministic pod OOM during cluster/runtime setup;
- `FAILED_PRECHECKS`: invalid/infeasible configuration or credentials/precheck failure;
- `FAILED_NO_RESOURCE`: launch attempts exhausted in a bounded launch path;
- `FAILED_CONTROLLER`: unexpected controller failure.

These are useful terminal categories, but `RECOVERING` is not a cause code. The v0.13.0 implementation describes it as preemption recovery while the controller actually enters it for a cluster that is "preempted or failed" and for recovery after losing reliable job status. ([status enum](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L475-L557), [controller branch](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/controller.py#L900-L961), [recovery transition](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/controller.py#L1130-L1165))

The queue record can retain `status`, `recovery_count`, `details`, `failure_reason`, timestamps, and infrastructure fields. The normal SDK result documents status and recovery count; the response model contains the richer fields. It does **not** expose the underlying program exit code. ([SDK queue result](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/client/sdk.py#L169-L236), [record model](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/schemas/api/responses.py#L191-L225))

### Exit codes

There are two different exit-code layers:

1. **User-program exit codes.** The controller retrieves per-node exit codes after a failed job on an otherwise healthy cluster. They drive `recover_on_exit_codes` and `max_restarts_on_errors`, but they are not a preemption code and are not part of `ManagedJobRecord`. ([controller decision](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/controller.py#L1007-L1047))
2. **SkyPilot job-operation exit codes.** Waiting/tailing returns `0` for success, `100` for any failure, `101` not finished, `102` not found, and `103` cancelled. Every managed-job failure status maps to `100`, so this surface cannot distinguish preemption, OOM, setup failure, or controller failure. ([`JobExitCode`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/exceptions.py#L638-L712))

Exit code `137` is especially unsafe as a caller-defined recovery marker: SkyPilot documents that it uses `137` internally and warns not to place it in `recover_on_exit_codes`. ([managed-jobs docs](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/examples/managed-jobs.rst#L277-L303))

### Job events and cluster events

v0.13.0 stores a managed-job event audit row with `new_status`, optional `code`, human-readable `reason`, and `timestamp`. A `/jobs/events` API route reads those rows and can best-effort merge cluster status/provisioning events. ([event schema/read](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L211-L228), [event records](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L3535-L3622), [API route](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/server/server.py#L294-L307), [cluster-event merge](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/server/core.py#L1881-L1922))

The event detail is conditional:

- if a provider/plugin supplies structured external failures, their codes and reasons are recorded;
- otherwise the latest cluster status-change reason is used;
- otherwise the event says only `Cluster preempted or failed, recovering`.

Thus an event may carry a strong provider-specific clue such as `OOMKilled`, but the fallback is explicitly ambiguous. ([event construction](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L2906-L2931))

There is also a v0.13.0 failure mode worth preserving in the architectural record: if `max_restarts_on_errors` or `recover_on_exit_codes` restarts a user-code failure, the controller logs that the user program crashed and then falls through to the same recovery transition with no user-failure reason. The job event can consequently contain the generic `Cluster preempted or failed` text even though the trigger was a user exit. This is verified from the control flow, not merely a hypothetical. ([user-failure fallthrough](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/controller.py#L1013-L1047), [generic event fallback](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L2919-L2930))

### Logs and debug dump

- `sky jobs logs <job_id>` exposes cached user setup/run output.
- `sky jobs logs --controller <job_id>` exposes provisioning, monitoring, preemption/recovery attempts, and the controller's user-failure/retry messages. SkyPilot's own docs direct users to the queue for a brief reason and controller logs for provisioning detail. ([official workflow](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/examples/managed-jobs.rst#L165-L193), [failure/recovery logs](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/examples/managed-jobs.rst#L306-L324))
- `sky debug-dump -j <job_id>` creates a troubleshooting archive. For managed jobs it includes job DB information, up to 1,000 job events, available run/controller logs, cluster history/events, and provisioning logs; cluster history and events can outlive the terminated worker cluster. ([CLI](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/cli/command.py#L8420-L8471), [managed-job manifest](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/utils.py#L1039-L1085), [terminated-cluster evidence](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/utils.py#L1115-L1149))

Logs are the richest diagnostic surface, but parsing prose logs is heuristic and version-sensitive. The `/jobs/events` endpoint is structured, but no public high-level `sky.jobs.events()` SDK function or documented stability guarantee was found in v0.13.0.

## How reliable is the distinction?

### Verified behavior

The core decision is cluster health first:

- **cluster not `UP`** -> recover as preemption/failure;
- **cluster `UP`, job `FAILED`/`FAILED_SETUP`/`FAILED_DRIVER`** -> user failure, terminal by default;
- **cluster `UP`, job status unavailable long enough** -> attempt recovery rather than report a definite program failure.

This is verified directly in the controller implementation. ([controller classification](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/controller.py#L900-L1068))

An OOM may therefore land on either side depending on how it manifests:

- a remote job driver OOM/driver death while the cluster stays healthy is classified `FAILED`, and is not retried by default;
- an OOM during cluster/runtime setup is `FAILED_SETUP` and treated as deterministic;
- a pod/node failure that makes the cluster unhealthy enters infrastructure recovery, although a Kubernetes reason such as `OOMKilled` may be preserved in details/events.

The first two are explicitly documented in source comments and statuses. The third follows from the cluster-first branch and the event-reason plumbing. ([driver OOM classification](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/controller.py#L981-L1005), [setup OOM status](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L539-L544))

### Inferences and limits

- **NaN is invisible to SkyPilot.** The recovery code observes cluster status, SkyPilot job status, and process exit codes—not model tensors or loss values. If training exits successfully with NaNs, SkyPilot can report `SUCCEEDED`; if the application raises/exits nonzero, SkyPilot treats it as user failure. This is an inference from the exhaustive decision inputs in the controller.
- **Ordinary bugs are distinguished only insofar as they produce a user-code failure while the cluster remains healthy.** A bug that kills or wedges the runtime strongly enough to make the cluster unhealthy can be classified on the infrastructure-recovery side.
- **Provider detail is best-effort.** SkyPilot's generic category is "preempted or failed." Exact provider coverage and fidelity of external failure codes varies by backend/plugin and was not established as a cross-cloud contract.
- **A recovery count is not a preemption count.** In v0.13.0 every completed recovery transition increments it. In v0.13.1rc1/master it excludes controller emergency/restart recovery, but its `FAILURE` bucket still includes preemption, node failure, and configured user-code retry. ([v0.13.0 increment](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L2980-L3005), [RC source categories](https://github.com/skypilot-org/skypilot/blob/0de34afe2b1cb6ffdde57585818e2aa680a475eb/sky/jobs/state.py#L731-L755))

## What Managed Jobs recovers, and what remains application-owned

Managed Jobs owns infrastructure recovery: it tears down the old temporary cluster, finds/provisions another resource (normally trying other regions/infra), resubmits setup/run commands, monitors it, and cleans resources up afterward. Hardware faults, node crashes, and preemptions are auto-recovered by default. User-code failures are not. ([official recovery table](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/examples/managed-jobs.rst#L306-L324))

SkyPilot restarts the program from scratch after infrastructure recovery. The application/library owns checkpoint creation in durable storage, checkpoint validation, selection of the latest valid checkpoint, and restoring optimizer/model/RNG/data-loader progress. Local disk data not persisted outside the temporary worker may be lost. ([checkpointing contract](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/examples/managed-jobs.rst#L332-L362))

SkyPilot also does not decide that a NaN is fatal, whether an OOM should be retried with different resources, or whether a checkpoint is safe to resume. Those remain application/library semantics.

## Default and configurable recovery attempts

There are separate budgets:

| Failure class | Default | Configuration / bound |
|---|---|---|
| User code exits nonzero | **0 restarts** | `resources.job_recovery.max_restarts_on_errors: N` gives N restarts, N+1 total attempts. |
| Exit code listed in `recover_on_exit_codes` | Not enabled | Always restarts; these restarts do not consume `max_restarts_on_errors`, so no overall limit is documented. Do not use `137`. |
| Preemption / hardware / unhealthy cluster | Auto-recover | No user-configurable overall attempt cap is documented. Recovery loops until a cluster can be relaunched or a non-retryable/precheck/controller failure terminates the job. |
| Capacity during normal initial launch | Retry-until-available behavior depends on path | Docs say capacity search across infra is indefinite for managed jobs; bounded internal launch paths can yield `FAILED_NO_RESOURCE`. |
| Invalid/auth/infeasible precheck | No retry | Terminal `FAILED_PRECHECKS`. |

The defaults and application-error controls are explicit in docs and source. ([docs](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/examples/managed-jobs.rst#L260-L320), [default `0`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/recovery_strategy.py#L247-L280), [counter logic](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/recovery_strategy.py#L864-L893))

Internally, the default `EAGER_NEXT_REGION` strategy performs batches of up to 240 relaunch tries (commented as four hours), then its surrounding `while True` starts another batch. This is an implementation detail, not a documented user-facing maximum. ([strategy default and loop](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/recovery_strategy.py#L896-L901), [eager recovery](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/recovery_strategy.py#L1017-L1099))

## What remains after a run ends

As long as the jobs controller/API-server state remains intact, a finished run can leave:

- its terminal status, timestamps, recovery count, details/failure reason, resource/infrastructure metadata, and submitted YAML in the managed-jobs database;
- job events for **30 days** by a hard-coded v0.13.0 retention daemon;
- cached task and controller logs for **7 days by default**, independently configurable with `jobs.controller.task_logs_gc_retention_hours` and `controller_logs_gc_retention_hours`; negative values disable each log GC;
- cluster history/events and provisioning logs, available to the debug-dump path subject to their own retention and file availability.

([record model](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/schemas/api/responses.py#L191-L225), [30-day event retention](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L50-L55), [event GC](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/jobs/state.py#L3684-L3719), [log retention config](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/reference/config.rst#L529-L586))

This evidence is controller-scoped, not an immutable run archive. SkyPilot explicitly warns that tearing down the jobs controller loses all logs and status information for finished managed jobs. ([controller lifecycle warning](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/examples/managed-jobs.rst#L651-L664))

## Facts relevant to R6 and O5

- A caller may safely treat terminal `FAILED`, `FAILED_SETUP`, `FAILED_PRECHECKS`, `FAILED_NO_RESOURCE`, and `FAILED_CONTROLLER` as distinct broad failure outcomes, but cannot infer an exact preemption from `RECOVERING` or `recovery_count` alone.
- The ordinary no-retry-on-program-error behavior is already SkyPilot's default; exact application error meaning (especially NaN) must come from the training process.
- Post-hoc diagnosis must collect the structured record/events plus task/controller logs before their retention windows expire or the controller is destroyed. Provider-specific event reasons improve diagnosis but are not a portable preemption contract.
- If a downstream contract requires a durable exact cause such as `PREEMPTED` versus `OOM` versus `NAN` versus `BUG`, the examined SkyPilot public surfaces do not supply that taxonomy on their own.

## Could not establish

- A documented, stable public SDK for managed-job events. The REST route exists in source, but no `sky.jobs.events()` client API or compatibility promise was found.
- A portable enumeration of provider-specific preemption/event codes across every cloud and Kubernetes integration.
- A supported setting for the 30-day managed-job event retention; unlike log retention, it is hard-coded in the examined release.
- A user-facing overall maximum for infrastructure/preemption recoveries. The source loops indefinitely in the default recovery strategy, while bounded sub-attempts and terminal precheck/controller paths still exist.
