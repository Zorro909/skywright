---
status: accepted
---

# Separate safe points from execution termination

A **Safe Point** is a committed-state boundary, not a signal or a way for a process to end. It exists only after a Training Project commits a Step, and it becomes a **Durable Safe Point** only once a checkpoint for that Step is confirmed published in the Run Store. This refines ADR 0001's Step contract, ADR 0008's checkpoint protocol, and ADR 0005's termination model so that logical progress, remote durability, process lifetimes, and Run outcomes cannot be mistaken for one another.

## Safe-point guarantees

An ordinary Safe Point commits the Step, advances the Dataset Cursor, and makes its Step-scoped observations eligible for flushing. It does not promise remote durability. When checkpoint cadence is due, the Run Context captures an immutable snapshot synchronously before project state can mutate again, then publishes it in the background as ADR 0008 requires. Until publication is confirmed, recovery still begins at the previous Durable Safe Point. The Progress Record carries both the current Step and latest Durable Safe Point so this lag is visible; checkpoint cadence is therefore a target rather than a hard bound on replay after abrupt compute loss.

A Run must commit at least one Step to complete successfully. Returning without one is a `contract_violation` and creates no checkpoint.

| Path | Checkpoint guarantee | Run effect |
|---|---|---|
| Ordinary Safe Point | Logical commit only; a cadence snapshot may still be uploading | Continues |
| Clean completion | The final committed Step is synchronously published as a Durable Safe Point | Finished |
| Cooperative interruption | The last committed Step is synchronously published as a Durable Safe Point | Recoverable in a new Execution Attempt of the same Run |
| Abrupt death | Only the latest previously confirmed Durable Safe Point survives | Derived from retained SkyPilot facts and any attempt evidence |
| Cooperative or forced cancellation | Cancellation creates no checkpoint | Cancelled |
| Contract violation, Training Project failure, or Skywright failure | Failure creates no checkpoint | Failed |

The clean-completion checkpoint is part of `C6` retention but is protected as the checkpoint on which the finished Run ended. A Run may report success only after that checkpoint, its final committed metrics and progress, and its termination report are durable.

## Execution Attempts and reports

One SkyPilot-managed Run may start the Training Project process several times while recovering infrastructure. Each process lifetime is therefore an **Execution Attempt**, not a Run. Before project code executes, the Run Context publishes an immutable Execution Attempt Record under a library-generated UUID, identifying the Run and the checkpoint from which the attempt starts. If that record cannot become durable, training does not begin. A record without a termination report proves only that the attempt started and did not get to report; it never proves why the process disappeared.

An attempt that terminates cooperatively writes one atomic, immutable Execution Termination Report beneath its attempt identity. Retrying the identical publication is idempotent; replacing it is forbidden. It is the last Run Store write made by that attempt, not the final write to the Run Store, because a recovered attempt continues the same Run. The report carries a schema version, Run and attempt identities, the canonical Execution Termination Cause, the last committed Step, the latest Durable Safe Point and checkpoint reference when present, and structured cause-specific diagnostics.

The closed cause vocabulary is:

- `completed` — normal completion, including project-owned early stopping after at least one Step;
- `cancelled` — a Cancellation Request was cooperatively honored;
- `interrupted` — a recoverable Interruption Request was cooperatively honored and its last committed Step became a Durable Safe Point;
- `contract_violation` — Skywright rejected misuse of the Training Contract;
- `training_project_failure` — project code ended with a catchable unrecoverable error; and
- `skywright_failure` — the library or finalization path failed.

Provider events, exception types, rule identifiers, and failure stages are diagnostic details rather than new causes. In particular, a generic signal is never labelled `preempted`: SkyPilot cannot prove that cause. A report may be absent after a hard kill, segmentation fault, out-of-memory termination, or failure to publish the report itself; absence remains non-diagnostic.

For `completed` and `interrupted`, the report's last committed Step and Durable Safe Point must be equal. Cancellation and failures may report a newer committed Step than the newest durable one. If nominal completion cannot finish its durability barrier, it becomes `skywright_failure`; if even that report cannot be published, the report remains absent and the original evidence is preserved elsewhere.

## Termination behavior

Step-scoped project metrics and Step-indexed system metrics remain provisional until their Step commits and are discarded with an uncommitted Step. Wall-clock system metrics remain as failure evidence. Samples and Artifacts whose explicit persistence operations succeeded are independent Run Store outputs and remain visible even when their surrounding Step fails. Contract violations and unrecoverable errors never trigger a checkpoint; previously committed observations receive only a best-effort final flush, and failure reporting must not replace the original error.

Cancellation is terminal and distinct from recoverable interruption, so `cancelled` becomes a sixth Run Lifecycle State and the previously used, undefined word `aborted` is retired. A Cancellation Request first asks the project to stop at its next Safe Point without checkpointing. After bounded grace it escalates to forced SkyPilot cancellation so resource release never waits indefinitely for project cooperation. A cooperative cancellation writes its report; a forced one may not, in which case the retained orchestrator cancellation fact still derives `cancelled`. Retrying a cancelled or otherwise terminal Run remains ADR 0008's checkpoint-seeded clone with a new Run Record.

When terminal paths race, finalization failures override nominal causes, contract or Training Project failures retain their own cause, valid completion beats a request that arrived too late, and cancellation beats interruption because explicit user intent must prevent automatic recovery. Publication of an Execution Termination Report closes that attempt; later requests against it are no-ops.
