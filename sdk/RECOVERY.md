# Run recovery

`run_training_process` prepares recovery automatically when its recorder is a
production `RunStoreRecorder`. The gate reads complete durable history before
publishing the next Execution Attempt Record. It selects a confirmed checkpoint,
checks its library state and validates its project version and Dataset ordering
before invoking the Training Project. Projects still register their state objects
and call `context.start()` to restore them before entering the training loop.

A new Run starts with Recovery Debt zero. Each subsequent admitted attempt adds
one; each distinct newly published checkpoint Step removes one, down to zero.
`maximum_recovery_debt` defaults to three and must be a positive integer. The first
journal event pins that maximum for the Run. Admission at the maximum is allowed.
A prospective attempt above it writes immutable Recovery Exhaustion evidence and
is refused. Publication failure also refuses startup.

## Previous writers

Recovery is unavailable by default. The runtime's `previous_writer_verifier` is a
trusted orchestration seam: given the previous Execution Attempt Record, it must
return `PreviousWriterEvidence` for that exact Run and attempt, or `None` when the
writer's status is uncertain. The evidence must name permanent process cessation
or removal of that attempt's storage write authority and an inspectable authority
reference. A process report, absent heartbeat, unreachable node, new UUID or
caller-supplied assertion cannot establish this fact.

The gate checks durable history again after verification because the previous
writer may have published progress or its final report during the check. A changed
head refuses this startup; a fresh process can reevaluate the latest history.
Terminal reports prevent same-Run recovery. A terminal Run can instead seed an
explicit clone with a new Run identity, `source_run_id`, and `resume_from`.
The initial admission pins the clone’s source Run, checkpoint Step/reference and
Ordering Reset choice. Until the clone publishes its own checkpoint, recovery
requires that same verified external seed; it never silently restarts from zero.
Once its own checkpoint is confirmed, recovery uses that state and clears the
source/Ordering Reset context. A seed factory is then left unevaluated, so retained
clone inputs do not require the original payload to remain available.

The local orchestration adapter in #56 owns trustworthy evidence or explicit
uncertainty. #231 owns assembly from the accepted Run Definition. Their absence
must not be bypassed by treating missing visibility as termination.

## Durable evidence

Under the Run Store's `v1/` prefix:

| Address | Meaning |
| --- | --- |
| `recovery/head.json` | Conditional head naming the committed journal digest. |
| `recovery/events/<sha256>.json` | Immutable attempt admission or checkpoint publication, linked to its predecessor digest. |
| `recovery/exhaustion.json` | Immutable maximum, prospective debt, history head, prior attempt identities, latest Durable Safe Point and exhaustion time. |

Each journal event records the Run, Project Version and pinned debt maximum.
Attempt events retain admission debt and previous-writer evidence. Checkpoint
events retain Step and durable reference; payload pruning never deletes them.
The Training Process configures automatic checkpoint retention from its Run
Configuration. After confirmation, the same checkpoint worker prunes behind a
fully verified newer protected checkpoint. It keeps the newest configured count,
optional every-Nth Steps and the confirmed final reference. Pruning shares the
publication cancellation control and shutdown wait. Failure preserves the
confirmed point and leaves excess objects; it is reported through the worker's
failure path instead of being treated as successful cleanup.
Replay verifies every addressed event and corresponding Execution Attempt Record.
A missing head with existing Run Store objects, missing event, corrupt digest,
conflicting identity, changed policy or invalid termination evidence fails closed.
Legacy stores without this journal cannot be silently recovered as empty Runs.

An attempt admission conditionally advances the head before publishing its
Execution Attempt Record. Project code starts only after both succeed. A crash
between those writes leaves incomplete history and refuses later admission.
Competing admissions cannot publish two accepted attempt records. Conditional
head updates do not replace previous-writer proof or claim to fence live writers.

Checkpoint publication uploads and verifies the payload, commits its immutable
journal evidence, then returns its durable reference. An interrupted upload or
uncommitted journal event cannot reduce debt or become a recovery candidate.
Identical checkpoint retries do not reduce debt twice. Readers may fall back from
a confirmed missing or corrupt checkpoint to a verified predecessor, preserving
rejection evidence. Incompatible state, permissions, timeouts and unavailable
history stop recovery. If no checkpoint has ever been confirmed, an admitted
recovery may restart from Step zero. If confirmed checkpoints exist but none can
be read safely, it refuses startup.

Reads and publication have explicit resource bounds: an event or attempt/report
record is at most 64 KiB; complete journal history is at most 16 MiB and 100,000
events. Exhaustion evidence may use up to 16 MiB. Exceeding a bound is a history
capacity failure, not Recovery Exhaustion. These bounds also limit retained
metadata in a live recorder. Raising them requires qualifying the workload's
memory budget. Checkpoint payload staging retains its separate Run Store limits.

## Startup refusal and outcomes

`RecoveryAdmissionError` represents refusal before opening an attempt. It does not
create an Execution Termination Report or diagnose the previous process's cause.
Its code distinguishes uncertain writers, exhausted recovery, terminal Runs,
invalid or changed history, incompatible state and unavailable storage. A refused
runtime invocation prints a `startup-refused` diagnostic and exits with code 1.

Exit code 75 remains reserved for cooperative interruption after the final
checkpoint, observations, progress and termination report are durable. A failed
finalization emits terminal failure. Abrupt loss may produce no runtime outcome.

## Verification

`tests/unit/test_recovery.py` exercises debt, publication idempotence, retention,
history corruption, conditional admission, lost responses, terminal reports,
policy pinning, invalid writer proof and stale exhaustion prevention.

`tests/integration/test_recovery_process.py` builds and installs a wheel outside
the source tree, asserts the imported package comes from that installation, and
runs fresh processes against real S3-compatible storage. Its parent supervisor
supplies stopped-writer evidence only after reaping the named child. The private
fixture file carrying that observation is not a production evidence mechanism.
The tests suspend and resume a writer around admission, progress, checkpoint
publication and pruning; kill a multipart upload; exhaust repeated recoveries;
remove or corrupt the latest checkpoint; and inject credential, timeout and report
publication failures. Outcome 75 is checked only after successful finalization.

## Qualified local writer authority

The optional local Kubernetes deployment can enable
`skywright.local-run.writer-authority-enabled=true`. Its managed task receives a
fixed read-only Unix socket mount and selects `EAGER_NEXT_REGION`, which tears
down the old cluster before replacement. The SDK uses only the local protocol;
it gains no Kubernetes, Vault or SkyPilot dependency or credential.

The node authority registers the exact process/container before attempt
publication. It independently proves permanent container death under the qualified
containerd and Linux custody rules and retains an inspectable proof. The SDK waits
up to 60 seconds only for explicit pending teardown, with at most 10 seconds per
call. Missing registration, invalid evidence and unavailable authority still refuse
startup before project entry. With the option absent, the original unavailable
recovery default remains.

See [deployment and qualification instructions](../deployment/examples/local-writer/README.md)
for node enrollment, persistent custody, required capabilities and unsupported
restart/mount configurations. A changed kernel boot or lost runtime evidence
before proof is retained remains uncertainty in this first implementation.
